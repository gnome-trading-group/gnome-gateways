package group.gnometrading.gateways.exchanges.hyperliquid;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.inbound.JsonWebSocketWriter;
import group.gnometrading.gateways.inbound.exchanges.hyperliquid.HyperliquidSocketReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SchemaEventAdapter;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.net.URI;
import org.agrona.concurrent.SystemEpochNanoClock;

public class HyperliquidIntegrationTest {

    public static void main(String[] args) throws Exception {
        Listing listing = new Listing(
                0,
                new Exchange(1, "hyperliquid", "global", SchemaType.MBP_10),
                new Security(1, "ETH", 1),
                "ETH",
                "ETH");

        SequencedRingBuffer<Mbp10Schema> outputBuffer =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        outputBuffer.handleEventsWith(new SchemaEventAdapter(HyperliquidIntegrationTest::printEvent));
        outputBuffer.start();

        WebSocketClient wsClient = new WebSocketClientBuilder()
                .withURI(new URI("wss://api.hyperliquid.xyz/ws"))
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 19)
                .build();

        JsonWebSocketWriter socketWriter = new JsonWebSocketWriter(wsClient, new JsonEncoder());

        HyperliquidSocketReader reader = new HyperliquidSocketReader(
                new NullLogger(),
                outputBuffer,
                new SystemEpochNanoClock(),
                socketWriter,
                listing,
                wsClient,
                new JsonDecoder());

        GnomeAgentRunner writerRunner = new GnomeAgentRunner(socketWriter, Throwable::printStackTrace);
        GnomeAgentRunner readerRunner = new GnomeAgentRunner(reader, Throwable::printStackTrace);

        GnomeAgentRunner.startOnThread(writerRunner);
        GnomeAgentRunner.startOnThread(readerRunner);

        while (!reader.isPaused) {
            Thread.yield();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                readerRunner.close();
                writerRunner.close();
            } catch (Exception ignored) {
            }
            outputBuffer.shutdown();
        }));

        System.out.println("Connecting to Hyperliquid...");
        reader.connect();
        System.out.println("Connected. Listening for ETH market data (30s)...");

        Thread.sleep(30_000);

        readerRunner.close();
        writerRunner.close();
        outputBuffer.shutdown();
    }

    private static void printEvent(Schema event, long sequence, boolean endOfBatch) {
        Mbp10Schema schema = (Mbp10Schema) event;
        Mbp10Decoder d = schema.decoder;

        long eventNs = d.timestampEvent();
        long recvNs = d.timestampRecv();
        long lagMs = (recvNs - eventNs) / 1_000_000L;
        long eventMinute = (eventNs / 1_000_000L) / 60_000L;
        long recvMinute = (recvNs / 1_000_000L) / 60_000L;
        boolean stale = lagMs > 1_000;
        boolean minuteMismatch = eventMinute != recvMinute;

        if (d.action() == Action.Trade) {
            System.out.printf(
                    "[TRADE]%s side=%-4s price=%.4f size=%.6f | eventMs=%d recvMs=%d lagMs=%d%s%n",
                    minuteMismatch ? " *** MINUTE MISMATCH ***" : "",
                    d.side(),
                    (double) d.price() / Statics.PRICE_SCALING_FACTOR,
                    (double) d.size() / Statics.SIZE_SCALING_FACTOR,
                    eventNs / 1_000_000L,
                    recvNs / 1_000_000L,
                    lagMs,
                    stale ? " [STALE]" : "");
        } else {
            System.out.printf(
                    "[L2 BOOK] bestBid=%.4f (%.6f) | bestAsk=%.4f (%.6f) | lagMs=%d%n",
                    (double) d.bidPrice0() / Statics.PRICE_SCALING_FACTOR,
                    (double) d.bidSize0() / Statics.SIZE_SCALING_FACTOR,
                    (double) d.askPrice0() / Statics.PRICE_SCALING_FACTOR,
                    (double) d.askSize0() / Statics.SIZE_SCALING_FACTOR,
                    lagMs);
        }
    }
}
