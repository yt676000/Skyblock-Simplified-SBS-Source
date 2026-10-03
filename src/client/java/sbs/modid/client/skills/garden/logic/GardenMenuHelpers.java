/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.farming.model.CropMilestones;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers Hypixel's Garden menus make you work out yourself, drawn straight onto the slots:
 * what a SkyMart item is really worth per copper, what a plot costs in coins, and the tier / level
 * a menu only tells you by making you hover every item.
 *
 * <p><b>Why coins and not the shown currency.</b> Copper and compost are only interesting as coins –
 * "200 compost" answers nothing on its own, "≈340k" answers whether the plot is worth buying now.
 * Both currencies trade on the Bazaar, so the conversion is a real market price rather than an
 * invented constant, and the label is left off entirely when the Bazaar cache has no price rather
 * than showing a confident zero.
 *
 * <p><b>Why drawing beats a tooltip.</b> These are comparisons: which SkyMart line is the best deal,
 * which crop is furthest behind. A tooltip shows one item at a time and hides the comparison; a
 * number on every slot is the whole answer in one glance. Text is drawn at half scale in the slot
 * corner, the same place vanilla puts stack counts, so it never covers the item itself.
 */
public final class GardenMenuHelpers {

    /** "Cost: 200 Copper", "200 Copper". */
    private static final Pattern COPPER = Pattern.compile("(?i)([\\d][\\d.,]*[kKmMbB]?)\\s*copper");
    /** "Cost: 200 Compost". */
    private static final Pattern COMPOST = Pattern.compile("(?i)([\\d][\\d.,]*[kKmMbB]?)\\s*compost");
    /** "Tier 25" / "Level 7" in a lore line. */
    private static final Pattern TIER = Pattern.compile("(?i)\\b(?:tier|level)\\s+(\\d+)");

    private GardenMenuHelpers() {
    }

    private static sbs.modid.client.core.config.SBSConfig.GardenSettings cfg() {
        return ConfigManager.getInstance().get().garden;
    }

    /**
     * Draws every enabled overlay for the open menu. Called from the container screen's slot pass,
     * so the numbers sit above the items and below the cursor stack and tooltips.
     *
     * <p>Takes no GUI origin: that pass runs with the pose already translated to
     * {@code leftPos}/{@code topPos}, so a slot's own {@code x}/{@code y} are the coordinates to
     * draw at. This used to be handed the origin and add it, which put every label a full GUI away
     * from its item.
     */
    public static void draw(GuiGraphicsExtractor g, AbstractContainerMenu menu, String rawTitle) {
        if (menu == null) {
            return;
        }
        String title = FarmingText.strip(rawTitle == null ? "" : rawTitle).toLowerCase(Locale.ROOT);
        Kind kind = Kind.of(title);
        if (kind == null) {
            return;
        }
        var cfg = cfg();
        boolean on = switch (kind) {
            case SKYMART -> cfg.skyMartCopperPrice;
            case PLOTS -> cfg.plotPrice;
            case MILESTONES -> cfg.milestoneNumbers;
            case UPGRADES -> cfg.upgradeNumbers;
        };
        if (!on) {
            return;
        }
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < containerSlots; i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String label = switch (kind) {
                case SKYMART -> copperValue(stack);
                case PLOTS -> compostValue(stack);
                case MILESTONES -> milestoneTier(stack);
                case UPGRADES -> upgradeLevel(stack);
            };
            if (label == null) {
                continue;
            }
            int color = kind == Kind.SKYMART || kind == Kind.PLOTS ? 0xFF57D977 : 0xFFFFE066;
            drawCorner(g, font, label, slot.x, slot.y, color);
        }
    }

    /** Which Garden menu is open, or {@code null} for anything this class has nothing to say about. */
    private enum Kind {
        SKYMART, PLOTS, MILESTONES, UPGRADES;

        static Kind of(String title) {
            if (title.contains("skymart")) {
                return SKYMART;
            }
            if (title.contains("plots")) {
                return PLOTS;
            }
            if (title.contains("milestone")) {
                return MILESTONES;
            }
            if (title.contains("upgrade")) {
                return UPGRADES;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ per-slot labels

    /**
     * Coins earned per copper spent on this SkyMart item: its Bazaar sell value divided by its
     * copper cost. Higher is the better deal, which is the only question the SkyMart poses.
     */
    private static String copperValue(ItemStack stack) {
        double copper = firstMatch(stack, COPPER);
        if (copper <= 0) {
            return null;
        }
        String id = SkyblockItem.idForPricing(stack);
        BazaarPriceCache.BzPrice price = id == null ? null : BazaarPriceCache.getInstance().get(id);
        if (price == null || price.sell() <= 0) {
            return null;
        }
        double perCopper = price.sell() * Math.max(1, stack.getCount()) / copper;
        return FarmingText.coins(perCopper) + "/c";
    }

    /** The plot's compost cost converted to coins at the Bazaar's compost price. */
    private static String compostValue(ItemStack stack) {
        double compost = firstMatch(stack, COMPOST);
        if (compost <= 0) {
            return null;
        }
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get("COMPOST");
        if (price == null || price.buy() <= 0) {
            return null;
        }
        return FarmingText.coins(compost * price.buy());
    }

    /**
     * The crop's milestone tier, taken from the anchored state so it shows the overflow tier the
     * live counter has already reached rather than the one the menu was opened at.
     */
    private static String milestoneTier(ItemStack stack) {
        CropType crop = CropType.forText(FarmingText.name(stack));
        if (crop != null) {
            CropMilestones.Progress progress = CropMilestones.getInstance().get(crop);
            if (progress != null && progress.tier() > 0) {
                return String.valueOf(progress.tier());
            }
        }
        return loreNumber(stack);
    }

    /** The upgrade's level, read off its lore. */
    private static String upgradeLevel(ItemStack stack) {
        return loreNumber(stack);
    }

    private static String loreNumber(ItemStack stack) {
        String name = FarmingText.name(stack);
        Matcher inName = TIER.matcher(name);
        if (inName.find()) {
            return inName.group(1);
        }
        for (String line : FarmingText.lore(stack)) {
            Matcher m = TIER.matcher(line);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** The first number the pattern finds in the stack's name or lore, or {@code -1}. */
    private static double firstMatch(ItemStack stack, Pattern pattern) {
        Matcher inName = pattern.matcher(FarmingText.name(stack));
        if (inName.find()) {
            return FarmingText.parseNumber(inName.group(1));
        }
        List<String> lore = FarmingText.lore(stack);
        for (String line : lore) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                return FarmingText.parseNumber(m.group(1));
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the label at half scale in the slot's bottom-left corner – opposite the stack count, so
     * the two never collide on an item that has both.
     */
    private static void drawCorner(GuiGraphicsExtractor g, Font font, String label,
                                   int slotX, int slotY, int color) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(slotX, slotY + 16f - font.lineHeight * 0.5f);
        pose.scale(0.5f);
        g.text(font, Component.literal(label), 0, 0, color);
        pose.popMatrix();
    }
}
