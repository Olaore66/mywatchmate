package com.mywatchmate.track;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrackPropertiesTest {

    private final TrackProperties props = new TrackProperties(0.5, Duration.ofMinutes(20), Duration.ofSeconds(60));

    @Test
    void shortSessionsNeedHalf() {
        assertThat(props.requiredSeconds(Duration.ofMinutes(30))).as("30-minute devotion").isEqualTo(15 * 60);
        assertThat(props.requiredSeconds(Duration.ofMinutes(10))).isEqualTo(5 * 60);
        assertThat(props.requiredSeconds(Duration.ofSeconds(61))).isEqualTo(30);
    }

    @Test
    void longSessionsNeedAtMostTwentyMinutes() {
        assertThat(props.requiredSeconds(Duration.ofHours(2))).as("2-hour film").isEqualTo(20 * 60);
        assertThat(props.requiredSeconds(Duration.ofMinutes(40))).isEqualTo(20 * 60);
    }

    @Test
    void anInstantSessionNeedsNothing() {
        assertThat(props.requiredSeconds(Duration.ZERO)).isZero();
    }
}
