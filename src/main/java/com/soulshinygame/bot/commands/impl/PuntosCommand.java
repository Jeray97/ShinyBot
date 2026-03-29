package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.database.DatabaseManager;

public class PuntosCommand implements Command {

    private final DatabaseManager db;

    public PuntosCommand(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public String getName() { return "puntos"; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();
        int points = db.getPoints(user);
        client.getChat().sendMessage(channel, "@" + user + " tienes " + points + " puntos ⭐");
    }
}
