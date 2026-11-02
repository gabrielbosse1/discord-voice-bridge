package com.discordvoicebridge.command;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.Lang;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;
import com.discordvoicebridge.discord.DiscordBotManager;

import me.lucko.fabric.api.permissions.v0.Permissions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /dvc config - admin-only, chat-based configuration panel with clickable,
 * hoverable Text components. No Discord commands exist; all configuration is
 * handled in-game.
 */
public class ConfigCommand {

    private static final String PERMISSION = "discord_voice_bridge.admin";

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandManager.RegistrationEnvironment environment) {
        if (!environment.dedicated) {
            return;
        }
        dispatcher.register(literal("dvc")
                .requires(Permissions.require(PERMISSION))
                .then(literal("config")
                        .executes(ConfigCommand::showPanel)
                        .then(literal("toggle")
                                .then(literal("enabled")
                                        .executes(ConfigCommand::toggleEnabled)))
                        .then(literal("reload")
                                .executes(ConfigCommand::reload))
                        .then(literal("status")
                                .executes(ConfigCommand::status))));
    }

    private static int showPanel(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordVoiceBridgeConfig cfg = DiscordVoiceBridgeMod.getConfig();
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();

        Text title = Text.literal(Lang.get("discord_voice_bridge.config.title"))
                .setStyle(Style.EMPTY.withColor(Formatting.GOLD).withBold(true));
        player.sendMessage(title);

        boolean enabled = cfg.isEnabled();
        String enabledValue = enabled ? Lang.get("discord_voice_bridge.config.enabled_true")
                : Lang.get("discord_voice_bridge.config.enabled_false");
        MutableText enabledLine = Text.literal(Lang.get("discord_voice_bridge.config.enabled", enabledValue))
                .setStyle(Style.EMPTY.withColor(enabled ? Formatting.GREEN : Formatting.RED));
        Text toggleBtn = button("[toggle]", "/dvc config toggle enabled",
                Lang.get("discord_voice_bridge.config.instruction"));
        player.sendMessage(enabledLine.append(Text.literal(" ")).append(toggleBtn));

        Text reloadBtn = button(Lang.get("discord_voice_bridge.config.reload"), "/dvc config reload",
                Lang.get("discord_voice_bridge.config.instruction"));
        player.sendMessage(reloadBtn);

        Text statusBtn = button(Lang.get("discord_voice_bridge.config.status"), "/dvc config status",
                Lang.get("discord_voice_bridge.config.instruction"));
        player.sendMessage(statusBtn);

        player.sendMessage(Text.literal(Lang.get("discord_voice_bridge.config.group_name", cfg.getGroupName())));
        player.sendMessage(Text.literal(Lang.get("discord_voice_bridge.config.bot_name", cfg.getBotDisplayName())));
        player.sendMessage(Text.literal(Lang.get("discord_voice_bridge.config.voice_channel",
                cfg.getVoiceChannelId() == null || cfg.getVoiceChannelId().isBlank()
                        ? Lang.get("discord_voice_bridge.error.missing_channel") : cfg.getVoiceChannelId())));

        String state = bot != null && bot.isRunning()
                ? Lang.get("discord_voice_bridge.config.status_online")
                : Lang.get("discord_voice_bridge.config.status_offline");
        player.sendMessage(Text.literal(state).setStyle(Style.EMPTY.withColor(Formatting.GRAY)));

        player.sendMessage(Text.literal(Lang.get("discord_voice_bridge.config.instruction")));
        return 1;
    }

    private static int toggleEnabled(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordVoiceBridgeMod.toggleEnabled();
        DiscordVoiceBridgeConfig cfg = DiscordVoiceBridgeMod.getConfig();
        player.sendMessage(Text.literal(
                cfg.isEnabled()
                        ? Lang.get("discord_voice_bridge.config.toggled_on")
                        : Lang.get("discord_voice_bridge.config.toggled_off"))
                .setStyle(Style.EMPTY.withColor(cfg.isEnabled() ? Formatting.GREEN : Formatting.RED)));
        return showPanel(ctx);
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordVoiceBridgeMod.reloadConfig();
        player.sendMessage(Text.literal(Lang.get("discord_voice_bridge.config.reloaded"))
                .setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
        return showPanel(ctx);
    }

    private static int status(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        String state = (bot != null && bot.isRunning())
                ? Lang.get("discord_voice_bridge.config.status_online")
                : Lang.get("discord_voice_bridge.config.status_offline");
        player.sendMessage(Text.literal(state));
        return showPanel(ctx);
    }

    private static Text button(String label, String command, String hover) {
        return Text.literal(label)
                .setStyle(Style.EMPTY.withColor(Formatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
    }
}
