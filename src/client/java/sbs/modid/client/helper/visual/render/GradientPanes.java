/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.MenuFiller;
import sbs.modid.client.core.tick.TickContexts;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.dungeons.terminal.TerminalType;
import sbs.modid.client.helper.bingo.logic.BingoCardParser;
import sbs.modid.client.helper.experiment.logic.ExperimentationTable;
import sbs.modid.client.helper.experiment.logic.HarpHelper;
import sbs.modid.client.skills.foraging.logic.BeaconTuning;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.List;

/**
 * Gradient filler panes: the blank glass panes that pad a SkyBlock menu are drawn as one smooth
 * colour surface - a light-to-dark wash of the pane's own dye, or a travelling rainbow - instead of
 * the glass texture. Display only: the slot, its item and every click on it are untouched; the pane
 * is just not drawn.
 *
 * <p><b>What is never restyled.</b> Only {@link MenuFiller filler} qualifies - a pane with a name or
 * lore is a status cell (on/off toggles, Bazaar order states) and keeps its texture. On top of
 * that, whole menus whose panes <i>are</i> the content are skipped, each through the screen test
 * of the feature that reads them, never a second list of titles: the dungeon terminals
 * ({@link TerminalType#classify}), the Experimentation Table and its games
 * ({@link ExperimentationTable#isExperimentScreen}), Melody's Harp ({@link HarpHelper#songOf}),
 * the beacon tuning puzzle ({@link BeaconTuning#isBeaconMenu}) and the Bingo card
 * ({@link BingoCardParser#TITLE_KEY}). Those tests run once per menu - the decision is cached on
 * the {@link MenuFrame} - so a frame pays one identity compare for it.
 *
 * <p><b>One surface, not tiles.</b> Each filler slot fills its whole 18 px cell, so neighbouring
 * fillers meet without a seam, and the colour is a function of the cell's position on a diagonal
 * across the menu rather than of the slot: the wash darkens from the top-left corner to the
 * bottom-right one, and the rainbow is one sweep across the whole menu.
 *
 * <p><b>Chroma only over one-colour filler.</b> A menu whose filler comes in several dyes is drawn
 * in Gradient mode even when Chroma is chosen, so colours that may carry meaning in a menu we do not
 * know keep their hue. Read once per menu state ({@code getStateId}) and cached with the title test. A cell is drawn as three
 * vertical gradient strips so the diagonal stays continuous across a cell's own width.
 *
 * <p><b>Cost.</b> HOT (per slot per frame). Steady state reads three config fields per frame, one
 * cached int per slot ({@link MenuFiller#fillerDye}) and draws three fills per filler; the per-dye
 * colours are rebuilt only when the opacity setting changes. No allocation.
 *
 * <p>Render thread only.
 */
public final class GradientPanes {

    public static final int MODE_GRADIENT = 0;
    public static final int MODE_CHROMA = 1;

    /** The 18 px slot cell: the 16 px item plus the 1 px border on each side. */
    private static final int CELL = 18;
    private static final int STRIPS = 3;
    private static final int STRIP = CELL / STRIPS;

    /**
     * Length of the diagonal one full wash (or one full rainbow) spans: a nine-wide, six-row chest,
     * {@code 9 * 18 + 6 * 18}. Shorter menus show part of the sweep, which is what keeps the colour
     * of a given corner the same from menu to menu.
     */
    private static final float SPAN = 270.0f;

    /**
     * Stock colours of the sixteen dyes plus plain glass, packed RGB, in {@link MenuFiller#dyeOf}
     * order. Chosen to read as the pane they replace, not taken from any texture.
     */
    private static final int[] DYE_RGB = {
            0xF2F2F2, 0xF07A1A, 0xC34FC0, 0x4FB4E6, 0xF5D53A, 0x7CCB2C, 0xF08CB0, 0x4A4F55,
            0x9A9A94, 0x1C9AA0, 0x8A3AC0, 0x3446B0, 0x7A5030, 0x5C7A1E, 0xB8302A, 0x1C1C22,
            0xC8DCE6};

    // render thread only
    private static final int[] LIGHT = new int[DYE_RGB.length];
    private static final int[] DARK = new int[DYE_RGB.length];
    private static int builtOpacity = -1;

    private static MenuFrame decidedFor;
    private static boolean decidedAllowed;

    /**
     * Whether every filler pane of the decided menu shares one dye, and the menu state it was
     * read at. Chroma is only allowed then: where filler comes in several colours, the colours may
     * mean something in a menu no feature here knows, and a rainbow would erase that.
     */
    private static boolean decidedSingleDye;
    private static int decidedStateId = Integer.MIN_VALUE;
    private static MenuFrame decidedDyesFor;

    /** The mode this frame draws in, or {@code -1} for nothing; set by {@link #begin}. */
    private static int frameMode = -1;
    private static int frameAlpha;

    private GradientPanes() {
    }

    /**
     * The once-per-frame gate, from the HEAD of {@code extractSlots}: setting, SkyBlock, and the
     * cached per-menu exclusion test. Everything {@link #draw} needs is left in static fields.
     */
    public static void begin(AbstractContainerScreen<?> screen) {
        frameMode = -1;
        SBSConfig.ItemOverlaySettings settings = ConfigManager.getInstance().get().itemOverlay;
        if (!settings.gradientPanes || !TickContexts.current().inSkyBlock()) {
            return;
        }
        MenuFrame frame = MenuFrame.of(screen);
        if (frame != decidedFor) {
            decidedFor = frame;
            decidedAllowed = !panesAreContent(frame);
        }
        if (!decidedAllowed) {
            return;
        }
        int opacity = settings.gradientPanesOpacity;
        if (opacity != builtOpacity) {
            rebuild(opacity);
        }
        frameAlpha = alpha(opacity);
        boolean chroma = settings.gradientPanesMode == MODE_CHROMA;
        if (chroma) {
            // Keyed on the state id as well as the frame: the menu's contents arrive after the
            // screen opens, and a page change refills the same screen. Rescanned only then.
            int stateId = screen.getMenu().getStateId();
            if (frame != decidedDyesFor || stateId != decidedStateId) {
                decidedDyesFor = frame;
                decidedStateId = stateId;
                decidedSingleDye = singleFillerDye(screen.getMenu().slots);
            }
            chroma = decidedSingleDye;
        }
        frameMode = chroma ? MODE_CHROMA : MODE_GRADIENT;
    }

    /**
     * Draws the slot as part of the gradient surface when it holds filler.
     *
     * @return whether it did - the caller then skips the pane's own item render
     */
    public static boolean draw(GuiGraphicsExtractor g, Slot slot) {
        int mode = frameMode;
        if (mode < 0 || slot.container instanceof Inventory) {
            return false;
        }
        int dye = MenuFiller.fillerDye(slot.getItem());
        if (dye == MenuFiller.NOT_PANE) {
            return false;
        }
        int x = slot.x - 1;
        int y = slot.y - 1;
        for (int i = 0; i < STRIPS; i++) {
            int sx = x + i * STRIP;
            float top = (sx + y) / SPAN;
            float bottom = (sx + y + CELL) / SPAN;
            int topColor;
            int bottomColor;
            if (mode == MODE_CHROMA) {
                int speed = Chroma.speed();
                topColor = withAlpha(Chroma.color(top, speed), frameAlpha);
                bottomColor = withAlpha(Chroma.color(bottom, speed), frameAlpha);
            } else {
                topColor = lerp(LIGHT[dye], DARK[dye], top);
                bottomColor = lerp(LIGHT[dye], DARK[dye], bottom);
            }
            g.fillGradient(sx, y, sx + STRIP, y + CELL, topColor, bottomColor);
        }
        return true;
    }

    /**
     * Whether the open menu is one whose panes carry the information, asked of each owning
     * feature's own screen test.
     */
    static boolean panesAreContent(MenuFrame frame) {
        String raw = frame.title();
        return TerminalType.classify(raw) != TerminalType.NONE
                || ExperimentationTable.isExperimentScreen(raw)
                || HarpHelper.songOf(PlainText.strip(raw).trim()) != null
                || BeaconTuning.isBeaconMenu(raw)
                || frame.titleContains(BingoCardParser.TITLE_KEY);
    }

    /** {@link #addDye} result once two different dyes have been seen. */
    static final int MIXED = -2;

    /** Whether every filler pane among the menu's own slots carries the same dye. */
    static boolean singleFillerDye(List<Slot> slots) {
        int seen = MenuFiller.NOT_PANE;
        for (int i = 0, n = slots.size(); i < n && seen != MIXED; i++) {
            Slot slot = slots.get(i);
            if (!(slot.container instanceof Inventory)) {
                seen = addDye(seen, MenuFiller.fillerDye(slot.getItem()));
            }
        }
        return seen != MIXED;
    }

    /**
     * One step of the one-colour scan: {@code seen} is {@link MenuFiller#NOT_PANE} before any
     * filler, then the first filler's dye, then {@link #MIXED} for good once a second dye shows
     * up. A {@code dye} of {@code NOT_PANE} (not filler) leaves it as it is.
     */
    static int addDye(int seen, int dye) {
        if (dye == MenuFiller.NOT_PANE || seen == MIXED) {
            return seen;
        }
        if (seen == MenuFiller.NOT_PANE) {
            return dye;
        }
        return seen == dye ? seen : MIXED;
    }

    private static void rebuild(int opacity) {
        int a = alpha(opacity);
        for (int i = 0; i < DYE_RGB.length; i++) {
            LIGHT[i] = withAlpha(mix(DYE_RGB[i], 0xFFFFFF, 0.30f), a);
            DARK[i] = withAlpha(mix(DYE_RGB[i], 0x000000, 0.45f), a);
        }
        builtOpacity = opacity;
    }

    private static int alpha(int opacityPercent) {
        int pct = Math.max(0, Math.min(100, opacityPercent));
        return Math.round(pct * 2.55f);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** Channel-wise blend of two packed RGB colours, {@code t = 0} giving {@code from}. */
    static int mix(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int gr = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (gr << 8) | b;
    }

    /** {@link #mix} for two ARGB colours that share one alpha, {@code t} clamped to {@code [0,1]}. */
    private static int lerp(int from, int to, float t) {
        float clamped = t < 0.0f ? 0.0f : Math.min(1.0f, t);
        return (from & 0xFF000000) | mix(from, to, clamped);
    }
}
