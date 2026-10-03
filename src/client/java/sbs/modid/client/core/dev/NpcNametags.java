/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Who a right-clicked entity is, for the Layout Recorder's opener. Pure: the caller collects the
 * entity's own name and the nametag stands around it; this decides.
 *
 * <p>On Hypixel an NPC is usually a player-model entity with no name of its own, and the name sits
 * on invisible armor stands stacked above it - the top line is the name, lines below it are hints
 * like {@code CLICK}.
 */
public final class NpcNametags {

    /** What the clicked entity is. */
    public enum Kind { NPC, PLAYER, ENTITY }

    /** One nametag stand relative to the clicked entity: offsets in blocks, and its text. */
    public record Stand(double dx, double dy, double dz, String text) {
    }

    /** A catalogue entry as far as matching needs it. */
    public record CatalogNpc(String name, String island, double x, double y, double z) {
    }

    static final double HORIZONTAL = 0.5;
    static final double ABOVE = 3.0;

    private static final Pattern HINT = Pattern.compile("(?i)^(?:right[ -]?)?click(?: to .*)?!?$|^\\W*$");
    private static final Pattern CODES = Pattern.compile("(?i)§.");

    private NpcNametags() {
    }

    /**
     * The entity's display name: its own custom name if it has one, else the top line of the
     * nametag stands within {@value #HORIZONTAL} blocks horizontally and up to {@value #ABOVE}
     * blocks above it. Hint lines ({@code CLICK}, {@code RIGHT CLICK}) are skipped. {@code null}
     * when nothing names it.
     */
    public static String resolveName(String customName, List<Stand> stands) {
        String own = clean(customName);
        if (own != null) {
            return own;
        }
        Stand best = null;
        for (Stand stand : stands) {
            if (Math.abs(stand.dx()) > HORIZONTAL || Math.abs(stand.dz()) > HORIZONTAL
                    || stand.dy() <= 0 || stand.dy() > ABOVE) {
                continue;
            }
            String text = clean(stand.text());
            if (text == null || HINT.matcher(text).matches()) {
                continue;
            }
            if (best == null || stand.dy() > best.dy()) {
                best = stand;
            }
        }
        return best == null ? null : clean(best.text());
    }

    /**
     * NPC, real player or other entity. A real player (listed as one in the tab) is never an NPC;
     * a catalogued name is; a player-model entity that is not a real player is one too, even
     * without a catalogue entry.
     */
    public static Kind classify(boolean playerModel, boolean realPlayer, boolean inCatalogue) {
        if (realPlayer) {
            return Kind.PLAYER;
        }
        if (inCatalogue || playerModel) {
            return Kind.NPC;
        }
        return Kind.ENTITY;
    }

    /**
     * The catalogue entry this click was, among several sharing the name: same island first, then
     * the nearest to where the entity stood. {@code null} when none is on this island.
     */
    public static CatalogNpc match(List<CatalogNpc> sameName, String island, double x, double y, double z) {
        CatalogNpc best = null;
        double bestDist = Double.MAX_VALUE;
        for (CatalogNpc npc : sameName) {
            if (island == null || !island.equalsIgnoreCase(npc.island())) {
                continue;
            }
            double d = sq(npc.x() - x) + sq(npc.y() - y) + sq(npc.z() - z);
            if (d < bestDist) {
                bestDist = d;
                best = npc;
            }
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String plain = CODES.matcher(text).replaceAll("").trim();
        return plain.isEmpty() ? null : plain;
    }

    /** Lower-case key for comparing names. */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
