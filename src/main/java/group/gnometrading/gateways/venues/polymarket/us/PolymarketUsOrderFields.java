package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.VenueRejectText;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.GnomeString;
import java.nio.ByteBuffer;

/**
 * The fields of a Polymarket US {@code Order}, and of the {@code Execution} that wraps one on the private socket,
 * read into reused storage. The REST order endpoints and the socket share the order's shape, so one parser serves
 * both. Prices are the venue's, in long (YES) terms.
 */
final class PolymarketUsOrderFields {

    static final int STATE_UNKNOWN = 0;
    static final int STATE_WORKING = 1;
    static final int STATE_FILLED = 2;
    static final int STATE_CANCELED = 3;
    static final int STATE_REJECTED = 4;
    static final int STATE_EXPIRED = 5;

    static final int EXEC_OTHER = 0;
    static final int EXEC_NEW = 1;
    static final int EXEC_FILL = 2;
    static final int EXEC_CANCELED = 3;
    static final int EXEC_REJECTED = 4;
    static final int EXEC_EXPIRED = 5;
    static final int EXEC_REPLACE = 6;

    static final int TEXT_MAX_LENGTH = 200;
    static final int REJECT_REASON_MAX_LENGTH = 64;

    static final int INTENT_UNKNOWN = 0;
    static final int INTENT_BUY_LONG = 1;
    static final int INTENT_SELL_LONG = 2;
    static final int INTENT_BUY_SHORT = 3;
    static final int INTENT_SELL_SHORT = 4;

    final byte[] orderId = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    int orderIdLength;
    int state;
    int intent;
    boolean immediate;
    long quantity;
    long cumQuantity;
    long price;
    long avgPx;
    long goodTillMillis;
    long commissionTotal;
    boolean sawCommissionTotal;
    long createTimeNanos;

    // Execution-level; unset when a bare order was read.
    int execType;
    // The venue's reason for a rejection; longer reasons are cut.
    final byte[] text = new byte[TEXT_MAX_LENGTH];
    int textLength;
    // The venue's coded reason for a rejection, an ORD_REJECT_REASON_* name; longer names are cut.
    final byte[] orderRejectReason = new byte[REJECT_REASON_MAX_LENGTH];
    int orderRejectReasonLength;
    // Views over the two, so they can be searched as a body is, without a buffer made per rejection.
    private final ByteBuffer textView = ByteBuffer.wrap(this.text);
    private final ByteBuffer orderRejectReasonView = ByteBuffer.wrap(this.orderRejectReason);
    long lastShares;
    long lastPx;
    boolean aggressor;
    long transactTimeNanos;

    void reset() {
        this.orderIdLength = 0;
        this.state = STATE_UNKNOWN;
        this.intent = INTENT_UNKNOWN;
        this.immediate = false;
        this.quantity = OrderContext.QTY_ABSENT;
        this.cumQuantity = OrderContext.QTY_ABSENT;
        this.price = 0;
        this.avgPx = 0;
        this.goodTillMillis = Rfc3339.INVALID;
        this.commissionTotal = 0;
        this.sawCommissionTotal = false;
        this.createTimeNanos = Rfc3339.INVALID;
        this.execType = EXEC_OTHER;
        this.textLength = 0;
        this.orderRejectReasonLength = 0;
        this.lastShares = 0;
        this.lastPx = 0;
        this.aggressor = false;
        this.transactTimeNanos = Rfc3339.INVALID;
    }

    boolean terminal() {
        return this.state >= STATE_FILLED;
    }

    /** Reads an {@code Execution}: its own fields and the order inside it. */
    void readExecution(final JsonDecoder.JsonObject execution) {
        while (execution.hasNextKey()) {
            try (var field = execution.nextKey()) {
                // The decoder reads a null as whatever string comes after it.
                if (field.isNull()) {
                    continue;
                }
                final GnomeString name = field.getName();
                if (name.equals("order")) {
                    try (var order = field.asObject()) {
                        readOrder(order);
                    }
                } else if (name.equals("type")) {
                    this.execType = execType(field.asString());
                } else if (name.equals("lastShares")) {
                    this.lastShares = decimal(field.asString(), Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("lastPx")) {
                    this.lastPx = amount(field);
                } else if (name.equals("aggressor")) {
                    this.aggressor = field.asBoolean();
                } else {
                    readExecutionTextField(name, field);
                }
            }
        }
    }

    private void readExecutionTextField(final GnomeString name, final JsonDecoder.JsonNode field) {
        if (name.equals("transactTime")) {
            this.transactTimeNanos = Rfc3339.toEpochNanos(field.asString());
        } else if (name.equals("text")) {
            this.textLength = copy(field.asString(), this.text);
        } else if (name.equals("orderRejectReason")) {
            this.orderRejectReasonLength = copy(field.asString(), this.orderRejectReason);
        }
    }

    /** Why the venue rejected the order, from its coded reason or else its text; EXCHANGE_REJECTED if neither says. */
    RejectReason rejectReason() {
        RejectReason reason =
                VenueRejectText.POLYMARKET_US.classify(this.orderRejectReasonView, 0, this.orderRejectReasonLength);
        if (reason == null) {
            reason = VenueRejectText.POLYMARKET_US.classify(this.textView, 0, this.textLength);
        }
        return reason != null ? reason : RejectReason.EXCHANGE_REJECTED;
    }

    /** Reads an {@code Order}. */
    void readOrder(final JsonDecoder.JsonObject order) {
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                if (field.isNull()) {
                    continue;
                }
                readOrderField(field.getName(), field);
            }
        }
    }

    private void readOrderField(final GnomeString name, final JsonDecoder.JsonNode field) {
        if (name.equals("id")) {
            this.orderIdLength = copy(field.asString(), this.orderId);
        } else if (name.equals("state")) {
            this.state = state(field.asString());
        } else if (name.equals("quantity")) {
            this.quantity = field.asFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("cumQuantity")) {
            this.cumQuantity = field.asFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("intent")) {
            this.intent = intent(field.asString());
        } else if (name.equals("tif")) {
            final GnomeString tif = field.asString();
            this.immediate =
                    tif.equals("TIME_IN_FORCE_IMMEDIATE_OR_CANCEL") || tif.equals("TIME_IN_FORCE_FILL_OR_KILL");
        } else {
            readOrderValueField(name, field);
        }
    }

    private void readOrderValueField(final GnomeString name, final JsonDecoder.JsonNode field) {
        if (name.equals("price")) {
            this.price = amount(field);
        } else if (name.equals("avgPx")) {
            this.avgPx = amount(field);
        } else if (name.equals("commissionNotionalTotalCollected")) {
            this.commissionTotal = amount(field);
            this.sawCommissionTotal = true;
        } else if (name.equals("goodTillTime")) {
            this.goodTillMillis = PolymarketUsStamps.parseMillis(field.asString());
        } else if (name.equals("createTime")) {
            this.createTimeNanos = Rfc3339.toEpochNanos(field.asString());
        }
    }

    /** An {@code Amount}: {@code {"value":"0.555","currency":"USD"}}, in price scale. */
    static long amount(final JsonDecoder.JsonNode node) {
        long value = 0;
        try (var amount = node.asObject()) {
            while (amount.hasNextKey()) {
                try (var field = amount.nextKey()) {
                    if (field.getName().equals("value") && !field.isNull()) {
                        value = decimal(field.asString(), Statics.PRICE_SCALING_FACTOR);
                    }
                }
            }
        }
        return value;
    }

    private static long decimal(final GnomeString text, final long scalingFactor) {
        return text.length() == 0 ? 0 : text.toFixedPointLong(scalingFactor);
    }

    private static int copy(final GnomeString value, final byte[] dest) {
        final int length = Math.min(value.length(), dest.length);
        for (int i = 0; i < length; i++) {
            dest[i] = value.byteAt(i);
        }
        return length;
    }

    private static int state(final GnomeString value) {
        if (value.equals("ORDER_STATE_FILLED")) {
            return STATE_FILLED;
        } else if (value.equals("ORDER_STATE_CANCELED")) {
            return STATE_CANCELED;
        } else if (value.equals("ORDER_STATE_REJECTED")) {
            return STATE_REJECTED;
        } else if (value.equals("ORDER_STATE_EXPIRED")) {
            return STATE_EXPIRED;
        }
        return isWorking(value) ? STATE_WORKING : STATE_UNKNOWN;
    }

    private static boolean isWorking(final GnomeString value) {
        return value.equals("ORDER_STATE_NEW")
                || value.equals("ORDER_STATE_PARTIALLY_FILLED")
                || value.equals("ORDER_STATE_REPLACED")
                || value.equals("ORDER_STATE_PENDING_NEW")
                || value.equals("ORDER_STATE_PENDING_REPLACE")
                || value.equals("ORDER_STATE_PENDING_CANCEL");
    }

    private static int execType(final GnomeString value) {
        if (value.equals("EXECUTION_TYPE_NEW")) {
            return EXEC_NEW;
        } else if (value.equals("EXECUTION_TYPE_FILL") || value.equals("EXECUTION_TYPE_PARTIAL_FILL")) {
            return EXEC_FILL;
        } else if (value.equals("EXECUTION_TYPE_CANCELED") || value.equals("EXECUTION_TYPE_DONE_FOR_DAY")) {
            return EXEC_CANCELED;
        } else if (value.equals("EXECUTION_TYPE_REJECTED")) {
            return EXEC_REJECTED;
        } else if (value.equals("EXECUTION_TYPE_EXPIRED")) {
            return EXEC_EXPIRED;
        } else if (value.equals("EXECUTION_TYPE_REPLACE")) {
            return EXEC_REPLACE;
        }
        return EXEC_OTHER;
    }

    private static int intent(final GnomeString value) {
        if (value.equals("ORDER_INTENT_BUY_LONG")) {
            return INTENT_BUY_LONG;
        } else if (value.equals("ORDER_INTENT_SELL_LONG")) {
            return INTENT_SELL_LONG;
        } else if (value.equals("ORDER_INTENT_BUY_SHORT")) {
            return INTENT_BUY_SHORT;
        } else if (value.equals("ORDER_INTENT_SELL_SHORT")) {
            return INTENT_SELL_SHORT;
        }
        return INTENT_UNKNOWN;
    }
}
