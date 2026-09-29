package com.soulshinygame.bot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valida el token de Twitch al arrancar el bot.
 * Llama al endpoint oficial de validación y comprueba que tenga todos los scopes que necesita el bot.
 *
 * Usa: https://id.twitch.tv/oauth2/validate
 */
public class TokenValidator {

    private static final Logger log = LoggerFactory.getLogger(TokenValidator.class);

    // Scopes que necesita el bot para funcionar completamente
    private static final List<ScopeInfo> REQUIRED_SCOPES = Arrays.asList(
            new ScopeInfo("chat:read",                     "Leer chat",                        true),
            new ScopeInfo("chat:edit",                     "Enviar mensajes al chat",          true),
            new ScopeInfo("moderator:manage:banned_users", "Timeouts y bans",                  false),
            new ScopeInfo("moderator:read:followers",      "Comprobar seguidores",             true),
            new ScopeInfo("channel:read:redemptions",      "Recompensas (!meme via puntos)",   true),
            new ScopeInfo("channel:manage:redemptions",    "Crear/editar rewards desde el panel", false)
    );

    public static void validate(String accessToken) {
        String token = accessToken.replace("oauth:", "");

        try {
            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://id.twitch.tv/oauth2/validate"))
                            .header("Authorization", "OAuth " + token)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.error("");
                log.error("╔══════════════════════════════════════════════════════════════════╗");
                log.error("║                    ❌ TOKEN INVÁLIDO                             ║");
                log.error("╚══════════════════════════════════════════════════════════════════╝");
                log.error("Status: {} | Respuesta: {}", res.statusCode(), res.body());
                log.error("");
                log.error("Soluciones:");
                log.error("  1. El token ha expirado → regenéralo");
                log.error("  2. El CLIENT_SECRET cambió → el token quedó invalidado");
                log.error("  3. El formato es incorrecto → debe empezar por 'oauth:'");
                log.error("══════════════════════════════════════════════════════════════════");
                return;
            }

            String body = res.body();
            String user = extractField(body, "login");
            String clientId = extractField(body, "client_id");
            int expiresIn   = extractInt(body, "expires_in");
            Set<String> scopes = extractScopes(body);

            log.info("");
            log.info("╔══════════════════════════════════════════════════════════════════╗");
            log.info("║                  VALIDACIÓN DEL TOKEN TWITCH                     ║");
            log.info("╚══════════════════════════════════════════════════════════════════╝");
            log.info("  👤 Usuario:       {}", user);
            log.info("  🔑 Client ID:     {}", clientId);
            log.info("  ⏱️  Expira en:    {} días", expiresIn / 86400);
            log.info("──────────────────────────────────────────────────────────────────");
            log.info("  SCOPES:");

            List<String> missing = new ArrayList<>();
            for (ScopeInfo s : REQUIRED_SCOPES) {
                boolean has = scopes.contains(s.name);
                String icon = has ? "✅" : (s.critical ? "❌" : "⚠️ ");
                log.info("    {} {}  → {}", icon, padRight(s.name, 35), s.description);
                if (!has) missing.add(s.name);
            }

            if (missing.isEmpty()) {
                log.info("──────────────────────────────────────────────────────────────────");
                log.info("  ✨ Todos los scopes están presentes — bot completamente funcional");
            } else {
                log.info("──────────────────────────────────────────────────────────────────");
                log.warn("  ⚠️  Faltan {} scope(s) — algunas funciones no irán", missing.size());
                log.warn("");
                log.warn("  Regenera el token con esta URL (cambia TU_CLIENT_ID):");
                log.warn("  https://id.twitch.tv/oauth2/authorize?client_id={}", clientId);
                log.warn("    &redirect_uri=http://localhost&response_type=token&scope=");
                StringBuilder scopeStr = new StringBuilder();
                for (ScopeInfo s : REQUIRED_SCOPES) {
                    if (scopeStr.length() > 0) scopeStr.append("+");
                    scopeStr.append(s.name);
                }
                log.warn("    {}", scopeStr);
            }
            log.info("══════════════════════════════════════════════════════════════════");
            log.info("");

        } catch (Exception e) {
            log.error("Error validando token: {}", e.getMessage());
        }
    }

    // ── Utilidades ────────────────────────────────────────────────

    private static String extractField(String json, String key) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private static int extractInt(String json, String key) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Exception e) { return 0; }
    }

    private static Set<String> extractScopes(String json) {
        Set<String> result = new HashSet<>();
        try {
            int start = json.indexOf("\"scopes\"");
            if (start == -1) return result;
            int arrayStart = json.indexOf('[', start);
            int arrayEnd   = json.indexOf(']', arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return result;
            String arrayContent = json.substring(arrayStart + 1, arrayEnd);
            Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(arrayContent);
            while (m.find()) result.add(m.group(1));
        } catch (Exception e) {}
        return result;
    }

    private static String padRight(String s, int len) {
        return s.length() >= len ? s : s + " ".repeat(len - s.length());
    }

    private record ScopeInfo(String name, String description, boolean critical) {}
}