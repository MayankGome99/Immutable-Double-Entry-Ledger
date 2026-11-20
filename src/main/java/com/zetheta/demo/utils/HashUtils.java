package com.zetheta.demo.utils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

public final class HashUtils {

    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private HashUtils() {
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static String normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    public static String computeEntryHash(
            String previousHash,
            UUID entryId,
            UUID journalId,
            UUID accountId,
            String entryType,
            BigDecimal amount,
            String currency,
            Instant effectiveDate,
            String createdBy
    ) {
        String prev = (previousHash == null || previousHash.isBlank()) ? GENESIS_HASH : previousHash.trim();
        String payload = String.join("|",
                prev,
                entryId == null ? "" : entryId.toString(),
                journalId == null ? "" : journalId.toString(),
                accountId == null ? "" : accountId.toString(),
                entryType == null ? "" : entryType.trim().toUpperCase(),
                normalizeAmount(amount),
                currency == null ? "" : currency.trim().toUpperCase(),
                effectiveDate == null ? "" : effectiveDate.toString(),
                createdBy == null ? "" : createdBy.trim()
        );
        return sha256(payload);
    }
    public static String generateSHA256(String input) {
        return sha256(input);
    }
}