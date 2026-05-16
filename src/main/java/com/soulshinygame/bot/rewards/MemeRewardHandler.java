package com.soulshinygame.bot.rewards;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.eventsub.events.CustomRewardRedemptionAddEvent;
import com.github.twitch4j.eventsub.subscriptions.SubscriptionTypes;
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
 * Maneja recompensas de espectador via EventSub (PubSub fue cerrado el 14/04/2025).
 *
 * REQUISITOS:
 *   - Twitch4J 1.24+ con EventSocket habilitado en TwitchClientBuilder
 *   - BOT_ACCESS_TOKEN del broadcaster con scope channel:read:redemptions
 */
public class MemeRewardHandler {

    private static final Logger log = LoggerFactory.getLogger(MemeRewardHandler.class);
    private static final String FILENAME = "meme_videos.json";

    private final TwitchClient client;
    private final WebSocketOverlayServer overlayServer;
    private final String broadcasterId;
    private final String accessToken;
    private final String clientId;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Random random = new Random();

    private final List<RewardConfig> rewards = new ArrayList<>();
    private final Map<String, String> resolvedUrls = new HashMap<>();

    public MemeRewardHandler(TwitchClient client, WebSocketOverlayServer overlayServer,
                             String broadcasterId, String accessToken, String clientId) {
        this.client        = client;
        this.overlayServer = overlayServer;
        this.broadcasterId = broadcasterId;
        this.accessToken   = accessToken.replace("oauth:", "");
        this.clientId      = clientId;
    }

    public void start() {
        loadConfig();
        listChannelRewards();

        if (rewards.isEmpty()) {
            log.warn("MemeRewardHandler: sin recompensas configuradas (rewardId vacío)");
            return;
        }

        resolveAllUrls();
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(this::resolveAllUrls, 6, 6, TimeUnit.HOURS);

        try {
            OAuth2Credential cred = new OAuth2Credential("twitch", accessToken);

            // Crear suscripción EventSub para redenciones de recompensas custom
            var subscription = SubscriptionTypes.CHANNEL_POINTS_CUSTOM_REWARD_REDEMPTION_ADD
                    .prepareSubscription(
                            builder -> builder.broadcasterUserId(broadcasterId).build(),
                            null  // transport: el EventSocket rellenará session_id automáticamente
                    );

            client.getEventSocket().register(cred, subscription);

            client.getEventManager().onEvent(CustomRewardRedemptionAddEvent.class, this::onRewardRedeemed);

            log.info("MemeRewardHandler (EventSub) escuchando {} recompensa(s)", rewards.size());

        } catch (Exception e) {
            log.error("Error suscribiendo a EventSub", e);
        }
    }

    /**
     * Lista todas las recompensas del canal con sus IDs.
     */
    private void listChannelRewards() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.twitch.tv/helix/channel_points/custom_rewards?broadcaster_id=" + broadcasterId))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Client-Id", clientId)
                    .GET().build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("No se pudieron listar recompensas (status {}): {}", res.statusCode(), res.body());
                return;
            }

            String body = res.body();

            // Aislar el array "data": [...]
            int dataStart = body.indexOf("\"data\"");
            if (dataStart == -1) {
                log.warn("Respuesta sin campo 'data'. Body completo: {}", body);
                return;
            }
            int arrayStart = body.indexOf('[', dataStart);
            int arrayEnd   = findMatchingBracket(body, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) {
                log.warn("No se pudo aislar el array de data. Body: {}", body);
                return;
            }

            // Extraer cada objeto-recompensa del array (objetos top-level)
            String arrayContent = body.substring(arrayStart + 1, arrayEnd);
            List<String> rewardBlocks = extractTopLevelObjects(arrayContent);

            log.info("");
            log.info("╔══════════════════════════════════════════════════════════════════╗");
            log.info("║          RECOMPENSAS DEL CANAL — COPIA EL ID QUE NECESITES        ║");
            log.info("╚══════════════════════════════════════════════════════════════════╝");

            int count = 0;
            for (String block : rewardBlocks) {
                // Solo nos interesan los campos del nivel superior del objeto-recompensa
                String id    = topLevelString(block, "id");
                String title = topLevelString(block, "title");
                String cost  = topLevelNumber(block, "cost");

                if (id.isEmpty()) continue;

                log.info("  📌 '{}' ({} pts)", title.isEmpty() ? "(sin título)" : title,
                        cost.isEmpty() ? "?" : cost);
                log.info("     ID: {}", id);
                count++;
            }
            log.info("══════════════════════════════════════════════════════════════════");
            log.info("Total: {} recompensa(s) encontradas", count);
            if (count == 0) {
                log.info("");
                log.info("Si no aparece ninguna, comprueba:");
                log.info("  1. Las recompensas están creadas y activas en el dashboard de Twitch");
                log.info("  2. El token tiene scope 'channel:read:redemptions'");
                log.info("  3. El token es del broadcaster (no de la cuenta del bot)");
                log.info("");
                log.info("Para debug, respuesta cruda de Twitch (primeros 800 chars):");
                log.info(body.length() > 800 ? body.substring(0, 800) + "..." : body);
            }
            log.info("");

        } catch (Exception e) {
            log.warn("Error listando recompensas: {}", e.getMessage());
        }
    }

    // ── Helpers para parsear JSON sin librerías ──────────────────

    /**
     * Devuelve los objetos { ... } de primer nivel dentro de un array.
     * Maneja objetos anidados y strings con caracteres especiales.
     */
    private List<String> extractTopLevelObjects(String json) {
        List<String> blocks = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {  // saltar strings
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
                if (depth == 0 && start != -1) {
                    blocks.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return blocks;
    }

    /**
     * Extrae un campo de tipo string del nivel superior de un objeto JSON
     * (ignora campos con el mismo nombre dentro de objetos anidados).
     */
    private String topLevelString(String objectJson, String key) {
        return scanField(objectJson, key, true);
    }

    /** Extrae un campo numérico del nivel superior */
    private String topLevelNumber(String objectJson, String key) {
        return scanField(objectJson, key, false);
    }

    private String scanField(String json, String key, boolean isString) {
        String search = "\"" + key + "\"";
        int depth = 0;  // 0 = nivel raíz del objeto

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                // Si estamos en nivel raíz y encontramos la key, leemos el valor
                if (depth == 1 && json.startsWith(search, i)) {
                    int colon = json.indexOf(':', i + search.length());
                    if (colon == -1) return "";
                    int valStart = colon + 1;
                    while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
                    if (valStart >= json.length()) return "";

                    if (isString) {
                        if (json.charAt(valStart) != '"') return "";
                        int valEnd = valStart + 1;
                        StringBuilder sb = new StringBuilder();
                        while (valEnd < json.length() && json.charAt(valEnd) != '"') {
                            if (json.charAt(valEnd) == '\\' && valEnd + 1 < json.length()) {
                                sb.append(json.charAt(valEnd + 1));
                                valEnd += 2;
                            } else {
                                sb.append(json.charAt(valEnd));
                                valEnd++;
                            }
                        }
                        return sb.toString();
                    } else {
                        // Número
                        StringBuilder sb = new StringBuilder();
                        while (valStart < json.length()
                                && (Character.isDigit(json.charAt(valStart)) || json.charAt(valStart) == '-')) {
                            sb.append(json.charAt(valStart));
                            valStart++;
                        }
                        return sb.toString();
                    }
                }
                // Saltar string (no es nuestra key)
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return "";
    }

    private int findMatchingBracket(String json, int startBracket) {
        int depth = 0;
        for (int i = startBracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
                continue;
            }
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private void onRewardRedeemed(CustomRewardRedemptionAddEvent event) {
        try {
            String rewardId = event.getReward().getId();
            String title    = event.getReward().getTitle();
            String user     = event.getUserName();

            log.info("🎁 Recompensa canjeada por {}: '{}' (ID: {})", user, title, rewardId);

            RewardConfig match = null;
            for (RewardConfig r : rewards) {
                if (r.rewardId.equals(rewardId)) { match = r; break; }
            }
            if (match == null) return;

            playRandomVideo(match, user);

        } catch (Exception e) {
            log.error("Error procesando recompensa", e);
        }
    }

    private void playRandomVideo(RewardConfig reward, String username) {
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
        double volume    = reward.muted ? 0 : 0.8;

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
        if (!Files.exists(path)) { log.warn("{} no encontrado.", FILENAME); return; }
        try {
            String json = Files.readString(path);
            int rewardsStart = json.indexOf("\"rewards\"");
            if (rewardsStart == -1) return;

            int arrayStart = json.indexOf('[', rewardsStart);
            int arrayEnd   = findMatching(json, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return;

            String arrayContent = json.substring(arrayStart, arrayEnd + 1);
            for (String block : extractTopLevelBlocks(arrayContent)) {
                RewardConfig r = parseReward(block);
                if (r != null) rewards.add(r);
            }
            log.info("Cargadas {} recompensa(s) del fichero {}", rewards.size(), FILENAME);
        } catch (IOException e) {
            log.error("Error leyendo {}", FILENAME, e);
        }
    }

    private RewardConfig parseReward(String block) {
        try {
            String rewardId = field(block, "rewardId");
            if (rewardId.isEmpty() || rewardId.startsWith("PEGA_AQUI")) {
                log.warn("Recompensa sin rewardId válido, ignorada");
                return null;
            }
            String name   = field(block, "name");
            String icon   = field(block, "icon");
            boolean muted = boolField(block, "muted", true);
            int duration  = intField(block, "durationSeconds", 15);
            if (icon.isEmpty()) icon = muted ? "🎭" : "🔊";

            List<MemeVideo> videos = new ArrayList<>();
            Pattern p = Pattern.compile(
                    "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"streamableId\"\\s*:\\s*\"([^\"]+)\"\\s*\\}");
            Matcher m = p.matcher(block);
            while (m.find()) videos.add(new MemeVideo(m.group(1), m.group(2)));

            if (videos.isEmpty()) { log.warn("Recompensa '{}' sin videos", name); return null; }
            return new RewardConfig(rewardId, name, icon, muted, duration, videos);
        } catch (Exception e) {
            log.warn("Error parseando recompensa: {}", e.getMessage());
            return null;
        }
    }

    // ── Streamable ────────────────────────────────────────────────

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
            if (c == '"') { i++; while (i < json.length() && json.charAt(i) != '"') { if (json.charAt(i) == '\\') i++; i++; } continue; }
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
        try { Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""); Matcher m = p.matcher(json); return m.find() ? m.group(1) : ""; }
        catch (Exception e) { return ""; }
    }

    private int intField(String json, String key, int def) {
        try { Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)"); Matcher m = p.matcher(json); return m.find() ? Integer.parseInt(m.group(1)) : def; }
        catch (Exception e) { return def; }
    }

    private boolean boolField(String json, String key, boolean def) {
        try { Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)"); Matcher m = p.matcher(json); return m.find() ? Boolean.parseBoolean(m.group(1)) : def; }
        catch (Exception e) { return def; }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record MemeVideo(String name, String streamableId) {}
    private record RewardConfig(String rewardId, String name, String icon, boolean muted, int durationSeconds, List<MemeVideo> videos) {}
}