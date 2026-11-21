package com.zetheta.demo.services;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FxRevaluationScheduler {

    private final ResilienceAndAuditService resilienceService;

    public FxRevaluationScheduler(ResilienceAndAuditService resilienceService) {
        this.resilienceService = resilienceService;
    }

    @Scheduled(cron = "0 55 23 * * *")
    public void runNightlyScheduledFxRevaluation() {
        resilienceService.runUnrealisedFxRevaluation(null);
    }
}