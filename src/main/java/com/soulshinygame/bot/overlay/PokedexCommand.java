package com.soulshinygame.bot.overlay;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.util.DailyLimitManager;
import com.soulshinygame.bot.util.FollowerCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PokedexCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(PokedexCommand.class);
    private static final int TOTAL_POKEMON = 898;
    private static final int DAILY_LIMIT   = 5;
    private static final String COLLECTION = "pokemon";

    private final WebSocketOverlayServer overlayServer;
    private final DatabaseManager db;
    private final FollowerCache followerCache;
    private final DailyLimitManager dailyLimit;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Random random = new Random();

    public PokedexCommand(WebSocketOverlayServer overlayServer, DatabaseManager db,
                          FollowerCache followerCache, DailyLimitManager dailyLimit) {
        this.overlayServer = overlayServer;
        this.db            = db;
        this.followerCache = followerCache;
        this.dailyLimit    = dailyLimit;
    }

    @Override public String getName() { return "pokedex"; }
    @Override public long getCooldownMs() { return 30_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String username = event.getUser().getName();
        String userId   = event.getUser().getId();

        // 1. Verificar que sigue el canal
        if (!followerCache.isFollower(userId)) {
            client.getChat().sendMessage(channel,
                    "@" + username + " ¡Tienes que seguir el canal para usar la Pokédex! 💜");
            return;
        }

        // 2. Verificar límite diario
        if (!dailyLimit.canCollect(COLLECTION, username, DAILY_LIMIT)) {
            client.getChat().sendMessage(channel,
                    "@" + username + " ya usaste tus " + DAILY_LIMIT +
                            " Pokédex de hoy. ¡Vuelve mañana! 📅");
            return;
        }

        dailyLimit.increment(COLLECTION, username);
        int remaining = dailyLimit.remaining(COLLECTION, username, DAILY_LIMIT);

        new Thread(() -> {
            try {
                int pokemonId = random.nextInt(TOTAL_POKEMON) + 1;
                PokemonData pokemon = fetchPokemon(pokemonId);

                if (pokemon == null) {
                    client.getChat().sendMessage(channel, "Error al obtener el Pokémon, inténtalo de nuevo");
                    return;
                }

                boolean isNew = db.registerCollectible(username, COLLECTION,
                        String.valueOf(pokemonId), pokemon.getNameEs());

                String newMsg       = isNew ? " ✨¡Nuevo!" : " (ya lo tenías)";
                String remainingMsg = remaining > 0
                        ? " | Te quedan " + remaining + " hoy"
                        : " | ¡Pokédex agotada por hoy!";

                client.getChat().sendMessage(channel,
                        "🔴 @" + username + " lanzó la Pokédex... ¡Es " +
                                pokemon.getNameEs() + " #" + pokemon.getId() + "!" +
                                newMsg + remainingMsg);

                overlayServer.sendEvent(pokemon.toJson(username));
                log.info("Pokédex: {} → {} #{} nuevo={}", username, pokemon.getNameEs(), pokemon.getId(), isNew);

            } catch (Exception e) {
                log.error("Error en !pokedex", e);
            }
        }).start();
    }

    private PokemonData fetchPokemon(int id) throws IOException, InterruptedException {
        String body = get("https://pokeapi.co/api/v2/pokemon/" + id);
        if (body == null) return null;
        String speciesBody = get("https://pokeapi.co/api/v2/pokemon-species/" + id);
        String nameEs = extractSpanishName(speciesBody);
        return parseResponse(id, body, nameEs);
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", "SoulShinyBot/1.0").GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) { log.warn("API {} → {}", url, res.statusCode()); return null; }
        return res.body();
    }

    private String extractSpanishName(String json) {
        if (json == null) return null;
        try {
            Pattern p = Pattern.compile(
                    "\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*\"language\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"es\"");
            Matcher m = p.matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception e) { log.warn("No se pudo extraer nombre ES"); }
        return null;
    }

    private PokemonData parseResponse(int id, String json, String nameEs) {
        String nameEn = extractString(json, "\"name\":\"", "\"", 0);
        int hp = extractStat(json,"hp"), attack = extractStat(json,"attack"),
                defense = extractStat(json,"defense"), speed = extractStat(json,"speed");
        String ts = extractBlock(json, "\"types\":");
        String type1 = extractString(ts,"\"name\":\"","\"",0);
        String type2 = extractString(ts,"\"name\":\"","\"",type1.length()+10);
        if (type2.equals(type1)) type2 = null;
        String ss = extractBlock(json,"\"sprites\":");
        String spriteUrl = extractString(ss,"\"front_default\":\"","\"",0);
        String animatedUrl = extractString(ss,"\"animated\":{\"back_default","front_default\":\"",0);
        animatedUrl = extractString(animatedUrl,"\"","\"",0);
        if (animatedUrl.isEmpty()) animatedUrl = spriteUrl;
        String cryUrl = "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/latest/"+id+".ogg";
        String finalName = (nameEs!=null && !nameEs.isEmpty()) ? nameEs : capitalize(nameEn);
        return new PokemonData(id, nameEn, finalName, type1, type2, hp, attack, defense, speed, spriteUrl, animatedUrl, cryUrl);
    }

    private String extractString(String t, String s, String e, int f) {
        try { int i=t.indexOf(s,f)+s.length(); return t.substring(i,t.indexOf(e,i)); } catch(Exception ex){return "";}
    }
    private String extractBlock(String t, String m) {
        try { int i=t.indexOf(m)+m.length(); return t.substring(i,Math.min(i+2000,t.length())); } catch(Exception ex){return "";}
    }
    private int extractStat(String json, String stat) {
        try { Pattern p=Pattern.compile("\"base_stat\":(\\d+)[^}]+\"name\":\""+stat+"\""); Matcher m=p.matcher(json); if(m.find())return Integer.parseInt(m.group(1)); } catch(Exception e){}
        return 0;
    }
    private String capitalize(String s) { return (s==null||s.isEmpty())?s:s.substring(0,1).toUpperCase()+s.substring(1); }
}