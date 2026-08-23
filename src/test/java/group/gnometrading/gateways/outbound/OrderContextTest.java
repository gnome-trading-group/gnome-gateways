package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OrderContextTest {

    @Test
    void resetClearsAllFields() {
        final OrderContext ctx = new OrderContext();
        ctx.orderId = 99L;
        ctx.clientOidCounter = 5L;
        ctx.clientOidStrategyId = 3;
        ctx.exchangeId = 7;
        ctx.securityId = 42L;
        ctx.originalQty = 1000L;
        ctx.cumulativeFilledQty = 500L;
        ctx.leavesQty = 500L;
        ctx.side = Side.Ask;
        ctx.execType = ExecType.FILL;
        ctx.orderStatus = OrderStatus.PARTIALLY_FILLED;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        final byte[] hash = "abc123".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(hash, 0, ctx.exchangeOrderIdBytes, 0, hash.length);
        ctx.exchangeOrderIdLength = hash.length;

        ctx.reset();

        assertEquals(0L, ctx.orderId);
        assertEquals(0L, ctx.clientOidCounter);
        assertEquals(0, ctx.clientOidStrategyId);
        assertEquals(0, ctx.exchangeId);
        assertEquals(0L, ctx.securityId);
        assertEquals(0L, ctx.originalQty);
        assertEquals(0L, ctx.cumulativeFilledQty);
        assertEquals(0L, ctx.leavesQty);
        assertNull(ctx.side);
        assertNull(ctx.execType);
        assertNull(ctx.orderStatus);
        assertNull(ctx.rejectReason);
        assertEquals(0, ctx.exchangeOrderIdLength);
    }

    @Test
    void copyFromCopiesAllFields() {
        final OrderContext src = new OrderContext();
        src.orderId = 42L;
        src.clientOidCounter = 7L;
        src.clientOidStrategyId = 3;
        src.exchangeId = 5;
        src.securityId = 99L;
        src.originalQty = 200L;
        src.cumulativeFilledQty = 50L;
        src.leavesQty = 150L;
        src.side = Side.Ask;
        src.execType = ExecType.PARTIAL_FILL;
        src.orderStatus = OrderStatus.PARTIALLY_FILLED;
        src.rejectReason = RejectReason.EXCHANGE_REJECTED;
        final byte[] hash = "0xdeadbeef".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(hash, 0, src.exchangeOrderIdBytes, 0, hash.length);
        src.exchangeOrderIdLength = hash.length;

        final OrderContext dst = new OrderContext();
        dst.copyFrom(src);

        assertEquals(src.orderId, dst.orderId);
        assertEquals(src.clientOidCounter, dst.clientOidCounter);
        assertEquals(src.clientOidStrategyId, dst.clientOidStrategyId);
        assertEquals(src.exchangeId, dst.exchangeId);
        assertEquals(src.securityId, dst.securityId);
        assertEquals(src.originalQty, dst.originalQty);
        assertEquals(src.cumulativeFilledQty, dst.cumulativeFilledQty);
        assertEquals(src.leavesQty, dst.leavesQty);
        assertEquals(src.side, dst.side);
        assertEquals(src.execType, dst.execType);
        assertEquals(src.orderStatus, dst.orderStatus);
        assertEquals(src.rejectReason, dst.rejectReason);
        assertEquals(src.exchangeOrderIdLength, dst.exchangeOrderIdLength);
        assertEquals(
                new String(src.exchangeOrderIdBytes, 0, src.exchangeOrderIdLength, StandardCharsets.UTF_8),
                new String(dst.exchangeOrderIdBytes, 0, dst.exchangeOrderIdLength, StandardCharsets.UTF_8));
    }

    @Test
    void copyFromDoesNotShareByteArray() {
        final OrderContext src = new OrderContext();
        final byte[] hash = "original".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(hash, 0, src.exchangeOrderIdBytes, 0, hash.length);
        src.exchangeOrderIdLength = hash.length;

        final OrderContext dst = new OrderContext();
        dst.copyFrom(src);

        src.exchangeOrderIdBytes[0] = (byte) 'X';

        assertEquals('o', (char) dst.exchangeOrderIdBytes[0]);
    }

    @Test
    void copyFromWithZeroLengthExchangeOrderId() {
        final OrderContext src = new OrderContext();
        src.orderId = 1L;
        src.exchangeOrderIdLength = 0;

        final OrderContext dst = new OrderContext();
        dst.copyFrom(src);

        assertEquals(0, dst.exchangeOrderIdLength);
        assertEquals(1L, dst.orderId);
    }

    @Test
    void copyFromWithMaxLengthExchangeOrderId() {
        final OrderContext src = new OrderContext();
        for (int i = 0; i < OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH; i++) {
            src.exchangeOrderIdBytes[i] = (byte) ('a' + (i % 26));
        }
        src.exchangeOrderIdLength = OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH;

        final OrderContext dst = new OrderContext();
        dst.copyFrom(src);

        assertEquals(OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH, dst.exchangeOrderIdLength);
        assertArrayEquals(src.exchangeOrderIdBytes, dst.exchangeOrderIdBytes);
    }
}
