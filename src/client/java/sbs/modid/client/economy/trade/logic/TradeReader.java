/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.economy.trade.model.ItemPrint;
import sbs.modid.client.economy.trade.model.TradeSlot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a live stack into the plain {@link TradeSlot} / {@link ItemPrint} the guard compares.
 * Client thread only: it reads the stack's components.
 */
public final class TradeReader {

    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d+)]");

    /** Attributes that are bookkeeping, not worth: they differ between two copies of the same item. */
    private static final Set<String> IGNORED = Set.of("id", "uuid", "timestamp", "originTag", "enchantments",
            "gems", "ability_scroll", "petInfo", "modifier", "rarity_upgrades", "upgrade_level",
            "dungeon_item_level", "hot_potato_count", "dye_item", "skin", "donated_museum", "bossId",
            "spawnedFor", "baseStatBoostPercentage", "item_tier", "boss_tier");

    /** Appraisals of item prints, 60 s each: appraising walks every modifier and price table. */
    private static final long VALUE_TTL_MS = 60_000L;
    private static final Map<ItemPrint, long[]> VALUES = new HashMap<>();
    private static final Map<ItemPrint, Boolean> UNKNOWN = new HashMap<>();

    private TradeReader() {
    }

    /** A slot as plain text; {@code offer} decides whether it gets a fingerprint and a value. */
    public static TradeSlot read(int slot, ItemStack stack, boolean offer, long now) {
        String material = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        String name = PlainText.strip(stack.getHoverName().getString());
        List<String> lore = ItemPriceKey.lore(stack);
        if (!offer) {
            return new TradeSlot(slot, material, name, lore, stack.getCount(), null, null);
        }
        long coins = TradeLayout.coinOffer(material, name, lore);
        ItemPrint print = coins >= 0
                ? ItemPrint.builder(null, material, name).coins(coins).build()
                : print(stack, material, name);
        Long value = coins >= 0 ? Long.valueOf(coins) : value(print, stack, now);
        return new TradeSlot(slot, material, name, lore, stack.getCount(), print, value);
    }

    public static ItemPrint print(ItemStack stack) {
        return print(stack, BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                PlainText.strip(stack.getHoverName().getString()));
    }

    private static ItemPrint print(ItemStack stack, String material, String name) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        if (extra == null) {
            extra = new CompoundTag();
        }
        String id = extra.getStringOr("id", null);
        ItemPrint.Builder b = ItemPrint.builder(id, material, name).count(stack.getCount());
        String uuid = extra.getStringOr("uuid", null);
        b.uuid(uuid);
        int level = Math.max(extra.getIntOr("upgrade_level", 0), extra.getIntOr("dungeon_item_level", 0));
        b.stars(Math.min(5, level)).masterStars(Math.max(0, level - 5));
        b.recomb(extra.getIntOr("rarity_upgrades", 0) > 0);
        b.reforge(extra.getStringOr("modifier", null));
        b.hotPotato(extra.getIntOr("hot_potato_count", 0));
        CompoundTag ench = extra.getCompoundOrEmpty("enchantments");
        for (String k : ench.keySet()) {
            b.enchant(k, ench.getIntOr(k, 0));
        }
        CompoundTag gems = extra.getCompoundOrEmpty("gems");
        for (String k : gems.keySet()) {
            Tag t = gems.get(k);
            if (t instanceof CompoundTag c) {
                b.gem(k, c.getStringOr("quality", c.toString()));
            } else if (t != null && !k.equals("unlocked_slots")) {
                b.gem(k, t.asString().orElse(t.toString()));
            }
        }
        extra.getListOrEmpty("ability_scroll").stream()
                .forEach(t -> b.scroll(t.asString().orElse(t.toString())));
        b.dye(extra.getStringOr("dye_item", null));
        b.skin(extra.getStringOr("skin", null));
        String petInfo = extra.getStringOr("petInfo", null);
        if (petInfo != null) {
            b.pet(pet(petInfo, name));
        }
        for (String k : extra.keySet()) {
            if (IGNORED.contains(k) || k.endsWith("_uuid")) {
                continue;
            }
            Tag t = extra.get(k);
            if (t == null) {
                continue;
            }
            t.asNumber().ifPresentOrElse(n -> b.other(k, stripZero(n)),
                    () -> t.asString().ifPresent(s -> b.other(k, s)));
        }
        return b.build();
    }

    private static ItemPrint.Pet pet(String json, String name) {
        int level = 0;
        Matcher m = PET_LEVEL.matcher(name);
        if (m.find()) {
            level = Integer.parseInt(m.group(1));
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return new ItemPrint.Pet(str(o, "type"), str(o, "tier"),
                    o.has("exp") ? o.get("exp").getAsDouble() : 0, level, str(o, "heldItem"), str(o, "skin"));
        } catch (RuntimeException e) {
            return new ItemPrint.Pet(null, null, 0, level, null, null);
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static String stripZero(Number n) {
        double d = n.doubleValue();
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Appraised value of the stack, cached per fingerprint; {@code null} when nothing prices. */
    public static Long value(ItemPrint print, ItemStack stack, long now) {
        long[] hit = VALUES.get(print);
        if (hit != null && now - hit[1] < VALUE_TTL_MS) {
            return UNKNOWN.containsKey(print) ? null : hit[0];
        }
        if (VALUES.size() > 512) {
            VALUES.clear();
            UNKNOWN.clear();
        }
        ItemAppraisal.Appraisal a = ItemAppraisal.of(stack);
        VALUES.put(print, new long[] {a.total(), now});
        if (a.priced()) {
            UNKNOWN.remove(print);
            return a.total();
        }
        UNKNOWN.put(print, Boolean.TRUE);
        return null;
    }
}
