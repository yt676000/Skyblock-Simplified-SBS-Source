/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;

import java.util.Locale;

/**
 * Live state for the Recipe Viewer search – shared by the Recipe Viewer screen and the
 * always-visible search bar drawn over container screens.
 *
 * <p>Holds the raw query text (what the bar displays and edits), the normalised query used for
 * matching, whether the overlay bar currently has keyboard focus, and whether Search Highlight Mode
 * (toggled by double-clicking either search bar) is on. While enabled, every visible container slot
 * whose item matches is highlighted by the existing container-highlight render pass.
 *
 * <p>Matching itself lives in {@link ItemSearchMatcher} – name, SkyBlock id, every lore line and
 * every enchant, with all terms required. Written on the client thread, read during rendering –
 * hence volatile.
 */
public final class SearchHighlightState {

    private static final SearchHighlightState INSTANCE = new SearchHighlightState();

    private static final int MAX_LENGTH = 64;

    private volatile boolean enabled;
    private volatile boolean searchFocused;
    private volatile String rawQuery = "";
    private volatile String query = "";

    /**
     * The query pre-split into the terms every match must contain. Parsed once here rather than in
     * {@link #matches}, which runs per slot per frame. Never mutated after it is published.
     */
    private volatile String[] terms = new String[0];

    /** Ctrl+A state: the whole query is selected – the next edit replaces / clears it. */
    private volatile boolean allSelected;

    private SearchHighlightState() {
    }

    public static SearchHighlightState getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Whether the overlay search bar (on container screens) has keyboard focus. */
    public boolean isSearchFocused() {
        return searchFocused;
    }

    public void setSearchFocused(boolean focused) {
        this.searchFocused = focused;
    }

    /** The raw query text as typed (shown in the overlay bar). */
    public String rawQuery() {
        return rawQuery;
    }

    /** Updates the live query (lower-cased once here so per-slot checks stay cheap). */
    public void setQuery(String query) {
        this.rawQuery = query == null ? "" : query;
        this.query = this.rawQuery.trim().toLowerCase(Locale.ROOT);
        this.terms = ItemSearchMatcher.terms(this.query);
        this.allSelected = false;
    }

    /** Ctrl+A in the overlay bar: selects the whole query (next edit replaces / clears it). */
    public void selectAll() {
        this.allSelected = !rawQuery.isEmpty();
    }

    /** True while the whole query is selected (rendered highlighted). */
    public boolean isAllSelected() {
        return allSelected;
    }

    /** Appends typed text (from the overlay bar's charTyped); replaces a full selection. */
    public void type(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (allSelected) {
            setQuery(text);
            return;
        }
        if (rawQuery.length() < MAX_LENGTH) {
            setQuery(rawQuery + text);
        }
    }

    /** Removes the last character (overlay bar backspace); clears a full selection. */
    public void backspace() {
        if (allSelected) {
            setQuery("");
            return;
        }
        String raw = rawQuery;
        if (!raw.isEmpty()) {
            setQuery(raw.substring(0, raw.length() - 1));
        }
    }

    /**
     * Removes the last whole word (Ctrl+Backspace / Ctrl+Delete), like every regular text input:
     * trailing spaces go first, then the word before them. The query has no cursor – it always
     * edits at its end – so "previous word" and "next word" collapse into the same delete.
     */
    public void backspaceWord() {
        if (allSelected) {
            setQuery("");
            return;
        }
        String raw = rawQuery;
        int i = raw.length();
        while (i > 0 && raw.charAt(i - 1) == ' ') {
            i--;
        }
        while (i > 0 && raw.charAt(i - 1) != ' ') {
            i--;
        }
        setQuery(raw.substring(0, i));
    }

    /** True when highlighting is on, a query is present, and the stack matches it. */
    public boolean matches(ItemStack stack) {
        if (!enabled) {
            return false;
        }
        return ItemSearchMatcher.matches(stack, this.terms,
                ConfigManager.getInstance().get().recipeViewer.searchTooltips);
    }
}
