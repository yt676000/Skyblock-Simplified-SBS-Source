/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.lowball.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Reads one stack into the Lowball request item: Hypixel's ExtraAttributes exactly as they sit in
 * {@code minecraft:custom_data}, converted NBT to JSON and nothing renamed or dropped - the program
 * expects Hypixel's own keys ({@code petInfo} stays the JSON string it is).
 *
 * <p>Client thread only (it reads live component maps). COLD: runs on a key press or command, so the
 * deep copy of the tag is fine here and nowhere hotter.
 */
public final class LowballItems {

    private LowballItems() {
    }

    /** The request item for {@code stack}, or {@code null} when it is not a SkyBlock item. */
    public static JsonObject read(ItemStack stack, String ref) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag nbt = data.copyTag();
        if (!nbt.contains("id")) {
            return null;   // menu glass, placeholders
        }
        JsonElement extra = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, nbt);
        JsonObject item = new JsonObject();
        item.addProperty("ref", ref);
        item.addProperty("name", stack.getHoverName().getString());
        item.addProperty("count", stack.getCount());
        item.add("extra", extra);
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        if (dye != null) {
            item.addProperty("color", dye.rgb() & 0xFFFFFF);
        }
        return item;
    }

    /**
     * The cache key: the item's uuid, or for uuid-less stackables its id, name and count - two
     * stacks that would be priced alike.
     */
    public static String key(JsonObject item) {
        JsonObject extra = item.getAsJsonObject("extra");
        if (extra != null && extra.has("uuid") && extra.get("uuid").isJsonPrimitive()) {
            return "uuid:" + extra.get("uuid").getAsString();
        }
        String id = extra != null && extra.has("id") ? extra.get("id").getAsString() : "?";
        return id + '|' + item.get("name").getAsString() + '|' + item.get("count").getAsInt();
    }
}
