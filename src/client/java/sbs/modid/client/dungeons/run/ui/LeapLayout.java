/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * The pure half of the Custom Leap Menu: which teammates get a button, which real slot each button
 * stands for, how the buttons tile, and which button a map marker belongs to. No Minecraft types,
 * so every rule here is unit-tested ({@code LeapLayoutTest}); {@link LeapScreenOverlay} only draws
 * what this returns and forwards clicks to {@link Entry#slot()}.
 */
public final class LeapLayout {

    /** A teammate head found in the leap menu: its slot, skin-profile name and stripped display name. */
    public record Head(int slot, String profileName, String displayName) {
    }

    /**
     * One button. {@code slot} is the real menu slot a click goes to, or {@code -1} for a teammate
     * on the run's roster whose head is not in the menu (dead, disconnected) - drawn greyed and never
     * clickable. {@code cls} is the class initial, or {@code 0} when unknown.
     */
    public record Entry(String name, int slot, char cls, boolean self) {
        public boolean available() {
            return slot >= 0;
        }
    }

    /** A button's box, in screen pixels. */
    public record Rect(int x, int y, int w, int h) {
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    private LeapLayout() {
    }

    /**
     * The buttons, in menu order: every head first (name chosen by {@link LeapNames#choose}, profile
     * before display), then every roster member with no head in the menu, alphabetically. The local
     * player is never added from the roster - you cannot leap to yourself - but a head that is you is
     * kept and flagged. Duplicate heads for one player keep the first slot.
     */
    public static List<Entry> entries(List<Head> heads, Collection<String> roster, String selfName,
                                      ToIntFunction<String> classOf) {
        List<Entry> out = new ArrayList<>();
        for (Head head : heads) {
            String name = LeapNames.choose(head.profileName(), head.displayName(), classOf);
            if (name == null || name.isEmpty() || indexOf(out, name) >= 0) {
                continue;
            }
            out.add(new Entry(name, head.slot(), (char) classOf.applyAsInt(name), name.equalsIgnoreCase(selfName)));
        }
        List<String> missing = new ArrayList<>();
        for (String member : roster) {
            if (member == null || member.isEmpty() || member.equalsIgnoreCase(selfName)
                    || indexOf(out, member) >= 0 || containsIgnoreCase(missing, member)) {
                continue;
            }
            missing.add(member);
        }
        missing.sort(String.CASE_INSENSITIVE_ORDER);
        for (String member : missing) {
            out.add(new Entry(member, -1, (char) classOf.applyAsInt(member), false));
        }
        return out;
    }

    /**
     * Columns for {@code count} buttons when at most {@code maxColumns} fit side by side: four make a
     * 2x2 block, anything else one row while it fits, and otherwise the widest row that fits.
     */
    public static int columns(int count, int maxColumns) {
        int max = Math.max(1, maxColumns);
        if (count <= 0) {
            return 1;
        }
        if (count == 4 && max >= 2) {
            return 2;
        }
        return Math.min(count, max);
    }

    /**
     * Tiles {@code count} buttons of {@code w}x{@code h} with {@code gap} between them in
     * {@code columns} columns, centred on {@code centreX}, starting at {@code top}. A short last row is
     * centred rather than left-aligned, so 5 in three columns reads 3 + 2 under one another.
     */
    public static Rect[] tile(int count, int columns, int w, int h, int gap, int centreX, int top) {
        Rect[] rects = new Rect[Math.max(0, count)];
        int cols = Math.max(1, columns);
        for (int i = 0; i < rects.length; i++) {
            int row = i / cols;
            int inRow = Math.min(cols, rects.length - row * cols);
            int rowW = inRow * w + (inRow - 1) * gap;
            int left = centreX - rowW / 2;
            rects[i] = new Rect(left + (i % cols) * (w + gap), top + row * (h + gap), w, h);
        }
        return rects;
    }

    /** Rows {@link #tile} produces for {@code count} buttons in {@code columns} columns. */
    public static int rows(int count, int columns) {
        int cols = Math.max(1, columns);
        return count <= 0 ? 0 : (count + cols - 1) / cols;
    }

    /** The rect under a point, or {@code -1}. */
    public static int hit(Rect[] rects, double x, double y) {
        for (int i = 0; i < rects.length; i++) {
            if (rects[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The button a map marker belongs to, or {@code -1}. Marker names may be the lower-cased roster
     * names, so the match ignores case; an unnamed marker belongs to nobody.
     */
    public static int entryForMarker(List<Entry> entries, String markerName) {
        if (markerName == null || markerName.isEmpty()) {
            return -1;
        }
        return indexOf(entries, markerName);
    }

    /** The class's display name, or {@code "Unknown class"} for 0 / an unrecognised letter. */
    public static String className(char cls) {
        return switch (cls) {
            case 'M' -> "Mage";
            case 'A' -> "Archer";
            case 'B' -> "Berserk";
            case 'T' -> "Tank";
            case 'H' -> "Healer";
            default -> "Unknown class";
        };
    }

    private static int indexOf(List<Entry> entries, String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }
}
