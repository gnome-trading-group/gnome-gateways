package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.inbound.mbp.MBP10Book;
import group.gnometrading.gateways.inbound.mbp.MBP10SchemaFactory;
import group.gnometrading.schemas.MBP10Schema;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SocketReader focusing on doWork() and connect() methods.
 * Tests thread safety between the tight-loop worker thread and supervisor thread.
 */
class SocketReaderTest {

    private Disruptor<MBP10Schema> disruptor;
    private RingBuffer<MBP10Schema> ringBuffer;
    private TestSocketReader socketReader;
    private EpochNanoClock clock;

    @BeforeEach
    void setUp() {
        disruptor = new Disruptor<>(
                MBP10Schema::new,
                1024,
                DaemonThreadFactory.INSTANCE
        );
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
        clock = System::nanoTime;
    }

    @AfterEach
    void tearDown() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }

    // ========== doWork Tests ==========

    @Test
    void testDoWorkWhenPaused() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = true;

        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(socketReader, null));
        sleep(100);

        assertTrue(socketReader.isPaused);
        assertEquals(0, socketReader.readSocketCallCount.get());
    }

    @Test
    void testDoWorkWhenNotPaused() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;
        socketReader.addNextReadResult(ByteBuffer.wrap("test".getBytes()));

        int result = socketReader.doWork();

        assertEquals(0, result);
        assertEquals(1, socketReader.readSocketCallCount.get());
        assertEquals(4, socketReader.handleMessageByteCount.get());
    }

    @Test
    void testDoWorkWithNullBuffer() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        int result = socketReader.doWork();

        assertEquals(0, result);
        assertEquals(1, socketReader.readSocketCallCount.get());
        assertEquals(0, socketReader.handleMessageByteCount.get());
    }

    @Test
    void testDoWorkWithEmptyBuffer() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;
        ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
        socketReader.addNextReadResult(emptyBuffer);

        int result = socketReader.doWork();

        assertEquals(0, result);
        assertEquals(1, socketReader.readSocketCallCount.get());
        assertEquals(0, socketReader.handleMessageByteCount.get());
    }

    @Test
    void testDoWorkProcessesMultipleMessages() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        // Create buffer with multiple "messages" (each byte is a message in our test)
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        socketReader.addNextReadResult(buffer);

        int result = socketReader.doWork();

        assertEquals(0, result);
        assertEquals(1, socketReader.readSocketCallCount.get());
        assertEquals(5, socketReader.handleMessageByteCount.get());
    }

    @Test
    void testDoWorkSetsRecvTimestamp() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;
        socketReader.addNextReadResult(ByteBuffer.wrap("test".getBytes()));

        long beforeTime = clock.nanoTime();
        socketReader.doWork();
        long afterTime = clock.nanoTime();

        assertTrue(socketReader.recvTimestamp >= beforeTime);
        assertTrue(socketReader.recvTimestamp <= afterTime);
    }

    // ========== connect Tests ==========

    @Test
    void testConnectResetsBuffers() throws IOException, InterruptedException {
        socketReader = new TestSocketReader(ringBuffer, clock);
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(socketReader, null));
        sleep(100);

        // Add some data to replay buffer
        socketReader.buffer = true;
        socketReader.schema.encoder.sequence(100L);
        socketReader.offer();

        socketReader.connect();

        assertTrue(socketReader.attachSocketCalled.get());
        assertTrue(socketReader.fetchSnapshotCalled.get());
    }

    @Test
    void testConnectSetsCorrectFlags() throws IOException {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = true;
        socketReader.buffer = false;
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(socketReader, null));

        // Start connect in supervisor thread
        Thread supervisor = new Thread(() -> {
            try {
                socketReader.connect();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        supervisor.start();

        // Give it time to set initial flags
        sleep(50);

        assertFalse(socketReader.pause);
        assertFalse(socketReader.buffer);
        assertFalse(socketReader.isPaused);


        // Wait for connect to complete
        try {
            supervisor.join(1000);
        } catch (InterruptedException e) {
            fail("Supervisor thread interrupted");
        }

        assertFalse(supervisor.isAlive());
        assertFalse(socketReader.pause);
        assertFalse(socketReader.isPaused);
    }

    @Test
    void testConnectWithSnapshot() throws IOException {
        socketReader = new TestSocketReader(ringBuffer, clock, true);
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(socketReader, null));

        // Set up snapshot
        MBP10Book snapshot = new MBP10Book();
        snapshot.sequenceNumber = 1000L;
        snapshot.bids[0].update(50000L, 100L, 1L);
        socketReader.setSnapshot(snapshot);

        var schema1 = new MBP10Schema();
        schema1.encoder.sequence(999L);
        socketReader.addNextReadResult(schema1.buffer, schema1.totalMessageSize());

        var schema2 = new MBP10Schema();
        schema2.encoder.sequence(1001L);
        socketReader.addNextReadResult(schema2.buffer, schema2.totalMessageSize());

        socketReader.connect();

        // Internal book should be updated from snapshot and replay buffer
        assertEquals(1001L, socketReader.internalBook.getSequenceNumber());
    }

    @Test
    void testConnectWithoutSnapshot() throws IOException {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.setSnapshot(null);
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(socketReader, null));

        socketReader.connect();

        assertTrue(socketReader.fetchSnapshotCalled.get());
    }

    // ========== Thread Safety Tests ==========

    @Test
    @Timeout(10)
    void testDoWorkAndConnectConcurrency() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        AtomicBoolean workerRunning = new AtomicBoolean(true);
        AtomicInteger doWorkCalls = new AtomicInteger(0);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch connectComplete = new CountDownLatch(1);
        List<Exception> exceptions = new ArrayList<>();

        // Worker thread (tight loop)
        Thread worker = new Thread(() -> {
            workerStarted.countDown();
            while (workerRunning.get()) {
                try {
                    socketReader.addNextReadResult(ByteBuffer.wrap(new byte[]{1}));
                    socketReader.doWork();
                    doWorkCalls.incrementAndGet();
                    Thread.yield();
                } catch (Exception e) {
                    exceptions.add(e);
                    break;
                }
            }
        });

        // Supervisor thread
        Thread supervisor = new Thread(() -> {
            try {
                workerStarted.await();
                sleep(100); // Let worker run for a bit
                socketReader.connect();
                connectComplete.countDown();
            } catch (Exception e) {
                exceptions.add(e);
            }
        });

        worker.start();
        supervisor.start();

        // Wait for connect to complete
        assertTrue(connectComplete.await(5, TimeUnit.SECONDS), "Connect did not complete");

        // Stop worker
        workerRunning.set(false);
        worker.join(1000);
        supervisor.join(1000);

        // Verify no exceptions
        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred: " + exceptions);
        }

        // Verify doWork was called multiple times
        assertTrue(doWorkCalls.get() > 0, "doWork should have been called");

        // Verify final state
        assertFalse(socketReader.pause);
    }

    @Test
    @Timeout(10)
    void testMultipleConnectCalls() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);

        AtomicBoolean workerRunning = new AtomicBoolean(true);
        CountDownLatch workerStarted = new CountDownLatch(1);
        List<Exception> exceptions = new ArrayList<>();

        // Worker thread
        Thread worker = new Thread(() -> {
            workerStarted.countDown();
            while (workerRunning.get()) {
                try {
                    socketReader.addNextReadResult(ByteBuffer.wrap(new byte[]{1}));
                    socketReader.doWork();
                    Thread.yield();
                } catch (Exception e) {
                    exceptions.add(e);
                    break;
                }
            }
        });

        worker.start();
        workerStarted.await();

        // Call connect multiple times
        for (int i = 0; i < 3; i++) {
            socketReader.connect();
            sleep(50);
            socketReader.disconnect();
        }

        workerRunning.set(false);
        worker.join(1000);

        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred: " + exceptions);
        }

        assertEquals(3, socketReader.connectCallCount.get());
    }

    @Test
    @Timeout(10)
    void testPauseLatchSynchronization() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        CountDownLatch workerReady = new CountDownLatch(1);
        AtomicBoolean workerAcknowledged = new AtomicBoolean(false);

        // Worker thread
        Thread worker = new Thread(() -> {
            workerReady.countDown();
            try {
                while (true) {
                    socketReader.doWork();
                    workerAcknowledged.set(true);
                    Thread.yield();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        worker.start();
        workerReady.await();

        // Set pause flag
        socketReader.pause = true;

        // Wait for worker to acknowledge
        worker.join(100);

        assertTrue(workerAcknowledged.get(), "Worker should have acknowledged pause");
        assertTrue(socketReader.isPaused);
    }

    // ========== Advanced Race Condition Tests ==========

    @Test
    @Timeout(10)
    void testConnectWhileDoWorkIsProcessingMessages() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        AtomicBoolean workerRunning = new AtomicBoolean(true);
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch connectStarted = new CountDownLatch(1);
        List<Exception> exceptions = new ArrayList<>();

        // Worker thread processing messages continuously
        Thread worker = new Thread(() -> {
            workerStarted.countDown();
            while (workerRunning.get()) {
                try {
                    // Simulate continuous message flow
                    socketReader.addNextReadResult(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}));
                    socketReader.doWork();
                    messagesProcessed.addAndGet(5);
                    Thread.yield();
                } catch (Exception e) {
                    exceptions.add(e);
                    break;
                }
            }
        });

        // Supervisor thread calling connect
        Thread supervisor = new Thread(() -> {
            try {
                workerStarted.await();
                sleep(50); // Let worker process some messages
                connectStarted.countDown();
                socketReader.connect();
            } catch (Exception e) {
                exceptions.add(e);
            }
        });

        worker.start();
        supervisor.start();

        // Wait for connect to start
        assertTrue(connectStarted.await(2, TimeUnit.SECONDS));

        // Wait for connect to complete
        supervisor.join(5000);
        assertFalse(supervisor.isAlive(), "Supervisor should have completed");

        // Stop worker
        workerRunning.set(false);
        worker.join(1000);

        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred: " + exceptions);
        }

        assertTrue(messagesProcessed.get() > 0, "Worker should have processed messages");
        assertFalse(socketReader.pause, "Should not be paused after connect");
    }

    @Test
    @Timeout(10)
    void testRapidConnectDisconnectCycles() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);

        AtomicBoolean workerRunning = new AtomicBoolean(true);
        CountDownLatch workerStarted = new CountDownLatch(1);
        List<Exception> exceptions = new ArrayList<>();

        // Worker thread
        Thread worker = new Thread(() -> {
            workerStarted.countDown();
            while (workerRunning.get()) {
                try {
                    socketReader.addNextReadResult(ByteBuffer.wrap(new byte[]{1}));
                    socketReader.doWork();
                    Thread.yield();
                } catch (Exception e) {
                    exceptions.add(e);
                    break;
                }
            }
        });

        worker.start();
        workerStarted.await();

        // Rapid connect/disconnect cycles
        for (int i = 0; i < 5; i++) {
            socketReader.connect();
            sleep(20);
            socketReader.disconnect();
            sleep(20);
        }

        workerRunning.set(false);
        worker.join(1000);

        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred: " + exceptions);
        }

        assertTrue(socketReader.connectCallCount.get() >= 5);
    }

    @Test
    @Timeout(10)
    void testOfferBufferLatchBehavior() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);

        // When buffering
        socketReader.buffer = true;
        socketReader.schema.encoder.sequence(100L);
        socketReader.offer();

        // When not buffering
        socketReader.buffer = false;
        socketReader.offer();
    }

    @Test
    @Timeout(10)
    void testReplayBufferDuringConnect() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock, true);
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(socketReader, null));

        // Add messages to replay buffer
        for (int i = 0; i < 10; i++) {
            socketReader.schema.encoder.sequence(i);
            socketReader.addNextReadResult(socketReader.schema.buffer, socketReader.schema.totalMessageSize());
        }

        // Set snapshot with sequence 5
        MBP10Book snapshot = new MBP10Book();
        snapshot.sequenceNumber = 5L;
        socketReader.setSnapshot(snapshot);

        socketReader.connect();

        // Replay buffer should be empty after connect
        // (messages before snapshot discarded, messages after applied to internal book)
        assertEquals(9L, socketReader.internalBook.getSequenceNumber());
    }

    @Test
    @Timeout(10)
    void testVolatileFlagVisibility() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        AtomicBoolean workerSawPause = new AtomicBoolean(false);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch pauseSet = new CountDownLatch(1);

        // Worker thread
        Thread worker = new Thread(() -> {
            workerStarted.countDown();
            try {
                pauseSet.await();
                // Give supervisor time to set pause flag
                sleep(10);
                if (socketReader.pause) {
                    workerSawPause.set(true);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        worker.start();
        workerStarted.await();

        // Supervisor sets pause flag
        socketReader.pause = true;
        pauseSet.countDown();

        worker.join(1000);

        assertTrue(workerSawPause.get(), "Worker should see pause flag due to volatile");
    }

    @Test
    @Timeout(10)
    void testStressTestDoWorkAndConnect() throws Exception {
        socketReader = new TestSocketReader(ringBuffer, clock);
        socketReader.pause = false;

        AtomicBoolean workerRunning = new AtomicBoolean(true);
        AtomicInteger doWorkCalls = new AtomicInteger(0);
        AtomicInteger connectCalls = new AtomicInteger(0);
        CountDownLatch workersStarted = new CountDownLatch(2);
        List<Exception> exceptions = new ArrayList<>();

        // Worker thread (tight loop)
        Thread worker = new Thread(() -> {
            workersStarted.countDown();
            while (workerRunning.get()) {
                try {
                    socketReader.addNextReadResult(ByteBuffer.wrap(new byte[]{1, 2, 3}));
                    socketReader.doWork();
                    doWorkCalls.incrementAndGet();
                } catch (Exception e) {
                    exceptions.add(e);
                    break;
                }
            }
        });

        // Supervisor thread (multiple connects)
        Thread supervisor = new Thread(() -> {
            workersStarted.countDown();
            try {
                for (int i = 0; i < 10; i++) {
                    socketReader.connect();
                    connectCalls.incrementAndGet();
                    sleep(10);
                }
            } catch (Exception e) {
                exceptions.add(e);
            }
        });

        worker.start();
        supervisor.start();

        workersStarted.await();
        supervisor.join(10000);

        workerRunning.set(false);
        worker.join(1000);

        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred: " + exceptions);
        }

        assertTrue(doWorkCalls.get() > 0, "doWork should have been called");
        assertEquals(10, connectCalls.get(), "connect should have been called 10 times");
    }

    // ========== Helper Methods ==========

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Test implementation of SocketReader for testing purposes.
     */
    static class TestSocketReader extends SocketReader<MBP10Schema> implements MBP10SchemaFactory {

        final AtomicInteger readSocketCallCount = new AtomicInteger(0);
        final AtomicInteger handleMessageByteCount = new AtomicInteger(0);
        final AtomicInteger connectCallCount = new AtomicInteger(0);
        final AtomicBoolean attachSocketCalled = new AtomicBoolean(false);
        final AtomicBoolean fetchSnapshotCalled = new AtomicBoolean(false);
        final AtomicBoolean pendingReads = new AtomicBoolean(false);

        private final Deque<ByteBuffer> readResults = new ArrayDeque<>();
        private Book<MBP10Schema> snapshot;
        private final boolean shouldOfferBuffer;

        public TestSocketReader(RingBuffer<MBP10Schema> outputBuffer, EpochNanoClock clock) {
            this(outputBuffer, clock, false);
        }

        public TestSocketReader(RingBuffer<MBP10Schema> outputBuffer, EpochNanoClock clock, boolean shouldOfferBuffer) {
            super(outputBuffer, clock, null);
            this.shouldOfferBuffer = shouldOfferBuffer;
        }

        public void addNextReadResult(ByteBuffer buffer) {
            readResults.add(buffer);
            pendingReads.set(true);
        }

        public void addNextReadResult(UnsafeBuffer buffer, int size) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(size);
            buffer.getBytes(0, byteBuffer, 0, size);
            readResults.add(byteBuffer);
            pendingReads.set(true);
        }

        public void setSnapshot(Book<MBP10Schema> snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        protected ByteBuffer readSocket() throws IOException {
            readSocketCallCount.incrementAndGet();
            if (!readResults.isEmpty()) {
                return readResults.removeFirst();
            }
            return null;
        }

        @Override
        protected void handleGatewayMessage(ByteBuffer buffer) {
            if (shouldOfferBuffer) {
                this.schema.buffer.putBytes(0, buffer, buffer.remaining());
                offer();
            } else {
                // Process one byte at a time to simulate multiple messages
                if (buffer.hasRemaining()) {
                    buffer.get();
                    handleMessageByteCount.incrementAndGet();
                }
            }
            // Check if we have processed all pending reads
            if (readResults.isEmpty()) {
                pendingReads.set(false);
            }
        }

        @Override
        protected void keepAlive() throws IOException {
            // No-op for testing
        }

        @Override
        public Book<MBP10Schema> fetchSnapshot() throws IOException {
            fetchSnapshotCalled.set(true);
            while (pendingReads.get()) {
                Thread.yield();
            }
            return snapshot;
        }

        @Override
        protected void attachSocket() throws IOException {
            attachSocketCalled.set(true);
            connectCallCount.incrementAndGet();
        }

        @Override
        protected void disconnectSocket() throws Exception {
            // No-op for testing
        }
    }
}

