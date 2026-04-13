package com.soulshinygame.bot.util;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifica si un usuario sigue el canal y cachea el resultado 10 minutos.
 *
 * Casos especiales que siempre pasan:
 *  - El broadcaster (nunca aparece como seguidor de su propio canal en la API)
 *  - Los moderadores (si quisieras añadirlos en el futuro)
 */
public class FollowerCache {

    private static final Logger log = LoggerFactory.getLogger(FollowerCache.class);
    private static final long CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutos

    private record CacheEntry(boolean isFollower, long timestamp) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final TwitchClient client;
    private final String broadcasterId;
    private final String accessToken;

    public FollowerCache(TwitchClient client, String broadcasterId) {
        this.client        = client;
        this.broadcasterId = broadcasterId;
        // Leer el token del broadcaster desde .env para autenticar la llamada
        this.accessToken   = Dotenv.load().get("BOT_ACCESS_TOKEN", "")
                .replace("oauth:", "");
    }

    public boolean isFollower(String userId) {
        // El broadcaster siempre tiene acceso — la API nunca lo devuelve como seguidor
        if (userId.equals(broadcasterId)) return true;

        // Consultar caché
        CacheEntry entry = cache.get(userId);
        if (entry != null && System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
            return entry.isFollower;
        }

        try {
            // La llamada necesita el token del broadcaster (primer parámetro)
            boolean follows = client.getHelix()
                    .getChannelFollowers(accessToken, broadcasterId, userId, 1, null)
                    .execute()
                    .getTotal() > 0;

            cache.put(userId, new CacheEntry(follows, System.currentTimeMillis()));
            log.debug("Follower check: userId={} follows={}", userId, follows);
            return follows;

        } catch (Exception e) {
            log.warn("Error comprobando seguidor {}: {}", userId, e.getMessage());
            // En caso de error de API, permitir el acceso para no bloquear injustamente
            return true;
        }
    }

    /** Fuerza recarga (por si el usuario acaba de seguir) */
    public void invalidate(String userId) {
        cache.remove(userId);
    }
}