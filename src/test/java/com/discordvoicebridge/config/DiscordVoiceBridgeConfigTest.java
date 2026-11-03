package com.discordvoicebridge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Round-trip test: save() must survive Java 25's module system (no reflective
 * access to the lock/path fields) and load() must restore every key.
 */
class DiscordVoiceBridgeConfigTest {

    @Test
    void saveThenLoadRoundTripsAllKeys(@TempDir Path dir) {
        Path file = dir.resolve("discord_voice_bridge.json");

        DiscordVoiceBridgeConfig cfg = new DiscordVoiceBridgeConfig(file);
        cfg.load(); // no file yet -> writes defaults (also proves save() doesn't throw)
        cfg.setToken("test-token");
        cfg.setVoiceChannelId("1234567890");
        cfg.setGroupName("Meu Grupo");
        cfg.setBotDisplayName("Bridge Bot");
        cfg.setBotSkinUrl("https://example.com/skin.png");
        cfg.setGroupType("ISOLATED");
        cfg.setGroupPassword("secret");
        cfg.setEnabled(false);
        cfg.save();

        DiscordVoiceBridgeConfig reloaded = new DiscordVoiceBridgeConfig(file);
        reloaded.load();
        assertEquals("test-token", reloaded.getToken());
        assertEquals("1234567890", reloaded.getVoiceChannelId());
        assertEquals("Meu Grupo", reloaded.getGroupName());
        assertEquals("Bridge Bot", reloaded.getBotDisplayName());
        assertEquals("https://example.com/skin.png", reloaded.getBotSkinUrl());
        assertEquals("ISOLATED", reloaded.getGroupType());
        assertEquals("secret", reloaded.getGroupPassword());
        assertFalse(reloaded.isEnabled());
    }

    @Test
    void loadIgnoresUnknownFieldsAndNulls(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("discord_voice_bridge.json");
        java.nio.file.Files.writeString(file,
                "{\"token\": null, \"enabled\": true, \"future_key\": \"x\"}",
                java.nio.charset.StandardCharsets.UTF_8);

        DiscordVoiceBridgeConfig cfg = new DiscordVoiceBridgeConfig(file);
        cfg.load();
        assertEquals("", cfg.getToken()); // null becomes the empty default
        assertEquals(true, cfg.isEnabled());
        assertEquals("Discord Voice Bridge", cfg.getGroupName());
    }
}
