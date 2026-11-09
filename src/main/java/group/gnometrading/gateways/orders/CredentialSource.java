package group.gnometrading.gateways.orders;

/**
 * Where an account's credentials come from: this library never reads secrets itself, so whoever runs it passes one
 * in. Keys can be rotated while a process runs, so a venue's refusal of a request's credentials (a 401) is answered by
 * reading them again and re-keying, not by failing until a restart. Both calls may block; never on the hot path.
 *
 * @param <C> the venue's credentials
 */
public interface CredentialSource<C> {

    C current();

    /** Reads the credentials again, as stored now; may return the same ones if nothing changed. */
    C refresh();
}
