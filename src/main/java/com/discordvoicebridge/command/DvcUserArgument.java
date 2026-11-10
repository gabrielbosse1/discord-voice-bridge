package com.discordvoicebridge.command;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.discord.DiscordBotManager;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;

/**
 * The <code>user</code> argument of the /dvc subcommands. Accepts the original
 * Discord username, either as a single word ({@code /dvc mute jockie}) or
 * double-quoted when it contains spaces ({@code /dvc mute "Jockie Music"}).
 *
 * The argument itself is the vanilla {@code StringArgumentType.greedyString()}
 * (used by {@link DvcCommand}): the server serializes the whole command tree to
 * every joining client, and only vanilla argument types are known to
 * {@code ArgumentTypeInfos} - a custom {@code ArgumentType} lambda makes every
 * join fail with "Invalid player data". This class only carries the
 * suggestions and the command-form helper.
 *
 * Autocomplete offers the exact text that will parse: names with spaces are
 * suggested double-quoted (so accepting the suggestion produces a valid
 * command), plain names without. Works the same whether the user typed a
 * leading quote or not - the suggestion always replaces the whole argument.
 */
final class DvcUserArgument {

    static SuggestionProvider<CommandSourceStack> suggestions() {
        return (ctx, builder) -> {
            DiscordBotManager bot = DiscordVoiceBridgeMod.getDiscordBot();
            if (bot != null) {
                for (DiscordBotManager.SenderInfo u : bot.voiceUsers()) {
                    builder.suggest(cmdName(u.username()));
                    builder.suggest(cmdName(u.displayName()));
                }
            }
            return builder.buildFuture();
        };
    }

    /** The command text for a user: double-quoted when it can break parsing. */
    static String cmdName(String name) {
        return name.contains(" ") ? quote(name) : name;
    }

    private static String quote(String name) {
        return '"' + name.replace("\"", "\\\"") + '"';
    }

    private DvcUserArgument() {
    }
}
