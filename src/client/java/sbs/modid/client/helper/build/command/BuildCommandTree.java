/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The {@code //} command nodes (one {@code /<verb>} root each) and the {@code /..} alias tree for the
 * client's command dispatcher - suggestions and Tab completion
 * only. Execution never goes through these nodes: the chat mixin hands the line to
 * {@link BuildCommands} and cancels it, so every {@code executes} here is a stub.
 *
 * <p><b>{@code ..} is a valid literal.</b> Brigadier does not validate node names, and the root
 * lookup splits on the first space, so {@code ..} is a key like any other. Verified 2026-09-25
 * against the game's own {@code brigadier-1.3.10}: {@code /..} executes, a greedy argument after it
 * receives {@code 50%stone,50%andesite} intact, {@code //s} completes to {@code set}, and
 * {@code /...} is an unknown command. {@code BuildCommandTreeTest} keeps it true.
 *
 * <p>Generic in the source type and fed its live data (saved names, block ids) through suppliers, so
 * the test builds the same tree against a plain dispatcher without the game.
 */
public final class BuildCommandTree {

    /** The root literal. */
    public static final String ROOT = "..";

    private static final List<String> DIRECTIONS = List.of("up", "down", "north", "south", "east", "west");

    private BuildCommandTree() {
    }

    /**
     * @param savedNames slugs of saved builds, for save/load/delete
     * @param blockIds   block ids, for the edit commands
     */
    public static <S> LiteralArgumentBuilder<S> build(Supplier<Collection<String>> savedNames,
                                                      Supplier<Collection<String>> blockIds) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal(ROOT).executes(context -> 0);
        SuggestionProvider<S> names = (context, builder) -> suggest(savedNames.get(), builder);
        SuggestionProvider<S> blocks = (context, builder) -> suggestBlockList(blockIds.get(), builder);
        for (BuildCommand command : BuildCommand.values()) {
            root.then(node(command.word(), command, names, blocks));
        }
        return root;
    }

    /**
     * One root literal per verb, named {@code /<verb>}: what {@code //set stone} is parsed against,
     * since the chat hands the client the line without its first slash. The primary form.
     */
    public static <S> List<LiteralArgumentBuilder<S>> slashRoots(Supplier<Collection<String>> savedNames,
                                                                 Supplier<Collection<String>> blockIds) {
        SuggestionProvider<S> names = (context, builder) -> suggest(savedNames.get(), builder);
        SuggestionProvider<S> blocks = (context, builder) -> suggestBlockList(blockIds.get(), builder);
        List<LiteralArgumentBuilder<S>> out = new java.util.ArrayList<>();
        for (BuildCommand command : BuildCommand.values()) {
            out.add(node("/" + command.word(), command, names, blocks));
        }
        return out;
    }

    private static <S> LiteralArgumentBuilder<S> node(String name, BuildCommand command, SuggestionProvider<S> names,
                                                      SuggestionProvider<S> blocks) {
        LiteralArgumentBuilder<S> node = LiteralArgumentBuilder.<S>literal(name).executes(context -> 0);
        return switch (command) {
            case EXPAND, CONTRACT -> node.then(BuildCommandTree.<S>integer("n").executes(context -> 0)
                    .then(word("dir", concat(DIRECTIONS, List.of("all")))));
            case SHIFT -> node.then(BuildCommandTree.<S>integer("n").executes(context -> 0).then(word("dir", DIRECTIONS)));
            case SEL -> node.then(literal("clear"));
            case ROTATE -> node.then(literal("90")).then(literal("180")).then(literal("270"));
            case FLIP -> node.then(literal("x")).then(literal("y")).then(literal("z"));
            case HOLOGRAM -> node.then(literal("on")).then(literal("off")).then(literal("clear"));
            case GUIDE -> node.then(literal("on")).then(literal("off"));
            case SELECT -> node.then(BuildCommandTree.<S>literal("connected").then(literal("family")).then(literal("any")));
            case MATERIALS -> node.then(literal("hologram")).then(literal("clipboard")).then(literal("selection"));
            case SWAP -> node.then(RequiredArgumentBuilder.<S, String>argument("blocks",
                    StringArgumentType.greedyString()).suggests(blocks).executes(context -> 0));
            case SHARE -> node.then(RequiredArgumentBuilder.<S, String>argument("name",
                    StringArgumentType.greedyString()).suggests(names).executes(context -> 0));
            case IMPORT, EXPORT -> node.then(RequiredArgumentBuilder.<S, String>argument("file",
                    StringArgumentType.greedyString()).executes(context -> 0));
            case SAVE, LOAD, DELETE -> node.then(RequiredArgumentBuilder.<S, String>argument("name",
                    StringArgumentType.greedyString()).suggests(names).executes(context -> 0));
            case LIST -> node.then(RequiredArgumentBuilder.<S, String>argument("search",
                    StringArgumentType.greedyString()).executes(context -> 0));
            case SET, WALLS, OUTLINE, FILL, REPLACE -> node.then(RequiredArgumentBuilder.<S, String>argument(
                    "blocks", StringArgumentType.greedyString()).suggests(blocks).executes(context -> 0));
            case MOVE, STACK -> node.then(BuildCommandTree.<S>integer("n").executes(context -> 0)
                    .then(word("dir", DIRECTIONS)));
            case UNDO, REDO -> node.then(BuildCommandTree.<S>integer("n").executes(context -> 0));
            default -> node;
        };
    }

    private static <S> LiteralArgumentBuilder<S> literal(String word) {
        return LiteralArgumentBuilder.<S>literal(word).executes(context -> 0);
    }

    private static <S> RequiredArgumentBuilder<S, Integer> integer(String name) {
        return RequiredArgumentBuilder.argument(name, IntegerArgumentType.integer());
    }

    private static <S> ArgumentBuilder<S, ?> word(String name, List<String> choices) {
        return RequiredArgumentBuilder.<S, String>argument(name, StringArgumentType.word())
                .suggests((context, builder) -> suggest(choices, builder))
                .executes(context -> 0);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static volatile List<String> blockIds;

    /** The "/<verb>" roots the current server's own command tree declares; recorded when it arrives. */
    private static volatile java.util.Set<String> serverDeclared = java.util.Set.of();

    public static void setServerDeclared(java.util.Set<String> roots) {
        serverDeclared = java.util.Set.copyOf(roots);
    }

    /** Whether the server itself has a command called {@code root} ("/set"). */
    public static boolean serverDeclares(String root) {
        return serverDeclared.contains(root);
    }

    /**
     * Every block id in the game's registry, sorted, built once. Only ever called from the game's
     * suggestion path - the tree itself takes a supplier, so tests never reach the registry.
     */
    public static Collection<String> registryBlockIds() {
        List<String> cached = blockIds;
        if (cached == null) {
            List<String> ids = new java.util.ArrayList<>();
            for (net.minecraft.resources.Identifier id : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) {
                ids.add(id.toString());
            }
            ids.sort(String::compareTo);
            cached = List.copyOf(ids);
            blockIds = cached;
        }
        return cached;
    }

    /** Offers every candidate that starts with what is typed, case-insensitively. */
    static CompletableFuture<Suggestions> suggest(Collection<String> candidates, SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /**
     * Completes the last entry of a block list - {@code 50%stone,50%andes} completes the
     * {@code andes} - so weighted mixes get the same help as a single block. The namespace is
     * optional when typing, so {@code stone} and {@code minecraft:stone} both match.
     */
    static CompletableFuture<Suggestions> suggestBlockList(Collection<String> blockIds, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int cut = Math.max(remaining.lastIndexOf(','), remaining.lastIndexOf(' '));
        int percent = remaining.lastIndexOf('%');
        int start = Math.max(cut, percent) + 1;
        String typed = remaining.substring(start).toLowerCase(Locale.ROOT);
        SuggestionsBuilder tail = builder.createOffset(builder.getStart() + start);
        // The held block first: "hand" and "offhand" are what a bare edit usually wants.
        for (String token : new String[] {"hand", "offhand"}) {
            if (token.startsWith(typed)) {
                tail.suggest(token);
            }
        }
        int offered = 0;
        for (String id : blockIds) {
            String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            if (bare.startsWith(typed) || id.startsWith(typed)) {
                tail.suggest(bare);
                if (++offered >= 200) {
                    break;   // the strip shows a handful anyway; a thousand entries only cost time
                }
            }
        }
        return tail.buildFuture();
    }
}
