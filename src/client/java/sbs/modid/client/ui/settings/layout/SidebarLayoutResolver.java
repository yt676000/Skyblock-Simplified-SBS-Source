/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings.layout;

import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges a stored {@link SidebarLayout} with the modules this build actually has.
 *
 * <p>The layout was written by some earlier build, so it can disagree with this one both ways:
 * <ul>
 *   <li><b>A module the layout does not list</b> (new since it was saved) goes to the end of the
 *       category standing for its default group, and is reported in {@link Resolved#newIds} so the
 *       sidebar can mark it "new". Nothing is ever lost from the sidebar because a layout is old.</li>
 *   <li><b>A module id the layout lists that no longer exists</b> is skipped silently.</li>
 *   <li><b>A built-in group the layout does not have</b> (added since) is appended as a category.</li>
 *   <li><b>Pinned modules</b> (Favourites, Licence Token) are never placed by a layout: they stay on
 *       top, header-less, and a layout listing one is ignored for it.</li>
 *   <li>A module listed twice keeps its first place.</li>
 * </ul>
 *
 * <p>Pure: module references in, categories out, so both directions are unit-tested.
 */
public final class SidebarLayoutResolver {

    /** What the resolver needs to know about a live module. */
    public record ModuleRef(String id, ModuleGroup group, ModuleSubgroup subgroup) {
    }

    /** One category as the sidebar draws it. */
    public record Resolved(SidebarLayout.Category category, List<ModuleRef> modules, Set<String> newIds) {
    }

    private SidebarLayoutResolver() {
    }

    /**
     * The built-in layout, written out as a layout: every group in enum order holding its modules in
     * {@code live} order. What the editor starts from, and what "Reset to default" returns to.
     */
    public static SidebarLayout defaults(List<ModuleRef> live) {
        SidebarLayout layout = new SidebarLayout();
        for (ModuleGroup group : ModuleGroup.values()) {
            if (group == ModuleGroup.PINNED) {
                continue;
            }
            SidebarLayout.Category category = new SidebarLayout.Category(group.name().toLowerCase(java.util.Locale.ROOT), group, "");
            for (ModuleRef ref : live) {
                if (ref.group() == group) {
                    category.modules.add(ref.id());
                }
            }
            layout.categories.add(category);
        }
        return layout;
    }

    /**
     * {@code layout} applied to {@code live} ({@code live} in the default sidebar order). Empty
     * categories are included - the sidebar skips them, the editor shows them.
     */
    public static List<Resolved> resolve(SidebarLayout layout, List<ModuleRef> live) {
        Map<String, ModuleRef> byId = new HashMap<>();
        for (ModuleRef ref : live) {
            if (ref.group() != ModuleGroup.PINNED) {
                byId.put(ref.id(), ref);
            }
        }
        List<SidebarLayout.Category> categories = new ArrayList<>(layout.categories);
        for (ModuleGroup group : ModuleGroup.values()) {
            if (group != ModuleGroup.PINNED && layout.builtin(group) == null) {
                categories.add(new SidebarLayout.Category(group.name().toLowerCase(java.util.Locale.ROOT), group, ""));
            }
        }

        Set<String> placed = new HashSet<>();
        List<List<ModuleRef>> members = new ArrayList<>();
        List<Set<String>> fresh = new ArrayList<>();
        for (SidebarLayout.Category category : categories) {
            List<ModuleRef> list = new ArrayList<>();
            for (String id : category.modules) {
                ModuleRef ref = byId.get(id);
                if (ref != null && placed.add(id)) {
                    list.add(ref);
                }
            }
            members.add(list);
            fresh.add(new LinkedHashSet<>());
        }
        for (ModuleRef ref : live) {
            if (ref.group() == ModuleGroup.PINNED || placed.contains(ref.id())) {
                continue;
            }
            for (int i = 0; i < categories.size(); i++) {
                if (categories.get(i).builtin == ref.group()) {
                    members.get(i).add(ref);
                    fresh.get(i).add(ref.id());
                    placed.add(ref.id());
                    break;
                }
            }
        }
        List<Resolved> out = new ArrayList<>(categories.size());
        for (int i = 0; i < categories.size(); i++) {
            out.add(new Resolved(categories.get(i), members.get(i), fresh.get(i)));
        }
        return out;
    }

    /**
     * The sub-header families of a built-in category, in first-appearance order, each keeping the
     * player's order inside it. A module moved in from another group has no family here and sits in
     * General ({@code null}). Custom categories have no sub-headers.
     */
    public static Map<ModuleSubgroup, List<ModuleRef>> families(Resolved resolved) {
        Map<ModuleSubgroup, List<ModuleRef>> families = new java.util.LinkedHashMap<>();
        ModuleGroup home = resolved.category().builtin;
        for (ModuleRef ref : resolved.modules()) {
            ModuleSubgroup family = ref.group() == home ? ref.subgroup() : null;
            families.computeIfAbsent(family, k -> new ArrayList<>()).add(ref);
        }
        return families;
    }
}
