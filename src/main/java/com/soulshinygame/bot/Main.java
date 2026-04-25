package com.soulshinygame.bot;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.soulshinygame.bot.battle.BattleSystem;
import com.soulshinygame.bot.commands.CommandRegistry;
import com.soulshinygame.bot.commands.MediaCommandsLoader;
import com.soulshinygame.bot.commands.impl.*;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.moderation.ModerationHandler;
import com.soulshinygame.bot.overlay.AnimeCommand;
import com.soulshinygame.bot.overlay.OverlayHttpServer;
import com.soulshinygame.bot.overlay.PokedexCommand;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import com.soulshinygame.bot.timers.TimerManager;
import com.soulshinygame.bot.util.DailyLimitManager;
import com.soulshinygame.bot.util.FollowerCache;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int OVERLAY_WS_PORT   = 8765;
    private static final int OVERLAY_HTTP_PORT = 8766;

    public static void main(String[] args) {
        log.info("Arrancando el bot...");

        Dotenv env = Dotenv.load();

        // Base de datos
        DatabaseManager db = new DatabaseManager();
        db.init();

        // Servidores overlay
        OverlayHttpServer httpServer = new OverlayHttpServer(OVERLAY_HTTP_PORT);
        httpServer.start();

        WebSocketOverlayServer overlayServer = new WebSocketOverlayServer(OVERLAY_WS_PORT);
        overlayServer.start();

        // Cliente Twitch
        TwitchClient client = TwitchClientBuilder.builder()
                .withClientId(env.get("CLIENT_ID"))
                .withClientSecret(env.get("CLIENT_SECRET"))
                .withEnableChat(true)
                .withChatAccount(new OAuth2Credential("twitch", env.get("BOT_ACCESS_TOKEN")))
                .withEnableHelix(true)
                .build();

        String channel     = env.get("CHANNEL_NAME");
        String accessToken = env.get("BOT_ACCESS_TOKEN", "").replace("oauth:", "");
        client.getChat().joinChannel(channel);

        String broadcasterId;
        try {
            broadcasterId = client.getHelix()
                    .getUsers(accessToken, null, List.of(channel))
                    .execute().getUsers().get(0).getId();
            log.info("Broadcaster ID de #{}: {}", channel, broadcasterId);
        } catch (Exception e) {
            log.error("No se pudo obtener el broadcaster ID de '{}'. " +
                      "Revisa CLIENT_ID, CLIENT_SECRET y BOT_ACCESS_TOKEN en el .env", channel, e);
            client.close();
            return;
        }

        // Utilidades compartidas
        FollowerCache followerCache = new FollowerCache(client, broadcasterId);
        DailyLimitManager dailyLimit = new DailyLimitManager();

        // ═══════════════════════════════════════════════════════════════
        // REGISTRO DE COMANDOS
        // ═══════════════════════════════════════════════════════════════

        CommandRegistry registry = new CommandRegistry(client, channel, followerCache);

        registry
                .register(new HolaCommand())
                .register(new PuntosCommand(db))
                .register(new DadosCommand(db))
                .register(new ColeccionCommand(db))
                .register(new PokedexCommand(overlayServer, db, followerCache, dailyLimit))
                .register(new AnimeCommand(overlayServer, db, followerCache, dailyLimit))
                .register(new PaquitoCommand(overlayServer));

        // Comandos de media desde media_commands.json (sin recompilar)
        new MediaCommandsLoader(overlayServer, db).loadInto(registry);

        // Sistema de combate Pokémon
        new BattleSystem(db, overlayServer).registerInto(registry);

        registry.start();

        // ═══════════════════════════════════════════════════════════════
        // TIMERS
        // ═══════════════════════════════════════════════════════════════

        TimerManager timerManager = new TimerManager(client, channel);
        timerManager.start();

        new ModerationHandler(channel, client, broadcasterId).register();

        log.info("==============================================");
        log.info("Bot listo en #{}", channel);
        log.info("OBS Browser Source → http://localhost:{}", OVERLAY_HTTP_PORT);
        log.info("==============================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Apagando...");
            timerManager.stop();
            httpServer.stop();
            try { overlayServer.stop(); } catch (Exception e) { log.error("Error cerrando WS", e); }
            client.close();
        }));
    }
}