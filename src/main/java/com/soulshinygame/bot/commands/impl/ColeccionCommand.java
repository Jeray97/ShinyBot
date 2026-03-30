package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;

import java.util.List;

/**
 * !coleccion [tipo]  → muestra cuántos ítems tienes de esa colección
 * !coleccion         → muestra todas las colecciones disponibles
 *
 * Ejemplos:
 *   !coleccion pokemon   → "@user tienes 12 Pokémons: Pikachu, Bulbasaur..."
 *   !coleccion naruto    → "@user tienes 2 de naruto: Naruto Uzumaki, Sasuke"
 */
public class ColeccionCommand implements Command {

    private final DatabaseManager db;

    public ColeccionCommand(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public String getName() { return "coleccion"; }

    @Override
    public long getCooldownMs() { return 5_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();
        String[] parts = event.getMessage().trim().split("\\s+");

        if (parts.length < 2) {
            client.getChat().sendMessage(channel,
                    "@" + user + " usa !coleccion [tipo]. Ej: !coleccion pokemon o !coleccion naruto");
            return;
        }

        String collectionType = parts[1].toLowerCase();
        List<String> items = db.getCollection(user, collectionType);

        if (items.isEmpty()) {
            client.getChat().sendMessage(channel,
                    "@" + user + " aún no tienes ningún coleccionable de " + collectionType + " 😢");
            return;
        }

        // Mostrar hasta 10 nombres para no llenar el chat
        String preview = items.size() <= 10
                ? String.join(", ", items)
                : String.join(", ", items.subList(0, 10)) + "... y " + (items.size() - 10) + " más";

        client.getChat().sendMessage(channel,
                "@" + user + " tiene " + items.size() + " de " + collectionType + ": " + preview);
    }
}