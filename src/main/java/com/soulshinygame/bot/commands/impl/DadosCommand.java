package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;

import java.util.Random;

public class DadosCommand implements Command {

    private final DatabaseManager db;
    private final Random random = new Random();

    public DadosCommand(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public String getName() { return "dados"; }

    @Override
    public long getCooldownMs() { return 10_000; } // 10s por usuario

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();
        int result = random.nextInt(6) + 1;

        if (result >= 4) {
            db.addPoints(user, 10);
            client.getChat().sendMessage(channel,
                "@" + user + " sacó un " + result + " 🎲 ¡Ganaste 10 puntos!");
        } else {
            db.removePoints(user, 5);
            client.getChat().sendMessage(channel,
                "@" + user + " sacó un " + result + " 🎲 Perdiste 5 puntos...");
        }
    }
}
