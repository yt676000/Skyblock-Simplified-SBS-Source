/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.perf.CacheCounter;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.enchants.counter.CounterEnchantTooltip;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item Stack Tips: a small number top-left on an item's icon where it says more than the icon -
 * pet level, minion tier, Catacombs pass floor, a dungeon drop's quality %, and (unverified, off by default) skill level and
 * collection tier.
 *
 * <p><b>Top-left, full size, vanilla's shadow.</b> The vanilla stack count sits bottom-right at the
 * same size, so the two never overlap even on a stack of minions. Enchanted Book Labels use the
 * top-right and bottom-left corners, and no book is ever a tip kind, so no slot carries two SBS
 * numbers.
 *
 * <p><b>Parsed once per stack.</b> A menu hands a slot a new {@link ItemStack} whenever its content
 * changes and {@code ItemStack} has identity equality, so the maps below are per-stack caches that
 * cannot go stale; they are cleared wholesale past {@link #CACHE_CAP}. Stacks are only read.
 */
public final class StackTips {

    private static final int CACHE_CAP = 512;
    /** Cached "this stack has no tip", so a miss is not re-parsed every frame. */
    private static final StackTipParser.Tip NONE = new StackTipParser.Tip(StackTipKind.PET, "-");

    private static final Map<ItemStack, StackTipParser.Tip> ITEM_TIPS = new ConcurrentHashMap<>();
    private static final Map<ItemStack, StackTipParser.Tip> MENU_TIPS = new ConcurrentHashMap<>();
    /** Render thread, like every caller of {@link #itemTip}. */
    private static final CacheCounter ITEM_TIPS_COUNTER = Perf.counter("stackTips.item");
    /** The custom_data key a cake's year is expected under - unverified, hence the lore fallback. */
    private static final String CAKE_YEAR_KEY = "new_years_cake";
    /** Whether the first cake's year source was logged this session (settles the key question). */
    private static boolean cakeSourceLogged;

    private StackTips() {
    }

    static SBSConfig.ItemOverlaySettings cfg() {
        return ConfigManager.getInstance().get().itemOverlay;
    }

    /** The configured scope; a missing or unreadable value in an old config means {@code BOTH}. */
    static StackTipScope scope(SBSConfig.ItemOverlaySettings cfg) {
        return cfg.stackTipScope == null ? StackTipScope.BOTH : cfg.stackTipScope;
    }

    /** Whether any kind is switched on - the one check both draw paths start with. */
    static boolean anyEnabled(SBSConfig.ItemOverlaySettings cfg) {
        return cfg.stackTipPets || cfg.stackTipMinions || cfg.stackTipDungeonPasses || cfg.stackTipDungeonQuality
                || cfg.stackTipSkills || cfg.stackTipCollections || cfg.stackTipCounterEnchant
                || cfg.stackTipCakeYears;
    }

    static boolean anyMenuKindEnabled(SBSConfig.ItemOverlaySettings cfg) {
        return cfg.stackTipSkills || cfg.stackTipCollections;
    }

    static boolean enabled(SBSConfig.ItemOverlaySettings cfg, StackTipKind kind) {
        return switch (kind) {
            case PET -> cfg.stackTipPets;
            case MINION -> cfg.stackTipMinions;
            case DUNGEON_PASS -> cfg.stackTipDungeonPasses;
            case DUNGEON_QUALITY -> cfg.stackTipDungeonQuality;
            case SKILL -> cfg.stackTipSkills;
            case COLLECTION -> cfg.stackTipCollections;
            case COUNTER_ENCHANT -> cfg.stackTipCounterEnchant;
            case CAKE_YEAR -> cfg.stackTipCakeYears;
        };
    }

    /** The item's own tip (pet, minion, pass), cached per stack; {@code null} for none. */
    static StackTipParser.Tip itemTip(ItemStack stack) {
        StackTipParser.Tip tip = ITEM_TIPS.get(stack);
        if (tip == null) {
            ITEM_TIPS_COUNTER.miss();
            if (ITEM_TIPS.size() >= CACHE_CAP) {
                ITEM_TIPS_COUNTER.invalidate(CacheCounter.Reason.CAPACITY);
            }
            trim(ITEM_TIPS);
            String id = SkyblockItem.id(stack);
            String name = PlainText.strip(stack.getHoverName().getString());
            tip = StackTipParser.CAKE_ID.equals(id)
                    ? cakeTip(stack, name, id)
                    : StackTipParser.itemTip(name, id, DungeonQualityReader.of(stack));
            if (tip == null) {
                // Pets, minions and passes never carry a counter enchant; a dungeon drop that has
                // both a quality and Champion shows the quality, the counter XP stays in the tooltip.
                String counter = CounterEnchantTooltip.stackTip(stack);
                tip = counter == null ? null : new StackTipParser.Tip(StackTipKind.COUNTER_ENCHANT, counter);
            }
            ITEM_TIPS.put(stack, tip == null ? NONE : tip);
        } else {
            ITEM_TIPS_COUNTER.hit();
        }
        return tip == NONE ? null : tip;
    }

    /** Cakes only, so no other stack pays for the custom_data and lore reads. */
    private static StackTipParser.Tip cakeTip(ItemStack stack, String name, String id) {
        Integer fromData = SkyblockItem.extraAttributes(stack).getInt(CAKE_YEAR_KEY).orElse(null);
        List<String> lore = new ArrayList<>();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) {
                lore.add(line.getString());
            }
        }
        StackTipParser.Tip tip = StackTipParser.itemTip(name, id, fromData, lore);
        if (tip != null && !cakeSourceLogged) {
            cakeSourceLogged = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][StackTips] cake year {} from {}",
                    tip.text(), fromData != null ? "custom_data" : "lore");
        }
        return tip;
    }

    /** The menu-bound tip for a stack shown in a menu titled {@code title}; {@code null} for none. */
    static StackTipParser.Tip menuTip(ItemStack stack, String title) {
        StackTipParser.Tip tip = MENU_TIPS.get(stack);
        if (tip == null) {
            trim(MENU_TIPS);
            tip = StackTipParser.menuTip(PlainText.strip(stack.getHoverName().getString()), title);
            MENU_TIPS.put(stack, tip == null ? NONE : tip);
        }
        return tip == NONE ? null : tip;
    }

    private static void trim(Map<ItemStack, StackTipParser.Tip> cache) {
        if (cache.size() >= CACHE_CAP) {
            cache.clear();
        }
    }

    /** Draws {@code tip} over the 16x16 icon at {@code (x, y)} if its kind is switched on. */
    static void draw(GuiGraphicsExtractor g, SBSConfig.ItemOverlaySettings cfg,
                     StackTipParser.Tip tip, int x, int y) {
        if (tip == null || !enabled(cfg, tip.kind())) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        // SBSTheme colours are retinted live by the theme engine - read at draw time, never cached.
        g.text(font, tip.text(), x, y,
                tip.color() == StackTipParser.Tip.THEME ? SBSTheme.ACCENT : tip.color(), true);
    }

    /** The HUD hotbar pass, called at the tail of {@code extractItemHotbar}. */
    public static void renderHotbar(GuiGraphicsExtractor g) {
        SBSConfig.ItemOverlaySettings cfg = cfg();
        if (!scope(cfg).hotbar() || !anyEnabled(cfg)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        // Same slot geometry as CooldownOverlay.renderHotbar: 20 px pitch, icon 3 px in.
        int leftEdge = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - 16 - 3;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                draw(g, cfg, itemTip(stack), leftEdge + 3 + slot * 20, y);
            }
        }
    }
}
