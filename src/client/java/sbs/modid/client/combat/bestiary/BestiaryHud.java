/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.core.render.DigitWidths;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The Bestiary Tracker HUD card: one row per pinned mob,
 * {@code "Zombie · Tier 12 · 1,234 / 1,500 (82%) · 8,766 to max"}, up to the configured number of
 * rows and "+N more" below. When a shown mob is not in the tab list's Bestiary widget its numbers are
 * from the last menu read, and the card says so with the time since. Movable / scalable via the GUI
 * editor; hidden while nothing is pinned.
 *
 * <p>The rows are built in the client tick, only when the tracker's revision, the pins, the row
 * limit or the minute changed ({@link #refresh}); the frame only draws the prepared model.
 */
public final class BestiaryHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 150;

    private static final int MAXED_COLOR = 0xFF57D977;
    private static final Component TITLE = Component.literal("Bestiary");

    /** One drawn line. */
    private record Line(Component text, int color) {
    }

    /** The prepared card; {@code null} lines = hidden. */
    private record Model(List<Line> lines, int width, int height) {
    }

    /** What the card is built from: a change in any of these rebuilds it. */
    private record Key(int trackerRevision, int pinsVersion, int maxRows, long minute) {
    }

    private static final Model EMPTY = new Model(List.of(), 0, 0);
    private static final CachedModel<Key, Model> MODEL = new CachedModel<>("bestiary.hud", BestiaryHud::build);

    /** The model the frame draws; swapped wholesale by the tick. */
    private static volatile Model current = EMPTY;

    private BestiaryHud() {
    }

    /** Client tick: rebuilds the card when one of its inputs changed. */
    static void refresh(int trackerRevision, int pinsVersion) {
        var cfg = ConfigManager.getInstance().get().bestiary;
        current = MODEL.get(new Key(trackerRevision, pinsVersion, Math.max(1, cfg.maxRows),
                System.currentTimeMillis() / 60_000L));
    }

    private static Model build(Key key) {
        BestiaryTracker tracker = BestiaryTracker.getInstance();
        List<SBSConfig.BestiaryPin> pins = tracker.pins();
        if (pins.isEmpty()) {
            return EMPTY;
        }
        Font font = Minecraft.getInstance().font;
        int shown = Math.min(key.maxRows(), pins.size());
        List<Line> lines = new ArrayList<>(shown + 3);
        lines.add(new Line(TITLE, SBSTheme.ACCENT));
        int contentW = font.width(TITLE);
        long oldestRead = Long.MAX_VALUE;
        boolean stale = false;
        for (int i = 0; i < shown; i++) {
            SBSConfig.BestiaryPin pin = pins.get(i);
            BestiaryProgress row = tracker.progress(pin);
            String text = row.text(NumberDisplay::format);
            int color = !row.loaded() ? SBSTheme.TEXT_MUTED : row.isMaxed() ? MAXED_COLOR : SBSTheme.TEXT;
            lines.add(new Line(Component.literal(text), color));
            contentW = Math.max(contentW, DigitWidths.width(font, text));
            if (!tracker.isLive(pin) && row.loaded()) {
                stale = true;
                long readAt = tracker.readAt(pin);
                if (readAt > 0) {
                    oldestRead = Math.min(oldestRead, readAt);
                }
            }
        }
        if (pins.size() > shown) {
            String more = "+" + (pins.size() - shown) + " more";
            lines.add(new Line(Component.literal(more), SBSTheme.TEXT_MUTED));
            contentW = Math.max(contentW, font.width(more));
        }
        if (stale) {
            String hint = "updated when you open the Bestiary"
                    + (oldestRead == Long.MAX_VALUE ? "" : " · read " + ago(System.currentTimeMillis() - oldestRead));
            lines.add(new Line(Component.literal(hint), SBSTheme.TEXT_MUTED));
            contentW = Math.max(contentW, font.width(hint));
        }
        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        return new Model(List.copyOf(lines), width, height);
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        Model model = current;
        if (model.lines().isEmpty() || !ConfigManager.getInstance().get().bestiary.enabled
                || Minecraft.getInstance().player == null || HudLayout.isHidden(HudElement.BESTIARY_TRACKER)) {
            return; // nothing pinned – the card stays hidden
        }
        HudElement.Bounds bounds = HudElement.BESTIARY_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.BESTIARY_TRACKER);
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        HudLayout.measure(HudElement.BESTIARY_TRACKER, x, y, model.width(), model.height());
        HudCard.draw(g, x, y, model.width(), model.height());
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int iy = y + PAD;
        List<Line> lines = model.lines();
        for (int i = 0, n = lines.size(); i < n; i++) {
            Line line = lines.get(i);
            g.text(font, line.text(), x + PAD, iy, line.color());
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** "just now", "4m ago", "2h ago", "3d ago". */
    static String ago(long ms) {
        long minutes = Math.max(0, ms) / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + "h ago" : hours / 24 + "d ago";
    }
}
