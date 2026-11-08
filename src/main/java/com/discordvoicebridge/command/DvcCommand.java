package com.discordvoicebridge.command;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.Lang;
import com.discordvoicebridge.discord.DiscordBotManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /dvc - the player-facing bridge menu and full control surface.
 *
 * <p>{@code /dvc} (or {@code /dvc list}) prints the Discord voice channel's
 * occupants, each with a coloured status dot (green unmuted, red muted-for-you,
 * yellow not heard) and clickable buttons that adjust <b>this player's</b> mute
 * and volume per Discord user. The buttons and the {@code mute/unmute/volume/
 * reset} subcommands are equivalent; the subcommands take the Discord display
 * name (double-quoted when it has spaces) or the original username, with
 * autocomplete, so everything is controllable without clicking.
 *
 * <p>The admin {@code config} panel is registered under the same tree by
 * {@link ConfigCommand#configSubtree()}.</p>
 */
public class DvcCommand {

    /** PUA icon code points (see assets/minecraft/font + icon font). */
    private static final char DOT_GREEN = '\uE000';
    private static final char DOT_RED = '\uE001';
    private static final char DOT_YELLOW = '\uE002';
    private static final char RING = '\uE003';

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Commands.CommandSelection selection) {
        if (selection != Commands.CommandSelection.DEDICATED) {
            return;
        }
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("dvc")
                .executes(DvcCommand::menu)
                .then(literal("list").executes(DvcCommand::menu))
                .then(literal("mute").then(argument("user", DvcUserArgument.USER)
                        .suggests(DvcUserArgument.suggestions()).executes(DvcCommand::mute)))
                .then(literal("unmute").then(argument("user", DvcUserArgument.USER)
                        .suggests(DvcUserArgument.suggestions()).executes(DvcCommand::unmute)))
                .then(literal("volume")
                        .then(argument("user", DvcUserArgument.USER)
                                .suggests(DvcUserArgument.suggestions())
                                .then(argument("percent", FloatArgumentType.floatArg(0f, 200f))
                                        .executes(DvcCommand::volume))))
                .then(literal("reset").then(argument("user", DvcUserArgument.USER)
                        .suggests(DvcUserArgument.suggestions()).executes(DvcCommand::reset)))
                .then(ConfigCommand.configSubtree()));
    }

    // ----- menu -----

    private static int menu(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        List<DiscordBotManager.SenderInfo> users = bot != null ? bot.voiceUsers() : List.of();

        player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.menu.title"))
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)));
        if (users.isEmpty()) {
            player.sendSystemMessage(Component.literal(Lang.get("discord_voice_bridge.menu.no_users"))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        } else {
            for (DiscordBotManager.SenderInfo u : users) {
                sendSystemMenuLine(u, bot, player);
            }
        }
        return 1;
    }

    /**
     * One menu row: a coloured status dot, the Discord display name, the status
     * text, the personal volume (when not 100%), and clickable action buttons.
     */
    private static void sendSystemMenuLine(DiscordBotManager.SenderInfo u,
                                           DiscordBotManager bot, ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        long id = u.id();
        boolean mutedByMe = bot.isPlayerSenderMuted(playerUuid, id);
        float vol = bot.getPlayerSenderVolume(playerUuid, id);
        boolean overridden = bot.overriddenSenders(playerUuid).contains(id);
        boolean speaking = bot.isSpeaking(id);

        char dot;
        ChatFormatting statusColor;
        String status;
        if (mutedByMe) {
            dot = DOT_RED;
            statusColor = ChatFormatting.RED;
            status = Lang.get("discord_voice_bridge.menu.status_muted_you");
        } else if (u.muted()) {
            dot = DOT_RED;
            statusColor = ChatFormatting.RED;
            status = Lang.get("discord_voice_bridge.menu.status_muted_discord");
        } else if (!speaking) {
            dot = DOT_YELLOW;
            statusColor = ChatFormatting.YELLOW;
            status = Lang.get("discord_voice_bridge.menu.status_not_heard");
        } else {
            dot = DOT_GREEN;
            statusColor = ChatFormatting.GREEN;
            status = Lang.get("discord_voice_bridge.menu.status_unmuted");
        }

        MutableComponent row = Component.literal(String.valueOf(dot))
                .withStyle(Style.EMPTY.withColor(statusColor));
        row = row.append(Component.literal(" " + u.displayName())
                .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
        // The original Discord username (no spaces) is what /dvc commands and
        // autocomplete use - shown so it can be copied/typed even when the
        // display name has spaces. The hover shows the exact command form.
        row = row.append(userHover(u));
        if (u.bot()) {
            row = row.append(Component.literal(Lang.get("discord_voice_bridge.menu.bot_suffix"))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        }
        row = row.append(Component.literal("  [" + status + "]")
                .withStyle(Style.EMPTY.withColor(statusColor)));
        if (overridden) {
            row = row.append(Component.literal("  " + (int) Math.round(vol * 100) + "%")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        }
        // Buttons (right side). Display name is what the user sees; it is
        // double-quoted when it has spaces so the click command parses.
        String cmdUser = DvcUserArgument.cmdName(u.displayName());
        row = row.append(button(mutedByMe ? Lang.get("discord_voice_bridge.menu.btn_unmute")
                        : Lang.get("discord_voice_bridge.menu.btn_mute"),
                "/dvc " + (mutedByMe ? "unmute" : "mute") + " " + cmdUser,
                mutedByMe ? Lang.get("discord_voice_bridge.menu.hover_muted_on")
                        : Lang.get("discord_voice_bridge.menu.hover_muted_off"),
                mutedByMe ? ChatFormatting.GREEN : ChatFormatting.RED));
        int pct = (int) Math.round(vol * 100);
        row = row.append(button(" -10%", "/dvc volume " + cmdUser + " " + Math.max(0, pct - 10),
                Lang.get("discord_voice_bridge.menu.hover_vol_down"), ChatFormatting.YELLOW));
        row = row.append(button(" +10%", "/dvc volume " + cmdUser + " " + Math.min(200, pct + 10),
                Lang.get("discord_voice_bridge.menu.hover_vol_up"), ChatFormatting.YELLOW));
        if (overridden) {
            row = row.append(button(Lang.get("discord_voice_bridge.menu.btn_reset"),
                    "/dvc reset " + cmdUser, Lang.get("discord_voice_bridge.menu.hover_reset"),
                    ChatFormatting.GRAY));
        }
        player.sendSystemMessage(row);
    }

    private static Component button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal("  [" + label + "]")
                .withStyle(Style.EMPTY.withColor(color)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** The gray "(username)" part of a row, with the exact command form on hover. */
    private static Component userHover(DiscordBotManager.SenderInfo u) {
        String commandForm = DvcUserArgument.cmdName(u.displayName());
        String hover = Lang.get("discord_voice_bridge.menu.hover_username", commandForm);
        HoverEvent show = new HoverEvent.ShowText(Component.literal(hover));
        Style style = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withHoverEvent(show);
        return Component.literal(" (" + u.username() + ")").withStyle(style);
    }

    // ----- subcommands -----

    private static int mute(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        String name = ctx.getArgument("user", String.class);
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        Long id = bot != null ? bot.resolveName(name) : null;
        if (id == null) {
            return unknownUser(player, name);
        }
        bot.setPlayerSenderMuted(player.getUUID(), id, true);
        player.sendSystemMessage(msg(Lang.get("discord_voice_bridge.muted_you", bot.displayName(id)),
                ChatFormatting.RED));
        menu(ctx); // chat is append-only (no in-place edit), so re-send the fresh status
        return 1;
    }

    private static int unmute(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        String name = ctx.getArgument("user", String.class);
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        Long id = bot != null ? bot.resolveName(name) : null;
        if (id == null) {
            return unknownUser(player, name);
        }
        bot.setPlayerSenderMuted(player.getUUID(), id, false);
        player.sendSystemMessage(msg(Lang.get("discord_voice_bridge.unmuted_you", bot.displayName(id)),
                ChatFormatting.GREEN));
        menu(ctx); // re-send the fresh status (see mute)
        return 1;
    }

    private static int volume(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        String name = ctx.getArgument("user", String.class);
        float percent = FloatArgumentType.getFloat(ctx, "percent");
        if (percent < 0f || percent > 200f) {
            player.sendSystemMessage(msg(Lang.get("discord_voice_bridge.bad_volume"), ChatFormatting.RED));
            return 0;
        }
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        Long id = bot != null ? bot.resolveName(name) : null;
        if (id == null) {
            return unknownUser(player, name);
        }
        bot.setPlayerSenderVolume(player.getUUID(), id, percent / 100f);
        player.sendSystemMessage(msg(Lang.get("discord_voice_bridge.volume_set",
                        bot.displayName(id), (int) Math.round(percent)),
                ChatFormatting.GREEN));
        menu(ctx); // re-send the fresh status (see mute)
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOf(ctx);
        if (player == null) {
            return 0;
        }
        String name = ctx.getArgument("user", String.class);
        DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
        Long id = bot != null ? bot.resolveName(name) : null;
        if (id == null) {
            return unknownUser(player, name);
        }
        bot.clearPlayerSender(player.getUUID(), id);
        player.sendSystemMessage(msg(Lang.get("discord_voice_bridge.volume_reset", bot.displayName(id)),
                ChatFormatting.GREEN));
        menu(ctx); // re-send the fresh status (see mute)
        return 1;
    }

    // ----- helpers -----

    private static int unknownUser(ServerPlayer player, String name) {
        player.sendSystemMessage(msg(Lang.get("discord_voice_bridge.unknown_user", name), ChatFormatting.RED));
        return 0;
    }

    private static Component msg(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
    }

    private static ServerPlayer playerOf(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }
}
