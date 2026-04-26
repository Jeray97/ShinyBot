package com.soulshinygame.bot.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Servidor HTTP del panel de administración.
 * Puerto 8767 — accesible en http://localhost:8767/admin
 * Protegido por contraseña básica definida en .env (ADMIN_PASSWORD)
 */
public class AdminServer {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private final int port;
    private final AdminApiHandler apiHandler;
    private HttpServer server;

    public AdminServer(int port, AdminApiHandler apiHandler) {
        this.port       = port;
        this.apiHandler = apiHandler;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // Panel HTML
            server.createContext("/admin", exchange -> {
                if (!apiHandler.isAuthorized(exchange)) {
                    sendAuth(exchange);
                    return;
                }
                serveResource(exchange, "/admin.html", "text/html; charset=utf-8");
            });

            // API REST
            server.createContext("/api/", exchange -> {
                if (!apiHandler.isAuthorized(exchange)) {
                    sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }
                addCors(exchange);
                apiHandler.handle(exchange);
            });

            // Redirigir raíz al panel
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Location", "/admin");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
            log.info("Panel de administración en http://localhost:{}/admin", port);

        } catch (IOException e) {
            log.error("Error arrancando AdminServer", e);
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        if (server != null) { server.stop(0); log.info("AdminServer detenido"); }
    }

    // ── Utilidades ────────────────────────────────────────────────

    private void serveResource(HttpExchange ex, String resource, String contentType) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            if (is == null) { sendJson(ex, 404, "{\"error\":\"Not found\"}"); return; }
            byte[] bytes = is.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        addCors(ex);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendAuth(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"SoulShinyBot Admin\"");
        ex.sendResponseHeaders(401, -1);
        ex.close();
    }
}