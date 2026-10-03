/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

import net.minecraft.network.chat.Component;
import sbs.modid.client.core.module.Searchable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A top-level category shown in the main module list (Combat, Dungeons, ...).
 *
 * <p>Currently categories are empty shells. They already own a list of
 * {@link Module}s so features can be dropped in later without changing the GUI or
 * the search system. Matching is delegated through {@link Searchable}, and also
 * considers the contained modules so a search hits a category whenever any of its
 * modules matches.
 */
public final class ModuleCategory implements Searchable {

    private final String id;
    private final ModuleGroup group;
    private final Component displayName;
    private final Component description;
    private final int accentColor;
    /** The family inside {@link #group} this module is listed under, or {@code null} for General. */
    private final ModuleSubgroup subgroup;
    private final List<Module> modules = new ArrayList<>();

    public ModuleCategory(String id, ModuleGroup group, Component displayName,
                          Component description, int accentColor) {
        this(id, group, null, displayName, description, accentColor);
    }

    public ModuleCategory(String id, ModuleGroup group, ModuleSubgroup subgroup,
                          Component displayName, Component description, int accentColor) {
        this.id = id;
        this.group = group;
        this.subgroup = subgroup;
        this.displayName = displayName;
        this.description = description;
        this.accentColor = accentColor;
    }

    public String id() {
        return id;
    }

    /** The top-level group this module is listed under (see {@link ModuleGroup}). */
    public ModuleGroup group() {
        return group;
    }

    /**
     * The family inside {@link #group()} this module is listed under - see {@link ModuleSubgroup}.
     * {@code null} means General: in a subdivided group that is the trailing bucket, in any other
     * group there is no second level at all.
     */
    public ModuleSubgroup subgroup() {
        return subgroup;
    }

    public Component displayName() {
        return displayName;
    }

    public Component description() {
        return description;
    }

    public int accentColor() {
        return accentColor;
    }

    public List<Module> modules() {
        return Collections.unmodifiableList(modules);
    }

    public void addModule(Module module) {
        modules.add(module);
    }

    /** Secondary line shown under the name in the list (module count / placeholder). */
    public Component subtitle() {
        int count = modules.size();
        if (count == 0) {
            return Component.literal("Coming soon");
        }
        return Component.literal(count + (count == 1 ? " module" : " modules"));
    }

    /**
     * Whether the module itself matches a lower-case query: its own name, its one-line description,
     * the group header above it, or the subgroup header above that.
     *
     * <p>The headers count so that typing a header finds everything under it - "dungeons" lists the
     * Dungeons group, and "foraging" lists the whole Foraging subgroup, including <i>Sweep</i>,
     * <i>Honey</i> and <i>Beacon Tuner</i>, none of which say the word. The description counts because
     * the name is the term we picked, and a player searching for what a feature does does not have it.
     *
     * <p>"General" is not a match: it names a bucket of leftovers, not a family, and nobody typing it
     * means "every card that has no family, across every group".
     *
     * <p>Lives here rather than in the config screen so there is one definition of "this module is a
     * hit by name", and so it can be tested without a screen.
     */
    public boolean matchesByName(String lowerCaseQuery) {
        String q = lowerCaseQuery;
        if (q == null || q.isEmpty()) {
            return true;
        }
        return displayName.getString().toLowerCase(Locale.ROOT).contains(q)
                || (description != null && description.getString().toLowerCase(Locale.ROOT).contains(q))
                || (!group.displayName().isEmpty()
                        && group.displayName().toLowerCase(Locale.ROOT).contains(q))
                || (subgroup != null && subgroup.displayName().toLowerCase(Locale.ROOT).contains(q));
    }

    @Override
    public Collection<String> searchTerms() {
        return List.of(id, displayName.getString(), description.getString());
    }

    @Override
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        // Match against the category itself...
        if (Searchable.super.matches(query)) {
            return true;
        }
        // ...or any contained module (future-proofing the search).
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Module module : modules) {
            if (module.matches(q)) {
                return true;
            }
        }
        return false;
    }
}
