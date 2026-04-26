package com.soulshinygame.bot.commands;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);
    private static final String PREFIX = "!";

    private final TwitchClient client;
    private final String channel;
    private final Map<String, Command> commands   = new LinkedHashMap<>();
    private final Set<String> disabledCommands    = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> cooldowns     = new ConcurrentHashMap<>();

    public CommandRegistry(TwitchClient client, String channel) {
        this.client  = client;
        this.channel = channel;
    }

    public CommandRegistry register(Command command) {
        commands.put(command.getName().toLowerCase(), command);
        log.info("Comando registrado: !{}", command.getName());
        return this;
    }

    public void start() {
        client.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            String message = event.getMessage().trim();
            if (!message.startsWith(PREFIX)) return;

            String[] parts     = message.substring(PREFIX.length()).split("\\s+");
            String commandName = parts[0].toLowerCase();
            Command command    = commands.get(commandName);
            if (command == null) return;

            if (disabledCommands.contains(commandName)) {
                client.getChat().sendMessage(channel,
                        "@" + event.getUser().getName() + " ese comando está desactivado temporalmente.");
                return;
            }

            if (isOnCooldown(event.getUser().getName(), commandName, command.getCooldownMs())) {
                long remaining = getRemainingCooldown(
                        event.getUser().getName(), commandName, command.getCooldownMs());
                client.getChat().sendMessage(channel,
                        "@" + event.getUser().getName() + " espera " + remaining + "s ⏳");
                return;
            }

            setCooldown(event.getUser().getName(), commandName);
            log.debug("Ejecutando !{} por {}", commandName, event.getUser().getName());
            command.execute(event, client, channel);
        });

        log.info("CommandRegistry activo — {} comandos: {}",
                commands.size(),
                commands.keySet().stream().map(c -> PREFIX + c).collect(Collectors.joining(", ")));
    }

    // ── Para el panel de admin ────────────────────────────────────

    public boolean toggleCommand(String name) {
        name = name.toLowerCase();
        if (disabledCommands.contains(name)) { disabledCommands.remove(name); return true; }
        else { disabledCommands.add(name); return false; }
    }

    public boolean isEnabled(String name)          { return !disabledCommands.contains(name.toLowerCase()); }
    public List<String> getCommandNames()          { return new ArrayList<>(commands.keySet()); }
    public List<String> getEnabledCommandNames()   { return commands.keySet().stream().filter(n -> !disabledCommands.contains(n)).collect(Collectors.toList()); }
    public int getCommandCount()                   { return commands.size(); }
    public int getEnabledCount()                   { return commands.size() - disabledCommands.size(); }

    // ── Cooldown ──────────────────────────────────────────────────

    private boolean isOnCooldown(String user, String cmd, long ms) {
        if (ms <= 0) return false;
        Long last = cooldowns.get(user + ":" + cmd);
        return last != null && System.currentTimeMillis() - last < ms;
    }

    private long getRemainingCooldown(String user, String cmd, long ms) {
        Long last = cooldowns.get(user + ":" + cmd);
        return last == null ? 0 : (ms - (System.currentTimeMillis() - last)) / 1000;
    }

    private void setCooldown(String user, String cmd) {
        cooldowns.put(user + ":" + cmd, System.currentTimeMillis());
    }
}