package com.discordvoicebridge.mixin;

import com.discordvoicebridge.DiscordVoiceBridgeMod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Keeps the bot out of the TAB player list. A FakePlayer is a
 * ServerPlayerEntity subclass, so if it were ever registered through the
 * normal join path it would be broadcast in PlayerListS2CPacket(ADD_PLAYER)
 * and show up in TAB on every client. Cancel onPlayerConnect for that UUID.
 * The fake player itself is only spawned as a world entity and never goes
 * into PlayerManager.players; this just makes the guarantee explicit.
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Inject(at = @At("HEAD"), method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)V")
    private void dvc_rejectBridgeBot(ClientConnection connection, ServerPlayerEntity player,
                                     ConnectedClientData data, CallbackInfo ci) {
        if (player.getUuid().equals(DiscordVoiceBridgeMod.BOT_UUID)) {
            ci.cancel();
        }
    }
}
