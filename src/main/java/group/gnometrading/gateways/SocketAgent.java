package group.gnometrading.gateways;

import org.agrona.concurrent.Agent;

public interface SocketAgent extends Agent {
    void onSocketClose();
}
