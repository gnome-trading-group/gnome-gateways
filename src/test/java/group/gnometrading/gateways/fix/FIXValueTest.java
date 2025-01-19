package group.gnometrading.gateways.fix;

import group.gnometrading.strings.ViewString;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static group.gnometrading.gateways.fix.FIXTestUtils.fix;
import static org.junit.jupiter.api.Assertions.*;
import static group.gnometrading.gateways.fix.FIXTimestampPrecision.*;

class FIXValueTest {

    private static Stream<Arguments> testParseBufferArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.allocate(0), false, 0, 0),
                Arguments.of(ByteBuffer.wrap("123".getBytes()), false, 0, 0),
                Arguments.of(ByteBuffer.wrap(("123|").getBytes()).put(3, (byte) 1), true, 3, 0),
                Arguments.of(ByteBuffer.wrap("111111|".getBytes()).position(1).put(6, (byte) 1), true, 5, 1),
                Arguments.of(ByteBuffer.wrap("111111|123".getBytes()).position(2).put(6, (byte) 1), true, 4, 2)

        );
    }

    @ParameterizedTest
    @MethodSource("testParseBufferArguments")
    void testParseBuffer(ByteBuffer buffer, boolean result, int length, int offset) {
        final var value = new FIXValue(0);
        assertEquals(result, value.parseBuffer(buffer));
        if (result) {
            assertEquals(offset, value.getOffset());
            assertEquals(length, value.getLength());
        }
    }

    private static Stream<Arguments> testBooleanArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("Y|".getBytes()), true),
                Arguments.of(ByteBuffer.wrap("N|".getBytes()), false),
                Arguments.of(ByteBuffer.wrap("Y".getBytes()), true),
                Arguments.of(ByteBuffer.wrap("N".getBytes()), false),
                Arguments.of(ByteBuffer.wrap("xxY".getBytes()).position(2), true),
                Arguments.of(ByteBuffer.wrap("xxN".getBytes()).position(2), false)
        );
    }

    @ParameterizedTest
    @MethodSource("testBooleanArguments")
    void testBoolean(ByteBuffer buffer, boolean expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asBoolean());
    }

    private static Stream<Arguments> testCharArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("a|".getBytes()), 'a'),
                Arguments.of(ByteBuffer.wrap("b|".getBytes()), 'b'),
                Arguments.of(ByteBuffer.wrap("c|".getBytes()), 'c'),
                Arguments.of(ByteBuffer.wrap("d|".getBytes()), 'd'),
                Arguments.of(ByteBuffer.wrap("xxd|".getBytes()).position(2), 'd')
        );
    }

    @ParameterizedTest
    @MethodSource("testCharArguments")
    void testChar(ByteBuffer buffer, char expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asChar());
    }

    private static Stream<Arguments> testDateArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("20240101".getBytes()), "20240101"),
                Arguments.of(ByteBuffer.wrap("xxx20240105".getBytes()).position(3), "20240105"),
                Arguments.of(ByteBuffer.wrap("xxx20220105-12:30:00".getBytes()).position(3), "20220105")
        );
    }

    @ParameterizedTest
    @MethodSource("testDateArguments")
    void testDate(ByteBuffer buffer, String expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asDate().toString());
    }

    private static Stream<Arguments> testTimeArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("12:30:11".getBytes()), SECONDS, "12:30:11"),
                Arguments.of(ByteBuffer.wrap("12:45:00.123".getBytes()), SECONDS, "12:45:00"),
                Arguments.of(ByteBuffer.wrap("xxx12:45:00.123".getBytes()).position(3), SECONDS, "12:45:00"),
                Arguments.of(ByteBuffer.wrap("12:45:00.123".getBytes()), MILLISECONDS, "12:45:00.123"),
                Arguments.of(ByteBuffer.wrap("12:45:00".getBytes()), MILLISECONDS, "12:45:00.000"),
                Arguments.of(ByteBuffer.wrap("11:15:00".getBytes()), MICROSECONDS, "11:15:00.000000"),
                Arguments.of(ByteBuffer.wrap("11:15:00.123456".getBytes()), MICROSECONDS, "11:15:00.123456"),
                Arguments.of(ByteBuffer.wrap("xx11:15:00.123456".getBytes()).position(2), MICROSECONDS, "11:15:00.123456")
        );
    }

    @ParameterizedTest
    @MethodSource("testTimeArguments")
    void testTime(ByteBuffer buffer, FIXTimestampPrecision precision, String expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asTime(precision).toString());
    }

    private static Stream<Arguments> testTimestampArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("20221231-12:30:11".getBytes()), SECONDS, "20221231-12:30:11"),
                Arguments.of(ByteBuffer.wrap("20221231-12:45:00.123".getBytes()), SECONDS, "20221231-12:45:00"),
                Arguments.of(ByteBuffer.wrap("xxx20221231-12:45:00.123".getBytes()).position(3), SECONDS, "20221231-12:45:00"),
                Arguments.of(ByteBuffer.wrap("20221231-12:45:00.123".getBytes()), MILLISECONDS, "20221231-12:45:00.123"),
                Arguments.of(ByteBuffer.wrap("20241231-12:45:00".getBytes()), MILLISECONDS, "20241231-12:45:00.000"),
                Arguments.of(ByteBuffer.wrap("20241231-11:15:00".getBytes()), MICROSECONDS, "20241231-11:15:00.000000"),
                Arguments.of(ByteBuffer.wrap("20241231-11:15:00.123456".getBytes()), MICROSECONDS, "20241231-11:15:00.123456"),
                Arguments.of(ByteBuffer.wrap("xx20241231-11:15:00.123456".getBytes()).position(2), MICROSECONDS, "20241231-11:15:00.123456")
        );
    }

    @ParameterizedTest
    @MethodSource("testTimestampArguments")
    void testTimestamp(ByteBuffer buffer, FIXTimestampPrecision precision, String expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asTimestamp(precision).toString());
    }

    private static Stream<Arguments> testStringArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("20221231-12:30:11".getBytes()), "20221231-12:30:11"),
                Arguments.of(ByteBuffer.wrap("xx20221231".getBytes()).position(2), "20221231"),
                Arguments.of(ByteBuffer.wrap("".getBytes()), "")
        );
    }

    @ParameterizedTest
    @MethodSource("testStringArguments")
    void testString(ByteBuffer buffer, String expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asString().toString());
    }

    private static Stream<Arguments> testIntArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("20221231".getBytes()), 20221231),
                Arguments.of(ByteBuffer.wrap("00001".getBytes()), 1),
                Arguments.of(ByteBuffer.wrap("xxx00001".getBytes()).position(3), 1),
                Arguments.of(ByteBuffer.wrap("999".getBytes()), 999)
        );
    }

    @ParameterizedTest
    @MethodSource("testIntArguments")
    void testInt(ByteBuffer buffer, int expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asInt());
    }

    private static Stream<Arguments> testLongArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("50".getBytes()), 50L),
                Arguments.of(ByteBuffer.wrap("xxx00001".getBytes()).position(3), 1L),
                Arguments.of(ByteBuffer.wrap("9223372036854775807".getBytes()), Long.MAX_VALUE)
        );
    }

    @ParameterizedTest
    @MethodSource("testLongArguments")
    void testInt(ByteBuffer buffer, long expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asLong());
    }

    private static Stream<Arguments> testDecimalArguments() {
        return Stream.of(
                Arguments.of(ByteBuffer.wrap("50".getBytes()), 50.0),
                Arguments.of(ByteBuffer.wrap("50.1".getBytes()), 50.1),
                Arguments.of(ByteBuffer.wrap("50.123".getBytes()), 50.123),
                Arguments.of(ByteBuffer.wrap("99.5692".getBytes()), 99.5692),
                Arguments.of(ByteBuffer.wrap("xx99.5692".getBytes()).position(2), 99.5692)
        );
    }

    @ParameterizedTest
    @MethodSource("testDecimalArguments")
    void testDecimal(ByteBuffer buffer, double expected) {
        final var value = new FIXValue(0);
        value.parseBuffer(buffer);
        assertEquals(expected, value.asDecimal());
    }

    private static Stream<Arguments> testWriteBufferArguments() {
        return Stream.of(
                Arguments.of((Consumer<FIXValue>) (value) -> value.setString(""), fix("|"), 1),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setString("hi"), fix("hi|"), 3),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setString(new ViewString("yo")), fix("yo|"), 3),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setChar('Y'), fix("Y|"), 2),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setBoolean(false), fix("N|"), 2),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setInt(99), fix("99|"), 3),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setInt(-1099), fix("-1099|"), 6),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setDecimal(53.1, 2), fix("53.10|"), 6),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setDecimal(53.1, 1), fix("53.1|"), 5),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setDecimal(99.5604, 4), fix("99.5604|"), 8),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setDecimal(-995678.5604, 4), fix("-995678.5604|"), 13),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setCheckSum(13543215), fix("047|"), 4),
                Arguments.of((Consumer<FIXValue>) (value) -> value.setTimestamp(1L, MILLISECONDS), fix("19700101-00:00:00.001|"), 22),
                Arguments.of((Consumer<FIXValue>) (value) -> {
                    FIXValue other = new FIXValue(64);
                    other.setString("123456789");
                    value.set(other);
                    other.setString("123");
                }, fix("123456789|"), 10),
                Arguments.of((Consumer<FIXValue>) (value) -> {
                    ByteBuffer other = ByteBuffer.wrap("123456".getBytes());
                    value.setByteBuffer(other);
                }, fix("123456|"), 7)
        );
    }

    @ParameterizedTest
    @MethodSource("testWriteBufferArguments")
    void testWriteBuffer(Consumer<FIXValue> writer, ByteBuffer expected, int bytes) {
        final var value = new FIXValue(64);
        writer.accept(value);
        ByteBuffer output = ByteBuffer.allocate(expected.remaining());
        assertEquals(bytes - 1, value.getLength());

        output.mark();
        value.writeToBuffer(output);
        assertEquals(bytes, output.position());
        output.reset();

        assertEquals(expected, output);
    }

}