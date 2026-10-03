/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.collection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;


/**
 * The Collection Tracker HUD card: the active collection's name and tier step, its <b>exact</b>
 * counter, a progress bar to the next tier with current/required, and the session gain (+rate/h).
 * Self-hiding: draws only while a collection is being tracked, and fades out after a while without
 * progress. Movable / scalable via the GUI editor like every {@link HudElement}.
 */
public final class CollectionTrackerHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int BAR_H = 5;
    private static final int MIN_W = 132;

    /** Hide the card after this long without a counter increase (once one had happened). */
    private static final long IDLE_HIDE_MS = 10 * 60_000L;

    private static final int GAIN_COLOR = 0xFF57D977;

    private CollectionTrackerHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().collectionTracker;
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.COLLECTION_TRACKER)) {
            return;
        }
        CollectionTracker.Snapshot snap = CollectionTracker.getInstance().snapshot();
        if (snap == null) {
            return;
        }
        // A pinned collection is shown always; only the auto-detected one self-hides after idle.
        boolean pinned = cfg.pinnedCollection != null && !cfg.pinnedCollection.isBlank();
        if (!pinned && snap.lastGainAt() > 0
                && System.currentTimeMillis() - snap.lastGainAt() > IDLE_HIDE_MS) {
            return; // farming stopped a while ago - stop occupying the screen
        }
        HudElement.Bounds bounds = HudElement.COLLECTION_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.COLLECTION_TRACKER);
        draw(g, snap, cfg.showRate, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, CollectionTracker.Snapshot snap,
                             boolean showRate, int x, int y) {
        if (!snap.anchored()) {
            drawCounting(g, snap, showRate, x, y);
            return;
        }
        Font font = Minecraft.getInstance().font;
        CollectionCatalog.Info info = snap.info();

        long current = snap.current();
        long next = info.nextTierAmount(current);
        int tier = info.tierOf(current);
        boolean maxed = tier >= info.maxTier() && current >= next;

        String title = info.name();
        String tierText = maxed ? "MAX" : roman(tier) + " → " + roman(tier + 1);
        String counter = format(current);
        String progress = maxed ? format(current) : format(current) + " / " + format(next);
        String gain = snap.sessionGain() > 0
                ? "+" + format(snap.sessionGain())
                + (showRate && snap.perHour() > 0 ? "  (" + format(Math.round(snap.perHour())) + "/h)" : "")
                : null;

        // Card width fits the widest line; height = title + counter + bar + progress (+ gain).
        int contentW = Math.max(font.width(title) + 10 + font.width(tierText),
                Math.max(font.width(counter), font.width(progress)));
        if (gain != null) {
            contentW = Math.max(contentW, font.width(gain));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * 2 + BAR_H + LINE_GAP + font.lineHeight
                + (gain != null ? lineH : 0);
        HudLayout.measure(HudElement.COLLECTION_TRACKER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;

        // Header: collection name left, tier step right.
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal(tierText), right - font.width(tierText), iy,
                maxed ? GAIN_COLOR : SBSTheme.ACCENT);
        iy += lineH;

        // The exact counter, big and alone on its line.
        g.text(font, Component.literal(counter), ix, iy, SBSTheme.TEXT);
        iy += lineH;

        // Progress bar to the next tier.
        int barW = width - PAD * 2;
        SciFiRender.roundedRect(g, ix, iy, barW, BAR_H, 2, SBSTheme.CARD_BG_DISABLED);
        double fraction = maxed || next <= 0 ? 1.0 : Math.min(1.0, current / (double) next);
        int fill = (int) Math.round(barW * fraction);
        if (fill > 0) {
            SciFiRender.roundedRect(g, ix, iy, fill, BAR_H, 2, maxed ? GAIN_COLOR : SBSTheme.ACCENT);
        }
        iy += BAR_H + LINE_GAP;

        // current / next-tier requirement.
        g.text(font, Component.literal(progress), ix, iy, SBSTheme.TEXT_MUTED);
        iy += font.lineHeight + LINE_GAP;

        if (gain != null) {
            g.text(font, Component.literal(gain), ix, iy, GAIN_COLOR);
        }
    }

    /**
     * Compact card shown while counting gains but the widget has not given an absolute value yet:
     * name + session gain, and a hint that the real total needs the Collection widget / menu.
     */
    private static void drawCounting(GuiGraphicsExtractor g, CollectionTracker.Snapshot snap,
                                     boolean showRate, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = snap.info().name();
        // No gain yet (e.g. a freshly pinned collection) → show only the load prompt, not "+0".
        String gain = snap.sessionGain() > 0
                ? "+" + format(snap.sessionGain())
                + (showRate && snap.perHour() > 0 ? "  (" + format(Math.round(snap.perHour())) + "/h)" : "")
                : null;
        String hint = "open the Collections menu to load the total";

        int gainW = gain != null ? font.width(gain) : 0;
        int width = Math.max(MIN_W, PAD * 2 + Math.max(font.width(title), Math.max(gainW, font.width(hint))));
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * (gain != null ? 2 : 1) + font.lineHeight;
        HudLayout.measure(HudElement.COLLECTION_TRACKER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        if (gain != null) {
            g.text(font, Component.literal(gain), ix, iy, GAIN_COLOR);
            iy += lineH;
        }
        g.text(font, Component.literal("§8" + hint), ix, iy, SBSTheme.TEXT_MUTED);
    }

    private static String format(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    /** Roman numeral for a tier (Hypixel shows collection tiers in roman). */
    private static String roman(int value) {
        if (value <= 0) {
            return "0";
        }
        String[] symbols = {"XX", "XIX", "XVIII", "XVII", "XVI", "XV", "XIV", "XIII", "XII", "XI",
                "X", "IX", "VIII", "VII", "VI", "V", "IV", "III", "II", "I"};
        int[] values = {20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        for (int i = 0; i < values.length; i++) {
            if (value >= values[i]) {
                return value == values[i] ? symbols[i] : String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }
}
