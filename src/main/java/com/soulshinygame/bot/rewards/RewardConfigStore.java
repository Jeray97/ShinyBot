package com.soulshinygame.bot.rewards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lee y escribe meme_videos.json ({ "rewards": [ ... ] }).
 * Usa el árbol JSON de Jackson para no perder campos que no conozcamos.
 */
public class RewardConfigStore {

    public static final String FILENAME = "meme_videos.json";
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path path = Path.of(FILENAME);

    public synchronized ArrayNode load() throws IOException {
        if (!Files.exists(path)) return MAPPER.createArrayNode();
        var root = MAPPER.readTree(Files.readString(path));
        if (root != null && root.has("rewards") && root.get("rewards").isArray()) {
            return (ArrayNode) root.get("rewards");
        }
        return MAPPER.createArrayNode();
    }

    public synchronized void save(ArrayNode rewards) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("rewards", rewards);
        Files.writeString(path, MAPPER.writeValueAsString(root));
    }
}
