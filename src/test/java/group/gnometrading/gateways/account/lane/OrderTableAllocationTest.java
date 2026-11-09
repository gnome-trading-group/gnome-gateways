package group.gnometrading.gateways.account.lane;

import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every order on an account goes in and out of the table, so holding and finding them may not allocate. */
@Tag("allocation")
class OrderTableAllocationTest {

    @Test
    void puttingFindingAndRemovingDoesNotAllocate() {
        final int working = 4_096;
        final OrderTable<OrderContext> table = new OrderTable<OrderContext>(working);
        final OrderContext[] orders = new OrderContext[working];
        for (int i = 0; i < working; i++) {
            orders[i] = new OrderContext();
        }
        final long[] next = {1};
        OrderPathTestKit.assertNoAllocation("order table", 200, 100, () -> {
            for (int i = 0; i < working; i++) {
                orders[i].sessionSlot = i % 30;
                orders[i].clientOidCounter = next[0]++;
                table.put(orders[i].sessionSlot, orders[i].clientOidCounter, orders[i]);
            }
            for (int i = 0; i < working; i++) {
                table.get(orders[i].sessionSlot, orders[i].clientOidCounter);
            }
            for (int i = 0; i < working; i++) {
                table.remove(orders[i].sessionSlot, orders[i].clientOidCounter);
            }
        });
    }
}
