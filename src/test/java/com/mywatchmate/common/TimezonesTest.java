package com.mywatchmate.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class TimezonesTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankMeansUtc(String timezone) {
        assertThat(Timezones.normalize(timezone)).isEqualTo("UTC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/London", "Africa/Lagos", "America/New_York", "UTC"})
    void acceptsIanaNames(String timezone) {
        assertThat(Timezones.normalize(timezone)).isEqualTo(timezone);
    }

    @Test
    void trimsWhitespace() {
        assertThat(Timezones.normalize("  Africa/Lagos ")).isEqualTo("Africa/Lagos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mars/Olympus", "London", "GMT+25", "not a zone"})
    void rejectsUnknownNames(String timezone) {
        assertThatThrownBy(() -> Timezones.normalize(timezone))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getCode()).isEqualTo("INVALID_TIMEZONE");
            });
    }
}
