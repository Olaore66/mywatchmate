package com.mywatchmate.connect;

import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps monthly chat_messages partitions created ahead of time.
 * Inserts into a month with no partition would fail, so we stay 3 months ahead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPartitionJob {

    private static final int MONTHS_AHEAD = 3;

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void ensurePartitions() {
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            jdbc.queryForObject("SELECT create_chat_partition(?)::text", String.class, month.plusMonths(i));
        }
        log.debug("Chat partitions ensured through {}", month.plusMonths(MONTHS_AHEAD));
    }
}
