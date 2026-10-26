package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.feed.book.Projection;

/** Which venue market a listing's stream reads, and how it sees it. */
public record SubjectRef(String key, Projection projection) {}
