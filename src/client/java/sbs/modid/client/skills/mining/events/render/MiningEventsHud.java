/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningEventSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.mining.events.logic.LiveEventState;
import sbs.modid.client.skills.mining.events.logic.MiningEventHistory;
import sbs.modid.client.skills.mining.events.logic.MiningEventTracker;
import sbs.modid.client.skills.mining.events.model.EventObservation;
import sbs.modid.client.skills.mining.events.model.MiningEvent;
import sbs.modid.client.skills.mining.events.model.NextEvent;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Mining Events card: the running event and its countdown, the next start with its confidence
 * label, the latest Powder Ghast, and the other mining island's latest event. Dwarven Mines and
 * Crystal Hollows only. The text is rebuilt four times a second; only the draw is per frame.
 */
public final class MiningEventsHud {

    private static final int PAD = 5;
    private static final long REBUILD_MS = 250L;

    private static List<String> lines = List.of();
    private static long builtAt;

    private MiningEventsHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        MiningEventSettings cfg = ConfigManager.getInstance().get().miningEvents;
        if (!cfg.enabled || !cfg.hud || HudLayout.isHidden(HudElement.MINING_EVENTS)) {
            return;
        }
        String island = SkyBlockLocation.island();
        if (!MiningEventTracker.miningIsland(island)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - builtAt >= REBUILD_MS) {
            builtAt = now;
            lines = build(cfg, island, now);
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        width += PAD * 2;
        int height = PAD * 2 + lineH * lines.size() - 2;
        HudElement.Bounds b = HudElement.MINING_EVENTS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.MINING_EVENTS, x, y, width, height);
        HudLayout.begin(g, HudElement.MINING_EVENTS);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        for (String line : lines) {
            g.text(font, Component.literal(line), x + PAD, iy, SBSTheme.ACCENT_BRIGHT);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** The card's lines. Never empty: with nothing known it still says so. */
    static List<String> build(MiningEventSettings cfg, String island, long now) {
        MiningEventTracker tracker = MiningEventTracker.getInstance();
        LiveEventState live = tracker.live();
        MiningEventHistory history = tracker.history();
        List<String> out = new ArrayList<>(4);

        MiningEvent running = live.event();
        if (running != null) {
            String name = MiningEvent.label(running, live.rawName());
            long duration = history.estimatedDurationMs(running);
            long remaining = live.remainingMs(now, duration);
            out.add(switch (live.remainingSource(duration)) {
                case SCOREBOARD -> "§b" + name + "  §f" + clock(remaining) + " left";
                case ESTIMATED -> "§b" + name + "  §f" + (running.endsEarly() ? "up to ~" : "~")
                        + clock(remaining) + " left §8(est.)";
                case NONE -> "§b" + name + "  §7running"
                        + (live.startExact() ? " " + clock(now - live.startedAt()) : "");
            });
        } else {
            out.add("§7No mining event running");
        }

        NextEvent next = tracker.nextEvent(now);
        out.add(nextLine(next, now));

        if (live.ghastAt() > 0 && now - live.ghastAt() < MiningEventTracker.GHAST_SHOWN_MS) {
            out.add("§dPowder Ghast" + (live.ghastZone() == null ? "" : " near " + live.ghastZone())
                    + "  §7" + clock(now - live.ghastAt()) + " ago");
        }

        if (cfg.otherIsland) {
            String other = MiningEventTracker.DWARVEN_MINES.equalsIgnoreCase(island)
                    ? MiningEventTracker.CRYSTAL_HOLLOWS : MiningEventTracker.DWARVEN_MINES;
            EventObservation seen = history.latestOn(other);
            if (seen != null) {
                out.add("§7" + other + ": last seen " + MiningEvent.label(seen.eventType(), seen.rawName())
                        + " " + ago(now - seen.lastSeen()));
            }
        }
        return out;
    }

    private static String nextLine(NextEvent next, long now) {
        String label = next.confidence().colorCode() + next.confidence().label();
        return switch (next.confidence()) {
            case KNOWN -> "§fNext: " + MiningEvent.label(next.event(), next.rawName()) + " in "
                    + clock(Math.max(0L, next.earliest() - now)) + "  " + label;
            case ESTIMATED, SHARED -> {
                if (now > next.latest()) {
                    yield "§fNext: due  " + label + " §8(" + next.samples() + ")";
                }
                long from = Math.max(0L, next.earliest() - now);
                long to = Math.max(0L, next.latest() - now);
                yield "§fNext: " + minutes(from) + "-" + minutes(to) + "m  " + label + " §8(" + next.samples() + ")";
            }
            case UNKNOWN -> "§fNext: unknown  " + label
                    + (next.samples() < MiningEventHistory.MIN_INTERVALS
                    ? " §8(" + next.samples() + "/" + MiningEventHistory.MIN_INTERVALS + ")" : "");
        };
    }

    /** {@code m:ss}. */
    static String clock(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Whole minutes, rounded to the nearest - the window is p25-p75, not a precise time. */
    private static long minutes(long ms) {
        return Math.round(ms / 60_000.0);
    }

    private static String ago(long ms) {
        long minutes = Math.max(0L, ms) / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + "h ago" : (hours / 24) + "d ago";
    }
}
