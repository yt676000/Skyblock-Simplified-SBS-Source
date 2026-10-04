/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.client.gui.screens.Screen;
import sbs.modid.client.combat.diana.model.MythCreature;
import sbs.modid.client.core.config.SBSConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Diana appearance preview: when it runs, and the made-up numbers it shows.
 *
 * <p>Runs only while the player switched it on <b>and</b> one of the screens they style things from
 * is open - the SBS settings, the Diana line editor, the HUD editor or the colour picker. Closing
 * the screen ends it on the next frame, so the sample cards and markers never reach normal play.
 * Nothing here reads or writes real state: the data is fixed, and the markers are published under
 * their own source tag.
 */
public final class DianaPreview {

    private DianaPreview() {
    }

    public static boolean active(SBSConfig.DianaSettings cfg) {
        if (cfg.appearance == null || !cfg.appearance.preview) {
            return false;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        return screen instanceof sbs.modid.client.ui.screen.SBSMainScreen
                || screen instanceof sbs.modid.client.combat.diana.ui.DianaLayoutScreen
                || screen instanceof sbs.modid.client.ui.hud.edit.ui.HudEditorScreen
                || screen instanceof sbs.modid.client.ui.theme.ThemeColorPickerScreen;
    }

    /** Numbers that put a row on every line, so each one can be seen and placed. */
    public static DianaHudRows.Data sampleData() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("Minos Hunter", 14L);
        counts.put("Siamese Lynxes", 9L);
        counts.put("Minotaur", 6L);
        counts.put("Gaia Construct", 4L);
        counts.put("Minos Champion", 3L);
        counts.put(MythCreature.INQUISITOR.defaultName(), 1L);
        return new DianaHudRows.Data(2, 21 * 60 * 1000L + 37_000L, true,
                128, 37, 52, 11, 23, counts,
                List.of(
                        new DianaHudRows.Creature(MythCreature.INQUISITOR.defaultName(), "18.4M",
                                MythCreature.INQUISITOR.color(), false),
                        new DianaHudRows.Creature(MythCreature.MANTICORE.defaultName(), "7.1M",
                                MythCreature.MANTICORE.color(), true)));
    }
}
