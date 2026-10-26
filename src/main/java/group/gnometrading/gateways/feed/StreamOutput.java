package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.SequencedRingBuffer;

/**
 * One stream a market feeds: its ring and how it sees the market. Published only by the thread reading the market's
 * connection, so each ring keeps a single producer.
 */
public record StreamOutput(int streamId, Projection projection, SequencedRingBuffer<Mbp10Schema> ring) {}
