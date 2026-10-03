/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings.layout;

import sbs.modid.client.core.module.ModuleGroup;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A player-made arrangement of the settings sidebar: categories in an order, each holding module ids
 * in an order. An <b>overlay</b> on the built-in layout, never an edit of it - with no layout stored,
 * the sidebar is exactly the default.
 *
 * <h2>Identity</h2>
 * Everything is keyed on ids, never on labels or positions. A module is its module id (the same id
 * favourites, jumps and the last selection use). A built-in category is its {@link ModuleGroup}
 * constant <b>name</b>. A custom category gets a generated id ({@code c} + 8 base-36 characters) the
 * moment it is made, so renaming it changes nothing the layout or a fold is keyed on.
 *
 * <h2>Stored as one line of text</h2>
 * {@link #encode()} / {@link #decode(String)}: categories joined by {@code |}, each
 * {@code id~builtin~name~module,module,...} ({@code builtin} is a group name or {@code -}). A string
 * because that is what the Share Settings payload carries for a setting; the decoder is strict - ids
 * must be {@code [a-z0-9_]}, names are stripped of {@code §} codes and control characters and
 * capped, and counts are bounded - so an imported layout cannot carry anything but a layout.
 */
public final class SidebarLayout {

    /** Longest custom category name. The sidebar is 132 px wide at GUI scale 4. */
    public static final int MAX_NAME = 24;
    public static final int MAX_CATEGORIES = 48;
    public static final int MAX_MODULES = 512;

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
    private static final String NO_BUILTIN = "-";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One category of the layout. Mutable: the editor works on it in place. */
    public static final class Category {
        public final String id;
        /** The built-in group this category stands for, or {@code null} for a custom one. */
        public final ModuleGroup builtin;
        public String name;
        public final List<String> modules = new ArrayList<>();

        public Category(String id, ModuleGroup builtin, String name) {
            this.id = id;
            this.builtin = builtin;
            this.name = name;
        }

        public boolean custom() {
            return builtin == null;
        }

        /** What the sidebar header shows. A built-in category always uses its group's own name. */
        public String displayName() {
            return builtin != null ? builtin.displayName() : name;
        }
    }

    public final List<Category> categories = new ArrayList<>();

    /** A new custom category with a fresh id, not yet added. */
    public static Category newCustom(String name) {
        String id;
        do {
            StringBuilder s = new StringBuilder("c");
            for (int i = 0; i < 8; i++) {
                s.append(Character.forDigit(RANDOM.nextInt(36), 36));
            }
            id = s.toString();
        } while (!ID.matcher(id).matches());
        return new Category(id, null, cleanName(name));
    }

    public Category find(String id) {
        for (Category category : categories) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        return null;
    }

    /** The category standing for {@code group}, or {@code null}. */
    public Category builtin(ModuleGroup group) {
        for (Category category : categories) {
            if (category.builtin == group) {
                return category;
            }
        }
        return null;
    }

    /** The ids of the custom categories - what folding needs to fold them all. */
    public List<String> customIds() {
        List<String> ids = new ArrayList<>();
        for (Category category : categories) {
            if (category.custom()) {
                ids.add(category.id);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ text form

    public String encode() {
        StringBuilder out = new StringBuilder();
        for (Category category : categories) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(category.id).append('~')
                    .append(category.builtin == null ? NO_BUILTIN : category.builtin.name()).append('~')
                    .append(category.custom() ? cleanName(category.name) : "").append('~')
                    .append(String.join(",", category.modules));
        }
        return out.toString();
    }

    /**
     * The layout in {@code text}, or {@code null} when there is none or it is not a layout.
     *
     * <p>Lenient about content, strict about shape: a module id or built-in group this build does
     * not know is kept or skipped by {@link SidebarLayoutResolver}, not here - but a malformed
     * category, a bad id or a duplicate category id makes the whole thing {@code null}, and the
     * sidebar falls back to the default rather than showing half a layout.
     */
    public static SidebarLayout decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        SidebarLayout layout = new SidebarLayout();
        int modules = 0;
        for (String part : text.split("\\|", -1)) {
            String[] fields = part.split("~", -1);
            if (fields.length != 4 || !ID.matcher(fields[0]).matches()
                    || layout.find(fields[0]) != null || layout.categories.size() >= MAX_CATEGORIES) {
                return null;
            }
            ModuleGroup builtin = null;
            if (!NO_BUILTIN.equals(fields[1])) {
                builtin = groupNamed(fields[1]);
                if (builtin == null) {
                    continue;   // a group this build no longer has: its modules go home
                }
                if (builtin == ModuleGroup.PINNED || layout.builtin(builtin) != null) {
                    return null;
                }
            }
            Category category = new Category(fields[0], builtin, builtin == null ? cleanName(fields[2]) : "");
            if (category.custom() && category.name.isEmpty()) {
                category.name = "Category";
            }
            if (!fields[3].isEmpty()) {
                for (String module : fields[3].split(",")) {
                    if (!ID.matcher(module).matches() || ++modules > MAX_MODULES) {
                        return null;
                    }
                    category.modules.add(module);
                }
            }
            layout.categories.add(category);
        }
        return layout.categories.isEmpty() ? null : layout;
    }

    private static ModuleGroup groupNamed(String name) {
        for (ModuleGroup group : ModuleGroup.values()) {
            if (group.name().equals(name)) {
                return group;
            }
        }
        return null;
    }

    /** A name as it may be stored: no {@code §} codes, no control or separator characters, capped. */
    public static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < raw.length() && clean.length() < MAX_NAME; i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (Character.isISOControl(c) || c == '|' || c == '~' || c == ',') {
                continue;
            }
            clean.append(c);
        }
        return clean.toString().trim();
    }

    /** Whether {@code query} (lower case) matches this category's shown name. */
    public static boolean nameMatches(Category category, String query) {
        return !query.isEmpty() && category.displayName().toLowerCase(Locale.ROOT).contains(query);
    }
}
