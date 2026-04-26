package com.soulshinygame.bot.timers;

import com.github.twitch4j.TwitchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimerManager {

    private static final Logger log = LoggerFactory.getLogger(TimerManager.class);
    private static final String FILENAME = "timers.json";

    private final TwitchClient client;
    private final String channel;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private boolean running = false;

    public TimerManager(TwitchClient client, String channel) {
        this.client  = client;
        this.channel = channel;
    }

    public void start() {
        Path path = Path.of(FILENAME);
        if (!Files.exists(path)) {
            log.warn("No se encontró {}. No se activarán timers.", FILENAME);
            return;
        }
        try {
            String json = Files.readString(path);
            List<TimerEntry> timers = parse(json);
            for (TimerEntry timer : timers) { scheduleTimer(timer); }
            running = true;
            log.info("TimerManager: {} timer(s) activos", timers.size());
        } catch (IOException e) {
            log.error("Error leyendo {}", FILENAME, e);
        }
    }

    /** Para el panel de admin */
    public boolean isRunning() { return running && !scheduler.isShutdown(); }

    public void stop() {
        scheduler.shutdownNow();
        running = false;
        log.info("TimerManager detenido");
    }

    private void scheduleTimer(TimerEntry timer) {
        if (timer.messages.isEmpty()) return;
        int[] index = {0};
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String message = timer.messages.get(index[0] % timer.messages.size());
                client.getChat().sendMessage(channel, message);
                index[0]++;
                log.debug("Timer '{}' disparado: {}", timer.name, message);
            } catch (Exception e) {
                log.error("Error en timer '{}'", timer.name, e);
            }
        }, timer.intervalMinutes, timer.intervalMinutes, TimeUnit.MINUTES);

        log.info("Timer '{}' programado cada {} min ({} mensaje(s))",
                timer.name, timer.intervalMinutes, timer.messages.size());
    }

    // ── Parser ────────────────────────────────────────────────────

    private List<TimerEntry> parse(String json) {
        List<TimerEntry> result = new ArrayList<>();
        for (String block : extractTopLevelBlocks(json)) {
            try {
                String name       = field(block, "name");
                int interval      = intField(block, "intervalMinutes", 30);
                List<String> msgs = extractMessages(block);
                if (msgs.isEmpty()) { log.warn("Timer '{}' sin mensajes, ignorado", name); continue; }
                result.add(new TimerEntry(name, interval, msgs));
            } catch (Exception e) { log.warn("Error parseando timer: {}", e.getMessage()); }
        }
        return result;
    }

    private List<String> extractMessages(String block) {
        List<String> messages = new ArrayList<>();
        try {
            int start = block.indexOf("\"messages\"");
            if (start == -1) return messages;
            int arrayStart = block.indexOf('[', start);
            int arrayEnd   = block.indexOf(']', arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return messages;
            String arrayContent = block.substring(arrayStart + 1, arrayEnd);
            Pattern p = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(arrayContent);
            while (m.find()) { messages.add(m.group(1)); }
        } catch (Exception e) { log.warn("Error extrayendo mensajes del timer"); }
        return messages;
    }

    private List<String> extractTopLevelBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') { i++; while (i < json.length() && json.charAt(i) != '"') { if (json.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start != -1) { blocks.add(json.substring(start, i + 1)); start = -1; } }
        }
        return blocks;
    }

    private String field(String json, String key) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private int intField(String json, String key, int def) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : def;
        } catch (Exception e) { return def; }
    }

    private record TimerEntry(String name, int intervalMinutes, List<String> messages) {}
}