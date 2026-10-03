/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.croesus.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.croesus.logic.CroesusMenu;
import sbs.modid.client.dungeons.croesus.logic.CroesusScan;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.SlotDecorations;

/**
 * Croesus's run list, marked: the runs that still owe you a chest stand out, the finished ones are
 * pushed back.
 *
 * <p><b>Both halves are drawn, and that is the point of the pair.</b> Highlighting the live runs
 * alone would leave the finished ones looking merely un-highlighted, which is also what an
 * unrecognised entry looks like - so the two states are given opposite treatments and anything the
 * parser did not recognise is left exactly as Hypixel drew it. Three appearances for three states,
 * and the third one is honest about knowing nothing.
 *
 * <p><b>Marked on top, never replaced.</b> A wash and an outline over Hypixel's own slots; no panel,
 * no reskin, no item substitution. The menu stays the menu - {@code container-reskin-scope} keeps
 * the SBS surface for plain grid screens, and a run list full of custom heads is not one.
 *
 * <p><b>It never clicks.</b> Nothing here opens a chest, claims a run or rerolls anything; the
 * feature reads the menu the player opened and draws on it.
 */
public final class CroesusOverlay {

    /** A run with something left: the mod's accent blue, which no chest mark uses. */
    private static final int LIVE_FILL = 0x553FB4FF;
    private static final int LIVE_FRAME = 0xFF3FB4FF;

    /** A finished run: pushed back rather than outlined. No frame - a frame is emphasis. */
    private static final int DONE_FILL = 0x70101418;

    /** The count chip, bright enough to read over a custom head. */
    private static final int COUNT_COLOR = 0xFFDCEBFF;

    /** Half size, for the same reason the chest profit is - see {@code DungeonChestOverlay}. */
    private static final float SCALE = 0.5f;

    private CroesusOverlay() {
    }

    /**
     * Whether this screen is the menu this feature is about.
     *
     * <p>The one test, asked from the cached frame. {@code tier} and {@code decorate} both come here
     * rather than each carrying a copy: {@code SlotDecorator} is explicit that two tests which can
     * disagree produce a feature that stops drawing on the screen it was written for, with nothing in
     * the log to say why.
     */
    public static boolean isCroesus(MenuFrame frame) {
        if (frame == null) {
            return false;
        }
        SBSConfig.DungeonsSettings cfg = ConfigManager.getInstance().get().dungeons;
        return cfg.croesusRunHighlight
                && CroesusMenu.isCroesusTitle(frame.normalised(), cfg.croesusTitle);
    }

    /** Draws the run marks over the open menu. Self-gating; called from the decorator. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!isCroesus(MenuFrame.of(screen))) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        // The player's own inventory is the last 36 slots and holds no run entries.
        int upper = Math.max(0, menu.getItems().size() - 36);
        if (upper == 0) {
            return;
        }
        CroesusScan.Result result = CroesusScan.get(screen, upper);
        if (result.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        for (var entry : result.entries().entrySet()) {
            int index = entry.getKey();
            if (index >= menu.getItems().size()) {
                continue;   // the menu shrank between the scan and this frame
            }
            Slot slot = menu.getSlot(index);
            if (entry.getValue() == CroesusMenu.RunState.DONE) {
                SlotDecorations.box(g, slot, DONE_FILL, 0);
                continue;
            }
            SlotDecorations.box(g, slot, LIVE_FILL, LIVE_FRAME);
            Integer count = result.counts().get(index);
            if (count != null && count > 0) {
                chip(g, font, slot, count);
            }
        }
    }

    /**
     * How many chests the entry said are waiting, in the slot's top-left corner.
     *
     * <p>Top-left because the bottom-right is where a stack size goes and the bottom edge is where
     * the chest overlay writes its profit - a run list and a chest list can be the same screen on a
     * menu that mixes them, and two features writing in one corner is a number nobody can read.
     * Drawn only when the entry actually named a count: this chip is a detail on top of the
     * highlight, never a guess standing in for one.
     */
    private static void chip(GuiGraphicsExtractor g, Font font, Slot slot, int count) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(slot.x + 1, slot.y + 1);
        pose.scale(SCALE, SCALE);
        g.text(font, Component.literal(String.valueOf(count)), 0, 0, COUNT_COLOR, true);
        pose.popMatrix();
    }
}
