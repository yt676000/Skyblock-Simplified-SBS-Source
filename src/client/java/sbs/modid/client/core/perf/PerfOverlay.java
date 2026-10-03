/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sbs perf}: the global KPIs and the 15 costliest SBS sections by p95 over the last 5 s,
 * red when over target. Dev mode only; movable in the HUD editor. Its text is rebuilt twice a
 * second, not per frame - the overlay must not be what it measures.
 */
public final class PerfOverlay {

    private static final int PAD = 5;
    private static final int TOP = 15;
    private static final long REBUILD_MS = 500L;
    private static final int RED = 0xFFFF5555;

    private static boolean visible;
    private static long builtAt;
    private static List<String[]> lines = List.of();
    private static List<Boolean> over = List.of();

    private PerfOverlay() {
    }

    public static boolean toggle() {
        visible = !visible;
        Perf.trackAllocations(visible);
        return visible;
    }

    public static void render(GuiGraphicsExtractor g) {
        if (!visible || !DevMode.ACTIVE || HudLayout.isHidden(HudElement.PERF_OVERLAY)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - builtAt >= REBUILD_MS) {
            builtAt = now;
            rebuild();
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 1;
        int width = 0;
        for (String[] l : lines) {
            width = Math.max(width, font.width(l[0]) + 10 + font.width(l[1]));
        }
        width += PAD * 2;
        int height = PAD * 2 + lineH * (lines.size() + 1);
        HudElement.Bounds b = HudElement.PERF_OVERLAY.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.PERF_OVERLAY, x, y, width, height);
        HudLayout.begin(g, HudElement.PERF_OVERLAY);
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal("SBS Perf (5 s)"), x + PAD, y + PAD, SBSTheme.ACCENT_BRIGHT);
        int iy = y + PAD + lineH;
        for (int i = 0; i < lines.size(); i++) {
            String[] l = lines.get(i);
            int colour = over.get(i) ? RED : SBSTheme.TEXT;
            g.text(font, Component.literal(l[0]), x + PAD, iy, i < globalsCount ? SBSTheme.TEXT_MUTED : colour);
            g.text(font, Component.literal(l[1]), x + width - PAD - font.width(l[1]), iy, colour);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static int globalsCount;

    private static void rebuild() {
        Perf.updateAllocWindow();
        List<String[]> out = new ArrayList<>(PerfReport.globals());
        List<Boolean> red = new ArrayList<>();
        out.forEach(l -> red.add(false));
        globalsCount = out.size();
        List<Perf.Row> rows = Perf.rows();
        for (int i = 0; i < rows.size() && i < TOP; i++) {
            Perf.Row row = rows.get(i);
            out.add(new String[] {row.name(), String.format(Locale.ROOT, "%.3f avg  %.3f p95  %.0f/s%s",
                    row.last5().avgMs(), row.last5().p95Ms(), row.last5().callsPerSec(),
                    row.allocMbPerSec() > 0.05 ? String.format(Locale.ROOT, "  %.1f MB/s", row.allocMbPerSec()) : "")});
            red.add(PerfReport.overTarget(row));
        }
        lines = out;
        over = red;
    }
}
