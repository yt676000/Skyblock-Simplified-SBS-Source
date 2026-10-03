/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * The module's flash call-out: one short, large, coloured line above the crosshair, plus a ping.
 *
 * <p><b>Kuudra's urgent moments all want the same shape of warning.</b> "No tri", "JUMP", "FRESH",
 * a phase turning over - each is a single word that has to land while you are looking at something
 * else, and each is worthless a second later. Giving them one look and one sound means the reaction
 * gets learned once instead of four times, and the pitch is what tells them apart without reading.
 *
 * <p>One at a time, newest wins. A run raises these rarely, and queueing would only ever delay the
 * one that is actually urgent behind one that has stopped being urgent.
 */
public final class KuudraAlert {

    private static final KuudraAlert INSTANCE = new KuudraAlert();

    /** How long a call-out stays up. Long enough to catch out of the corner of an eye, no longer. */
    private static final long SHOW_MS = 2_000L;

    /** Text scale - readable without looking away from the fight. */
    private static final float SCALE = 2.0f;

    /** How far above the crosshair it sits, in GUI pixels. */
    private static final int ABOVE_CROSSHAIR = 46;

    private volatile String text = "";
    private volatile int color;
    private volatile long until;

    private KuudraAlert() {
    }

    public static KuudraAlert getInstance() {
        return INSTANCE;
    }

    /**
     * Flashes {@code message} for a moment.
     *
     * @param sound whether to ping - the calling feature's own toggle decides, not this class
     * @param pitch note pitch, so two different call-outs stay apart without being read
     */
    public void flash(String message, int argb, boolean sound, float pitch) {
        this.text = message;
        this.color = argb;
        this.until = System.currentTimeMillis() + SHOW_MS;
        Minecraft minecraft = Minecraft.getInstance();
        if (sound && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch);
        }
    }

    /** Drops the current call-out - a run ending should not leave one hanging on screen. */
    public void clear() {
        until = 0;
        text = "";
    }

    /** Called from the HUD render hook once per frame; draws nothing while nothing is live. */
    public void render(GuiGraphicsExtractor g) {
        if (System.currentTimeMillis() > until || text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(g.guiWidth() / 2, g.guiHeight() / 2 - ABOVE_CROSSHAIR);
        pose.scale(SCALE, SCALE);
        g.text(font, Component.literal(text), -font.width(text) / 2, 0, color);
        pose.popMatrix();
    }
}
