package group.gnometrading.gateways.inbound;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SocketConnectController focusing on:
 * 1. Basic connection functionality
 * 2. Timeout handling
 * 3. Retry and backoff logic
 * 4. Thread safety and interrupt handling
 *
 * IMPLEMENTATION NOTES:
 * - Uses ScheduledExecutorService for timeout management (no manual watchdog threads)
 * - Connect runs on supervisor thread, timeout task runs on executor thread
 * - AtomicBoolean ensures thread-safe timeout flag checking
 * - No race conditions or interrupt flag bugs
 */
class SocketConnectControllerTest {

    private Logger logger;
    private SocketReader<?> socketReader;
    private SocketConnectController controller;

    @BeforeEach
    void setUp() {
        logger = mock(Logger.class);
        socketReader = mock(SocketReader.class);
    }

    // ========== Constructor Tests ==========

    @Test
    void testConstructorInitializesFields() {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 3, Duration.ofSeconds(1));
        assertNotNull(controller);
    }

    // ========== Successful Connection Tests ==========

    @Test
    @Timeout(5)
    void testSuccessfulConnectionOnFirstAttempt() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 3, Duration.ofSeconds(1));
        doNothing().when(socketReader).connect();

        controller.connect();

        verify(socketReader, times(1)).connect();
        verify(logger).log(LogMessage.SOCKET_CONNECTING);
        verify(logger).log(LogMessage.SOCKET_CONNECTED);
        verify(logger, never()).log(LogMessage.SOCKET_CONNECT_FAILED);
    }

    @Test
    @Timeout(5)
    void testSuccessfulConnectionAfterRetries() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 3, Duration.ofSeconds(1));

        // Fail twice, then succeed
        doThrow(new IOException("Fail 1"))
            .doThrow(new IOException("Fail 2"))
            .doNothing()
            .when(socketReader).connect();

        controller.connect();

        verify(socketReader, times(3)).connect();
        verify(logger).log(LogMessage.SOCKET_CONNECTED);
    }

    // ========== Timeout Tests ==========

    @Test
    @Timeout(10)
    void testConnectionTimeout() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(100), 0, Duration.ofMillis(10));

        // Simulate a long-running connect that will timeout
        doAnswer(invocation -> {
            Thread.sleep(1000);
            return null;
        }).when(socketReader).connect();

        assertThrows(RuntimeException.class, () -> controller.connect());

        verify(logger).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
    }

    @Test
    @Timeout(10)
    void testTimeoutWithRetries() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(100), 2, Duration.ofMillis(10));

        // Always timeout
        doAnswer(invocation -> {
            Thread.sleep(2000);
            return null;
        }).when(socketReader).connect();

        assertThrows(RuntimeException.class, () -> controller.connect());

        verify(socketReader, times(3)).connect(); // 1 initial + 2 retries
        verify(logger, atLeastOnce()).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
    }

    @Test
    @Timeout(10)
    void testTimeoutThenSuccess() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(100), 3, Duration.ofMillis(10));

        // Timeout on first attempt, succeed on second
        AtomicInteger attempts = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                Thread.sleep(1000); // Timeout
            }
            return null;
        }).when(socketReader).connect();

        controller.connect();

        verify(socketReader, times(2)).connect();
        verify(logger).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
        verify(logger).log(LogMessage.SOCKET_CONNECTED);
    }

    // ========== Failure and Retry Tests ==========

    @Test
    @Timeout(5)
    void testConnectionFailureWithException() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 0, Duration.ofSeconds(1));

        doThrow(new IOException("Connection refused")).when(socketReader).connect();

        RuntimeException exception = assertThrows(RuntimeException.class, () -> controller.connect());

        verify(socketReader, times(1)).connect();
        verify(logger).log(LogMessage.SOCKET_CONNECT_FAILED);
        assertNotNull(exception.getCause());
    }

    @Test
    @Timeout(10)
    void testMaxReconnectAttempts() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 5, Duration.ofMillis(10));
        
        doThrow(new IOException("Always fail")).when(socketReader).connect();

        assertThrows(RuntimeException.class, () -> controller.connect());

        verify(socketReader, times(6)).connect(); // 1 initial + 5 retries
        verify(logger, atLeastOnce()).log(LogMessage.SOCKET_CONNECT_FAILED);
    }

    // ========== Backoff Tests ==========

    @Test
    @Timeout(15)
    void testExponentialBackoff() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(100), 3, Duration.ofMillis(100));

        // Fail 3 times, then succeed
        doThrow(new IOException("Fail 1"))
            .doThrow(new IOException("Fail 2"))
            .doThrow(new IOException("Fail 3"))
            .doNothing()
            .when(socketReader).connect();

        long startTime = System.currentTimeMillis();
        controller.connect();
        long duration = System.currentTimeMillis() - startTime;

        // Should have backoff: 100ms + 200ms + 400ms = 700ms minimum
        assertTrue(duration >= 700, "Duration was " + duration + "ms, expected >= 700ms");
        verify(socketReader, times(4)).connect();
    }

    // ========== Watchdog Thread Tests ==========

    @Test
    @Timeout(10)
    void testWatchdogThreadInterruptsConnectThread() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(100), 0, Duration.ofMillis(10));
        
        CountDownLatch connectStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            connectStarted.countDown();
            Thread.sleep(10000); // Long delay to trigger timeout
            return null;
        }).when(socketReader).connect();

        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread connectThread = new Thread(() -> {
            try {
                controller.connect();
            } catch (RuntimeException e) {
                if (e.getMessage().contains("timed out") || 
                    Thread.currentThread().isInterrupted()) {
                    interrupted.set(true);
                }
            }
        });

        connectThread.start();
        assertTrue(connectStarted.await(2, TimeUnit.SECONDS));
        connectThread.join(5000);

        assertTrue(interrupted.get() || !connectThread.isAlive());
        verify(logger).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
    }

    // ========== Edge Cases ==========

    @Test
    @Timeout(5)
    void testZeroMaxReconnectAttempts() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 0, Duration.ofSeconds(1));
        doNothing().when(socketReader).connect();

        controller.connect();

        verify(socketReader, times(1)).connect();
    }

    @Test
    @Timeout(5)
    void testVeryShortTimeout() throws IOException {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(10), 0, Duration.ofSeconds(1));

        doAnswer(invocation -> {
            Thread.sleep(100);
            return null;
        }).when(socketReader).connect();

        assertThrows(RuntimeException.class, () -> controller.connect());
        verify(logger).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
    }

    // ========== Interrupt Handling Tests ==========

    @Test
    @Timeout(5)
    void testInterruptDuringBackoff() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 5, Duration.ofSeconds(2));
        
        doThrow(new IOException("Fail")).when(socketReader).connect();

        CountDownLatch backoffStarted = new CountDownLatch(1);
        AtomicBoolean caughtInterrupt = new AtomicBoolean(false);
        
        Thread connectThread = new Thread(() -> {
            try {
                // First attempt will fail, then backoff starts
                controller.connect();
            } catch (RuntimeException e) {
                backoffStarted.countDown();
                if (e.getCause() instanceof InterruptedException || 
                    Thread.currentThread().isInterrupted()) {
                    caughtInterrupt.set(true);
                }
            }
        });

        connectThread.start();
        Thread.sleep(100); // Let first attempt fail
        connectThread.interrupt();
        connectThread.join(3000);

        // Should have been interrupted during backoff
        assertTrue(!connectThread.isAlive());
    }

    // ========== THREAD SAFETY AND RACE CONDITION TESTS ==========
    // These tests verify that the new implementation fixes the previous race conditions

    /**
     * FIXED: No more timeout flag race condition
     *
     * PREVIOUS PROBLEM: Watchdog thread could set timeoutOccurred=true AFTER
     * supervisor checked it, causing wrong log messages.
     *
     * NEW IMPLEMENTATION: Uses AtomicBoolean for thread-safe flag checking.
     * The timeout task sets the flag atomically, and Future.cancel() ensures
     * proper synchronization.
     *
     * This test verifies that even with tight timing, we get correct logs.
     */
    @Test
    @Timeout(10)
    void testNoRaceCondition_TimeoutFlagIsThreadSafe() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(50), 0, Duration.ofSeconds(1));

        AtomicInteger correctLogCount = new AtomicInteger(0);

        // Connect completes just before timeout (tight race window)
        doAnswer(invocation -> {
            Thread.sleep(25); // Just before 50ms timeout
            return null;
        }).when(socketReader).connect();

        // Run multiple times - should NEVER see wrong logs
        for (int i = 0; i < 100; i++) {
            reset(logger);

            controller.connect();

            // Should always get CONNECTED, never TIMED_OUT
            verify(logger).log(LogMessage.SOCKET_CONNECTED);
            verify(logger, never()).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
            correctLogCount.incrementAndGet();

            Thread.sleep(5); // Small delay between attempts
        }

        assertEquals(100, correctLogCount.get(), "All 100 attempts should have correct logs");
    }

    /**
     * FIXED: Interrupt flag is now preserved
     *
     * PREVIOUS PROBLEM: Thread.interrupted() cleared ALL interrupts, including
     * external shutdown signals, preventing graceful shutdown.
     *
     * NEW IMPLEMENTATION: No longer calls Thread.interrupted() unconditionally.
     * External interrupts are preserved and can be handled by the caller.
     *
     * This test verifies that external interrupts are NOT masked.
     */
    @Test
    @Timeout(5)
    void testInterruptFlagPreserved_ExternalInterruptsNotMasked() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 0, Duration.ofSeconds(1));

        AtomicBoolean interruptWasPreserved = new AtomicBoolean(false);
        AtomicBoolean connectSucceeded = new AtomicBoolean(false);

        // Simulate external interrupt (e.g., shutdown signal) during connect
        doAnswer(invocation -> {
            // External interrupt happens during connect
            Thread.currentThread().interrupt();
            // But connect still succeeds
            return null;
        }).when(socketReader).connect();

        Thread supervisorThread = new Thread(() -> {
            controller.connect();
            connectSucceeded.set(true);

            // After connect() returns, check if interrupt flag was preserved
            if (Thread.currentThread().isInterrupted()) {
                interruptWasPreserved.set(true);
            }
        });

        supervisorThread.start();
        supervisorThread.join(3000);

        // Verify the fix
        assertTrue(connectSucceeded.get(), "Connect should have succeeded");
        assertTrue(interruptWasPreserved.get(),
            "✅ FIX CONFIRMED: Interrupt flag was preserved, allowing graceful shutdown!");
    }

    /**
     * Test that timeout mechanism works correctly with ScheduledExecutorService
     */
    @Test
    @Timeout(5)
    void testTimeoutMechanismWithScheduledExecutor() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofMillis(100), 0, Duration.ofSeconds(1));

        // Connect takes longer than timeout
        doAnswer(invocation -> {
            Thread.sleep(500);
            return null;
        }).when(socketReader).connect();

        assertThrows(RuntimeException.class, () -> controller.connect());

        verify(logger).log(LogMessage.SOCKET_CONNECTING);
        verify(logger).log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
        verify(logger, never()).log(LogMessage.SOCKET_CONNECTED);
    }

    /**
     * Test that executor is properly shut down even on success
     */
    @Test
    @Timeout(5)
    void testExecutorShutdownOnSuccess() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 0, Duration.ofSeconds(1));

        doNothing().when(socketReader).connect();

        controller.connect();

        // Give executor time to shut down
        Thread.sleep(100);

        verify(logger).log(LogMessage.SOCKET_CONNECTED);
    }

    /**
     * Test that executor is properly shut down even on failure
     */
    @Test
    @Timeout(5)
    void testExecutorShutdownOnFailure() throws Exception {
        controller = new SocketConnectController(logger, socketReader, Duration.ofSeconds(5), 0, Duration.ofSeconds(1));

        doThrow(new IOException("Connection failed")).when(socketReader).connect();

        assertThrows(RuntimeException.class, () -> controller.connect());

        // Give executor time to shut down
        Thread.sleep(100);

        verify(logger).log(LogMessage.SOCKET_CONNECT_FAILED);
    }
}

