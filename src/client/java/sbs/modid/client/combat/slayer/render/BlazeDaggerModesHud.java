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

/**
 * The Blaze dagger in your hand, big: the mode it is in now, and under it the one mode a right-click
 * flips it to. Shown whenever a Blaze dagger is held, fight or not, so the two modes of each dagger
 * are learnt without opening its lore.
 *
 * <p>While a fight is asking for a mode the second line also says what to do about it: nothing (the
 * mode matches), flip (the other mode of this dagger), or swap to the other dagger. The colours are
 * the attunements' own, so the big word reads the same as the word floating over the boss.
 *
 * <p>Text only, no panel, like the attunement readout. Everything drawn comes from state the slayer
 * tick prepared; the two components are rebuilt only when what they say changes.
 */
public final class BlazeDaggerModesHud {

    private static final float MODE_SCALE = 2.0f;
    private static final int LINE_GAP = 3;
    private static final int MATCH_COLOR = 0xFF57D977;
    private static final int MISMATCH_COLOR = 0xFFFF4040;

    // What the cached lines say: the mode in hand and the mode the fight needs (null = none).
    private static BlazeAttunement cachedHeld;
    private static BlazeAttunement cachedNeeded;
    private static Component modeLine = Component.empty();
    private static Component hintLine = Component.empty();
    private static int hintColor;

    private BlazeDaggerModesHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerTracker tracker = SlayerTracker.getInstance();
        BlazeAttunement held = tracker.handDaggerMode();
        if (!cfg.enabled || !cfg.heldDaggerDisplay || held == null || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.BLAZE_DAGGER)) {
            return;
        }
        BlazeAttunement needed = tracker.attunementFresh() ? tracker.neededAttunement() : null;
        if (held != cachedHeld || needed != cachedNeeded) {
            rebuild(held, needed);
        }

        Font font = Minecraft.getInstance().font;
        int modeWidth = (int) Math.ceil(font.width(modeLine) * MODE_SCALE);
        int modeHeight = (int) Math.ceil(font.lineHeight * MODE_SCALE);
        int hintWidth = font.width(hintLine);
        int width = Math.max(modeWidth, hintWidth);
        int height = modeHeight + LINE_GAP + font.lineHeight;

        HudElement.Bounds bounds = HudElement.BLAZE_DAGGER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) (bounds.x() + (bounds.w() - width) / 2f);
        int y = (int) bounds.y();
        HudLayout.begin(g, HudElement.BLAZE_DAGGER);
        HudLayout.measure(HudElement.BLAZE_DAGGER, x, y, width, height);
        int centre = x + width / 2;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(centre - modeWidth / 2f, y);
        pose.scale(MODE_SCALE, MODE_SCALE);
        g.text(font, modeLine, 0, 0, color(held));
        pose.popMatrix();
        g.text(font, hintLine, centre - hintWidth / 2, y + modeHeight + LINE_GAP, hintColor);
        HudLayout.end(g);
    }

    private static void rebuild(BlazeAttunement held, BlazeAttunement needed) {
        cachedHeld = held;
        cachedNeeded = needed;
        BlazeAttunement partner = held.partner();
        String hint;
        if (needed == null) {
            hint = "⇄ " + partner.name();
            hintColor = color(partner);
        } else if (needed == held) {
            hint = "✔ " + held.name() + "  ·  ⇄ " + partner.name();
            hintColor = MATCH_COLOR;
        } else if (needed == partner) {
            hint = "FLIP ⇄ " + partner.name();
            hintColor = MISMATCH_COLOR;
        } else {
            hint = "OTHER DAGGER → " + needed.name();
            hintColor = MISMATCH_COLOR;
        }
        modeLine = Component.literal(held.name());
        hintLine = Component.literal(hint);
    }

    /** The attunement's own colour, as the word is drawn over the boss. */
    private static int color(BlazeAttunement attunement) {
        return switch (attunement) {
            case ASHEN -> 0xFF9A9A9A;     // Hypixel's dark grey is unreadable on a dark sky; lighter
            case SPIRIT -> 0xFFFFFFFF;
            case AURIC -> 0xFFFFFF55;
            case CRYSTAL -> 0xFF55FFFF;
        };
    }
}
