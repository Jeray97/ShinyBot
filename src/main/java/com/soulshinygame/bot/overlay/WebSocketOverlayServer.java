package com.soulshinygame.bot.overlay;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class WebSocketOverlayServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketOverlayServer.class);
    private final Set<WebSocket> clients = Collections.synchronizedSet(new HashSet<>());

    public WebSocketOverlayServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        log.info("Overlay conectado: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        log.info("Overlay desconectado");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // El overlay no nos manda mensajes, pero por si acaso
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("Error en WebSocket", ex);
    }

    @Override
    public void onStart() {
        log.info("Servidor WebSocket del overlay arrancado en puerto {}", getPort());
    }

    // Envía un evento a todos los overlays conectados en OBS
    public void sendEvent(String jsonPayload) {
        synchronized (clients) {
            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    client.send(jsonPayload);
                }
            }
        }
    }
}