/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File names and catalogued menu names for the Server Scanner. Pure, unit-tested.
 */
public final class ScanNames {

    /** A trailing page counter: {@code "Auction Browser (2/5)"}. */
    private static final Pattern PAGE_COUNTER = Pattern.compile("\\s*\\(\\d+\\s*/\\s*\\d+\\)\\s*$");

    private static final int SLUG_MAX = 40;

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT);

    private static final String TABLE = "Experimentation Table";

    /**
     * Menus whose meaning is catalogued in {@code docs/skyblock-ui/menus.md}, as pattern -> name.
     * The docs are not bundled with the mod, so the table lives here; a feature that is scanned for
     * the first time adds its menus.
     */
    private static final List<Entry> CATALOG = List.of(
            new Entry(Pattern.compile("^Experimentation Table$"), TABLE),
            new Entry(Pattern.compile("^(Chronomatron|Ultrasequencer|Superpairs) \\(.+\\)$"), TABLE + " / $1"),
            new Entry(Pattern.compile("^(Chronomatron|Ultrasequencer|Superpairs) ➜ Stakes$"), TABLE + " / $1 Stakes"),
            new Entry(Pattern.compile("^Superpairs Rewards$"), TABLE + " / Superpairs Rewards"),
            new Entry(Pattern.compile("^Experiment Over$"), TABLE + " / Experiment Over"));

    private ScanNames() {
    }

    /**
     * The title as a file-name part: page counter stripped, lower case, letters and digits joined by
     * single dashes, at most {@value #SLUG_MAX} characters, never empty.
     */
    public static String slug(String title) {
        String text = title == null ? "" : PAGE_COUNTER.matcher(title).replaceAll("");
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > SLUG_MAX) {
            slug = slug.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "menu" : slug;
    }

    /** {@code <menu-slug>_<HHmmss>_<containerId>.jsonl}. */
    public static String menuFile(String title, LocalTime opened, int containerId) {
        return slug(title) + "_" + opened.format(FILE_TIME) + "_" + containerId + ".jsonl";
    }

    /**
     * The catalogued name of a menu title ({@code "Chronomatron (Metaphysical)"} ->
     * {@code "Experimentation Table / Chronomatron"}), or {@code null} when it is not catalogued.
     * Matched on the plain title with the page counter removed.
     */
    public static String catalog(String title) {
        if (title == null) {
            return null;
        }
        String text = PAGE_COUNTER.matcher(title.trim()).replaceAll("");
        for (Entry entry : CATALOG) {
            Matcher matcher = entry.pattern().matcher(text);
            if (matcher.matches()) {
                return matcher.replaceFirst(entry.name());
            }
        }
        return null;
    }

    private record Entry(Pattern pattern, String name) {
    }
}
