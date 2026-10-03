/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider;

import java.util.Locale;

/**
 * Item Overlay module: identifies Enchanted Books at a glance by abbreviating the enchant straight
 * onto the slot icon – because in an Auction House page or a storage full of identical book icons,
 * the only vanilla way to tell them apart is hovering every single one.
 *
 * <ul>
 *   <li><b>Abbreviation</b>, bottom-left: initials for multi-word enchants ("First Strike" → FS,
 *       "One For All" → OFA), the first three letters for single-word ones ("Sharpness" → Sha).
 *       Ultimate enchants draw pink + bold, matching their in-game lore colour. Turbo books
 *       abbreviate their <i>crop</i> ("Turbo-Cactus" → Cac) – initials would collide on
 *       Turbo-Cactus / Turbo-Cane, and the crop is the part that matters.</li>
 *   <li><b>Tier</b>, top-right: the enchant's level as a plain number, gold when the book is at
 *       the enchant's known maximum level.</li>
 * </ul>
 *
 * <p>Both parts have their own toggle. Only single-enchant books are labelled: a combined
 * (multi-enchant) book has no one identity to abbreviate, and guessing would mislabel it.
 * Naming goes through {@link EnchantNames}, so renames (Duplex, Gravity) abbreviate from the name
 * players actually see, not the NBT key.
 */
public final class EnchantBookOverlay {

    private static final int ITEM_SIZE = 16;
    private static final float TEXT_SCALE = 0.75f;

    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    /** Light purple – the lore colour of ultimate enchants. */
    private static final int COLOR_ULTIMATE = 0xFFFF55FF;
    /** Gold – the tier when the book sits at the enchant's max obtainable level. */
    private static final int COLOR_MAX_TIER = 0xFFFFAA00;

    private EnchantBookOverlay() {
    }

    /** Draws abbreviation and/or tier over one slot icon at {@code (x, y)} if it is a book. */
    public static void draw(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        SBSConfig.ItemOverlaySettings cfg = ConfigManager.getInstance().get().itemOverlay;
        if (!cfg.bookAbbreviation && !cfg.bookTier) {
            return;
        }
        if (stack == null || stack.isEmpty() || !"ENCHANTED_BOOK".equals(SkyblockItem.id(stack))) {
            return;
        }
        CompoundTag enchants = SkyblockItem.extraAttributes(stack).getCompoundOrEmpty("enchantments");
        if (enchants.keySet().size() != 1) {
            return;   // a combined book has no single identity to label
        }
        String key = enchants.keySet().iterator().next();
        Font font = Minecraft.getInstance().font;

        if (cfg.bookAbbreviation) {
            boolean ultimate = EnchantNames.isUltimate(key);
            Component text = Component.literal(abbreviate(key))
                    .withStyle(style -> style.withBold(ultimate));
            drawScaled(g, font, text, x,
                    y + ITEM_SIZE - font.lineHeight * TEXT_SCALE,
                    ultimate ? COLOR_ULTIMATE : COLOR_NORMAL);
        }
        if (cfg.bookTier) {
            int level = enchants.getIntOr(key, 0);
            if (level > 0) {
                int max = EnchantmentRecipeProvider.maxLevelOf(key);
                Component text = Component.literal(String.valueOf(level));
                drawScaled(g, font, text, x + ITEM_SIZE - font.width(text) * TEXT_SCALE, y,
                        max > 0 && level >= max ? COLOR_MAX_TIER : COLOR_NORMAL);
            }
        }
    }

    /**
     * The on-icon abbreviation for an enchant NBT key, built from its real display name:
     * initials for multi-word names, first three letters for single-word ones, the crop's first
     * three letters for Turbo books (see class doc for why).
     */
    static String abbreviate(String nbtKey) {
        String display = EnchantNames.displayName(nbtKey);
        String[] words = display.split("[ \\-]+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "?";
        }
        if (words.length > 1 && "turbo".equals(words[0].toLowerCase(Locale.ROOT))) {
            return clip(words[1]);
        }
        if (words.length > 1) {
            StringBuilder initials = new StringBuilder(words.length);
            for (String word : words) {
                if (!word.isEmpty()) {
                    initials.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            return initials.toString();
        }
        return clip(words[0]);
    }

    /** "Sharpness" → "Sha": capitalised first three letters. */
    private static String clip(String word) {
        String cut = word.substring(0, Math.min(3, word.length()));
        return cut.isEmpty() ? "?"
                : Character.toUpperCase(cut.charAt(0)) + cut.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Small text at {@link #TEXT_SCALE} with a manual drop shadow – an item icon guarantees no
     * contrast, and the shadow keeps a white "Sha" readable on the book's bright pages.
     */
    private static void drawScaled(GuiGraphicsExtractor g, Font font, Component text,
                                   float leftX, float topY, int color) {
        int shadow = 0xFF000000 | ((color & 0xFCFCFC) >> 2);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(leftX, topY);
        pose.scale(TEXT_SCALE);
        g.text(font, text, 1, 1, shadow);
        g.text(font, text, 0, 0, color);
        pose.popMatrix();
    }
}
