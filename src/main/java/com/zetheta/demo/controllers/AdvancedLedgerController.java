package com.zetheta.demo.controllers;

import com.zetheta.demo.models.ExchangeRateSnapshot;
import com.zetheta.demo.models.FeeRefundPolicy;
import com.zetheta.demo.models.TransactionType;
import com.zetheta.demo.services.ExchangeRateService;
import com.zetheta.demo.services.TransactionEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/ledger", "/api/v1"})
public class AdvancedLedgerController {

    private final TransactionEngineService transactionEngineService;
    private final ExchangeRateService exchangeRateService;

    public AdvancedLedgerController(TransactionEngineService transactionEngineService,
                                    ExchangeRateService exchangeRateService) {
        this.transactionEngineService = transactionEngineService;
        this.exchangeRateService = exchangeRateService;
    }

    @PostMapping("/transactions/typed")
    public ResponseEntity<Object> postTypedTransaction(
            @RequestParam TransactionType type,
            @RequestParam BigDecimal amount,
            @RequestParam(defaultValue = "API_USER") String createdBy) {
        Map<String, Object> result = transactionEngineService.processTypedTransaction(type, amount, createdBy);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/refunds/partial")
    public ResponseEntity<Object> processPartialRefund(
            @RequestParam UUID originalJournalId,
            @RequestParam(required = false) BigDecimal originalAmount,
            @RequestParam(required = false) BigDecimal originalFee,
            @RequestParam BigDecimal refundAmount,
            @RequestParam(defaultValue = "PROPORTIONAL") FeeRefundPolicy feePolicy,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestParam(defaultValue = "API_USER") String createdBy) {
        Map<String, Object> result = transactionEngineService.processPartialRefund(
                originalJournalId, refundAmount, feePolicy, idempotencyKey, createdBy);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fx/rates")
    public ResponseEntity<Object> createRateSnapshot(
            @RequestParam String baseCurrency,
            @RequestParam String quoteCurrency,
            @RequestParam BigDecimal rate,
            @RequestParam(defaultValue = "RBI_REFERENCE") String source,
            @RequestParam(defaultValue = "60") int validMinutes) {
        ExchangeRateSnapshot saved = exchangeRateService.saveRateSnapshot(
                baseCurrency, quoteCurrency, rate, source, validMinutes);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/fx/convert/usd-to-inr")
    public ResponseEntity<Object> convertUsdToInr(
            @RequestParam BigDecimal usdAmount,
            @RequestParam(defaultValue = "FX_USER") String createdBy) {
        Map<String, Object> result = exchangeRateService.convertUsdToInr(usdAmount, createdBy);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/reports/currency-exposure")
    public ResponseEntity<Object> getCurrencyExposureReport() {
        return ResponseEntity.ok(exchangeRateService.getCurrencyExposureReport());
    }
}