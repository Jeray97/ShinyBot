package com.soulshinygame.bot.util;

import com.github.twitch4j.TwitchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifica si un usuario sigue el canal y cachea el resultado 10 minutos
 * para no hacer una llamada a la API de Twitch en cada comando.
 */
public class FollowerCache {

    private static final Logger log = LoggerFactory.getLogger(FollowerCache.class);
    private static final long CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutos

    private record CacheEntry(boolean isFollower, long timestamp) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final TwitchClient client;
    private final String broadcasterId;

    public FollowerCache(TwitchClient client, String broadcasterId) {
        this.client        = client;
        this.broadcasterId = broadcasterId;
    }

    /**
     * Comprueba si el usuario sigue el canal.
     * El resultado se cachea 10 minutos.
     */
    public boolean isFollower(String userId) {
        CacheEntry entry = cache.get(userId);
        if (entry != null && System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
            return entry.isFollower;
        }

        try {
            boolean follows = client.getHelix()
                    .getChannelFollowers(null, broadcasterId, userId, 1, null)
                    .execute()
                    .getTotal() > 0;

            cache.put(userId, new CacheEntry(follows, System.currentTimeMillis()));
            return follows;

        } catch (Exception e) {
            log.warn("Error comprobando seguidor {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /** Fuerza la recarga del estado de un usuario (por si acaba de seguir) */
    public void invalidate(String userId) {
        cache.remove(userId);
    }
}