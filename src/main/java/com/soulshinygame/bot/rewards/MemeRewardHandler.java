package com.soulshinygame.bot.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.eventsub.events.CustomRewardRedemptionAddEvent;
import com.github.twitch4j.eventsub.subscriptions.SubscriptionTypes;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maneja recompensas de espectador via EventSub (PubSub fue cerrado el 14/04/2025).
 *
 * La configuración vive en meme_videos.json y se puede recargar en caliente con {@link #reload()}
 * (lo usa la pestaña "Rewards" del panel admin), así que no hace falta reiniciar el bot.
 *
 * REQUISITOS:
 *   - Twitch4J con EventSocket habilitado en TwitchClientBuilder
 *   - BOT_ACCESS_TOKEN del broadcaster con scope channel:read:redemptions
 *     (y channel:manage:redemptions si quieres crear/editar rewards desde el panel)
 */
public class MemeRewardHandler {

    private static final Logger log = LoggerFactory.getLogger(MemeRewardHandler.class);

    private final TwitchClient client;
    private final WebSocketOverlayServer overlayServer;
    private final String broadcasterId;
    private final String accessToken;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Random random = new Random();

    private final RewardConfigStore store = new RewardConfigStore();
    private final TwitchRewardsApi api;

    private volatile List<RewardConfig> rewards = List.of();
    private final Map<String, String> resolvedUrls = new ConcurrentHashMap<>();

    public MemeRewardHandler(TwitchClient client, WebSocketOverlayServer overlayServer,
                             String broadcasterId, String accessToken, String clientId) {
        this.client        = client;
        this.overlayServer = overlayServer;
        this.broadcasterId = broadcasterId;
        this.accessToken   = accessToken.replace("oauth:", "");
        this.api           = new TwitchRewardsApi(broadcasterId, accessToken, clientId);
    }

    public RewardConfigStore getStore() { return store; }
    public TwitchRewardsApi  getApi()   { return api; }

    public void start() {
        reload();
        logChannelRewards();

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "meme-url-refresh");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> resolveAllUrls(true), 6, 6, TimeUnit.HOURS);

        // Nos suscribimos SIEMPRE (aunque aún no haya rewards configuradas):
        // así las que se añadan desde el panel funcionan sin reiniciar.
        try {
            OAuth2Credential cred = new OAuth2Credential("twitch", accessToken);

            var subscription = SubscriptionTypes.CHANNEL_POINTS_CUSTOM_REWARD_REDEMPTION_ADD
                    .prepareSubscription(
                            builder -> builder.broadcasterUserId(broadcasterId).build(),
                            null  // transport: el EventSocket rellenará session_id automáticamente
                    );

            client.getEventSocket().register(cred, subscription);
            client.getEventManager().onEvent(CustomRewardRedemptionAddEvent.class, this::onRewardRedeemed);

            log.info("MemeRewardHandler (EventSub) escuchando canjes de recompensas");

        } catch (Exception e) {
            log.error("Error suscribiendo a EventSub", e);
        }
    }

    // ── Recarga en caliente ───────────────────────────────────────

    /** Vuelve a leer meme_videos.json y aplica los cambios al instante. */
    public synchronized void reload() {
        List<RewardConfig> loaded = new ArrayList<>();
        try {
            for (JsonNode node : store.load()) {
                RewardConfig r = parseReward(node);
                if (r != null) loaded.add(r);
            }
        } catch (Exception e) {
            log.error("Error leyendo {}: {}", RewardConfigStore.FILENAME, e.getMessage());
            return; // conservamos la configuración anterior
        }
        rewards = List.copyOf(loaded);
        log.info("Cargadas {} recompensa(s) activas de {}", rewards.size(), RewardConfigStore.FILENAME);
        resolveAllUrls(false);
    }

    private RewardConfig parseReward(JsonNode n) {
        String rewardId = n.path("rewardId").asText("").trim();
        String name     = n.path("name").asText("");
        if (rewardId.isEmpty() || rewardId.startsWith("PEGA_AQUI")) {
            log.debug("Recompensa '{}' aún sin rewardId (no creada en Twitch), ignorada", name);
            return null;
        }
        boolean muted = n.path("muted").asBoolean(true);
        int duration  = n.path("durationSeconds").asInt(15);
        String icon   = n.path("icon").asText("");
        if (icon.isEmpty()) icon = muted ? "🎭" : "🔊";

        List<MemeVideo> videos = new ArrayList<>();
        for (JsonNode v : n.path("videos")) {
            String id = v.path("streamableId").asText("").trim();
            if (!id.isEmpty()) videos.add(new MemeVideo(v.path("name").asText(id), id));
        }
        if (videos.isEmpty()) { log.warn("Recompensa '{}' sin videos", name); return null; }
        return new RewardConfig(rewardId, name, icon, muted, duration, videos);
    }

    // ── Info al arrancar ──────────────────────────────────────────

    private void logChannelRewards() {
        var res = api.list(false);
        if (!res.ok()) { log.warn("No se pudieron listar recompensas: {}", res.error()); return; }
        log.info("Recompensas del canal:");
        for (JsonNode r : res.body().path("data")) {
            log.info("  📌 '{}' ({} pts)  ID: {}", r.path("title").asText(), r.path("cost").asInt(), r.path("id").asText());
        }
    }

    // ── Canjes ────────────────────────────────────────────────────

    private void onRewardRedeemed(CustomRewardRedemptionAddEvent event) {
        try {
            String rewardId = event.getReward().getId();
            String title    = event.getReward().getTitle();
            String user     = event.getUserName();

            log.info("🎁 Recompensa canjeada por {}: '{}' (ID: {})", user, title, rewardId);

            for (RewardConfig r : rewards) {
                if (r.rewardId.equals(rewardId)) { playRandomVideo(r, user); return; }
            }
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

    // ── Streamable ────────────────────────────────────────────────

    /** force=true vuelve a resolver todo (las URLs caducan); false solo lo que falte. */
    private void resolveAllUrls(boolean force) {
        List<RewardConfig> snapshot = rewards;
        Thread t = new Thread(() -> {
            int ok = 0, total = 0;
            for (RewardConfig r : snapshot) {
                for (MemeVideo v : r.videos) {
                    total++;
                    if (!force && resolvedUrls.containsKey(v.streamableId)) { ok++; continue; }
                    String url = resolveStreamable(v.streamableId);
                    if (url != null) { resolvedUrls.put(v.streamableId, url); ok++; }
                    else log.warn("No se pudo resolver el vídeo de Streamable '{}'", v.streamableId);
                }
            }
            log.info("URLs de meme resueltas: {}/{}", ok, total);
        }, "meme-url-resolve");
        t.setDaemon(true);
        t.start();
    }

    private String resolveStreamable(String id) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.streamable.com/videos/" + id))
                    .header("User-Agent", "SoulShinyBot/1.0").GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            // Si el código no es 200 (ej. 404), el vídeo ya no existe en Streamable
            if (res.statusCode() != 200) return null;

            // Expresión regular flexibilizada para aceptar enlaces con o sin "https:" y sin forzar el ".mp4"
            Pattern p = Pattern.compile("\"url\"\\s*:\\s*\"((?:https?:)?//[^\"]+)\"");
            Matcher m = p.matcher(res.body());

            if (m.find()) {
                String url = m.group(1);
                // Si la URL devuelta empieza por "//", le añadimos "https:" para que el overlay pueda cargarla
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
                return url;
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record MemeVideo(String name, String streamableId) {}
    private record RewardConfig(String rewardId, String name, String icon, boolean muted, int durationSeconds, List<MemeVideo> videos) {}
}
