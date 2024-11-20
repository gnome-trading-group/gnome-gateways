package group.gnometrading.gateways.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static group.gnometrading.gateways.fix.FIXTimestampPrecision.*;
import static org.junit.jupiter.api.Assertions.*;

class FIXTimestampUtilsTest {

    private static Stream<Arguments> testPutTimestampArguments() {
        return Stream.of(
                Arguments.of(0, MILLISECONDS, "19700101-00:00:00.000"),
                Arguments.of(1, MILLISECONDS, "19700101-00:00:00.001"),
                Arguments.of(1001, MILLISECONDS, "19700101-00:00:01.001"),
                Arguments.of(61001, MILLISECONDS, "19700101-00:01:01.001"),
                Arguments.of(86400000, MILLISECONDS, "19700102-00:00:00.000"),
                Arguments.of(31536000000L, MILLISECONDS, "19710101-00:00:00.000"),
                Arguments.of(63072000000L, MILLISECONDS, "19720101-00:00:00.000"),
                Arguments.of(946684800000L, MILLISECONDS, "20000101-00:00:00.000"),
                Arguments.of(978307200000L, MILLISECONDS, "20010101-00:00:00.000"),
                Arguments.of(1609459200000L, MILLISECONDS, "20210101-00:00:00.000"),
                Arguments.of(1704067200000L, MILLISECONDS, "20240101-00:00:00.000"),
                Arguments.of(253402300799999L, MILLISECONDS, "99991231-23:59:59.999"),
                Arguments.of(1615708799000L, MILLISECONDS, "20210314-07:59:59.000"),
                Arguments.of(1615708800000L, MILLISECONDS, "20210314-08:00:00.000"),
                Arguments.of(1582934400000L, MILLISECONDS, "20200229-00:00:00.000"),
                Arguments.of(2222222222222L, MILLISECONDS, "20400602-03:57:02.222"),
                Arguments.of(1732029285129L, MILLISECONDS, "20241119-15:14:45.129"),
                Arguments.of(1732029285129L, SECONDS, "20241119-15:14:45"),
                Arguments.of(2222222222222L, SECONDS, "20400602-03:57:02")
        );
    }

    @ParameterizedTest
    @MethodSource("testPutTimestampArguments")
    void testPutTimestamp(long millis, FIXTimestampPrecision precision, String expected) {
        byte[] buffer = new byte[expected.length()];
        int bytes = FIXTimestampUtils.putTimestamp(millis, precision, buffer, 0);
        assertEquals(expected.length(), bytes);
        assertEquals(expected, new String(buffer));
    }

    @Test
    void testPutTimestampOffset() {
        String expected = "20241119-15:14:45.129";
        byte[] buffer = new byte[expected.length() + 2];
        buffer[0] = 'a';
        buffer[1] = 'b';
        int bytes = FIXTimestampUtils.putTimestamp(1732029285129L, MILLISECONDS, buffer, 2);
        assertEquals(bytes, expected.length());
        assertEquals("ab" + expected, new String(buffer));
    }

    @Test
    void testPutTimestampDisablesMicroseconds() {
        assertThrowsExactly(IllegalArgumentException.class, () -> FIXTimestampUtils.putTimestamp(0, MICROSECONDS, new byte[0], 0));
    }

}