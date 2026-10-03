/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.core.render.OverlayColor;

import java.util.List;

/**
 * The bite indicator: a big red "!!!" with "Reel in now!" the moment something bites your bobber –
 * the glanceable version of the tiny particle splash, in the spirit of the classic fishing helpers.
 *
 * <p><b>Detection.</b> Hypixel announces a bite by spawning an armor stand named "{@code !!!}"
 * directly above <i>your</i> bobber. Each tick this scans the few blocks around
 * {@code player.fishing} for that stand: present = biting, gone = over. The rising edge is also the
 * one reliable "you fished" event the client gets, so it feeds the session's
 * {@link FishingTracker#onBite() times-fished counter} and marks fishing activity for the
 * profit tracker's loot window.
 *
 * <p>Renders on {@link HudElement#BITE_ALERT} (movable / scalable in the GUI editor), gated on the
 * Fishing module and on the two parts' own toggles – the "!!!" and the "Reel in now!" line switch
 * on and off independently, so you can keep whichever of the two you actually read.
 */
public final class BiteIndicator {

    private static final BiteIndicator INSTANCE = new BiteIndicator();

    /** How far around the bobber the "!!!" stand is searched (blocks). */
    private static final double SCAN_RANGE = 2.5;

    /** Fallback when the colour field holds a half-typed hex – the long-standing bite red. */
    private static final int DEFAULT_MARK_COLOR = 0xFF4040;

    private volatile boolean biting;
    private long bitingSince;

    private BiteIndicator() {
    }

    public static BiteIndicator getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    /** Called once per client tick from {@link FishingTracker#onTick()}. */
    void onTick(Player player) {
        SBSConfig.FishingSettings cfg = cfg();
        if (!cfg.enabled) {
            biting = false;
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        FishingHook hook = player.fishing;
        if (level == null || hook == null) {
            biting = false;
            return;
        }
        boolean now = hasBiteMarker(level, hook);
        if (now && !biting) {
            bitingSince = System.currentTimeMillis();
            FishingTracker.getInstance().onBite();
            if (cfg.alertSound && anyPart(cfg)) {
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 2.0f);
            }
        }
        biting = now;
    }

    /** Whether an armor stand named "!!!" floats within {@link #SCAN_RANGE} of the bobber. */
    private static boolean hasBiteMarker(ClientLevel level, FishingHook hook) {
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class,
                hook.getBoundingBox().inflate(SCAN_RANGE, SCAN_RANGE + 1.5, SCAN_RANGE),
                stand -> stand.hasCustomName());
        for (ArmorStand stand : stands) {
            var name = stand.getCustomName();
            if (name != null && name.getString().contains("!!!")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drawn from the HUD pass; nothing unless a bite is live and at least one of the two parts is
     * switched on – the "!!!" and the "Reel in now!" line are independent of each other.
     */
    public void render(GuiGraphicsExtractor g) {
        SBSConfig.FishingSettings cfg = cfg();
        if (!biting || !cfg.enabled || !anyPart(cfg) || HudLayout.isHidden(HudElement.BITE_ALERT)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = HudElement.BITE_ALERT.defaultBounds(g.guiWidth(), g.guiHeight());
        int cx = (int) (b.x() + b.w() / 2);
        int y = (int) b.y();
        long age = System.currentTimeMillis() - bitingSince;

        HudLayout.begin(g, HudElement.BITE_ALERT);
        var pose = g.pose();
        if (cfg.biteAlert) {
            // A quick pulse keeps the eye on it without strobing.
            float scale = 2.4f + (float) (0.25 * Math.abs(Math.sin(age / 140.0)));
            pose.pushMatrix();
            pose.translate(cx, y);
            pose.scale(scale, scale);
            g.centeredText(font, Component.literal("!!!"), 0, 0, 0xFF000000 | markRgb(cfg));
            pose.popMatrix();
        }
        if (cfg.reelInText) {
            // Without the marks above it the line takes their place rather than leaving a hole.
            pose.pushMatrix();
            pose.translate(cx, cfg.biteAlert ? y + 30 : y);
            pose.scale(1.5f, 1.5f);
            g.centeredText(font, Component.literal("Reel in now!"), 0, 0, 0xFF57D977);
            pose.popMatrix();
        }
        HudLayout.end(g);
    }

    /** Whether either half of the indicator is switched on. */
    private static boolean anyPart(SBSConfig.FishingSettings cfg) {
        return cfg.biteAlert || cfg.reelInText;
    }

    /** The configured "!!!" colour, falling back to red while the hex field is half-typed. */
    private static int markRgb(SBSConfig.FishingSettings cfg) {
        Integer rgb = OverlayColor.parseHex(cfg.biteColorHex);
        return rgb == null ? DEFAULT_MARK_COLOR : rgb;
    }
}
