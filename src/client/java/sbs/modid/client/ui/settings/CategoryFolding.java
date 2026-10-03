/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which groups of the settings sidebar are folded away, and what decides that when the config is
 * opened.
 *
 * <p>The fold state itself is a plain static set: the module list is one list, shared by every
 * instance of the config screen, and a fold the player made should still be there when they come
 * back from a sub-editor. What is new here is that the set is <i>seeded</i> - by {@link Mode},
 * chosen by the player - instead of always starting empty.
 *
 * <p><b>"Startup" is this JVM run.</b> {@link #startupApplied} is a static with no persistence, so
 * it cannot survive a restart; that is precisely the semantics {@link Mode#OPEN_ON_STARTUP} and
 * {@link Mode#CLOSED_ON_STARTUP} need, and it is why they need no timestamp or marker file.
 *
 * <p><b>Sub-headers fold the same way.</b> A subdivided group (see
 * {@link sbs.modid.client.core.module.ModuleSubgroup}) has a second level, and every mode applies
 * to it exactly as to the groups: "closed" folds the sub-headers too, so opening Skills shows six
 * compact families rather than thirty cards. They are kept in a second set, keyed by
 * {@link #subKey}, because a sub-header is a (group, subgroup) pair and the General bucket has no
 * constant of its own.
 *
 * <p>This lives outside {@code SBSMainScreen} because the settings row that picks the mode is
 * declared by a module, and a config row reaching into a {@code Screen} class to change what it
 * draws is the wrong direction. Both sides talk to this instead, and the screen notices a change it
 * did not make itself through {@link #revision()}.
 */
public final class CategoryFolding {

    /**
     * What the fold state is when the settings screen is opened.
     *
     * <p><b>Always vs on startup is reach, not wording.</b> An {@code ALWAYS_} mode re-applies every
     * time the config is opened; an {@code _ON_STARTUP} mode applies once per game launch and then
     * leaves the player's folds alone for the rest of the session.
     */
    public enum Mode {

        /** Unfolded every time the config is opened. */
        ALWAYS_OPEN("Always Open"),

        /** Folded every time the config is opened. */
        ALWAYS_CLOSED("Always Closed"),

        /** Unfolded the first time the config is opened after a launch; kept after that. */
        OPEN_ON_STARTUP("Open On Startup"),

        /** Folded the first time the config is opened after a launch; kept after that. */
        CLOSED_ON_STARTUP("Closed On Startup"),

        /** The folds are written to the config and restored across restarts. */
        SAVE_CURRENT("Save Current");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        /** The name shown in the config row. Unique - the row matches a pick by this string. */
        public String displayName() {
            return displayName;
        }
    }

    /**
     * The groups folded away right now - the live truth the sidebar draws from.
     *
     * <p>{@link ModuleGroup#PINNED} is never in here: it has no header, so there is nothing to click
     * and nothing to unfold it with again.
     */
    private static final Set<ModuleGroup> COLLAPSED = EnumSet.noneOf(ModuleGroup.class);

    /**
     * The sub-headers folded away right now, as {@link #subKey} strings.
     *
     * <p>Sorted, so the list written under {@link Mode#SAVE_CURRENT} is the same between two saves of
     * the same folds - the property the group set gets from being an {@code EnumSet}.
     */
    private static final Set<String> COLLAPSED_SUBS = new TreeSet<>();

    /**
     * The key part standing for a group's General bucket, which has no {@link ModuleSubgroup}
     * constant. Reserved: {@code ModuleSubgroupTest} fails if a constant takes the name.
     */
    public static final String GENERAL_KEY = "GENERAL";

    /**
     * The custom sidebar categories folded away right now, by their generated ids (see
     * {@link sbs.modid.client.ui.settings.layout.SidebarLayout}). Keyed on the id and never the name,
     * so renaming a category keeps its fold. Built-in categories in a custom layout keep using the
     * group set above - a group is still the same group wherever the player moved it.
     */
    private static final Set<String> COLLAPSED_CUSTOM = new TreeSet<>();

    /**
     * The custom category ids that exist right now, so "closed" modes can fold them. Supplied rather
     * than read from the config here, which keeps this class testable without one.
     */
    private static java.util.function.Supplier<java.util.Collection<String>> customIds =
            CategoryFolding::storedCustomIds;

    /** Whether this launch has already had its startup state applied. Never persisted - see above. */
    private static boolean startupApplied;

    /**
     * Bumped on every change to {@link #COLLAPSED}.
     *
     * <p>An open config screen caches this and re-filters when it moves, which is what makes picking
     * a mode fold the sidebar on the left while the row that changed it is still on the right. The
     * alternative - handing this class a reference to the live screen - would have to survive the
     * screen being replaced, which is the bug that reference would exist to cause.
     */
    private static int revision;

    private CategoryFolding() {
    }

    /** The chosen mode; the default when the config predates this setting. */
    public static Mode mode() {
        Mode stored = ConfigManager.getInstance().get().gui.categoryFolding;
        return stored == null ? Mode.CLOSED_ON_STARTUP : stored;
    }

    /** Whether this group is folded away right now. */
    public static boolean collapsed(ModuleGroup group) {
        return group != null && COLLAPSED.contains(group);
    }

    /**
     * The fold key of one sub-header: {@code GROUP/SUBGROUP} by constant <b>name</b>, with
     * {@link #GENERAL_KEY} for the bucket of modules that declare no subgroup.
     *
     * <p>Names and never ordinals, for the reason {@link #writeCollapsed} gives. The group is part
     * of the key because General exists once per subdivided group and needs telling apart.
     */
    public static String subKey(ModuleGroup group, ModuleSubgroup subgroup) {
        return group.name() + "/" + (subgroup == null ? GENERAL_KEY : subgroup.name());
    }

    /** Whether this sub-header of this group is folded away right now. */
    public static boolean collapsed(ModuleGroup group, ModuleSubgroup subgroup) {
        return group != null && COLLAPSED_SUBS.contains(subKey(group, subgroup));
    }

    /** Whether the custom category {@code id} is folded away right now. */
    public static boolean collapsedCustom(String id) {
        return id != null && COLLAPSED_CUSTOM.contains(id);
    }

    /** Folds or unfolds a custom category - what a click on its header does. Saved like a group fold. */
    public static void toggleCustom(String id) {
        if (id == null) {
            return;
        }
        if (!COLLAPSED_CUSTOM.remove(id)) {
            COLLAPSED_CUSTOM.add(id);
        }
        changedByPlayer();
    }

    /** Unfolds a custom category holding the selection. Same rules as {@link #reveal}. */
    public static void revealCustom(String id) {
        if (id != null && COLLAPSED_CUSTOM.remove(id)) {
            revision++;
        }
    }

    /** The mode as this class reads it; a seam so the fold logic can be tested without a config. */
    private static java.util.function.Supplier<Mode> modeSource = CategoryFolding::mode;

    /** Test seam: which mode the fold logic sees. */
    static void modeSource(java.util.function.Supplier<Mode> source) {
        modeSource = source;
    }

    /** Test seam: where the live custom category ids come from. */
    static void customIdSource(java.util.function.Supplier<java.util.Collection<String>> source) {
        customIds = source;
    }

    private static java.util.Collection<String> storedCustomIds() {
        var layout = sbs.modid.client.ui.settings.layout.SidebarLayout.decode(
                ConfigManager.getInstance().get().gui.sidebarLayout);
        return layout == null ? java.util.List.of() : layout.customIds();
    }

    /** @see #revision */
    public static int revision() {
        return revision;
    }

    /**
     * Folds an unfolded group, unfolds a folded one - what a click on a header does.
     *
     * <p>Under {@link Mode#SAVE_CURRENT} this is also the moment the set is written to disk: a fold
     * is the whole state that mode remembers, so there is nothing else to save it on.
     */
    public static void toggle(ModuleGroup group) {
        if (group == null || group == ModuleGroup.PINNED) {
            return;
        }
        if (!COLLAPSED.remove(group)) {
            COLLAPSED.add(group);
        }
        changedByPlayer();
    }

    /** Folds or unfolds one sub-header - what a click on it does. Saved like a group fold. */
    public static void toggle(ModuleGroup group, ModuleSubgroup subgroup) {
        if (group == null || group == ModuleGroup.PINNED) {
            return;
        }
        String key = subKey(group, subgroup);
        if (!COLLAPSED_SUBS.remove(key)) {
            COLLAPSED_SUBS.add(key);
        }
        changedByPlayer();
    }

    /** After a fold the player made: redraw, and under {@link Mode#SAVE_CURRENT} write it down. */
    private static void changedByPlayer() {
        revision++;
        if (modeSource.get() == Mode.SAVE_CURRENT) {
            SBSConfig config = ConfigManager.getInstance().get();
            writeCollapsed(config);
            ConfigManager.getInstance().save();
        }
    }

    /**
     * Unfolds whatever hides a module's row: its group, and its sub-header if the group has them.
     *
     * <p>For the config opening on a module the player cannot see - the selection outlives the fold
     * that hides it, so the settings on the right would belong to nothing on the left. Called once
     * when the config is opened afresh and when a jump lands, and <b>never</b> from the screen's
     * refilter, which runs on every fold click: the group holding the selection would spring open
     * again the moment the player folded it.
     *
     * <p>Not written to disk on its own - it is not something the player did. Under
     * {@link Mode#SAVE_CURRENT} the next fold they make saves what is on screen, revealed group
     * included, which is that mode's own promise applied to what they were looking at.
     *
     * @param subdivided whether {@code group} is drawn with sub-headers; a flat group's General key
     *                   is never drawn, so there is nothing to unfold
     */
    public static void reveal(ModuleGroup group, ModuleSubgroup subgroup, boolean subdivided) {
        if (group == null || group == ModuleGroup.PINNED) {
            return;
        }
        boolean changed = COLLAPSED.remove(group);
        if (subdivided) {
            changed |= COLLAPSED_SUBS.remove(subKey(group, subgroup));
        }
        if (changed) {
            revision++;
        }
    }

    /**
     * The config screen was opened afresh - seed the fold state from the mode.
     *
     * <p>Called from the screen's constructor rather than from {@code init()}, which also runs on
     * every window resize: applying there would re-fold the sidebar whenever the player resized the
     * game. And not on a Back from a sub-editor either - see {@code SBSMainScreen}'s constructor for
     * how that is told apart.
     */
    public static void configOpened() {
        boolean firstOpen = !startupApplied;
        startupApplied = true;
        switch (modeSource.get()) {
            case ALWAYS_OPEN -> openAll();
            case ALWAYS_CLOSED -> closeAll();
            case OPEN_ON_STARTUP -> {
                if (firstOpen) {
                    openAll();
                }
            }
            case CLOSED_ON_STARTUP -> {
                if (firstOpen) {
                    closeAll();
                }
            }
            case SAVE_CURRENT -> {
                if (firstOpen) {
                    restore();
                }
            }
        }
    }

    /**
     * The player picked a mode: store it, and apply it at once so the sidebar shows what it means.
     *
     * <p>Applying immediately is also what settles the startup modes for this launch - picking
     * "Closed On Startup" folds everything now and does not do it again until the next launch, which
     * is what the player just watched happen.
     *
     * <p>{@link Mode#SAVE_CURRENT} is the one that changes nothing on screen: it takes whatever is
     * folded right now as the thing to remember, which is what its name promises.
     */
    public static void pick(Mode picked) {
        if (picked == null) {
            return;
        }
        SBSConfig config = ConfigManager.getInstance().get();
        config.gui.categoryFolding = picked;
        startupApplied = true;
        switch (picked) {
            case ALWAYS_OPEN, OPEN_ON_STARTUP -> openAll();
            case ALWAYS_CLOSED, CLOSED_ON_STARTUP -> closeAll();
            case SAVE_CURRENT -> writeCollapsed(config);
        }
        ConfigManager.getInstance().save();
    }

    private static void openAll() {
        COLLAPSED.clear();
        COLLAPSED_SUBS.clear();
        COLLAPSED_CUSTOM.clear();
        revision++;
    }

    private static void closeAll() {
        for (ModuleGroup group : ModuleGroup.values()) {
            if (group != ModuleGroup.PINNED) {
                COLLAPSED.add(group);
            }
        }
        COLLAPSED_SUBS.addAll(allSubKeys());
        COLLAPSED_CUSTOM.addAll(customIds.get());
        revision++;
    }

    /**
     * Every sub-header key that can exist: each subgroup constant, plus the General bucket of every
     * group that owns one.
     *
     * <p>From the enum rather than the module list, so {@link #closeAll} and {@link #restore} need no
     * catalogue - and a key here whose header happens not to be drawn (a General bucket nobody is in)
     * is folded harmlessly and never seen.
     */
    private static Set<String> allSubKeys() {
        Set<String> keys = new TreeSet<>();
        for (ModuleSubgroup subgroup : ModuleSubgroup.values()) {
            keys.add(subKey(subgroup.group(), subgroup));
            keys.add(subKey(subgroup.group(), null));
        }
        return keys;
    }

    /**
     * Reads the saved fold set back.
     *
     * <p>Matched by name against the live constants rather than parsed: a name that no longer exists
     * is skipped and that group simply starts open, where {@code Enum.valueOf} would throw on a set
     * written by a build whose groups were not quite these.
     */
    private static void restore() {
        COLLAPSED.clear();
        Set<String> saved = ConfigManager.getInstance().get().gui.collapsedCategories;
        if (saved != null && !saved.isEmpty()) {
            for (ModuleGroup group : ModuleGroup.values()) {
                if (group != ModuleGroup.PINNED && saved.contains(group.name())) {
                    COLLAPSED.add(group);
                }
            }
        }
        // Same rule for sub-headers: only keys a live constant still produces come back, so a
        // subgroup renamed or removed since the save starts open instead of being mis-read.
        COLLAPSED_SUBS.clear();
        Set<String> savedSubs = ConfigManager.getInstance().get().gui.collapsedSubgroups;
        if (savedSubs != null && !savedSubs.isEmpty()) {
            for (String key : allSubKeys()) {
                if (savedSubs.contains(key)) {
                    COLLAPSED_SUBS.add(key);
                }
            }
        }
        // Custom categories: only ids a live category still has come back.
        COLLAPSED_CUSTOM.clear();
        Set<String> savedCustom = ConfigManager.getInstance().get().gui.collapsedCustomCategories;
        if (savedCustom != null && !savedCustom.isEmpty()) {
            for (String id : customIds.get()) {
                if (savedCustom.contains(id)) {
                    COLLAPSED_CUSTOM.add(id);
                }
            }
        }
        revision++;
    }

    /**
     * Copies the fold set into the config as constant <b>names</b>.
     *
     * <p>Never ordinals: inserting a group into {@link ModuleGroup} shifts every position after it,
     * and a saved layout would then quietly come back pointing at different categories. Iteration
     * order of an {@code EnumSet} is declaration order, so the written list is stable between saves.
     */
    private static void writeCollapsed(SBSConfig config) {
        Set<String> names = new LinkedHashSet<>();
        for (ModuleGroup group : COLLAPSED) {
            names.add(group.name());
        }
        config.gui.collapsedCategories = names;
        // Sub-headers go to their own field: the one above is rebuilt from the groups on every
        // write, so a sub key stored there would be lost on the next group fold.
        config.gui.collapsedSubgroups = new LinkedHashSet<>(COLLAPSED_SUBS);
        config.gui.collapsedCustomCategories = new LinkedHashSet<>(COLLAPSED_CUSTOM);
    }
}
