package com.discordvoicebridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every localised key must exist in both pt_br (default) and en_us (fallback)
 * with the same number of {0}/{1}... placeholders, so a missing translation can
 * never leak a raw key or a malformed format string at runtime.
 */
class LangParityTest {

    @Test
    void bothLangFilesHaveTheSameKeysAndPlaceholderCount() throws Exception {
        Map<String, String> pt = load("pt_br.json");
        Map<String, String> en = load("en_us.json");

        assertEquals(pt.keySet(), en.keySet(),
                "pt_br and en_us must define the exact same set of keys");
        for (Map.Entry<String, String> e : pt.entrySet()) {
            assertEquals(placeholders(e.getValue()), placeholders(en.get(e.getKey())),
                    "placeholder count differs for " + e.getKey());
        }
        assertTrue(!pt.isEmpty(), "lang files should not be empty");
    }

    private static Map<String, String> load(String name) throws Exception {
        String path = "assets/discord_voice_bridge/lang/" + name;
        try (InputStream in = LangParityTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "lang resource not found on classpath: " + path);
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> out = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
            return out;
        }
    }

    private static int placeholders(String value) {
        int count = 0;
        int idx = 0;
        while ((idx = value.indexOf('{', idx)) != -1) {
            int close = value.indexOf('}', idx);
            if (close != -1) {
                count++;
                idx = close + 1;
            }
        }
        return count;
    }
}
