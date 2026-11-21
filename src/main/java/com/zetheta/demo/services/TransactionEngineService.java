package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.FeeRefundPolicy;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.models.TransactionType;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.validators.TransactionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class TransactionEngineService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerService ledgerService;
    private final ExchangeRateService exchangeRateService;
    private final TransactionValidator validator;
    private final JdbcTemplate jdbcTemplate;

    private final BigDecimal defaultFeeRate;
    private final BigDecimal defaultTaxRate;
    private final BigDecimal defaultPartialRefundRatio;

    public TransactionEngineService(AccountRepository accountRepository,
                                    LedgerEntryRepository entryRepository,
                                    LedgerService ledgerService,
                                    ExchangeRateService exchangeRateService,
                                    TransactionValidator validator,
                                    JdbcTemplate jdbcTemplate,
                                    @Value("${ledger.fees.default-rate:0.01}") BigDecimal defaultFeeRate,
                                    @Value("${ledger.fees.tax-rate:0.005}") BigDecimal defaultTaxRate,
                                    @Value("${ledger.refunds.default-partial-ratio:0.50}") BigDecimal defaultPartialRefundRatio) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.ledgerService = ledgerService;
        this.exchangeRateService = exchangeRateService;
        this.validator = validator;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultFeeRate = defaultFeeRate;
        this.defaultTaxRate = defaultTaxRate;
        this.defaultPartialRefundRatio = defaultPartialRefundRatio;
    }

    @Transactional
    public Map<String, Object> processTypedTransaction(TransactionType type, BigDecimal amount, String createdBy) {
        validator.validate(type, amount, createdBy);
        String actor = createdBy.trim();

        if (type == TransactionType.FX_CONVERSION) {
            return exchangeRateService.convertUsdToInr(amount, actor);
        }

        BigDecimal fee = amount.multiply(defaultFeeRate).setScale(4, RoundingMode.HALF_UP);
        BigDecimal tax = amount.multiply(defaultTaxRate).setScale(4, RoundingMode.HALF_UP);
        BigDecimal netAfterFee = amount.subtract(fee);
        BigDecimal netAfterTax = amount.subtract(tax);

        List<LedgerEntry> entries = new ArrayList<>();

        switch (type) {
            case DEPOSIT_BANK -> {
                entries.add(buildEntry("1001", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("2001", "CREDIT", amount, "INR", actor));
            }
            case DEPOSIT_CARD -> {
                entries.add(buildEntry("1001", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("2001", "CREDIT", amount, "INR", actor));
                entries.add(buildEntry("5001", "DEBIT", fee, "INR", actor));
                entries.add(buildEntry("2002", "CREDIT", fee, "INR", actor));
            }
            case WITHDRAWAL_BANK -> {
                entries.add(buildEntry("2001", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
            }
            case P2P_TRANSFER -> {
                entries.add(buildEntry("2001", "DEBIT", amount.add(fee), "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", fee, "INR", actor));
            }
            case MERCHANT_PAYMENT_QR -> {
                entries.add(buildEntry("1001", "DEBIT", amount.add(fee).add(tax), "INR", actor));
                entries.add(buildEntry("2002", "CREDIT", amount, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", fee, "INR", actor));
                entries.add(buildEntry("2020", "CREDIT", tax, "INR", actor));
            }
            case MERCHANT_PAYMENT_ONLINE -> {
                entries.add(buildEntry("1001", "DEBIT", amount.add(fee), "INR", actor));
                entries.add(buildEntry("2002", "CREDIT", amount, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", fee, "INR", actor));
                entries.add(buildEntry("5001", "DEBIT", tax, "INR", actor));
                entries.add(buildEntry("1010", "CREDIT", tax, "INR", actor));
            }
            case BILL_PAYMENT -> {
                entries.add(buildEntry("1001", "DEBIT", amount.add(fee), "INR", actor));
                entries.add(buildEntry("2002", "CREDIT", amount, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", fee, "INR", actor));
            }
            case INTEREST_ACCRUAL -> {
                entries.add(buildEntry("5003", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("2010", "CREDIT", amount, "INR", actor));
            }
            case INTEREST_PAYOUT -> {
                entries.add(buildEntry("2010", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", netAfterTax, "INR", actor));
                entries.add(buildEntry("2020", "CREDIT", tax, "INR", actor));
            }
            case FEE_DEDUCTION -> {
                entries.add(buildEntry("1001", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", amount, "INR", actor));
            }
            case CASHBACK_CREDIT -> {
                entries.add(buildEntry("5002", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
            }
            case PROMOTIONAL_CREDIT -> {
                entries.add(buildEntry("5004", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
            }
            case LOAN_DISBURSEMENT -> {
                entries.add(buildEntry("1020", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", netAfterFee, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", fee, "INR", actor));
            }
            case LOAN_EMI_PAYMENT -> {
                entries.add(buildEntry("1001", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1020", "CREDIT", netAfterFee, "INR", actor));
                entries.add(buildEntry("4002", "CREDIT", fee, "INR", actor));
            }
            case REFUND_FULL -> {
                entries.add(buildEntry("2002", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
            }
            case REFUND_PARTIAL -> {
                BigDecimal partialAmt = amount.multiply(defaultPartialRefundRatio).setScale(4, RoundingMode.HALF_UP);
                entries.add(buildEntry("2002", "DEBIT", partialAmt, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", partialAmt, "INR", actor));
            }
            case CHARGEBACK -> {
                entries.add(buildEntry("1010", "DEBIT", amount.add(fee), "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
                entries.add(buildEntry("4001", "CREDIT", fee, "INR", actor));
            }
            case REWARD_POINTS_REDEMPTION -> {
                entries.add(buildEntry("2030", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
            }
            case ACCOUNT_CLOSURE_SWEEP -> {
                entries.add(buildEntry("2001", "DEBIT", amount, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", amount, "INR", actor));
            }
            default -> throw new IllegalArgumentException("Unsupported transaction type: " + type);
        }

        UUID journalId = ledgerService.postTransaction(entries);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "POSTED");
        response.put("transactionType", type.name());
        response.put("journalId", journalId.toString());
        response.put("amount", amount);
        response.put("entriesCount", entries.size());
        return response;
    }

    /**
     * Backward-compatible signature: client-supplied originalAmount and originalFee are intentionally
     * ignored and always derived authoritatively from persisted ledger_entries.
     */
    @Transactional
    public Map<String, Object> processPartialRefund(UUID originalJournalId,
                                                    BigDecimal ignoredClientOriginalAmount,
                                                    BigDecimal ignoredClientOriginalFee,
                                                    BigDecimal refundAmount,
                                                    FeeRefundPolicy feePolicy,
                                                    String idempotencyKey,
                                                    String createdBy) {
        return processPartialRefund(originalJournalId, refundAmount, feePolicy, idempotencyKey, createdBy);
    }

    @Transactional
    public Map<String, Object> processPartialRefund(UUID originalJournalId,
                                                    BigDecimal refundAmount,
                                                    FeeRefundPolicy feePolicy,
                                                    String idempotencyKey,
                                                    String createdBy) {
        if (originalJournalId == null || refundAmount == null) {
            throw new IllegalArgumentException("originalJournalId and refundAmount are required.");
        }
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required for partial refunds.");
        }

        // 1. Serialize all concurrent refund requests targeting the same originalJournalId
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "PARTIAL_REFUND:" + originalJournalId);

        // 2. Verify original journal exists and is not itself a reversal or refund
        List<LedgerEntry> originalEntries = entryRepository.findByJournalId(originalJournalId);
        if (originalEntries.isEmpty()) {
            throw new IllegalArgumentException("Original journal not found: " + originalJournalId);
        }

        Integer isReversalJournal = jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM reversals WHERE reversal_journal_id = ?) + " +
                        "(SELECT COUNT(*) FROM partial_refunds WHERE refund_journal_id = ?)",
                Integer.class, originalJournalId, originalJournalId);
        if (isReversalJournal != null && isReversalJournal > 0) {
            throw new IllegalArgumentException("Cannot refund a journal that is itself a reversal or refund: " + originalJournalId);
        }

        Integer alreadyFullyReversed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reversals WHERE original_journal_id = ?",
                Integer.class, originalJournalId);
        if (alreadyFullyReversed != null && alreadyFullyReversed > 0) {
            throw new IllegalStateException("Original journal was already fully reversed: " + originalJournalId);
        }

        // 3. Derive authoritative original principal and fee strictly from the ledger
        BigDecimal derivedFee = BigDecimal.ZERO;
        BigDecimal derivedMerchantPrincipal = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;

        for (LedgerEntry e : originalEntries) {
            String code = e.getAccount().getAccountCode();
            if ("DEBIT".equals(e.getEntryType())) {
                totalDebits = totalDebits.add(e.getAmount());
            }
            if ("4001".equals(code) && "CREDIT".equals(e.getEntryType())) {
                derivedFee = derivedFee.add(e.getAmount());
            }
            if ("2002".equals(code) && "CREDIT".equals(e.getEntryType())) {
                derivedMerchantPrincipal = derivedMerchantPrincipal.add(e.getAmount());
            }
        }

        BigDecimal derivedOriginalAmount = derivedMerchantPrincipal.compareTo(BigDecimal.ZERO) > 0
                ? derivedMerchantPrincipal
                : totalDebits.subtract(derivedFee);
        if (derivedOriginalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            derivedOriginalAmount = totalDebits;
        }

        // 4. Duplicate idempotency check under advisory lock
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM partial_refunds WHERE original_journal_id = ? AND idempotency_key = ?",
                Integer.class, originalJournalId, idempotencyKey);
        if (existing != null && existing > 0) {
            throw new IllegalStateException("Duplicate partial refund rejected for idempotency key: " + idempotencyKey);
        }

        // 5. Enforce cumulative refund cap (never exceed original principal)
        BigDecimal alreadyRefunded = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM partial_refunds WHERE original_journal_id = ?",
                BigDecimal.class, originalJournalId);
        BigDecimal alreadyRefundedFee = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(fee_refunded), 0) FROM partial_refunds WHERE original_journal_id = ?",
                BigDecimal.class, originalJournalId);
        if (alreadyRefunded == null) alreadyRefunded = BigDecimal.ZERO;
        if (alreadyRefundedFee == null) alreadyRefundedFee = BigDecimal.ZERO;

        if (alreadyRefunded.add(refundAmount).compareTo(derivedOriginalAmount) > 0) {
            throw new IllegalArgumentException(
                    "Refund exceeds original amount. Original: " + derivedOriginalAmount
                            + ", Already refunded: " + alreadyRefunded
                            + ", Requested: " + refundAmount);
        }

        // 6. Calculate fee refund according to policy (capped at remaining unrefunded fee)
        FeeRefundPolicy policy = (feePolicy == null) ? FeeRefundPolicy.NONE : feePolicy;
        BigDecimal remainingFee = derivedFee.subtract(alreadyRefundedFee).max(BigDecimal.ZERO);
        BigDecimal feeToRefund = switch (policy) {
            case PROPORTIONAL -> derivedFee.multiply(refundAmount)
                    .divide(derivedOriginalAmount, 4, RoundingMode.HALF_UP)
                    .min(remainingFee);
            case FULL -> remainingFee;
            case NONE -> BigDecimal.ZERO;
        };

        String actor = (createdBy == null || createdBy.isBlank()) ? "REFUND_ENGINE" : createdBy.trim();
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(buildEntry("2002", "DEBIT", refundAmount, "INR", actor));
        if (feeToRefund.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(buildEntry("4001", "DEBIT", feeToRefund, "INR", actor));
        }
        entries.add(buildEntry("1001", "CREDIT", refundAmount.add(feeToRefund), "INR", actor));

        UUID refundJournalId = ledgerService.postTransaction(entries);

        jdbcTemplate.update(
                "INSERT INTO partial_refunds (original_journal_id, refund_journal_id, idempotency_key, original_amount, refund_amount, fee_refunded, fee_policy) VALUES (?, ?, ?, ?, ?, ?, ?)",
                originalJournalId, refundJournalId, idempotencyKey, derivedOriginalAmount, refundAmount, feeToRefund, policy.name());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PARTIALLY_REFUNDED");
        result.put("originalJournalId", originalJournalId.toString());
        result.put("refundJournalId", refundJournalId.toString());
        result.put("derivedOriginalAmount", derivedOriginalAmount);
        result.put("derivedOriginalFee", derivedFee);
        result.put("refundAmount", refundAmount);
        result.put("feePolicy", policy.name());
        result.put("feeRefunded", feeToRefund);
        result.put("totalRefundedSoFar", alreadyRefunded.add(refundAmount));
        result.put("remainingRefundable", derivedOriginalAmount.subtract(alreadyRefunded.add(refundAmount)));
        return result;
    }

    private LedgerEntry buildEntry(String accountCode, String type, BigDecimal amount, String currency, String actor) {
        Account account = accountRepository.findAll().stream()
                .filter(a -> accountCode.equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account code not found: " + accountCode));
        LedgerEntry e = new LedgerEntry();
        e.setAccount(account);
        e.setEntryType(type);
        e.setAmount(amount);
        e.setCurrency(currency);
        e.setCreatedBy(actor);
        return e;
    }
}