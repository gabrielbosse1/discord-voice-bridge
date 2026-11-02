package com.discordvoicebridge;

import com.discordvoicebridge.command.ConfigCommand;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;
import com.discordvoicebridge.discord.DiscordBotManager;
import com.discordvoicebridge.svc.SvcVoicechatPlugin;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Main mod class. Server-side only, so the entry point is
 * {@link DedicatedServerModInitializer}: dedicated servers call it,
 * integrated (single-player) servers never do.
 * <ul>
 *   <li>On start: loads config, starts the Discord bot, initializes the
 *       SVC side (group, fake player, audio channels) once the server
 *       is fully up (world loaded).</li>
 *   <li>Every tick: refreshes the set of in-group players so MC to
 *       Discord audio follows group membership.</li>
 *   <li>On stop: tears the bridge down.</li>
 * </ul>
 */
public class DiscordVoiceBridgeMod implements DedicatedServerModInitializer {

    public static final String MOD_ID = "discord_voice_bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Stable UUID of the bridge bot. Used by the TAB-mixin and the fake player. */
    public static final UUID BOT_UUID = UUID.nameUUIDFromBytes("discord_voice_bridge:bot".getBytes(StandardCharsets.UTF_8));

    private static DiscordVoiceBridgeConfig config;
    private static DiscordBotManager discordBot;
    private static SvcVoicechatPlugin svcPlugin;
    private static MinecraftServer currentServer;

    @Override
    public void onInitializeServer() {
        Lang.load();

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> ConfigCommand.register(dispatcher, environment));

        ServerLifecycleEvents.SERVER_STARTED.register(DiscordVoiceBridgeMod::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            currentServer = null;
            stopServer();
        });
    }

    private static void onServerStarted(MinecraftServer server) {
        currentServer = server;

        Path configDir = server.getServerDirectory().resolve("config").resolve(MOD_ID);
        config = new DiscordVoiceBridgeConfig(configDir.resolve("discord_voice_bridge.json"));
        config.load();

        discordBot = new DiscordBotManager(config);

        try {
            // Class.forName on SVC's bootstrap class: fails cleanly when SVC is absent.
            Class.forName("de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager");
            Class<?> impl = Class.forName("com.discordvoicebridge.svc.SvcBridge");
            svcPlugin = (SvcVoicechatPlugin) impl.getConstructor(DiscordVoiceBridgeConfig.class).newInstance(config);
            svcPlugin.onServerStarted(server);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("{} is not installed: {}", "voicechat",
                    Lang.get("discord_voice_bridge.error.voicechat_not_found"));
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize Simple Voice Chat bridge", t);
        }

        ServerTickEvents.END_SERVER_TICK.register(event -> svcPlugin.refreshPlayers(server));

        if (config.isEnabled()) {
            discordBot.start();
        }
        LOGGER.info("Discord Voice Bridge: initialized");
    }

    public static void stopServer() {
        if (discordBot != null) {
            discordBot.stop();
        }
        if (svcPlugin != null) {
            svcPlugin.onServerStopped(currentServer);
            svcPlugin = null;
        }
        LOGGER.info(Lang.get("discord_voice_bridge.startup.stopped"));
    }

    /**
     * Applies the {@code enabled} flag to the live Discord connection: starts the
     * bridge when enabled, stops it when disabled. Called by the admin command's
     * toggle/reload actions so those buttons actually take effect, not just edit JSON.
     */
    public static void applyConfig() {
        if (config == null || discordBot == null) {
            return;
        }
        if (config.isEnabled()) {
            if (!discordBot.isRunning()) {
                discordBot.start();
            }
        } else {
            discordBot.stop();
        }
    }

    /** Toggles the enabled flag, persists it and applies it to the live connection. */
    public static void toggleEnabled() {
        if (config == null || discordBot == null) {
            return;
        }
        config.setEnabled(!config.isEnabled());
        config.save();
        applyConfig();
    }

    /** Reloads the config from disk and re-applies the enabled state. */
    public static void reloadConfig() {
        if (config == null) {
            return;
        }
        config.load();
        applyConfig();
    }

    public static DiscordVoiceBridgeConfig getConfig() {
        return config;
    }

    public static DiscordBotManager getDiscordBot() {
        return discordBot;
    }

    public static SvcVoicechatPlugin getSvcPlugin() {
        return svcPlugin;
    }
}
