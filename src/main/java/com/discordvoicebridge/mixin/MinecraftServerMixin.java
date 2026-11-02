package com.discordvoicebridge.mixin;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.command.ConfigCommand;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server lifecycle hooks, used instead of a DedicatedServerModInitializer
 * which is not available in this setup.
 * <ul>
 *   <li>{@code runServer} HEAD: register the {@code /dvc config} command callback early.</li>
 *   <li>{@code runTask} TAIL: one-time initialization on the first server tick (the
 *       world is loaded by then); starts the bot, creates the SVC group, spawns the
 *       fake player, registers the group-refresh tick.</li>
 *   <li>{@code stop(boolean)} / {@code shutdown} HEAD: tear the bridge down.</li>
 * </ul>
 * Only dedicated servers are bridged; integrated (single-player) servers are ignored.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private static boolean initialized = false;
    private static boolean commandRegistered = false;

    @Inject(at = @At("HEAD"), method = "runServer()V")
    private void onRunServer(CallbackInfo ci) {
        // Register the admin command callback before the command dispatcher is built.
        // A server restart in the same JVM runs runServer again, so only once.
        if (commandRegistered) {
            return;
        }
        commandRegistered = true;
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> ConfigCommand.register(dispatcher, environment));
    }

    @Inject(at = @At("TAIL"), method = "runTask()Z")
    private void onRunTask(CallbackInfo ci) {
        if (initialized) {
            return;
        }
        MinecraftServer self = (MinecraftServer) (Object) this;
        if (!self.isDedicated()) {
            return;
        }
        initialized = true;
        DiscordVoiceBridgeMod.initServer(self);
        LOGGER.info("Discord Voice Bridge: server initialized on first tick");
    }

    @Inject(at = @At("HEAD"), method = "stop(Z)V")
    private void onStop(CallbackInfo ci) {
        DiscordVoiceBridgeMod.stopServer();
    }

    @Inject(at = @At("HEAD"), method = "shutdown()V")
    private void onShutdown(CallbackInfo ci) {
        DiscordVoiceBridgeMod.stopServer();
    }
}
