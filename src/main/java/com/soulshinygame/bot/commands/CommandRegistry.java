package com.soulshinygame.bot.commands;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.soulshinygame.bot.util.FollowerCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registro central de comandos.
 * Gestiona el despacho y el cooldown de forma automática para todos los comandos.
 * Requiere que el usuario siga el canal para usar cualquier comando.
 */
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);
    private static final String PREFIX = "!";

    private final TwitchClient client;
    private final String channel;
    private final FollowerCache followerCache;

    private final Map<String, Command> commands = new HashMap<>();
    // cooldown: usuario+comando -> timestamp del último uso
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public CommandRegistry(TwitchClient client, String channel, FollowerCache followerCache) {
        this.client        = client;
        this.channel       = channel;
        this.followerCache = followerCache;
    }

    // !pokemon.pokedex

    /** Registra un nuevo comando. Llamar desde Main.java. */
    public CommandRegistry register(Command command) {
        commands.put(command.getName().toLowerCase(), command);
        log.info("Comando registrado: !{}", command.getName());
        return this; // permite encadenar: registry.register(...).register(...)
    }

    /** Engancha el listener al EventManager de Twitch. Llamar una sola vez desde Main.java. */
    public void start() {
        client.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            String message = event.getMessage().trim();
            if (!message.startsWith(PREFIX)) return;

            String[] parts = message.substring(PREFIX.length()).split("\\s+");
            String commandName = parts[0].toLowerCase();
            Command command = commands.get(commandName);
            if (command == null) return;

            // Comprobar que sigue el canal
            if (!followerCache.isFollower(event.getUser().getId())) {
                client.getChat().sendMessage(channel,
                    "@" + event.getUser().getName() + " ¡Necesitas seguir el canal para usar los comandos! 💜 /follow");
                return;
            }

            // Comprobar cooldown por usuario
            if (isOnCooldown(event.getUser().getName(), commandName, command.getCooldownMs())) {
                long remaining = getRemainingCooldown(
                    event.getUser().getName(), commandName, command.getCooldownMs()
                );
                client.getChat().sendMessage(channel,
                    "@" + event.getUser().getName() + " espera " + remaining + "s ⏳");
                return;
            }

            setCooldown(event.getUser().getName(), commandName);
            log.debug("Ejecutando !{} por {}", commandName, event.getUser().getName());
            command.execute(event, client, channel);
        });

        log.info("CommandRegistry activo con {} comandos: {}",
            commands.size(),
            commands.keySet().stream().map(c -> PREFIX + c).collect(Collectors.joining(", "))
        );
    }

    // --- Gestión de cooldown ---

    private boolean isOnCooldown(String user, String command, long cooldownMs) {
        if (cooldownMs <= 0) return false;
        Long last = cooldowns.get(user + ":" + command);
        return last != null && System.currentTimeMillis() - last < cooldownMs;
    }

    private long getRemainingCooldown(String user, String command, long cooldownMs) {
        Long last = cooldowns.get(user + ":" + command);
        if (last == null) return 0;
        return (cooldownMs - (System.currentTimeMillis() - last)) / 1000;
    }

    private void setCooldown(String user, String command) {
        cooldowns.put(user + ":" + command, System.currentTimeMillis());
    }
}
