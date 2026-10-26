package group.gnometrading.gateways.feed.probe;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.networking.http.HTTPResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** What a venue's probe client test needs: canned REST answers, and a fetch for a market by key. */
public final class ProbeTestKit {

    private ProbeTestKit() {}

    public static HTTPResponse ok(final String body) {
        return response(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** A real response; its constructor is protected, so an anonymous subclass makes one. */
    public static HTTPResponse response(final boolean success, final ByteBuffer body) {
        return new HTTPResponse() {}.update(success, success ? 200 : 429, body);
    }

    public static boolean fetch(final BookProbeClient client, final String key, final Book into) throws Exception {
        return client.fetch(client.prepare(new Subject(key, 8)), into);
    }
}
