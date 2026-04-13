package com.soulshinygame.bot.overlay;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.util.DailyLimitManager;
import com.soulshinygame.bot.util.FollowerCache;
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

public class AnimeCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(AnimeCommand.class);
    private static final String FILENAME   = "anime_characters.json";
    private static final int DAILY_LIMIT   = 1;
    private static final String COLLECTION = "anime";

    private static final Map<String, Integer> RARITY_WEIGHTS = Map.of(
            "comun", 60, "raro", 25, "epico", 12, "legendario", 3);
    private static final Map<String, String> RARITY_EMOJI = Map.of(
            "comun","⚪","raro","🔵","epico","🟣","legendario","⭐");

    private final WebSocketOverlayServer overlayServer;
    private final DatabaseManager db;
    private final FollowerCache followerCache;
    private final DailyLimitManager dailyLimit;
    private final Random random = new Random();
    private List<AnimeData> characters = new ArrayList<>();

    public AnimeCommand(WebSocketOverlayServer overlayServer, DatabaseManager db,
                        FollowerCache followerCache, DailyLimitManager dailyLimit) {
        this.overlayServer = overlayServer;
        this.db            = db;
        this.followerCache = followerCache;
        this.dailyLimit    = dailyLimit;
        loadCharacters();
    }

    @Override public String getName() { return "anime"; }
    @Override public long getCooldownMs() { return 20_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String username = event.getUser().getName();
        String userId   = event.getUser().getId();

        if (characters.isEmpty()) {
            client.getChat().sendMessage(channel, "No hay personajes cargados. Comprueba " + FILENAME);
            return;
        }

        // 1. Verificar seguidor
        if (!followerCache.isFollower(userId)) {
            client.getChat().sendMessage(channel,
                    "@" + username + " ¡Tienes que seguir el canal para invocar personajes! 💜");
            return;
        }

        // 2. Verificar límite diario
        if (!dailyLimit.canCollect(COLLECTION, username, DAILY_LIMIT)) {
            client.getChat().sendMessage(channel,
                    "@" + username + " ya invocaste tu personaje de hoy. ¡Vuelve mañana! 📅");
            return;
        }

        dailyLimit.increment(COLLECTION, username);

        new Thread(() -> {
            try {
                AnimeData character = pickWeightedRandom();
                String emoji = RARITY_EMOJI.getOrDefault(character.getRarity(), "⚪");

                client.getChat().sendMessage(channel,
                        emoji + " @" + username + " invocó a " + character.getName() +
                                " de " + character.getSeries() +
                                "! [" + character.getRarity().toUpperCase() + "] | Próxima invocación mañana 📅");

                String colType = character.getCollectionType() != null
                        ? character.getCollectionType() : COLLECTION;

                if (character.getItemId() != null) {
                    boolean isNew = db.registerCollectible(username, colType,
                            character.getItemId(), character.getName());
                    if (isNew) {
                        long total = db.getCollectionCount(username, colType);
                        client.getChat().sendMessage(channel,
                                "✨ @" + username + " desbloqueó: " + character.getName() +
                                        " (" + total + " de " + colType + ")");
                    }
                }

                overlayServer.sendEvent(character.toJson(username));
                log.info("!anime: {} → {} [{}]", username, character.getName(), character.getRarity());

            } catch (Exception e) {
                log.error("Error en !anime", e);
            }
        }).start();
    }

    public void loadCharacters() {
        Path path = Path.of(FILENAME);
        if (!Files.exists(path)) { log.warn("{} no encontrado.", FILENAME); return; }
        try {
            characters = parse(Files.readString(path));
            log.info("AnimeCommand: {} personajes cargados", characters.size());
        } catch (IOException e) { log.error("Error leyendo {}", FILENAME, e); }
    }

    private AnimeData pickWeightedRandom() {
        int total = characters.stream().mapToInt(c -> RARITY_WEIGHTS.getOrDefault(c.getRarity(), 60)).sum();
        int roll = random.nextInt(total), acc = 0;
        for (AnimeData c : characters) {
            acc += RARITY_WEIGHTS.getOrDefault(c.getRarity(), 60);
            if (roll < acc) return c;
        }
        return characters.get(random.nextInt(characters.size()));
    }

    private List<AnimeData> parse(String json) {
        List<AnimeData> result = new ArrayList<>();
        for (String block : extractTopLevelBlocks(json)) {
            try {
                int id = intField(block,"id",0);
                String name = field(block,"name"), series = field(block,"series"),
                        gifUrl = field(block,"gifUrl"), rarity = field(block,"rarity"),
                        desc = field(block,"description"), color = field(block,"accentColor"),
                        colType = field(block,"collectionType"), itemId = field(block,"itemId");
                if (name.isEmpty() || gifUrl.isEmpty()) continue;
                if (rarity.isEmpty()) rarity = "comun";
                result.add(new AnimeData(id, name, series, gifUrl, rarity, desc, color, colType, itemId));
            } catch (Exception e) { log.warn("Error parseando personaje: {}", e.getMessage()); }
        }
        return result;
    }

    private List<String> extractTopLevelBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        int depth=0, start=-1;
        for (int i=0; i<json.length(); i++) {
            char c = json.charAt(i);
            if (c=='"') { i++; while(i<json.length()&&json.charAt(i)!='"'){if(json.charAt(i)=='\\')i++;i++;} continue; }
            if (c=='{') { if(depth==0)start=i; depth++; }
            else if (c=='}') { depth--; if(depth==0&&start!=-1){blocks.add(json.substring(start,i+1));start=-1;} }
        }
        return blocks;
    }

    private String field(String json, String key) {
        try {
            Pattern p = Pattern.compile("\""+key+"\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private int intField(String json, String key, int def) {
        try {
            Pattern p = Pattern.compile("\""+key+"\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : def;
        } catch (Exception e) { return def; }
    }
}