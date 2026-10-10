package com.shylesh.processor_simulator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** The passage of banking time: every replica moves due transfers on (rows are claimed with SKIP LOCKED). */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankClock {

    private final BankService bankService;

    @Scheduled(fixedDelayString = "${processor.bank.clock-interval:2s}")
    public void tick() {
        try {
            bankService.advance(LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("Bank clock tick failed: {}", e.getMessage());
        }
    }
}
