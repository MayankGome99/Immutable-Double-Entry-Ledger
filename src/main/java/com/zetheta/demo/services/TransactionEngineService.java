package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.FeeRefundPolicy;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.models.TransactionType;
import com.zetheta.demo.repositories.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class TransactionEngineService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final ExchangeRateService exchangeRateService;
    private final JdbcTemplate jdbcTemplate;

    public TransactionEngineService(AccountRepository accountRepository,
                                    LedgerService ledgerService,
                                    ExchangeRateService exchangeRateService,
                                    JdbcTemplate jdbcTemplate) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.exchangeRateService = exchangeRateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> processTypedTransaction(TransactionType type, BigDecimal amount, String createdBy) {
        if (type == null) {
            throw new IllegalArgumentException("TransactionType is required.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }

        String actor = (createdBy == null || createdBy.isBlank()) ? "TXN_ENGINE" : createdBy;

        if (type == TransactionType.FX_CONVERSION) {
            return exchangeRateService.convertUsdToInr(amount, actor);
        }

        BigDecimal fee = amount.multiply(new BigDecimal("0.01")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal tax = amount.multiply(new BigDecimal("0.005")).setScale(4, RoundingMode.HALF_UP);
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
                BigDecimal half = amount.divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
                entries.add(buildEntry("2002", "DEBIT", half, "INR", actor));
                entries.add(buildEntry("1001", "CREDIT", half, "INR", actor));
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

    @Transactional
    public Map<String, Object> processPartialRefund(UUID originalJournalId,
                                                    BigDecimal originalAmount,
                                                    BigDecimal originalFee,
                                                    BigDecimal refundAmount,
                                                    FeeRefundPolicy feePolicy,
                                                    String idempotencyKey,
                                                    String createdBy) {
        if (originalJournalId == null || refundAmount == null || originalAmount == null) {
            throw new IllegalArgumentException("originalJournalId, originalAmount, and refundAmount are required.");
        }
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required for partial refunds.");
        }

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM partial_refunds WHERE original_journal_id = ? AND idempotency_key = ?",
                Integer.class, originalJournalId, idempotencyKey);
        if (existing != null && existing > 0) {
            throw new IllegalStateException("Duplicate partial refund rejected for idempotency key: " + idempotencyKey);
        }

        BigDecimal alreadyRefunded = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM partial_refunds WHERE original_journal_id = ?",
                BigDecimal.class, originalJournalId);
        if (alreadyRefunded == null) {
            alreadyRefunded = BigDecimal.ZERO;
        }

        if (alreadyRefunded.add(refundAmount).compareTo(originalAmount) > 0) {
            throw new IllegalArgumentException(
                    "Refund exceeds original amount. Original: " + originalAmount
                            + ", Already refunded: " + alreadyRefunded
                            + ", Requested: " + refundAmount);
        }

        BigDecimal safeFee = (originalFee == null) ? BigDecimal.ZERO : originalFee;
        FeeRefundPolicy policy = (feePolicy == null) ? FeeRefundPolicy.NONE : feePolicy;
        BigDecimal feeToRefund = switch (policy) {
            case PROPORTIONAL -> safeFee.multiply(refundAmount)
                    .divide(originalAmount, 4, RoundingMode.HALF_UP);
            case FULL -> safeFee;
            case NONE -> BigDecimal.ZERO;
        };

        String actor = (createdBy == null || createdBy.isBlank()) ? "REFUND_ENGINE" : createdBy;
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(buildEntry("2002", "DEBIT", refundAmount, "INR", actor));
        if (feeToRefund.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(buildEntry("4001", "DEBIT", feeToRefund, "INR", actor));
        }
        entries.add(buildEntry("1001", "CREDIT", refundAmount.add(feeToRefund), "INR", actor));

        UUID refundJournalId = ledgerService.postTransaction(entries);

        jdbcTemplate.update(
                "INSERT INTO partial_refunds (original_journal_id, refund_journal_id, idempotency_key, original_amount, refund_amount, fee_refunded, fee_policy) VALUES (?, ?, ?, ?, ?, ?, ?)",
                originalJournalId, refundJournalId, idempotencyKey, originalAmount, refundAmount, feeToRefund, policy.name());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PARTIALLY_REFUNDED");
        result.put("originalJournalId", originalJournalId.toString());
        result.put("refundJournalId", refundJournalId.toString());
        result.put("refundAmount", refundAmount);
        result.put("feePolicy", policy.name());
        result.put("feeRefunded", feeToRefund);
        result.put("totalRefundedSoFar", alreadyRefunded.add(refundAmount));
        result.put("remainingRefundable", originalAmount.subtract(alreadyRefunded.add(refundAmount)));
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