package com.soulshinygame.bot.battle;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.commands.CommandRegistry;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BattleSystem {

    private static final Logger log = LoggerFactory.getLogger(BattleSystem.class);
    private static final String ASSETS_FILE = "battle_assets.json";
    private static final long CHALLENGE_TIMEOUT_MS = 60_000;

    private final Map<String, Battle> activeBattles     = new ConcurrentHashMap<>();
    private final Map<String, Battle> pendingChallenges = new ConcurrentHashMap<>();

    private final DatabaseManager db;
    private final WebSocketOverlayServer overlayServer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private String attackGifUrl  = "";
    private String victoryGifUrl = "";
    private String startGifUrl   = "";

    public BattleSystem(DatabaseManager db, WebSocketOverlayServer overlayServer) {
        this.db            = db;
        this.overlayServer = overlayServer;
        loadAssets();
        startExpirationTimer();
    }

    private void loadAssets() {
        Path path = Path.of(ASSETS_FILE);
        if (!Files.exists(path)) {
            log.warn("{} no encontrado. El overlay de batalla no tendrá GIFs.", ASSETS_FILE);
            return;
        }
        try {
            String json  = Files.readString(path);
            attackGifUrl  = field(json, "attackGif");
            victoryGifUrl = field(json, "victoryGif");
            startGifUrl   = field(json, "startGif");
            log.info("Battle assets cargados desde {}", ASSETS_FILE);
        } catch (IOException e) {
            log.error("Error leyendo {}", ASSETS_FILE, e);
        }
    }

    public void registerInto(CommandRegistry registry) {
        registry
                .register(retarCommand())
                .register(aceptarCommand())
                .register(atacarCommand())
                .register(huirCommand());
        log.info("BattleSystem registrado: !retar, !aceptar, !atacar, !huir");
    }

    // ── !retar ────────────────────────────────────────────────────

    private Command retarCommand() {
        return new Command() {
            @Override public String getName() { return "retar"; }
            @Override public long getCooldownMs() { return 5_000; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String challenger = event.getUser().getName().toLowerCase();
                String[] parts    = event.getMessage().trim().split("\\s+");

                if (parts.length < 2) { send(client, channel, "@" + challenger + " usa !retar @usuario"); return; }

                String challenged = parts[1].replace("@", "").toLowerCase();

                if (challenged.equals(challenger)) { send(client, channel, "@" + challenger + " no puedes retarte a ti mismo 😅"); return; }
                if (activeBattles.containsKey(challenger)) { send(client, channel, "@" + challenger + " ya estás en combate! Usa !huir primero"); return; }
                if (pendingChallenges.containsKey(challenged)) { send(client, channel, "@" + challenged + " ya tiene un reto pendiente"); return; }

                pendingChallenges.put(challenged, new Battle(challenger, challenged));
                send(client, channel, "⚔️ @" + challenger + " reta a @" + challenged + " a un combate Pokémon! Escribe !aceptar en los próximos 60s");
            }
        };
    }

    // ── !aceptar ──────────────────────────────────────────────────

    private Command aceptarCommand() {
        return new Command() {
            @Override public String getName() { return "aceptar"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String challenged = event.getUser().getName().toLowerCase();
                Battle battle     = pendingChallenges.remove(challenged);

                if (battle == null) { send(client, channel, "@" + challenged + " no tienes ningún reto pendiente"); return; }
                if (activeBattles.containsKey(challenged)) { send(client, channel, "@" + challenged + " ya estás en combate!"); return; }

                battle.start();
                activeBattles.put(battle.challengerName, battle);
                activeBattles.put(battle.challengedName, battle);

                send(client, channel,
                        "🔴 ¡Comienza el combate! @" + battle.challengerName + " → " + battle.challengerPokemonName +
                                " (" + battle.getMaxHp(battle.challengerName) + "HP) VS @" +
                                battle.challengedName + " → " + battle.challengedPokemonName +
                                " (" + battle.getMaxHp(battle.challengedName) + "HP)");
                send(client, channel, "⚔️ Turno de @" + battle.getCurrentTurnName() + " — usa !atacar");

                overlayServer.sendEvent(buildStartEvent(battle));

                log.info("Combate: {} ({}) vs {} ({})",
                        battle.challengerName, battle.challengerPokemonName,
                        battle.challengedName, battle.challengedPokemonName);
            }
        };
    }

    // ── !atacar ───────────────────────────────────────────────────

    private Command atacarCommand() {
        return new Command() {
            @Override public String getName() { return "atacar"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String player = event.getUser().getName().toLowerCase();
                Battle battle = activeBattles.get(player);

                if (battle == null || battle.state != Battle.State.ACTIVE) { send(client, channel, "@" + player + " no estás en ningún combate"); return; }
                if (!battle.getCurrentTurnName().equals(player)) { send(client, channel, "@" + player + " ¡espera tu turno! Le toca a @" + battle.getCurrentTurnName()); return; }

                String attacker     = player;
                String defender     = attacker.equals(battle.challengerName) ? battle.challengedName : battle.challengerName;

                int damage = battle.attack();

                send(client, channel,
                        "💥 " + battle.getPokemonName(attacker) + " de @" + attacker +
                                " atacó a " + battle.getPokemonName(defender) + " de @" + defender +
                                " causando " + damage + " de daño!");
                send(client, channel,
                        "❤️ @" + battle.challengerName + " [" + battle.hpBar(battle.challengerName) + "] " +
                                "| @" + battle.challengedName + " [" + battle.hpBar(battle.challengedName) + "]");

                overlayServer.sendEvent(buildAttackEvent(battle, attacker, defender, damage));

                if (battle.isOver()) {
                    endBattle(battle, battle.getWinner(), client, channel, false);
                } else {
                    send(client, channel, "⚔️ Turno de @" + battle.getCurrentTurnName() + " — usa !atacar");
                }
            }
        };
    }

    // ── !huir ─────────────────────────────────────────────────────

    private Command huirCommand() {
        return new Command() {
            @Override public String getName() { return "huir"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String player = event.getUser().getName().toLowerCase();
                Battle battle = activeBattles.get(player);

                if (battle == null) { send(client, channel, "@" + player + " no estás en ningún combate"); return; }

                String winner = player.equals(battle.challengerName) ? battle.challengedName : battle.challengerName;
                endBattle(battle, winner, client, channel, true);
            }
        };
    }

    // ── FIN ───────────────────────────────────────────────────────

    private void endBattle(Battle battle, String winnerName, TwitchClient client, String channel, boolean fled) {
        battle.state = Battle.State.FINISHED;
        activeBattles.remove(battle.challengerName);
        activeBattles.remove(battle.challengedName);

        String loserName = winnerName.equals(battle.challengerName) ? battle.challengedName : battle.challengerName;

        if (fled) {
            send(client, channel, "🏳️ @" + loserName + " huyó! @" + winnerName + " gana por abandono! 🏆");
        } else {
            send(client, channel, "🏆 ¡" + battle.getPokemonName(winnerName) + " de @" + winnerName + " venció a " + battle.getPokemonName(loserName) + " de @" + loserName + "!");
            send(client, channel, "🎉 @" + winnerName + " gana el combate!");
        }

        db.addPoints(winnerName, 50);
        send(client, channel, "⭐ @" + winnerName + " gana 50 puntos!");

        overlayServer.sendEvent(buildEndEvent(battle, winnerName, fled));

        log.info("Combate terminado: ganador={}", winnerName);
    }

    // ── JSON BUILDERS ─────────────────────────────────────────────

    private String buildStartEvent(Battle b) {
        return String.format("""
            {"type":"battle_start","startGifUrl":"%s",
             "player1":{"name":"%s","pokemon":"%s","hp":%d,"maxHp":%d,"spriteUrl":"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/%d.png"},
             "player2":{"name":"%s","pokemon":"%s","hp":%d,"maxHp":%d,"spriteUrl":"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/%d.png"}}""",
                startGifUrl,
                b.challengerName, b.challengerPokemonName, b.challengerPokemon[1], b.challengerPokemon[2], b.challengerPokemon[0],
                b.challengedName,  b.challengedPokemonName, b.challengedPokemon[1], b.challengedPokemon[2], b.challengedPokemon[0]);
    }

    private String buildAttackEvent(Battle b, String attacker, String defender, int damage) {
        return String.format("""
            {"type":"battle_attack","attacker":"%s","defender":"%s","damage":%d,"attackGifUrl":"%s",
             "player1":{"name":"%s","hp":%d,"maxHp":%d},
             "player2":{"name":"%s","hp":%d,"maxHp":%d}}""",
                attacker, defender, damage, attackGifUrl,
                b.challengerName, b.challengerPokemon[1], b.challengerPokemon[2],
                b.challengedName,  b.challengedPokemon[1], b.challengedPokemon[2]);
    }

    private String buildEndEvent(Battle b, String winner, boolean fled) {
        String loser = winner.equals(b.challengerName) ? b.challengedName : b.challengerName;
        return String.format(
                "{\"type\":\"battle_end\",\"winner\":\"%s\",\"loser\":\"%s\",\"fled\":%b,\"victoryGifUrl\":\"%s\"}",
                winner, loser, fled, victoryGifUrl);
    }

    private void startExpirationTimer() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            pendingChallenges.entrySet().removeIf(e -> now - e.getValue().createdAt > CHALLENGE_TIMEOUT_MS);
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void send(TwitchClient client, String channel, String msg) {
        client.getChat().sendMessage(channel, msg);
    }

    private String field(String json, String key) {
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }
}