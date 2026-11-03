package com.discordvoicebridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config README is what documents each JSON option for the admin,
 * so it must exist, be localized (no missing_translation markers) and
 * cover the groupPassword option.
 */
class ConfigReadmeTest {

    @Test
    void firstRunWritesLocalizedReadme(@TempDir Path dir) throws Exception {
        Lang.load();

        DiscordVoiceBridgeMod.writeConfigReadme(dir);

        Path readme = dir.resolve("README.md");
        assertTrue(Files.exists(readme));
        String content = Files.readString(readme, StandardCharsets.UTF_8);
        assertTrue(content.contains("groupPassword"));
        assertTrue(content.contains("token"));
        assertFalse(content.contains("missing_translation"),
                "README contains untranslated keys: " + content);
    }

    @Test
    void readmeIsNotOverwritten(@TempDir Path dir) throws Exception {
        Lang.load();
        DiscordVoiceBridgeMod.writeConfigReadme(dir);
        Path readme = dir.resolve("README.md");
        Files.writeString(readme, "my notes", StandardCharsets.UTF_8);

        DiscordVoiceBridgeMod.writeConfigReadme(dir);

        assertEquals("my notes", Files.readString(readme, StandardCharsets.UTF_8));
    }
}
