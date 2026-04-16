package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;
import com.soulshinygame.bot.database.UserCollectible;

import java.util.ArrayList;
import java.util.List;

/**
 * !coleccion           -> resumen de Pokemon + Anime
 * !coleccion pokemon   -> lista completa de Pokemon
 * !coleccion anime     -> lista completa de personajes anime
 *
 * Formato Pokemon:  #025 | Pikachu | 1a Gen
 * Formato Anime:    Naruto Uzumaki
 */
public class ColeccionCommand implements Command {

    private static final int ENTRIES_PER_MSG = 8;

    private final DatabaseManager db;

    public ColeccionCommand(DatabaseManager db) {
        this.db = db;
    }

    @Override public String getName()     { return "coleccion"; }
    @Override public long getCooldownMs() { return 8_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user   = event.getUser().getName();
        String[] parts = event.getMessage().trim().split("\\s+");

        if (parts.length < 2) {
            showSummary(user, client, channel);
        } else {
            showCollection(user, parts[1].toLowerCase(), client, channel);
        }
    }

    // ── RESUMEN (sin argumentos) ──────────────────────────────────────────

    private void showSummary(String user, TwitchClient client, String channel) {
        List<UserCollectible> pokemon = db.getCollectibles(user, "pokemon");
        List<UserCollectible> anime   = db.getCollectiblesExcluding(user, "pokemon");

        if (pokemon.isEmpty() && anime.isEmpty()) {
            say(client, channel,
                "📭 @" + user + " tu coleccion esta vacia. Usa !pokedex y !anime para empezar! 🌟");
            return;
        }

        say(client, channel,
            "📦 Coleccion de @" + user + " ->"
            + " 🔴 Pokemon: " + pokemon.size()
            + " | 🌸 Anime: " + anime.size()
            + " | Usa !coleccion pokemon o !coleccion anime para ver la lista completa");
    }

    // ── COLECCION COMPLETA ────────────────────────────────────────────────

    private void showCollection(String user, String type, TwitchClient client, String channel) {
        boolean isPokemon = type.equals("pokemon");
        boolean isAnime   = type.equals("anime");

        if (!isPokemon && !isAnime) {
            say(client, channel,
                "❓ @" + user + " tipo no reconocido. Usa !coleccion pokemon o !coleccion anime");
            return;
        }

        List<UserCollectible> items = isPokemon
                ? db.getCollectibles(user, "pokemon")
                : db.getCollectiblesExcluding(user, "pokemon");

        if (items.isEmpty()) {
            say(client, channel,
                "📭 @" + user + " no tienes nada en tu coleccion de " + type
                + (isPokemon ? ". Usa !pokedex! 🔴" : ". Usa !anime! 🌸"));
            return;
        }

        String emoji = isPokemon ? "🔴" : "🌸";
        String title = isPokemon ? "POKEDEX" : "ANIME";

        // Cabecera
        say(client, channel,
            emoji + " " + title + " de @" + user
            + " | " + items.size() + " coleccionados"
            + (isPokemon ? " / 898 totales 🎯" : " 🎭"));

        // Entradas en bloques de ENTRIES_PER_MSG por mensaje
        List<String> formatted = isPokemon ? formatPokemon(items) : formatAnime(items);
        for (int i = 0; i < formatted.size(); i += ENTRIES_PER_MSG) {
            List<String> batch = formatted.subList(i, Math.min(i + ENTRIES_PER_MSG, formatted.size()));
            say(client, channel, "@" + user + " -> " + String.join(" | ", batch));
        }
    }

    // ── FORMATEO ──────────────────────────────────────────────────────────

    private List<String> formatPokemon(List<UserCollectible> items) {
        List<String> result = new ArrayList<>();
        for (UserCollectible item : items) {
            int id = parseId(item.getItemId());
            result.add(String.format("#%03d %s %s", id, item.getItemName(), generation(id)));
        }
        return result;
    }

    private List<String> formatAnime(List<UserCollectible> items) {
        List<String> result = new ArrayList<>();
        for (UserCollectible item : items) {
            result.add("🌸 " + item.getItemName() + " [" + item.getCollectionType() + "]");
        }
        return result;
    }

    // ── UTILIDADES ────────────────────────────────────────────────────────

    private void say(TwitchClient client, String channel, String message) {
        client.getChat().sendMessage(channel, message);
    }

    private int parseId(String itemId) {
        try { return Integer.parseInt(itemId); } catch (NumberFormatException e) { return 0; }
    }

    private String generation(int id) {
        if (id <=  151) return "(1a Gen)";
        if (id <=  251) return "(2a Gen)";
        if (id <=  386) return "(3a Gen)";
        if (id <=  493) return "(4a Gen)";
        if (id <=  649) return "(5a Gen)";
        if (id <=  721) return "(6a Gen)";
        if (id <=  809) return "(7a Gen)";
        if (id <=  905) return "(8a Gen)";
        return "(9a Gen)";
    }
}
