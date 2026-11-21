package com.zetheta.demo.validators;


import com.zetheta.demo.models.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

@Component
public class TransactionValidator {

    public record TypeRule(BigDecimal minAmount, BigDecimal maxAmount, int maxScale) {
    }

    private final Map<TransactionType, TypeRule> rules = new EnumMap<>(TransactionType.class);

    public TransactionValidator() {
        rules.put(TransactionType.DEPOSIT_BANK, new TypeRule(new BigDecimal("1.00"), new BigDecimal("10000000.00"), 4));
        rules.put(TransactionType.DEPOSIT_CARD, new TypeRule(new BigDecimal("1.00"), new BigDecimal("500000.00"), 4));
        rules.put(TransactionType.WITHDRAWAL_BANK, new TypeRule(new BigDecimal("1.00"), new BigDecimal("5000000.00"), 4));
        rules.put(TransactionType.P2P_TRANSFER, new TypeRule(new BigDecimal("1.00"), new BigDecimal("200000.00"), 4));
        rules.put(TransactionType.MERCHANT_PAYMENT_QR, new TypeRule(new BigDecimal("1.00"), new BigDecimal("100000.00"), 4));
        rules.put(TransactionType.MERCHANT_PAYMENT_ONLINE, new TypeRule(new BigDecimal("1.00"), new BigDecimal("500000.00"), 4));
        rules.put(TransactionType.BILL_PAYMENT, new TypeRule(new BigDecimal("1.00"), new BigDecimal("500000.00"), 4));
        rules.put(TransactionType.INTEREST_ACCRUAL, new TypeRule(new BigDecimal("0.01"), new BigDecimal("1000000.00"), 4));
        rules.put(TransactionType.INTEREST_PAYOUT, new TypeRule(new BigDecimal("1.00"), new BigDecimal("1000000.00"), 4));
        rules.put(TransactionType.FEE_DEDUCTION, new TypeRule(new BigDecimal("1.00"), new BigDecimal("50000.00"), 4));
        rules.put(TransactionType.CASHBACK_CREDIT, new TypeRule(new BigDecimal("0.01"), new BigDecimal("50000.00"), 4));
        rules.put(TransactionType.PROMOTIONAL_CREDIT, new TypeRule(new BigDecimal("0.01"), new BigDecimal("50000.00"), 4));
        rules.put(TransactionType.LOAN_DISBURSEMENT, new TypeRule(new BigDecimal("10.00"), new BigDecimal("5000000.00"), 4));
        rules.put(TransactionType.LOAN_EMI_PAYMENT, new TypeRule(new BigDecimal("1.00"), new BigDecimal("1000000.00"), 4));
        rules.put(TransactionType.FX_CONVERSION, new TypeRule(new BigDecimal("1.00"), new BigDecimal("250000.00"), 4));
        rules.put(TransactionType.REFUND_FULL, new TypeRule(new BigDecimal("1.00"), new BigDecimal("1000000.00"), 4));
        rules.put(TransactionType.REFUND_PARTIAL, new TypeRule(new BigDecimal("1.00"), new BigDecimal("1000000.00"), 4));
        rules.put(TransactionType.CHARGEBACK, new TypeRule(new BigDecimal("1.00"), new BigDecimal("1000000.00"), 4));
        rules.put(TransactionType.REWARD_POINTS_REDEMPTION, new TypeRule(new BigDecimal("1.00"), new BigDecimal("100000.00"), 4));
        rules.put(TransactionType.ACCOUNT_CLOSURE_SWEEP, new TypeRule(new BigDecimal("0.01"), new BigDecimal("10000000.00"), 4));
    }

    public void validate(TransactionType type, BigDecimal amount, String createdBy) {
        if (type == null) {
            throw new IllegalArgumentException("TransactionType is required.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be strictly greater than zero.");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy actor metadata is required for audit compliance.");
        }
        TypeRule rule = rules.get(type);
        if (rule == null) {
            throw new IllegalArgumentException("No validation rule registered for transaction type: " + type);
        }
        if (amount.stripTrailingZeros().scale() > rule.maxScale()) {
            throw new IllegalArgumentException("Amount exceeds maximum decimal precision of " + rule.maxScale() + " places.");
        }
        if (amount.compareTo(rule.minAmount()) < 0) {
            throw new IllegalArgumentException(type + " amount " + amount + " is below minimum allowed " + rule.minAmount());
        }
        if (amount.compareTo(rule.maxAmount()) > 0) {
            throw new IllegalArgumentException(type + " amount " + amount + " exceeds maximum allowed " + rule.maxAmount());
        }
    }

    public BigDecimal getMinAmount(TransactionType type) {
        return rules.get(type).minAmount();
    }

    public BigDecimal getMaxAmount(TransactionType type) {
        return rules.get(type).maxAmount();
    }
}