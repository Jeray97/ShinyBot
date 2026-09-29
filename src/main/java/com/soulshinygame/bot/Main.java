package com.soulshinygame.bot;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.soulshinygame.bot.admin.AdminApiHandler;
import com.soulshinygame.bot.admin.AdminLogHandler;
import com.soulshinygame.bot.admin.AdminServer;
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
import com.soulshinygame.bot.rewards.MemeRewardHandler;
import com.soulshinygame.bot.rewards.RewardAdminService;
import com.soulshinygame.bot.timers.TimerManager;
import com.soulshinygame.bot.util.DailyLimitManager;
import com.soulshinygame.bot.util.FollowerCache;
import com.soulshinygame.bot.util.TokenValidator;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int OVERLAY_WS_PORT   = 8765;
    private static final int OVERLAY_HTTP_PORT = 8766;
    private static final int ADMIN_PORT        = 8767;

    public static void main(String[] args) {
        log.info("Arrancando el bot...");

        Dotenv env = Dotenv.load();
        TokenValidator.validate(env.get("BOT_ACCESS_TOKEN"));
        AdminLogHandler logHandler = new AdminLogHandler();

        DatabaseManager db = new DatabaseManager();
        db.init();

        OverlayHttpServer httpServer = new OverlayHttpServer(OVERLAY_HTTP_PORT);
        httpServer.start();
        WebSocketOverlayServer overlayServer = new WebSocketOverlayServer(OVERLAY_WS_PORT);
        overlayServer.start();

        String botToken = env.get("BOT_ACCESS_TOKEN");
        String clientId = env.get("CLIENT_ID");

        // Cliente Twitch — usar EventSocket en vez de PubSub (cerrado en abril 2025)
        TwitchClient client = TwitchClientBuilder.builder()
                .withClientId(clientId)
                .withClientSecret(env.get("CLIENT_SECRET"))
                .withEnableChat(true)
                .withChatAccount(new OAuth2Credential("twitch", botToken))
                .withEnableHelix(true)
                .withEnableEventSocket(true)   // ← EventSub WebSocket (sustituye a PubSub)
                .build();

        String channel = env.get("CHANNEL_NAME");
        client.getChat().joinChannel(channel);

        String broadcasterId = client.getHelix()
                .getUsers(null, null, List.of(channel))
                .execute().getUsers().get(0).getId();

        FollowerCache followerCache = new FollowerCache(client, broadcasterId);
        DailyLimitManager dailyLimit = new DailyLimitManager();

        // ═══════════════════════════════════════════════════════════════
        // REGISTRO DE COMANDOS
        // ═══════════════════════════════════════════════════════════════

        CommandRegistry registry = new CommandRegistry(client, channel);

        registry
                .register(new HolaCommand())
                .register(new PuntosCommand(db))
                .register(new DadosCommand(db))
                .register(new ColeccionCommand(db))
                .register(new PokedexCommand(overlayServer, db, followerCache, dailyLimit))
                .register(new AnimeCommand(overlayServer, db, followerCache, dailyLimit))
                .register(new PaquitoCommand(overlayServer));

        new MediaCommandsLoader(overlayServer, db).loadInto(registry);
        new BattleSystem(db, overlayServer).registerInto(registry);

        registry.start();

        // ═══════════════════════════════════════════════════════════════
        // RECOMPENSAS DEL CANAL (via EventSub)
        // ═══════════════════════════════════════════════════════════════

        MemeRewardHandler memeRewards = new MemeRewardHandler(client, overlayServer, broadcasterId, botToken, clientId);
        memeRewards.start();

        // ═══════════════════════════════════════════════════════════════
        // TIMERS
        // ═══════════════════════════════════════════════════════════════

        TimerManager timerManager = new TimerManager(client, channel);
        timerManager.start();

        new ModerationHandler(channel, client, broadcasterId).register();

        // ═══════════════════════════════════════════════════════════════
        // PANEL DE ADMINISTRACIÓN
        // ═══════════════════════════════════════════════════════════════

        String adminPassword = env.get("ADMIN_PASSWORD", "");
        AdminApiHandler apiHandler = new AdminApiHandler(
                registry, db, overlayServer, timerManager, logHandler,
                new RewardAdminService(memeRewards), adminPassword);
        AdminServer adminServer = new AdminServer(ADMIN_PORT, apiHandler);
        adminServer.start();

        log.info("==============================================");
        log.info("Bot listo en #{}", channel);
        log.info("Overlay    → http://localhost:{}", OVERLAY_HTTP_PORT);
        log.info("Admin      → http://localhost:{}/admin", ADMIN_PORT);
        log.info("==============================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Apagando...");
            timerManager.stop();
            adminServer.stop();
            httpServer.stop();
            try { overlayServer.stop(); } catch (Exception e) { log.error("Error cerrando WS", e); }
            client.close();
        }));
    }
}