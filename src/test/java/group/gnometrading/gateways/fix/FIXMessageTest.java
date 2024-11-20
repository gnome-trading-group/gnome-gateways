package group.gnometrading.gateways.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static group.gnometrading.gateways.fix.FIXTestUtils.fix;
import static org.junit.jupiter.api.Assertions.*;

class FIXMessageTest {

    private static Stream<Arguments> testParseBufferResultArguments() {
        return Stream.of(
                Arguments.of(fix(""), false, 0),
                Arguments.of(fix("35="), false, 0),
                Arguments.of(fix("8=FIX.5.2"), false, 0),
                Arguments.of(fix("8=FIX.5.2|"), false, 0),
                Arguments.of(fix("8=FIX.5.2|35=A|"), false, 0),
                Arguments.of(fix("8=FIX.5.2|9=|"), false, 0),
                Arguments.of(fix("8=FIX.5.2|9=70|"), false, 0),
                Arguments.of(fix("8=FIX.5.2|9=0|10=000|"), true, 21),
                Arguments.of(fix("8=FIX.5.2|9=1|10=000|"), false, 0),
                Arguments.of(fix("8=FIX.5.2|9=5|35=A|10=005|"), true, 26),
                Arguments.of(fix("8=FIX.5.2|9=5|35=A|10=005|other stuff on the wire"), true, 26),
                Arguments.of(fix("8=FIX.5.2|9=14|35=A|18=hello|10=015|other stuff on the wire"), true, 36),
                Arguments.of(fix("x8=FIX.5.2|9=14|35=A|18=hello|10=015|other stuff on the wire").position(1), true, 37),
                Arguments.of(fix("x9=FIX.5.2|9=14|35=A|18=hello|10=015|other stuff on the wire").position(1), false, 1)
        );
    }

    @ParameterizedTest
    @MethodSource("testParseBufferResultArguments")
    void testParseBufferResult(ByteBuffer buffer, boolean result, int position) {
        int originalLimit = buffer.limit();
        final var message = new FIXMessage(new FIXConfig.Builder().build());
        assertEquals(result, message.parseBuffer(buffer));

        assertEquals(originalLimit, buffer.limit());
        assertEquals(position, buffer.position());
    }

    private static Stream<Arguments> testMessageTagsArguments() {
        return Stream.of(
                Arguments.of(fix("8=FIX.5.2|9=5|35=A|10=005|"), Map.of(35, "A")),
                Arguments.of(fix("8=FIX.5.2|9=5|35=A|10=005|other stuff on the wire"), Map.of(35, "A")),
                Arguments.of(fix("8=FIX.5.2|9=14|35=A|18=hello|10=015|other stuff on the wire"), Map.of(35, "A", 18, "hello"))
        );
    }

    @ParameterizedTest
    @MethodSource("testMessageTagsArguments")
    void testMessageTags(ByteBuffer buffer, Map<Integer, String> tags) {
        final var message = new FIXMessage(new FIXConfig.Builder().build());
        assertTrue(message.parseBuffer(buffer));

        for (var item : tags.entrySet()) {
            assertEquals(item.getValue(), message.getTag(item.getKey()).asString().toString());
        }
    }


    private static Stream<Arguments> testWriteBufferArguments() {
        return Stream.of(
                Arguments.of(
                        new FIXConfig.Builder().build(),
                        (Consumer<FIXMessage>) message -> {},
                        fix("8=FIXT.1.1|9=0|10=022|")
                ),
                Arguments.of(
                        new FIXConfig.Builder().withSessionVersion(FIXVersion.FIX_4_3).build(),
                        (Consumer<FIXMessage>) message -> {},
                        fix("8=FIX.4.3|9=0|10=199|")
                ),
                Arguments.of(
                        new FIXConfig.Builder().withSessionVersion(FIXVersion.FIX_4_3).build(),
                        (Consumer<FIXMessage>) message -> {
                            message.addTag(35).setChar('A');
                        },
                        fix("8=FIX.4.3|9=5|35=A|10=179|")
                ),
                Arguments.of(
                        new FIXConfig.Builder().withSessionVersion(FIXVersion.FIX_4_4).build(),
                        (Consumer<FIXMessage>) message -> {
                            message.addTag(35).setChar('5');
                            message.addTag(34).setInt(1091);
                            message.addTag(49).setString("TESTBUY1");
                            message.addTag(52).setString("20180920-18:24:58.675");
                            message.addTag(56).setString("TESTSELL1");
                        },
                        fix("8=FIX.4.4|9=63|35=5|34=1091|49=TESTBUY1|52=20180920-18:24:58.675|56=TESTSELL1|10=138|")
                )
        );
    }

    @ParameterizedTest
    @MethodSource("testWriteBufferArguments")
    void testWriteBuffer(FIXConfig config, Consumer<FIXMessage> consumer, ByteBuffer expected) {
        FIXMessage message = new FIXMessage(config);
        consumer.accept(message);

        final var output = ByteBuffer.allocate(expected.remaining());
        int bytes = message.writeToBuffer(output);

        output.flip();
        assertEquals(expected.remaining(), bytes);
        assertEquals(StandardCharsets.ISO_8859_1.decode(expected), StandardCharsets.ISO_8859_1.decode(output));
    }


    @Test
    void testCapsTagSize() {
        FIXConfig config = new FIXConfig.Builder().withMaxTagCapacity(1).build();

        FIXMessage message = new FIXMessage(config);

        message.addTag(1);

        assertThrowsExactly(RuntimeException.class, () -> message.addTag(2), "Too many tags created");
    }

    @Test
    void testReset() {
        FIXMessage message = new FIXMessage(new FIXConfig.Builder().build());
        message.addTag(1);

        assertNotNull(message.getTag(1));
        assertNull(message.getTag(2));
        message.reset();
        assertNull(message.getTag(1));
    }
}