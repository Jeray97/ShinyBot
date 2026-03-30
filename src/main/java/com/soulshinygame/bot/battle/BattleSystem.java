package com.soulshinygame.bot.battle;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.commands.CommandRegistry;
import com.soulshinygame.bot.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sistema de combate Pokémon por chat.
 *
 * Comandos registrados:
 *   !retar @usuario  → reta a alguien
 *   !aceptar         → acepta el reto más reciente que te han hecho
 *   !atacar          → ataca en tu turno
 *   !huir            → te rindes
 *
 * Los comandos de combate (!atacar, !huir) solo funcionan si el usuario
 * está en una batalla activa. El resto los ignora.
 *
 * Uso en Main.java:
 *   new BattleSystem(db).registerInto(registry);
 */
public class BattleSystem {

    private static final Logger log = LoggerFactory.getLogger(BattleSystem.class);

    // Batallas activas: clave = nombre del jugador (ambos apuntan a la misma batalla)
    private final Map<String, Battle> activeBattles  = new ConcurrentHashMap<>();

    // Retos pendientes: clave = nombre del retado, valor = batalla pendiente
    private final Map<String, Battle> pendingChallenges = new ConcurrentHashMap<>();

    private final DatabaseManager db;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Expirar retos sin respuesta tras 60 segundos
    private static final long CHALLENGE_TIMEOUT_MS = 60_000;

    public BattleSystem(DatabaseManager db) {
        this.db = db;
        startExpirationTimer();
    }

    /** Registra todos los comandos de batalla en el registry */
    public void registerInto(CommandRegistry registry) {
        registry
                .register(retarCommand())
                .register(aceptarCommand())
                .register(atacarCommand())
                .register(huirCommand());

        log.info("BattleSystem registrado: !retar, !aceptar, !atacar, !huir");
    }

    // ── COMANDO !retar ────────────────────────────────────────────

    private Command retarCommand() {
        return new Command() {
            @Override public String getName() { return "retar"; }
            @Override public long getCooldownMs() { return 5_000; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String challenger = event.getUser().getName().toLowerCase();
                String[] parts    = event.getMessage().trim().split("\\s+");

                if (parts.length < 2) {
                    send(client, channel, "@" + challenger + " usa !retar @usuario");
                    return;
                }

                // Quitar @ del nombre si lo tienen
                String challenged = parts[1].replace("@", "").toLowerCase();

                if (challenged.equals(challenger)) {
                    send(client, channel, "@" + challenger + " no puedes retarte a ti mismo 😅");
                    return;
                }

                if (activeBattles.containsKey(challenger)) {
                    send(client, channel, "@" + challenger + " ya estás en un combate! Usa !huir primero");
                    return;
                }

                if (pendingChallenges.containsKey(challenged)) {
                    send(client, channel, "@" + challenged + " ya tiene un reto pendiente");
                    return;
                }

                Battle battle = new Battle(challenger, challenged);
                pendingChallenges.put(challenged, battle);

                send(client, channel,
                        "⚔️ @" + challenger + " reta a @" + challenged +
                                " a un combate Pokémon! Escribe !aceptar en los próximos 60s o el reto expira");
            }
        };
    }

    // ── COMANDO !aceptar ──────────────────────────────────────────

    private Command aceptarCommand() {
        return new Command() {
            @Override public String getName() { return "aceptar"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String challenged = event.getUser().getName().toLowerCase();
                Battle battle     = pendingChallenges.remove(challenged);

                if (battle == null) {
                    send(client, channel, "@" + challenged + " no tienes ningún reto pendiente");
                    return;
                }

                if (activeBattles.containsKey(challenged)) {
                    send(client, channel, "@" + challenged + " ya estás en un combate!");
                    return;
                }

                // Iniciar batalla
                battle.start();
                activeBattles.put(battle.challengerName, battle);
                activeBattles.put(battle.challengedName, battle);

                send(client, channel,
                        "🔴 ¡Comienza el combate! " +
                                "@" + battle.challengerName + " → " + battle.challengerPokemonName +
                                " (" + battle.getMaxHp(battle.challengerName) + "HP) VS " +
                                "@" + battle.challengedName + " → " + battle.challengedPokemonName +
                                " (" + battle.getMaxHp(battle.challengedName) + "HP)");

                send(client, channel,
                        "⚔️ Turno de @" + battle.getCurrentTurnName() +
                                " — usa !atacar");

                log.info("Combate iniciado: {} ({}) vs {} ({})",
                        battle.challengerName, battle.challengerPokemonName,
                        battle.challengedName, battle.challengedPokemonName);
            }
        };
    }

    // ── COMANDO !atacar ───────────────────────────────────────────

    private Command atacarCommand() {
        return new Command() {
            @Override public String getName() { return "atacar"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String player = event.getUser().getName().toLowerCase();
                Battle battle = activeBattles.get(player);

                if (battle == null || battle.state != Battle.State.ACTIVE) {
                    send(client, channel, "@" + player + " no estás en ningún combate");
                    return;
                }

                // Verificar turno
                if (!battle.getCurrentTurnName().equals(player)) {
                    send(client, channel,
                            "@" + player + " ¡espera tu turno! Le toca a @" + battle.getCurrentTurnName());
                    return;
                }

                String attacker      = player;
                String defender      = attacker.equals(battle.challengerName)
                        ? battle.challengedName : battle.challengerName;
                String attackerPkmn  = battle.getPokemonName(attacker);
                String defenderPkmn  = battle.getPokemonName(defender);

                int damage = battle.attack();

                // Mensaje del ataque
                send(client, channel,
                        "💥 " + attackerPkmn + " de @" + attacker +
                                " atacó a " + defenderPkmn + " de @" + defender +
                                " causando " + damage + " de daño!");

                // Mostrar estado de vida
                send(client, channel,
                        "❤️ @" + battle.challengerName + " [" + battle.hpBar(battle.challengerName) + "]  " +
                                "| @" + battle.challengedName + " [" + battle.hpBar(battle.challengedName) + "]");

                // Comprobar si terminó
                if (battle.isOver()) {
                    endBattle(battle, battle.getWinner(), client, channel, false);
                } else {
                    send(client, channel,
                            "⚔️ Turno de @" + battle.getCurrentTurnName() + " — usa !atacar");
                }
            }
        };
    }

    // ── COMANDO !huir ─────────────────────────────────────────────

    private Command huirCommand() {
        return new Command() {
            @Override public String getName() { return "huir"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String player = event.getUser().getName().toLowerCase();
                Battle battle = activeBattles.get(player);

                if (battle == null) {
                    send(client, channel, "@" + player + " no estás en ningún combate");
                    return;
                }

                String winner = player.equals(battle.challengerName)
                        ? battle.challengedName : battle.challengerName;

                endBattle(battle, winner, client, channel, true);
            }
        };
    }

    // ── FIN DE BATALLA ────────────────────────────────────────────

    private void endBattle(Battle battle, String winnerName,
                           TwitchClient client, String channel, boolean fled) {
        battle.state = Battle.State.FINISHED;
        activeBattles.remove(battle.challengerName);
        activeBattles.remove(battle.challengedName);

        String loserName = winnerName.equals(battle.challengerName)
                ? battle.challengedName : battle.challengerName;

        if (fled) {
            send(client, channel,
                    "🏳️ @" + loserName + " huyó del combate! @" + winnerName + " ¡gana por abandono! 🏆");
        } else {
            send(client, channel,
                    "🏆 ¡" + battle.getPokemonName(winnerName) + " de @" + winnerName +
                            " venció a " + battle.getPokemonName(loserName) + " de @" + loserName + "!");
            send(client, channel,
                    "🎉 @" + winnerName + " gana el combate! ¡Felicidades!");
        }

        // Dar puntos al ganador
        db.addPoints(winnerName, 50);
        send(client, channel, "⭐ @" + winnerName + " gana 50 puntos por la victoria!");

        log.info("Combate terminado: ganador={}", winnerName);
    }

    // ── EXPIRACIÓN DE RETOS ───────────────────────────────────────

    private void startExpirationTimer() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            pendingChallenges.entrySet().removeIf(entry -> {
                boolean expired = now - entry.getValue().createdAt > CHALLENGE_TIMEOUT_MS;
                if (expired) log.debug("Reto expirado para {}", entry.getKey());
                return expired;
            });
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void send(TwitchClient client, String channel, String msg) {
        client.getChat().sendMessage(channel, msg);
    }
}