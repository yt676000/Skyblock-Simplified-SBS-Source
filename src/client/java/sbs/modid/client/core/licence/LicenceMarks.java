/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.LicenceToken;
import sbs.modid.client.ui.settings.ModuleSettings;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Marks the settings that need a licence token while none is set: the config screen paints them in
 * the same red an undercut Bazaar order wears, so "which of this actually works for me" is answerable
 * by looking instead of by trying each one and waiting for nothing to happen.
 *
 * <p><b>What is marked, and by whom.</b> The truth lives on the rows themselves -
 * {@link SettingRow#licenced} - not in a list here. A list of module ids was the obvious design and
 * is wrong: most licence-gated features are one row on a page whose other rows work offline, so
 * module-level marking would paint the whole Bazaar page red on account of one flip feed. The
 * sidebar entry is <i>derived</i> from the rows ({@link #modules()}), which is why the two can never
 * disagree.
 *
 * <p><b>Marking is not the same as breaking.</b> Some of these degrade rather than stop - Bazaar
 * flips fall back to the local engine, item appraisal keeps its "Now" column - so a marked row
 * carries a note saying what you still get. A red row that silently works fine would teach players
 * to ignore the colour, which costs more than the colour is worth.
 *
 * <p>Nothing is marked once a token is set, and the whole thing is one switch away from off.
 */
public final class LicenceMarks {

    /**
     * Derived module set, filled in slices by {@link #advance}. The row structure does not change
     * within a session, so what lands here stays true for it.
     */
    private static final Set<String> found = new HashSet<>();

    /** The walk in progress: the categories to index, and how far {@link #advance} has got. */
    private static List<sbs.modid.client.core.module.ModuleCategory> pending;
    private static int cursor;
    private static boolean ready;

    private LicenceMarks() {
    }

    /**
     * Whether licence marks should be drawn at all: the setting is on <b>and</b> no token is set.
     *
     * <p>The token check is the point of the feature, not an optimisation - with a licence there is
     * nothing to warn about, and a permanent badge on features you have paid for would be noise.
     */
    public static boolean marking() {
        return ConfigManager.getInstance().get().licence.markLicenceFeatures
                && !LicenceToken.getInstance().isSet();
    }

    /** True when this row needs a token and marks are currently being drawn. */
    public static boolean marked(SettingRow row) {
        return row != null && row.isLicenced() && marking();
    }

    /**
     * The ids of modules holding at least one licence-gated row, so the sidebar can mark the entry
     * rather than leaving the red rows to be discovered by opening every page.
     *
     * <p>Built by walking the same rows the config screen builds, which means building every
     * module's rows — the single most expensive thing this mod does on a config-screen frame, and
     * unavoidable, since the answer is derived from the rows and nowhere else. So it is built in
     * slices by {@link #advance}, and this returns whatever is known so far. Failures are swallowed
     * per module on purpose: a module whose settings cannot be listed outside a running game (a
     * handful read Minecraft's own options) must not take the sidebar down with it.
     *
     * <p>This accessor is the <b>complete</b> one: it finishes the walk before answering, so a caller
     * that needs certainty gets it. <b>Do not call it from a render path</b> — blocking here is what
     * made opening the config screen cost half a second. The sidebar uses {@link #markedModule},
     * which reads whatever is indexed so far and never waits.
     */
    public static synchronized Set<String> modules() {
        advance(Integer.MAX_VALUE);
        return Set.copyOf(found);
    }

    /**
     * Builds up to {@code budget} more modules into the index.
     *
     * <p>Returns {@code true} once the whole set is known, so a caller can stop asking. Cheap and
     * safe to call every frame: past the end it does nothing.
     */
    public static synchronized boolean advance(int budget) {
        if (ready) {
            return true;
        }
        if (pending == null) {
            pending = List.copyOf(
                    sbs.modid.client.core.module.ModuleManager.getInstance().getCategories());
        }
        // Widened deliberately: modules() passes Integer.MAX_VALUE to force completion, and
        // cursor + that overflows to a negative end, which would silently index nothing and leave
        // the walk permanently unfinished.
        int end = (int) Math.min(pending.size(), (long) cursor + Math.max(1, budget));
        for (; cursor < end; cursor++) {
            String moduleId = pending.get(cursor).id();
            try {
                for (SettingRow row : ModuleSettings.rowsFor(moduleId)) {
                    if (row.isLicenced()) {
                        found.add(moduleId);
                        break;
                    }
                }
            } catch (Exception | LinkageError ignored) {
                // Listed as unmarked rather than not listed at all - see the method doc.
            }
        }
        if (cursor >= pending.size()) {
            ready = true;
            pending = null;
        }
        return ready;
    }

    /** Whether the index is complete. Until it is, {@link #markedModule} under-reports. */
    public static synchronized boolean ready() {
        return ready;
    }

    /**
     * Drops the index so it rebuilds from the start. Needed when the set of modules changes — dev
     * mode being switched on adds a card — not when a value changes.
     */
    public static synchronized void invalidate() {
        found.clear();
        pending = null;
        cursor = 0;
        ready = false;
    }

    /**
     * True when this module holds a licence-gated row and marks are currently being drawn.
     *
     * <p>Answers from what is indexed so far and never waits for the rest. A module whose slice has
     * not run yet reads as unmarked for a frame or two, which is invisible; blocking the frame to
     * be certain is not.
     */
    public static boolean markedModule(String moduleId) {
        return moduleId != null && marking() && indexedSoFar(moduleId);
    }

    /** The partial read behind {@link #markedModule}: what the walk has reached, without advancing it. */
    private static synchronized boolean indexedSoFar(String moduleId) {
        return found.contains(moduleId);
    }
}
