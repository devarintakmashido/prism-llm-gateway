package com.prism.controlplane.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowTest {

    @Test
    void dateOnlyToIsInclusiveOfThatWholeDay() {
        Window w = Window.parse("2026-09-01", "2026-09-30");
        assertThat(w.from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(w.to()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void acceptsIsoTimestamps() {
        Window w = Window.parse("2026-09-29T13:08:00Z", "2026-09-29T14:00:00+00:00");
        assertThat(w.from()).isEqualTo(Instant.parse("2026-09-29T13:08:00Z"));
        assertThat(w.to()).isEqualTo(Instant.parse("2026-09-29T14:00:00Z"));
    }

    @Test
    void defaultsToTheCurrentMonthSoFar() {
        Window w = Window.parse(null, "");
        assertThat(w.from().toString()).matches("\\d{4}-\\d{2}-01T00:00:00Z");
        assertThat(w.to()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void rejectsGarbageAndEmptyRanges() {
        assertThatThrownBy(() -> Window.parse("yesterday", null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Invalid date");
        assertThatThrownBy(() -> Window.parse("2026-09-10", "2026-09-01"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("'to' must be after 'from'");
    }
}
