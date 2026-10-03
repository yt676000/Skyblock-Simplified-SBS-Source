/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import sbs.modid.client.core.util.PlainText;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which screen a shard is being read out of - and therefore <b>how</b> to read it.
 *
 * <p><b>The context is the fix.</b> A shard has no single spelling across Hypixel's menus: the
 * Attribute Menu writes the <i>attribute</i> and a roman tier, the Hunting Box writes the shard's
 * own display name, the Confirm Fusion dialog writes it into a lore line, and a real item carries
 * NBT. One resolver that tried every reading in turn is what put the wrong id on every shard in the
 * game - the Attribute Menu's "Berry Eater IX" happily produced a shard id, it was simply not the
 * id of any shard. Each screen therefore names the one strategy that is correct for it, and a
 * strategy that does not answer returns nothing rather than falling through to a reading that is
 * known to be wrong here. See {@code ShardResolver}.
 *
 * <p><b>Titles are matched past an optional page marker.</b> Hypixel prefixes a paged menu with
 * {@code (11/13) }, so {@code "Attribute Menu"} and {@code "(11/13) Attribute Menu"} are the same
 * screen and both must match. The marker is stripped, not searched for, so the fragment can then be
 * anchored at the start - which is what keeps "Shard Fusion" from also claiming "Confirm Fusion".
 */
public enum ShardContext {

    /**
     * The Attribute Menu. Entries are named for the <b>attribute</b> plus a roman tier, so the
     * display name never names the shard and is never consulted here.
     */
    ATTRIBUTE_MENU("Attribute Menu", "attribute menu"),

    /** The Hunting Box - every shard the player is holding. The name here <i>is</i> the shard. */
    HUNTING_BOX("Hunting Box", "hunting box", "shard box"),

    /** The fusion machine's shard picker. Names its shards the same way the box does. */
    FUSION_BOX("Fusion Box", "fusion box"),

    /** The fusion machine itself, with the two selected shards in it. */
    SHARD_FUSION("Shard Fusion", "shard fusion"),

    /** The dialog that confirms one fusion; its entry states the shard in its first lore line. */
    CONFIRM_FUSION("Confirm Fusion", "confirm fusion"),

    /**
     * Anywhere the player is holding the real item - their inventory, a chest, the cursor. The only
     * context whose stacks carry usable NBT, and the only one that reads it.
     */
    INVENTORY("Inventory");

    /** A leading {@code (11/13)} page marker, which every paged Hypixel menu carries. */
    private static final Pattern PAGE_MARKER = Pattern.compile("^\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)\\s*");

    /**
     * The first slot of a Hypixel menu that can hold an entry. Row 0 is the menu's own header - the
     * tab strip, the search box, the sort control - and reading it produces entries that are not
     * shards.
     */
    public static final int FIRST_CONTENT_SLOT = 9;

    /** The last such slot: below this is the player's own inventory, which is not the menu. */
    public static final int LAST_CONTENT_SLOT = 44;

    /** Slots per row. */
    private static final int ROW_WIDTH = 9;

    private final String displayName;
    private final String[] titles;

    ShardContext(String displayName, String... titles) {
        this.displayName = displayName;
        this.titles = titles;
    }

    public String displayName() {
        return displayName;
    }


    /**
     * The context a menu title names, or {@code null} when it names none of them.
     *
     * <p>{@link #INVENTORY} is never returned: it is not a title, it is where a caller looks when no
     * shard menu is open, and only that caller knows it is holding real items.
     */
    public static ShardContext fromTitle(String rawTitle) {
        String title = normalise(rawTitle);
        if (title.isEmpty()) {
            return null;
        }
        for (ShardContext context : values()) {
            for (String candidate : context.titles) {
                if (title.startsWith(candidate)) {
                    return context;
                }
            }
        }
        return null;
    }

    /**
     * Whether a slot index is one a shard menu puts entries in.
     *
     * <p>Inside {@link #FIRST_CONTENT_SLOT}..{@link #LAST_CONTENT_SLOT}, and not in the first or
     * last column - Hypixel borders these menus with filler panes down both sides. The panes resolve
     * to nothing anyway, so skipping them is not what makes a scan correct; it is what keeps eight
     * panes per menu out of the "could not read this" count, which is a number the player is asked
     * to act on and which is worthless if it is mostly decoration.
     */
    public static boolean isContentSlot(int slot) {
        if (slot < FIRST_CONTENT_SLOT || slot > LAST_CONTENT_SLOT) {
            return false;
        }
        int column = slot % ROW_WIDTH;
        return column != 0 && column != ROW_WIDTH - 1;
    }

    /**
     * A title reduced to what is compared: colour codes out, page marker off, lower case.
     *
     * <p>Public because the debug dump prints it, and a diagnostic that normalised the title its own
     * way would be answering a different question from the matcher it exists to explain.
     */
    public static String normalise(String rawTitle) {
        String plain = PlainText.strip(rawTitle == null ? "" : rawTitle).trim();
        return PAGE_MARKER.matcher(plain).replaceFirst("").trim().toLowerCase(Locale.ROOT);
    }
}
