package com.discordvoicebridge.discord;

import com.discordvoicebridge.Lang;
import com.discordvoicebridge.audio.AudioPipeline;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.function.Consumer;

/**
 * Headless Discord bot. Joins the configured voice channel and bridges PCM audio
 * in both directions:
 * <ul>
 *   <li>Discord -> Minecraft: combined stereo PCM is converted to mono and handed to
 *       the SVC side via the callback set with {@link #setMonoPcmHandler(Consumer)}.</li>
 *   <li>Minecraft -> Discord: stereo PCM frames are enqueued via
 *       {@link #playToDiscord(short[])} and drained by the JDA send handler.</li>
 * </ul>
 * No slash commands or message listeners are registered (voice-state intent only).
 */
public class DiscordBotManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("discord_voice_bridge");

    private final DiscordVoiceBridgeConfig config;
    private final AudioPipeline.StereoFrameQueue sendQueue = new AudioPipeline.StereoFrameQueue(100);
    private volatile Consumer<short[]> monoPcmHandler = mono -> { };
    private volatile boolean running = false;
    private volatile JDA jda;

    public DiscordBotManager(DiscordVoiceBridgeConfig config) {
        this.config = config;
    }

    /** SVC installs this: receives one mono PCM frame (960 samples = 20 ms @ 48 kHz) per Discord packet. */
    public void setMonoPcmHandler(Consumer<short[]> handler) {
        this.monoPcmHandler = handler != null ? handler : mono -> { };
    }

    /** Called by SVC whenever a group member speaks (stereo 48 kHz 16-bit shorts). */
    public void playToDiscord(short[] stereoPcm) {
        if (stereoPcm == null || stereoPcm.length == 0) {
            return;
        }
        sendQueue.offer(AudioPipeline.stereoToFrame(stereoPcm));
    }

    /**
     * Start is non-blocking: JDA has no ready callback, only the blocking
     * awaitReady(), so the wait and channel connect run on a daemon thread.
     */
    public void start() {
        if (running) {
            return;
        }
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            LOGGER.warn(Lang.get("discord_voice_bridge.error.missing_token"));
            return;
        }
        String channelId = config.getVoiceChannelId();
        if (channelId == null || channelId.isBlank()) {
            LOGGER.warn(Lang.get("discord_voice_bridge.error.missing_channel"));
            return;
        }

        running = true;
        LOGGER.info(Lang.get("discord_voice_bridge.startup.starting"));
        // Discord enforces E2EE/DAVE encrypted voice: the audio module must
        // carry a DAVE session factory (jdave) or the voice server closes the
        // connection with "E2EE/DAVE protocol required" in a reconnect loop.
        JDA bot = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                .build();
        jda = bot;
        Thread worker = new Thread(() -> {
            try {
                bot.awaitReady();
                connectVoice(channelId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop();
            } catch (Exception e) {
                LOGGER.error(Lang.get("discord_voice_bridge.error.bot_start_failed", e.getMessage()), e);
                stop();
            }
        }, "discord-voice-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectVoice(String channelId) {
        JDA self = jda;
        if (self == null || !running) {
            return;
        }

        VoiceChannel channel = null;
        Guild guild = null;
        for (Guild g : self.getGuilds()) {
            VoiceChannel vc = g.getVoiceChannelCache().getElementById(channelId);
            if (vc != null) {
                guild = g;
                channel = vc;
                break;
            }
        }
        if (channel == null) {
            LOGGER.error(Lang.get("discord_voice_bridge.error.channel_not_found", channelId));
            stop();
            return;
        }

        AudioManager manager = guild.getAudioManager();
        manager.setReceivingHandler(new AudioReceiveHandler() {
            @Override
            public boolean canReceiveCombined() {
                return true;
            }

            @Override
            public void handleCombinedAudio(CombinedAudio combined) {
                if (!running) {
                    return;
                }
                byte[] pcm = combined.getAudioData(1.0); // big-endian stereo PCM
                short[] stereo = new short[pcm.length / 2];
                for (int i = 0; i < stereo.length; i++) {
                    stereo[i] = (short) ((pcm[i * 2] << 8) | (pcm[i * 2 + 1] & 0xFF));
                }
                monoPcmHandler.accept(AudioPipeline.stereoToMono(stereo));
            }
        });
        manager.setSendingHandler(new AudioSendHandler() {
            @Override
            public boolean canProvide() {
                return true;
            }

            @Override
            public ByteBuffer provide20MsAudio() {
                byte[] frame = sendQueue.poll();
                return frame == null ? null : ByteBuffer.wrap(frame);
            }
        });
        manager.setAutoReconnect(true);
        manager.openAudioConnection(channel);

        LOGGER.info(Lang.get("discord_voice_bridge.startup.started", config.getGroupName()));
    }

    public void stop() {
        running = false;
        JDA self = jda;
        jda = null;
        if (self != null) {
            try {
                self.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running;
    }
}
