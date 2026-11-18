package com.zetheta.demo.Data_Transfer_Object;

import java.math.BigDecimal;
import java.util.UUID;

// A record automatically creates the getters and setters for us!

public record TransferRequest(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
}