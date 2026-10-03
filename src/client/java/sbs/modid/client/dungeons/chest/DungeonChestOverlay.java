/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.render.SlotDecorations;

/**
 * What every reward chest in the open menu is worth, written on the chests themselves.
 *
 * <p><b>Why a number and not only a highlight.</b> The highlight answers "which one" and says nothing
 * about "by how much" - and in Croesus's run list, where a dozen chests from several runs are on one
 * page, that is most of the question. Reading the answer used to mean hovering each chest in turn,
 * which is the work this exists to remove.
 *
 * <p><b>The number's shape, which is not the usual one.</b> A leading minus marks a loss; a
 * <i>trailing</i> {@code +} marks a total that is a floor rather than a figure, because something in
 * the chest had no live price. Written the other way round - a leading {@code +} for profit, as the
 * tooltip writes it - an incomplete profit would read {@code +2.4M+}, which says nothing twice.
 * Money is already written with a minus and no plus, so this costs the reader nothing to learn.
 *
 * <p><b>The colour is never the only channel.</b> Red fill and red text agree with a minus sign that
 * is there whether or not the player can separate red from green, which is what {@code ui/AGENTS.md}
 * asks of any state drawn in colour.
 *
 * <p>Nothing here prices anything: {@link DungeonChestRanking} holds one throttled scan of the menu
 * and both the highlight and this text read it, so a menu is valued once per quarter second however
 * many things are drawn from it.
 */
public final class DungeonChestOverlay {

    /** Translucent fill + solid frame; yellow = profitable, green = the best, red = a loss. */
    private static final int YELLOW_FILL = 0x60FFD24D;
    private static final int YELLOW_FRAME = 0xFFFFD24D;
    private static final int GREEN_FILL = 0x6057D977;
    private static final int GREEN_FRAME = 0xFF57D977;
    private static final int RED_FILL = 0x50FF6B6B;
    private static final int RED_FRAME = 0xFFFF6B6B;

    /** Text colours, brighter than the frames: these sit over an item rather than beside one. */
    private static final int TEXT_GAIN = 0xFF7BF09B;
    private static final int TEXT_LOSS = 0xFFFF8A8A;

    /**
     * How small the profit is drawn.
     *
     * <p>Half size, because a slot is 16 px wide and the shortest honest number a chest produces -
     * {@code -1.2M+}, seven glyphs - is about 40 px at full size. At half it is 20 px, which still
     * overhangs a slot by a few pixels on its left; that is deliberate and the reason the text is
     * right-aligned, since a slot's left neighbour is another chest's number rather than its icon.
     */
    private static final float SCALE = 0.5f;

    private DungeonChestOverlay() {
    }

    /** Draws the chest marks over the open menu. Self-gating; called from the decorator. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        SBSConfig.DungeonsSettings cfg = ConfigManager.getInstance().get().dungeons;
        if (!cfg.chestValue) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        // The player inventory is the last 36 slots and can never hold a reward chest, so the scan
        // stops before it - both to save the work and so a chest-shaped item in your own bags can
        // never be ranked against the menu's.
        int upper = Math.max(0, menu.getItems().size() - 36);
        if (upper == 0) {
            return;
        }
        DungeonChestRanking.Ranking ranking = DungeonChestRanking.get(menu, upper);
        if (ranking.noChests()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        for (var entry : ranking.chests().entrySet()) {
            int index = entry.getKey();
            if (index >= menu.getItems().size()) {
                continue;   // the menu shrank between the scan and this frame
            }
            Slot slot = menu.getSlot(index);
            DungeonChestValue.Chest chest = entry.getValue();
            long profit = chest.profit();
            boolean best = index == ranking.bestSlot();
            box(g, slot, profit, best, cfg);
            if (cfg.chestProfitText) {
                text(g, font, slot, profit, chest.complete());
            }
        }
    }

    /**
     * The number as it is written on a slot: {@code 2.4M}, {@code -400K}, {@code 2.4M+}.
     *
     * <p>Always the short form, whatever the player set for numbers elsewhere - no preference makes
     * {@code 3,400,000} fit sixteen pixels, which is the case {@code NumberDisplay.shorten} exists
     * for. Separated out from the drawing because the convention it encodes is the part worth
     * pinning down: a minus is a sign and a plus is not, and nothing should ever carry both.
     *
     * @param complete whether every item in the chest could be priced; a chest with an unpriced drop
     *                 is worth <i>at least</i> what is shown, which is what the trailing plus says
     */
    public static String profitText(long profit, boolean complete) {
        return NumberDisplay.shorten(profit) + (complete ? "" : "+");
    }

    /**
     * The slot's highlight: green for the best chest, yellow for any other that profits, red for one
     * that does not.
     *
     * <p>A losing chest is marked only when the player asked for it. Left off, the menu looks exactly
     * as it did before this class existed - which matters, because "no highlight" was the shipped
     * meaning of "not worth opening" for long enough that somebody has learned it.
     */
    private static void box(GuiGraphicsExtractor g, Slot slot, long profit, boolean best,
                            SBSConfig.DungeonsSettings cfg) {
        if (profit > 0) {
            SlotDecorations.box(g, slot, best ? GREEN_FILL : YELLOW_FILL,
                    best ? GREEN_FRAME : YELLOW_FRAME);
        } else if (cfg.chestLossHighlight) {
            SlotDecorations.box(g, slot, RED_FILL, RED_FRAME);
        }
    }

    /**
     * The profit, small, along the slot's bottom edge and hard against its right.
     *
     * <p>Right-aligned so a column of chests reads as a column of numbers with their magnitudes
     * lined up, which is the comparison the menu is actually asking for. Drawn with a shadow: this
     * sits on top of an item's own texture, and thin half-size glyphs on a bright chest are otherwise
     * unreadable at exactly the moment they matter.
     */
    private static void text(GuiGraphicsExtractor g, Font font, Slot slot, long profit,
                             boolean complete) {
        String value = profitText(profit, complete);
        int width = font.width(value);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(slot.x + 16 - width * SCALE, slot.y + 16 - font.lineHeight * SCALE);
        pose.scale(SCALE, SCALE);
        g.text(font, Component.literal(value), 0, 0, profit > 0 ? TEXT_GAIN : TEXT_LOSS, true);
        pose.popMatrix();
    }
}
