package com.soulshinygame.bot.admin;

import com.sun.net.httpserver.HttpExchange;
import com.soulshinygame.bot.commands.CommandRegistry;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import com.soulshinygame.bot.timers.TimerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdminApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminApiHandler.class);

    private final CommandRegistry registry;
    private final DatabaseManager db;
    private final WebSocketOverlayServer overlayServer;
    private final TimerManager timerManager;
    private final AdminLogHandler logHandler;
    private final String adminPassword;
    private final long startTime = System.currentTimeMillis();

    public AdminApiHandler(CommandRegistry registry, DatabaseManager db,
                           WebSocketOverlayServer overlayServer, TimerManager timerManager,
                           AdminLogHandler logHandler, String adminPassword) {
        this.registry       = registry;
        this.db             = db;
        this.overlayServer  = overlayServer;
        this.timerManager   = timerManager;
        this.logHandler     = logHandler;
        this.adminPassword  = adminPassword;
    }

    public void handle(HttpExchange ex) throws IOException {
        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if ("OPTIONS".equals(method)) { ex.sendResponseHeaders(204, -1); ex.close(); return; }

        try {
            if (path.equals("/api/status"))                     handleStatus(ex);
            else if (path.equals("/api/commands"))              handleCommands(ex);
            else if (path.startsWith("/api/commands/"))         handleCommandToggle(ex, path);
            else if (path.equals("/api/database/users"))        handleDbUsers(ex, method);
            else if (path.startsWith("/api/database/users/"))   handleDbUserEdit(ex, path, method);
            else if (path.equals("/api/database/collectibles")) handleCollectibles(ex);
            else if (path.equals("/api/media-commands"))        handleMediaCommands(ex, method);
            else if (path.equals("/api/timers"))                handleTimers(ex, method);
            else if (path.equals("/api/logs"))                  handleLogs(ex);
            else AdminServer.sendJson(ex, 404, "{\"error\":\"Route not found\"}");
        } catch (Exception e) {
            log.error("Error en API {}", path, e);
            AdminServer.sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ── GET /api/status ───────────────────────────────────────────

    private void handleStatus(HttpExchange ex) throws IOException {
        long uptimeMs  = System.currentTimeMillis() - startTime;
        long uptimeSec = uptimeMs / 1000;
        long h = uptimeSec / 3600, m = (uptimeSec % 3600) / 60, s = uptimeSec % 60;

        boolean dbOk          = db.isHealthy();
        boolean timersOk      = timerManager.isRunning();
        int overlayClients    = overlayServer.getConnections().size();

        String json = String.format("""
            {
              "uptime": "%dh %dm %ds",
              "uptimeMs": %d,
              "services": {
                "bot":      {"status": "ok",    "label": "Bot Twitch"},
                "database": {"status": "%s",    "label": "Base de datos"},
                "websocket":{"status": "ok",    "label": "WebSocket Overlay", "clients": %d},
                "timers":   {"status": "%s",    "label": "Timers"}
              },
              "commandCount": %d,
              "enabledCommands": %d
            }
            """,
                h, m, s, uptimeMs,
                dbOk ? "ok" : "error",
                overlayClients,
                timersOk ? "ok" : "warn",
                registry.getCommandCount(),
                registry.getEnabledCount()
        );
        AdminServer.sendJson(ex, 200, json);
    }

    // ── GET /api/commands ─────────────────────────────────────────

    private void handleCommands(HttpExchange ex) throws IOException {
        List<String> names = registry.getCommandNames();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            String name  = names.get(i);
            boolean isOn = registry.isEnabled(name);
            sb.append(String.format("{\"name\":\"%s\",\"enabled\":%b}", name, isOn));
            if (i < names.size() - 1) sb.append(",");
        }
        AdminServer.sendJson(ex, 200, sb.append("]").toString());
    }

    // ── POST /api/commands/{name}/toggle ──────────────────────────

    private void handleCommandToggle(HttpExchange ex, String path) throws IOException {
        String[] parts = path.split("/");
        if (parts.length < 5) { AdminServer.sendJson(ex, 400, "{\"error\":\"Bad path\"}"); return; }
        String name  = parts[3];
        boolean now  = registry.toggleCommand(name);
        AdminServer.sendJson(ex, 200, String.format("{\"name\":\"%s\",\"enabled\":%b}", name, now));
        log.info("Comando !{} {} via admin", name, now ? "habilitado" : "deshabilitado");
    }

    // ── GET /api/database/users ───────────────────────────────────

    private void handleDbUsers(HttpExchange ex, String method) throws IOException {
        AdminServer.sendJson(ex, 200, db.getAllUsersJson());
    }

    // ── POST /api/database/users/{name} ──────────────────────────

    private void handleDbUserEdit(HttpExchange ex, String path, String method) throws IOException {
        if (!"POST".equals(method)) { AdminServer.sendJson(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }
        String username = path.replace("/api/database/users/", "");
        String body     = AdminServer.readBody(ex);
        int points      = intFromJson(body, "points");
        db.setPoints(username, points);
        AdminServer.sendJson(ex, 200,
                String.format("{\"username\":\"%s\",\"points\":%d}", username, points));
        log.info("Admin editó puntos de {}: {}", username, points);
    }

    // ── GET /api/database/collectibles ────────────────────────────

    private void handleCollectibles(HttpExchange ex) throws IOException {
        AdminServer.sendJson(ex, 200, db.getAllCollectiblesJson());
    }

    // ── GET/POST /api/media-commands ──────────────────────────────

    private void handleMediaCommands(HttpExchange ex, String method) throws IOException {
        Path file = Path.of("media_commands.json");
        if ("GET".equals(method)) {
            AdminServer.sendJson(ex, 200, Files.exists(file) ? Files.readString(file) : "[]");
        } else if ("POST".equals(method)) {
            Files.writeString(file, AdminServer.readBody(ex));
            AdminServer.sendJson(ex, 200, "{\"ok\":true}");
            log.info("Admin actualizó media_commands.json");
        }
    }

    // ── GET/POST /api/timers ──────────────────────────────────────

    private void handleTimers(HttpExchange ex, String method) throws IOException {
        Path file = Path.of("timers.json");
        if ("GET".equals(method)) {
            AdminServer.sendJson(ex, 200, Files.exists(file) ? Files.readString(file) : "[]");
        } else if ("POST".equals(method)) {
            Files.writeString(file, AdminServer.readBody(ex));
            AdminServer.sendJson(ex, 200, "{\"ok\":true}");
            log.info("Admin actualizó timers.json");
        }
    }

    // ── GET /api/logs ─────────────────────────────────────────────

    private void handleLogs(HttpExchange ex) throws IOException {
        AdminServer.sendJson(ex, 200, logHandler.getLogsJson(100));
    }

    // ── Auth ──────────────────────────────────────────────────────

    public boolean isAuthorized(HttpExchange ex) {
        if (adminPassword == null || adminPassword.isEmpty()) return true;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6)));
            String[] parts = decoded.split(":", 2);
            return parts.length == 2 && "admin".equals(parts[0]) && adminPassword.equals(parts[1]);
        } catch (Exception e) { return false; }
    }

    private int intFromJson(String json, String key) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
            Matcher m = p.matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Exception e) { return 0; }
    }
}