package com.soulshinygame.bot.rewards;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.pubsub.events.RewardRedeemedEvent;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maneja varias "Recompensas de espectador" de Twitch.
 *
 * Cada recompensa en meme_videos.json tiene:
 *   - name: nombre exacto de la recompensa en Twitch
 *   - icon: emoji que sale en el panel del overlay
 *   - muted: true para reproducir sin sonido
 *   - durationSeconds: cuánto dura visible
 *   - videos: lista de IDs de Streamable
 *
 * Al canjear, elige uno aleatorio de la lista y lo manda al overlay.
 */
public class MemeRewardHandler {

    private static final Logger log = LoggerFactory.getLogger(MemeRewardHandler.class);
    private static final String FILENAME = "meme_videos.json";

    private final TwitchClient client;
    private final WebSocketOverlayServer overlayServer;
    private final String broadcasterId;
    private final String accessToken;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Random random = new Random();

    private final List<RewardConfig> rewards = new ArrayList<>();
    private final Map<String, String> resolvedUrls = new HashMap<>(); // streamableId → mp4 url

    public MemeRewardHandler(TwitchClient client, WebSocketOverlayServer overlayServer,
                             String broadcasterId, String accessToken) {
        this.client        = client;
        this.overlayServer = overlayServer;
        this.broadcasterId = broadcasterId;
        this.accessToken   = accessToken.replace("oauth:", "");
    }

    public void start() {
        loadConfig();
        if (rewards.isEmpty()) {
            log.warn("MemeRewardHandler: sin recompensas configuradas, no se activa");
            return;
        }

        resolveAllUrls();

        // Re-resolver cada 6h (URLs firmadas de Streamable expiran)
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(this::resolveAllUrls, 6, 6, TimeUnit.HOURS);

        try {
            OAuth2Credential cred = new OAuth2Credential("twitch", accessToken);
            client.getPubSub().listenForChannelPointsRedemptionEvents(cred, broadcasterId);
            client.getEventManager().onEvent(RewardRedeemedEvent.class, this::onRewardRedeemed);

            String names = rewards.stream().map(r -> "'" + r.name + "'")
                    .reduce((a, b) -> a + ", " + b).orElse("");
            log.info("MemeRewardHandler escuchando recompensas: {}", names);

        } catch (Exception e) {
            log.error("Error suscribiendo a PubSub", e);
        }
    }

    private void onRewardRedeemed(RewardRedeemedEvent event) {
        try {
            String title = event.getRedemption().getReward().getTitle();
            String user  = event.getRedemption().getUser().getDisplayName();

            // Buscar la recompensa por nombre (insensitivo a mayúsculas)
            RewardConfig match = null;
            for (RewardConfig r : rewards) {
                if (r.name.equalsIgnoreCase(title)) { match = r; break; }
            }
            if (match == null) return;

            log.info("Recompensa '{}' canjeada por {}", title, user);
            playRandomVideo(match, user);

        } catch (Exception e) {
            log.error("Error procesando recompensa", e);
        }
    }

    private void playRandomVideo(RewardConfig reward, String username) {
        // Filtrar videos con URL resuelta
        List<MemeVideo> available = new ArrayList<>();
        for (MemeVideo v : reward.videos) {
            if (resolvedUrls.containsKey(v.streamableId)) available.add(v);
        }
        if (available.isEmpty()) {
            log.warn("Recompensa '{}': sin videos disponibles", reward.name);
            return;
        }

        MemeVideo chosen = available.get(random.nextInt(available.size()));
        String videoUrl  = resolvedUrls.get(chosen.streamableId);

        // volume = 0 si está muted, 0.8 si tiene sonido
        double volume = reward.muted ? 0 : 0.8;

        overlayServer.sendEvent(String.format("""
            {
              "type": "video",
              "title": "%s %s",
              "triggeredBy": "%s",
              "videoUrl": "%s",
              "muted": %b,
              "volume": %s,
              "durationSeconds": %d
            }
            """,
                escape(reward.icon), escape(chosen.name), escape(username),
                videoUrl, reward.muted, volume, reward.durationSeconds));

        log.info("Reproducido: '{}' (de '{}') para {}", chosen.name, reward.name, username);
    }

    // ── Carga del JSON ────────────────────────────────────────────

    private void loadConfig() {
        Path path = Path.of(FILENAME);
        if (!Files.exists(path)) {
            log.warn("{} no encontrado.", FILENAME);
            return;
        }
        try {
            String json = Files.readString(path);

            // Aislar el array "rewards"
            int rewardsStart = json.indexOf("\"rewards\"");
            if (rewardsStart == -1) {
                // Compatibilidad con la estructura antigua (sin "rewards")
                log.warn("Estructura antigua detectada — cargando como recompensa única 'meme'");
                loadLegacyFormat(json);
                return;
            }

            int arrayStart = json.indexOf('[', rewardsStart);
            int arrayEnd   = findMatching(json, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return;

            String arrayContent = json.substring(arrayStart, arrayEnd + 1);

            // Extraer cada objeto del array de recompensas
            for (String block : extractTopLevelBlocks(arrayContent)) {
                RewardConfig r = parseReward(block);
                if (r != null) rewards.add(r);
            }

            log.info("MemeRewardHandler: {} recompensa(s) cargada(s)", rewards.size());

        } catch (IOException e) {
            log.error("Error leyendo {}", FILENAME, e);
        }
    }

    private RewardConfig parseReward(String block) {
        try {
            String name = field(block, "name");
            if (name.isEmpty()) return null;
            String icon       = field(block, "icon");
            boolean muted     = boolField(block, "muted", true);
            int duration      = intField(block, "durationSeconds", 15);
            if (icon.isEmpty()) icon = muted ? "🎭" : "🔊";

            // Extraer videos del array
            List<MemeVideo> videos = new ArrayList<>();
            Pattern p = Pattern.compile(
                    "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"streamableId\"\\s*:\\s*\"([^\"]+)\"\\s*\\}");
            Matcher m = p.matcher(block);
            while (m.find()) videos.add(new MemeVideo(m.group(1), m.group(2)));

            if (videos.isEmpty()) {
                log.warn("Recompensa '{}' sin videos, ignorada", name);
                return null;
            }
            return new RewardConfig(name, icon, muted, duration, videos);
        } catch (Exception e) {
            log.warn("Error parseando recompensa: {}", e.getMessage());
            return null;
        }
    }

    /** Compatibilidad con el formato antiguo de meme_videos.json */
    private void loadLegacyFormat(String json) {
        String rewardName = field(json, "rewardName");
        if (rewardName.isEmpty()) rewardName = "meme";
        List<MemeVideo> videos = new ArrayList<>();
        Pattern p = Pattern.compile(
                "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"streamableId\"\\s*:\\s*\"([^\"]+)\"\\s*\\}");
        Matcher m = p.matcher(json);
        while (m.find()) videos.add(new MemeVideo(m.group(1), m.group(2)));
        if (!videos.isEmpty()) rewards.add(new RewardConfig(rewardName, "🎭", true, 15, videos));
    }

    // ── Resolución de URLs de Streamable ──────────────────────────

    private void resolveAllUrls() {
        new Thread(() -> {
            int ok = 0, total = 0;
            for (RewardConfig r : rewards) {
                for (MemeVideo v : r.videos) {
                    total++;
                    String url = resolveStreamable(v.streamableId);
                    if (url != null) { resolvedUrls.put(v.streamableId, url); ok++; }
                }
            }
            log.info("URLs de meme resueltas: {}/{}", ok, total);
        }).start();
    }

    private String resolveStreamable(String id) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.streamable.com/videos/" + id))
                    .header("User-Agent", "SoulShinyBot/1.0").GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            Pattern p = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\"");
            Matcher m = p.matcher(res.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    // ── Utilidades ────────────────────────────────────────────────

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
            else if (c == '}') { depth--; if (depth == 0 && start != -1) { blocks.add(json.substring(start, i + 1)); start = -1; } }
        }
        return blocks;
    }

    private int findMatching(String json, int startBracket) {
        int depth = 0;
        for (int i = startBracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') { i++; while (i < json.length() && json.charAt(i) != '"') { if (json.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private String field(String json, String key) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
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

    private boolean boolField(String json, String key, boolean def) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
            Matcher m = p.matcher(json);
            return m.find() ? Boolean.parseBoolean(m.group(1)) : def;
        } catch (Exception e) { return def; }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record MemeVideo(String name, String streamableId) {}
    private record RewardConfig(String name, String icon, boolean muted, int durationSeconds, List<MemeVideo> videos) {}
}