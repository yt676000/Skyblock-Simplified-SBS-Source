/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.progress.SkillTracker;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A <b>capture-only</b> recorder for Hypixel's per-chop foraging lines, and the reason the Sweep
 * feature could be written before anybody had seen one.
 *
 * <p><b>Why it exists.</b> The exact wording of the message is not established - see
 * {@code docs/SWEEP-HUD-DESIGN.md} §1. A parser written against a guessed sentence matches nothing
 * and looks exactly like a feature that is switched off, so instead of guessing, this records what
 * Hypixel really sends in one chopping session. {@link SweepParser} is built to be tolerant enough
 * to work meanwhile; this is what turns the remaining guesses into facts.
 *
 * <p><b>What it records and why each part is needed.</b> The raw line, the stripped line, every
 * non-ASCII character as its codepoint (the {@code ∮} may be a private-use glyph from Hypixel's own
 * font rather than U+222E, in which case the character in our source matches nothing forever - the
 * same trap the {@code [SBS][Vitality]} logging exists for), the island and zone, and the held
 * item's SkyBlock id, because the axe is what carries the conditional bonuses the message is about.
 *
 * <p><b>Deliberately not gated behind dev mode</b>, for the reason {@code MenuProbe} states: the
 * person who can produce the artifact is a player on Galatea with the setting on, not us. It stays
 * harmless because it only ever reads and writes its own file.
 */
public final class SweepCapture {

    private static final SweepCapture INSTANCE = new SweepCapture();

    /** Hard cap on recorded lines. A forgotten capture cannot fill a disk. */
    private static final int MAX_LINES = 400;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private volatile boolean armed;
    private Path file;
    private int lines;

    private SweepCapture() {
    }

    public static SweepCapture getInstance() {
        return INSTANCE;
    }

    /** Whether anything at all should be handed to {@link #onChat}. One boolean read while off. */
    public boolean armed() {
        return armed;
    }

    // ------------------------------------------------------------------ command

    /** {@code /sbs sweep capture [on|off|status]}. */
    public void handleCommand(String argument) {
        String arg = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        switch (arg) {
            case "off", "stop", "disarm" -> stop();
            case "status" -> status();
            default -> start();
        }
    }

    private void start() {
        armed = true;
        lines = 0;
        file = SBSFiles.probeDir().resolve(
                "sweep-" + LocalDateTime.now().format(STAMP) + ".txt");
        try {
            SBSFiles.ensureParent(file);
            Files.writeString(file, header(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            armed = false;
            say("§cCould not open the capture file - see the log.");
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Foraging] sweep capture could not start", e);
            return;
        }
        say("§aSweep capture on§7 - chop for a while, then run §f/sbs sweep capture off§7.");
        say("§7Melee and thrown, on at least two tree types, on both islands if you can.");
        say("§8File: " + file);
    }

    private void stop() {
        if (!armed) {
            say("§7Sweep capture is not running.");
            return;
        }
        armed = false;
        say("§7Sweep capture off after §f" + lines + "§7 line(s).");
        say("§8Please send this file back: §f" + file);
    }

    private void status() {
        say(armed ? "§aCapture is running §7(" + lines + " line(s) so far)."
                : "§7Capture is off. §f/sbs sweep capture§7 starts one.");
        say("§8Files: " + SBSFiles.probeDir());
    }

    // ------------------------------------------------------------------ recording

    /**
     * Every chat line while armed. Broader than {@link SweepParser#isCandidate} on purpose: if the
     * message never says the word and all its fields are glyph-prefixed, a "sweep" match alone would
     * find nothing and look like an answer, so any non-ASCII line counts while Foraging XP is
     * flowing.
     */
    public void onChat(String raw) {
        if (!armed || raw == null || lines >= MAX_LINES) {
            return;
        }
        boolean interesting = SweepParser.isCandidate(raw)
                || (hasNonAscii(raw) && SkillTracker.getInstance().foragingActive());
        if (!interesting) {
            return;
        }
        String stripped = StyledText.strip(raw);
        StringBuilder out = new StringBuilder(256);
        out.append("\n--- line ").append(++lines).append(" @ ").append(LocalDateTime.now())
                .append('\n');
        out.append("  island   : ").append(SkyBlockLocation.island()).append('\n');
        out.append("  zone     : ").append(SkyBlockLocation.zone()).append('\n');
        out.append("  held     : ").append(heldId()).append('\n');
        out.append("  foraging : ").append(SkillTracker.getInstance().foragingActive()).append('\n');
        out.append("  parsed   : ").append(SweepParser.parse(stripped, 0L)).append('\n');
        out.append("  raw      : ").append(raw).append('\n');
        out.append("  stripped : ").append(stripped).append('\n');
        out.append("  codepoints of every non-ASCII character:\n");
        appendCodepoints(out, stripped);
        append(out.toString());
        if (lines >= MAX_LINES) {
            armed = false;
            say("§eSweep capture stopped by itself after " + MAX_LINES + " lines.");
            say("§8Please send this file back: §f" + file);
        }
    }

    private void append(String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            armed = false;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Foraging] sweep capture write failed", e);
        }
    }

    private static void appendCodepoints(StringBuilder out, String text) {
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F) {
                any = true;
                out.append(String.format(Locale.ROOT, "    [%d] U+%04X %s%n", i, (int) c, c));
            }
        }
        if (!any) {
            out.append("    (none - the line is plain ASCII)\n");
        }
    }

    private static boolean hasNonAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String heldId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return "(no player)";
        }
        ItemStack held = minecraft.player.getMainHandItem();
        String id = SkyblockItem.id(held);
        return id == null || id.isBlank() ? held.getHoverName().getString() : id;
    }

    private static String header() {
        return """
                SkyBlock Simplified - foraging sweep capture
                ===========================================

                A read-only recording of the per-chop foraging lines Hypixel sends, written so the
                Sweep card can be built against what the game really says instead of against a guess.
                It contains no account data beyond the chat lines themselves, the island you are on
                and which axe you are holding. Please send the whole file back unedited.

                """;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
