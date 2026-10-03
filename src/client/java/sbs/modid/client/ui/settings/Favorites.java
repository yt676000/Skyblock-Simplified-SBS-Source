/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings a player pinned, and the page that shows them.
 *
 * <p><b>One source of truth, by construction.</b> A favourite is stored as an option id and nothing
 * else – no value, no copy. The page resolves each id back to the row its own module builds and
 * shows <i>that</i> row, so the control on this page is the control on its home page: the same
 * supplier reading the same config field, the same action writing it. There is nothing to keep in
 * sync because there is only ever one of everything.
 *
 * <p><b>Unresolvable ids are hidden, not deleted.</b> A favourite that no longer resolves is left
 * out of the page and logged, but stays in the config. A removed feature and a module that merely
 * failed to describe itself this session look identical from here, and quietly rewriting the stored
 * list on the second one would throw away favourites that a restart would have brought back. The log
 * line is what tells the two apart after a release – a rename shows up as a warning about an id that
 * used to exist, which is the moment to add an alias to its row.
 */
public final class Favorites {

    /** Module id of the favourites page itself. Not a real module – it owns no settings of its own. */
    public static final String PAGE_ID = "favorites";

    private Favorites() {
    }

    private static SBSConfig.FavoritesSettings cfg() {
        return ConfigManager.getInstance().get().favorites;
    }

    /** The stored ids, in the player's order, including any that do not currently resolve. */
    public static List<String> stored() {
        List<String> ids = cfg().optionIds;
        return ids == null ? List.of() : List.copyOf(ids);
    }

    public static boolean isFavorite(String optionId) {
        return optionId != null && cfg().optionIds != null && cfg().optionIds.contains(optionId);
    }

    public static boolean isEmpty() {
        return cfg().optionIds == null || cfg().optionIds.isEmpty();
    }

    /**
     * Adds or removes a favourite, keeping insertion order – new favourites go to the end, and the
     * order therefore never changes on its own between sessions.
     */
    public static void toggle(String optionId) {
        if (optionId == null || optionId.isEmpty()) {
            return;
        }
        SBSConfig.FavoritesSettings settings = cfg();
        if (settings.optionIds == null) {
            settings.optionIds = new ArrayList<>();
        }
        if (!settings.optionIds.remove(optionId)) {
            settings.optionIds.add(optionId);
        }
        ConfigManager.getInstance().save();
    }

    /**
     * The favourites page: for each favourite, a button showing where the option lives (which is
     * also the jump), then the option's own live control.
     *
     * <p>Two rows per favourite rather than one row with extra chrome: every settings page in the mod
     * draws rows of one fixed height, and a favourite that needs a path, a control and a jump button
     * does not fit one. Using two ordinary rows keeps the favourites page inside the renderer every
     * other page uses – no second layout to keep working.
     */
    public static List<SettingRow> rows() {
        List<String> ids = stored();
        if (ids.isEmpty()) {
            return List.of(
                    SettingRow.label("No favourites yet"),
                    SettingRow.label("Click the star at the right of any setting to pin it here"),
                    SettingRow.label("§8Favourites stay across restarts and follow the option, not its name"));
        }
        Map<String, List<SettingRow>> moduleRows = new HashMap<>();
        List<SettingRow> out = new ArrayList<>(ids.size() * 2);
        List<String> unresolved = new ArrayList<>();

        for (String optionId : ids) {
            OptionIndex.Entry entry = OptionIndex.byId(optionId);
            if (entry == null) {
                unresolved.add(optionId);
                continue;
            }
            List<SettingRow> rows = moduleRows.computeIfAbsent(entry.moduleId(), id -> {
                try {
                    return ModuleSettings.rowsFor(id);
                } catch (Throwable t) {
                    SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Favourites] module '{}' failed to list settings", id, t);
                    return List.of();
                }
            });
            SettingRow live = find(rows, entry);
            if (live == null) {
                unresolved.add(optionId);
                continue;
            }
            out.add(SettingRow.button(entry.path(), () -> ConfigNavigator.jumpTo(optionId))
                    .anchor("goto_" + optionId.replace(':', '_'))
                    .describe("Opens this setting where it lives, scrolled to it and highlighted."));
            out.add(live.owner(entry.moduleId()));
        }
        if (!unresolved.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Favourites] {} favourite(s) did not resolve and "
                    + "are hidden (kept in the config): {}", unresolved.size(), unresolved);
        }
        return out;
    }

    /**
     * The live row behind an index entry: matched by id first, by position only as a fallback.
     *
     * <p>The index records where a row sat when it was built, but a module is free to build a
     * different number of rows later. Matching on the id is what makes a favourite survive that;
     * the position is only there for the case where the id genuinely changed underneath us.
     */
    private static SettingRow find(List<SettingRow> rows, OptionIndex.Entry entry) {
        String rowId = entry.optionId().substring(entry.moduleId().length() + 1);
        for (SettingRow row : rows) {
            if (!row.isLabel() && row.id().equals(rowId)) {
                return row;
            }
        }
        int index = entry.rowIndex();
        return index >= 0 && index < rows.size() && !rows.get(index).isLabel() ? rows.get(index) : null;
    }
}
