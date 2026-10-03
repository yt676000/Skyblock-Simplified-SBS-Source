/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.hunting.logic.HuntingSession;
import sbs.modid.client.skills.hunting.logic.HuntingSessionTracker;
import sbs.modid.client.ui.font.SbsFonts;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The live hunting session as a movable HUD card: what you have caught, how fast, and where.
 *
 * <p>Deliberately carries no coin figure. Shards spent levelling an attribute return no coins, and
 * the mod cannot currently read which shards a player still needs - so a total would be pricing stock
 * somebody is saving. Counts and rates are safe to show; see
 * {@code docs/features/hunting-profit-tracker.md}.
 */
public final class HuntingSessionHud {

    private static final int PAD = 6;
    private static final int MIN_WIDTH = 108;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 3;
    private static final int SPECIES_SHOWN = 4;

    private record Row(String left, String right, int leftColor, int rightColor, boolean spaced) {
    }

    private HuntingSessionHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        HuntingSessionTracker tracker = HuntingSessionTracker.getInstance();
        if (!tracker.visible() || HudLayout.isHidden(HudElement.HUNTING_SESSION)) {
            return;
        }
        HuntingSession session = tracker.session();
        if (session.total() == 0) {
            return;   // nothing caught yet - an empty card is clutter, not information
        }

        var font = SbsFonts.ui();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Shards", NumberDisplay.format(session.total()),
                SBSTheme.TEXT, SBSTheme.TEXT, false));
        rows.add(new Row("Species", String.valueOf(session.uniqueSpecies()),
                SBSTheme.TEXT_MUTED, SBSTheme.TEXT, false));

        // The rate is withheld rather than shown wrong: under a minute of active time a single catch
        // swings it into the thousands, and a number that silly is worse than an honest dash.
        rows.add(new Row("Per hour",
                session.rateReady() ? NumberDisplay.format(Math.round(session.shardsPerHour())) : "-",
                SBSTheme.TEXT_MUTED, session.rateReady() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED, false));

        if (!session.rarest().isEmpty()) {
            rows.add(new Row("Rarest", session.rarest(), SBSTheme.TEXT_MUTED, SBSTheme.TEXT, false));
        }

        List<Map.Entry<String, Integer>> top = session.topShards(SPECIES_SHOWN);
        boolean first = true;
        for (Map.Entry<String, Integer> entry : top) {
            rows.add(new Row(entry.getKey(), NumberDisplay.format(entry.getValue()),
                    SBSTheme.TEXT_MUTED, SBSTheme.TEXT, first));
            first = false;
        }

        List<Map.Entry<String, Integer>> islands = session.byIsland();
        if (islands.size() > 1) {
            // Only worth the lines when there is a comparison to make - one island is just the total
            // again, and that is the row above.
            boolean firstIsland = true;
            for (Map.Entry<String, Integer> entry : islands) {
                rows.add(new Row(entry.getKey(), NumberDisplay.format(entry.getValue()),
                        SBSTheme.TEXT_MUTED, SBSTheme.TEXT, firstIsland));
                firstIsland = false;
            }
        }

        String header = "Hunting Session";
        int lineH = font.lineHeight + LINE_GAP;
        int contentW = font.width(header);
        for (Row row : rows) {
            contentW = Math.max(contentW,
                    font.width(row.left()) + (row.right().isEmpty() ? 0 : 10 + font.width(row.right())));
        }
        int width = Math.max(MIN_WIDTH, contentW + PAD * 2);
        int height = PAD * 2 + font.lineHeight + 2 - LINE_GAP;
        for (Row row : rows) {
            height += lineH + (row.spaced() ? SECTION_GAP : 0);
        }

        HudElement.Bounds b = HudElement.HUNTING_SESSION.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.HUNTING_SESSION, x, y, width, height);

        HudLayout.begin(g, HudElement.HUNTING_SESSION);
        HudCard.draw(g, x, y, width, height);

        int left = x + PAD;
        int right = x + width - PAD;
        int ty = y + PAD;
        g.text(font, Component.literal(header), left, ty, SBSTheme.ACCENT_BRIGHT);
        ty += font.lineHeight + 2;
        for (Row row : rows) {
            if (row.spaced()) {
                ty += SECTION_GAP;
            }
            g.text(font, Component.literal(row.left()), left, ty, row.leftColor());
            if (!row.right().isEmpty()) {
                g.text(font, Component.literal(row.right()),
                        right - font.width(row.right()), ty, row.rightColor());
            }
            ty += lineH;
        }
        HudLayout.end(g);
    }
}
