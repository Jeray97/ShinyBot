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
    // Si la API falla (scope incorrecto), desactivamos las llamadas para no spamear logs
    private volatile boolean apiAvailable = true;

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

        // Si la API no esta disponible (scope incorrecto), permitir acceso directamente
        if (!apiAvailable) return true;

        // Consultar cache
        CacheEntry entry = cache.get(userId);
        if (entry != null && System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
            return entry.isFollower;
        }

        try {
            boolean follows = client.getHelix()
                    .getChannelFollowers(accessToken, broadcasterId, userId, 1, null)
                    .execute()
                    .getTotal() > 0;

            cache.put(userId, new CacheEntry(follows, System.currentTimeMillis()));
            log.debug("Follower check: userId={} follows={}", userId, follows);
            return follows;

        } catch (Exception e) {
            // Desactivar la API para no repetir el error en cada comando
            apiAvailable = false;
            log.error("=================================================");
            log.error("FollowerCache: la API de seguidores fallo.");
            log.error("El token necesita el scope: moderator:read:followers");
            log.error("Regenera el BOT_ACCESS_TOKEN con ese scope.");
            log.error("Hasta entonces el check de seguidores esta desactivado.");
            log.error("=================================================");
            return true;
        }
    }

    /** Fuerza recarga (por si el usuario acaba de seguir) */
    public void invalidate(String userId) {
        cache.remove(userId);
    }
}