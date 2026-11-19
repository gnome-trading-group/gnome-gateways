package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SpecChangeNoticesTest {

    private static boolean offer(final SpecChangeNotices notices, final String id, final long tick, final long nanos) {
        final byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        final byte[] padded = new byte[bytes.length + 8];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return notices.offer(padded, bytes.length, tick, nanos);
    }

    private static List<String> drain(final SpecChangeNotices notices) {
        final List<String> drained = new ArrayList<>();
        notices.drain((id, tick, nanos) -> drained.add(id + " " + tick + " " + nanos));
        return drained;
    }

    @Test
    void noticesComeOutOldestFirstAndOnce() {
        final SpecChangeNotices notices = new SpecChangeNotices(4);
        assertTrue(offer(notices, "c:a", 1_000_000, 10));
        assertTrue(offer(notices, "c:b", 10_000_000, 20));

        assertEquals(List.of("c:a 1000000 10", "c:b 10000000 20"), drain(notices));
        assertEquals(List.of(), drain(notices));
    }

    @Test
    void aFullQueueDropsAndCountsRatherThanWaiting() {
        final SpecChangeNotices notices = new SpecChangeNotices(2);
        assertTrue(offer(notices, "a", 1, 1));
        assertTrue(offer(notices, "b", 1, 2));
        assertFalse(offer(notices, "c", 1, 3));

        assertEquals(1, notices.dropped());
        assertEquals(List.of("a 1 1", "b 1 2"), drain(notices));
        assertTrue(offer(notices, "d", 1, 4), "draining makes room again");
        assertEquals(List.of("d 1 4"), drain(notices));
    }

    @Test
    void anIdLongerThanANoticeHoldsIsDropped() {
        final SpecChangeNotices notices = new SpecChangeNotices(2);
        assertFalse(offer(notices, "x".repeat(SpecChangeNotices.MAX_ID_BYTES + 1), 1, 1));
        assertEquals(1, notices.dropped());
    }

    @Test
    void readersOfferingAtOnceLoseNothingThatFitsAndMixNothing() throws Exception {
        final int readers = 4;
        final int each = 20_000;
        final SpecChangeNotices notices = new SpecChangeNotices(1 << 10);
        final CountDownLatch start = new CountDownLatch(1);
        final Thread[] threads = new Thread[readers];
        for (int r = 0; r < readers; r++) {
            final int reader = r;
            threads[r] = new Thread(() -> {
                final byte[] id = ("reader-" + reader).getBytes(StandardCharsets.US_ASCII);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < each; i++) {
                    while (!notices.offer(id, id.length, reader, i)) {
                        Thread.onSpinWait();
                    }
                }
            });
            threads[r].start();
        }
        final long[] next = new long[readers];
        final List<String> wrong = new ArrayList<>();
        start.countDown();
        int drained = 0;
        while (drained < readers * each) {
            drained += notices.drain((id, tick, nanos) -> {
                final int reader = (int) tick;
                if (!id.equals("reader-" + reader) || nanos != next[reader]) {
                    wrong.add(id + " " + tick + " " + nanos);
                }
                next[reader] = nanos + 1;
            });
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(List.of(), wrong, "each reader's notices whole and in its order");
        assertEquals(List.of(), drain(notices));
    }

    @Test
    void aSinkThatThrowsLosesOnlyTheNoticeItWasGiven() {
        final SpecChangeNotices notices = new SpecChangeNotices(4);
        offer(notices, "a", 1, 1);
        offer(notices, "b", 1, 2);
        assertThrows(
                IllegalStateException.class,
                () -> notices.drain((id, tick, nanos) -> {
                    throw new IllegalStateException(id);
                }));

        assertEquals(List.of("b 1 2"), drain(notices));
    }

    @Test
    void theCapacityIsAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new SpecChangeNotices(3));
    }
}
