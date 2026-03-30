package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.overlay.OverlayEvent;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;

/**
 * Comando genérico de media para el overlay de OBS.
 *
 * CÓMO AÑADIR UN NUEVO COMANDO:
 * Edita media_commands.json y añade un bloque. No hace falta recompilar.
 *
 * Si quieres añadirlo desde código (Main.java):
 *
 *   registry.register(new MediaCommand.Builder("luffy")
 *       .title("¡Luffy está aquí!")
 *       .chatMessage("@{user} invocó a Luffy! ⚓")
 *       .mediaUrl("https://url-del-gif.gif")
 *       .accentColor("#FFD600")
 *       .collectionType("onepiece")
 *       .itemId("luffy")
 *       .itemName("Monkey D. Luffy")
 *       .build(overlayServer, db));
 */
public class MediaCommand implements Command {

    private final String name;
    private final String title;
    private final String chatMessage;
    private final String mediaUrl;
    private final String soundUrl;
    private final String accentColor;
    private final int durationSeconds;
    private final long cooldownMs;
    private final WebSocketOverlayServer overlayServer;
    private final DatabaseManager db;

    // Campos opcionales para coleccionables
    private final String collectionType;
    private final String itemId;
    private final String itemName;

    private MediaCommand(Builder builder, WebSocketOverlayServer overlayServer, DatabaseManager db) {
        this.name            = builder.name;
        this.title           = builder.title;
        this.chatMessage     = builder.chatMessage;
        this.mediaUrl        = builder.mediaUrl;
        this.soundUrl        = builder.soundUrl;
        this.accentColor     = builder.accentColor;
        this.durationSeconds = builder.durationSeconds;
        this.cooldownMs      = builder.cooldownSeconds * 1000L;
        this.overlayServer   = overlayServer;
        this.db              = db;
        this.collectionType  = builder.collectionType;
        this.itemId          = builder.itemId;
        this.itemName        = builder.itemName;
    }

    @Override
    public String getName() { return name; }

    @Override
    public long getCooldownMs() { return cooldownMs; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();

        // Mensaje en el chat
        if (chatMessage != null && !chatMessage.isEmpty()) {
            client.getChat().sendMessage(channel, chatMessage.replace("{user}", "@" + user));
        }

        // Registrar coleccionable si está configurado
        if (db != null && collectionType != null && itemId != null) {
            boolean isNew = db.registerCollectible(user, collectionType, itemId,
                    itemName != null ? itemName : title);
            if (isNew) {
                long total = db.getCollectionCount(user, collectionType);
                client.getChat().sendMessage(channel,
                        "✨ @" + user + " consiguió un nuevo coleccionable: " +
                                (itemName != null ? itemName : title) +
                                " (" + total + " de " + collectionType + ")");
            }
        }

        // Enviar al overlay
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

    // ── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private final String name;
        private String title          = "";
        private String chatMessage    = "";
        private String mediaUrl;
        private String soundUrl       = "";
        private String accentColor    = "#e53935";
        private int durationSeconds   = 8;
        private int cooldownSeconds   = 15;
        private String collectionType = null;
        private String itemId         = null;
        private String itemName       = null;

        public Builder(String commandName) { this.name = commandName; }

        public Builder title(String v)          { this.title = v;           return this; }
        public Builder chatMessage(String v)    { this.chatMessage = v;     return this; }
        public Builder mediaUrl(String v)       { this.mediaUrl = v;        return this; }
        public Builder soundUrl(String v)       { this.soundUrl = v;        return this; }
        public Builder accentColor(String v)    { this.accentColor = v;     return this; }
        public Builder duration(int v)          { this.durationSeconds = v; return this; }
        public Builder cooldownSeconds(int v)   { this.cooldownSeconds = v; return this; }
        public Builder collectionType(String v) { this.collectionType = v;  return this; }
        public Builder itemId(String v)         { this.itemId = v;          return this; }
        public Builder itemName(String v)       { this.itemName = v;        return this; }

        public MediaCommand build(WebSocketOverlayServer overlayServer, DatabaseManager db) {
            if (name == null || mediaUrl == null)
                throw new IllegalStateException("name y mediaUrl son obligatorios");
            return new MediaCommand(this, overlayServer, db);
        }

        /** Compatibilidad: sin base de datos (coleccionables desactivados) */
        public MediaCommand build(WebSocketOverlayServer overlayServer) {
            return build(overlayServer, null);
        }
    }
}