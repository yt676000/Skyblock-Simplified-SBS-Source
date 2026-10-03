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

/**
 * The {@code origins} array of a layout file: where and how the screen was opened. Pure Gson, so
 * building, keying and merging are unit-tested.
 *
 * <p>One origin = {@code island}, {@code zone}, {@code pos} (player block position), {@code root}
 * (the first step's {@code via}), {@code path}, {@code count}, {@code firstSeen}, {@code lastSeen},
 * and for NPC roots {@code playerPositions}.
 *
 * <p><b>Dedupe.</b> Two openings are the same origin when their root identity, the titles and slots
 * along the path, and the island match - and, for an NPC root, the NPC's block position; for any
 * other root, the player's position rounded to {@value #POS_GRID} blocks. Standing somewhere else to
 * click the same NPC is therefore the same origin, with the extra spot kept in
 * {@code playerPositions} (at most {@value #MAX_PLAYER_POSITIONS}).
 */
public final class LayoutOrigins {

    static final int POS_GRID = 8;
    static final int MAX_PLAYER_POSITIONS = 5;

    private LayoutOrigins() {
    }

    /** A fresh origin for one opening. */
    public static JsonObject build(String island, String zone, int[] playerBlock, JsonArray path, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("island", island == null ? "" : island);
        o.addProperty("zone", zone == null ? "" : zone);
        o.add("pos", ints(playerBlock));
        o.addProperty("root", path.isEmpty() ? "unknown" : str(path.get(0).getAsJsonObject(), "via"));
        o.add("path", path);
        o.addProperty("count", 1);
        o.addProperty("firstSeen", now);
        o.addProperty("lastSeen", now);
        if ("npc".equals(str(o, "root"))) {
            JsonArray spots = new JsonArray();
            spots.add(ints(playerBlock));
            o.add("playerPositions", spots);
        }
        return o;
    }

    /** The dedupe key of an origin (see the class note). */
    public static String key(JsonObject origin) {
        StringBuilder key = new StringBuilder();
        String root = str(origin, "root");
        key.append(root).append('|').append(str(origin, "island")).append('|');
        JsonArray path = origin.has("path") ? origin.getAsJsonArray("path") : new JsonArray();
        for (int i = 0; i < path.size(); i++) {
            JsonObject step = path.get(i).getAsJsonObject();
            String via = str(step, "via");
            key.append(via).append(':');
            switch (via) {
                case "npc" -> key.append(str(step, "npc").isEmpty() ? str(step, "name") : str(step, "npc"))
                        .append('@').append(step.has("npcBlock") ? step.get("npcBlock").toString() : "");
                case "entity" -> key.append(str(step, "name")).append('|').append(str(step, "type"));
                case "block" -> key.append(str(step, "block")).append('@')
                        .append(step.has("blockPos") ? step.get("blockPos").toString() : "");
                case "command" -> key.append(str(step, "command"));
                case "item" -> key.append(str(step, "item"));
                case "menu" -> key.append(str(step, "title")).append('#').append(str(step, "slot"));
                default -> { }
            }
            key.append(';');
        }
        if (!"npc".equals(root)) {
            key.append("pos=").append(rounded(origin.getAsJsonArray("pos")));
        }
        return key.toString();
    }

    /**
     * Merges one opening into a file's origins. An existing match is counted (and, for an NPC root,
     * learns the new standing spot); a new origin is appended while under {@code cap}.
     *
     * @return whether the array changed in a way worth writing (a new origin or a new spot); a
     *         plain count bump returns {@code false} and waits for the next flush
     */
    public static boolean merge(JsonArray origins, JsonObject incoming, int cap) {
        String key = key(incoming);
        for (JsonElement element : origins) {
            JsonObject existing = element.getAsJsonObject();
            if (!key.equals(key(existing))) {
                continue;
            }
            existing.addProperty("count", existing.get("count").getAsInt() + 1);
            existing.addProperty("lastSeen", incoming.get("lastSeen").getAsLong());
            return addSpot(existing, incoming.getAsJsonArray("pos"));
        }
        if (origins.size() >= cap) {
            return false;
        }
        origins.add(incoming);
        return true;
    }

    /** The {@code origins} array of a layout file, created when an older file has none. */
    public static JsonArray originsOf(JsonObject layoutFile) {
        if (layoutFile.has("origins") && layoutFile.get("origins").isJsonArray()) {
            return layoutFile.getAsJsonArray("origins");
        }
        JsonArray created = new JsonArray();
        layoutFile.add("origins", created);
        return created;
    }

    private static boolean addSpot(JsonObject origin, JsonArray pos) {
        if (!"npc".equals(str(origin, "root")) || pos == null) {
            return false;
        }
        JsonArray spots = origin.has("playerPositions") ? origin.getAsJsonArray("playerPositions")
                : new JsonArray();
        origin.add("playerPositions", spots);
        for (JsonElement spot : spots) {
            if (spot.equals(pos)) {
                return false;
            }
        }
        if (spots.size() >= MAX_PLAYER_POSITIONS) {
            return false;
        }
        spots.add(pos.deepCopy());
        return true;
    }

    private static String rounded(JsonArray pos) {
        if (pos == null || pos.size() < 3) {
            return "";
        }
        return Math.floorDiv(pos.get(0).getAsInt(), POS_GRID) + "," + Math.floorDiv(pos.get(1).getAsInt(), POS_GRID)
                + "," + Math.floorDiv(pos.get(2).getAsInt(), POS_GRID);
    }

    static JsonArray ints(int[] values) {
        JsonArray out = new JsonArray();
        if (values != null) {
            for (int v : values) {
                out.add(v);
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
