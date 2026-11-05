/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The God Potion and Booster Cookie cards. Two separate HUD elements rather than one combined card:
 * the two buffs run on wildly different scales (hours against months), so most players want the
 * expiring one where they will see it and the other one out of the way.
 *
 * <p>A card draws only while its buff is actually active - an empty "God Potion: none" card would
 * cost permanent screen space to say nothing.
 *
 * <p><b>Both are off by default.</b> The same two timers are rows in the Custom Scoreboard now
 * ({@code CustomScoreboardRenderer.addBuffRows}), which is where the eye already goes for status;
 * these cards remain for anyone who wants a timer somewhere of its own, at its own scale and opacity.
 *
 * <p>Every card is a {@link CachedModel}: texts, widths and colours are resolved when the key moves,
 * and a steady frame only draws. The keys are reused while unchanged, so a hit allocates nothing.
 */
public final class BuffHud {

    private static final int PAD = 5;
    private static final String CAKES = "Cakes";
    private static final Component CAKES_TEXT = Component.literal(CAKES);

    private BuffHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().buffs;
        if (!cfg.enabled || Minecraft.getInstance().player == null) {
            return;
        }
        BuffTracker tracker = BuffTracker.getInstance();
        Font font = Minecraft.getInstance().font;
        int configRevision = ConfigManager.getInstance().revision();
        if (cfg.godPotionCard) {
            String value = tracker.godPotion();
            if (value != null && !HudLayout.isHidden(HudElement.GOD_POT_TIMER)) {
                godKey = CardKey.of(godKey, value, configRevision, font);
                card(g, HudElement.GOD_POT_TIMER, GOD_MODEL.get(godKey), font);
            }
        }
        if (cfg.cookieCard) {
            String value = tracker.cookieBuff();
            if (value != null && !HudLayout.isHidden(HudElement.COOKIE_BUFF_TIMER)) {
                cookieKey = CardKey.of(cookieKey, value, configRevision, font);
                card(g, HudElement.COOKIE_BUFF_TIMER, COOKIE_MODEL.get(cookieKey), font);
            }
        }
        if (cfg.cakeCard && !HudLayout.isHidden(HudElement.CAKE_BUFFS)) {
            cakeKey = CakeKey.of(cakeKey, CakeBuffStore.getInstance().book(), cfg.cakeCollapsed,
                    Math.floorDiv(System.currentTimeMillis(), 1_000L), SBSTheme.revision(), font);
            cakeCard(g, CAKE_MODEL.get(cakeKey), font);
        }
    }

    // ------------------------------------------------------------------ God Potion / Cookie cards

    /**
     * What a single-buff card is built from: the value as the tab words it (it moves at most once
     * per tab scan), the config revision (its two colours are config hex strings) and the font.
     */
    record CardKey(String value, int configRevision, Font font) {

        /** {@code last} itself while nothing moved, so a steady frame allocates no key. */
        static CardKey of(CardKey last, String value, int configRevision, Font font) {
            if (last != null && last.configRevision == configRevision && last.font == font
                    && last.value.equals(value)) {
                return last;
            }
            return new CardKey(value, configRevision, font);
        }
    }

    /** One card's drawn texts, colours and width, resolved at build. */
    private record CardModel(Component label, Component value, int width, int nameColor, int timeColor) {
    }

    private static CardKey godKey;
    private static CardKey cookieKey;

    private static final CachedModel<CardKey, CardModel> GOD_MODEL = new CachedModel<>("buffHud.godPotion",
            key -> buildCard(key, "God Potion", BuffColors.godPotionName(), BuffColors.godPotionTime()));
    private static final CachedModel<CardKey, CardModel> COOKIE_MODEL = new CachedModel<>("buffHud.cookie",
            key -> buildCard(key, "Cookie Buff", BuffColors.cookieBuffName(), BuffColors.cookieBuffTime()));

    private static CardModel buildCard(CardKey key, String label, int nameRgb, int timeRgb) {
        Font font = key.font();
        int contentW = Math.max(font.width(label), font.width(key.value()));
        // The card's own two colours, not the theme's: the value used to be SBSTheme.TEXT, which
        // made the time on a card and the time on the scoreboard row two different colours for one
        // number. Both readouts are drawn from BuffColors now.
        return new CardModel(Component.literal(label), Component.literal(key.value()),
                Math.max(96, contentW + PAD * 2), 0xFF000000 | nameRgb, 0xFF000000 | timeRgb);
    }

    /** One labelled card, sized to its text; drawn only while the buff is active. */
    private static void card(GuiGraphicsExtractor g, HudElement element, CardModel m, Font font) {
        int lineH = font.lineHeight + 2;
        int width = m.width();
        int height = PAD * 2 + lineH * 2 - 2;

        HudElement.Bounds b = element.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(element, x, y, width, height);

        HudLayout.begin(g, element);
        HudCard.draw(g, x, y, width, height);
        g.text(font, m.label(), x + PAD, y + PAD, m.nameColor());
        g.text(font, m.value(), x + PAD, y + PAD + lineH, m.timeColor());
        HudLayout.end(g);
    }

    // ------------------------------------------------------------------ Century Cake card

    /**
     * What the cake card is built from: the book and its revision (an eat, a refresh, a prune, or a
     * profile reload that swaps the book), the collapsed setting, the wall second (its rows count
     * down), the theme revision (its colours) and the font.
     */
    record CakeKey(CakeBuffBook book, int bookRevision, boolean collapsed, long wallSecond,
                   int themeRevision, Font font) {

        /** {@code last} itself while nothing moved, so a steady frame allocates no key. */
        static CakeKey of(CakeKey last, CakeBuffBook book, boolean collapsed, long wallSecond,
                          int themeRevision, Font font) {
            int bookRevision = book.revision();
            if (last != null && last.book == book && last.bookRevision == bookRevision
                    && last.collapsed == collapsed && last.wallSecond == wallSecond
                    && last.themeRevision == themeRevision && last.font == font) {
                return last;
            }
            return new CakeKey(book, bookRevision, collapsed, wallSecond, themeRevision, font);
        }
    }

    private record CakeRow(Component label, Component value, int valueWidth) {
    }

    /** No rows: no cake is active, and the card is not drawn. */
    private record CakeModel(CakeRow[] rows, int width, int headerColor, int labelColor, int valueColor) {
    }

    private static CakeKey cakeKey;

    private static final CachedModel<CakeKey, CakeModel> CAKE_MODEL =
            new CachedModel<>("buffHud.cakes", BuffHud::buildCakes);

    private static CakeModel buildCakes(CakeKey key) {
        Font font = key.font();
        long now = System.currentTimeMillis();
        List<CakeBuffBook.Buff> cakes = CakeBuffStore.getInstance().active();
        String[][] texts;
        if (cakes.isEmpty()) {
            texts = new String[0][];
        } else if (key.collapsed()) {
            texts = new String[][] {{cakes.size() + (cakes.size() == 1 ? " cake" : " cakes")
                    + " - next ends in", CakeBuffBook.remaining(cakes.get(0).expiresAt, now)}};
        } else {
            texts = new String[cakes.size()][];
            for (int i = 0; i < texts.length; i++) {
                CakeBuffBook.Buff cake = cakes.get(i);
                texts[i] = new String[] {"+" + cake.amount + " " + cake.stat,
                        CakeBuffBook.remaining(cake.expiresAt, now)};
            }
        }
        int contentW = font.width(CAKES);
        CakeRow[] rows = new CakeRow[texts.length];
        for (int i = 0; i < texts.length; i++) {
            int valueW = font.width(texts[i][1]);
            contentW = Math.max(contentW, font.width(texts[i][0]) + 8 + valueW);
            rows[i] = new CakeRow(Component.literal(texts[i][0]), Component.literal(texts[i][1]), valueW);
        }
        return new CakeModel(rows, Math.max(96, contentW + PAD * 2),
                SBSTheme.ACCENT_BRIGHT, SBSTheme.TEXT, SBSTheme.TEXT_MUTED);
    }

    /**
     * The Century Cake card: a "Cakes" header and one row per active buff ("+5 Mining Fortune" and
     * its time left), or a single summary line when collapsed. Draws only while a cake is active.
     */
    private static void cakeCard(GuiGraphicsExtractor g, CakeModel m, Font font) {
        if (m.rows().length == 0) {
            return;
        }
        int lineH = font.lineHeight + 2;
        int width = m.width();
        int height = PAD * 2 + lineH * (1 + m.rows().length) - 2;

        HudElement.Bounds b = HudElement.CAKE_BUFFS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.CAKE_BUFFS, x, y, width, height);

        HudLayout.begin(g, HudElement.CAKE_BUFFS);
        HudCard.draw(g, x, y, width, height);
        g.text(font, CAKES_TEXT, x + PAD, y + PAD, m.headerColor());
        int iy = y + PAD + lineH;
        int right = x + width - PAD;
        for (CakeRow row : m.rows()) {
            g.text(font, row.label(), x + PAD, iy, m.labelColor());
            g.text(font, row.value(), right - row.valueWidth(), iy, m.valueColor());
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
