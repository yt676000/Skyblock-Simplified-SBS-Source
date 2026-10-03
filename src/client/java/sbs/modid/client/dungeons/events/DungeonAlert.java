/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.events;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * The dungeon helpers' flash alert: one short, large, coloured line above the crosshair plus a ping.
 *
 * <p>Shared on purpose. A run has a handful of moments where something has to be done <i>now</i> -
 * the blood room going live, the Spirit Bear landing - and they should look and sound the same
 * whoever raises them, so the reaction is learned once. One alert at a time: a run throws them
 * rarely enough that queueing would only delay the urgent one, and the newest is the one that counts.
 */
public final class DungeonAlert {

    private static final DungeonAlert INSTANCE = new DungeonAlert();

    private static final long SHOW_MS = 2_000L;

    /** Text scale - large enough to read without looking away from the fight. */
    private static final float SCALE = 2.0f;

    private volatile String text = "";
    private volatile int color;
    private volatile long until;

    private DungeonAlert() {
    }

    public static DungeonAlert getInstance() {
        return INSTANCE;
    }

    /**
     * Flashes {@code message} above the crosshair for a moment.
     *
     * @param sound whether to ping - the calling feature's own alert toggle decides, not this class
     * @param pitch note pitch, so two different calls stay distinguishable without looking
     */
    public void trigger(String message, int argb, boolean sound, float pitch) {
        this.text = message;
        this.color = argb;
        this.until = System.currentTimeMillis() + SHOW_MS;
        Minecraft minecraft = Minecraft.getInstance();
        if (sound && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch);
        }
    }

    /** Drops the current alert (a room ending should not leave its call-out hanging). */
    public void clear() {
        until = 0;
        text = "";
    }

    /** Called from the HUD render hook once per frame; draws nothing while no alert is live. */
    public void render(GuiGraphicsExtractor g) {
        if (System.currentTimeMillis() > until || text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(g.guiWidth() / 2, g.guiHeight() / 2 - 46);
        pose.scale(SCALE, SCALE);
        g.text(font, Component.literal(text), -font.width(text) / 2, 0, color);
        pose.popMatrix();
    }
}
