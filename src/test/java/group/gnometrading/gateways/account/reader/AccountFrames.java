package group.gnometrading.gateways.account.reader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** One order's frames on each venue's account socket, as the venue sends them, for allocation tests and benchmarks. */
public final class AccountFrames {

    private AccountFrames() {}

    public static ByteBuffer kalshi(
            final String clientOrderId, final String status, final String fillCount, final String remaining) {
        return bytes("{\"type\":\"user_order\",\"sid\":1,\"msg\":{\"order_id\":\"venue-" + clientOrderId
                + "\",\"client_order_id\":\"" + clientOrderId + "\",\"status\":\"" + status
                + "\",\"fill_count_fp\":\"" + fillCount + "\",\"remaining_count_fp\":\"" + remaining
                + "\",\"taker_fill_cost_dollars\":\"0.000000\",\"maker_fill_cost_dollars\":\"1.500000\","
                + "\"last_updated_ts_ms\":1800000000000}}");
    }

    public static ByteBuffer pmUs(
            final String type,
            final String state,
            final long cumQuantity,
            final String stamp,
            final String lastShares) {
        return bytes("{\"requestId\":\"orders\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\","
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"E\",\"order\":{\"id\":\"V1\","
                + "\"marketSlug\":\"slug-a\",\"side\":\"ORDER_SIDE_BUY\",\"type\":\"ORDER_TYPE_LIMIT\","
                + "\"price\":{\"value\":\"0.55\",\"currency\":\"USD\"},\"quantity\":3,\"cumQuantity\":" + cumQuantity
                + ",\"leavesQuantity\":" + (3 - cumQuantity) + ",\"tif\":\"TIME_IN_FORCE_GOOD_TILL_DATE\","
                + "\"goodTillTime\":\"" + stamp + "\",\"intent\":\"ORDER_INTENT_BUY_LONG\",\"state\":\"" + state
                + "\",\"commissionNotionalTotalCollected\":{\"value\":\"0.0000\",\"currency\":\"USD\"},"
                + "\"avgPx\":{\"value\":\"0.5500\",\"currency\":\"USD\"},"
                + "\"createTime\":\"2026-10-08T14:33:25.872677584Z\"},\"lastShares\":\"" + lastShares
                + "\",\"lastPx\":{\"value\":\"0.5500\",\"currency\":\"USD\"},\"type\":\"" + type
                + "\",\"tradeId\":\"T\",\"aggressor\":false,\"transactTime\":\"2026-10-08T14:37:56.769167653Z\"}}}");
    }

    public static ByteBuffer pmIntl(final String hash, final String type, final String status) {
        return bytes("{\"asset_id\":\"token-a\",\"associate_trades\":null,\"event_type\":\"order\",\"id\":\"" + hash
                + "\",\"market\":\"condition-1\",\"original_size\":\"10\",\"outcome\":\"YES\",\"owner\":\"key\","
                + "\"price\":\"0.55\",\"side\":\"BUY\",\"size_matched\":\"0\",\"status\":\"" + status
                + "\",\"timestamp\":\"1700000000000\",\"type\":\"" + type + "\"}");
    }

    private static ByteBuffer bytes(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }
}
