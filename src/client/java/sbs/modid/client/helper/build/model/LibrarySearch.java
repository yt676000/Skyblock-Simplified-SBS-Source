/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.core.build.model.SchematicHeader;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Quick Paste search: every typed term must match. {@code #tag} matches the start of a tag;
 * any other term matches part of the name, a tag or the folder. This is also how folders and tags
 * are browsed - typing a folder's name or {@code #oak} narrows the grid to it.
 */
public final class LibrarySearch {

    private static final Pattern TERM_SEPARATOR = Pattern.compile("\s+");

    private LibrarySearch() {
    }

    public static boolean matches(String displayName, SchematicHeader header, String query) {
        String name = displayName.toLowerCase(Locale.ROOT);
        String folder = header.folder().toLowerCase(Locale.ROOT);
        for (String term : TERM_SEPARATOR.split(query.trim().toLowerCase(Locale.ROOT))) {
            if (term.isEmpty()) {
                continue;
            }
            if (term.startsWith("#")) {
                String tag = term.substring(1);
                if (header.tags().stream().noneMatch(t -> t.toLowerCase(Locale.ROOT).startsWith(tag))) {
                    return false;
                }
            } else if (!name.contains(term) && !folder.contains(term)
                    && header.tags().stream().noneMatch(t -> t.toLowerCase(Locale.ROOT).contains(term))) {
                return false;
            }
        }
        return true;
    }
}
