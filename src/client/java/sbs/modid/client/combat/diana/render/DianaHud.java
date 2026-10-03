/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.diana.logic.ChainTracker;
import sbs.modid.client.combat.diana.logic.DianaEvent;
import sbs.modid.client.combat.diana.logic.DianaTracker;
import sbs.modid.client.combat.diana.logic.MythMobTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The two Diana cards: how the event is going, and what is about to kill you.
 *
 * <h2>Self-hiding, both of them</h2>
 *
 * <p>Neither card draws a frame with nothing in it. No chains and no totals means no tracker card;
 * no rare creature in sight means no health card. A card that is present and empty is a card the
 * player moves out of the way and then never sees when it matters.
 *
 * <p>They also draw nothing outside the Hub and nothing while the ritual is not running, which is
 * most of the time for most players - the event is a fortnight in eight.
 */
public final class DianaHud {

    private static final int PADDING = 5;
    private static final int MIN_WIDTH = 110;

    /** A chain nearer than this to expiring is worth colouring. */
    private static final long CHAIN_WARN_MS = 5 * 60 * 1000L;

    private static final int ACCENT = 0xFFFFD65A;
    private static final int WARN = 0xFFFF8A6B;
    private static final int OK = 0xFF9BE37F;

    private DianaHud() {
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** Drawn from the HUD pass. Both cards, each gated on its own switch. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !DianaEvent.awake() || !SkyBlockLocation.onIsland("Hub")) {
            return;
        }
        renderTracker(g, cfg);
        renderCreatures(g, cfg);
    }

    // ------------------------------------------------------------------
    // Chains and session totals
    // ------------------------------------------------------------------

    private static void renderTracker(GuiGraphicsExtractor g, SBSConfig.DianaSettings cfg) {
        if (HudLayout.isHidden(HudElement.DIANA_TRACKER)) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();

        if (cfg.chainsHud) {
            ChainTracker chains = ChainTracker.getInstance();
            int running = chains.count();
            if (running > 0) {
                long remaining = chains.oldestRemainingMs();
                rows.add(new String[] {"Chains", String.valueOf(running)});
                colours.add(remaining < CHAIN_WARN_MS ? WARN : OK);
                rows.add(new String[] {"Oldest", clock(remaining)});
                colours.add(SBSTheme.TEXT_MUTED);
            }
        }
        if (cfg.sessionHud && cfg.tracker) {
            DianaTracker tracker = DianaTracker.getInstance();
            rows.add(new String[] {"Burrows", String.valueOf(tracker.burrows())});
            colours.add(SBSTheme.TEXT_MUTED);
            rows.add(new String[] {"Creatures", String.valueOf(tracker.creatures())});
            colours.add(SBSTheme.TEXT_MUTED);
            rows.add(new String[] {"Treasures", String.valueOf(tracker.treasures())});
            colours.add(SBSTheme.TEXT_MUTED);
            if (tracker.creatures() > 0) {
                rows.add(new String[] {"Since inq", String.valueOf(tracker.mobsSinceInquisitor())});
                colours.add(ACCENT);
            }
            for (Map.Entry<String, Long> entry : tracker.creatureCounts().entrySet()) {
                if (rows.size() >= 14) {
                    break;
                }
                rows.add(new String[] {entry.getKey(), String.valueOf(entry.getValue())});
                colours.add(SBSTheme.TEXT_MUTED);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        draw(g, HudElement.DIANA_TRACKER, "Diana", rows, colours);
    }

    // ------------------------------------------------------------------
    // Creature health
    // ------------------------------------------------------------------

    private static void renderCreatures(GuiGraphicsExtractor g, SBSConfig.DianaSettings cfg) {
        if (!cfg.creatureHealthHud || HudLayout.isHidden(HudElement.DIANA_CREATURES)) {
            return;
        }
        List<MythMobTracker.Sighting> sightings = MythMobTracker.getInstance().sightings();
        List<String[]> rows = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        for (MythMobTracker.Sighting sighting : sightings) {
            if (sighting.shared()) {
                // Somebody else's sighting has no health to report - only a place. It gets a marker,
                // not a row, because a health card listing a blank is a card that looks broken.
                continue;
            }
            String right;
            if (sighting.hits() != null) {
                right = sighting.hits() + " hits";
            } else if (sighting.health() >= 0) {
                right = MythMobTracker.format(sighting.health());
            } else {
                right = "?";
            }
            rows.add(new String[] {sighting.label(), right});
            colours.add(sighting.creature().color());
            if (cfg.shurikenWarning && !sighting.starred()) {
                rows.add(new String[] {"  no shuriken", ""});
                colours.add(WARN);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        draw(g, HudElement.DIANA_CREATURES, "Mythological", rows, colours);
    }

    // ------------------------------------------------------------------
    // Shared drawing
    // ------------------------------------------------------------------

    /**
     * A titled card of label/value rows, measured from its contents.
     *
     * <p>The element's default bounds are only the anchor the editor moves and scales about;
     * {@code HudLayout.begin}/{@code end} is what makes the card movable, scalable and themed like
     * everything else on screen.
     */
    private static void draw(GuiGraphicsExtractor g, HudElement element, String title,
                             List<String[]> rows, List<Integer> colours) {
        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight + 2;
        int contentWidth = font.width(title);
        for (String[] row : rows) {
            contentWidth = Math.max(contentWidth, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(MIN_WIDTH, contentWidth + PADDING * 2);
        int height = PADDING * 2 + lineHeight * (1 + rows.size()) - 2;

        HudElement.Bounds bounds = element.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(bounds.x());
        int y = Math.round(bounds.y());
        HudLayout.measure(element, x, y, width, height);

        HudLayout.begin(g, element);
        HudCard.draw(g, x, y, width, height);

        int left = x + PADDING;
        int right = x + width - PADDING;
        int cursor = y + PADDING;
        g.text(font, Component.literal(title), left, cursor, SBSTheme.ACCENT_BRIGHT);
        cursor += lineHeight;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            g.text(font, Component.literal(row[0]), left, cursor, SBSTheme.TEXT_MUTED);
            if (!row[1].isEmpty()) {
                g.text(font, Component.literal(row[1]),
                        right - font.width(row[1]), cursor, colours.get(i));
            }
            cursor += lineHeight;
        }
        HudLayout.end(g);
    }

    /** "12:04" - minutes and seconds, which is how a half-hour chain timer is read. */
    private static String clock(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
