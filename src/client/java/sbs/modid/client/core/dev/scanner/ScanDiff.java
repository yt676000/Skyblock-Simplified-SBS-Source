/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/**
 * Line-by-line diff of a text block (scoreboard, tab list): one {@code {i, old, new}} per line whose
 * text changed at its position, {@code old} missing for an added line and {@code new} missing for a
 * removed one. Positional on purpose - a sidebar is a fixed layout where line 5 means something, and
 * a shifted block reads correctly as "every line from here changed". Pure, unit-tested.
 */
final class ScanDiff {

    private ScanDiff() {
    }

    static JsonArray lines(List<String> before, List<String> after) {
        JsonArray out = new JsonArray();
        int size = Math.max(before.size(), after.size());
        for (int i = 0; i < size; i++) {
            String was = i < before.size() ? before.get(i) : null;
            String now = i < after.size() ? after.get(i) : null;
            if (Objects.equals(was, now)) {
                continue;
            }
            JsonObject change = new JsonObject();
            change.addProperty("i", i);
            if (was != null) {
                change.addProperty("old", was);
            }
            if (now != null) {
                change.addProperty("new", now);
            }
            out.add(change);
        }
        return out;
    }
}
