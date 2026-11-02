package com.discordvoicebridge.mixin;

import com.discordvoicebridge.DiscordVoiceBridgeMod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;

/**
 * Keeps the bot out of the TAB player list. A FakePlayer is a
 * ServerPlayer subclass, so if it were ever registered through the
 * normal join path it would be broadcast in
 * ClientboundPlayerInfoUpdatePacket(ADD_PLAYER) and show up in TAB on
 * every client. Cancel placeNewPlayer for that UUID. The fake player
 * itself is only spawned as a world entity and never goes into
 * PlayerList's map; this just makes the guarantee explicit.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(at = @At("HEAD"), method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V")
    private void dvc_rejectBridgeBot(Connection connection, ServerPlayer player,
                                     CommonListenerCookie cookie, CallbackInfo ci) {
        if (player.getUUID().equals(DiscordVoiceBridgeMod.BOT_UUID)) {
            ci.cancel();
        }
    }
}
