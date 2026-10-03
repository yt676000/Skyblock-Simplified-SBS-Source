/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.carry.logic.CarryCounter;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.CarryCounterSettings;
import sbs.modid.client.core.config.SBSConfig.CarryCounterSettings.Carry;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.List;

/**
 * The Slayer Carry Counter HUD panel: one row per tracked carry – {@code ▶ Player   Eman T4   12} –
 * in the shared SBS panel shell. Self-hiding: it draws only while at least one carry is tracked, so
 * it costs nothing when you are not carrying. Movable / scalable / hideable via the GUI editor like
 * every other {@link HudElement}.
 */
public final class CarryCounterOverlay {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MARK_W = 9;   // width reserved for the "▶" active marker
    private static final int COL_GAP = 8;  // gap between the name, boss label and count columns

    private static final int COUNT_COLOR = 0xFF57D977; // green, matches the chat announce

    private CarryCounterOverlay() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        CarryCounterSettings cfg = ConfigManager.getInstance().get().carryCounter;
        if (!cfg.enabled || !cfg.showOverlay || Minecraft.getInstance().player == null
                || cfg.carries.isEmpty() || HudLayout.isHidden(HudElement.CARRY_COUNTER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.CARRY_COUNTER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.CARRY_COUNTER);
        draw(g, cfg, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, CarryCounterSettings cfg, int x, int y) {
        Font font = Minecraft.getInstance().font;
        CarryCounter counter = CarryCounter.getInstance();
        List<Carry> carries = cfg.carries;

        String header = "Carry Counter";

        // Measure: the panel fits the widest row (name + boss label + count) and the header.
        int contentW = font.width(header);
        for (Carry c : carries) {
            int rowW = MARK_W + font.width(c.player) + COL_GAP + font.width(labelOf(c))
                    + COL_GAP + font.width(countText(c));
            contentW = Math.max(contentW, rowW);
        }
        int width = PAD * 2 + contentW;
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + font.lineHeight + LINE_GAP + carries.size() * lineH - LINE_GAP;
        HudLayout.measure(HudElement.CARRY_COUNTER, x, y, width, height);

        // Shared SBS panel shell: glow, border, gradient body.
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;

        // Header: title left, total count right.
        int total = 0;
        for (Carry c : carries) {
            total += c.count;
        }
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        String totalText = String.valueOf(total);
        g.text(font, Component.literal(totalText), right - font.width(totalText), iy, SBSTheme.TEXT_MUTED);

        int ly = iy + font.lineHeight + LINE_GAP;
        for (Carry c : carries) {
            boolean active = counter.isActive(c);

            // Active marker + player name.
            if (active) {
                g.text(font, Component.literal("▶"), ix, ly, SBSTheme.ACCENT);
            }
            g.text(font, Component.literal(c.player), ix + MARK_W, ly,
                    active ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);

            // Count hard right (green), boss label just left of it (muted).
            String count = countText(c);
            int countX = right - font.width(count);
            g.text(font, Component.literal(count), countX, ly, COUNT_COLOR);

            String label = labelOf(c);
            g.text(font, Component.literal(label), countX - COL_GAP - font.width(label), ly,
                    SBSTheme.TEXT_MUTED);

            ly += lineH;
        }
    }

    private static String labelOf(Carry carry) {
        return CarryCounter.describe(carry);
    }

    private static String countText(Carry carry) {
        return carry.goal > 0 ? carry.count + "/" + carry.goal : String.valueOf(carry.count);
    }
}
