package com.discordvoicebridge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves translation keys. Portuguese (Brazil) is the default language;
 * English (US) is the fallback. Resolution happens server-side so chat output
 * is always human-readable even for clients that do not have this mod.
 */
public final class Lang {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");
    private static final String DEFAULT_LOCALE = "pt_br";
    private static final String FALLBACK_LOCALE = "en_us";

    private static final Map<String, Map<String, String>> STRINGS = new HashMap<>();
    private static boolean loaded = false;

    private Lang() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loadLocale(DEFAULT_LOCALE);
        loadLocale(FALLBACK_LOCALE);
        loaded = true;
        LOGGER.info("Localization loaded (default: pt_br, fallback: en_us)");
    }

    private static void loadLocale(String locale) {
        Map<String, String> map = new HashMap<>();
        String path = "assets/discord_voice_bridge/lang/" + locale + ".json";
        try (InputStream in = Lang.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.warn("Lang file not found: {}", path);
                return;
            }
            JsonElement root = JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load lang {}", locale, e);
        }
        STRINGS.put(locale, map);
    }

    /**
     * Resolve a key with optional {@code {0}, {1}, ...} placeholders.
     */
    public static String get(String key, Object... args) {
        Objects.requireNonNull(key);
        String template = resolveTemplate(key);
        if (template == null) {
            return "missing_translation:" + key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    private static String resolveTemplate(String key) {
        Map<String, String> def = STRINGS.get(DEFAULT_LOCALE);
        if (def != null && def.containsKey(key)) {
            return def.get(key);
        }
        Map<String, String> fb = STRINGS.get(FALLBACK_LOCALE);
        if (fb != null && fb.containsKey(key)) {
            return fb.get(key);
        }
        return null;
    }
}
