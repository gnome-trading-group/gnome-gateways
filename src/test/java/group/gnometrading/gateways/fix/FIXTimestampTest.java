package group.gnometrading.gateways.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static group.gnometrading.gateways.fix.FIXTimestampPrecision.*;

class FIXTimestampTest {

    private static Stream<Arguments> testParseTimestampBufferArguments() {
        return Stream.of(
                // Date + time w/ seconds precision
                Arguments.of("20240105-01:30:12", 0, 17, SECONDS, "20240105-01:30:12"),
                Arguments.of("20240105-01:30:12.123", 0, 21, SECONDS, "20240105-01:30:12"),
                Arguments.of("020240105-01:30:12.123", 1, 21, SECONDS, "20240105-01:30:12"),

                // Date + time w/ millis precision
                Arguments.of("20240105-01:30:12.123", 0, 21, MILLISECONDS, "20240105-01:30:12.123"),
                Arguments.of("20240105-01:30:12.123456", 0, 21, MILLISECONDS, "20240105-01:30:12.123"),
                Arguments.of("20240105-01:30:12", 0, 17, MILLISECONDS, "20240105-01:30:12.000"),
                Arguments.of("20240105-01:30:12.123", 0, 17, MILLISECONDS, "20240105-01:30:12.000"),


                // Date + time w/ micro precision
                Arguments.of("20240105-01:30:12", 0, 17, MICROSECONDS, "20240105-01:30:12.000000"),
                Arguments.of("20240105-01:30:12.123", 0, 21, MICROSECONDS, "20240105-01:30:12.000000"),
                Arguments.of("20240105-01:30:12.123456", 0, 24, MICROSECONDS, "20240105-01:30:12.123456"),
                Arguments.of("20240105-01:30:12.120000", 0, 24, MICROSECONDS, "20240105-01:30:12.120000"),
                Arguments.of("020240105-01:30:12.120000", 1, 24, MICROSECONDS, "20240105-01:30:12.120000")
        );
    }

    @ParameterizedTest
    @MethodSource("testParseTimestampBufferArguments")
    void testParseTimestampBuffer(String bytes, int offset, int length, FIXTimestampPrecision precision, String expected) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes.getBytes());
        final var timestamp = new FIXTimestamp();
        timestamp.parseTimestampBuffer(buffer, offset, length, precision);
        assertEquals(expected, timestamp.toString());
    }

    private static Stream<Arguments> testParseTimeBufferArguments() {
        return Stream.of(
                // Seconds precision
                Arguments.of("12:45:56", 0, 8, SECONDS, "12:45:56"),
                Arguments.of("18:59:55", 0, 8, SECONDS, "18:59:55"),
                Arguments.of("00018:59:30", 3, 8, SECONDS, "18:59:30"),
                Arguments.of("00018:59:30.123456", 3, 12, SECONDS, "18:59:30"),

                // Millis precision
                Arguments.of("12:45:56", 0, 8, MILLISECONDS, "12:45:56.000"),
                Arguments.of("18:59:55.123", 0, 12, MILLISECONDS, "18:59:55.123"),
                Arguments.of("00018:59:30.456", 3, 12, MILLISECONDS, "18:59:30.456"),
                Arguments.of("00018:59:30.123456", 3, 15, MILLISECONDS, "18:59:30.123"),

                // Micro precision
                Arguments.of("12:45:56", 0, 8, MICROSECONDS, "12:45:56.000000"),
                Arguments.of("18:59:55.123", 0, 12, MICROSECONDS, "18:59:55.000000"),
                Arguments.of("00018:59:30.456123", 3, 15, MICROSECONDS, "18:59:30.456123"),
                Arguments.of("00018:59:30.123456", 3, 15, MICROSECONDS, "18:59:30.123456")
        );
    }

    @ParameterizedTest
    @MethodSource("testParseTimeBufferArguments")
    void testParseTimeBuffer(String bytes, int offset, int length, FIXTimestampPrecision precision, String expected) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes.getBytes());
        final var timestamp = new FIXTimestamp();
        timestamp.parseTimeBuffer(buffer, offset, length, precision);
        assertEquals(expected, timestamp.toString());
    }

    private static Stream<Arguments> testParseDateBufferArguments() {
        return Stream.of(
                Arguments.of("20240101", 0, 8, "20240101"),
                Arguments.of("20231231", 0, 8, "20231231"),
                Arguments.of("123420221219", 4, 8, "20221219"),
                Arguments.of("20221219-12:45", 0, 8, "20221219")
        );
    }

    @ParameterizedTest
    @MethodSource("testParseDateBufferArguments")
    void testParseDateBuffer(String bytes, int offset, int length, String expected) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes.getBytes());
        final var timestamp = new FIXTimestamp();
        timestamp.parseDateBuffer(buffer, offset, length);
        assertEquals(expected, timestamp.toString());
    }

    @Test
    void testResets() {
        final var timestamp = new FIXTimestamp();

        timestamp.parseTimestampBuffer(ByteBuffer.wrap("20240101-12:45:30.123".getBytes()), 0, 21, MILLISECONDS);
        assertEquals("20240101-12:45:30.123", timestamp.toString());

        timestamp.reset();

        timestamp.parseTimestampBuffer(ByteBuffer.wrap("20240105-14:56:30.999999".getBytes()), 0, 24, MICROSECONDS);
        assertEquals("20240105-14:56:30.999999", timestamp.toString());

        timestamp.reset();

        timestamp.parseTimeBuffer(ByteBuffer.wrap("10:30:56".getBytes()), 0, 8, SECONDS);
        assertEquals("10:30:56", timestamp.toString());

        timestamp.parseDateBuffer(ByteBuffer.wrap("20220101".getBytes()), 0, 8);
        assertEquals("20220101", timestamp.toString());
    }

}