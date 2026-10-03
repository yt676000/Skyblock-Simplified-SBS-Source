/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import sbs.modid.client.core.command.CommandRegistry;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.helper.build.command.BuildCommandTree;

/**
 * Registers {@code /sbs} and its subcommands into the client's command tree, so they are offered in
 * the suggestion strip above the chat box, complete on Tab, and no longer typed in red.
 *
 * <p>The client rebuilds its dispatcher from the server's {@link ClientboundCommandsPacket} in
 * {@code handleCommands}; this appends to the freshly built tree at TAIL, so it survives every
 * rebuild. Execution still happens in the chat mixin, which recognises the text and cancels before
 * anything reaches the server - these nodes exist purely so the client knows the commands are real
 * and can suggest them. The {@code executes} stubs are never run.
 *
 * <p><b>The commands come from {@link sbs.modid.client.core.command.CommandRegistry}.</b> This
 * used to be a table of its own, which had drifted to about half of what {@code SBSCommands}
 * answers to, so most commands never completed while typing. The registry is now the one list, a
 * test fails the build when it and the dispatch disagree, and this only turns its PUBLIC entries into
 * nodes. Entries whose suggestion spec is {@code null} are built by hand below (live name lists for
 * Player Notes, the channel-then-note shape of {@code /sendcoords}) or deliberately not suggested
 * (the built-in short commands, which were never in this tree).
 *
 * <p><b>Developer commands are deliberately absent:</b> {@code /sbs developermode} (whose whole
 * contract is to be silent and unsuggestable - completing it would announce a hidden toggle to every
 * player), {@code /sbstest}, {@code /sbsdev} and {@code /sbs terminaltest}, which is a solver
 * self-check rather than something to play with.
 */
@Mixin(ClientPacketListener.class)
public class ClientCommandsMixin {

    @Shadow
    private CommandDispatcher<SharedSuggestionProvider> commands;

    @Inject(method = "handleCommands", at = @At("TAIL"))
    private void skyblockSimplified$registerClientCommands(ClientboundCommandsPacket packet, CallbackInfo ci) {
        LiteralArgumentBuilder<SharedSuggestionProvider> sbs =
                LiteralArgumentBuilder.<SharedSuggestionProvider>literal("sbs").executes(context -> 0);
        // PUBLIC entries only: developer and hidden commands never reach the suggestion tree.
        for (CommandRegistry.Command command : CommandRegistry.visible(false)) {
            if (command.tab() == null) {
                continue;   // built by hand below, or deliberately not suggested
            }
            if (command.root().equals("sbs") && !command.name().isEmpty()) {
                for (String name : command.shownNames()) {
                    sbs.then(skyblockSimplified$node(name, command.tab(), command.argumentName()));
                }
            } else if (command.name().isEmpty()) {
                for (String root : command.shownNames()) {
                    this.commands.register(skyblockSimplified$node(root, command.tab(), command.argumentName()));
                }
            }
        }
        skyblockSimplified$playerNotes(sbs);
        this.commands.register(sbs);

        // Build Tools: /.. and its verbs. Saved names come from the library's cached listing and
        // block ids from the registry - both read on the client, nothing asked of the server.
        this.commands.register(BuildCommandTree.<SharedSuggestionProvider>build(
                BuildLibrary::slugsForCompletion, BuildCommandTree::registryBlockIds));
        // The // verbs: one "/<verb>" root each. A server may declare the same root (a server-side
        // build plugin would); register() merges into it, and the chat path only claims the line
        // while Build Tools is on - so the clash is logged once here, never silently taken over.
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (LiteralArgumentBuilder<SharedSuggestionProvider> root : BuildCommandTree.<SharedSuggestionProvider>slashRoots(
                BuildLibrary::slugsForCompletion, BuildCommandTree::registryBlockIds)) {
            if (this.commands.getRoot().getChild(root.getLiteral()) != null) {
                declared.add(root.getLiteral());
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Build] The server also declares /{} - "
                        + "Build Tools claims it only while the module is on", root.getLiteral());
            }
            this.commands.register(root);
        }
        BuildCommandTree.setServerDeclared(declared);
        // /sendcoords, under both its names: the four channels suggest, and each one takes a free-text
        // note after it. The alias is registered identically on purpose - one that never completes is
        // one nobody discovers, which defeats the point of having it.
        for (String alias : new String[] {"sendcoords", "sbssendcoords"}) {
            LiteralArgumentBuilder<SharedSuggestionProvider> coords =
                    LiteralArgumentBuilder.<SharedSuggestionProvider>literal(alias)
                            .executes(context -> 0);
            for (String channel : new String[] {"party", "guild", "coop", "all"}) {
                coords.then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal(channel)
                        .then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                                        "note", StringArgumentType.greedyString())
                                .executes(context -> 0))
                        .executes(context -> 0));
            }
            this.commands.register(coords);
        }
    }

    /**
     * Player Notes. Not table rows, because the name argument completes from live data: your notes
     * plus the players the client can see (tab list and world), and {@code note remove} from your
     * notes alone. The suggestion providers run on the client and ask the server nothing.
     */
    @Unique
    private static void skyblockSimplified$playerNotes(LiteralArgumentBuilder<SharedSuggestionProvider> root) {
        SuggestionProvider<SharedSuggestionProvider> anyName = (context, builder) ->
                SharedSuggestionProvider.suggest(
                        sbs.modid.client.social.notes.logic.PlayerNotesCommands.suggestableNames(), builder);
        SuggestionProvider<SharedSuggestionProvider> notedName = (context, builder) ->
                SharedSuggestionProvider.suggest(
                        sbs.modid.client.social.notes.logic.PlayerNotesCommands.notedNames(), builder);
        for (String verb : new String[] {"note", "avoid", "trust"}) {
            LiteralArgumentBuilder<SharedSuggestionProvider> node =
                    LiteralArgumentBuilder.<SharedSuggestionProvider>literal(verb).executes(context -> 0);
            if (verb.equals("note")) {
                node.then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("remove")
                        .then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                                        "player", StringArgumentType.word())
                                .suggests(notedName).executes(context -> 0))
                        .executes(context -> 0));
            }
            node.then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                            "player", StringArgumentType.word())
                    .suggests(anyName)
                    .executes(context -> 0)
                    .then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                            "text", StringArgumentType.greedyString()).executes(context -> 0)));
            root.then(node);
        }
        root.then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("notes")
                .executes(context -> 0)
                .then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                        "search", StringArgumentType.greedyString()).executes(context -> 0)));
    }

    /** One subcommand node, with whatever its {@code follows} column asks for. */
    @Unique
    private static LiteralArgumentBuilder<SharedSuggestionProvider> skyblockSimplified$node(
            String name, String follows, String argumentName) {
        LiteralArgumentBuilder<SharedSuggestionProvider> node =
                LiteralArgumentBuilder.<SharedSuggestionProvider>literal(name).executes(context -> 0);
        if (follows == null || follows.isEmpty()) {
            return node;   // the command stands alone
        }
        if (follows.equals("<word>")) {
            return node.then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                    argumentName, StringArgumentType.word()).executes(context -> 0));
        }
        if (follows.equals("<text>")) {
            return node.then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument(
                    "text", StringArgumentType.greedyString()).executes(context -> 0));
        }
        // Fixed choices: registered as literals so they are suggested in turn once the subcommand
        // is typed - the difference between knowing a command exists and knowing how to use it.
        for (String choice : follows.split("\\|")) {
            node.then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal(choice)
                    .executes(context -> 0));
        }
        return node;
    }
}
