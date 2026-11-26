/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import sbs.modid.client.core.audio.SbsSounds;
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
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The spawn alert: an SBS-styled toast that fires the moment a sea creature appears, so you can look away from
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
                SbsSounds.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.6f);
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
        drawToast(g, font, x + w / 2, y, color, cfg, age);
        HudLayout.end(g);
    }

    /**
     * The alert as an SBS toast: a themed card with an accent bar in the alert colour, a small
     * "SEA CREATURE" caption and the creature's name at 1.5x. It slides down into place and fades
     * with {@link #alertColor}, replacing the bare "!" testers found hard to read.
     */
    private void drawToast(GuiGraphicsExtractor g, Font font, int cx, int y, int color,
                           SBSConfigView cfg, long age) {
        String name = creature.isEmpty() ? "Sea Creature" : creature;
        String caption = "! SEA CREATURE !";
        float nameScale = 1.5f;
        int nameW = (int) Math.ceil(font.width(name) * nameScale);
        int cardW = Math.max(font.width(caption), nameW) + 24;
        int cardH = 34;
        int alpha = (color >>> 24) & 0xFF;
        int slide = cfg.animation() ? (int) Math.round(8 * Math.max(0, 1.0 - age / 200.0)) : 0;
        int left = cx - cardW / 2;
        int top = y - slide;

        SciFiRender.roundedRectWithBorder(g, left, top, cardW, cardH, 4,
                withAlpha(SBSTheme.PANEL_BASE, alpha * 0.9), withAlpha(color, alpha * 0.8));
        SciFiRender.roundedRect(g, left + 2, top + 3, 3, cardH - 6, 1, color);
        g.centeredText(font, Component.literal(caption), cx, top + 4, withAlpha(SBSTheme.TEXT_MUTED, alpha));

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, top + 15);
        pose.scale(nameScale, nameScale);
        g.centeredText(font, Component.literal(name), 0, 0, color);
        pose.popMatrix();
    }

    /** {@code argb} with its alpha scaled to {@code alpha} (0..255) times its own. */
    private static int withAlpha(int argb, double alpha) {
        int own = (argb >>> 24) & 0xFF;
        int a = (int) Math.max(0, Math.min(255, own * alpha / 255.0));
        return (a << 24) | (argb & 0xFFFFFF);
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
}
