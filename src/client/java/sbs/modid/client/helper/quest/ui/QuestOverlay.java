/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.quest.logic.QuestTracker;
import sbs.modid.client.helper.quest.model.Quest;
import sbs.modid.client.helper.quest.model.QuestCost;
import sbs.modid.client.helper.quest.model.QuestIslands;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * The active quest on the HUD, in the SBS panel style: what to do now, what is left, and what the
 * rest will cost.
 *
 * <p>Shows a window around the current step rather than all thirty – the whole list would not fit,
 * and what matters while playing is what was just done and what is next. The active step gets a
 * second line with its hint and the item it needs, because "▶ Bring 15x Poppy" alone does not say
 * <i>where</i> or <i>why not Silk Touch</i>.
 */
public final class QuestOverlay {

    /** How many steps to show around the current one. */
    private static final int CONTEXT_BEFORE = 2;
    private static final int CONTEXT_AFTER = 3;

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int BAR_HEIGHT = 4;
    private static final int BOX = 9;
    private static final int ICON = 10;

    private static final int DONE = 0xFF57D977;
    private static final int PENDING = 0xFF6E86A3;

    private QuestOverlay() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().questGuide;
        QuestTracker tracker = QuestTracker.getInstance();
        Quest quest = tracker.quest();
        if (!cfg.enabled || !cfg.showOverlay || quest == null
                || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.QUEST_GUIDE)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.QUEST_GUIDE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.QUEST_GUIDE);
        draw(g, tracker, quest, (int) bounds.x(), (int) bounds.y(), (int) bounds.w());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, QuestTracker tracker, Quest quest,
                             int x, int y, int width) {
        Font font = Minecraft.getInstance().font;
        List<Row> rows = buildRows(tracker, quest);

        int rowsH = 0;
        for (Row row : rows) {
            rowsH += rowHeight(font, row) + LINE_GAP;
        }
        int height = PAD * 2 + font.lineHeight + LINE_GAP + BAR_HEIGHT + LINE_GAP
                + rowsH + font.lineHeight;
        HudLayout.measure(HudElement.QUEST_GUIDE, x, y, width, height);

        // SBS panel: glow, border, gradient body - the same shell every other SBS surface uses.
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;

        // Header: quest name left, step counter right.
        g.text(font, Component.literal(quest.name), ix, iy, SBSTheme.ACCENT_BRIGHT);
        String counter = Math.min(tracker.stepIndex(), quest.stepCount()) + "/" + quest.stepCount();
        g.text(font, Component.literal(counter), right - font.width(counter), iy, SBSTheme.ACCENT);

        // Progress bar.
        int barY = iy + font.lineHeight + LINE_GAP;
        int barW = width - PAD * 2;
        SciFiRender.roundedRect(g, ix, barY, barW, BAR_HEIGHT, 1, SBSTheme.CARD_BG_DISABLED);
        int filled = (int) Math.round(barW * tracker.progress());
        if (filled > 0) {
            SciFiRender.roundedRect(g, ix, barY, filled, BAR_HEIGHT, 1, SBSTheme.ACCENT);
        }

        int ly = barY + BAR_HEIGHT + LINE_GAP;
        for (Row row : rows) {
            drawRow(g, font, row, ix, ly, right);
            ly += rowHeight(font, row) + LINE_GAP;
        }

        // Footer: what the rest of the quest still costs, priced live.
        QuestCost.Total cost = QuestCost.remaining(quest, tracker.stepIndex());
        String label = "Remaining cost";
        String value = cost.priced() == 0 ? "?" : cost.display();
        g.text(font, Component.literal(label), ix, ly, PENDING);
        g.text(font, Component.literal(value), right - font.width(value), ly,
                cost.complete() ? SBSTheme.ACCENT : PENDING);
    }

    /** A row is one line, or two when the active step has a hint / item to explain. */
    private static int rowHeight(Font font, Row row) {
        return row.detail == null ? font.lineHeight : font.lineHeight * 2 + 1;
    }

    private static void drawRow(GuiGraphicsExtractor g, Font font, Row row, int x, int y, int right) {
        int color = row.done ? DONE : row.active ? SBSTheme.TEXT : PENDING;

        // Checkbox: ticked when done, an arrow for the step in progress, empty when ahead.
        String box = row.done ? "✔" : row.active ? "▶" : "□";
        g.text(font, Component.literal(box), x, y, color);

        int textX = x + BOX;
        int rightEdge = right;

        // Count hard right, item icon just left of it.
        if (row.counter != null) {
            rightEdge -= font.width(row.counter);
            g.text(font, Component.literal(row.counter), rightEdge, y,
                    row.satisfied ? DONE : SBSTheme.ACCENT_BRIGHT);
            rightEdge -= 2;
        }
        if (row.icon != null) {
            rightEdge -= ICON;
            g.item(row.icon, rightEdge, y - 1);
            rightEdge -= 2;
        }
        // NOTE: rightEdge and textX are both ABSOLUTE. Mixing an absolute x with the panel's
        // relative width here previously made this negative, so the title silently never drew.
        int space = rightEdge - textX;
        if (space > 8) {
            g.text(font, Component.literal(trim(font, row.title, space)), textX, y, color);
        }
        if (row.detail != null) {
            int detailSpace = right - textX;
            g.text(font, Component.literal(trim(font, row.detail, detailSpace)),
                    textX, y + font.lineHeight + 1, PENDING);
        }
    }

    /** The rows to show: a window around the current step, done ones above, upcoming below. */
    private static List<Row> buildRows(QuestTracker tracker, Quest quest) {
        int current = Math.min(tracker.stepIndex(), quest.stepCount());
        int from = Math.max(0, current - CONTEXT_BEFORE);
        int to = Math.min(quest.stepCount(), current + CONTEXT_AFTER);

        List<Row> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Quest.QuestStep step = quest.step(i);
            if (step == null) {
                continue;
            }
            Row row = new Row();
            row.title = step.title == null ? "?" : step.title;
            row.done = i < current;
            row.active = i == current;

            // Only the active step is spelled out. A done step's count is noise, and a future
            // step's would have the player chasing an item two steps early.
            if (row.active) {
                row.detail = detailFor(step);
                if (step.item != null) {
                    int have = QuestTracker.countItem(step.item);
                    row.counter = have + "/" + step.item.amount;
                    row.satisfied = have >= step.item.amount;
                    row.icon = SkyBlockItemIcons.getInstance().icon(step.item.id, null, 1);
                } else if (step.requirement != null) {
                    row.satisfied = QuestTracker.requirementMet(step.requirement);
                    row.counter = row.satisfied ? "✔" : "✗";
                }
            }
            rows.add(row);
        }
        if (tracker.finished()) {
            Row row = new Row();
            row.title = "Quest complete!";
            row.done = true;
            rows.add(row);
        }
        return rows;
    }

    /**
     * The active step's second line.
     *
     * <p>"You are on the wrong island" wins over everything: the route cannot be drawn there, and
     * without saying so the overlay would just show a step with no path and look broken.
     */
    private static String detailFor(Quest.QuestStep step) {
        Quest quest = QuestTracker.getInstance().quest();
        if (step.waypoint != null && !QuestIslands.onIslandOf(quest, step.waypoint)) {
            String where = QuestIslands.prettyIsland(step.waypoint.island);
            String area = QuestIslands.currentArea();
            return area.isEmpty() ? "Travel to " + where : "Travel to " + where + " (you: " + area + ")";
        }
        if (step.hint != null && !step.hint.isBlank()) {
            return step.hint;
        }
        if (step.item != null) {
            long cost = QuestCost.stepCost(step);
            if (cost != QuestCost.UNKNOWN && cost > 0) {
                return step.item.amount + "x " + step.item.name + "  ~" + QuestCost.format(cost);
            }
            return step.item.amount + "x " + step.item.name;
        }
        if (step.requirement != null && step.requirement.amount > 0) {
            return "Needs " + step.requirement.amount + " " + step.requirement.type;
        }
        return null;
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    /** One rendered checklist line. */
    private static final class Row {
        private String title;
        private String detail;
        private boolean done;
        private boolean active;
        private boolean satisfied;
        private String counter;
        private ItemStack icon;
    }
}
