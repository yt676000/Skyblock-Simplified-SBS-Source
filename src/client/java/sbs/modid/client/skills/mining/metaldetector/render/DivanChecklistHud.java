/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.skills.mining.metaldetector.logic.DivanChecklist;
import sbs.modid.client.skills.mining.metaldetector.logic.DivanTracker;
import sbs.modid.client.skills.mining.metaldetector.logic.MetalDetectorTracker;
import sbs.modid.client.skills.mining.metaldetector.model.DivanTool;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Divan Tools card: one row per scavenged tool - missing, found, returned - then the Jade Crystal
 * once all four are back. On the Crystal Hollows, in the Mines of Divan always and elsewhere only
 * while a run is open, so it follows the player out to the Keepers and back without staying up after
 * the crystal is collected.
 */
public final class DivanChecklistHud {

    private static final int PAD = 5;
    private static final int ROWS = DivanTool.values().length + 2;
    private static final int FOUND = 0xFFFFD64D;

    private DivanChecklistHud() {
    }

    /** Called from {@code HudMixin}; self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().metalDetector.checklist
                || HudLayout.isHidden(HudElement.DIVAN_CHECKLIST)
                || !SkyBlockLocation.onIsland(HollowsTracker.ISLAND)) {
            return;
        }
        DivanChecklist checklist = DivanTracker.getInstance().checklist();
        boolean inMines = MetalDetectorTracker.ZONE.equalsIgnoreCase(SkyBlockLocation.zone());
        if (!checklist.visible(inMines)) {
            return;
        }
        String[] lines = new String[ROWS];
        int[] colours = new int[ROWS];
        int n = 0;
        lines[n] = "Divan Tools  " + checklist.obtained() + "/" + DivanTool.values().length;
        colours[n++] = SBSTheme.ACCENT_BRIGHT;
        for (DivanTool tool : DivanTool.values()) {
            switch (checklist.step(tool)) {
                case MISSING -> {
                    lines[n] = tool.displayName() + ": missing";
                    colours[n] = SBSTheme.TEXT_MUTED;
                }
                case FOUND -> {
                    lines[n] = tool.displayName() + ": found, to the Keeper of " + tool.keeper();
                    colours[n] = FOUND;
                }
                case RETURNED -> {
                    lines[n] = tool.displayName() + ": returned";
                    colours[n] = SBSTheme.TOGGLE_ON;
                }
            }
            n++;
        }
        if (checklist.jade() == DivanChecklist.Jade.READY) {
            lines[n] = "Jade Crystal: ready, punch it";
            colours[n++] = FOUND;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int width = 0;
        for (int i = 0; i < n; i++) {
            width = Math.max(width, font.width(lines[i]));
        }
        width += PAD * 2;
        int height = PAD * 2 + lineH * n - 2;
        HudElement.Bounds b = HudElement.DIVAN_CHECKLIST.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.DIVAN_CHECKLIST, x, y, width, height);
        HudLayout.begin(g, HudElement.DIVAN_CHECKLIST);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        for (int i = 0; i < n; i++) {
            g.text(font, Component.literal(lines[i]), x + PAD, iy, colours[i]);
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
