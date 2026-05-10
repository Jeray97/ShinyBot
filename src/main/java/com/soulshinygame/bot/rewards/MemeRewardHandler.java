package com.soulshinygame.bot.rewards;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Escucha redenciones de "Recompensas de espectador" en Twitch via PubSub.
 *
 * Cuando un viewer canjea la recompensa cuyo nombre coincide con "rewardName"
 * en meme_videos.json, el bot:
 *   1. Elige un vídeo aleatorio de la lista
 *   2. Lo reproduce en el overlay de OBS
 *
 * No hay que indicar el ID de la recompensa, el bot la encuentra por nombre.
 *
 * REQUISITOS:
 *   - El BOT_ACCESS_TOKEN debe tener el scope: channel:read:redemptions
 *   - PubSub habilitado en TwitchClientBuilder (.withEnablePubSub(true))
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

    private String rewardName = "meme";
    private final List<MemeVideo> videos = new ArrayList<>();
    private final Map<String, String> resolvedUrls = new HashMap<>(); // streamableId → mp4 url

    public MemeRewardHandler(TwitchClient client, WebSocketOverlayServer overlayServer,
                             String broadcasterId, String accessToken) {
        this.client        = client;
        this.overlayServer = overlayServer;
        this.broadcasterId = broadcasterId;
        this.accessToken   = accessToken.replace("oauth:", "");
    }

    public void start() {
        loadVideosFromJson();
        if (videos.isEmpty()) {
            log.warn("MemeRewardHandler: no hay videos cargados, no se activará el handler");
            return;
        }

        // Resolver URLs de Streamable
        resolveAllUrls();

        // Re-resolver cada 6 horas (las URLs firmadas expiran)
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(this::resolveAllUrls, 6, 6, TimeUnit.HOURS);

        // Suscribirse a las recompensas del canal via PubSub
        try {
            com.github.philippheuer.credentialmanager.domain.OAuth2Credential cred =
                    new com.github.philippheuer.credentialmanager.domain.OAuth2Credential("twitch", accessToken);

            client.getPubSub().listenForChannelPointsRedemptionEvents(cred, broadcasterId);

            client.getEventManager().onEvent(RewardRedeemedEvent.class, this::onRewardRedeemed);

            log.info("MemeRewardHandler escuchando recompensa '{}' en el canal", rewardName);

        } catch (Exception e) {
            log.error("Error suscribiendo a PubSub", e);
        }
    }

    private void onRewardRedeemed(RewardRedeemedEvent event) {
        try {
            String title = event.getRedemption().getReward().getTitle();
            String user  = event.getRedemption().getUser().getDisplayName();

            // Filtrar por nombre de recompensa (insensitivo a mayúsculas)
            if (!title.equalsIgnoreCase(rewardName)) return;

            log.info("Recompensa '{}' canjeada por {}", title, user);
            playRandomMeme(user);

        } catch (Exception e) {
            log.error("Error procesando recompensa", e);
        }
    }

    private void playRandomMeme(String username) {
        if (resolvedUrls.isEmpty()) {
            log.warn("Sin URLs resueltas, no se puede reproducir");
            return;
        }

        // Elegir un video aleatorio que tenga URL resuelta
        List<MemeVideo> available = new ArrayList<>();
        for (MemeVideo v : videos) {
            if (resolvedUrls.containsKey(v.streamableId)) available.add(v);
        }
        if (available.isEmpty()) return;

        MemeVideo chosen = available.get(random.nextInt(available.size()));
        String videoUrl  = resolvedUrls.get(chosen.streamableId);

        overlayServer.sendEvent(String.format("""
            {
              "type": "video",
              "title": "🎭 %s",
              "triggeredBy": "%s",
              "videoUrl": "%s",
              "volume": 0.7,
              "durationSeconds": 20
            }
            """, escape(chosen.name), escape(username), videoUrl));

        log.info("Meme '{}' reproducido para {}", chosen.name, username);
    }

    // ── Carga del JSON ────────────────────────────────────────────

    private void loadVideosFromJson() {
        Path path = Path.of(FILENAME);
        if (!Files.exists(path)) {
            log.warn("{} no encontrado.", FILENAME);
            return;
        }
        try {
            String json = Files.readString(path);
            String name = field(json, "rewardName");
            if (!name.isEmpty()) rewardName = name;

            // Extraer cada bloque {"name":"...","streamableId":"..."}
            Pattern p = Pattern.compile(
                    "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"streamableId\"\\s*:\\s*\"([^\"]+)\"\\s*\\}");
            Matcher m = p.matcher(json);
            while (m.find()) {
                videos.add(new MemeVideo(m.group(1), m.group(2)));
            }
            log.info("MemeRewardHandler: {} videos cargados (recompensa='{}')", videos.size(), rewardName);
        } catch (IOException e) {
            log.error("Error leyendo {}", FILENAME, e);
        }
    }

    // ── Resolución de URLs de Streamable ──────────────────────────

    private void resolveAllUrls() {
        new Thread(() -> {
            int ok = 0;
            for (MemeVideo v : videos) {
                String url = resolveStreamable(v.streamableId);
                if (url != null) {
                    resolvedUrls.put(v.streamableId, url);
                    ok++;
                }
            }
            log.info("URLs de meme resueltas: {}/{}", ok, videos.size());
        }).start();
    }

    private String resolveStreamable(String id) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.streamable.com/videos/" + id))
                    .header("User-Agent", "SoulShinyBot/1.0").GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Streamable {} → {}", id, res.statusCode());
                return null;
            }
            Pattern p = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\"");
            Matcher m = p.matcher(res.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            log.warn("Error resolviendo Streamable {}: {}", id, e.getMessage());
            return null;
        }
    }

    // ── Utilidades ────────────────────────────────────────────────

    private String field(String json, String key) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record MemeVideo(String name, String streamableId) {}
}