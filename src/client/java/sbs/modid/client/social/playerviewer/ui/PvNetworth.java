/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.economy.itemvalue.ValueSum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An estimated networth for the viewed profile, computed on this PC from the local Bazaar and
 * auction price caches ({@link ValueSum} / the offline appraisal - nothing is fetched per item).
 *
 * <p>Counted: every container in the view (inventory, armour, equipment, Ender Chest, backpacks,
 * vault, accessory bag, quiver, potion and bait bags), the wardrobe and equipment-wardrobe slots
 * that are <b>not</b> on the body (the worn ones are already in armour / equipment), purse and
 * bank. Not counted: pets and sacks, which the view does not price yet. A part that could not be
 * fully priced makes the total a floor ({@code +}).
 *
 * <p>Computed once per profile view, then again only while it is unsettled: every 3 s while item
 * icons are still resolving, every 30 s while the price caches leave items unpriced.
 */
public final class PvNetworth {

    private static final long PENDING_MS = 3_000;
    private static final long FLOOR_MS = 30_000;

    /** One estimate: the total and its parts, in display order. */
    public record Estimate(ValueSum.Total total, Map<String, ValueSum.Total> parts) {
    }

    private static JsonObject forProfile;
    private static Estimate estimate;
    private static long at;
    private static boolean pending;

    private PvNetworth() {
    }

    public static Estimate of(JsonObject profile) {
        long now = System.currentTimeMillis();
        boolean stale = estimate == null || profile != forProfile
                || (pending && now - at >= PENDING_MS) || (estimate.total().floor() && now - at >= FLOOR_MS);
        if (stale) {
            estimate = compute(profile);
            forProfile = profile;
            at = now;
        }
        return estimate;
    }

    private static Estimate compute(JsonObject profile) {
        pending = false;
        Map<String, ValueSum.Total> parts = new LinkedHashMap<>();
        JsonObject invs = PvDraw.obj(profile, "inventories");
        if (invs != null) {
            List<ItemStack> worn = new ArrayList<>();
            add(profile, PvDraw.arr(invs, "armor"), worn);
            add(profile, PvDraw.arr(invs, "equipment"), worn);
            parts.put("Worn", ValueSum.of(worn));
            parts.put("Inventory", sum(profile, PvDraw.arr(invs, "inventory")));
            parts.put("Ender Chest", sum(profile, PvDraw.arr(invs, PvDraw.containerKey(invs, "ender_chest"))));
            List<ItemStack> packs = new ArrayList<>();
            for (JsonElement pack : PvDraw.arr(invs, "backpacks")) {
                if (pack.isJsonArray()) {
                    add(profile, pack.getAsJsonArray(), packs);
                }
            }
            parts.put("Backpacks", ValueSum.of(packs));
            parts.put("Accessories", sum(profile, PvDraw.arr(invs, "accessory_bag")));
            List<ItemStack> other = new ArrayList<>();
            for (String key : new String[]{"vault", "quiver", "potion_bag", "fishing_bag"}) {
                add(profile, PvDraw.arr(invs, key), other);
            }
            parts.put("Vault & bags", ValueSum.of(other));
        }
        JsonObject lo = PvDraw.obj(profile, "loadouts");
        if (lo != null) {
            List<ItemStack> wardrobe = new ArrayList<>();
            for (String[] kind : new String[][]{{"sets", "armor"}, {"equipment_sets", "equipment"}}) {
                for (JsonElement el : PvDraw.arr(lo, kind[0])) {
                    JsonObject set = el.getAsJsonObject();
                    if (!(PvDraw.has(set, "on_body") && set.get("on_body").getAsBoolean())) {
                        add(profile, PvDraw.arr(set, kind[1]), wardrobe);
                    }
                }
            }
            parts.put("Wardrobe", ValueSum.of(wardrobe));
        }
        JsonObject extras = PvDraw.obj(profile, "extras");
        long coins = PvDraw.num(extras, "purse") + PvDraw.num(extras, "bank");
        parts.put("Purse & bank", new ValueSum.Total(coins, 0));
        ValueSum.Total total = ValueSum.Total.ZERO;
        for (ValueSum.Total part : parts.values()) {
            total = total.plus(part);
        }
        return new Estimate(total, parts);
    }

    private static ValueSum.Total sum(JsonObject profile, JsonArray slots) {
        List<ItemStack> stacks = new ArrayList<>();
        add(profile, slots, stacks);
        return ValueSum.of(stacks);
    }

    private static void add(JsonObject profile, JsonArray slots, List<ItemStack> into) {
        for (JsonElement el : slots) {
            if (el != null && el.isJsonObject()) {
                ItemStack stack = PvDraw.stack(profile, el.getAsJsonObject());
                pending |= stack.is(Items.BARRIER);
                into.add(stack);
            }
        }
    }
}
