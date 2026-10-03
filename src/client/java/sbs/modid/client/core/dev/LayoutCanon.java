/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The canonical strings the Layout Recorder hashes: one per menu (fine and coarse), one per tab
 * widget, one per scoreboard element. Pure, so "same layout, other values → same signature" is a
 * unit test.
 */
public final class LayoutCanon {

    /** One menu slot as captured. {@code lore} are plain lines. */
    public record Slot(int index, String id, String name, List<String> lore, int count, boolean glint) {
    }

    /** One tab widget: its key (from the header line) and its lines. */
    public record Section(String key, List<String> lines) {
    }

    /**
     * A tab header: "Info", "Players (20)", "Skills:", "Account Info" - up to three Title-Case words
     * and no value. Title case on purpose: "MAX LEVEL" under "Pet:" is a value row, not a header.
     */
    private static final Pattern HEADER = Pattern.compile(
            "^[A-Z][a-z']+(?: [A-Z][a-z']+){0,2}(?:\\s*\\(\\d+\\))?:?$");

    private LayoutCanon() {
    }

    /** One slot's fine form: index, id, normalised name, normalised lore. */
    public static String slotFine(Slot slot, Collection<String> players) {
        StringBuilder out = new StringBuilder();
        out.append(slot.index()).append('|').append(slot.id() == null ? "" : slot.id()).append('|')
                .append(LayoutSignature.normalise(slot.name(), players));
        for (String line : slot.lore()) {
            out.append("\n  ").append(LayoutSignature.normalise(line, players));
        }
        return out.toString();
    }

    /** The menu's fine signature: normalised title + every slot's fine form, in slot order. */
    public static String menuFine(String title, List<Slot> slots, Collection<String> players) {
        StringBuilder out = new StringBuilder("menu:").append(LayoutSignature.normalise(title, players));
        for (Slot slot : slots) {
            out.append('\n').append(slotFine(slot, players));
        }
        return out.toString();
    }

    /** The menu's coarse signature: normalised title + slot index → item id only. */
    public static String menuCoarse(String title, List<Slot> slots, Collection<String> players) {
        StringBuilder out = new StringBuilder("menu:").append(LayoutSignature.normalise(title, players));
        for (Slot slot : slots) {
            out.append('\n').append(slot.index()).append('|').append(slot.id() == null ? "" : slot.id());
        }
        return out.toString();
    }

    /** The slot indices whose fine forms differ between two captures of the same coarse layout. */
    public static List<Integer> changedSlots(List<String> fineA, List<String> fineB) {
        List<Integer> changed = new ArrayList<>();
        int n = Math.max(fineA.size(), fineB.size());
        for (int i = 0; i < n; i++) {
            String a = i < fineA.size() ? fineA.get(i) : null;
            String b = i < fineB.size() ? fineB.get(i) : null;
            if (a == null || !a.equals(b)) {
                String line = a != null ? a : b;
                changed.add(Integer.parseInt(line.substring(0, line.indexOf('|'))));
            }
        }
        return changed;
    }

    /**
     * Splits tab widget lines into sections at each header line, so one widget changing records
     * only that widget. Lines before the first header go under {@code "top"}.
     */
    public static List<Section> tabSections(List<String> lines) {
        List<Section> sections = new ArrayList<>();
        String key = "top";
        List<String> current = new ArrayList<>();
        for (String raw : lines) {
            String line = raw == null ? "" : LayoutSignature.stripCodes(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (HEADER.matcher(line).matches()) {
                if (!current.isEmpty() || !"top".equals(key)) {
                    sections.add(new Section(key, current));
                }
                key = LayoutSignature.slug(line.replaceAll("\\(\\d+\\)", ""));
                current = new ArrayList<>();
                continue;
            }
            current.add(line);
        }
        if (!current.isEmpty() || !"top".equals(key)) {
            sections.add(new Section(key, current));
        }
        return sections;
    }

    /**
     * A section's canonical form. Lines are normalised, and repeats of one normalised line collapse
     * to one - so "Players (19)" and "Players (20)" rows, all {@code <player>}, are one layout.
     */
    public static String sectionCanon(Section section, Collection<String> players) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : section.lines()) {
            seen.add(LayoutSignature.normalise(line, players));
        }
        return "tab:" + section.key().toLowerCase(Locale.ROOT) + "\n" + String.join("\n", seen);
    }
}
