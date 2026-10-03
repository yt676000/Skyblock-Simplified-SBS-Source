/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Rift category: motes, the quest chain's progress per area, and the Enigma souls.
 *
 * <p>The Rift is a completionist dimension, so its pages are framed as "how much of it is done"
 * rather than as raw counters.
 */
public final class RiftPage implements PvPage {

    public enum Mode { OVERVIEW, PROGRESS, SOULS }

    /** How many Enigma souls exist in the Rift. */
    private static final int ENIGMA_SOULS_TOTAL = 42;
    private static final int ROW_H = 13;

    private final Mode mode;

    public RiftPage(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        JsonObject rift = PvDraw.obj(ctx.profile, "rift");
        if (rift == null) {
            PvDraw.empty(g, ctx, "No Rift data - this profile never entered the Rift.");
            return;
        }
        switch (mode) {
            case PROGRESS -> drawProgress(g, ctx, rift);
            case SOULS -> drawSouls(g, ctx, rift);
            default -> drawOverview(g, ctx, rift);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g, PvContext ctx, JsonObject rift) {
        long souls = PvDraw.num(rift, "enigma_souls");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Motes", "§d" + PvDraw.fmt(PvDraw.num(rift, "motes")), "purse"},
                {"Lifetime", "§d" + PvDraw.fmt(PvDraw.num(rift, "motes_lifetime"))},
                {"Enigma Souls", "§b" + souls + "§8/" + ENIGMA_SOULS_TOTAL},
                {"Visits", "§b" + PvDraw.num(rift, "visits")}});

        int half = (ctx.width - 10) / 2;
        int ly = PvDraw.heading(g, ctx.font, ctx.x, y + 5, half, "Progress");
        ly = drawProgressRows(g, ctx, rift, ctx.x, ly, half);
        if (ly + ctx.font.lineHeight * 2 <= ctx.bottom()) {
            ly = PvDraw.heading(g, ctx.font, ctx.x, ly + 4, half, "Unlocks");
            boolean cloak = rift.has("enigma_cloak") && rift.get("enigma_cloak").getAsBoolean();
            ly = PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Enigma Cloak",
                    cloak ? "§ayes" : "§8no");
            boolean cat = rift.has("montezuma");
            PvDraw.keyValue(g, ctx.font, ctx.x, ly, half, "Montezuma", cat ? "§ayes" : "§8no");
        }

        // Every Rift counter, biggest first – the column scrolls rather than silently ending.
        JsonArray counters = PvDraw.arr(rift, "counters");
        int rx = ctx.x + half + 10;
        int rw = ctx.right() - rx;
        int ry = PvDraw.heading(g, ctx.font, rx, y + 5, rw, "Counters §8" + counters.size());
        int visible = Math.max(1, (ctx.bottom() - ry) / (ctx.font.lineHeight + 2));
        int scroll = ctx.rows(counters.size(), visible);
        for (int i = scroll; i < counters.size() && i < scroll + visible; i++) {
            var pair = counters.get(i).getAsJsonArray();
            ry = PvDraw.keyValue(g, ctx.font, rx, ry, rw,
                    PvDraw.pretty(pair.get(0).getAsString()),
                    "§f" + PvDraw.fmt(pair.get(1).getAsLong()));
        }
        if (counters.size() > visible) {
            PvDraw.scrollbar(g, ctx.right() - 3, y + 5 + ctx.font.lineHeight + 5,
                    visible * (ctx.font.lineHeight + 2), counters.size(), visible, scroll);
        }
    }

    /** Each area's quest chain as a done/total bar. */
    private int drawProgressRows(GuiGraphicsExtractor g, PvContext ctx, JsonObject rift,
                                 int x, int y, int w) {
        JsonObject progress = PvDraw.obj(rift, "progress");
        if (progress == null || progress.isEmpty()) {
            return PvDraw.line(g, ctx.font, x, y, "§8No progress data");
        }
        for (var entry : progress.entrySet()) {
            if (y + ctx.font.lineHeight + 6 > ctx.bottom()) {
                break;
            }
            JsonArray pair = entry.getValue().getAsJsonArray();
            long done = pair.get(0).getAsLong();
            long total = Math.max(1, pair.get(1).getAsLong());
            boolean complete = done >= total;
            y = PvDraw.keyValue(g, ctx.font, x, y, w, entry.getKey(),
                    (complete ? "§a" : "§f") + done + "§8/" + total);
            PvDraw.bar(g, x, y, w, done / (double) total,
                    complete ? SBSTheme.TOGGLE_ON : SBSTheme.ACCENT);
            y += 6;
        }
        return y;
    }

    private void drawProgress(GuiGraphicsExtractor g, PvContext ctx, JsonObject rift) {
        int y = PvDraw.heading(g, ctx.font, ctx.x, ctx.y, ctx.width, "Quest Chains");
        y = drawProgressRows(g, ctx, rift, ctx.x, y, ctx.width);
        y = PvDraw.heading(g, ctx.font, ctx.x, y + 4, ctx.width, "Visits");
        y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, "Rift Visits",
                "§f" + PvDraw.num(rift, "visits"));
        y = PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, "Passes Consumed",
                "§f" + PvDraw.num(rift, "pass_consumed"));
        PvDraw.keyValue(g, ctx.font, ctx.x, y, ctx.width, "Motes Earned (lifetime)",
                "§d" + PvDraw.fmt(PvDraw.num(rift, "motes_lifetime")));
    }

    /** The souls the player found, and the count still out there. */
    private void drawSouls(GuiGraphicsExtractor g, PvContext ctx, JsonObject rift) {
        long found = PvDraw.num(rift, "enigma_souls");
        int y = PvDraw.cardRow(g, ctx.font, ctx.x, ctx.y, ctx.width, 34, new String[][]{
                {"Found", "§b" + found + "§8/" + ENIGMA_SOULS_TOTAL},
                {"Remaining", "§b" + Math.max(0, ENIGMA_SOULS_TOTAL - found)},
                {"Cloak", rift.has("enigma_cloak") && rift.get("enigma_cloak").getAsBoolean()
                        ? "§abought" : "§8no"}});
        PvDraw.bar(g, ctx.x, y + 4, ctx.width, found / (double) ENIGMA_SOULS_TOTAL, SBSTheme.ACCENT);
        y += 10;

        JsonArray souls = PvDraw.arr(rift, "enigma_souls_list");
        if (souls.isEmpty()) {
            PvDraw.empty(g, ctx, "No souls found yet.");
            return;
        }
        y = PvDraw.heading(g, ctx.font, ctx.x, y, ctx.width, "Souls Found");
        // Two or three name columns, so 41 souls fit without scrolling on a full-size panel.
        int cols = Math.max(1, ctx.width / 120);
        int colW = ctx.width / cols;
        int rowsPerCol = Math.max(1, (ctx.bottom() - y) / ROW_H);
        int visible = cols * rowsPerCol;
        int scroll = ctx.rows((souls.size() + cols - 1) / cols, rowsPerCol);
        int start = scroll * cols;
        for (int i = start; i < souls.size() && i < start + visible; i++) {
            int idx = i - start;
            int cx = ctx.x + (idx % cols) * colW;
            int cy = y + (idx / cols) * ROW_H;
            g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font,
                            "§a✔ §7" + PvDraw.pretty(souls.get(i).getAsString()), colW - 6)),
                    cx, cy, SBSTheme.TEXT);
        }
    }
}
