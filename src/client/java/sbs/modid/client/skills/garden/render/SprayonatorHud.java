/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.skills.garden.logic.SprayTracker;
import sbs.modid.client.skills.garden.model.SprayMaterial;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Sprayonator card: which spray is running, with its item icon and countdown, and which one ran
 * before it.
 *
 * <p>The icon is the point of the card. A spray is only ever identified by name in chat and in the
 * tab list, and six material names read alike at a glance mid-farm - the item picture does not.
 *
 * <p>Garden-only through {@link PestTracker#onGarden()}, the same rule the pest cards use, and it
 * stays hidden entirely when there is nothing to say: no spray running, no Sprayonator carried and
 * nothing sprayed before.
 */
public final class SprayonatorHud {

    private static final int PAD = 5;
    /** Icon box; rows are sized to it rather than to the font, so the pictures stay square. */
    private static final int ICON = 16;
    private static final int ROW_H = ICON + 2;

    private SprayonatorHud() {
    }

    private static SBSConfig.SprayonatorSettings cfg() {
        return ConfigManager.getInstance().get().sprayonator;
    }

    /** One line of the card: an icon, the name beside it, and a right-aligned value. */
    private record Row(ItemStack icon, String label, String value, int valueColor) {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.SprayonatorSettings cfg = cfg();
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.SPRAYONATOR)) {
            return;
        }
        if (!PestTracker.getInstance().onGarden()) {
            return;
        }
        List<Row> rows = buildRows(cfg);
        if (rows.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        String header = "Sprayonator";
        int contentW = font.width(header);
        for (Row row : rows) {
            contentW = Math.max(contentW,
                    ICON + 3 + font.width(row.label()) + 12 + font.width(row.value()));
        }
        int width = Math.max(132, contentW + PAD * 2);
        int height = PAD * 2 + font.lineHeight + 2 + ROW_H * rows.size() - 2;

        HudElement.Bounds b = HudElement.SPRAYONATOR.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.SPRAYONATOR, x, y, width, height);

        HudLayout.begin(g, HudElement.SPRAYONATOR);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += font.lineHeight + 2;
        for (Row row : rows) {
            g.item(row.icon(), ix, iy);
            // Centre the text on the icon box, so a 9px line sits level with a 16px picture.
            int textY = iy + (ICON - font.lineHeight) / 2;
            g.text(font, Component.literal(row.label()), ix + ICON + 3, textY, SBSTheme.TEXT);
            if (!row.value().isEmpty()) {
                g.text(font, Component.literal(row.value()),
                        right - font.width(row.value()), textY, row.valueColor());
            }
            iy += ROW_H;
        }
        HudLayout.end(g);
    }

    /**
     * The card's lines: the running spray (or, when none runs, what the Sprayonator is loaded with)
     * and the previous spray.
     */
    private static List<Row> buildRows(SBSConfig.SprayonatorSettings cfg) {
        SprayTracker tracker = SprayTracker.getInstance();
        List<Row> rows = new ArrayList<>(2);

        SprayMaterial active = tracker.active();
        if (tracker.spraying()) {
            // The material can be unknown (a widget layout that does not name it, and no Sprayonator
            // carried when it started) - the countdown is still the number worth showing, so the row
            // falls back to the Sprayonator's own icon rather than dropping out.
            String label = active != null ? active.displayName() : "Spraying";
            String plot = tracker.activePlot();
            if (cfg.showPlot && !plot.isEmpty()) {
                label += "  Plot " + plot;
            }
            long remaining = tracker.remainingMs();
            // Under two minutes the spray is about to lapse - the one moment the number is urgent.
            int color = remaining <= 120_000L ? 0xFFE0A14D : 0xFF57D977;
            rows.add(new Row(active != null ? active.icon() : sprayonatorIcon(), label,
                    FarmingText.duration(remaining), color));
        } else if (cfg.showSelected) {
            SprayMaterial selected = tracker.selected();
            if (selected != null) {
                rows.add(new Row(selected.icon(), selected.displayName(), "loaded",
                        SBSTheme.TEXT_MUTED));
            }
        }

        if (cfg.showLast) {
            SprayMaterial last = tracker.lastMaterial();
            long at = tracker.lastSprayedAt();
            if (last != null && at > 0 && last != active) {
                rows.add(new Row(last.icon(), "Last: " + last.displayName(),
                        FarmingText.duration(System.currentTimeMillis() - at) + " ago",
                        SBSTheme.TEXT_MUTED));
            }
        }
        return rows;
    }

    /** The tool's own icon, the stand-in for a spray whose material could not be identified. */
    private static ItemStack sprayonatorIcon() {
        return sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons.getInstance()
                .iconShared("SPRAYONATOR", null, 1);
    }
}
