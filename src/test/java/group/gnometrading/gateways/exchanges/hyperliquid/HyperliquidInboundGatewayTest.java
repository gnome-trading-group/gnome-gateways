package group.gnometrading.gateways.exchanges.hyperliquid;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.schemas.*;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HyperliquidInboundGatewayTest {

    private static final int EXCHANGE_ID = 47;
    private static final int SECURITY_ID = 124;

    @Mock
    private Publication publication;

    private HyperliquidInboundGateway gateway;

    @BeforeEach
    void setup() {
        ByteBuffer buffer = ByteBuffer.allocate(1 << 12);
        gateway = new HyperliquidInboundGateway(
                publication,
                null,
                SchemaType.MBP_10,
                null,
                null,
                buffer,
                new Listing(0, EXCHANGE_ID, SECURITY_ID, null, null),
                new JSONEncoder()
        );
    }

    private static Stream<Arguments> generateL2JSON() {
        return Stream.of(
                Arguments.of(
                        book("", ""),
                        l2(1740669530496L, l("138.2|7.01|1"), l("138.22|733.9|2")),
                        List.of(
                                mbpL2(1740669530496L, 0,
                                        l("138.2|7.01|1"),
                                        l("138.22|733.9|2")
                                )
                        )
                ),
                Arguments.of(
                        book("138.1|7|2", "138.22|733.9|2"),
                        l2(1740669530496L, l("138.2|7.01|1"), l("138.22|733.9|2")),
                        List.of(
                                mbpL2(1740669530496L, 0,
                                        l("138.2|7.01|1"),
                                        l("138.22|733.9|2")
                                )
                        )
                ),
                Arguments.of(
                        book("138.1|7|2", "138.22|733.9|2"),
                        l2(1740669530496L, l("138.1|6.01|3"), l("138.22|733.9|2")),
                        List.of(
                                mbpL2(1740669530496L, 0,
                                        l("138.1|6.01|3"),
                                        l("138.22|733.9|2")
                                )
                        )
                ),
                Arguments.of(
                        book("138.2|5|4 138.1|7|2", "138.22|733.9|2"),
                        l2(1740669530496L, l("138.2|5|4 138.1|6.01|3"), l("138.22|733.9|2")),
                        List.of(
                                mbpL2(1740669530496L, 1,
                                        l("138.2|5|4 138.1|6.01|3"),
                                        l("138.22|733.9|2")
                                )
                        )
                ),
                Arguments.of(
                        book("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9", "1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9"),
                        l2(1740669530456L, l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9"), l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9")),
                        List.of(
                                mbpL2(1740669530456L, MBP10Encoder.depthNullValue(),
                                        l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9"),
                                        l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9")
                                )
                        )
                ),
                Arguments.of(
                        book("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9", "1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9"),
                        l2(1740669530456L, l("1|1.5|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10"), l("1|1|1 2|2.5|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10")),
                        List.of(
                                mbpL2(1740669530456L, 0,
                                        l("1|1.5|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10"),
                                        l("1|1|1 2|2.5|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10")
                                )
                        )
                ),
                Arguments.of(
                        book("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9", "1|1|1 2|2|2 3|3|3 4|4|4 5|5|5"),
                        l2(1740669530456L, l("1|1.5|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10"), l("1|1|1 2|2.5|2 3|3|3 4|4|4 5|5|5")),
                        List.of(
                                mbpL2(1740669530456L, 0,
                                        l("1|1.5|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10"),
                                        l("1|1|1 2|2.5|2 3|3|3 4|4|4 5|5|5")
                                )
                        )
                ),
                Arguments.of(
                        book("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9", "1|1|1 2|2|2 3|3|3 4|4|4 5|5|5"),
                        l2(1740669530456L, l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10"), l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6")),
                        List.of(
                                mbpL2(1740669530456L, 5,
                                        l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6 7|7|7 8|8|8 9|9|9 10|10|10"),
                                        l("1|1|1 2|2|2 3|3|3 4|4|4 5|5|5 6|6|6")
                                )
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("generateL2JSON")
    void testL2Book(Book initial, String message, List<String> messages) {
        initial.update(gateway);
        ByteBuffer input = ByteBuffer.wrap(message.getBytes());
        try (var node = new JSONDecoder().wrap(input)) {
            gateway.handleJSONMessage(node);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ArgumentCaptor<DirectBuffer> captor = ArgumentCaptor.forClass(DirectBuffer.class);
        verify(publication, times(messages.size())).offer(captor.capture());
        var captured = captor.getAllValues();
        assertEquals(messages.size(), captured.size());

        for (int i = 0; i < messages.size(); i++) {
            assertEquals(messages.get(i), new MBP10Decoder().wrapAndApplyHeader(captured.get(i), 0, new MessageHeaderDecoder()).toString());
        }
    }

    private static Stream<Arguments> generateTradesJSON() {
        return Stream.of(
                Arguments.of(
                        book("", ""),
                        trades(1740669630496L, Side.Ask, "138.2|5"),
                        List.of(
                                mbpTrade(1740669630496L, Side.Ask, 138.2, 5, List.of(), List.of())
                        )
                ),
                Arguments.of(
                        book("", "138.2|5|1"),
                        trades(1740669630496L, Side.Ask, "138.2|5"),
                        List.of(
                                mbpTrade(1740669630496L, Side.Ask, 138.2, 5, List.of(), l("138.2|5|1"))
                        )
                ),
                Arguments.of(
                        book("", "138.2|5|1"),
                        trades(1740669630496L, Side.Bid, "138.2|5", "138.21|10"),
                        List.of(
                                mbpTrade(1740669630496L, Side.Bid, 138.2, 5, List.of(), l("138.2|5|1")),
                                mbpTrade(1740669630496L, Side.Bid, 138.21, 10, List.of(), l("138.2|5|1"))
                        )
                ),
                Arguments.of(
                        book("130.5|1|1", "138.2|5|1"),
                        trades(1740669630496L, Side.Bid, "138.2|5", "138.21|10", "138.25|15"),
                        List.of(
                                mbpTrade(1740669630496L, Side.Bid, 138.2, 5, l("130.5|1|1"), l("138.2|5|1")),
                                mbpTrade(1740669630496L, Side.Bid, 138.21, 10, l("130.5|1|1"), l("138.2|5|1")),
                                mbpTrade(1740669630496L, Side.Bid, 138.25, 15, l("130.5|1|1"), l("138.2|5|1"))
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("generateTradesJSON")
    void testTrades(Book initial, String message, List<String> messages) {
        initial.update(gateway);
        List<String> capturedMessages = new ArrayList<>();

        doAnswer((Answer<Long>) invocation -> {
            DirectBuffer buffer = invocation.getArgument(0);
            capturedMessages.add(
                    new MBP10Decoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder()).toString()
            );
            return 0L;
        }).when(publication).offer(any(DirectBuffer.class));

        ByteBuffer input = ByteBuffer.wrap(message.getBytes());
        try (var node = new JSONDecoder().wrap(input)) {
            gateway.handleJSONMessage(node);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        verify(publication, times(messages.size())).offer(any(DirectBuffer.class));
        assertEquals(messages.size(), capturedMessages.size());

        for (int i = 0; i < messages.size(); i++) {
            assertEquals(messages.get(i), capturedMessages.get(i));
        }
    }

    private static String mbpTrade(long time, Side side, double px, double sz, List<Level> bids, List<Level> asks) {
        MBP10Schema schema = new MBP10Schema();
        MBP10Encoder encoder = schema.encoder;
        encoder.exchangeId(EXCHANGE_ID);
        encoder.securityId(SECURITY_ID);
        encoder.timestampRecv(0);
        encoder.timestampEvent(time * 1_000_000);
        encoder.timestampSent(MBP10Encoder.timestampSentNullValue());
        encoder.price((long) (px * Statics.PRICE_SCALING_FACTOR));
        encoder.size((long) (sz * Statics.SIZE_SCALING_FACTOR));
        encoder.action(Action.Trade);
        encoder.side(side);
        encoder.depth(MBP10Encoder.depthNullValue());
        encoder.sequence(MBP10Encoder.sequenceNullValue());
        encoder.flags().clear();

        writeLevels(encoder, bids, asks);

        return encoder.toString();
    }

    private static String mbpL2(long time, int depth, List<Level> bids, List<Level> asks) {
        MBP10Schema schema = new MBP10Schema();
        MBP10Encoder encoder = schema.encoder;
        encoder.exchangeId(EXCHANGE_ID);
        encoder.securityId(SECURITY_ID);
        encoder.timestampRecv(0);
        encoder.timestampEvent(time * 1_000_000);
        encoder.timestampSent(MBP10Encoder.timestampSentNullValue());
        encoder.price(MBP10Encoder.priceNullValue());
        encoder.size(MBP10Encoder.sizeNullValue());
        encoder.action(Action.Modify);
        encoder.side(Side.None);
        encoder.depth((short) depth);
        encoder.sequence(MBP10Encoder.sequenceNullValue());

        encoder.flags().clear();
        encoder.flags().marketByPrice(true);

        writeLevels(encoder, bids, asks);

        return encoder.toString();
    }

    private static void writeLevels(MBP10Encoder encoder, List<Level> bids, List<Level> asks) {
        if (bids.size() > 0) {
            encoder.bidPrice0((long) (bids.get(0).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize0((long) (bids.get(0).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount0(bids.get(0).n);
        } else {
            encoder.bidPrice0(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize0(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount0(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 1) {
            encoder.bidPrice1((long) (bids.get(1).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize1((long) (bids.get(1).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount1(bids.get(1).n);
        } else {
            encoder.bidPrice1(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize1(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount1(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 2) {
            encoder.bidPrice2((long) (bids.get(2).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize2((long) (bids.get(2).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount2(bids.get(2).n);
        } else {
            encoder.bidPrice2(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize2(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount2(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 3) {
            encoder.bidPrice3((long) (bids.get(3).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize3((long) (bids.get(3).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount3(bids.get(3).n);
        } else {
            encoder.bidPrice3(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize3(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount3(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 4) {
            encoder.bidPrice4((long) (bids.get(4).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize4((long) (bids.get(4).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount4(bids.get(4).n);
        } else {
            encoder.bidPrice4(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize4(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount4(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 5) {
            encoder.bidPrice5((long) (bids.get(5).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize5((long) (bids.get(5).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount5(bids.get(5).n);
        } else {
            encoder.bidPrice5(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize5(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount5(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 6) {
            encoder.bidPrice6((long) (bids.get(6).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize6((long) (bids.get(6).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount6(bids.get(6).n);
        } else {
            encoder.bidPrice6(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize6(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount6(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 7) {
            encoder.bidPrice7((long) (bids.get(7).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize7((long) (bids.get(7).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount7(bids.get(7).n);
        } else {
            encoder.bidPrice7(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize7(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount7(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 8) {
            encoder.bidPrice8((long) (bids.get(8).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize8((long) (bids.get(8).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount8(bids.get(8).n);
        } else {
            encoder.bidPrice8(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize8(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount8(MBP10Encoder.bidCount0NullValue());
        }

        if (bids.size() > 9) {
            encoder.bidPrice9((long) (bids.get(9).px * Statics.PRICE_SCALING_FACTOR));
            encoder.bidSize9((long) (bids.get(9).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.bidCount9(bids.get(9).n);
        } else {
            encoder.bidPrice9(MBP10Encoder.bidPrice0NullValue());
            encoder.bidSize9(MBP10Encoder.bidSize0NullValue());
            encoder.bidCount9(MBP10Encoder.bidCount0NullValue());
        }

        if (asks.size() > 0) {
            encoder.askPrice0((long) (asks.get(0).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize0((long) (asks.get(0).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount0(asks.get(0).n);
        } else {
            encoder.askPrice0(MBP10Encoder.askPrice0NullValue());
            encoder.askSize0(MBP10Encoder.askSize0NullValue());
            encoder.askCount0(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 1) {
            encoder.askPrice1((long) (asks.get(1).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize1((long) (asks.get(1).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount1(asks.get(1).n);
        } else {
            encoder.askPrice1(MBP10Encoder.askPrice0NullValue());
            encoder.askSize1(MBP10Encoder.askSize0NullValue());
            encoder.askCount1(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 2) {
            encoder.askPrice2((long) (asks.get(2).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize2((long) (asks.get(2).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount2(asks.get(2).n);
        } else {
            encoder.askPrice2(MBP10Encoder.askPrice0NullValue());
            encoder.askSize2(MBP10Encoder.askSize0NullValue());
            encoder.askCount2(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 3) {
            encoder.askPrice3((long) (asks.get(3).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize3((long) (asks.get(3).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount3(asks.get(3).n);
        } else {
            encoder.askPrice3(MBP10Encoder.askPrice0NullValue());
            encoder.askSize3(MBP10Encoder.askSize0NullValue());
            encoder.askCount3(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 4) {
            encoder.askPrice4((long) (asks.get(4).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize4((long) (asks.get(4).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount4(asks.get(4).n);
        } else {
            encoder.askPrice4(MBP10Encoder.askPrice0NullValue());
            encoder.askSize4(MBP10Encoder.askSize0NullValue());
            encoder.askCount4(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 5) {
            encoder.askPrice5((long) (asks.get(5).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize5((long) (asks.get(5).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount5(asks.get(5).n);
        } else {
            encoder.askPrice5(MBP10Encoder.askPrice0NullValue());
            encoder.askSize5(MBP10Encoder.askSize0NullValue());
            encoder.askCount5(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 6) {
            encoder.askPrice6((long) (asks.get(6).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize6((long) (asks.get(6).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount6(asks.get(6).n);
        } else {
            encoder.askPrice6(MBP10Encoder.askPrice0NullValue());
            encoder.askSize6(MBP10Encoder.askSize0NullValue());
            encoder.askCount6(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 7) {
            encoder.askPrice7((long) (asks.get(7).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize7((long) (asks.get(7).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount7(asks.get(7).n);
        } else {
            encoder.askPrice7(MBP10Encoder.askPrice0NullValue());
            encoder.askSize7(MBP10Encoder.askSize0NullValue());
            encoder.askCount7(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 8) {
            encoder.askPrice8((long) (asks.get(8).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize8((long) (asks.get(8).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount8(asks.get(8).n);
        } else {
            encoder.askPrice8(MBP10Encoder.askPrice0NullValue());
            encoder.askSize8(MBP10Encoder.askSize0NullValue());
            encoder.askCount8(MBP10Encoder.askCount0NullValue());
        }

        if (asks.size() > 9) {
            encoder.askPrice9((long) (asks.get(9).px * Statics.PRICE_SCALING_FACTOR));
            encoder.askSize9((long) (asks.get(9).sz * Statics.SIZE_SCALING_FACTOR));
            encoder.askCount9(asks.get(9).n);
        } else {
            encoder.askPrice9(MBP10Encoder.askPrice0NullValue());
            encoder.askSize9(MBP10Encoder.askSize0NullValue());
            encoder.askCount9(MBP10Encoder.askCount0NullValue());
        }
    }

    private static List<Level> l(String levels) {
        List<Level> out = new ArrayList<>();
        for (String input : levels.split(" ")) {
            String[] parts = input.split("\\|");
            Level level = new Level(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Integer.parseInt(parts[2]));
            out.add(level);
        }
        return out;
    }

    private static String tradeJson(long time, Side side, String trade) {
        String[] parts = trade.split("\\|");
        return """
                {
                    "coin": "SOL",
                    "side": "%s",
                    "px": "%.2f",
                    "sz": "%.2f",
                    "time": %d,
                    "hash": "xxxxx",
                    "tid": 123456789,
                    "users": ["xxx1", "xxx2"]
                }
                """.formatted(side == Side.Ask ? "A" : "B", Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), time);
    }

    private static String trades(long time, Side side, String... trades) {
        List<String> out = new ArrayList<>();
        for (String trade : trades) {
            out.add(tradeJson(time, side, trade));
        }
        return """
                {"channel": "trades", "data": %s}
                """.formatted(out);
    }

    private static String l2(long time, List<Level> bids, List<Level> asks) {
        return """
                {"channel": "l2Book", "data": {"coin": "SOL", "time": %d, "levels": [%s, %s]}}
                """.formatted(time, bids, asks);
    }

    private static Book book(String bids, String asks) {
        // Example: "138.2|500.1|2 138.4|10.4|2"
        Book book = new Book();
        book.parseSide(asks, book.asks);
        book.parseSide(bids, book.bids);
        return book;
    }

    private static class Book {
        List<Level> asks = new ArrayList<>(), bids = new ArrayList<>();

        public void parseSide(String input, List<Level> output) {
            if (input.isEmpty()) return;
            for (String part : input.split(" ")) {
                String[] parts = part.split("\\|");
                Level level = new Level(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Integer.parseInt(parts[2]));
                output.add(level);
            }
        }

        public void update(HyperliquidInboundGateway gateway) {
            for (int i = 0; i < gateway.asks.length; i++) {
                if (asks.size() > i) {
                    gateway.asks[i].update(
                            (long) (asks.get(i).px * Statics.PRICE_SCALING_FACTOR),
                            (long) (asks.get(i).sz * Statics.SIZE_SCALING_FACTOR),
                            asks.get(i).n
                    );
                }
                if (bids.size() > i) {
                    gateway.bids[i].update(
                            (long) (bids.get(i).px * Statics.PRICE_SCALING_FACTOR),
                            (long) (bids.get(i).sz * Statics.SIZE_SCALING_FACTOR),
                            bids.get(i).n
                    );
                }
            }
        }
    }

    private static class Level {
        double px, sz;
        int n;

        public Level(double px, double sz, int n) {
            this.px = px;
            this.sz = sz;
            this.n = n;
        }

        public String toString() {
            return """
                    {"px": "%.2f", "sz": "%.2f", "n": %d}
                    """.formatted(px, sz, n);
        }
    }
}