package group.gnometrading.gateways.account.lane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A local HTTP server answering a lane's requests from a script, recording each and how many overlapped. */
public final class FakeVenueServer implements AutoCloseable {

    public record Request(String method, String path, String body, Map<String, String> headers, long receivedNanos) {
        public String header(final String name) {
            return this.headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Reply(int status, String body, long delayMillis) {
        public static Reply ok(final String body) {
            return new Reply(200, body, 0);
        }

        public static Reply status(final int status) {
            return new Reply(status, "", 0);
        }

        public Reply after(final long millis) {
            return new Reply(this.status, this.body, millis);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new ArrayList<>();
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private volatile Function<Request, Reply> script = request -> Reply.status(500);

    public FakeVenueServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(this.executor);
        this.server.start();
    }

    public int port() {
        return this.server.getAddress().getPort();
    }

    public void script(final Function<Request, Reply> answers) {
        this.script = answers;
    }

    public synchronized List<Request> requests() {
        return new ArrayList<>(this.requests);
    }

    /** Requests received so far; reads without allocating. */
    public int received() {
        return this.received.get();
    }

    public int maxConcurrent() {
        return this.maxActive.get();
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final int now = this.active.incrementAndGet();
        this.maxActive.accumulateAndGet(now, Math::max);
        try {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
            final Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders()
                    .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
            final Request request = new Request(
                    exchange.getRequestMethod(), exchange.getRequestURI().toString(), body, headers, System.nanoTime());
            synchronized (this) {
                this.requests.add(request);
            }
            this.received.incrementAndGet();
            final Reply reply = this.script.apply(request);
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            final byte[] bytes = reply.body().getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            this.active.decrementAndGet();
            exchange.close();
        }
    }

    @Override
    public void close() {
        this.server.stop(0);
        this.executor.shutdownNow();
    }
}
