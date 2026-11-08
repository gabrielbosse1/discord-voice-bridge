package com.discordvoicebridge.command;

import com.discordvoicebridge.DiscordVoiceBridgeMod;
import com.discordvoicebridge.discord.DiscordBotManager;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;

/**
 * The <code>user</code> argument of the /dvc subcommands. Accepts the original
 * Discord username, either as a single word ({@code /dvc mute jockie}) or
 * double-quoted when it contains spaces ({@code /dvc mute "Jockie Music"}).
 *
 * Autocomplete offers the exact text that will parse: names with spaces are
 * suggested double-quoted (so accepting the suggestion produces a valid
 * command), plain names without. Works the same whether the user typed a
 * leading quote or not - the suggestion always replaces the whole argument.
 */
final class DvcUserArgument {

    static final ArgumentType<String> USER = reader -> reader.readString();

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
