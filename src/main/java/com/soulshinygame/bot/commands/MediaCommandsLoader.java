package com.soulshinygame.bot.commands;

import com.soulshinygame.bot.commands.impl.MediaCommand;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaCommandsLoader {

    private static final Logger log = LoggerFactory.getLogger(MediaCommandsLoader.class);
    private static final String FILENAME = "media_commands.json";

    private final WebSocketOverlayServer overlayServer;
    private final DatabaseManager db;

    public MediaCommandsLoader(WebSocketOverlayServer overlayServer, DatabaseManager db) {
        this.overlayServer = overlayServer;
        this.db = db;
    }

    public void loadInto(CommandRegistry registry) {
        Path path = Path.of(FILENAME);
        if (!Files.exists(path)) {
            log.warn("No se encontró {}. Crea el fichero al lado del JAR para añadir comandos de media.", FILENAME);
            return;
        }
        try {
            String json = Files.readString(path);
            List<MediaCommand> commands = parse(json);
            commands.forEach(registry::register);
            log.info("Cargados {} comandos de media desde {}", commands.size(), FILENAME);
        } catch (IOException e) {
            log.error("Error leyendo {}", FILENAME, e);
        }
    }

    private List<MediaCommand> parse(String json) {
        List<MediaCommand> result = new ArrayList<>();
        for (String block : extractTopLevelBlocks(json)) {
            try {
                String command        = field(block, "command");
                String title          = field(block, "title");
                String chatMessage    = field(block, "chatMessage");
                String mediaUrl       = field(block, "mediaUrl");
                String accentColor    = field(block, "accentColor");
                String soundUrl       = field(block, "soundUrl");
                int duration          = intField(block, "durationSeconds", 8);
                int cooldown          = intField(block, "cooldownSeconds", 15);
                String collectionType = field(block, "collectionType");
                String itemId         = field(block, "itemId");
                String itemName       = field(block, "itemName");

                if (command.isEmpty() || mediaUrl.isEmpty()) {
                    log.warn("Entrada ignorada en {}: falta 'command' o 'mediaUrl'", FILENAME);
                    continue;
                }

                result.add(new MediaCommand.Builder(command)
                        .title(title)
                        .chatMessage(chatMessage)
                        .mediaUrl(mediaUrl)
                        .accentColor(accentColor.isEmpty() ? "#e53935" : accentColor)
                        .soundUrl(soundUrl)
                        .duration(duration)
                        .cooldownSeconds(cooldown)
                        .collectionType(collectionType.isEmpty() ? null : collectionType)
                        .itemId(itemId.isEmpty() ? null : itemId)
                        .itemName(itemName.isEmpty() ? null : itemName)
                        .build(overlayServer, db));

                log.debug("Comando de media cargado: !{}", command);
            } catch (Exception e) {
                log.warn("Error parseando bloque de {}: {}", FILENAME, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Extrae bloques {} de nivel superior del array JSON.
     * Usa contador de profundidad para no confundirse con {user} u objetos anidados.
     */
    private List<String> extractTopLevelBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            // Saltar strings entre comillas para ignorar { } dentro de valores
            if (c == '"') {
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++; // saltar carácter escapado
                    i++;
                }
                continue;
            }

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    blocks.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return blocks;
    }

    private String field(String json, String key) {
        try {
            // Captura el valor incluyendo {user} u otros { } dentro del string
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private int intField(String json, String key, int defaultValue) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
        } catch (Exception e) { return defaultValue; }
    }
}