/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants.counter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.item.TooltipLines;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.helper.enchants.EnchantNames;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Item Overlay's "Counter Enchant XP" line - {@code Champion X: 3.4M XP (max, +413K over)} - and
 * the probe that logs the custom data behind it. Appended through {@code PriceTooltipMixin}'s chain.
 *
 * <p>Built once per stack, table version, config revision and Shift state through
 * {@link TooltipLines}, so the custom data is read when the stack changes, not every frame. The
 * probe runs inside that build, which is what keeps it to one pass per stack; the uuid set on top
 * keeps it to one log line per item.
 */
public final class CounterEnchantTooltip {

    private static final int ENCHANT_BLUE = 0x5555FF;
    private static final int GRAY = 0xAAAAAA;
    private static final int DARK_GRAY = 0x555555;

    private static final TooltipLines CACHE = new TooltipLines(CounterEnchantTooltip::build);

    /** Enchants and custom-data keys the probe reports even when the table has no row for them. */
    private static final Set<String> PROBE_ENCHANTS = Set.of(
            "champion", "toxophilite", "expertise", "compact", "cultivating", "hecatomb");
    private static final Set<String> PROBE_KEYS = Set.of(
            "expertise_kills", "compact_blocks", "farmed_cultivating", "hecatomb_s_runs");
    private static final String PROBE_SUFFIX = "_combat_xp";
    private static final int PROBE_CAP = 512;
    /** Items already probed, by uuid (else id). Render thread only, like every caller. */
    private static final Set<String> PROBED = new HashSet<>();

    private CounterEnchantTooltip() {
    }

    /** {@code current} with the counter line appended, or {@code current} when there is none. */
    public static List<Component> decorate(List<Component> current, ItemStack stack) {
        if (!ConfigManager.getInstance().get().itemOverlay.counterEnchantXp) {
            return current;
        }
        long key = TooltipLines.key(CounterEnchants.version(), ConfigManager.getInstance().revision(),
                shiftDown() ? 1 : 0);
        return TooltipLines.append(current, CACHE.get(stack, key));
    }

    /** The compact total for the stack tip ({@code 3.4M}), or {@code null} without a counter enchant. */
    public static String stackTip(ItemStack stack) {
        CounterEnchantXp.Progress progress =
                CounterEnchantXp.find(SkyblockItem.extraAttributes(stack), CounterEnchants.entries());
        return progress == null ? null : NumberDisplay.shorten(progress.value());
    }

    private static List<Component> build(ItemStack stack) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        List<CounterEnchantData.Entry> table = CounterEnchants.entries();
        CounterEnchantXp.Progress progress = CounterEnchantXp.find(extra, table);
        probe(stack, extra, progress);
        if (progress == null) {
            return List.of();
        }
        boolean full = shiftDown();
        String text = CounterEnchantXp.text(progress, full ? NumberDisplay::grouped : NumberDisplay::format);
        String name = EnchantNames.displayName(progress.entry().enchant) + " "
                + CounterEnchantXp.roman(progress.level());
        Component line = Component.literal(name).withColor(ENCHANT_BLUE)
                .append(Component.literal(text.substring(name.length())).withColor(GRAY));
        Certainty certainty = CounterEnchantXp.certainty(progress.entry().certainty);
        if (!certainty.trusted()) {
            line = line.copy().append(Component.literal(" (levels " + certainty.displayName() + ")")
                    .withColor(DARK_GRAY));
        }
        return List.of(line);
    }

    /**
     * Logs one line per item that carries a counter enchant or a counter-shaped key, with every
     * custom-data key, so the real key names show up from normal play.
     */
    private static void probe(ItemStack stack, CompoundTag extra, CounterEnchantXp.Progress progress) {
        String enchant = probeEnchant(extra, progress);
        String probeKey = probeKey(extra, progress);
        if (enchant == null && probeKey == null) {
            return;
        }
        String id = extra.getStringOr("id", "?");
        String identity = SkyblockItem.uuid(stack);
        if (identity == null) {
            identity = id;
        }
        if (PROBED.size() >= PROBE_CAP) {
            PROBED.clear();
        }
        if (!PROBED.add(identity)) {
            return;
        }
        int level = 0;
        if (enchant != null && extra.get("enchantments") instanceof CompoundTag enchants) {
            level = enchants.getIntOr(enchant, 0);
        }
        String valueKey = progress != null ? progress.counterKey() : probeKey;
        Object value = valueKey == null ? null : extra.get(valueKey);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][CounterEnchant] {} enchant={} lvl={} keys={} value={}{} lore={}",
                id, enchant, level, extra.keySet(), valueKey, value == null ? "" : "=" + value,
                loreLine(stack, enchant));
    }

    /** The probed enchant: the matched row's, else the first probe-listed enchant on the item. */
    private static String probeEnchant(CompoundTag extra, CounterEnchantXp.Progress progress) {
        if (progress != null) {
            return progress.entry().enchant;
        }
        if (extra.get("enchantments") instanceof CompoundTag enchants) {
            for (String key : enchants.keySet()) {
                if (PROBE_ENCHANTS.contains(key.toLowerCase(Locale.ROOT))) {
                    return key;
                }
            }
        }
        return null;
    }

    /** The first counter-shaped custom-data key, or {@code null}. */
    private static String probeKey(CompoundTag extra, CounterEnchantXp.Progress progress) {
        if (progress != null) {
            return progress.counterKey();
        }
        for (String key : extra.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.endsWith(PROBE_SUFFIX) || PROBE_KEYS.contains(lower)) {
                return key;
            }
        }
        return null;
    }

    /**
     * Every lore line naming the enchant (at most four), colour-stripped and quoted: the enchant
     * list entry and, below max, Hypixel's own progress line. Empty when there is none.
     */
    private static String loreLine(ItemStack stack, String enchant) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || enchant == null) {
            return "";
        }
        String name = EnchantNames.displayName(enchant).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int found = 0;
        for (Component line : lore.lines()) {
            String plain = ChatFormatting.stripFormatting(line.getString());
            if (plain != null && plain.toLowerCase(Locale.ROOT).contains(name) && found++ < 4) {
                out.append(out.isEmpty() ? "" : " | ").append('\'').append(plain.trim()).append('\'');
            }
        }
        return out.toString();
    }

    private static boolean shiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }
}
