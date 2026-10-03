/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.FishingData.TrophyFish;
import sbs.modid.client.skills.trophyfish.logic.TrophyFishSession;
import sbs.modid.client.skills.trophyfish.logic.TrophyFishStore;
import sbs.modid.client.skills.trophyfish.logic.TrophyFishTracker;
import sbs.modid.client.skills.trophyfish.logic.TrophyMenuParser;
import sbs.modid.client.skills.trophyfish.model.TrophyTier;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The two Trophy Fish cards: the fish x tier grid ({@link HudElement#TROPHY_FISH}) and this
 * session's catches ({@link HudElement#TROPHY_SESSION}).
 *
 * <p>Grid cells: a number is a count, a tick is "caught, count not shown by the menu", a dim dash is
 * missing. The header always says how old the last menu sync is, because between visits every
 * number is the sync plus what chat counted - and "never synced" is said outright, not implied by a
 * wall of dashes.
 */
public final class TrophyFishHud {

    private static final int PAD = 4;
    private static final int ROW = 10;
    private static final int COL = 22;
    private static final int MISSING = 0xFF4A5566;

    private TrophyFishHud() {
    }

    private static SBSConfig.TrophyFishSettings cfg() {
        return ConfigManager.getInstance().get().trophyFish;
    }

    private static boolean shown(SBSConfig.TrophyFishSettings cfg) {
        return cfg.enabled && Minecraft.getInstance().player != null
                && (!cfg.onlyCrimsonIsle || SkyBlockLocation.onIsland("Crimson Isle"));
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.TrophyFishSettings cfg = cfg();
        if (!shown(cfg)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        if (cfg.gridHud && !HudLayout.isHidden(HudElement.TROPHY_FISH)) {
            if (cfg.compact) {
                renderCompact(g, font);
            } else {
                renderGrid(g, font);
            }
        }
        if (cfg.sessionHud && !HudLayout.isHidden(HudElement.TROPHY_SESSION)) {
            renderSession(g, font);
        }
    }

    static String syncLine(long syncedAt, long now) {
        if (syncedAt <= 0) {
            return "never synced - open Odger's menu";
        }
        long minutes = Math.max(0, (now - syncedAt) / 60_000L);
        if (minutes < 1) {
            return "synced just now";
        }
        if (minutes < 60) {
            return "synced " + minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours < 24 ? "synced " + hours + "h ago" : "synced " + (hours / 24) + "d ago";
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SBSTheme.HUD_CARD_BG);
        g.outline(x, y, w, h, SBSTheme.HUD_CARD_BORDER);
    }

    private static void renderGrid(GuiGraphicsExtractor g, Font font) {
        TrophyFishStore store = TrophyFishStore.getInstance();
        List<TrophyFish> fish = FishingData.TROPHY_FISH_LIST;
        TrophyTier[] tiers = TrophyTier.values();
        int nameW = 0;
        for (TrophyFish f : fish) {
            nameW = Math.max(nameW, font.width(f.name()));
        }
        String sync = syncLine(store.syncedAt(), System.currentTimeMillis());
        int w = Math.max(PAD * 2 + nameW + 6 + COL * tiers.length, PAD * 2 + font.width(sync));
        int h = PAD * 2 + ROW * (fish.size() + 3) + 2;

        HudElement.Bounds b = HudElement.TROPHY_FISH.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.TROPHY_FISH, x, y, w, h);
        HudLayout.begin(g, HudElement.TROPHY_FISH);
        panel(g, x, y, w, h);
        int cy = y + PAD;
        g.text(font, "Trophy Fish", x + PAD, cy, SBSTheme.ACCENT, true);
        cy += ROW;
        g.text(font, sync, x + PAD, cy, SBSTheme.TEXT_MUTED, false);
        cy += ROW;
        int colX = x + PAD + nameW + 6;
        for (TrophyTier t : tiers) {
            g.text(font, t.letter(), colX + COL * t.ordinal() + COL - 4 - font.width(t.letter()), cy,
                    t.color(), true);
        }
        cy += ROW;
        int[] caughtPerTier = new int[tiers.length];
        for (TrophyFish f : fish) {
            g.text(font, f.name(), x + PAD, cy, SBSTheme.TEXT, false);
            for (TrophyTier t : tiers) {
                int n = store.count(f.apiKey(), t);
                String cell = n == TrophyMenuParser.CAUGHT_UNKNOWN_COUNT ? "✔" : n > 0 ? compact(n) : "-";
                if (n != 0) {
                    caughtPerTier[t.ordinal()]++;
                }
                g.text(font, cell, colX + COL * t.ordinal() + COL - 4 - font.width(cell), cy,
                        n != 0 ? t.color() : MISSING, false);
            }
            cy += ROW;
        }
        cy += 2;
        g.fill(x + PAD, cy - 2, x + w - PAD, cy - 1, SBSTheme.HUD_CARD_BORDER);
        g.text(font, "Caught", x + PAD, cy, SBSTheme.TEXT_MUTED, false);
        for (TrophyTier t : tiers) {
            String cell = caughtPerTier[t.ordinal()] + "/" + fish.size();
            g.text(font, cell, colX + COL * t.ordinal() + COL - 4 - font.width(cell), cy, t.color(), false);
        }
        HudLayout.end(g);
    }

    private static void renderCompact(GuiGraphicsExtractor g, Font font) {
        TrophyFishStore store = TrophyFishStore.getInstance();
        List<String> lines = new ArrayList<>();
        for (TrophyFish f : FishingData.TROPHY_FISH_LIST) {
            StringBuilder missing = new StringBuilder();
            for (TrophyTier t : TrophyTier.values()) {
                if (store.count(f.apiKey(), t) == 0) {
                    missing.append(missing.isEmpty() ? "" : " ").append(t.letter());
                }
            }
            if (!missing.isEmpty()) {
                lines.add(f.name() + ": " + missing);
            }
        }
        String sync = syncLine(store.syncedAt(), System.currentTimeMillis());
        String title = lines.isEmpty() ? "Trophy Fish: all caught" : "Trophy Fish missing";
        int w = PAD * 2 + Math.max(font.width(title), font.width(sync));
        for (String line : lines) {
            w = Math.max(w, PAD * 2 + font.width(line));
        }
        int h = PAD * 2 + ROW * (lines.size() + 2);
        HudElement.Bounds b = HudElement.TROPHY_FISH.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.TROPHY_FISH, x, y, w, h);
        HudLayout.begin(g, HudElement.TROPHY_FISH);
        panel(g, x, y, w, h);
        int cy = y + PAD;
        g.text(font, title, x + PAD, cy, SBSTheme.ACCENT, true);
        cy += ROW;
        g.text(font, sync, x + PAD, cy, SBSTheme.TEXT_MUTED, false);
        cy += ROW;
        for (String line : lines) {
            g.text(font, line, x + PAD, cy, SBSTheme.TEXT, false);
            cy += ROW;
        }
        HudLayout.end(g);
    }

    private static void renderSession(GuiGraphicsExtractor g, Font font) {
        TrophyFishSession session = TrophyFishTracker.getInstance().session();
        List<String> lines = new ArrayList<>();
        lines.add("Caught: " + session.total());
        lines.add("Fishing: " + duration(session.activeMs()));
        double rate = session.perHour();
        lines.add("Rate: " + (rate > 0 ? Math.round(rate) + "/h" : "-"));
        for (TrophyFish f : FishingData.TROPHY_FISH_LIST) {
            StringBuilder row = new StringBuilder();
            for (TrophyTier t : TrophyTier.values()) {
                int n = session.count(f.apiKey(), t);
                if (n > 0) {
                    row.append(row.isEmpty() ? "" : " ").append(n).append(t.letter());
                }
            }
            if (!row.isEmpty()) {
                lines.add(f.name() + " " + row);
            }
        }
        if (!ConfigManager.getInstance().get().trophyFish.chatCounting) {
            lines.add("Chat counting is off");
        }
        String title = "Trophy Session";
        int w = PAD * 2 + font.width(title);
        for (String line : lines) {
            w = Math.max(w, PAD * 2 + font.width(line));
        }
        int h = PAD * 2 + ROW * (lines.size() + 1);
        HudElement.Bounds b = HudElement.TROPHY_SESSION.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.TROPHY_SESSION, x, y, w, h);
        HudLayout.begin(g, HudElement.TROPHY_SESSION);
        panel(g, x, y, w, h);
        int cy = y + PAD;
        g.text(font, title, x + PAD, cy, SBSTheme.ACCENT, true);
        cy += ROW;
        for (String line : lines) {
            g.text(font, line, x + PAD, cy, SBSTheme.TEXT, false);
            cy += ROW;
        }
        HudLayout.end(g);
    }

    private static String compact(int n) {
        return n >= 10_000 ? (n / 1000) + "k" : String.valueOf(n);
    }

    static String duration(long ms) {
        long minutes = ms / 60_000L;
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
