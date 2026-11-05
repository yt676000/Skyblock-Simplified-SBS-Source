/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static sbs.modid.client.social.playerviewer.logic.PvValidator.ID_LEN;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.arr;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.bool;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.num;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.obj;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.str;

/**
 * The two sections that come from their own relay endpoints: the museum
 * ({@code /v1/playerviewer/museum/...}, raw Hypixel {@code /skyblock/museum}) and the online status
 * ({@code /v1/playerviewer/status/...}, raw Hypixel {@code /status}). Ports of the old server's
 * {@code _pv_museum} and {@code _pv_location}.
 *
 * <p>Each returns the section's view object, or {@code null} when Hypixel has no data for it. A
 * malformed body throws {@link PvInvalidDataException}; the caller then marks only that section
 * unavailable.
 */
public final class PvSideSections {

    /** SkyBlock island mode -> display name, for when Hypixel leaves {@code map} out. */
    static final Map<String, String> SKYBLOCK_MODES = Map.ofEntries(
            Map.entry("dynamic", "Private Island"), Map.entry("hub", "Hub"), Map.entry("garden", "The Garden"),
            Map.entry("farming_1", "The Barn"), Map.entry("combat_1", "Spider's Den"),
            Map.entry("combat_2", "Blazing Fortress"), Map.entry("combat_3", "The End"),
            Map.entry("crimson_isle", "Crimson Isle"), Map.entry("mining_1", "Gold Mine"),
            Map.entry("mining_2", "Deep Caverns"), Map.entry("mining_3", "Dwarven Mines"),
            Map.entry("crystal_hollows", "Crystal Hollows"), Map.entry("mineshaft", "Glacite Mineshaft"),
            Map.entry("foraging_1", "The Park"), Map.entry("the_park", "The Park"),
            Map.entry("dungeon_hub", "Dungeon Hub"), Map.entry("dungeon", "The Catacombs"),
            Map.entry("rift", "The Rift"), Map.entry("winter", "Jerry's Workshop"),
            Map.entry("kuudra", "Kuudra's Hollow"), Map.entry("instanced", "Instance"));

    private PvSideSections() {
    }

    private static JsonObject root(String body) throws PvInvalidDataException {
        JsonElement el = PvJson.parse(body);
        if (!el.isJsonObject()) {
            throw new PvInvalidDataException("root: expected object, got " + PvValidator.typeOf(el));
        }
        return el.getAsJsonObject();
    }

    /** {@code _pv_museum}: value, appraisal, donated item ids, borrowing and special counts. */
    public static JsonObject museum(String body, String uuid) throws PvInvalidDataException {
        JsonObject members = obj(root(body), "members");
        JsonObject member = members == null ? null : PvValidator.objIfObject(members, uuid);
        if (member == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        Double value = num(member, "value");
        if (value != null) {
            out.addProperty("value", (long) (double) value);
        }
        Boolean appraisal = bool(member, "appraisal");
        if (appraisal != null) {
            out.addProperty("appraisal", appraisal);
        }
        JsonObject items = obj(member, "items");
        if (items != null) {
            List<String> ids = new ArrayList<>();
            long borrowing = 0;
            for (Map.Entry<String, JsonElement> e : items.entrySet()) {
                ids.add(e.getKey().toUpperCase(Locale.ROOT));
                if (e.getValue().isJsonObject()) {
                    Boolean b = bool(e.getValue().getAsJsonObject(), "borrowing");
                    if (b != null && b) {
                        borrowing++;
                    }
                }
            }
            Collections.sort(ids);
            JsonArray arr = new JsonArray();
            ids.forEach(id -> arr.add(PvText.plain(id, ID_LEN)));
            out.add("items", arr);
            out.addProperty("donated", ids.size());
            out.addProperty("borrowing", borrowing);
        }
        JsonArray special = arr(member, "special");
        if (special != null) {
            out.addProperty("special", special.size());
            // Special donations are a list of entries, each naming its item by id.
            JsonArray specialIds = new JsonArray();
            for (JsonElement e : special) {
                if (e.isJsonObject()) {
                    String id = str(e.getAsJsonObject(), "id", ID_LEN);
                    if (id != null && !id.isEmpty()) {
                        specialIds.add(PvText.plain(id.toUpperCase(Locale.ROOT), ID_LEN));
                    }
                }
            }
            out.add("special_items", specialIds);
        }
        return out.isEmpty() ? null : out;
    }

    /** {@code _pv_location}: online flag and, when online, game, mode and map. */
    public static JsonObject location(String body) throws PvInvalidDataException {
        JsonObject session = obj(root(body), "session");
        if (session == null || session.isEmpty()) {
            return null;
        }
        JsonObject out = new JsonObject();
        Boolean online = bool(session, "online");
        out.addProperty("online", online != null && online);
        if (online == null || !online) {
            return out;
        }
        String game = str(session, "gameType", 64);
        String mode = str(session, "mode", 64);
        if (game != null && !game.isEmpty()) {
            out.addProperty("game", PvText.plain(game, 64));
        }
        if (mode != null && !mode.isEmpty()) {
            out.addProperty("mode", PvText.plain(mode, 64));
        }
        String map = str(session, "map", 64);
        if ((map == null || map.isEmpty()) && "SKYBLOCK".equalsIgnoreCase(game)) {
            map = SKYBLOCK_MODES.get(mode == null ? "" : mode.toLowerCase(Locale.ROOT));
        }
        if (map != null && !map.isEmpty()) {
            out.addProperty("map", PvText.plain(map, 64));
        }
        return out;
    }
}
