package com.soulshinygame.bot.admin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Captura logs en un buffer circular en memoria.
 *
 * Como el proyecto usa slf4j-simple (no Logback), no podemos
 * añadir un Appender. En su lugar los componentes del bot
 * llaman a AdminLogHandler.log() directamente, o usamos
 * el método estático getInstance() para obtener la instancia global.
 *
 * Los logs del sistema (INFO del bot de Twitch, etc.) se capturan
 * via el método log() desde los puntos clave del código.
 */
public class AdminLogHandler {

    private static final int MAX_LOGS = 500;
    private static AdminLogHandler INSTANCE;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Deque<LogEntry> buffer = new ArrayDeque<>();

    public AdminLogHandler() {
        INSTANCE = this;
    }

    /** Instancia global para que otros componentes puedan loguear */
    public static AdminLogHandler getInstance() { return INSTANCE; }

    /** Añade una entrada al buffer */
    public void log(String level, String logger, String msg) {
        String time = FMT.format(Instant.now());
        // Acortar nombre del logger
        if (logger.contains(".")) logger = logger.substring(logger.lastIndexOf('.') + 1);
        synchronized (buffer) {
            if (buffer.size() >= MAX_LOGS) buffer.pollFirst();
            buffer.addLast(new LogEntry(time, level, logger, msg));
        }
    }

    public void info(String logger, String msg)  { log("INFO",  logger, msg); }
    public void warn(String logger, String msg)  { log("WARN",  logger, msg); }
    public void error(String logger, String msg) { log("ERROR", logger, msg); }

    /** Devuelve los últimos N logs como JSON array */
    public String getLogsJson(int limit) {
        List<LogEntry> copy;
        synchronized (buffer) { copy = new ArrayList<>(buffer); }

        int from = Math.max(0, copy.size() - limit);
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i < copy.size(); i++) {
            LogEntry e = copy.get(i);
            sb.append(String.format(
                    "{\"time\":\"%s\",\"level\":\"%s\",\"logger\":\"%s\",\"msg\":%s}",
                    e.time, e.level, e.logger, jsonString(e.msg)));
            if (i < copy.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String jsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "") + "\"";
    }

    private record LogEntry(String time, String level, String logger, String msg) {}
}