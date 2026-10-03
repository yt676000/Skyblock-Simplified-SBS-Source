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
import sbs.modid.client.combat.slayer.model.BlazeAttunement;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Blaze attunement alert: the mode the fight is asking for, and the mode your dagger is actually
 * on, one above the other a little over the crosshair.
 *
 * <p>Two lines rather than one, because the useful thing is not either mode on its own but whether
 * they agree – wrong mode and every swing is worth one percent, with nothing in the world to tell you
 * so. The lower line is therefore the one that carries the colour: green when they match, red when
 * the dagger still needs flipping.
 *
 * <p>Drawn as bare text, no panel: it sits where the eyes already are during a fight, and a card there
 * would cover the thing being fought. Goes the moment the attunement tag stops being seen – a stale
 * mode reads exactly like a live one and costs exactly as much.
 */
public final class BlazeAttunementHud {

    private static final float NEEDED_SCALE = 1.5f;
    private static final int LINE_GAP = 3;
    private static final int MATCH_COLOR = 0xFF57D977;
    private static final int MISMATCH_COLOR = 0xFFFF4040;

    private BlazeAttunementHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerTracker tracker = SlayerTracker.getInstance();
        if (!cfg.enabled || !cfg.attunementDisplay || Minecraft.getInstance().player == null
                || !tracker.attunementFresh() || HudLayout.isHidden(HudElement.BLAZE_ATTUNEMENT)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.BLAZE_ATTUNEMENT.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.BLAZE_ATTUNEMENT);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, SlayerTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;
        BlazeAttunement needed = tracker.neededAttunement();
        if (needed == null) {
            return;
        }
        BlazeAttunement held = tracker.heldAttunement();
        String neededLine = needed.name();
        String heldLine = held == null
                ? "no " + needed.name() + "/" + needed.partner().name() + " dagger"
                : "dagger: " + held.name();
        int heldColor = held == null ? SBSTheme.TEXT_MUTED
                : held == needed ? MATCH_COLOR : MISMATCH_COLOR;

        int neededWidth = (int) Math.ceil(font.width(neededLine) * NEEDED_SCALE);
        int neededHeight = (int) Math.ceil(font.lineHeight * NEEDED_SCALE);
        int width = Math.max(neededWidth, font.width(heldLine));
        int height = neededHeight + LINE_GAP + font.lineHeight;
        // The editor box has to wrap the text that is really there, or a two-word mode and a
        // "no dagger" line would drag the anchor around every time one of them changed.
        HudLayout.measure(HudElement.BLAZE_ATTUNEMENT, x, y, width, height);

        int centre = x + width / 2;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(centre - neededWidth / 2f, y);
        pose.scale(NEEDED_SCALE, NEEDED_SCALE);
        g.text(font, Component.literal(neededLine), 0, 0, tracker.attunementColor());
        pose.popMatrix();

        g.text(font, Component.literal(heldLine), centre - font.width(heldLine) / 2,
                y + neededHeight + LINE_GAP, heldColor);
    }
}
