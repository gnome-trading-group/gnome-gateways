package group.gnometrading.gateways.account.reader;

/** Where an account's event socket stands; only READY means every event since the last connect has been applied. */
public enum AccountState {
    CONNECTING,
    CATCHING_UP,
    READY,
    DOWN
}
