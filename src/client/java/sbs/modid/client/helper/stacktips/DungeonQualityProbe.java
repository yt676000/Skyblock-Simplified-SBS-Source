/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * One log line per item id the first time a stack with a quality roll is seen, so a short look at a
 * few dungeon items confirms the custom-data keys and Hypixel's lore wording. It logs only the item's
 * own attributes and lore - no player data, no uuid value (the key list names it, nothing more).
 *
 * <p><b>Dev only</b>: silent unless this is the dev client ({@code runClient}) or Developer Mode is
 * on ({@code /sbs developermode}), so a player's log never gets these lines. Called only from
 * {@link DungeonQualityReader}'s {@code StackCache} miss - never per frame, never through a
 * {@code copyTag()} - and deduplicated per item id in a bounded set. Remove once the spec marks the
 * fields CONFIRMED.
 */
final class DungeonQualityProbe {

    private static final int CAP = 256;
    private static final Set<String> SEEN = new HashSet<>();

    private DungeonQualityProbe() {
    }

    /** Logs {@code stack} once per id when its attributes carry either quality key. Render thread. */
    static void observe(ItemStack stack, CompoundTag extra) {
        // DEV-ONLY: a probe log line; the quality tip and tooltip work the same with dev mode off.
        if (!DevMode.ACTIVE && !FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }
        if (!extra.contains(DungeonQuality.BOOST_KEY) && !extra.contains(DungeonQuality.TIER_KEY)) {
            return;
        }
        String id = SkyblockItem.id(stack);
        String key = id == null ? "?" : id;
        if (SEEN.size() >= CAP || !SEEN.add(key)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonQuality] {} keys={} {}={} {}={} lore={}",
                key, new TreeSet<>(extra.keySet()),
                DungeonQuality.BOOST_KEY, extra.get(DungeonQuality.BOOST_KEY),
                DungeonQuality.TIER_KEY, extra.get(DungeonQuality.TIER_KEY),
                loreMentions(stack));
    }

    /** The plain lore lines that mention a gear score, a quality or a percentage. */
    private static List<String> loreMentions(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return out;
        }
        for (Component line : lore.lines()) {
            String plain = PlainText.strip(line.getString());
            String lower = plain.toLowerCase(Locale.ROOT);
            if (lower.contains("gear score") || lower.contains("quality") || lower.contains("%")) {
                out.add(plain);
            }
        }
        return out;
    }
}
