/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.fabricmc.loader.api.FabricLoader;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.module.ModuleCategory;
import sbs.modid.client.core.module.ModuleManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every configurable option in the mod, addressable by a stable id.
 *
 * <p>This is the shared foundation three features resolve through – the favourites list, the HUD
 * quick-edit click-through, and the overlay inspector – so that "which option is this and where does
 * it live" is answered in one place with one set of rules.
 *
 * <p><b>Why an index rather than a search over rows.</b> {@link ModuleSettings#rowsFor} builds a
 * fresh list every time it is called, and the config search used to call it for every module on
 * every keystroke. The rows themselves must keep being rebuilt (some carry live text – a reminder's
 * status line is baked into its label), but their <i>identity</i> is structural and does not change
 * between builds. Caching that, and searching it instead of rebuilding everything, is the whole
 * point: the index is built once and answers every lookup from memory.
 *
 * <p><b>Ids.</b> {@code moduleId:rowId}, where the row id is its {@link SettingRow#anchor} or a slug
 * of its label. Rows that only explain something ({@link SettingRow#isLabel()}) are not options and
 * are left out – they are also the only rows whose text is known to change at runtime, so leaving
 * them out is what keeps derived ids stable.
 */
public final class OptionIndex {

    /**
     * One option: its id, where it lives, and where it sits in its module's row list.
     *
     * <p>{@code section} is the middle segment of the display path and is empty everywhere today –
     * the config is two levels deep. It is in the model from the start because the path is what
     * three features render and navigate by, and widening that record later means changing a
     * signature at the exact point they all meet.
     */
    public record Entry(String optionId, String moduleId, String moduleName, String section,
                        String label, int rowIndex) {

        /** The origin path shown to the player, e.g. {@code Dungeons ▸ Room Colors}. */
        public String path() {
            return section == null || section.isEmpty()
                    ? moduleName + " ▸ " + label
                    : moduleName + " ▸ " + section + " ▸ " + label;
        }
    }

    private static Map<String, Entry> byId;
    private static Map<String, String> byAlias;
    private static List<Entry> ordered;
    private static boolean audited;

    private OptionIndex() {
    }

    /**
     * Builds the index once, early, so the duplicate-id audit runs whether or not anyone opens the
     * config. Called from the client tick.
     *
     * <p>Not from the mod initializer, which was tried and is too early: that entrypoint runs inside
     * {@code Minecraft}'s constructor, where {@code Minecraft.getInstance().options} is still null,
     * and a module whose settings mention the render distance throws while listing them. Its options
     * would then be missing from an index built for the rest of the session – so the audit would pass
     * on a config that is not the one the player sees.
     */
    public static synchronized void auditOnce() {
        if (audited) {
            return;
        }
        audited = true;
        ensureBuilt();
    }

    /** Every option, in sidebar order then row order. */
    public static synchronized List<Entry> all() {
        ensureBuilt();
        return ordered;
    }

    /**
     * The option with this id, following aliases, or {@code null} when nothing answers to it.
     *
     * <p>A {@code null} here is the normal way a stored reference to a removed feature dies.
     */
    public static synchronized Entry byId(String optionId) {
        if (optionId == null || optionId.isEmpty()) {
            return null;
        }
        ensureBuilt();
        Entry direct = byId.get(optionId);
        if (direct != null) {
            return direct;
        }
        String canonical = byAlias.get(optionId);
        return canonical == null ? null : byId.get(canonical);
    }

    /**
     * Whether any option in this module matches the query – the config search's per-module test.
     *
     * <p>Answered from the index instead of building every module's rows on every keystroke, which
     * is what the sidebar filter used to do. Note this searches <i>options</i>: the explanatory label
     * rows are not in the index, so a module no longer matches merely because its prose mentions the
     * word – which is the tighter and more useful reading of a settings search.
     */
    public static synchronized boolean moduleMatches(String moduleId, String query) {
        if (moduleId == null || query == null || query.isEmpty()) {
            return false;
        }
        ensureBuilt();
        for (Entry entry : ordered) {
            if (entry.moduleId().equals(moduleId)
                    && entry.label().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    /** Options whose label matches the query, for the config search. */
    public static synchronized List<Entry> search(String query) {
        ensureBuilt();
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Entry> hits = new ArrayList<>();
        for (Entry entry : ordered) {
            if (entry.label().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                hits.add(entry);
            }
        }
        return hits;
    }

    /**
     * Drops the cache so the next lookup rebuilds.
     *
     * <p>Needed when the set of modules changes – dev mode being switched on adds a card – not when
     * a value changes: values live in the config and are read through the rows' own suppliers, so
     * the index never holds one.
     */
    public static synchronized void invalidate() {
        byId = null;
        byAlias = null;
        ordered = null;
    }

    private static void ensureBuilt() {
        if (byId != null) {
            return;
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> collisions = new ArrayList<>();

        for (ModuleCategory category : ModuleManager.getInstance().getCategories()) {
            String moduleId = category.id();
            // The favourites page borrows its rows from other modules, and resolving them asks this
            // index for them - indexing it would recurse, and its entries are not options of its own.
            if (Favorites.PAGE_ID.equals(moduleId)) {
                continue;
            }
            String moduleName = category.displayName().getString();
            List<SettingRow> rows;
            try {
                rows = ModuleSettings.rowsFor(moduleId);
            } catch (Throwable t) {
                // One module that throws while listing its settings must not cost every other
                // module its ids - the favourites of unrelated features would vanish with it.
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Options] module '{}' failed to list settings",
                        moduleId, t);
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                SettingRow row = rows.get(i);
                if (row.isLabel()) {
                    continue;
                }
                String optionId = moduleId + ":" + row.id();
                Entry entry = new Entry(optionId, moduleId, moduleName, "", row.label(), i);
                Entry previous = entries.putIfAbsent(optionId, entry);
                if (previous != null) {
                    // First row wins, and "first" is well defined: sidebar order, then row order,
                    // both stable across runs. Two players therefore never resolve the same
                    // colliding pair to different options.
                    collisions.add(optionId + " (\"" + previous.label() + "\" and \""
                            + row.label() + "\")");
                    continue;
                }
                for (String alias : row.aliases()) {
                    aliases.putIfAbsent(moduleId + ":" + alias, optionId);
                }
            }
        }

        byId = entries;
        byAlias = aliases;
        ordered = List.copyOf(entries.values());
        // Logged on success as well as failure: a silent audit cannot be told apart from one that
        // never ran, and this is the line that says which config the ids were built from.
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Options] indexed {} options ({} aliases)",
                entries.size(), aliases.size());
        report(collisions);
    }

    /**
     * What happens when two rows in one module claim the same id.
     *
     * <p>Loud in development, because a collision means one of the two options is unreachable by id
     * and the fix is one {@link SettingRow#anchor} call. Quiet in a release – a player can neither
     * fix it nor act on it – but resolved the same way everywhere, so a collision is a bug that
     * behaves identically for everyone rather than one that depends on who is running it.
     */
    private static void report(List<String> collisions) {
        if (collisions.isEmpty()) {
            return;
        }
        String detail = String.join(", ", collisions);
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Options] duplicate option ids: {}", detail);
            throw new IllegalStateException("Duplicate config option ids - give one of each pair a "
                    + "SettingRow.anchor(...): " + detail);
        }
        SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Options] duplicate option ids (first wins): {}", detail);
    }

    /** Unmodifiable view of the id → entry map, for tooling. */
    public static synchronized Map<String, Entry> asMap() {
        ensureBuilt();
        return Collections.unmodifiableMap(byId);
    }
}
