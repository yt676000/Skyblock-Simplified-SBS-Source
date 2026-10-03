/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

import sbs.modid.client.helper.buffs.BuffDuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Active Effects menu ({@code /effects}) the player opened themselves. Pure.
 *
 * <p><b>UNVERIFIED.</b> This menu has never been captured (docs/skyblock-ui/menus.md). The shape
 * assumed here is one item per effect, named after it, with its remaining time on a lore line -
 * either labelled ({@code Remaining: 12:34}, {@code Duration: 3m}) or alone ({@code (00:30:00)}).
 * Reading it is behind a setting that is off by default, and {@code ConsumableCapture} logs every
 * slot so the real format can replace this guess.
 */
public final class EffectsMenu {

    /** One slot as the menu shows it: name and lore, colour-stripped. */
    public record Slot(String name, List<String> lore) {
    }

    /** One effect with its time. */
    public record Effect(String name, long remainingMs, long precisionMs) {
    }

    /** The menu's effects, and whether this page is the whole listing (no further pages). */
    public record Reading(List<Effect> effects, boolean complete) {
    }

    private static final Pattern PAGE = Pattern.compile("^\\((\\d+)/(\\d+)\\)\\s*");
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)^(?:time\\s*)?(?:remaining|time left|duration|expires in|ends in)\\s*:?\\s*(.+)$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z' -]*?(?: [IVXLC]+)?$");
    private static final Pattern NAVIGATION = Pattern.compile(
            "(?i)^(?:close|go back|next page|previous page|back|sort.*|filter.*)$");

    private EffectsMenu() {
    }

    /** Whether a normalised (stripped, lower-case) menu title is the Active Effects menu. */
    public static boolean isActiveEffectsMenu(String normalisedTitle) {
        if (normalisedTitle == null) {
            return false;
        }
        String title = PAGE.matcher(normalisedTitle).replaceFirst("").trim();
        // The title has not been captured: "Active Effects" is the expectation, and any title naming
        // effects is accepted for the capture, so a different wording still gets recorded.
        return title.equals("active effects") || title.contains("effects");
    }

    /** Whether the title says this is the only page ("(1/1)" or no page marker at all). */
    static boolean singlePage(String normalisedTitle) {
        Matcher m = PAGE.matcher(normalisedTitle == null ? "" : normalisedTitle.trim());
        return !m.find() || m.group(2).equals("1");
    }

    public static Reading parse(String normalisedTitle, List<Slot> slots) {
        List<Effect> out = new ArrayList<>();
        for (Slot slot : slots) {
            String name = slot.name() == null ? "" : slot.name().trim();
            if (name.isEmpty() || NAVIGATION.matcher(name).matches() || !NAME.matcher(name).matches()) {
                continue;
            }
            BuffDuration.Parsed time = timeIn(slot.lore());
            if (time != null) {
                out.add(new Effect(name, time.millis(), time.precisionMs()));
            }
        }
        return new Reading(List.copyOf(out), !out.isEmpty() && singlePage(normalisedTitle));
    }

    /** The first lore line that is, or is labelled as, a remaining time. */
    static BuffDuration.Parsed timeIn(List<String> lore) {
        if (lore == null) {
            return null;
        }
        for (String raw : lore) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LABELLED.matcher(line);
            BuffDuration.Parsed d = BuffDuration.parse(m.matches() ? m.group(1) : line);
            if (d != null) {
                return d;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith("click")) {
                break;   // instructions follow; nothing after them is a time
            }
        }
        return null;
    }
}
