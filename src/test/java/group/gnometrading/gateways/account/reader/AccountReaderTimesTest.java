package group.gnometrading.gateways.account.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.schemas.ExecType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The receive time each report carries: a venue event's is its frame's arrival, and anything the reader applies off a
 * frame (the lane's notices, catch-up) is stamped when the reader applies it, however long the socket has been quiet.
 */
class AccountReaderTimesTest {

    private static final long SLOT = 9;
    private static final long FIRST_FRAME = TimeUnit.SECONDS.toNanos(1);
    // A quiet account's only frames are the keep-alive's answers, this far apart.
    private static final long QUIET = TimeUnit.SECONDS.toNanos(10);

    record Stamped(ExecType execType, long counter, long receivedNanos) {}

    private final TextAccountProtocol protocol = new TextAccountProtocol();
    private final ManyToOneRingBuffer<OrderContext> noticeQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<AccountReport> reportQueue =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
    private final ReaderNotices notices = new ReaderNotices(this.noticeQueue);
    private final long[] now = {0L};
    private AccountReader reader;

    @BeforeEach
    void setUp() {
        this.reader = new AccountReader(
                this.protocol,
                () -> this.now[0],
                64,
                this.noticeQueue,
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64),
                this.reportQueue);
        this.notices.register(RecordedReports.order(SLOT, 1, 2, 3, "a", 5));
        this.reader.beforeRead();
        this.now[0] = FIRST_FRAME;
        this.reader.onFrame(frame("ACK a"), FIRST_FRAME);
        drain();
    }

    @Test
    void aVenueEventCarriesItsFramesArrival() {
        this.now[0] = FIRST_FRAME + QUIET;
        this.reader.onFrame(frame("FILL a 1"), FIRST_FRAME + 5);

        assertEquals(List.of(new Stamped(ExecType.PARTIAL_FILL, 1, FIRST_FRAME + 5)), drain());
    }

    @Test
    void anOrderCancelledUnsentOnAQuietSocketCarriesWhenTheReaderEndedIt() {
        final OrderContext unsent = RecordedReports.order(SLOT, 2, 2, 3, "b", 5);
        this.notices.register(unsent);
        this.notices.canceledUnsent(unsent);
        this.now[0] = FIRST_FRAME + QUIET - 1;

        this.reader.beforeRead();

        assertEquals(List.of(new Stamped(ExecType.CANCEL, 2, FIRST_FRAME + QUIET - 1)), drain());
    }

    @Test
    void anOrderCancelledBeforePlacedThatTheNextFrameWakesTheReaderForCarriesThatFramesArrival() {
        final OrderContext refused = RecordedReports.order(SLOT, 2, 2, 3, "b", 5);
        this.notices.register(refused);
        this.notices.canceledBeforePlaced(refused);
        this.now[0] = FIRST_FRAME + QUIET + 2;

        this.reader.onFrame(frame("ACK nobody"), FIRST_FRAME + QUIET);

        assertEquals(
                List.of(new Stamped(ExecType.CANCEL, 2, FIRST_FRAME + QUIET)),
                drain(),
                "not the arrival of the frame before it, a keep-alive interval earlier");
    }

    @Test
    void catchUpCarriesWhenTheReaderAppliedIt() {
        this.protocol.catchUp = List.of("FILL a 1");
        this.protocol.fetchCatchUp(0, List.of());
        this.reader.catchUpFetched();
        this.now[0] = FIRST_FRAME + QUIET;

        this.reader.beforeRead();

        assertEquals(List.of(new Stamped(ExecType.PARTIAL_FILL, 1, FIRST_FRAME + QUIET)), drain());
    }

    private List<Stamped> drain() {
        final List<Stamped> stamped = new ArrayList<>();
        this.reportQueue.read(
                out -> stamped.add(new Stamped(
                        out.report.decoder.execType(),
                        out.report.getClientOidCounter(),
                        out.report.decoder.timestampRecv())),
                Integer.MAX_VALUE);
        return stamped;
    }

    private static ByteBuffer frame(final String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }
}
