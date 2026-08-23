package group.gnometrading.gateways;

import java.io.IOException;

@FunctionalInterface
public interface Connectable {
    void connect() throws IOException;
}
