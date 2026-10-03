/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.skills.fishing.logic.SpawnAlertFilter;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.core.render.OverlayColor;

/**
 * The spawn alert: a big "!" that fires the moment a sea creature appears, so you can look away from
 * the water and still know to turn round.
 *
 * <p>Triggered from {@link FishingTracker}'s chat intake – the same spawn message that books the
 * catch raises the alert, so the two can never disagree about what spawned.
 *
 * <p>It rides on {@link HudElement#FISHING_ALERT}, which is what makes it movable and scalable in
 * the GUI editor without this class knowing anything about either.
 */
public final class FishingAlert {

    private static final FishingAlert INSTANCE = new FishingAlert();

    /** When the current alert started, or 0 when none is showing. */
    private long shownAt;
    /** What spawned – drawn under the "!" so a glance tells you whether it is worth turning for. */
    private String creature = "";

    private FishingAlert() {
    }

    public static FishingAlert getInstance() {
        return INSTANCE;
    }

    /** Fallback when the colour field holds a half-typed hex – the long-standing alert red. */
    private static final int DEFAULT_COLOR = 0xFF5555;

    private static SBSConfigView config() {
        var cfg = ConfigManager.getInstance().get().fishing;
        Integer rgb = OverlayColor.parseHex(cfg.alertColorHex);
        return new SBSConfigView(cfg.enabled, cfg.spawnAlert, rgb == null ? DEFAULT_COLOR : rgb,
                cfg.alertSound, cfg.alertAnimation, cfg.alertDurationMs, cfg.alertFilter());
    }

    /** A flattened read of the settings, so the render path touches the config once per frame. */
    private record SBSConfigView(boolean enabled, boolean spawnAlert, int rgb, boolean sound,
                                 boolean animation, int durationMs, SpawnAlertFilter filter) {
    }

    /** Raises the alert for {@code creature}; called when its spawn message arrives. */
    public void trigger(String creature) {
        SBSConfigView cfg = config();
        if (!cfg.enabled() || !cfg.spawnAlert() || !cfg.filter().allows(creature)) {
            return;
        }
        this.creature = creature == null ? "" : creature;
        this.shownAt = System.currentTimeMillis();
        if (cfg.sound()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                // The note block "pling" cuts through ambient water noise without being a jumpscare.
                minecraft.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.6f);
            }
        }
    }

    /** Whether the alert is still within its configured lifetime. */
    private boolean showing(SBSConfigView cfg) {
        return shownAt > 0 && System.currentTimeMillis() - shownAt < cfg.durationMs();
    }

    /** Drawn from the HUD pass; does nothing unless an alert is live. */
    public void render(GuiGraphicsExtractor g) {
        SBSConfigView cfg = config();
        if (!cfg.enabled() || !cfg.spawnAlert() || HudLayout.isHidden(HudElement.FISHING_ALERT)) {
            return;
        }
        if (!showing(cfg)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = HudElement.FISHING_ALERT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) b.x();
        int y = (int) b.y();
        int w = (int) b.w();

        long age = System.currentTimeMillis() - shownAt;
        int color = alertColor(cfg, age);

        HudLayout.begin(g, HudElement.FISHING_ALERT);
        // The "!" is the whole point, so it is drawn as scaled-up text rather than an icon: it
        // stays crisp at any HUD scale and needs no texture.
        drawBigMark(g, font, x + w / 2, y, color, cfg, age);
        if (!creature.isEmpty()) {
            // Clears the scaled "!" below its baseline; a tighter offset lets the two collide.
            g.centeredText(font, Component.literal(creature), x + w / 2, y + 36, color);
        }
        HudLayout.end(g);
    }

    /**
     * The configured colour, faded out over the last third of the alert's life (and pulsing while
     * animation is on) – a hard disappearance reads as a glitch, a fade reads as "that is over".
     */
    private int alertColor(SBSConfigView cfg, long age) {
        double life = Math.min(1.0, age / (double) Math.max(1, cfg.durationMs()));
        double fade = life > 0.66 ? 1.0 - (life - 0.66) / 0.34 : 1.0;
        double pulse = cfg.animation() ? 0.65 + 0.35 * Math.abs(Math.sin(age / 160.0)) : 1.0;
        int alpha = (int) Math.max(0, Math.min(255, 255 * fade * pulse));
        return (alpha << 24) | cfg.rgb();
    }

    /** The "!" itself, scaled about its own centre so the HUD element's own scale still applies. */
    private void drawBigMark(GuiGraphicsExtractor g, Font font, int cx, int y, int color,
                             SBSConfigView cfg, long age) {
        float scale = 3.6f;
        if (cfg.animation()) {
            // A short pop on arrival: eye-catching once, then settled.
            scale += (float) (0.6 * Math.max(0, 1.0 - age / 250.0));
        }
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, y);
        pose.scale(scale, scale);
        g.centeredText(font, Component.literal("!"), 0, 0, color);
        pose.popMatrix();
    }
}
