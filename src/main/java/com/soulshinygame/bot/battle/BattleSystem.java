package com.soulshinygame.bot.battle;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.commands.CommandRegistry;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.database.UserCollectible;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BattleSystem {

    private static final Logger log = LoggerFactory.getLogger(BattleSystem.class);
    private static final String ASSETS_FILE    = "battle_assets.json";
    private static final long   PICK_TIMEOUT   = 120_000; // 2 min para elegir pokemon
    private static final long   ACCEPT_TIMEOUT =  60_000; // 1 min para aceptar reto

    // ── ESTADOS ───────────────────────────────────────────────────
    // challengerPicking  : key=challengerName  — challenger debe !elegir su pokemon
    // pendingAccept      : key=challengedName  — challenger eligio, challenged debe !aceptar
    // challengedPicking  : key=challengedName  — challenged acepto, debe !elegir su pokemon
    // activeBattles      : key=playerName (ambos) — combate activo
    private final Map<String, Battle> challengerPicking = new ConcurrentHashMap<>();
    private final Map<String, Battle> pendingAccept     = new ConcurrentHashMap<>();
    private final Map<String, Battle> challengedPicking = new ConcurrentHashMap<>();
    private final Map<String, Battle> activeBattles     = new ConcurrentHashMap<>();

    private final DatabaseManager       db;
    private final WebSocketOverlayServer overlayServer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private final Map<Integer, BattleStats> statCache = new ConcurrentHashMap<>();

    private String attackGifUrl  = "";
    private String victoryGifUrl = "";
    private String startGifUrl   = "";

    private record BattleStats(int hp, int atk, int def, String type1, String type2) {}

    // ── CONSTRUCTOR ───────────────────────────────────────────────

    public BattleSystem(DatabaseManager db, WebSocketOverlayServer overlayServer) {
        this.db            = db;
        this.overlayServer = overlayServer;
        loadAssets();
        startExpirationTimer();
    }

    private void loadAssets() {
        Path path = Path.of(ASSETS_FILE);
        if (!Files.exists(path)) {
            log.warn("{} no encontrado. El overlay de batalla no tendra GIFs.", ASSETS_FILE);
            return;
        }
        try {
            String json   = Files.readString(path);
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
                .register(elegirCommand())
                .register(aceptarCommand())
                .register(atacarCommand())
                .register(huirCommand());
        log.info("BattleSystem registrado: !retar, !elegir, !aceptar, !atacar, !huir");
    }

    // ── !retar ────────────────────────────────────────────────────

    private Command retarCommand() {
        return new Command() {
            @Override public String getName()        { return "retar"; }
            @Override public long   getCooldownMs()  { return 5_000; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String challenger = event.getUser().getName().toLowerCase();
                String[] parts    = event.getMessage().trim().split("\\s+");

                if (parts.length < 2) {
                    send(client, channel, "@" + challenger + " usa !retar @usuario"); return;
                }
                String challenged = parts[1].replace("@", "").toLowerCase();

                if (challenged.equals(challenger))  { send(client, channel, "@" + challenger + " no puedes retarte a ti mismo!"); return; }
                if (isPlayerBusy(challenger))        { send(client, channel, "@" + challenger + " ya estas en combate o proceso de seleccion"); return; }
                if (isPlayerBusy(challenged))        { send(client, channel, "@" + challenged + " ya tiene un combate o reto pendiente"); return; }

                List<UserCollectible> pokemonList = db.getCollectibles(challenger, "pokemon");
                if (pokemonList.isEmpty()) {
                    send(client, channel, "@" + challenger + " no tienes ningun Pokemon! Usa !pokedex primero"); return;
                }

                Battle battle = new Battle(challenger, challenged);
                challengerPicking.put(challenger, battle);

                send(client, channel, "Combate Pokemon! @" + challenger + " reta a @" + challenged
                        + " — @" + challenger + " elige tu Pokemon con !elegir <nombre>:");
                sendPokemonList(client, channel, challenger, pokemonList);
            }
        };
    }

    // ── !elegir ───────────────────────────────────────────────────

    private Command elegirCommand() {
        return new Command() {
            @Override public String getName() { return "elegir"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String   player = event.getUser().getName().toLowerCase();
                String[] parts  = event.getMessage().trim().split("\\s+", 2);

                if (parts.length < 2 || parts[1].isBlank()) {
                    send(client, channel, "@" + player + " usa !elegir <nombre del Pokemon>"); return;
                }
                String pokemonName = parts[1].trim();

                if (challengerPicking.containsKey(player)) {
                    handleChallengerPick(player, pokemonName, client, channel);
                } else if (challengedPicking.containsKey(player)) {
                    handleChallengedPick(player, pokemonName, client, channel);
                } else {
                    send(client, channel, "@" + player + " no tienes ningun reto pendiente de seleccion");
                }
            }
        };
    }

    private void handleChallengerPick(String challenger, String pokemonName,
                                      TwitchClient client, String channel) {
        Battle battle = challengerPicking.get(challenger);
        List<UserCollectible> list = db.getCollectibles(challenger, "pokemon");
        UserCollectible pick = findByName(list, pokemonName);

        if (pick == null) {
            send(client, channel, "@" + challenger + " no tienes a \"" + pokemonName
                    + "\" en tu Pokedex. Escribe el nombre exacto"); return;
        }

        int pokemonId = parseId(pick.getItemId());
        BattleStats stats = fetchStats(pokemonId);
        if (stats == null) {
            send(client, channel, "@" + challenger + " error al obtener datos de "
                    + pick.getItemName() + ". Intenta de nuevo"); return;
        }

        battle.setChallenger(pokemonId, pick.getItemName(),
                stats.hp(), stats.atk(), stats.def(), stats.type1(), stats.type2());
        challengerPicking.remove(challenger);
        pendingAccept.put(battle.challengedName, battle);

        String abilityInfo = battle.getAbility(challenger).displayName
                + ": " + battle.getAbility(challenger).description;
        send(client, channel, "@" + challenger + " eligio a " + pick.getItemName()
                + " [" + typeStr(stats.type1(), stats.type2()) + "] | Habilidad: " + abilityInfo
                + " — @" + battle.challengedName + " escribe !aceptar en los proximos 60s!");
    }

    private void handleChallengedPick(String challenged, String pokemonName,
                                      TwitchClient client, String channel) {
        Battle battle = challengedPicking.get(challenged);
        List<UserCollectible> list = db.getCollectibles(challenged, "pokemon");
        UserCollectible pick = findByName(list, pokemonName);

        if (pick == null) {
            send(client, channel, "@" + challenged + " no tienes a \"" + pokemonName
                    + "\" en tu Pokedex. Escribe el nombre exacto"); return;
        }

        int pokemonId = parseId(pick.getItemId());
        BattleStats stats = fetchStats(pokemonId);
        if (stats == null) {
            send(client, channel, "@" + challenged + " error al obtener datos de "
                    + pick.getItemName() + ". Intenta de nuevo"); return;
        }

        battle.setChallenged(pokemonId, pick.getItemName(),
                stats.hp(), stats.atk(), stats.def(), stats.type1(), stats.type2());
        challengedPicking.remove(challenged);
        activeBattles.put(battle.challengerName, battle);
        activeBattles.put(battle.challengedName, battle);
        battle.start();

        String abilityInfo = battle.getAbility(challenged).displayName
                + ": " + battle.getAbility(challenged).description;
        send(client, channel, "@" + challenged + " eligio a " + pick.getItemName()
                + " [" + typeStr(stats.type1(), stats.type2()) + "] | Habilidad: " + abilityInfo);
        send(client, channel,
                "Comienza el combate! @" + battle.challengerName
                + " (" + battle.challengerPokemonName + ") VS @"
                + battle.challengedName + " (" + battle.challengedPokemonName + ")");
        send(client, channel, "Turno de @" + battle.getCurrentTurnName() + " — usa !atacar");

        overlayServer.sendEvent(buildStartEvent(battle));
        log.info("Combate: {} ({}) vs {} ({})",
                battle.challengerName, battle.challengerPokemonName,
                battle.challengedName, battle.challengedPokemonName);
    }

    // ── !aceptar ──────────────────────────────────────────────────

    private Command aceptarCommand() {
        return new Command() {
            @Override public String getName() { return "aceptar"; }

            @Override
            public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
                String challenged = event.getUser().getName().toLowerCase();
                Battle battle     = pendingAccept.remove(challenged);

                if (battle == null)               { send(client, channel, "@" + challenged + " no tienes ningun reto pendiente"); return; }
                if (activeBattles.containsKey(challenged)) { send(client, channel, "@" + challenged + " ya estas en combate!"); return; }

                List<UserCollectible> pokemonList = db.getCollectibles(challenged, "pokemon");
                if (pokemonList.isEmpty()) {
                    send(client, channel, "@" + challenged + " no tienes ningun Pokemon! Reto cancelado. Usa !pokedex"); return;
                }

                challengedPicking.put(challenged, battle);
                send(client, channel, "@" + challenged + " acepto el reto! Elige tu Pokemon con !elegir <nombre>:");
                sendPokemonList(client, channel, challenged, pokemonList);
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

                if (battle == null || battle.state != Battle.State.ACTIVE) {
                    send(client, channel, "@" + player + " no estas en ningun combate"); return;
                }
                if (!battle.getCurrentTurnName().equals(player)) {
                    send(client, channel, "@" + player + " espera tu turno! Le toca a @" + battle.getCurrentTurnName()); return;
                }

                String defender = player.equals(battle.challengerName)
                        ? battle.challengedName : battle.challengerName;

                Battle.AttackResult result = battle.attack();

                String typeMsg = "";
                if      (result.typeMult() == 0.0)  typeMsg = " No afecta...";
                else if (result.typeMult() <= 0.5)  typeMsg = " No es muy efectivo...";
                else if (result.typeMult() >= 2.0)  typeMsg = " Es muy efectivo!";

                String critMsg = result.isCrit()      ? " Golpe critico!"                       : "";
                String healMsg = result.healedHp() > 0 ? " (+" + result.healedHp() + " HP reg.)" : "";

                send(client, channel,
                        battle.getPokemonName(player) + " de @" + player
                        + " ataco a " + battle.getPokemonName(defender) + " de @" + defender
                        + " causando " + result.damage() + " de dano!" + typeMsg + critMsg + healMsg);
                send(client, channel,
                        "@" + battle.challengerName + " [" + battle.hpBar(battle.challengerName) + "] "
                        + "| @" + battle.challengedName + " [" + battle.hpBar(battle.challengedName) + "]");

                overlayServer.sendEvent(buildAttackEvent(battle, player, defender, result));

                if (battle.isOver()) {
                    endBattle(battle, battle.getWinner(), client, channel, false);
                } else {
                    send(client, channel, "Turno de @" + battle.getCurrentTurnName() + " — usa !atacar");
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

                // Cancelar reto antes de que empiece
                if (challengerPicking.containsKey(player)) {
                    challengerPicking.remove(player);
                    send(client, channel, "@" + player + " cancelo el reto"); return;
                }
                // Cancelar si estaba esperando que el challenged elija
                Battle fromAccept = pendingAcceptByChallenger(player);
                if (fromAccept != null) {
                    pendingAccept.remove(fromAccept.challengedName);
                    send(client, channel, "@" + player + " cancelo el reto"); return;
                }

                Battle battle = activeBattles.get(player);
                if (battle == null) { send(client, channel, "@" + player + " no estas en ningun combate"); return; }

                String winner = player.equals(battle.challengerName) ? battle.challengedName : battle.challengerName;
                endBattle(battle, winner, client, channel, true);
            }
        };
    }

    // ── FIN ───────────────────────────────────────────────────────

    private void endBattle(Battle battle, String winnerName,
                           TwitchClient client, String channel, boolean fled) {
        battle.state = Battle.State.FINISHED;
        activeBattles.remove(battle.challengerName);
        activeBattles.remove(battle.challengedName);

        String loserName = winnerName.equals(battle.challengerName)
                ? battle.challengedName : battle.challengerName;

        if (fled) {
            send(client, channel, "@" + loserName + " huyo! @" + winnerName + " gana por abandono!");
        } else {
            send(client, channel, battle.getPokemonName(winnerName) + " de @" + winnerName
                    + " vencio a " + battle.getPokemonName(loserName) + " de @" + loserName + "!");
            send(client, channel, "@" + winnerName + " gana el combate!");
        }

        db.addPoints(winnerName, 50);
        send(client, channel, "@" + winnerName + " gana 50 puntos!");
        overlayServer.sendEvent(buildEndEvent(battle, winnerName, fled));
        log.info("Combate terminado: ganador={}", winnerName);
    }

    // ── POKEAPI ───────────────────────────────────────────────────

    private BattleStats fetchStats(int pokemonId) {
        if (statCache.containsKey(pokemonId)) return statCache.get(pokemonId);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://pokeapi.co/api/v2/pokemon/" + pokemonId))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            String body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();

            int hp  = statValue(body, "hp");
            int atk = statValue(body, "attack");
            int def = statValue(body, "defense");
            String[] types = parseTypes(body);

            BattleStats stats = new BattleStats(hp, atk, def, types[0], types[1]);
            statCache.put(pokemonId, stats);
            return stats;
        } catch (Exception e) {
            log.error("Error obteniendo stats de PokeAPI para id={}", pokemonId, e);
            return null;
        }
    }

    /** Extrae el base_stat de un stat concreto del JSON de PokeAPI */
    private int statValue(String json, String statName) {
        Pattern p = Pattern.compile(
                "\"base_stat\"\\s*:\\s*(\\d+)[^}]*\"name\"\\s*:\\s*\"" + Pattern.quote(statName) + "\"");
        Matcher m = p.matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        // Fallback: buscar en orden inverso (PokeAPI a veces pone name antes de base_stat)
        Pattern p2 = Pattern.compile(
                "\"name\"\\s*:\\s*\"" + Pattern.quote(statName) + "\"[^{]*\"base_stat\"\\s*:\\s*(\\d+)");
        Matcher m2 = p2.matcher(json);
        return m2.find() ? Integer.parseInt(m2.group(1)) : 50;
    }

    /** Extrae los tipos (slot 1 y slot 2) del JSON de PokeAPI */
    private String[] parseTypes(String json) {
        Pattern p = Pattern.compile("\"slot\"\\s*:\\s*(\\d+).*?\"name\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.DOTALL);
        Matcher m = p.matcher(json);
        String type1 = "normal", type2 = null;
        while (m.find()) {
            int slot = Integer.parseInt(m.group(1));
            if (slot == 1) type1 = m.group(2);
            if (slot == 2) type2 = m.group(2);
        }
        return new String[]{type1, type2};
    }

    // ── HELPERS ───────────────────────────────────────────────────

    private boolean isPlayerBusy(String player) {
        if (challengerPicking.containsKey(player)) return true;
        if (challengedPicking.containsKey(player)) return true;
        if (activeBattles.containsKey(player))     return true;
        // Check challenger waiting in pendingAccept values
        return pendingAcceptByChallenger(player) != null
            || pendingAccept.containsKey(player); // challenged side
    }

    private Battle pendingAcceptByChallenger(String challengerName) {
        for (Battle b : pendingAccept.values()) {
            if (b.challengerName.equals(challengerName)) return b;
        }
        return null;
    }

    private UserCollectible findByName(List<UserCollectible> list, String name) {
        String lower = name.toLowerCase();
        for (UserCollectible c : list) {
            if (c.getItemName().toLowerCase().equals(lower)) return c;
        }
        // Partial match fallback
        for (UserCollectible c : list) {
            if (c.getItemName().toLowerCase().contains(lower)) return c;
        }
        return null;
    }

    private void sendPokemonList(TwitchClient client, String channel,
                                 String user, List<UserCollectible> list) {
        int perMsg = 10;
        List<String> formatted = list.stream()
                .map(c -> "#" + String.format("%03d", parseId(c.getItemId())) + " " + c.getItemName())
                .collect(Collectors.toList());
        for (int i = 0; i < formatted.size(); i += perMsg) {
            List<String> batch = formatted.subList(i, Math.min(i + perMsg, formatted.size()));
            send(client, channel, "@" + user + ": " + String.join(" | ", batch));
        }
    }

    private String typeStr(String t1, String t2) {
        return (t2 != null && !t2.isBlank()) ? t1 + "/" + t2 : t1;
    }

    private int parseId(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ── EXPIRACION ────────────────────────────────────────────────

    private void startExpirationTimer() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            challengerPicking.entrySet().removeIf(e -> now - e.getValue().createdAt > PICK_TIMEOUT);
            pendingAccept.entrySet().removeIf(e -> now - e.getValue().createdAt > ACCEPT_TIMEOUT);
            challengedPicking.entrySet().removeIf(e -> now - e.getValue().createdAt > PICK_TIMEOUT);
        }, 10, 10, TimeUnit.SECONDS);
    }

    // ── JSON BUILDERS ─────────────────────────────────────────────

    private String buildStartEvent(Battle b) {
        return String.format(
            "{\"type\":\"battle_start\",\"startGifUrl\":\"%s\",\"currentTurn\":\"%s\"," +
            "\"player1\":{\"name\":\"%s\",\"pokemon\":\"%s\",\"hp\":%d,\"maxHp\":%d," +
            "\"type\":\"%s\",\"ability\":\"%s\"," +
            "\"spriteUrl\":\"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/%d.png\"," +
            "\"backSpriteUrl\":\"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/%d.png\"}," +
            "\"player2\":{\"name\":\"%s\",\"pokemon\":\"%s\",\"hp\":%d,\"maxHp\":%d," +
            "\"type\":\"%s\",\"ability\":\"%s\"," +
            "\"spriteUrl\":\"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/%d.png\"}}",
            startGifUrl,
            b.challengerName,
            b.challengerName, b.challengerPokemonName,
            b.challengerPokemon[1], b.challengerMaxHp,
            typeStr(b.challengerType1, b.challengerType2),
            b.challengerAbility.displayName,
            b.challengerPokemon[0], b.challengerPokemon[0],
            b.challengedName, b.challengedPokemonName,
            b.challengedPokemon[1], b.challengedMaxHp,
            typeStr(b.challengedType1, b.challengedType2),
            b.challengedAbility.displayName,
            b.challengedPokemon[0]);
    }

    private String buildAttackEvent(Battle b, String attacker, String defender,
                                    Battle.AttackResult result) {
        return String.format(
            "{\"type\":\"battle_attack\",\"attacker\":\"%s\",\"defender\":\"%s\"," +
            "\"damage\":%d,\"typeMult\":%.1f,\"isCrit\":%b,\"healedHp\":%d," +
            "\"attackGifUrl\":\"%s\",\"currentTurn\":\"%s\"," +
            "\"player1\":{\"name\":\"%s\",\"hp\":%d,\"maxHp\":%d}," +
            "\"player2\":{\"name\":\"%s\",\"hp\":%d,\"maxHp\":%d}}",
            attacker, defender,
            result.damage(), result.typeMult(), result.isCrit(), result.healedHp(),
            attackGifUrl, b.getCurrentTurnName(),
            b.challengerName, b.challengerPokemon[1], b.challengerMaxHp,
            b.challengedName,  b.challengedPokemon[1], b.challengedMaxHp);
    }

    private String buildEndEvent(Battle b, String winner, boolean fled) {
        String loser = winner.equals(b.challengerName) ? b.challengedName : b.challengerName;
        return String.format(
            "{\"type\":\"battle_end\",\"winner\":\"%s\",\"loser\":\"%s\"," +
            "\"fled\":%b,\"victoryGifUrl\":\"%s\"}",
            winner, loser, fled, victoryGifUrl);
    }

    // ── UTILIDADES ────────────────────────────────────────────────

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
