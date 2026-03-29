package com.soulshinygame.bot;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.soulshinygame.bot.commands.CommandRegistry;
import com.soulshinygame.bot.commands.impl.*;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.moderation.ModerationHandler;
import com.soulshinygame.bot.overlay.OverlayHttpServer;
import com.soulshinygame.bot.overlay.PokedexCommand;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int OVERLAY_WS_PORT   = 8765; // WebSocket bot → overlay
    private static final int OVERLAY_HTTP_PORT = 8766; // HTTP overlay → OBS

    public static void main(String[] args) {
        log.info("Arrancando el bot...");

        Dotenv env = Dotenv.load();

        // Base de datos
        DatabaseManager db = new DatabaseManager();
        db.init();

        // Servidor HTTP — sirve overlay.html a OBS
        OverlayHttpServer httpServer = new OverlayHttpServer(OVERLAY_HTTP_PORT);
        httpServer.start();

        // Servidor WebSocket — comunica el bot con el overlay
        WebSocketOverlayServer overlayServer = new WebSocketOverlayServer(OVERLAY_WS_PORT);
        overlayServer.start();

        // Cliente de Twitch
        TwitchClient client = TwitchClientBuilder.builder()
                .withClientId(env.get("CLIENT_ID"))
                .withClientSecret(env.get("CLIENT_SECRET"))
                .withEnableChat(true)
                .withChatAccount(new OAuth2Credential("twitch", env.get("BOT_ACCESS_TOKEN")))
                .withEnableHelix(true)
                .build();

        String channel = env.get("CHANNEL_NAME");
        client.getChat().joinChannel(channel);

        String broadcasterId = client.getHelix()
                .getUsers(null, null, List.of(channel))
                .execute().getUsers().get(0).getId();

        // ═══════════════════════════════════════════════════════════════
        // REGISTRO DE COMANDOS
        // ═══════════════════════════════════════════════════════════════

        CommandRegistry registry = new CommandRegistry(client, channel);

        registry
                .register(new HolaCommand())
                .register(new PuntosCommand(db))
                .register(new DadosCommand(db))
                .register(new PokedexCommand(overlayServer))

                // ── COMANDOS DE MEDIA ─────────────────────────────────────
                // Copia un bloque y cambia los valores para añadir más.
                // ──────────────────────────────────────────────────────────

                .register(new MediaCommand.Builder("naruto")
                        .title("¡Naruto apareció!")
                        .chatMessage("@{user} invocó a Naruto! 🍥")
                        .mediaUrl("https://media0.giphy.com/media/v1.Y2lkPTc5MGI3NjExcXlncDdhMnN6ODZtOHFoamFzcTdycHBhYmh6eGI3cWk4b3phNXZiNSZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/w7CP59oLYw6PK/giphy.gif")
                        .accentColor("#FF6B35")
                        .duration(8)
                        .cooldownSeconds(20)
                        .build(overlayServer))

                .register(new MediaCommand.Builder("goku")
                        .title("¡Goku está aquí!")
                        .chatMessage("@{user} llamó a Goku! 🔥")
                        .mediaUrl("https://media.tenor.com/goku-kamehameha.gif")
                        .accentColor("#1565C0")
                        .duration(10)
                        .cooldownSeconds(30)
                        .build(overlayServer));

        registry.start();

        // Moderación
        new ModerationHandler(channel, client, broadcasterId).register();

        log.info("==============================================");
        log.info("Bot listo en #{}", channel);
        log.info("OBS Browser Source → http://localhost:{}", OVERLAY_HTTP_PORT);
        log.info("==============================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Apagando...");
            httpServer.stop();
            try { overlayServer.stop(); } catch (Exception e) { log.error("Error cerrando overlay WS", e); }
            client.close();
        }));
    }
}