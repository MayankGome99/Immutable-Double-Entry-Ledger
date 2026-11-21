package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.ExchangeRateSnapshot;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.ExchangeRateSnapshotRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.*;

@Service
public class ExchangeRateService {

    private final ExchangeRateSnapshotRepository rateRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerService ledgerService;

    public ExchangeRateService(ExchangeRateSnapshotRepository rateRepository,
                               AccountRepository accountRepository,
                               LedgerEntryRepository entryRepository,
                               LedgerService ledgerService) {
        this.rateRepository = rateRepository;
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.ledgerService = ledgerService;
    }

    public ExchangeRateSnapshot saveRateSnapshot(String base, String quote, BigDecimal rate,
                                                 String source, int validMinutes) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Exchange rate must be strictly positive.");
        }
        ZonedDateTime now = ZonedDateTime.now();
        ExchangeRateSnapshot snapshot = new ExchangeRateSnapshot();
        snapshot.setBaseCurrency(base.toUpperCase());
        snapshot.setQuoteCurrency(quote.toUpperCase());
        snapshot.setRate(rate);
        snapshot.setInverseRate(BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP));
        snapshot.setSource(source == null || source.isBlank() ? "INTERNAL" : source);
        snapshot.setCapturedAt(now);
        snapshot.setValidFrom(now);
        snapshot.setValidUntil(now.plusMinutes(validMinutes));
        return rateRepository.save(snapshot);
    }

    public ExchangeRateSnapshot getValidRate(String baseCurrency, String quoteCurrency) {
        ExchangeRateSnapshot snapshot = rateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByCapturedAtDesc(
                        baseCurrency.toUpperCase(), quoteCurrency.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No exchange rate snapshot found for " + baseCurrency + "/" + quoteCurrency));

        ZonedDateTime now = ZonedDateTime.now();
        if (snapshot.getValidUntil() != null && now.isAfter(snapshot.getValidUntil())) {
            throw new IllegalArgumentException(
                    "Stale exchange rate rejected for " + baseCurrency + "/" + quoteCurrency
                            + ". Expired at: " + snapshot.getValidUntil());
        }
        return snapshot;
    }

    @Transactional
    public Map<String, Object> convertUsdToInr(BigDecimal usdAmount, String createdBy) {
        if (usdAmount == null || usdAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Conversion amount must be greater than zero.");
        }

        ExchangeRateSnapshot snapshot = getValidRate("USD", "INR");
        BigDecimal grossInr = usdAmount.multiply(snapshot.getRate()).setScale(4, RoundingMode.HALF_UP);
        BigDecimal fxMarkup = grossInr.multiply(new BigDecimal("0.005")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal netInr = grossInr.subtract(fxMarkup);

        Account usdWallet = findByCode("1002");
        Account fxHoldingUsd = findByCode("1005");
        Account fxHoldingInr = findByCode("1004");
        Account inrWallet = findByCode("1001");
        Account fxRevenue = findByCode("4003");

        String actor = (createdBy == null || createdBy.isBlank()) ? "FX_ENGINE" : createdBy;

        UUID usdJournalId = ledgerService.postTransaction(Arrays.asList(
                buildEntry(usdWallet, "CREDIT", usdAmount, "USD", actor),
                buildEntry(fxHoldingUsd, "DEBIT", usdAmount, "USD", actor)
        ));

        UUID inrJournalId = ledgerService.postTransaction(Arrays.asList(
                buildEntry(fxHoldingInr, "DEBIT", grossInr, "INR", actor),
                buildEntry(inrWallet, "CREDIT", netInr, "INR", actor),
                buildEntry(fxRevenue, "CREDIT", fxMarkup, "INR", actor)
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", snapshot.getSnapshotId().toString());
        result.put("rateApplied", snapshot.getRate());
        result.put("usdAmount", usdAmount);
        result.put("grossInr", grossInr);
        result.put("fxRevenueMarkupInr", fxMarkup);
        result.put("netInrCredited", netInr);
        result.put("usdJournalId", usdJournalId.toString());
        result.put("inrJournalId", inrJournalId.toString());
        return result;
    }

    public Map<String, Object> getCurrencyExposureReport() {
        List<Account> allAccounts = accountRepository.findAll();
        Map<String, BigDecimal> assetsByCurrency = new LinkedHashMap<>();
        Map<String, BigDecimal> liabilitiesByCurrency = new LinkedHashMap<>();

        // Net FX position = Assets - Liabilities per currency (excludes internal FX clearing transit 1005 and Equity/P&L)
        for (Account acc : allAccounts) {
            String type = acc.getAccountType();
            if (!"Asset".equalsIgnoreCase(type) && !"Liability".equalsIgnoreCase(type)) {
                continue;
            }
            if ("1005".equals(acc.getAccountCode())) {
                continue;
            }
            String curr = acc.getCurrency().trim().toUpperCase();
            BigDecimal bal = entryRepository.calculateBalanceByCurrency(acc.getId(), curr);
            if ("Asset".equalsIgnoreCase(type)) {
                assetsByCurrency.merge(curr, bal, BigDecimal::add);
                liabilitiesByCurrency.putIfAbsent(curr, BigDecimal.ZERO);
            } else {
                liabilitiesByCurrency.merge(curr, bal, BigDecimal::add);
                assetsByCurrency.putIfAbsent(curr, BigDecimal.ZERO);
            }
        }

        List<Object> exposures = new ArrayList<>();
        BigDecimal grandTotalInr = BigDecimal.ZERO;

        for (String currency : assetsByCurrency.keySet()) {
            BigDecimal assets = assetsByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal liabilities = liabilitiesByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal netExposure = assets.subtract(liabilities);
            BigDecimal inrEquivalent = netExposure;
            BigDecimal appliedRate = BigDecimal.ONE;

            if (!"INR".equals(currency)) {
                if (netExposure.compareTo(BigDecimal.ZERO) != 0) {
                    ExchangeRateSnapshot validSnap = getValidRate(currency, "INR");
                    appliedRate = validSnap.getRate();
                    inrEquivalent = netExposure.multiply(appliedRate).setScale(4, RoundingMode.HALF_UP);
                } else {
                    appliedRate = BigDecimal.ZERO;
                    inrEquivalent = BigDecimal.ZERO;
                }
            }
            grandTotalInr = grandTotalInr.add(inrEquivalent);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("currency", currency);
            row.put("assets", assets);
            row.put("liabilities", liabilities);
            row.put("netExposure", netExposure);
            row.put("rateToInr", appliedRate);
            row.put("inrEquivalent", inrEquivalent);
            exposures.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report", "Currency Exposure Report");
        report.put("exposures", exposures);
        report.put("totalExposureInr", grandTotalInr);
        report.put("generatedAt", ZonedDateTime.now().toString());
        return report;
    }

    private Account findByCode(String code) {
        return accountRepository.findAll().stream()
                .filter(a -> code.equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account code not found: " + code));
    }

    private LedgerEntry buildEntry(Account account, String type, BigDecimal amount, String currency, String actor) {
        LedgerEntry e = new LedgerEntry();
        e.setAccount(account);
        e.setEntryType(type);
        e.setAmount(amount);
        e.setCurrency(currency);
        e.setCreatedBy(actor);
        return e;
    }
}