/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.MagicStickInput;
import sbs.modid.client.helper.build.logic.Placement;
import sbs.modid.client.helper.build.logic.SelectionActions;
import sbs.modid.client.helper.build.model.BuildHelpLines;
import sbs.modid.client.helper.build.model.SelectionUx;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Magic Stick help card: the commands and keys for what the player is doing - holding the stick,
 * with a selection, or placing a hologram. A movable {@link HudElement} (hideable in the GUI editor),
 * on by default.
 *
 * <p>Lines come from {@link BuildHelpLines}, which names commands through the verb table and keys by
 * their bound names, so the card cannot drift from the real commands or show a key the player
 * rebound. While this card is shown, the placing card leaves out its key lines ({@link #showing}).
 */
public final class BuildHelpHud {

    private static final int PAD = 5;

    private BuildHelpHud() {
    }

    private static SBSConfig.BuildToolsSettings cfg() {
        return ConfigManager.getInstance().get().buildTools;
    }

    /** The state the card describes now, or {@code null} when it is not shown. */
    static BuildHelpLines.State state() {
        SBSConfig.BuildToolsSettings cfg = cfg();
        if (!cfg.enabled || !cfg.helpCard || HudLayout.isHidden(HudElement.BUILD_HELP)) {
            return null;
        }
        if (Placement.active()) {
            return BuildHelpLines.State.HOLOGRAM;
        }
        SelectionManager selection = SelectionManager.getInstance();
        boolean anyCorner = selection.corner1() != null || selection.corner2() != null;
        boolean holding = MagicStickInput.holding();
        // On a server there is no stick: a selection being worked on is what shows the card.
        boolean serverWork = !BuildGate.singleplayer() && anyCorner && SelectionActions.visible();
        if (!holding && !serverWork) {
            return null;
        }
        return selection.selection() != null ? BuildHelpLines.State.SELECTION : BuildHelpLines.State.NO_SELECTION;
    }

    /** Whether the card is on screen - the placing card then leaves its key lines to it. */
    public static boolean showing() {
        return state() != null;
    }

    public static void render(GuiGraphicsExtractor g) {
        BuildHelpLines.State state = state();
        if (state == null) {
            return;
        }
        SBSConfig.BuildToolsSettings cfg = cfg();
        Selection box = SelectionManager.getInstance().selection();
        SelectionManager selection = SelectionManager.getInstance();
        boolean anyCorner = selection.corner1() != null || selection.corner2() != null;
        String size = box == null ? null : SelectionUx.label(box.width(), box.height(), box.length(), false);
        List<BuildHelpLines.Line> lines = BuildHelpLines.lines(state, BuildGate.singleplayer(), cfg.helpCompact,
                new BuildHelpLines.KeyCodes(cfg.corner1Key, cfg.corner2Key, cfg.quickPasteKey, cfg.libraryKey, cfg.freecamKey),
                code -> Keys.displayName(code).getString(), size, placeKeyNames(), anyCorner);
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int maxWidth = Math.max(80, g.guiWidth() - 2 * SBSTheme.SCREEN_MARGIN);
        int width = 0;
        for (BuildHelpLines.Line line : lines) {
            width = Math.max(width, font.width(line.text()));
        }
        width = Math.min(maxWidth, width + PAD * 2);
        int height = PAD * 2 + lineH * lines.size() - 2;
        HudElement.Bounds b = HudElement.BUILD_HELP.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.BUILD_HELP, x, y, width, height);
        HudLayout.begin(g, HudElement.BUILD_HELP);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        for (BuildHelpLines.Line line : lines) {
            g.text(font, Component.literal(RowText.fit(font, line.text(), width - PAD * 2)), x + PAD, iy,
                    line.greyed() ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** The fixed placing keys by their real names: move, height, turn, flip, snap, apply, cancel. */
    static String[] placeKeyNames() {
        return new String[] {"Arrows", name(Placement.Keys.PAGE_UP) + "/" + name(Placement.Keys.PAGE_DOWN),
                name(Placement.Keys.R), name(Placement.Keys.F), name(Placement.Keys.G), name(Placement.Keys.ENTER),
                name(Placement.Keys.ESCAPE)};
    }

    private static String name(int code) {
        return Keys.displayName(code).getString();
    }
}
