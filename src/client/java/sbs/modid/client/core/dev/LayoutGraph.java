/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code layouts/graph.json}: which layout files each NPC leads to, and which menus each menu
 * leads to - the walk "NPC -> menu -> submenu" a tutorial needs. Built from the origins as they
 * are recorded.
 *
 * <p>A file of its own rather than keys in {@code index.json}: the index is a flat
 * hash -> entry map, and the loader (this build's and every older one) reads every key as an
 * entry. Pure apart from the JSON it produces, so it is unit-tested.
 */
public final class LayoutGraph {

    private final Map<String, TreeSet<String>> npcs = new TreeMap<>();
    private final Map<String, TreeSet<String>> menus = new TreeMap<>();

    /**
     * Adds one opening.
     *
     * @param path  the click path ({@link OpenerPath} steps)
     * @param title the title of the screen it opened
     * @param file  that screen's layout file, relative to {@code layouts/}
     * @return whether anything new was learned
     */
    public boolean add(JsonArray path, String title, String file) {
        boolean changed = false;
        String parent = null;
        for (JsonElement element : path) {
            JsonObject step = element.getAsJsonObject();
            String via = str(step, "via");
            if (via.equals("npc")) {
                String npc = str(step, "npc").isEmpty() ? str(step, "name") : str(step, "npc");
                if (!npc.isEmpty()) {
                    changed |= npcs.computeIfAbsent(npc, k -> new TreeSet<>()).add(file);
                }
            } else if (via.equals("menu")) {
                String menu = str(step, "title");
                if (parent != null && !menu.isEmpty()) {
                    changed |= menus.computeIfAbsent(parent, k -> new TreeSet<>()).add(menu);
                }
                parent = menu;
            }
        }
        if (parent != null && title != null && !title.isEmpty()) {
            changed |= menus.computeIfAbsent(parent, k -> new TreeSet<>()).add(title);
        }
        return changed;
    }

    /** Forgets everything (the recorder's "clear"). */
    public void clear() {
        npcs.clear();
        menus.clear();
    }

    /** Reads a previously written graph.json (missing or broken parts are skipped). */
    public void load(JsonObject root) {
        if (root == null) {
            return;
        }
        read(root, "npcs", npcs);
        read(root, "menus", menus);
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.add("npcs", write(npcs));
        root.add("menus", write(menus));
        return root;
    }

    private static void read(JsonObject root, String key, Map<String, TreeSet<String>> into) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return;
        }
        for (var entry : root.getAsJsonObject(key).entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            TreeSet<String> values = into.computeIfAbsent(entry.getKey(), k -> new TreeSet<>());
            entry.getValue().getAsJsonArray().forEach(v -> values.add(v.getAsString()));
        }
    }

    private static JsonObject write(Map<String, TreeSet<String>> map) {
        JsonObject out = new JsonObject();
        map.forEach((key, values) -> {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            out.add(key, array);
        });
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
