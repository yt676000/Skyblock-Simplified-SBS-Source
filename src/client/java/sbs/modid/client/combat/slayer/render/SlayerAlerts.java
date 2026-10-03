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
import net.minecraft.sounds.SoundEvents;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Slayer module's flash alerts: a short, large, coloured word above the crosshair
 * ("BEACON!", "NUKEKUBI", "MINIBOSS", …) plus an optional ping. One alert at a time – a fight
 * throws them rarely enough that queueing would only delay the urgent one.
 */
public final class SlayerAlerts {

    private static final SlayerAlerts INSTANCE = new SlayerAlerts();

    private static final long SHOW_MS = 1_800L;

    private volatile String text = "";
    private volatile int color;
    private volatile long until;

    private SlayerAlerts() {
    }

    public static SlayerAlerts getInstance() {
        return INSTANCE;
    }

    /** Shows {@code message} for a moment (and pings, when the sound toggle is on). */
    public void trigger(String message, int argb) {
        this.text = message;
        this.color = argb;
        this.until = System.currentTimeMillis() + SHOW_MS;
        Minecraft minecraft = Minecraft.getInstance();
        if (ConfigManager.getInstance().get().slayer.alertSound && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.2f);
        }
    }

    /** Called from the HUD render hook once per frame; draws nothing while no alert is live. */
    public void render(GuiGraphicsExtractor g) {
        if (System.currentTimeMillis() > until || text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        // Doubled size via the 2D pose so the warning reads instantly mid-fight.
        var pose = g.pose();
        pose.pushMatrix();
        float scale = 2.0f;
        int x = g.guiWidth() / 2;
        int y = g.guiHeight() / 2 - 46;
        pose.translate(x, y);
        pose.scale(scale, scale);
        g.text(font, Component.literal(text), -font.width(text) / 2, 0, color);
        pose.popMatrix();
    }
}
