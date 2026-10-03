/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.spiritbear.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.spiritbear.logic.SpiritBearTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spirit Bear card: how full the arena's lantern ring is, the countdown once it completes, and
 * the bear's own state. Only ever on screen inside the F4/M4 boss room - off that floor there is no
 * ring to read and no bear to wait for.
 */
public final class SpiritBearHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 128;
    private static final int BEAR_COLOR = 0xFFFFC020;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private SpiritBearHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().spiritBear;
        if (!cfg.enabled || !cfg.showHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.SPIRIT_BEAR)) {
            return;
        }
        SpiritBearTracker tracker = SpiritBearTracker.getInstance();
        if (!tracker.inArena()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.SPIRIT_BEAR.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.SPIRIT_BEAR);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, SpiritBearTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String> lines = new ArrayList<>();
        lines.add("Spirit Bear");
        // The ring is the kill counter, so its own numbers are the progress readout - no floor table
        // needed to say "12 of 25", the ring says how many slots it has.
        int slots = tracker.ringSlots();
        lines.add(slots == 0
                ? "§7Ring §8searching…"
                : "§7Ring §f" + tracker.lit() + "§7/§f" + slots);
        lines.add(stateLine(tracker));

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.SPIRIT_BEAR, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? SBSTheme.ACCENT_BRIGHT
                    : (i == lines.size() - 1 && tracker.bear() != null ? BEAR_COLOR : SBSTheme.TEXT);
            g.text(font, Component.literal(lines.get(i)), ix, iy, color);
            iy += lineH;
        }
    }

    /**
     * The bear's state line. A measured spawn gap is stated plainly, the configured estimate carries
     * a {@code ~} - the difference is the point, so a guess is never mistaken for a reading.
     *
     * <p>A bow on the floor outranks all of it: while it is lying there it is the only thing anyone
     * in the arena should be doing, so it takes the line over whatever the bear was saying.
     */
    private static String stateLine(SpiritBearTracker tracker) {
        if (tracker.bow() != null) {
            return "§dBOW DOWN §7- grab it";
        }
        if (tracker.bear() != null) {
            return "§6UP" + (tracker.measuredDelayMs() > 0
                    ? " §8after " + SpiritBearTracker.seconds(tracker.measuredDelayMs()) : "");
        }
        int eta = tracker.spawnEtaSeconds();
        if (eta >= 0) {
            return "§7Spawn §f" + (tracker.delayMeasured() ? "" : "~") + eta + "s";
        }
        if (tracker.ringFull()) {
            return "§7Spawn §8due";
        }
        return "§8kill spirits to fill the ring";
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
