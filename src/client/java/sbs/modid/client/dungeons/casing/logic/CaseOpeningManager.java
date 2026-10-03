/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.casing.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.casing.model.CaseItem;
import sbs.modid.client.dungeons.casing.render.CaseOpeningReel;
import sbs.modid.client.dungeons.casing.ui.CaseOpeningScreen;
import sbs.modid.client.core.item.Rarity;

import java.util.List;

/**
 * The entry point that turns a known reward + loot pool into a running case-opening animation.
 *
 * <p>Kept separate from the screen and the reel so the trigger side (a real dungeon chest later, or
 * the {@link #preview()} button now) has one place to call, and the visual pieces stay pure.
 *
 * <p><b>Auto-trigger from a real dungeon chest is not wired here yet</b>, on purpose: reading the
 * reward items out of Hypixel's reward-chest GUI has to be checked against a real chest, and this
 * session had none to verify against. The animation, its landing and its effects are complete and
 * exercised through {@link #preview()}; wiring the detector is the remaining step.
 */
public final class CaseOpeningManager {

    private CaseOpeningManager() {
    }

    /**
     * Starts the animation for a reward that is already decided.
     *
     * @param pool   the items that may appear as filler on the reel (the chest's loot pool)
     * @param winner the reward the player actually receives – the reel lands here
     */
    public static void open(List<CaseItem> pool, CaseItem winner) {
        if (!ConfigManager.getInstance().get().caseOpening.enabled || winner == null) {
            return;
        }
        long duration = ConfigManager.getInstance().get().caseOpening.durationMs;
        CaseOpeningReel reel = CaseOpeningReel.build(pool, winner, duration,
                System.nanoTime(), System.currentTimeMillis());
        Minecraft.getInstance().setScreenAndShow(new CaseOpeningScreen(reel));
    }

    /**
     * Shows a representative animation from the settings screen, so the look can be judged without a
     * dungeon. The reel holds the whole M7 loot pool and lands on the Dark Claymore, so the reel
     * shows only real M7 items and the landing runs the full top-tier flourish.
     */
    public static void preview() {
        CaseItem winner = new CaseItem("DARK_CLAYMORE", Rarity.LEGENDARY);
        long duration = ConfigManager.getInstance().get().caseOpening.durationMs;
        CaseOpeningReel reel = CaseOpeningReel.build(DungeonLoot.M7, winner, duration,
                System.nanoTime(), System.currentTimeMillis());
        Minecraft.getInstance().setScreenAndShow(new CaseOpeningScreen(reel));
    }
}
