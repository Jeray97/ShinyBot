package com.soulshinygame.bot.moderation;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;


public class ModerationHandler {

    private static final Logger log = LoggerFactory.getLogger(ModerationHandler.class);
    private final String broadcasterId;

    // Palabras prohibidas (ampliar según necesites)
    private static final List<String> BANNED_WORDS = List.of(
        "spam1", "spam2" // Añade aquí tus palabras prohibidas
    );

    // Control de flood: usuario -> timestamp del último mensaje
    private final Map<String, Long> lastMessageTime = new ConcurrentHashMap<>();
    private static final long FLOOD_THRESHOLD_MS = 1500; // 1.5 segundos entre mensajes

    private final TwitchClient client;
    private final String channel;

    public ModerationHandler(String broadcasterId, TwitchClient client, String channel) {
        this.broadcasterId = broadcasterId;
        this.client = client;
        this.channel = channel;
    }

    public void register() {
        client.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            String user = event.getUser().getName();
            String message = event.getMessage().toLowerCase();

            // No moderar al broadcaster ni a los mods
            if (isMod(event)) return;

            // 1. Filtro de palabras prohibidas
            if (containsBannedWord(message)) {
                timeout(user, 60, "Palabra no permitida en el canal");
                sendMessage("@" + user + " mensaje eliminado. Lee las reglas del canal ⚠️");
                return;
            }

            // 2. Filtro anti-flood
            if (isFlooding(user)) {
                timeout(user, 10, "Flood detectado");
                sendMessage("@" + user + " ¡Mensajes demasiado rápidos! Espera un momento 🐢");
                return;
            }

            // 3. Filtro de links (opcional, puedes desactivarlo)
            if (containsLink(message) && !isTrustedUser(event)) {
                timeout(user, 30, "Links no permitidos sin permiso");
                sendMessage("@" + user + " los links necesitan permiso del mod 🔗");
            }
        });

        log.info("ModerationHandler registrado");
    }

    private boolean containsBannedWord(String message) {
        return BANNED_WORDS.stream().anyMatch(message::contains);
    }

    private boolean isFlooding(String user) {
        long now = System.currentTimeMillis();
        Long last = lastMessageTime.put(user, now);
        return last != null && (now - last) < FLOOD_THRESHOLD_MS;
    }

    private boolean containsLink(String message) {
        return message.contains("http://") ||
               message.contains("https://") ||
               message.matches(".*\\.(com|es|net|org|tv|gg|io).*");
    }

    private boolean isMod(ChannelMessageEvent event) {
        return event.getPermissions().contains(
            com.github.twitch4j.common.enums.CommandPermission.MODERATOR
        ) || event.getPermissions().contains(
            com.github.twitch4j.common.enums.CommandPermission.BROADCASTER
        );
    }

    private boolean isTrustedUser(ChannelMessageEvent event) {
        // VIPs y subs pueden poner links
        return event.getPermissions().contains(
            com.github.twitch4j.common.enums.CommandPermission.SUBSCRIBER
        ) || event.getPermissions().contains(
            com.github.twitch4j.common.enums.CommandPermission.VIP
        );
    }

    private void timeout(String user, int seconds, String reason) {
        // Obtener el ID del usuario a banear
        var userList = client.getHelix().getUsers(null, null, List.of(user)).execute();
        if (userList.getUsers().isEmpty()) return;

        String userId = userList.getUsers().get(0).getId();

        client.getHelix().banUser(
                null,
                broadcasterId,
                broadcasterId, // moderator ID (tú mismo como broadcaster)
                new com.github.twitch4j.helix.domain.BanUserInput()
                        .withUserId(userId)
                        .withDuration(seconds)
                        .withReason(reason)
        ).execute();

        log.info("Timeout a {} por {}s: {}", user, seconds, reason);
    }

    private void sendMessage(String message) {
        client.getChat().sendMessage(channel, message);
    }
}
