package com.mywatchmate.sessions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param generateDays how far ahead repeating schedules are turned into sessions
 * @param maxLength    a live session ends on its own after this, when the video's length is unknown
 * @param endGrace     extra time after a known-length video before the session ends on its own
 */
@ConfigurationProperties("mywatchmate.sessions")
public record SessionProperties(int generateDays, Duration maxLength, Duration endGrace) {
}
