package com.discordvoicebridge.mixin;

import com.discordvoicebridge.DiscordVoiceBridgeMod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;

/**
 * Keeps the bridge bot out of the sleep count. The bot is a
 * ServerPlayer (Fabric FakePlayer) that lives in the overworld's
 * player list, and SleepStatus.update counts every non-spectator in
 * that list as an active player - so with a single real player the
 * message reads "1 of 2 players are sleeping". Rewrite the list
 * parameter before it is counted; the bot stays in the world as a
 * normal (visible, non-spectator) voice avatar and can never sleep,
 * so the sleeping numerator is unaffected.
 */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> dvc_excludeBridgeBot(List<ServerPlayer> players) {
        if (players.stream().anyMatch(p -> p.getUUID().equals(DiscordVoiceBridgeMod.BOT_UUID))) {
            return players.stream()
                    .filter(p -> !p.getUUID().equals(DiscordVoiceBridgeMod.BOT_UUID))
                    .toList();
        }
        return players;
    }
}
