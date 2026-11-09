package group.gnometrading.gateways.transport;

import group.gnometrading.networking.websockets.WebSocketClient;

/** A websocket as a venue's protocol sees it: the client, for the upgrade's headers, and the writer to send on. */
public record WebSocketChannel(WebSocketClient client, WebSocketWriter writer) {}
