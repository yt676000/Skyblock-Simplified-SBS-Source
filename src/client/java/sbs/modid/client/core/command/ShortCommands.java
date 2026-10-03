/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.command;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.warp.WarpCatalog;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Built-in command shortcuts: short forms for the party commands you type all day
 * ({@code /pw}, {@code /pt}, {@code /pk} ...), optional prefix-free warping ({@code /wizard} instead
 * of {@code /warp wizard}) and lower-case item ids for {@code /viewrecipe}.
 *
 * <p>These sit BELOW the user-defined {@link sbs.modid.client.core.keybind.CommandShortcut}s in
 * {@link SBSCommands#tryExecute(String)}: your own alias always wins, so a built-in can be
 * overridden by defining a shortcut with the same name. Anything not recognised here is returned to
 * the caller untouched and goes to the server as usual.
 *
 * <p>Expansion runs through {@link SBSCommands#run(String)}, whose {@code tryExecute} pass will not
 * match the expanded command again ({@code party warp} is no shortcut), so there is no recursion.
 */
public final class ShortCommands {

    /** "[MVP+] Name has invited you to join their party!" – remembered for a bare {@code /pa}. */
    private static final Pattern INVITED_YOU = Pattern.compile(
            "^(?:\\[[^]]+] )?([A-Za-z0-9_]{1,16}) has invited you to join their party!");

    /** Hypixel invites expire after 60s; a remembered inviter older than that is useless. */
    private static final long INVITE_VALID_MS = 60_000L;

    /**
     * Warp names that are also real Hypixel commands. Expanding these would swallow the command the
     * player actually meant ({@code /hub} leaves SkyBlock, {@code /bank} opens the bank), so they are
     * never shortened even with the option on.
     */
    private static final Set<String> RESERVED = Set.of("hub", "bank", "museum", "home");

    /**
     * Short warp names Hypixel accepts that are no Warp Menu destination of their own, so the
     * prefix-free form covers them too: {@code /dh} → {@code /warp dh}, the Dungeon Hub, which the
     * catalog only lists under its long name {@code dungeon_hub}.
     *
     * <p>{@code isle} is the Crimson Isle, which {@link WarpCatalog} lists only as {@code /warp
     * nether}. It is here rather than in the catalog because the catalog describes the Warp Menu's
     * destinations - one entry per island, under the name that menu uses - and a second name for a
     * place already in it would show up as a second island. Requested because {@code /isle} used to
     * work as a Hypixel command on its own and no longer does; this puts it back client-side, as an
     * addition to the existing short forms rather than a replacement for any of them.
     * <b>UNVERIFIED</b>: that {@code /warp isle} is still a destination Hypixel accepts has not been
     * checked in game with this build. If it is not, the expanded command goes to the server and
     * Hypixel answers it - the same as typing it by hand, so a wrong guess here costs nothing.
     */
    private static final Set<String> ALIASES = Set.of("dh", "isle");

    /** Every {@code /warp <name>} destination the Warp Menu knows, minus the reserved ones. */
    private static final Set<String> WARPS = collectWarps();

    private static String lastInviter = "";
    private static long lastInviteAt;

    private ShortCommands() {
    }

    private static SBSConfig.ShortCommandsSettings cfg() {
        return ConfigManager.getInstance().get().shortCommands;
    }

    /**
     * Runs the built-in shortcut {@code name} stands for.
     *
     * @param name the typed command, lower-case and without its leading slash
     * @param args everything after it (may be empty, never {@code null})
     * @return {@code true} when this was a shortcut and has been handled locally
     */
    public static boolean tryRun(String name, String args) {
        SBSConfig.ShortCommandsSettings cfg = cfg();
        if (!cfg.enabled) {
            return false;
        }
        String[] words = args.isEmpty() ? new String[0] : args.trim().split("\\s+", 2);
        String first = words.length > 0 ? words[0] : "";
        String rest = words.length > 1 ? words[1].trim() : "";

        if (cfg.party && party(cfg, name, first, rest)) {
            return true;
        }
        if (cfg.warpIs && name.equals("warp") && (first.equals("is") || first.equals("island"))) {
            return run("/is");
        }
        if (cfg.lowercaseViewrecipe && name.equals("viewrecipe") && !args.isBlank()
                && !args.equals(args.toUpperCase(Locale.ROOT))) {
            return run("/viewrecipe " + args.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        if (cfg.shortWarp && args.isBlank() && WARPS.contains(name)) {
            return run("/warp " + name);
        }
        return false;
    }

    /** The party short forms. {@code first}/{@code rest} are the split arguments. */
    private static boolean party(SBSConfig.ShortCommandsSettings cfg, String name,
                                 String first, String rest) {
        return switch (name) {
            case "pw" -> run("/party warp");
            case "pd" -> run("/party disband");
            case "pko" -> run("/party kickoffline");
            case "pt" -> !first.isEmpty() && run("/party transfer " + first);
            case "pp" -> !first.isEmpty() && run("/party promote " + first);
            case "pdm" -> !first.isEmpty() && run("/party demote " + first);
            case "pi" -> !first.isEmpty() && run("/party invite " + first);
            case "pk" -> !first.isEmpty() && kick(cfg, first, rest);
            case "pa" -> accept(cfg, first);
            default -> false;
        };
    }

    /**
     * {@code /pk <name> [reason]}: with a reason, the party is told why before the kick lands –
     * otherwise the member just vanishes and nobody knows what happened.
     */
    private static boolean kick(SBSConfig.ShortCommandsSettings cfg, String member, String reason) {
        if (cfg.kickReason && !reason.isEmpty()) {
            run("/pc " + sbs.modid.client.core.util.ChatTag.tag("Kicked " + member + ": " + reason));
        }
        return run("/party kick " + member);
    }

    /**
     * {@code /pa [name]}: with no name, accepts the invite whose "has invited you" line came in last
     * (Hypixel needs the inviter's name and only gives you 60 seconds).
     */
    private static boolean accept(SBSConfig.ShortCommandsSettings cfg, String member) {
        if (!member.isEmpty()) {
            return run("/party accept " + member);
        }
        if (!cfg.acceptLastInvite || lastInviter.isEmpty()
                || System.currentTimeMillis() - lastInviteAt > INVITE_VALID_MS) {
            return false; // nothing remembered – let Hypixel answer the bare command
        }
        return run("/party accept " + lastInviter);
    }

    /** Called with every displayed chat line: remembers who invited you last, for a bare {@code /pa}. */
    public static void onChat(String text) {
        if (text == null || !text.contains("has invited you to join their party")) {
            return;
        }
        Matcher matcher = INVITED_YOU.matcher(text);
        if (matcher.find()) {
            lastInviter = matcher.group(1);
            lastInviteAt = System.currentTimeMillis();
        }
    }

    /**
     * A one-line hint for the settings panel. The full list would be dozens of names and a label row
     * silently drops what does not fit, so it names the count and points at the Warp Menu – which
     * shows every destination anyway, being the same source.
     *
     * <p>The extras are spelled out because they are the ones the Warp Menu does <b>not</b> show, so
     * "same list as the Warp Menu" would send someone looking for them there and find nothing. Built
     * from {@link #ALIASES} rather than typed out, so adding one cannot leave this line stale.
     */
    public static String warpHint() {
        String extras = ALIASES.stream().sorted().map(name -> "/" + name)
                .collect(Collectors.joining(", "));
        return WARPS.size() + " warps - the Warp Menu's list plus " + extras
                + ": /garden, /wizard, /nether, /mines, ...";
    }

    private static boolean run(String command) {
        Minecraft.getInstance().execute(() -> SBSCommands.run(command));
        return true;
    }

    private static Set<String> collectWarps() {
        Set<String> names = new HashSet<>();
        for (WarpCatalog.Island island : WarpCatalog.ISLANDS) {
            addWarp(names, island.command());
            for (WarpCatalog.Warp warp : island.warps()) {
                addWarp(names, warp.command());
            }
        }
        names.addAll(ALIASES);
        names.removeAll(RESERVED);
        return names;
    }

    private static void addWarp(Set<String> names, String command) {
        if (command != null && command.startsWith("/warp ")) {
            names.add(command.substring("/warp ".length()).trim().toLowerCase(Locale.ROOT));
        }
    }
}
