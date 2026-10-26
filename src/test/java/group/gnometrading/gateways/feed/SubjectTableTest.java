package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.gnometrading.strings.MutableString;
import org.junit.jupiter.api.Test;

class SubjectTableTest {

    private static Subject subject(final String key) {
        return new Subject(key, 4);
    }

    private static MutableString parsed(final String key) {
        return new MutableString(key);
    }

    @Test
    void aParsedKeyFindsItsMarket() {
        SubjectTable table = new SubjectTable();
        Subject a = subject("KXBTC-26OCT26-B100000");
        Subject b = subject("KXETH-26OCT26-B4000");
        table.put(a);
        table.put(b);

        assertSame(a, table.get(parsed("KXBTC-26OCT26-B100000")));
        assertSame(b, table.get("KXETH-26OCT26-B4000"));
        assertNull(table.get(parsed("KXBTC-26OCT26-B10000")));
        assertNull(table.get(parsed("")));
    }

    @Test
    void manyMarketsAllResolveAndARemovedOneDoesnt() {
        SubjectTable table = new SubjectTable();
        for (int i = 0; i < 1_000; i++) {
            table.put(subject("asset-" + i));
        }
        Subject gone = table.get("asset-500");
        table.remove(gone);

        assertEquals(999, table.size());
        assertNull(table.get(parsed("asset-500")));
        for (int i = 0; i < 1_000; i += 37) {
            if (i != 500) {
                assertEquals("asset-" + i, table.get(parsed("asset-" + i)).key());
            }
        }
    }

    @Test
    void puttingAKeyAgainReplacesIt() {
        SubjectTable table = new SubjectTable();
        table.put(subject("X"));
        Subject replacement = subject("X");
        table.put(replacement);

        assertEquals(1, table.size());
        assertSame(replacement, table.get("X"));
    }
}
