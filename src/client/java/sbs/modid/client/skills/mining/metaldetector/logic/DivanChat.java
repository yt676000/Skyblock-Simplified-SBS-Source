/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.logic;

import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.skills.mining.metaldetector.model.DivanTool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Mines of Divan chat lines, read into events. Pure: a string in, an event or {@code null} out.
 *
 * <p>Every pattern is CONFIRMED against the maintainer's chat logs (colour codes removed):
 * <pre>
 * You found Scavenged Diamond Axe with your Metal Detector!
 * You found ◆ Flawed Ruby Gemstone x2 with your Metal Detector!
 * [NPC] Keeper of Diamond: Excellent! You have returned the Scavenged Diamond Axe to its rightful place!
 * [NPC] Keeper of Diamond: You found all of the items! Behold... the Jade Crystal!
 * [NPC] Keeper of Diamond: You haven't collected the Jade Crystal yet!
 * [NPC] Keeper of Diamond: Punch the crystal in front of me to collect it!
 * </pre>
 * Two things in those logs are not the game's: a trailing {@code (N)} is a stacking counter the
 * logging client added, and the {@code ◆} before gemstone loot was written as {@code ?} by a log that
 * could not encode it. Both are tolerated so the same tests run on old and new text.
 *
 * <p>{@code You uncovered a treasure chest!} is the Crystal Hollows treasure chest, not the
 * detector, and is deliberately not read here.
 */
public final class DivanChat {

    /** Something the Mines of Divan said. */
    public sealed interface Event permits ToolFound, ChestLoot, ToolReturned, AllReturned, JadeNotCollected {
    }

    /** A scavenged tool dug up with the detector - it is now in the inventory. */
    public record ToolFound(DivanTool tool) implements Event {
    }

    /** Anything else the detector dug up; {@code text} is the loot as printed, glyph removed. */
    public record ChestLoot(String text) implements Event {
    }

    /** A tool handed to its Keeper. {@code keeper} is the word after {@code Keeper of}. */
    public record ToolReturned(DivanTool tool, String keeper) implements Event {
    }

    /** The fourth tool went back: the Jade Crystal is standing there to be punched. */
    public record AllReturned() implements Event {
    }

    /** A Keeper reminding the player that the Jade Crystal is still there. Implies all four back. */
    public record JadeNotCollected() implements Event {
    }

    private static final String TOOLS = "Golden Hammer|Emerald Hammer|Diamond Axe|Lapis Sword";
    private static final String KEEPER = "\\[NPC] Keeper of (Gold|Emerald|Diamond|Lapis): ";

    /** The logging client's stacking counter, {@code " (12)"} at the very end. */
    private static final Pattern STACK_COUNTER = Pattern.compile("\\s*\\(\\d+\\)$");

    private static final Pattern FOUND =
            Pattern.compile("^You found (.+?) with your Metal Detector!$");
    private static final Pattern SCAVENGED =
            Pattern.compile("^Scavenged (" + TOOLS + ")$");
    /** The gemstone glyph {@code ◆}, or the {@code ?} an old log wrote in its place. */
    private static final Pattern LOOT_GLYPH = Pattern.compile("^[◆?]\\s*");
    private static final Pattern RETURNED = Pattern.compile("^" + KEEPER
            + "Excellent! You have returned the Scavenged (" + TOOLS + ") to its rightful place!$");
    private static final Pattern ALL_RETURNED = Pattern.compile("^" + KEEPER
            + "You found all of the items! Behold(?:\\.\\.\\.|…) the Jade Crystal!$");
    private static final Pattern NOT_COLLECTED = Pattern.compile("^" + KEEPER
            + "(?:You haven['’]t collected the Jade Crystal yet!"
            + "|Punch the crystal in front of me to collect it!)$");

    private DivanChat() {
    }

    /** Colour codes and the stacking counter removed, trimmed. */
    public static String plain(String raw) {
        String text = PlainText.strip(raw).trim();
        return STACK_COUNTER.matcher(text).replaceFirst("");
    }

    /** {@code raw} as an event, or {@code null} when it is not a Mines of Divan line. */
    public static Event parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String line = plain(raw);
        Matcher m = FOUND.matcher(line);
        if (m.matches()) {
            String what = m.group(1).trim();
            Matcher tool = SCAVENGED.matcher(what);
            if (tool.matches()) {
                return new ToolFound(DivanTool.byName(tool.group(1)));
            }
            return new ChestLoot(LOOT_GLYPH.matcher(what).replaceFirst(""));
        }
        if (!line.startsWith("[NPC] Keeper of ")) {
            return null;
        }
        m = RETURNED.matcher(line);
        if (m.matches()) {
            return new ToolReturned(DivanTool.byName(m.group(2)), m.group(1));
        }
        if (ALL_RETURNED.matcher(line).matches()) {
            return new AllReturned();
        }
        if (NOT_COLLECTED.matcher(line).matches()) {
            return new JadeNotCollected();
        }
        return null;
    }
}
