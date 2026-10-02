package com.prism.controlplane.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * A [from, to) time window from query parameters. Accepts dates ("2026-09-01", where "to" is inclusive
 * of that whole day) or ISO timestamps ("2026-09-29T13:08:00Z"). Defaults to the current UTC month so far.
 */
record Window(Instant from, Instant to) {

    static Window parse(String from, String to) {
        Instant start = from == null || from.isBlank()
                ? LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : parse(from, false);
        Instant end = to == null || to.isBlank() ? Instant.now().plusSeconds(1) : parse(to, true);
        if (!end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must be after 'from'");
        }
        return new Window(start, end);
    }

    private static Instant parse(String value, boolean endOfRange) {
        try {
            if (value.length() == 10) {
                LocalDate day = LocalDate.parse(value);
                return (endOfRange ? day.plusDays(1) : day).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid date '" + value + "': use YYYY-MM-DD or an ISO-8601 timestamp");
        }
    }

    java.sql.Timestamp fromTs() {
        return java.sql.Timestamp.from(from);
    }

    java.sql.Timestamp toTs() {
        return java.sql.Timestamp.from(to);
    }
}
