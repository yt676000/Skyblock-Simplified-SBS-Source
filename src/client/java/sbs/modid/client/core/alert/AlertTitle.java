/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;

/**
 * The shared on-screen title for the {@link AlertChannel#TITLE} channel: two lines above the
 * crosshair that fade out by themselves.
 *
 * <p>Shared so every alert looks the same and only one can be on screen at a time - the alternative
 * is each feature drawing its own, which is how two simultaneous alerts end up overlapping. A
 * feature with its own established title (the pest spawn title has one, with its own placement and
 * colour) keeps drawing that; this is for everything that just wants "put it on the screen".
 */
public final class AlertTitle {

    /** How long a title stays up before it has faded out completely. */
    private static final long SHOWN_MS = 3_000L;

    /** The fraction of the lifetime spent at full opacity; the rest fades. */
    private static final float SOLID_FRACTION = 0.66f;

    private static volatile String headline = "";
    private static volatile String detail = "";
    private static volatile long shownAt;

    private AlertTitle() {
    }

    /** Raises the title. Call on the game thread. */
    public static void show(String headlineText, String detailText) {
        headline = headlineText == null ? "" : headlineText;
        detail = detailText == null ? "" : detailText;
        shownAt = System.currentTimeMillis();
    }

    /** Expires the title once its lifetime is up - cheap enough to run every tick. */
    public static void tick() {
        if (shownAt != 0 && System.currentTimeMillis() - shownAt > SHOWN_MS) {
            shownAt = 0;
        }
    }

    /** Clears it immediately (world change). */
    public static void clear() {
        shownAt = 0;
    }

    /** Drawn from the HUD pass. Self-hiding: nothing on screen while no alert is live. */
    public static void render(GuiGraphicsExtractor g) {
        long at = shownAt;
        if (at == 0 || HudLayout.isHidden(HudElement.SBS_ALERT)) {
            return;
        }
        long age = System.currentTimeMillis() - at;
        if (age > SHOWN_MS) {
            return;
        }
        float life = Math.min(1f, age / (float) SHOWN_MS);
        int alpha = (int) (255 * (life > SOLID_FRACTION ? (1 - life) / (1 - SOLID_FRACTION) : 1f));
        int color = (Math.max(16, Math.min(255, alpha)) << 24) | 0x00FFD65A;

        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = HudElement.SBS_ALERT.defaultBounds(g.guiWidth(), g.guiHeight());
        int centreX = (int) (b.x() + b.w() / 2);
        int y = (int) b.y();

        HudLayout.begin(g, HudElement.SBS_ALERT);
        g.centeredText(font, Component.literal(headline), centreX, y, color);
        if (!detail.isEmpty()) {
            g.centeredText(font, Component.literal(detail), centreX, y + font.lineHeight + 2, color);
        }
        HudLayout.end(g);
    }
}
