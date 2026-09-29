package com.soulshinygame.bot.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Cliente mínimo de Helix para Channel Points.
 *  - Listar / crear / editar / borrar recompensas.
 *  - Crear, editar y borrar exige el scope channel:manage:redemptions.
 *  - Twitch solo deja editar o borrar las recompensas creadas por ESTA app (client id).
 */
public class TwitchRewardsApi {

    public record Result(int status, JsonNode body, String error) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    private static final String BASE = "https://api.twitch.tv/helix/channel_points/custom_rewards";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String broadcasterId;
    private final String token;
    private final String clientId;

    public TwitchRewardsApi(String broadcasterId, String accessToken, String clientId) {
        this.broadcasterId = broadcasterId;
        this.token         = accessToken.replace("oauth:", "");
        this.clientId      = clientId;
    }

    /** Todas las recompensas del canal (manageableOnly = solo las creadas por esta app). */
    public Result list(boolean manageableOnly) {
        return call("GET", BASE + "?broadcaster_id=" + broadcasterId
                + (manageableOnly ? "&only_manageable_rewards=true" : ""), null);
    }

    public Result create(ObjectNode payload) {
        return call("POST", BASE + "?broadcaster_id=" + broadcasterId, payload);
    }

    public Result update(String rewardId, ObjectNode payload) {
        return call("PATCH", BASE + "?broadcaster_id=" + broadcasterId + "&id=" + rewardId, payload);
    }

    public Result delete(String rewardId) {
        return call("DELETE", BASE + "?broadcaster_id=" + broadcasterId + "&id=" + rewardId, null);
    }

    private Result call(String method, String url, ObjectNode payload) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", clientId);
            if (payload != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(payload.toString()));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());

            JsonNode body = null;
            if (res.body() != null && !res.body().isBlank()) {
                try { body = RewardConfigStore.MAPPER.readTree(res.body()); } catch (Exception ignored) {}
            }
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return new Result(res.statusCode(), body, null);
            }
            return new Result(res.statusCode(), body, explain(res.statusCode(), body));
        } catch (Exception e) {
            return new Result(0, null, "No se pudo contactar con Twitch: " + e.getMessage());
        }
    }

    /** Traduce los errores típicos de Twitch a mensajes útiles. */
    private String explain(int status, JsonNode body) {
        String msg = body != null && body.hasNonNull("message") ? body.get("message").asText() : "";
        String low = msg.toLowerCase();
        if (status == 401) {
            return "Token rechazado por Twitch (401). Puede haber caducado: regenéralo.";
        }
        if (status == 403 && (low.contains("scope") || low.contains("authorization"))) {
            return "Al token le falta el scope channel:manage:redemptions. Regenéralo con ese scope.";
        }
        if (status == 403 && (low.contains("partner") || low.contains("affiliate"))) {
            return "Solo canales Afiliados o Partners pueden usar puntos del canal.";
        }
        if (status == 403) {
            return "Twitch solo permite editar/borrar recompensas creadas por esta app. "
                    + "Las que hiciste a mano en el dashboard se gestionan desde Twitch. (" + msg + ")";
        }
        if (status == 400 && low.contains("duplicate")) {
            return "Ya existe una recompensa con ese título en tu canal. Twitch no permite títulos repetidos.";
        }
        if (status == 404) {
            return "Twitch no encuentra esa recompensa (¿la borraste desde el dashboard?).";
        }
        return "Twitch respondió " + status + (msg.isEmpty() ? "" : ": " + msg);
    }
}
