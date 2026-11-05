package com.discordvoicebridge.command;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.Lang;
import com.discordvoicebridge.config.DiscordVoiceBridgeConfig;
import com.discordvoicebridge.discord.DiscordBotManager;

import me.lucko.fabric.api.permissions.v0.Permissions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import static net.minecraft.commands.Commands.literal;

/**
 * /dvc config - admin-only, chat-based configuration panel with clickable,
 * hoverable components. No Discord commands exist; all configuration is
 * handled in-game.
 */
public class ConfigCommand {

    private static final String PERMISSION = "discord_voice_bridge.admin";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Commands.CommandSelection selection) {
        if (selection != Commands.CommandSelection.DEDICATED) {
            return;
        }
        // PermissionLevel.OWNERS (4) = the default /op level. Without the level
        // fallback, require(PERMISSION) denies EVERYONE on servers without a
        // permission provider (no LuckPerms), and Brigadier then hides the
        // command entirely, so it looks "unknown" even for OPs.
        dispatcher.register(literal("dvc")
                .requires(Permissions.require(PERMISSION, PermissionLevel.OWNERS))
                .executes(ConfigCommand::showPanel)
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

    private static int showPanel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DiscordVoiceBridgeConfig cfg = DiscordVoiceBridgeMod.getConfig();
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();

        Component title = Component.literal(Lang.get("discord_voice_bridge.config.title"))
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true));
        player.sendSystemMessage(title);

        boolean enabled = cfg.isEnabled();
        String enabledValue = enabled ? Lang.get("discord_voice_bridge.config.enabled_true")
                : Lang.get("discord_voice_bridge.config.enabled_false");
        MutableComponent enabledLine = Component.literal(Lang.get("discord_voice_bridge.config.enabled", enabledValue))
                .withStyle(Style.EMPTY.withColor(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
        Component toggleBtn = button("[toggle]", "/dvc config toggle enabled",
                Lang.get("discord_voice_bridge.config.instruction"));
        player.sendSystemMessage(enabledLine.append(Component.literal(" ")).append(toggleBtn));

        Component reloadBtn = button(Lang.get("discord_voice_bridge.config.reload"), "/dvc config reload",
                Lang.get("discord_voice_bridge.config.instruction"));
        player.sendSystemMessage(reloadBtn);

        Component statusBtn = button(Lang.get("discord_voice_bridge.config.status"), "/dvc config status",
                Lang.get("discord_voice_bridge.config.instruction"));
        player.sendSystemMessage(statusBtn);

        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.config.group_name", cfg.getGroupName())));
        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.config.bot_name", cfg.getBotDisplayName())));
        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.config.voice_channel",
                cfg.getVoiceChannelId() == null || cfg.getVoiceChannelId().isBlank()
                        ? Lang.get("discord_voice_bridge.error.missing_channel") : cfg.getVoiceChannelId())));
        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.config.file", cfg.getPath()))
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));

        String state = bot != null && bot.isRunning()
                ? Lang.get("discord_voice_bridge.config.status_online")
                : Lang.get("discord_voice_bridge.config.status_offline");
        player.sendSystemMessage(Component.literal(state).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));

        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.config.instruction")));
        return 1;
    }

    private static int toggleEnabled(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DiscordVoiceBridgeMod.toggleEnabled();
        DiscordVoiceBridgeConfig cfg = DiscordVoiceBridgeMod.getConfig();
        player.sendSystemMessage(Component.literal(
                cfg.isEnabled()
                        ? Lang.get("discord_voice_bridge.config.toggled_on")
                        : Lang.get("discord_voice_bridge.config.toggled_off"))
                .withStyle(Style.EMPTY.withColor(cfg.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return showPanel(ctx);
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DiscordVoiceBridgeMod.reloadConfig();
        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.config.reloaded"))
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        return showPanel(ctx);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        String state = (bot != null && bot.isRunning())
                ? Lang.get("discord_voice_bridge.config.status_online")
                : Lang.get("discord_voice_bridge.config.status_offline");
        player.sendSystemMessage(Component.literal(state));
        return showPanel(ctx);
    }

    private static Component button(String label, String command, String hover) {
        return Component.literal(label)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
