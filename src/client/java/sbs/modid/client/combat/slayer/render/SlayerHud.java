/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import sbs.modid.client.combat.carry.model.SlayerBoss;
import sbs.modid.client.combat.slayer.logic.RngMeterTracker;
import sbs.modid.client.combat.slayer.model.RngMeterState;
import sbs.modid.client.core.util.NumberDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Slayer session tracker card: boss counter (+fails), average kill time, estimated bosses/h,
 * session profit (+/h) and the drop list with per-item values. Self-hiding: draws only while the
 * session has recent slayer activity. Movable / scalable via the GUI editor.
 *
 * <p>The rows are built into a {@link HudCard.Snapshot} when the tracker or the RNG meter moves, and
 * at most once a second otherwise - that second is what picks up a Bazaar / LBIN price refresh. A
 * frame only draws the snapshot.
 */
public final class SlayerHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MAX_DROPS = 8;
    private static final int GAIN_COLOR = 0xFF57D977;

    /** What a snapshot was built for: both trackers' versions, the price clock, the RNG settings. */
    private record Version(int tracker, int meters, long second, boolean rngMeter, boolean dropValue) {
    }

    /** The card's draw model: the rows, plus where the RNG bar stands. */
    private record Model(HudCard.Snapshot card, double rngFraction) {
    }

    private static volatile Model model;

    private SlayerHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerTracker tracker = SlayerTracker.getInstance();
        if (!cfg.enabled || !cfg.showOverlay || Minecraft.getInstance().player == null
                || !tracker.sessionVisible() || HudLayout.isHidden(HudElement.SLAYER_TRACKER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.SLAYER_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.SLAYER_TRACKER);
        draw(g, model(tracker, cfg), (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static Model model(SlayerTracker tracker, SBSConfig.SlayerSettings cfg) {
        int trackerVersion = tracker.version();
        int meters = RngMeterTracker.getInstance().version();
        long second = System.currentTimeMillis() / 1000L;
        Model current = model;
        // Field by field against the built version, so an unchanged frame allocates no key.
        if (current == null || !(current.card().version() instanceof Version built)
                || built.tracker() != trackerVersion || built.meters() != meters || built.second() != second
                || built.rngMeter() != cfg.rngMeter || built.dropValue() != cfg.rngMeterDropValue) {
            current = build(tracker, new Version(trackerVersion, meters, second, cfg.rngMeter,
                    cfg.rngMeterDropValue));
            model = current;
        }
        return current;
    }

    private static Model build(SlayerTracker tracker, Version version) {
        Font font = Minecraft.getInstance().font;

        String title = tracker.questBoss() != null
                ? tracker.questBoss().shortLabel() + " Slayer" : "Slayer";
        List<HudCard.Line> lines = new ArrayList<>();
        lines.add(HudCard.Line.of(font, title, SBSTheme.ACCENT_BRIGHT));
        int contentW = font.width(title) + 10;

        List<HudCard.Line> stats = new ArrayList<>();
        stats.add(stat(font, "Bosses", tracker.bossesSlain()
                + (tracker.questsFailed() > 0 ? "  (" + tracker.questsFailed() + " failed)" : "")));
        if (tracker.avgKillSeconds() > 0) {
            stats.add(stat(font, "Avg kill", String.format(Locale.ROOT, "%.1fs", tracker.avgKillSeconds())));
        }
        if (tracker.bossesPerHour() > 0) {
            stats.add(stat(font, "Bosses/h", String.format(Locale.ROOT, "%.1f", tracker.bossesPerHour())));
        }
        double profit = tracker.profit();
        if (profit > 0) {
            String perHour = tracker.profitPerHour() > 0
                    ? "  (" + coins(tracker.profitPerHour()) + "/h)" : "";
            stats.add(stat(font, "Profit", coins(profit) + perHour));
        }

        // Drop list, most valuable first. Sorted here, on change, never per frame; the sort is
        // stable, so equal values keep insertion order exactly as the old stream did.
        List<Map.Entry<String, Integer>> drops = new ArrayList<>(tracker.drops().entrySet());
        drops.sort((a, b) -> Double.compare(
                tracker.price(b.getKey()) * b.getValue(),
                tracker.price(a.getKey()) * a.getValue()));
        List<HudCard.Line> dropLines = new ArrayList<>();
        for (int i = 0; i < drops.size() && i < MAX_DROPS; i++) {
            Map.Entry<String, Integer> entry = drops.get(i);
            dropLines.add(HudCard.Line.of(font, entry.getValue() + "x " + displayName(entry.getKey()),
                    SBSTheme.TEXT, coins(tracker.price(entry.getKey()) * entry.getValue()), GAIN_COLOR));
        }

        List<HudCard.Line> rngLines = rngRows(font, tracker, version.rngMeter(), version.dropValue());

        for (List<HudCard.Line> group : List.of(stats, dropLines, rngLines)) {
            for (HudCard.Line line : group) {
                if (line != BAR_ROW) {
                    contentW = Math.max(contentW, line.width(12));
                }
            }
            lines.addAll(group);
        }

        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(150, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        return new Model(new HudCard.Snapshot(List.copyOf(lines), width, height, version),
                rngFraction(tracker));
    }

    private static HudCard.Line stat(Font font, String label, String value) {
        return HudCard.Line.of(font, label, SBSTheme.TEXT_MUTED, value, SBSTheme.TEXT);
    }

    private static void draw(GuiGraphicsExtractor g, Model model, int x, int y) {
        Font font = Minecraft.getInstance().font;
        HudCard.Snapshot card = model.card();
        HudLayout.measure(HudElement.SLAYER_TRACKER, x, y, card.width(), card.height());

        HudCard.draw(g, x, y, card.width(), card.height());

        int lineH = font.lineHeight + LINE_GAP;
        int ix = x + PAD;
        int right = x + card.width() - PAD;
        int iy = y + PAD;
        for (HudCard.Line line : card.rows()) {
            if (line == BAR_ROW) {
                drawBar(g, ix, iy + 1, right - ix, font.lineHeight - 2, model.rngFraction());
            } else {
                HudCard.drawLine(g, font, line, ix, right, iy);
            }
            iy += lineH;
        }
    }

    // ------------------------------------------------------------------ RNG meter

    /** Marks the row the progress bar is drawn in; compared by identity. */
    private static final HudCard.Line BAR_ROW = new HudCard.Line(Component.empty(), 0, 0, null, 0, 0);

    /** The slayer whose meter the card shows: the running quest's, else the last one that moved. */
    private static SlayerBoss rngBoss(SlayerTracker tracker) {
        SlayerBoss quest = tracker.questBoss();
        return quest != null ? quest : RngMeterTracker.getInstance().mostRecent();
    }

    private static double rngFraction(SlayerTracker tracker) {
        RngMeterState state = RngMeterTracker.getInstance().state(rngBoss(tracker));
        return state == null ? -1 : state.fraction();
    }

    /**
     * The RNG meter rows: drop, bar, progress, gains, the estimate and the drop's value. Empty when
     * the feature is off or this slayer's meter has never been read.
     */
    private static List<HudCard.Line> rngRows(Font font, SlayerTracker tracker,
                                              boolean rngMeter, boolean dropValue) {
        List<HudCard.Line> out = new ArrayList<>();
        SlayerBoss boss = rngBoss(tracker);
        RngMeterTracker meters = RngMeterTracker.getInstance();
        RngMeterState state = rngMeter ? meters.state(boss) : null;
        if (state == null || state.current < 0) {
            return out;
        }
        String approx = state.approximate ? "~" : "";
        out.add(stat(font, "RNG Meter", state.selectedDrop != null ? state.selectedDrop : "no drop selected"));
        if (state.goal > 0) {
            out.add(BAR_ROW);
            out.add(stat(font, String.format(Locale.ROOT, "%s%.1f%%", approx, state.fraction() * 100),
                    approx + NumberDisplay.grouped(state.current) + " / " + coins(state.goal)));
        } else {
            out.add(stat(font, "Goal unknown", "open the Slayer menu"));
        }
        String last = state.lastGain > 0 ? "+" + NumberDisplay.grouped(state.lastGain) : "?";
        long sessionGain = meters.sessionGain(boss);
        out.add(stat(font, "Last boss / session", last + " / +" + NumberDisplay.grouped(sessionGain)));
        long left = state.bossesLeft(meters.averageGain(boss));
        if (left >= 0) {
            out.add(stat(font, "Bosses left (est.)", "~" + NumberDisplay.grouped(left)));
        }
        if (dropValue && state.selectedDrop != null) {
            String id = SlayerTracker.resolveItemId(state.selectedDrop);
            double value = id == null ? 0 : tracker.price(id);
            if (value > 0) {
                out.add(stat(font, "Drop value", coins(value)));
            }
        }
        return out;
    }

    private static void drawBar(GuiGraphicsExtractor g, int x, int y, int w, int h, double fraction) {
        g.fill(x, y, x + w, y + h, SBSTheme.CARD_BORDER);
        if (fraction > 0) {
            int filled = (int) Math.round((w - 2) * Math.min(1.0, fraction));
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, SBSTheme.ACCENT);
        }
    }

    private static String displayName(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        if (entry != null && entry.name != null && !entry.name.isEmpty()) {
            return entry.name;
        }
        String[] words = itemId.toLowerCase(Locale.ROOT).split("_");
        StringBuilder pretty = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (pretty.length() > 0) {
                    pretty.append(' ');
                }
                pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return pretty.toString();
    }

    /** 12_400_000 -> "12.4M", 830_000 -> "830K", 950 -> "950" (unless "Shorten Numbers" is off). */
    private static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
