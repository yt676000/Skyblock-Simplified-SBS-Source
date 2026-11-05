/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.combat.slayer.model.InfernoPhase;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;

/**
 * The Inferno phase timer: one line under the crosshair saying whether your boss is whole or split
 * into its demons, and how long it has been that way – "Demons #1 · 12.4s".
 *
 * <p>It counts up, never down. How long a split lasts is up to how fast the demons die, so there is
 * no fixed length a countdown could honestly aim at; time spent is what tells you whether this split
 * is going faster or slower than the last.
 */
public final class InfernoPhaseHud {

    private static final int BOSS_COLOR = 0xFFFFB347;
    private static final int DEMON_COLOR = 0xFFFF5555;

    private InfernoPhaseHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerTracker tracker = SlayerTracker.getInstance();
        InfernoPhase phase = tracker.infernoPhase();
        if (!cfg.enabled || !cfg.infernoPhaseTimer || phase == InfernoPhase.NONE
                || Minecraft.getInstance().player == null || HudLayout.isHidden(HudElement.INFERNO_PHASE)) {
            return;
        }
        String line = line(phase, tracker.infernoSplit(), tracker.infernoPhaseElapsed());
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds bounds = HudElement.INFERNO_PHASE.defaultBounds(g.guiWidth(), g.guiHeight());
        int width = font.width(line);
        int x = (int) (bounds.x() + (bounds.w() - width) / 2f);
        int y = (int) bounds.y();
        HudLayout.begin(g, HudElement.INFERNO_PHASE);
        HudLayout.measure(HudElement.INFERNO_PHASE, x, y, width, font.lineHeight);
        g.text(font, Component.literal(line), x, y, phase == InfernoPhase.DEMONS ? DEMON_COLOR : BOSS_COLOR);
        HudLayout.end(g);
    }

    /** The text of the line, e.g. "Demons #2 · 8.0s" or "Boss · 1:04". */
    static String line(InfernoPhase phase, int split, long elapsedMs) {
        String label = phase == InfernoPhase.DEMONS ? phase.label() + " #" + Math.max(1, split) : phase.label();
        return label + " · " + duration(elapsedMs);
    }

    /** Tenths of a second under a minute, where they matter; m:ss beyond it. */
    static String duration(long ms) {
        long tenths = ms / 100;
        if (tenths < 600) {
            return (tenths / 10) + "." + (tenths % 10) + "s";
        }
        long seconds = ms / 1000;
        return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d",seconds % 60);
    }
}
