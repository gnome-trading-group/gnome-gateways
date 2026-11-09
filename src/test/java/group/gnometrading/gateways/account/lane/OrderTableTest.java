package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.orders.OrderContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OrderTableTest {

    @Test
    void theSameCounterInTwoSessionsIsTwoOrders() {
        final OrderTable<OrderContext> table = new OrderTable<OrderContext>(8);
        final OrderContext first = order(41, 7);
        final OrderContext second = order(42, 7);

        assertTrue(table.put(first.sessionSlot, first.clientOidCounter, first));
        assertTrue(table.put(second.sessionSlot, second.clientOidCounter, second));

        assertSame(first, table.get(41, 7));
        assertSame(second, table.get(42, 7));
        assertNull(table.get(43, 7));
        assertEquals(2, table.size());
    }

    @Test
    void anOrderIsHeldOnce() {
        final OrderTable<OrderContext> table = new OrderTable<OrderContext>(8);
        assertTrue(table.put(1, 1, order(1, 1)));
        assertFalse(table.put(1, 1, order(1, 1)));
        assertEquals(1, table.size());
    }

    @Test
    void aFullTableRefusesMore() {
        final OrderTable<OrderContext> table = new OrderTable<OrderContext>(3);
        assertTrue(table.put(1, 1, order(1, 1)));
        assertTrue(table.put(1, 2, order(1, 2)));
        assertTrue(table.put(1, 3, order(1, 3)));
        assertTrue(table.isFull());
        assertFalse(table.put(1, 4, order(1, 4)));

        assertSame(table.get(1, 2), table.remove(1, 2));
        assertTrue(table.put(1, 4, order(1, 4)));
    }

    @Test
    void removingWhatIsntHeldChangesNothing() {
        final OrderTable<OrderContext> table = new OrderTable<OrderContext>(8);
        table.put(1, 1, order(1, 1));
        assertNull(table.remove(2, 1));
        assertEquals(1, table.size());
    }

    @Test
    void agreesWithAMapThroughHeavyChurn() {
        // Small and kept near full, so probe runs are long and removals shift entries back across them.
        final OrderTable<OrderContext> table = new OrderTable<OrderContext>(64);
        final Map<List<Long>, OrderContext> model = new HashMap<>();
        final Random random = new Random(7);
        for (int step = 0; step < 200_000; step++) {
            final long slot = random.nextInt(6);
            final long counter = random.nextInt(40);
            final List<Long> key = List.of(slot, counter);
            if (random.nextBoolean()) {
                final OrderContext ctx = order(slot, counter);
                final boolean expected = !model.containsKey(key) && model.size() < 64;
                assertEquals(expected, table.put(ctx.sessionSlot, ctx.clientOidCounter, ctx), "put at step " + step);
                if (expected) {
                    model.put(key, ctx);
                }
            } else {
                assertSame(model.remove(key), table.remove(slot, counter), "remove at step " + step);
            }
            assertEquals(model.size(), table.size());
        }
        for (final Map.Entry<List<Long>, OrderContext> entry : model.entrySet()) {
            assertSame(
                    entry.getValue(),
                    table.get(entry.getKey().get(0), entry.getKey().get(1)));
        }
        final List<OrderContext> walked = new ArrayList<>();
        for (int i = 0; i < table.capacity(); i++) {
            if (table.at(i) != null) {
                walked.add(table.at(i));
            }
        }
        assertEquals(model.size(), walked.size(), "a walk visits every order once");
    }

    private static OrderContext order(final long slot, final long counter) {
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = slot;
        ctx.clientOidCounter = counter;
        return ctx;
    }
}
