package com.soulshinygame.bot.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lógica de la pestaña "Rewards" del panel admin:
 *  - lista las rewards configuradas y las compara con las de Twitch,
 *  - crea / edita / borra rewards en Twitch a la vez que en meme_videos.json,
 *  - recarga el handler para que los cambios valgan al instante.
 */
public class RewardAdminService {

    private static final Logger log = LoggerFactory.getLogger(RewardAdminService.class);
    private static final Pattern STREAMABLE_ID = Pattern.compile("streamable\\.com/(?:[a-z]/)?([A-Za-z0-9]+)");
    private static final Pattern HEX_COLOR     = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** Respuesta lista para enviar: código HTTP + JSON. */
    public record Reply(int status, String json) {}

    private final RewardConfigStore store;
    private final TwitchRewardsApi api;
    private final MemeRewardHandler handler;

    public RewardAdminService(MemeRewardHandler handler) {
        this.handler = handler;
        this.store   = handler.getStore();
        this.api     = handler.getApi();
    }

    // ── GET /api/rewards ──────────────────────────────────────────

    public Reply list() throws Exception {
        ArrayNode config = store.load();

        var all        = api.list(false);
        var manageable = api.list(true);

        Map<String, JsonNode> byId = new java.util.HashMap<>();
        java.util.Set<String> manageableIds = new java.util.HashSet<>();
        ArrayNode twitchRewards = RewardConfigStore.MAPPER.createArrayNode();

        if (all.ok()) {
            if (manageable.ok()) for (JsonNode r : manageable.body().path("data")) manageableIds.add(r.path("id").asText());
            for (JsonNode r : all.body().path("data")) {
                String id = r.path("id").asText();
                byId.put(id, r);
                ObjectNode o = twitchRewards.addObject();
                o.put("id", id);
                o.put("title", r.path("title").asText());
                o.put("cost", r.path("cost").asInt());
                o.put("enabled", r.path("is_enabled").asBoolean(true));
                o.put("manageable", manageableIds.contains(id));
            }
        }

        ArrayNode states = RewardConfigStore.MAPPER.createArrayNode();
        for (JsonNode reward : config) {
            String id = reward.path("rewardId").asText("").trim();
            ObjectNode s = states.addObject();
            String state;
            if (id.isEmpty() || id.startsWith("PEGA_AQUI")) state = "pending";      // no existe en Twitch aún
            else if (!all.ok())                            state = "unknown";      // no pudimos consultar a Twitch
            else if (byId.containsKey(id))                 state = "synced";
            else                                           state = "missing";      // ID que Twitch no conoce
            s.put("state", state);
            s.put("manageable", manageableIds.contains(id));
        }

        ObjectNode out = RewardConfigStore.MAPPER.createObjectNode();
        out.set("rewards", config);
        out.set("states", states);
        ObjectNode tw = out.putObject("twitch");
        tw.put("ok", all.ok());
        if (!all.ok()) tw.put("error", all.error());
        tw.set("rewards", twitchRewards);
        return new Reply(200, out.toString());
    }

    // ── POST /api/rewards  |  PUT /api/rewards/{index} ────────────

    /** index = -1 → nueva reward. body = { "reward": {...}, "push": true|false } */
    public Reply save(int index, String body) throws Exception {
        JsonNode req = RewardConfigStore.MAPPER.readTree(body);
        boolean push = req.path("push").asBoolean(false);
        JsonNode input = req.path("reward");
        if (!input.isObject()) return error(400, "Falta el objeto 'reward'");

        ArrayNode config = store.load();
        if (index >= config.size()) return error(404, "Esa reward ya no existe, recarga la lista");

        ObjectNode reward;
        try { reward = normalize((ObjectNode) input); }
        catch (IllegalArgumentException e) { return error(400, e.getMessage()); }

        if (push) {
            String rewardId = reward.path("rewardId").asText("");
            if (rewardId.isEmpty()) {
                if (reward.path("cost").asInt(0) < 1) return error(400, "Pon un coste en puntos (mínimo 1) para crearla en Twitch");
                var res = api.create(twitchPayload(reward, true));
                if (!res.ok()) return error(400, res.error());
                reward.put("rewardId", res.body().path("data").path(0).path("id").asText());
                log.info("Reward '{}' creada en Twitch desde el panel", reward.path("name").asText());
            } else {
                if (reward.path("cost").asInt(0) < 1) return error(400, "El coste debe ser al menos 1");
                var res = api.update(rewardId, twitchPayload(reward, false));
                if (!res.ok()) return error(400, res.error());
                log.info("Reward '{}' actualizada en Twitch desde el panel", reward.path("name").asText());
            }
        }

        if (index < 0) { config.add(reward); index = config.size() - 1; }
        else           { config.set(index, reward); }
        store.save(config);
        handler.reload();

        ObjectNode out = RewardConfigStore.MAPPER.createObjectNode();
        out.put("ok", true);
        out.put("index", index);
        out.set("reward", reward);
        return new Reply(200, out.toString());
    }

    // ── DELETE /api/rewards/{index}?twitch=1 ──────────────────────

    public Reply delete(int index, boolean alsoTwitch) throws Exception {
        ArrayNode config = store.load();
        if (index < 0 || index >= config.size()) return error(404, "Esa reward ya no existe, recarga la lista");

        String rewardId = config.get(index).path("rewardId").asText("");
        if (alsoTwitch && !rewardId.isEmpty()) {
            var res = api.delete(rewardId);
            if (!res.ok() && res.status() != 404) return error(400, res.error());
        }
        config.remove(index);
        store.save(config);
        handler.reload();
        log.info("Reward eliminada desde el panel (twitch={})", alsoTwitch);
        return new Reply(200, "{\"ok\":true}");
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Limpia y valida lo que llega del navegador. Conserva campos desconocidos. */
    private ObjectNode normalize(ObjectNode in) {
        String name = in.path("name").asText("").trim();
        if (name.isEmpty())      throw new IllegalArgumentException("Ponle un nombre a la reward");
        if (name.length() > 45)  throw new IllegalArgumentException("Twitch limita el nombre a 45 caracteres");

        ArrayNode videos = RewardConfigStore.MAPPER.createArrayNode();
        int n = 0;
        for (JsonNode v : in.path("videos")) {
            String id = extractStreamableId(v.path("streamableId").asText(""));
            if (id.isEmpty()) continue;
            n++;
            ObjectNode vo = videos.addObject();
            vo.put("name", v.path("name").asText("").isBlank() ? "Video " + n : v.path("name").asText().trim());
            vo.put("streamableId", id);
        }
        if (videos.isEmpty()) throw new IllegalArgumentException("Añade al menos un vídeo de Streamable");

        ObjectNode out = RewardConfigStore.MAPPER.createObjectNode();
        out.put("rewardId", in.path("rewardId").asText("").trim());
        out.put("name", name);
        out.put("icon", in.path("icon").asText("").trim());
        out.put("cost", Math.max(0, in.path("cost").asInt(0)));
        String prompt = in.path("prompt").asText("").trim();
        out.put("prompt", prompt.length() > 200 ? prompt.substring(0, 200) : prompt);
        String color = in.path("backgroundColor").asText("").trim();
        out.put("backgroundColor", HEX_COLOR.matcher(color).matches() ? color : "");
        out.put("enabled", in.path("enabled").asBoolean(true));
        out.put("skipQueue", in.path("skipQueue").asBoolean(true));
        out.put("cooldownSeconds", Math.max(0, in.path("cooldownSeconds").asInt(0)));
        out.put("maxPerStream", Math.max(0, in.path("maxPerStream").asInt(0)));
        out.put("maxPerUserPerStream", Math.max(0, in.path("maxPerUserPerStream").asInt(0)));
        out.put("muted", in.path("muted").asBoolean(true));
        out.put("durationSeconds", Math.max(1, in.path("durationSeconds").asInt(15)));
        out.set("videos", videos);

        // campos extra que el usuario haya añadido a mano en el JSON
        Iterator<String> names = in.fieldNames();
        while (names.hasNext()) {
            String f = names.next();
            if (!out.has(f)) out.set(f, in.get(f));
        }
        return out;
    }

    /** Acepta la URL completa de Streamable o solo el ID. */
    static String extractStreamableId(String raw) {
        String s = raw == null ? "" : raw.trim();
        Matcher m = STREAMABLE_ID.matcher(s);
        if (m.find()) return m.group(1);
        return s.matches("[A-Za-z0-9]+") ? s : "";
    }

    /** Campos de nuestro JSON → cuerpo que espera Helix. */
    private ObjectNode twitchPayload(ObjectNode r, boolean creating) {
        ObjectNode p = RewardConfigStore.MAPPER.createObjectNode();
        p.put("title", r.path("name").asText());
        p.put("cost", r.path("cost").asInt());
        p.put("prompt", r.path("prompt").asText(""));
        p.put("is_enabled", r.path("enabled").asBoolean(true));
        p.put("should_redemptions_skip_request_queue", r.path("skipQueue").asBoolean(true));

        String color = r.path("backgroundColor").asText("");
        if (!color.isEmpty()) p.put("background_color", color);

        int cd = r.path("cooldownSeconds").asInt(0);
        p.put("is_global_cooldown_enabled", cd > 0);
        if (cd > 0) p.put("global_cooldown_seconds", cd);

        int ms = r.path("maxPerStream").asInt(0);
        p.put("is_max_per_stream_enabled", ms > 0);
        if (ms > 0) p.put("max_per_stream", ms);

        int mu = r.path("maxPerUserPerStream").asInt(0);
        p.put("is_max_per_user_per_stream_enabled", mu > 0);
        if (mu > 0) p.put("max_per_user_per_stream", mu);
        return p;
    }

    private Reply error(int status, String message) {
        ObjectNode o = RewardConfigStore.MAPPER.createObjectNode();
        o.put("ok", false);
        o.put("error", message);
        return new Reply(status, o.toString());
    }
}
