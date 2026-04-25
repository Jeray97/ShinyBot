package com.soulshinygame.bot.commands.impl;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.common.enums.CommandPermission;
import com.soulshinygame.bot.commands.Command;
import com.soulshinygame.bot.overlay.WebSocketOverlayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PaquitoCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(PaquitoCommand.class);

    // Usuarios con acceso además de mods y broadcaster
    private static final Set<String> WHITELIST = Set.of("elsyum");

    // ID del video en Streamable (parte final de la URL)
    private static final String STREAMABLE_ID = "wlc460";

    private final WebSocketOverlayServer overlayServer;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // URL real del .mp4, se resuelve al arrancar y se renueva cada 6h
    private volatile String resolvedVideoUrl = null;

    public PaquitoCommand(WebSocketOverlayServer overlayServer) {
        this.overlayServer = overlayServer;
        resolveStreamableUrl();

        // Re-resolver cada 6 horas por si la URL firmada de Streamable expira
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(this::resolveStreamableUrl, 6, 6, TimeUnit.HOURS);
    }

    /**
     * Llama a la API de Streamable para obtener la URL directa del .mp4.
     * Las URLs de Streamable llevan firma y expiran, por eso se renuevan periódicamente.
     */
    private void resolveStreamableUrl() {
        new Thread(() -> {
            try {
                String apiUrl = "https://api.streamable.com/videos/" + STREAMABLE_ID;
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("User-Agent", "SoulShinyBot/1.0")
                        .GET().build();

                HttpResponse<String> res = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofString());

                if (res.statusCode() == 200) {
                    String[] patterns = {
                            "\"url\"\\s*:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\""  // https://cdn.../video.mp4?...
                    };

                    for (String pat : patterns) {
                        Matcher m = Pattern.compile(pat).matcher(res.body());
                        if (m.find()) {
                            String url = m.group(1);
                            resolvedVideoUrl = url.startsWith("//") ? "https:" + url : url;
                            log.info("URL de Paquito resuelta correctamente");
                            return;
                        }
                    }
                    log.warn("No se pudo extraer la URL del .mp4 de Streamable");
                } else {
                    log.warn("Streamable API respondió {}", res.statusCode());
                }
            } catch (Exception e) {
                log.error("Error resolviendo URL de Streamable", e);
            }
        }).start();
    }

    @Override
    public String getName() { return "paquito"; }

    @Override
    public long getCooldownMs() { return 30_000; }

    @Override
    public void execute(ChannelMessageEvent event, TwitchClient client, String channel) {
        String username = event.getUser().getName().toLowerCase();
        Set<CommandPermission> perms = event.getPermissions();

        // Verificar permisos: broadcaster, moderador o whitelist
        boolean allowed = perms.contains(CommandPermission.BROADCASTER)
                || perms.contains(CommandPermission.MODERATOR)
                || WHITELIST.contains(username);

        if (!allowed) {
            client.getChat().sendMessage(channel,
                    "@" + username + " no tienes permiso para usar este comando 🚫");
            return;
        }

        // Si la URL aún no se resolvió al arrancar
        if (resolvedVideoUrl == null) {
            client.getChat().sendMessage(channel,
                    "@" + username + " Paquito aún se está preparando, inténtalo en unos segundos ⏳");
            resolveStreamableUrl();
            return;
        }

        client.getChat().sendMessage(channel,
                "🐾 @" + username + " invocó a Paquito!");

        overlayServer.sendEvent(String.format("""
            {
              "type": "video",
              "triggeredBy": "%s",
              "videoUrl": "%s",
              "volume": 0.8,
              "durationSeconds": 15
            }
            """, username, resolvedVideoUrl));

        log.info("!paquito invocado por {}", username);
    }
}