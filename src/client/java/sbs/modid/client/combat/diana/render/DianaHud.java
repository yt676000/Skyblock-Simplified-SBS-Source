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
import sbs.modid.client.combat.diana.logic.DianaHudLayout;
import sbs.modid.client.combat.diana.logic.DianaHudRows;
import sbs.modid.client.combat.diana.logic.DianaPreview;
import sbs.modid.client.combat.diana.logic.DianaTracker;
import sbs.modid.client.combat.diana.logic.MythMobTracker;
import sbs.modid.client.combat.diana.model.DianaHudLine;
import sbs.modid.client.combat.diana.model.DianaPanel;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The two Diana cards: how the event is going, and what is about to kill you.
 *
 * <h2>What goes on which card is the player's</h2>
 *
 * <p>Each card draws the lines {@link DianaHudLayout} places on it, in that order, built into rows
 * by {@link DianaHudRows}. The shipped layout is exactly what the two cards always showed. Title and
 * compact mode are per card; see {@code docs/features/diana-appearance.md}.
 *
 * <h2>Self-hiding, both of them</h2>
 *
 * <p>Neither card draws a frame with nothing in it. A card whose lines have nothing to say is not
 * drawn: a card that is present and empty is a card the player moves out of the way and then never
 * sees when it matters.
 *
 * <p>They also draw nothing outside the Hub and nothing while the ritual is not running, which is
 * most of the time for most players - the event is a fortnight in eight. The one exception is the
 * appearance preview, which draws sample cards while a settings screen is open.
 */
public final class DianaHud {

    private static final int ACCENT = 0xFFFFD65A;
    private static final int WARN = 0xFFFF8A6B;
    private static final int OK = 0xFF9BE37F;

    /** How a card is laid out: the normal card, or the compact one with no title. */
    public record CardStyle(boolean title, int padding, int lineGap, int minWidth) {

        static final CardStyle NORMAL = new CardStyle(true, 5, 2, 110);
        static final CardStyle UNTITLED = new CardStyle(false, 5, 2, 110);
        static final CardStyle COMPACT = new CardStyle(false, 3, 1, 60);

        public static CardStyle of(boolean title, boolean compact) {
            if (compact) {
                return COMPACT;
            }
            return title ? NORMAL : UNTITLED;
        }
    }

    private DianaHud() {
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** Drawn from the HUD pass. Both cards, each with the lines placed on it. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DianaSettings cfg = cfg();
        boolean preview = DianaPreview.active(cfg);
        if (!preview && (!cfg.enabled || !DianaEvent.awake() || !SkyBlockLocation.onIsland("Hub"))) {
            return;
        }
        DianaHudLayout.Layout layout = DianaHudLayout.current(cfg);
        DianaHudRows.Data data = preview ? DianaPreview.sampleData() : liveData(cfg, layout);
        for (DianaPanel panel : DianaPanel.values()) {
            HudElement element = element(panel);
            if (HudLayout.isHidden(element)) {
                continue;
            }
            List<DianaHudRows.Row> rows = DianaHudRows.build(layout.lines(panel), data, perCreatureCap(cfg));
            if (!rows.isEmpty()) {
                drawPlaced(g, element, panel, style(cfg, panel), rows);
            }
        }
    }

    public static HudElement element(DianaPanel panel) {
        return panel == DianaPanel.TRACKER ? HudElement.DIANA_TRACKER : HudElement.DIANA_CREATURES;
    }

    public static CardStyle style(SBSConfig.DianaSettings cfg, DianaPanel panel) {
        SBSConfig.DianaAppearanceSettings a = cfg.appearance;
        if (a == null) {
            return CardStyle.NORMAL;
        }
        return panel == DianaPanel.TRACKER
                ? CardStyle.of(a.trackerTitle, a.trackerCompact)
                : CardStyle.of(a.creatureTitle, a.creatureCompact);
    }

    public static int perCreatureCap(SBSConfig.DianaSettings cfg) {
        return cfg.appearance == null ? DianaHudRows.MAX_ROWS
                : Math.max(0, Math.min(DianaHudRows.MAX_ROWS, cfg.appearance.perCreatureRows));
    }

    /**
     * The live numbers, read only for the lines that are placed: a tracker card showing chains alone
     * does not walk the creature sightings every frame.
     */
    private static DianaHudRows.Data liveData(SBSConfig.DianaSettings cfg,
                                              DianaHudLayout.Layout layout) {
        ChainTracker chains = ChainTracker.getInstance();
        int running = chains.count();
        long oldest = running > 0 ? chains.oldestRemainingMs() : 0L;
        DianaTracker tracker = DianaTracker.getInstance();
        List<DianaHudRows.Creature> inSight = List.of();
        if (layout.panelOf(DianaHudLine.CREATURE_HEALTH) != null
                || layout.panelOf(DianaHudLine.NO_SHURIKEN) != null) {
            inSight = new ArrayList<>();
            for (MythMobTracker.Sighting sighting : MythMobTracker.getInstance().sightings()) {
                if (sighting.shared()) {
                    // Somebody else's sighting has no health to report - only a place. It gets a
                    // marker, not a row, because a health card listing a blank looks broken.
                    continue;
                }
                inSight.add(new DianaHudRows.Creature(sighting.label(), healthText(sighting),
                        sighting.creature().color(), sighting.starred()));
            }
        }
        return new DianaHudRows.Data(running, oldest, cfg.tracker,
                tracker.burrows(), tracker.creatures(), tracker.treasures(),
                tracker.mobsSinceInquisitor(), tracker.mobsSinceKing(),
                tracker.creatureCounts(), inSight);
    }

    private static String healthText(MythMobTracker.Sighting sighting) {
        if (sighting.hits() != null) {
            return sighting.hits() + " hits";
        }
        if (sighting.health() >= 0) {
            return MythMobTracker.format(sighting.health());
        }
        return "?";
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * A card at its HUD position. The element's default bounds are only the anchor the editor moves
     * and scales about; {@code HudLayout.begin}/{@code end} is what makes the card movable, scalable
     * and themed like everything else on screen.
     */
    private static void drawPlaced(GuiGraphicsExtractor g, HudElement element, DianaPanel panel,
                                   CardStyle style, List<DianaHudRows.Row> rows) {
        int[] size = measure(panel, style, rows);
        HudElement.Bounds bounds = element.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(bounds.x());
        int y = Math.round(bounds.y());
        HudLayout.measure(element, x, y, size[0], size[1]);
        HudLayout.begin(g, element);
        draw(g, x, y, size[0], size[1], panel, style, rows);
        HudLayout.end(g);
    }

    /** Width and height of a card with these rows, measured from its contents. */
    public static int[] measure(DianaPanel panel, CardStyle style, List<DianaHudRows.Row> rows) {
        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight + style.lineGap();
        int contentWidth = style.title() ? font.width(panel.title()) : 0;
        for (DianaHudRows.Row row : rows) {
            contentWidth = Math.max(contentWidth, font.width(row.label()) + 12 + font.width(row.value()));
        }
        int lines = rows.size() + (style.title() ? 1 : 0);
        int width = Math.max(style.minWidth(), contentWidth + style.padding() * 2);
        int height = style.padding() * 2 + lineHeight * lines - style.lineGap();
        return new int[] {width, height};
    }

    /** One card at a fixed position, outside any HUD transform - the HUD and the editor preview. */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int width, int height,
                            DianaPanel panel, CardStyle style, List<DianaHudRows.Row> rows) {
        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight + style.lineGap();
        HudCard.draw(g, x, y, width, height);

        int left = x + style.padding();
        int right = x + width - style.padding();
        int cursor = y + style.padding();
        if (style.title()) {
            g.text(font, Component.literal(panel.title()), left, cursor, SBSTheme.ACCENT_BRIGHT);
            cursor += lineHeight;
        }
        for (DianaHudRows.Row row : rows) {
            g.text(font, Component.literal(row.label()), left, cursor, SBSTheme.TEXT_MUTED);
            if (!row.value().isEmpty()) {
                g.text(font, Component.literal(row.value()),
                        right - font.width(row.value()), cursor, colour(row));
            }
            cursor += lineHeight;
        }
    }

    private static int colour(DianaHudRows.Row row) {
        return switch (row.tone()) {
            case MUTED -> SBSTheme.TEXT_MUTED;
            case ACCENT -> ACCENT;
            case WARN -> WARN;
            case OK -> OK;
            case CREATURE -> row.rgb();
        };
    }
}
