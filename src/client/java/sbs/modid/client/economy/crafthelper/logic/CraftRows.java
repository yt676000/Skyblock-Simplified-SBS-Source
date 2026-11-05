/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.logic;

import sbs.modid.client.economy.crafthelper.model.CraftNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flattens a {@link CraftNode} tree into the rows a panel draws: tree connectors, the expand marker,
 * the tick or cross, the counts and the name - all as plain strings, built once per change.
 *
 * <p><b>Collapse state</b> is a default plus the player's toggles: a branch starts collapsed when
 * "show completed branches collapsed" is on and the branch is {@link CraftNode#covered() covered},
 * and every path in {@code toggled} flips that default. Storing flips rather than states is what lets
 * a branch fold itself away the moment it completes, unless the player opened it by hand.
 */
public final class CraftRows {

    /** Pipe, branch and corner, each followed by a space - the tree connectors. */
    static final String PIPE = "│ ";
    static final String BRANCH = "├ ";
    static final String CORNER = "└ ";
    static final String BLANK = "  ";

    /** Tick and cross: the row's state as a shape as well as a colour. */
    public static final String TICK = "✔";
    public static final String CROSS = "✖";

    /**
     * One visible row.
     *
     * @param node       the node drawn
     * @param depth      0 for the root
     * @param connectors the tree lines left of the row
     * @param expandable whether the row has children to show or hide
     * @param expanded   whether those children are shown
     * @param counts     {@code have/need} with thousands separators
     */
    public record Row(CraftNode node, int depth, String connectors, boolean expandable, boolean expanded,
                      String counts) {

        /** {@link #TICK} when held, {@link #CROSS} otherwise. */
        public String mark() {
            return node.done() ? TICK : CROSS;
        }
    }

    private CraftRows() {
    }

    /** The rows of {@code root} under the given collapse state, root first. */
    public static List<Row> flatten(CraftNode root, Set<String> toggled, boolean collapseCompleted) {
        List<Row> rows = new ArrayList<>();
        if (root != null) {
            walk(root, 0, "", true, toggled, collapseCompleted, rows);
        }
        return rows;
    }

    /** Whether {@code node} is expanded under the given collapse state. */
    public static boolean expanded(CraftNode node, Set<String> toggled, boolean collapseCompleted) {
        boolean byDefault = !(collapseCompleted && node.covered());
        return toggled.contains(node.path()) != byDefault;
    }

    private static void walk(CraftNode node, int depth, String lead, boolean last, Set<String> toggled,
                             boolean collapseCompleted, List<Row> rows) {
        boolean expandable = !node.children().isEmpty();
        boolean open = expandable && expanded(node, toggled, collapseCompleted);
        String connectors = depth == 0 ? "" : lead + (last ? CORNER : BRANCH);
        rows.add(new Row(node, depth, connectors, expandable, open, counts(node)));
        if (!open) {
            return;
        }
        String childLead = depth == 0 ? "" : lead + (last ? BLANK : PIPE);
        List<CraftNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            walk(children.get(i), depth + 1, childLead, i == children.size() - 1, toggled,
                    collapseCompleted, rows);
        }
    }

    /** {@code "512/1,024"}. */
    public static String counts(CraftNode node) {
        return thousands(node.have()) + "/" + thousands(node.need());
    }

    public static String thousands(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
