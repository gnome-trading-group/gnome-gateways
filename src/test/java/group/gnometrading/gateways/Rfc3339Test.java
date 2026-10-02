package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.strings.MutableString;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Rfc3339Test {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2024-01-15T10:30:00Z",
                "2026-10-02T14:22:56.542213551Z",
                "2026-10-02T14:22:56.5Z",
                "2026-10-02T14:22:56.123456Z",
                "1970-01-01T00:00:00Z",
                "2000-02-29T23:59:59.999Z",
                "2026-10-02T09:22:56-05:00",
                "2026-10-02T19:52:56.25+05:30",
                "2100-12-31T23:59:59Z",
            })
    void matchesJavaTime(final String text) {
        final Instant expected = OffsetDateTime.parse(text).toInstant();
        assertEquals(
                expected.getEpochSecond() * 1_000_000_000L + expected.getNano(),
                Rfc3339.toEpochNanos(new MutableString(text)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "2026-10-02",
                "2026-10-02T14:22:56",
                "2026-10-02T14:22:56.Z",
                "2026-13-02T14:22:56Z",
                "2026-10-02T14:22:56Zjunk",
                "2026/10/02T14:22:56Z",
                "1700000000000",
            })
    void rejectsMalformed(final String text) {
        assertEquals(Rfc3339.INVALID, Rfc3339.toEpochNanos(new MutableString(text)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-02T14:22:56.1234567891234Z"})
    void truncatesBeyondNanos(final String text) {
        final Instant expected = Instant.parse("2026-10-02T14:22:56.123456789Z");
        assertEquals(
                expected.getEpochSecond() * 1_000_000_000L + expected.getNano(),
                Rfc3339.toEpochNanos(new MutableString(text)));
    }
}
