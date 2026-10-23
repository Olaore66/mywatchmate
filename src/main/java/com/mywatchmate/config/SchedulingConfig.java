package com.mywatchmate.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (session generation/lifecycle, reminders, chat partitions).
 * Off in tests (mywatchmate.jobs.enabled=false), which call the jobs directly instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mywatchmate.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
