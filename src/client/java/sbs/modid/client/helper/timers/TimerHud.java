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
import sbs.modid.client.core.render.CachedModel;
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
        // TEMPORARY measurement split: the card frame (HudCard.draw: glow, plate, material) is
        // "hud.timersChrome", everything else "hud.timers" - sequential sections, never nested.
        sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("hud.timers");
        try {
            renderMeasured(g);
        } finally {
            perf.close();   // the same section object, reopened or not
        }
    }

    private static void renderMeasured(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().timers;
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.EVENT_TIMERS)) {
            return;
        }
        Model model = MODEL.get(EventTimers.getInstance().modelKey());
        if (model.rows().length == 0) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int width = model.width();
        int height = PAD * 2 + lineH * (1 + model.rows().length) - 2;

        HudElement.Bounds b = HudElement.EVENT_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.EVENT_TIMERS, x, y, width, height);

        HudLayout.begin(g, HudElement.EVENT_TIMERS);
        sbs.modid.client.core.perf.Perf.hud("hud.timers").close();
        try (sbs.modid.client.core.perf.Perf.Section chrome = sbs.modid.client.core.perf.Perf.hud("hud.timersChrome")) {
            HudCard.draw(g, x, y, width, height);
        }
        sbs.modid.client.core.perf.Perf.hud("hud.timers");

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, HEADER_TEXT, ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (Row row : model.rows()) {
            g.text(font, row.label(), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, row.value(), right - row.valueWidth(), iy, row.color());
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static final String HEADER = "Events";
    private static final Component HEADER_TEXT = Component.literal(HEADER);

    /** One drawn row: components, colour and value width resolved at build. */
    private record Row(Component label, Component value, int valueWidth, int color) {
    }

    /** The card's rows and width; rebuilt only when {@link EventTimers.ModelKey} moves (at most per second). */
    private record Model(Row[] rows, int width) {
    }

    private static final CachedModel<EventTimers.ModelKey, Model> MODEL =
            new CachedModel<>("timerHud", key -> build());

    private static Model build() {
        List<TimerEntry> entries = EventTimers.getInstance().entries();
        Font font = Minecraft.getInstance().font;
        int contentW = font.width(HEADER);
        Row[] rows = new Row[entries.size()];
        for (int i = 0; i < rows.length; i++) {
            TimerEntry entry = entries.get(i);
            int valueW = font.width(entry.value());
            contentW = Math.max(contentW, font.width(entry.label()) + 12 + valueW);
            rows[i] = new Row(Component.literal(entry.label()), Component.literal(entry.value()), valueW,
                    colorFor(entry));
        }
        return new Model(rows, Math.max(110, contentW + PAD * 2));
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
