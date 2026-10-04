/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/**
 * One slot's item as the Server Scanner records it: plain values only, taken on the client thread
 * from a live stack by {@link ScanReads}, so it can be kept, compared and written without touching
 * the game again. An empty slot is {@code null}, never an instance.
 *
 * <p>Pure - no Minecraft types - so the diff and the JSON form are unit-tested.
 *
 * @param id         vanilla registry key ({@code minecraft:red_stained_glass})
 * @param sbId       SkyBlock id, or {@code null} for an item without one
 * @param name       display name, plain
 * @param rawName    display name with {@code §} codes
 * @param lore       lore lines, plain
 * @param count      stack size
 * @param glint      whether the enchantment glint is drawn
 * @param customData the SkyBlock attribute compound as SNBT, or {@code null} when the item has none
 */
public record ScanSlot(String id, String sbId, String name, String rawName, List<String> lore, int count,
                       boolean glint, String customData) {

    public ScanSlot {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    /** The full slot as a JSON object, field order fixed so files diff cleanly. */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.add("sbId", string(sbId));
        out.addProperty("name", name);
        out.addProperty("rawName", rawName);
        out.add("lore", lines(lore));
        out.addProperty("count", count);
        out.addProperty("glint", glint);
        out.add("customData", string(customData));
        return out;
    }

    /** {@link #toJson()} of a slot that may be empty: {@code null} becomes JSON {@code null}. */
    public static JsonElement json(ScanSlot slot) {
        return slot == null ? JsonNull.INSTANCE : slot.toJson();
    }

    /**
     * The {@code old} side of a change: only the fields of {@code before} that differ in
     * {@code after}, with their old values. {@code null} when the slot was empty before (there is no
     * old item to describe) and an empty object when nothing changed.
     */
    public static JsonElement oldFields(ScanSlot before, ScanSlot after) {
        if (before == null) {
            return JsonNull.INSTANCE;
        }
        if (after == null) {
            return before.toJson();   // emptied: every field of the old item is gone
        }
        JsonObject out = new JsonObject();
        if (!Objects.equals(before.id, after.id)) {
            out.addProperty("id", before.id);
        }
        if (!Objects.equals(before.sbId, after.sbId)) {
            out.add("sbId", string(before.sbId));
        }
        if (!Objects.equals(before.name, after.name)) {
            out.addProperty("name", before.name);
        }
        if (!Objects.equals(before.rawName, after.rawName)) {
            out.addProperty("rawName", before.rawName);
        }
        if (!before.lore.equals(after.lore)) {
            out.add("lore", lines(before.lore));
        }
        if (before.count != after.count) {
            out.addProperty("count", before.count);
        }
        if (before.glint != after.glint) {
            out.addProperty("glint", before.glint);
        }
        if (!Objects.equals(before.customData, after.customData)) {
            out.add("customData", string(before.customData));
        }
        return out;
    }

    /** Whether two possibly-empty slots hold the same item. */
    public static boolean same(ScanSlot a, ScanSlot b) {
        return Objects.equals(a, b);
    }

    private static JsonElement string(String value) {
        return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);
    }

    private static JsonArray lines(List<String> lines) {
        JsonArray out = new JsonArray();
        lines.forEach(out::add);
        return out;
    }
}
