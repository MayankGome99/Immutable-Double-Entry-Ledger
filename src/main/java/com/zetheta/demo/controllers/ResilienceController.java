package com.zetheta.demo.controllers;

import com.zetheta.demo.services.ResilienceAndAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/ledger")
public class ResilienceController {

    private final ResilienceAndAuditService resilienceService;

    public ResilienceController(ResilienceAndAuditService resilienceService) {
        this.resilienceService = resilienceService;
    }

    @PostMapping("/fx/revalue")
    public ResponseEntity<Object> runUnrealisedFxRevaluation(
            @RequestParam(defaultValue = "83.00000000") BigDecimal baselineUsdRate) {
        return ResponseEntity.ok(resilienceService.runUnrealisedFxRevaluation(baselineUsdRate));
    }

    @GetMapping("/audit/anomalies")
    public ResponseEntity<Object> detectAnomalies(
            @RequestParam(defaultValue = "10000.00") BigDecimal threshold) {
        return ResponseEntity.ok(resilienceService.detectAnomalies(threshold));
    }

    @GetMapping("/circuit-breaker/status")
    public ResponseEntity<Object> getCircuitBreakerStatus() {
        return ResponseEntity.ok(resilienceService.getCircuitBreakerStatus());
    }
}