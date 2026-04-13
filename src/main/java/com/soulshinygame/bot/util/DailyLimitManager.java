package com.soulshinygame.bot.util;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lleva la cuenta de cuántas veces ha coleccionado cada usuario hoy.
 * El contador se resetea automáticamente a medianoche.
 *
 * Uso:
 *   dailyLimit.canCollect("pokemon", "usuario", 5)  → true/false
 *   dailyLimit.increment("pokemon", "usuario")
 *   dailyLimit.remaining("pokemon", "usuario", 5)   → cuántos le quedan
 */
public class DailyLimitManager {

    // Clave: "collectionType:username:fecha", valor: número de veces hoy
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    private String key(String collectionType, String username) {
        return collectionType + ":" + username + ":" + LocalDate.now();
    }

    /** Comprueba si el usuario puede coleccionar (no ha llegado al límite diario) */
    public boolean canCollect(String collectionType, String username, int dailyMax) {
        return counts.getOrDefault(key(collectionType, username), 0) < dailyMax;
    }

    /** Registra un uso */
    public void increment(String collectionType, String username) {
        String k = key(collectionType, username);
        counts.merge(k, 1, Integer::sum);
    }

    /** Cuántos intentos le quedan hoy */
    public int remaining(String collectionType, String username, int dailyMax) {
        return Math.max(0, dailyMax - counts.getOrDefault(key(collectionType, username), 0));
    }

    /** Cuántos ha usado hoy */
    public int usedToday(String collectionType, String username) {
        return counts.getOrDefault(key(collectionType, username), 0);
    }
}