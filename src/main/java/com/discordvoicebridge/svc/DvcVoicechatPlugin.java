package com.discordvoicebridge.svc;

import com.discordvoicebridge.DiscordVoiceBridgeMod;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

/**
 * Simple Voice Chat plugin entrypoint, declared in fabric.mod.json under the
 * {@code voicechat} entrypoint. SVC resolves it via
 * {@code FabricLoader.getEntrypointContainers("voicechat", VoicechatPlugin.class)},
 * so this class is only loaded when SVC is present - keeping the mod free of a
 * hard dependency on SVC at load time (the same rule as the reflectively-loaded
 * {@link SvcBridge}).
 *
 * It subscribes to {@link MicrophonePacketEvent}, which fires for every player's
 * microphone packet with the sender, and forwards it to the bridge. {@code
 * PlayerAudioListener} cannot capture a speaker's own voice: it only delivers the
 * audio a player receives from others (wrapped as Static/Entity sound), never the
 * player's own {@code MicrophonePacket}, and it skips the speaker's own packets -
 * so a solo group member's mic is never captured through it.
 */
public class DvcVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "discord_voice_bridge";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicPacket);
    }

    private void onMicPacket(MicrophonePacketEvent event) {
        if (DiscordVoiceBridgeMod.getSvcPlugin() instanceof SvcBridge bridge) {
            bridge.onMicPacket(event);
        }
    }
}
