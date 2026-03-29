package com.soulshinygame.bot.commands;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;

/**
 * Interfaz que debe implementar cualquier comando del bot.
 *
 * Para añadir un comando nuevo:
 *  1. Crea una clase que implemente esta interfaz
 *  2. Regístrala en Main.java con: registry.register(new TuComando(...))
 *  Eso es to-do.
 */
public interface Command {

    /** Nombre del comando sin prefijo. Ej: "pokedex", "dados", "naruto" */
    String getName();

    /** Lógica que se ejecuta cuando alguien escribe el comando en el chat */
    void execute(ChannelMessageEvent event, TwitchClient client, String channel);

    /** Cooldown en milisegundos. 0 = sin cooldown */
    default long getCooldownMs() { return 0; }
}
