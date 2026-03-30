package com.soulshinygame.bot.overlay;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comando !anime — elige un personaje aleatorio de anime_characters.json
 * y lo muestra en el overlay de OBS.
 *
 * La rareza afecta la probabilidad de aparición:
 *   comun      → 60%
 *   raro       → 25%
 *   epico      → 12%
 *   legendario →  3%
 *
 * El fichero anime_characters.json debe estar al lado del JAR.
 */
public class AnimeCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(AnimeCommand.class);
    private static final String FILENAME = "anime_characters.json";

    // Pesos de rareza
    private static final Map<String, Integer> RARITY_WEIGHTS = Map.of(
            "comun",      60,
            "raro",       25,
            "epico",      12,
            "legendario",  3
    );

    private static final Map<String, String> RARITY_EMOJI = Map.of(
            "comun",      "⚪",
            "raro",       "🔵",
            "epico",      "🟣",
            "legendario", "⭐"
    );

    private final WebSocketOverlayServer overlayServer;
    private final DatabaseManager db;
    private final Random random = new Random();

    private List<AnimeData> characters = new ArrayList<>();

    public AnimeCommand(WebSocketOverlayServer overlayServer, DatabaseManager db) {
        this.overlayServer = overlayServer;
        this.db = db;
        loadCharacters();
    }

    @Override
    public String getName() { return "anime"; }

    @Override
    public long getCooldownMs() { return 20_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        if (characters.isEmpty()) {
            client.getChat().sendMessage(channel,
                    "No hay personajes cargados. Comprueba el fichero " + FILENAME);
            return;
        }

        String user = event.getUser().getName();

        new Thread(() -> {
            try {
                AnimeData character = pickWeightedRandom();

                String emoji = RARITY_EMOJI.getOrDefault(character.getRarity(), "⚪");

                // Mensaje en el chat
                client.getChat().sendMessage(channel,
                        emoji + " @" + user + " invocó a " + character.getName() +
                                " de " + character.getSeries() + "! [" + character.getRarity().toUpperCase() + "]");

                // Registrar coleccionable si es nuevo
                if (character.getCollectionType() != null && character.getItemId() != null) {
                    boolean isNew = db.registerCollectible(
                            user,
                            character.getCollectionType(),
                            character.getItemId(),
                            character.getName()
                    );
                    if (isNew) {
                        long total = db.getCollectionCount(user, character.getCollectionType());
                        client.getChat().sendMessage(channel,
                                "✨ @" + user + " desbloqueó: " + character.getName() +
                                        " (" + total + " de " + character.getCollectionType() + ")");
                    }
                }

                // Enviar al overlay
                overlayServer.sendEvent(character.toJson(user));
                log.info("!anime por {}: {} [{}]", user, character.getName(), character.getRarity());

            } catch (Exception e) {
                log.error("Error en comando !anime", e);
            }
        }).start();
    }

    /**
     * Recarga los personajes del JSON en caliente.
     * Se puede llamar con !reloadanime si quieres añadirlo como comando.
     */
    public void loadCharacters() {
        Path path = Path.of(FILENAME);
        if (!Files.exists(path)) {
            log.warn("{} no encontrado. Crea el fichero al lado del JAR.", FILENAME);
            return;
        }
        try {
            String json = Files.readString(path);
            characters = parse(json);
            log.info("AnimeCommand: {} personajes cargados desde {}", characters.size(), FILENAME);
        } catch (IOException e) {
            log.error("Error leyendo {}", FILENAME, e);
        }
    }

    /** Elige un personaje usando probabilidades ponderadas por rareza */
    private AnimeData pickWeightedRandom() {
        int totalWeight = characters.stream()
                .mapToInt(c -> RARITY_WEIGHTS.getOrDefault(c.getRarity(), 60))
                .sum();

        int roll = random.nextInt(totalWeight);
        int accumulated = 0;

        for (AnimeData character : characters) {
            accumulated += RARITY_WEIGHTS.getOrDefault(character.getRarity(), 60);
            if (roll < accumulated) return character;
        }

        // Fallback por si acaso
        return characters.get(random.nextInt(characters.size()));
    }

    // ── Parser JSON ───────────────────────────────────────────────

    private List<AnimeData> parse(String json) {
        List<AnimeData> result = new ArrayList<>();
        for (String block : extractTopLevelBlocks(json)) {
            try {
                int    id             = intField(block, "id", 0);
                String name           = field(block, "name");
                String series         = field(block, "series");
                String gifUrl         = field(block, "gifUrl");
                String rarity         = field(block, "rarity");
                String description    = field(block, "description");
                String accentColor    = field(block, "accentColor");
                String collectionType = field(block, "collectionType");
                String itemId         = field(block, "itemId");

                if (name.isEmpty() || gifUrl.isEmpty()) {
                    log.warn("Personaje ignorado: falta 'name' o 'gifUrl'");
                    continue;
                }
                if (rarity.isEmpty()) rarity = "comun";

                result.add(new AnimeData(id, name, series, gifUrl, rarity,
                        description, accentColor, collectionType, itemId));

            } catch (Exception e) {
                log.warn("Error parseando personaje: {}", e.getMessage());
            }
        }
        return result;
    }

    private List<String> extractTopLevelBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
                continue;
            }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) { blocks.add(json.substring(start, i + 1)); start = -1; }
            }
        }
        return blocks;
    }

    private String field(String json, String key) {
        try {
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