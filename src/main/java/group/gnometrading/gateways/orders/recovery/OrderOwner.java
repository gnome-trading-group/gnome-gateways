package group.gnometrading.gateways.orders.recovery;

/**
 * Whose order a venue's id names, where the id carries it: the session's slot (its registry {@code session_seq}, modulo
 * however many slots the venue's ids have room for) and the session's order counter.
 */
public record OrderOwner(long slot, long counter) {}
