package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.overlay.OverlayEvent;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;

/**
 * Comando genérico de media para el overlay de OBS.
 *
 * Muestra cualquier GIF, imagen o video en el stream cuando alguien
 * escribe el comando en el chat.
 *
 * ──────────────────────────────────────────────────
 * CÓMO AÑADIR UN NUEVO COMANDO DE MEDIA:
 *
 *   En Main.java, añade una línea como esta:
 *
 *   registry.register(new MediaCommand.Builder("naruto")
 *       .title("¡Naruto apareció!")
 *       .chatMessage("@{user} invocó a Naruto! 🍥")
 *       .mediaUrl("https://url-del-gif-naruto.gif")
 *       .accentColor("#FF6B35")
 *       .duration(8)
 *       .cooldownSeconds(20)
 *       .build(overlayServer));
 *
 * ──────────────────────────────────────────────────
 */
public class MediaCommand implements Command {

    private final String name;
    private final String title;
    private final String chatMessage;   // Soporta {user} como placeholder
    private final String mediaUrl;
    private final String soundUrl;
    private final String accentColor;
    private final int durationSeconds;
    private final long cooldownMs;
    private final WebSocketOverlayServer overlayServer;

    private MediaCommand(Builder builder, WebSocketOverlayServer overlayServer) {
        this.name            = builder.name;
        this.title           = builder.title;
        this.chatMessage     = builder.chatMessage;
        this.mediaUrl        = builder.mediaUrl;
        this.soundUrl        = builder.soundUrl;
        this.accentColor     = builder.accentColor;
        this.durationSeconds = builder.durationSeconds;
        this.cooldownMs      = builder.cooldownSeconds * 1000L;
        this.overlayServer   = overlayServer;
    }

    @Override
    public String getName() { return name; }

    @Override
    public long getCooldownMs() { return cooldownMs; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();

        // Mensaje en el chat con placeholder {user}
        if (chatMessage != null && !chatMessage.isEmpty()) {
            client.getChat().sendMessage(channel, chatMessage.replace("{user}", "@" + user));
        }

        // Enviar al overlay de OBS
        OverlayEvent overlayEvent = OverlayEvent.builder()
                .triggeredBy(user)
                .title(title)
                .mediaUrl(mediaUrl)
                .soundUrl(soundUrl)
                .duration(durationSeconds)
                .accentColor(accentColor)
                .build();

        overlayServer.sendEvent(overlayEvent.toJson());
    }

    // --- Builder ---

    public static class Builder {
        private final String name;
        private String title;
        private String chatMessage;
        private String mediaUrl;
        private String soundUrl    = "";
        private String accentColor = "#e53935";
        private int durationSeconds = 8;
        private int cooldownSeconds = 15;

        public Builder(String commandName) { this.name = commandName; }

        public Builder title(String title)               { this.title = title;           return this; }
        public Builder chatMessage(String msg)           { this.chatMessage = msg;       return this; }
        public Builder mediaUrl(String url)              { this.mediaUrl = url;          return this; }
        public Builder soundUrl(String url)              { this.soundUrl = url;          return this; }
        public Builder accentColor(String color)         { this.accentColor = color;     return this; }
        public Builder duration(int seconds)             { this.durationSeconds = seconds; return this; }
        public Builder cooldownSeconds(int seconds)      { this.cooldownSeconds = seconds; return this; }

        public MediaCommand build(WebSocketOverlayServer overlayServer) {
            if (name == null || mediaUrl == null)
                throw new IllegalStateException("name y mediaUrl son obligatorios");
            return new MediaCommand(this, overlayServer);
        }
    }
}
