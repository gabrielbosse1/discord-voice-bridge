package com.discordvoicebridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe mod configuration persisted to JSON.
 * All keys and logic are in English; only user-facing output is localized.
 */
public class DiscordVoiceBridgeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // transient: Gson must not try to (de)serialize these. Reflecting into
    // ReentrantReadWriteLock/Path fails under Java 25's module system and
    // crashed the server on first save() with a JsonIOException.
    private final transient Path configPath;
    private final transient ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ----- configuration keys (English, never localized) -----
    private String token = "";
    private String voiceChannelId = "";
    private String groupName = "Discord Voice Bridge";
    private String groupType = "NORMAL"; // NORMAL | ISOLATED
    private String groupPassword = ""; // empty = no password (public group)
    private java.util.List<String> legacyUserIds = new java.util.ArrayList<>(); // Discord users still on unencrypted voice
    private boolean enabled = true;

    public DiscordVoiceBridgeConfig(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath);
    }

    public void load() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(configPath)) {
                save(); // create defaults
            }
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            DiscordVoiceBridgeConfig parsed = GSON.fromJson(json, DiscordVoiceBridgeConfig.class);
            if (parsed == null) {
                throw new JsonSyntaxException("config parsed to null");
            }
            this.token = parsed.token == null ? "" : parsed.token;
            this.voiceChannelId = parsed.voiceChannelId == null ? "" : parsed.voiceChannelId;
            this.groupName = parsed.groupName == null ? "Discord Voice Bridge" : parsed.groupName;
            this.groupType = parsed.groupType == null ? "NORMAL" : parsed.groupType;
            this.groupPassword = parsed.groupPassword == null ? "" : parsed.groupPassword;
            this.legacyUserIds = parsed.legacyUserIds == null ? new java.util.ArrayList<>() : parsed.legacyUserIds;
            this.enabled = parsed.enabled;
            LOGGER.info("Loaded configuration from {}", configPath);
        } catch (JsonSyntaxException | IOException e) {
            LOGGER.warn("Failed to load config from {}, using defaults: {}", configPath, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void save() {
        lock.writeLock().lock();
        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(this);
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", configPath, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ----- getters (read lock) -----

    public String getToken() {
        lock.readLock().lock();
        try { return token; } finally { lock.readLock().unlock(); }
    }

    public void setToken(String token) {
        lock.writeLock().lock();
        try { this.token = token; } finally { lock.writeLock().unlock(); }
    }

    public String getVoiceChannelId() {
        lock.readLock().lock();
        try { return voiceChannelId; } finally { lock.readLock().unlock(); }
    }

    public void setVoiceChannelId(String voiceChannelId) {
        lock.writeLock().lock();
        try { this.voiceChannelId = voiceChannelId; } finally { lock.writeLock().unlock(); }
    }

    public String getGroupName() {
        lock.readLock().lock();
        try { return groupName; } finally { lock.readLock().unlock(); }
    }

    public void setGroupName(String groupName) {
        lock.writeLock().lock();
        try { this.groupName = groupName; } finally { lock.writeLock().unlock(); }
    }

    /** Discord user IDs still transmitting unencrypted (legacy) voice, as strings. */
    public java.util.List<String> getLegacyUserIds() {
        lock.readLock().lock();
        try { return new java.util.ArrayList<>(legacyUserIds); } finally { lock.readLock().unlock(); }
    }

    public void setLegacyUserIds(java.util.List<String> legacyUserIds) {
        lock.writeLock().lock();
        try { this.legacyUserIds = legacyUserIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(legacyUserIds); }
        finally { lock.writeLock().unlock(); }
    }

    public String getGroupType() {
        lock.readLock().lock();
        try { return groupType; } finally { lock.readLock().unlock(); }
    }

    public void setGroupType(String groupType) {
        lock.writeLock().lock();
        try { this.groupType = groupType; } finally { lock.writeLock().unlock(); }
    }

    /** Absolute path of the JSON file, for display in the admin panel. */
    public String getPath() {
        return configPath.toString();
    }

    public String getGroupPassword() {
        lock.readLock().lock();
        try { return groupPassword; } finally { lock.readLock().unlock(); }
    }

    public void setGroupPassword(String groupPassword) {
        lock.writeLock().lock();
        try { this.groupPassword = groupPassword; } finally { lock.writeLock().unlock(); }
    }

    public boolean isEnabled() {
        lock.readLock().lock();
        try { return enabled; } finally { lock.readLock().unlock(); }
    }

    public void setEnabled(boolean enabled) {
        lock.writeLock().lock();
        try { this.enabled = enabled; } finally { lock.writeLock().unlock(); }
    }
}
