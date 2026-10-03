/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.hunting.logic.HuntingBoxScanner;
import sbs.modid.client.skills.hunting.logic.HuntingBoxStore;
import sbs.modid.client.skills.hunting.logic.ShardValuation;
import sbs.modid.client.skills.hunting.model.ShardRarity;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The value panel beside the Hunting Box: three answers, never one number.
 *
 * <p>The layout is deliberate about which figure is the headline. <b>Surplus</b> leads, because it is
 * the only stack that is safe to sell; the whole-box raw figure is shown underneath in a muted colour
 * as a fact about the box rather than a suggestion; and the syphon line counts the shards the player
 * still needs, which is the number that stops the raw total being read as advice.
 *
 * <p>Every figure carries its price age, and the syphon counts carry their {@link ShardRarity}
 * certainty tag - they are wiki numbers until someone reads them off a live attribute menu.
 */
public final class HuntingBoxPanel {

    private static final HuntingBoxPanel INSTANCE = new HuntingBoxPanel();

    private static final int PANEL_W = 186;
    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int GAP_TO_CONTAINER = 8;
    private static final int MAX_ROWS = 12;

    private static final int NEEDED_COLOR = 0xFFE0A14D;
    private static final int SURPLUS_COLOR = 0xFF57D977;
    private static final int WARN_COLOR = 0xFFE0605F;

    private HuntingBoxPanel() {
    }

    public static HuntingBoxPanel getInstance() {
        return INSTANCE;
    }

    /** Called for every container screen from the overlay render hook. */
    public void render(AbstractContainerScreen<?> container, GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().hunting.boxValue
                || !ConfigManager.getInstance().get().hunting.boxPanel
                || !HuntingBoxScanner.isBox(container)) {
            return;
        }
        HuntingBoxStore store = HuntingBoxStore.getInstance();
        ShardValuation.BoxValue value = ShardValuation.value(store.shards());

        Font font = Minecraft.getInstance().font;
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) container;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        int imageW = bounds.skyblockSimplified$imageWidth();

        int x = left - PANEL_W - GAP_TO_CONTAINER;
        if (x < 4) {
            x = left + imageW + GAP_TO_CONTAINER;
        }
        draw(g, font, value, x, top, store.ageMs());
    }

    private void draw(GuiGraphicsExtractor g, Font font, ShardValuation.BoxValue value,
                      int x, int y, long boxAgeMs) {
        List<ShardValuation.Valued> rows = sorted(value);
        int shown = Math.min(rows.size(), MAX_ROWS);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * (4 + Math.max(1, shown) + (rows.size() > MAX_ROWS ? 1 : 0))
                + lineH;   // the certainty footer

        HudCard.draw(g, x, y, PANEL_W, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal("Hunting Box"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;

        // Surplus first: the only figure that is a suggestion rather than a fact.
        g.text(font, Component.literal("Surplus: " + NumberDisplay.format(value.surplusTotal())),
                ix, iy, SURPLUS_COLOR);
        iy += lineH;
        g.text(font, Component.literal("Whole box: " + NumberDisplay.format(value.rawTotal())),
                ix, iy, SBSTheme.TEXT_MUTED);
        iy += lineH;
        g.text(font, Component.literal(value.needingShards() + " kind(s) still short of an attribute"),
                ix, iy, value.needingShards() > 0 ? NEEDED_COLOR : SBSTheme.TEXT_MUTED);
        iy += lineH;

        if (rows.isEmpty()) {
            g.text(font, Component.literal("Open the box to read it."), ix, iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        for (int index = 0; index < shown; index++) {
            ShardValuation.Valued row = rows.get(index);
            String label = row.shard().name() + " x" + row.shard().count();
            int color = row.needed() > 0 ? NEEDED_COLOR
                    : row.surplus() > 0 ? SURPLUS_COLOR : SBSTheme.TEXT_MUTED;
            g.text(font, Component.literal(trim(font, label)), ix, iy, color);
            String right = !row.priced() ? "?" : NumberDisplay.format(row.surplusValue());
            g.text(font, Component.literal(right),
                    x + PANEL_W - PAD - font.width(right), iy,
                    row.wideSpread() || row.thinForStack() ? WARN_COLOR : SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        if (rows.size() > MAX_ROWS) {
            g.text(font, Component.literal("+" + (rows.size() - MAX_ROWS) + " more"),
                    ix, iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }

        // Both ages, because they are different vintages of fact: the box is what we last read off
        // the menu, the prices are what the Bazaar said.
        g.text(font, Component.literal(age(boxAgeMs) + " · prices " + age(value.priceAgeMs())
                + (value.unpriced() > 0 ? " · " + value.unpriced() + " unpriced" : "")),
                ix, iy, SBSTheme.TEXT_MUTED);
        iy += lineH;
        g.text(font, Component.literal("syphon counts " + ShardRarity.COUNT_CERTAINTY.displayName()),
                ix, iy, SBSTheme.TEXT_MUTED);
    }

    /** Shards still needed first, then the biggest surplus - the order the player acts in. */
    private static List<ShardValuation.Valued> sorted(ShardValuation.BoxValue value) {
        List<ShardValuation.Valued> rows = new java.util.ArrayList<>(value.rows());
        rows.sort((a, b) -> {
            boolean needA = a.needed() > 0;
            boolean needB = b.needed() > 0;
            if (needA != needB) {
                return needA ? -1 : 1;
            }
            return Long.compare(b.surplusValue(), a.surplusValue());
        });
        return rows;
    }

    private static String trim(Font font, String text) {
        String out = text;
        while (font.width(out) > PANEL_W - PAD * 2 - 40 && out.length() > 4) {
            out = out.substring(0, out.length() - 2);
        }
        return out.equals(text) ? text : out + "…";
    }

    /** "never read" / "just now" / "6m ago" - an age nobody has to work out. */
    private static String age(long ms) {
        if (ms < 0) {
            return "never read";
        }
        long seconds = ms / 1000;
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86_400) + "d ago";
    }
}
