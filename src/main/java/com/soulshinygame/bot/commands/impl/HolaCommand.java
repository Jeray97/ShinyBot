package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.commands.Command;

public class HolaCommand implements Command {

    @Override
    public String getName() { return "hola"; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String user = event.getUser().getName();
        client.getChat().sendMessage(channel, "¡Hola, @" + user + "! Bienvenido al canal 👋");
    }
}
