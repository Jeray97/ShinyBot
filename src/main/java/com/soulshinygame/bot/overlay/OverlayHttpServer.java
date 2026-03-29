package com.soulshinygame.bot.overlay;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Servidor HTTP minimalista que sirve el overlay.html desde dentro del JAR.
 * OBS apunta a http://localhost:8766 en vez de a un fichero local.
 * No requiere ninguna dependencia extra — usa HttpServer incluido en Java.
 */
public class OverlayHttpServer {

    private static final Logger log = LoggerFactory.getLogger(OverlayHttpServer.class);
    private final int port;
    private HttpServer server;

    public OverlayHttpServer(int port) {
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // Sirve el overlay.html desde resources/
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();

                // Solo servimos la raíz o /overlay.html
                String resourcePath = "/overlay.html";

                try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        String error = "overlay.html no encontrado en el JAR";
                        exchange.sendResponseHeaders(404, error.length());
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(error.getBytes());
                        }
                        return;
                    }

                    byte[] bytes = is.readAllBytes();
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            });

            server.setExecutor(null);
            server.start();
            log.info("Servidor HTTP del overlay arrancado en http://localhost:{}", port);
            log.info("Configura OBS Browser Source con URL: http://localhost:{}", port);

        } catch (IOException e) {
            log.error("Error arrancando el servidor HTTP del overlay", e);
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Servidor HTTP del overlay detenido");
        }
    }
}