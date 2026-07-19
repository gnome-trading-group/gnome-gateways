package group.gnometrading.gateways.inbound.exchanges.polymarket;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.gateways.inbound.JsonWebSocketReader;
import group.gnometrading.gateways.inbound.JsonWebSocketWriter;
import group.gnometrading.gateways.inbound.SocketWriter;
import group.gnometrading.gateways.inbound.WebSocketWriter;
import group.gnometrading.gateways.inbound.mbp.Mbp10Book;
import group.gnometrading.gateways.inbound.mbp.Mbp10SchemaFactory;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.Mbp10Encoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import org.agrona.concurrent.EpochNanoClock;

public final class PolymarketSocketReader extends JsonWebSocketReader<Mbp10Schema> implements Mbp10SchemaFactory {

    private static final int MAX_LEVEL_DEPTH = 10;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Mbp10Book book;
    private final String tokenId;
    private long lastTradePrice;
    private long lastTradeSize;

    public PolymarketSocketReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (Mbp10Book) this.internalBook;
        // exchangeSecurityId is "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIndex = exchangeSecurityId.indexOf(':');
        this.tokenId = colonIndex >= 0 ? exchangeSecurityId.substring(colonIndex + 1) : exchangeSecurityId;

        this.lastTradePrice = Mbp10Encoder.priceNullValue();
        this.lastTradeSize = Mbp10Encoder.sizeNullValue();
    }

    @Override
    protected void keepAlive() throws IOException {
        // Polymarket CLOB WebSocket does not require periodic pings
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        // Polymarket sends a full book snapshot via WebSocket upon subscription.
        // It is captured in the replay buffer before connect() unpauses the reader.
        return null;
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonNode node) {
        try (var obj = node.asObject()) {
            parseEventObject(obj);
        }
    }

    private void parseEventObject(final JsonDecoder.JsonObject obj) {
        // Polymarket sends event_type as the first key, so we read it first and then
        // dispatch the remaining keys in one pass (the streaming decoder cannot seek back).
        boolean isBook = false;
        boolean isTrade = false;

        while (obj.hasNextKey()) {
            try (var key = obj.nextKey()) {
                final var name = key.getName();
                if (name.equals("event_type")) {
                    final var value = key.asString().toString();
                    isBook = value.equals("book");
                    isTrade = value.equals("last_trade_price");
                    if (isBook) {
                        initBookEncoder();
                    }
                } else if (isBook) {
                    parseBookKey(name, key);
                } else if (isTrade) {
                    parseTradeKey(name, key);
                }
            }
        }

        if (isBook) {
            this.book.writeTo(this.schema);
            offer();
        }
    }

    private void initBookEncoder() {
        prepareEncoder();
        this.schema.encoder.timestampEvent(Mbp10Encoder.timestampEventNullValue());
        this.schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
    }

    private void parseBookKey(final GnomeString name, final JsonDecoder.JsonNode key) {
        if (name.equals("timestamp")) {
            final long timestampSec = key.asLong();
            this.schema.encoder.timestampEvent(timestampSec * NANOS_PER_SECOND);
            this.schema.encoder.sequence(timestampSec * NANOS_PER_SECOND);
        } else if (name.equals("bids")) {
            parseSide(key, true);
        } else if (name.equals("asks")) {
            parseSide(key, false);
        }
    }

    private void parseTradeKey(final GnomeString name, final JsonDecoder.JsonNode key) {
        if (name.equals("price")) {
            this.lastTradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("size")) {
            this.lastTradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        }
    }

    private void parseSide(final JsonDecoder.JsonNode node, final boolean isBids) {
        try (var array = node.asArray()) {
            final Mbp10Book.PriceLevel[] levels = isBids ? this.book.bids : this.book.asks;
            int idx = 0;
            while (array.hasNextItem() && idx < MAX_LEVEL_DEPTH) {
                parseLevel(array, levels[idx]);
                idx++;
            }
            // Consume levels beyond MAX_LEVEL_DEPTH
            while (array.hasNextItem()) {
                try (var item = array.nextItem();
                        var ignored = item.asObject()) {
                    // consume
                }
            }
            // Clear unused slots
            for (; idx < MAX_LEVEL_DEPTH; idx++) {
                levels[idx].reset();
            }
        }
    }

    private void parseLevel(final JsonDecoder.JsonArray array, final Mbp10Book.PriceLevel level) {
        long price = Mbp10Encoder.askPrice0NullValue();
        long size = Mbp10Encoder.askSize0NullValue();

        try (var levelNode = array.nextItem();
                var levelObj = levelNode.asObject()) {
            while (levelObj.hasNextKey()) {
                try (var key = levelObj.nextKey()) {
                    if (key.getName().equals("price")) {
                        price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("size")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    }
                }
            }
        }

        // Polymarket does not expose order count; use 1 as a sentinel
        level.update(price, size, 1L);
    }

    private void prepareEncoder() {
        this.schema.encoder.exchangeId(this.listing.exchange().exchangeId());
        this.schema.encoder.securityId(this.listing.security().securityId());
        this.schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(this.recvTimestamp);
    }

    @Override
    protected void subscribe() throws IOException {
        // { "assets_ids": ["<token_id>"], "type": "market" }
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "market");
        jsonEncoder.writeComma();
        jsonEncoder.writeString("assets_ids");
        jsonEncoder.writeColon();
        jsonEncoder.writeArrayStart();
        jsonEncoder.writeString(this.tokenId);
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }
}
