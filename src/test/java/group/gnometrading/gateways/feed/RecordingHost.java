package group.gnometrading.gateways.feed;

import java.util.List;

/** A host that notes each resync as "key:REASON", each stale request as "stale key:REASON", each republish. */
public record RecordingHost(List<String> into) implements SubjectHost {

    @Override
    public void requestResync(final Subject subject, final GapReason reason, final long atNanos) {
        into.add(subject.key() + ":" + reason);
    }

    @Override
    public void requestStale(final Subject subject, final GapReason reason) {
        into.add("stale " + subject.key() + ":" + reason);
    }

    @Override
    public void requestRepublish(final Subject subject) {
        into.add("republish " + subject.key());
    }
}
