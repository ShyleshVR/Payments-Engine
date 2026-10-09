package com.shylesh.ledger_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Outbox relay and cleanup. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
