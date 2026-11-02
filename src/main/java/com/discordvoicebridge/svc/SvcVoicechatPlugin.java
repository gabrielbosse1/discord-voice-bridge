package com.discordvoicebridge.svc;

import net.minecraft.server.MinecraftServer;

/**
 * Narrow interface implemented by the SVC integration class. The implementation is
 * loaded reflectively only when Simple Voice Chat is present, so this mod never
 * hard-depends on SVC classes at load time.
 */
public interface SvcVoicechatPlugin {
    void onServerStarted(MinecraftServer server);
    void onServerStopped(MinecraftServer server);
    void refreshPlayers(MinecraftServer server);
}
