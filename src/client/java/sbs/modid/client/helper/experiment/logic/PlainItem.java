/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One menu slot reduced to the plain values the Experimentation models read: the item id without
 * its namespace, the display name with {@code §} codes removed, the stack size, the glint and the
 * plain lore lines. The models see only these, so the Server Scanner's JSONL recordings drive them
 * in tests exactly as the live menu does in game.
 *
 * @param id    item id without namespace, e.g. {@code lime_terracotta}
 * @param name  display name, plain text
 * @param count stack size
 * @param glint whether the stack draws the enchant glint
 * @param lore  lore lines, plain text
 */
public record PlainItem(String id, String name, int count, boolean glint, List<String> lore) {

    private static final Pattern TIMER = Pattern.compile("^Timer: ?\\d+s?$");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,3}$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    public PlainItem {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    /** The glass panes that frame every board ({@code black_stained_glass_pane} and friends). */
    boolean isPane() {
        return id.endsWith("glass_pane");
    }

    /** The instruction item of the memorise phase: glowstone "Remember the pattern!". */
    boolean isRemember() {
        return name.equals("Remember the pattern!");
    }

    /** The instruction item of the input phase: a clock "Timer: Ns" (stack size = seconds left). */
    boolean isTimer() {
        return TIMER.matcher(name).matches();
    }

    /** The number of a purely numeric display name ("1" ... "14"), or -1. */
    int numericName() {
        return NUMBER.matcher(name).matches() ? Integer.parseInt(name) : -1;
    }

    /** The integer after {@code prefix} in the name ("Round: 3" -> 3), or -1. */
    int nameValue(String prefix) {
        if (!name.startsWith(prefix)) {
            return -1;
        }
        Matcher m = DIGITS.matcher(name.substring(prefix.length()));
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }
}
