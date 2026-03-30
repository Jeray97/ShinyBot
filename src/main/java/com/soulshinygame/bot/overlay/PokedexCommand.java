package com.soulshinygame.bot.overlay;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
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

    private final WebSocketOverlayServer overlayServer;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Random random = new Random();

    public PokedexCommand(WebSocketOverlayServer overlayServer) {
        this.overlayServer = overlayServer;
    }

    @Override
    public String getName() { return "pokedex"; }

    @Override
    public long getCooldownMs() { return 30_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();

        new Thread(() -> {
            try {
                int pokemonId = random.nextInt(TOTAL_POKEMON) + 1;
                PokemonData pokemon = fetchPokemon(pokemonId);

                if (pokemon == null) {
                    client.getChat().sendMessage(channel, "Error al obtener el Pokémon, inténtalo de nuevo");
                    return;
                }

                client.getChat().sendMessage(channel,
                        "🔴 @" + user + " lanzó la Pokédex... ¡Es un " +
                                pokemon.getNameEs() + " #" + pokemon.getId() + "!");

                overlayServer.sendEvent(pokemon.toJson(user));
                log.info("Pokédex lanzada por {}: {} #{}", user, pokemon.getNameEs(), pokemon.getId());

            } catch (Exception e) {
                log.error("Error en comando !pokedex", e);
            }
        }).start();
    }

    private PokemonData fetchPokemon(int id) throws IOException, InterruptedException {
        // Llamada 1: datos base del pokemon
        String body = get("https://pokeapi.co/api/v2/pokemon/" + id);
        if (body == null) return null;

        // Llamada 2: nombre en español desde species
        String speciesBody = get("https://pokeapi.co/api/v2/pokemon-species/" + id);
        String nameEs = extractSpanishName(speciesBody);

        return parseResponse(id, body, nameEs);
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SoulShinyBot/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("API respondió {} para {}", response.statusCode(), url);
            return null;
        }
        return response.body();
    }

    /**
     * Extrae el nombre en español del JSON de pokemon-species.
     * El JSON tiene un array "names" con objetos {"name":"...","language":{"name":"es",...}}
     */
    private String extractSpanishName(String json) {
        if (json == null) return null;
        try {
            // Buscamos el bloque donde language.name es "es"
            Pattern p = Pattern.compile("\"name\":\"([^\"]+)\",\"language\":\\{\"name\":\"es\"");
            Matcher m = p.matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception e) {
            log.warn("No se pudo extraer nombre en español");
        }
        return null;
    }

    private PokemonData parseResponse(int id, String json, String nameEs) {
        String nameEn = extractString(json, "\"name\":\"", "\"", 0);
        int hp        = extractStat(json, "hp");
        int attack    = extractStat(json, "attack");
        int defense   = extractStat(json, "defense");
        int speed     = extractStat(json, "speed");

        String typesSection = extractBlock(json, "\"types\":");
        String type1 = extractString(typesSection, "\"name\":\"", "\"", 0);
        String type2 = extractString(typesSection, "\"name\":\"", "\"", type1.length() + 10);
        if (type2.equals(type1)) type2 = null;

        String spritesSection = extractBlock(json, "\"sprites\":");
        String spriteUrl = extractString(spritesSection, "\"front_default\":\"", "\"", 0);

        String animatedUrl = extractString(spritesSection, "\"animated\":{\"back_default", "front_default\":\"", 0);
        animatedUrl = extractString(animatedUrl, "\"", "\"", 0);
        if (animatedUrl.isEmpty()) animatedUrl = spriteUrl;

        String cryUrl = "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/latest/" + id + ".ogg";

        // Si no hay nombre en español, usar el inglés capitalizado
        String finalName = (nameEs != null && !nameEs.isEmpty()) ? nameEs : capitalize(nameEn);

        return new PokemonData(id, nameEn, finalName, type1, type2,
                hp, attack, defense, speed,
                spriteUrl, animatedUrl, cryUrl);
    }

    private String extractString(String text, String startMarker, String endMarker, int fromIndex) {
        try {
            int start = text.indexOf(startMarker, fromIndex) + startMarker.length();
            int end = text.indexOf(endMarker, start);
            return text.substring(start, end);
        } catch (Exception e) { return ""; }
    }

    private String extractBlock(String text, String marker) {
        try {
            int start = text.indexOf(marker) + marker.length();
            return text.substring(start, Math.min(start + 2000, text.length()));
        } catch (Exception e) { return ""; }
    }

    private int extractStat(String json, String statName) {
        try {
            Pattern p = Pattern.compile("\"base_stat\":(\\d+)[^}]+\"name\":\"" + statName + "\"");
            Matcher m = p.matcher(json);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception e) { log.warn("No se pudo extraer stat: {}", statName); }
        return 0;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}