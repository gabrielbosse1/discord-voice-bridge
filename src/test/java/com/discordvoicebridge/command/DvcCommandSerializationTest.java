package com.discordvoicebridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The server sends the whole command tree to every joining client, and only
 * argument types that Minecraft knows about can be serialized in that packet.
 * A mod-defined {@code ArgumentType} (the original lambda-based user argument
 * of /dvc) makes the sync packet throw, failing every join with "Invalid
 * player data". This test fails the build if any argument type in the /dvc
 * tree comes from our own package again.
 */
class DvcCommandSerializationTest {

    @BeforeAll
    static void bootstrapRegistries() {
        // Commands' static initializers read vanilla registries, which are only
        // filled after this call (the real server does the same on startup).
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();
    }

    @Test
    void dvcTreeUsesOnlyVanillaArgumentTypes() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DvcCommand.register(dispatcher, Commands.CommandSelection.DEDICATED);

        assertFalse(dispatcher.getRoot().getChildren().isEmpty(),
                "/dvc was not registered on the dispatcher");

        List<Class<?>> argumentTypes = new ArrayList<>();
        collectArgumentTypes(dispatcher.getRoot(), argumentTypes);
        assertFalse(argumentTypes.isEmpty(), "no arguments found under /dvc - walk is broken");

        for (Class<?> type : argumentTypes) {
            assertFalse(type.getName().startsWith("com.discordvoicebridge"),
                    "mod-defined ArgumentType in the /dvc tree breaks command sync (\"Invalid player data\" on join): "
                            + type);
        }
    }

    private static void collectArgumentTypes(CommandNode<CommandSourceStack> node, List<Class<?>> types) {
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            types.add(argument.getType().getClass());
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            collectArgumentTypes(child, types);
        }
    }
}
