/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The event-timer card: one row per enabled timer, sized to its content.
 *
 * <p>Rows carry their {@link TimerEntry.Certainty}, and the card shows it - a learned prediction is
 * dimmer than a measured one and an unknown phase says "learning..." rather than a number. That is
 * the whole point of the feature: a timer you can trust, and a visible difference where you cannot.
 */
public final class TimerHud {

    private static final int PAD = 5;
    /** Below this many seconds a countdown turns urgent. */
    private static final int URGENT_SECONDS = 60;

    private TimerHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().timers;
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.EVENT_TIMERS)) {
            return;
        }
        List<TimerEntry> rows = EventTimers.getInstance().entries();
        if (rows.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        String header = "Events";
        int contentW = font.width(header);
        for (TimerEntry row : rows) {
            contentW = Math.max(contentW, font.width(row.label()) + 12 + font.width(row.value()));
        }
        int width = Math.max(110, contentW + PAD * 2);
        int height = PAD * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.EVENT_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.EVENT_TIMERS, x, y, width, height);

        HudLayout.begin(g, HudElement.EVENT_TIMERS);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (TimerEntry row : rows) {
            g.text(font, Component.literal(row.label()), ix, iy, SBSTheme.TEXT_MUTED);
            String value = row.value();
            g.text(font, Component.literal(value), right - font.width(value), iy, colorFor(row));
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** Urgent red when a countdown is nearly up, dimmed when the value is a prediction. */
    private static int colorFor(TimerEntry row) {
        if (row.color() != 0) {
            return row.color();
        }
        return switch (row.certainty()) {
            case MEASURED -> SBSTheme.TEXT;
            case DERIVED -> isUrgent(row.value()) ? 0xFFFF6060 : SBSTheme.TEXT;
            case LEARNED -> isUrgent(row.value()) ? 0xFFFF6060 : 0xFFBFD4E0;
            case UNKNOWN -> SBSTheme.TEXT_MUTED;
        };
    }

    /** A value is urgent once it is down to seconds only ("45s") or under a minute of them. */
    private static boolean isUrgent(String value) {
        if (value == null || value.isEmpty() || !value.endsWith("s") || value.contains("h")) {
            return false;
        }
        int m = value.indexOf('m');
        if (m < 0) {
            try {
                return Integer.parseInt(value.substring(0, value.length() - 1)) <= URGENT_SECONDS;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
