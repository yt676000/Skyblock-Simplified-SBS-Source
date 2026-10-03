/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.quest.model.Quest;
import sbs.modid.client.helper.quest.model.QuestCost;
import sbs.modid.client.helper.quest.logic.QuestItemActions;
import sbs.modid.client.helper.quest.logic.QuestTracker;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything a quest needs, in one place: every step in order, every required item with its live
 * price, the total, and – per item – a way to go buy, craft or look it up.
 *
 * <p>This exists because the HUD overlay cannot: it is HUD paint, so it can never be clicked. The
 * shopping list is a thing you plan with before you set off, which is a screen's job.
 *
 * <p>Prices are live Bazaar / lowest-BIN lookups, so the totals move with the market and need no
 * price history.
 */
public final class QuestDetailScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int ICON = 16;
    private static final int ACTION_W = 52;

    private final Quest quest;
    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;

    private int scroll;
    private int scrollMax;

    /** Only steps that actually need buying – the shopping list, not the walkthrough. */
    private final List<Quest.QuestStep> itemSteps = new ArrayList<>();

    public QuestDetailScreen(Quest quest, Screen parent) {
        super(Component.literal("Quest Items"));
        this.quest = quest;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 400, 560);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 240, 400);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        listTop = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER + 12;
        listBottom = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT - 18;

        itemSteps.clear();
        if (quest != null && quest.steps != null) {
            for (Quest.QuestStep step : quest.steps) {
                if (step != null && step.item != null) {
                    itemSteps.add(step);
                }
            }
        }

        addRenderableOnly(new PanelRenderable());
        addRenderableWidget(new SciFiButton(innerX, panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT,
                contentW, SBSTheme.SEARCH_HEIGHT, Component.literal("Back"), this::onClose));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (event.y() < listTop || event.y() >= listBottom) {
            return false;
        }
        int index = scroll + (int) ((event.y() - listTop) / ROW_H);
        if (index < 0 || index >= itemSteps.size()) {
            return false;
        }
        Quest.QuestStep step = itemSteps.get(index);
        // Three buttons, right-aligned: Market | Recipe | Wiki.
        int x3 = innerX + contentW - ACTION_W;
        int x2 = x3 - ACTION_W - 3;
        int x1 = x2 - ACTION_W - 3;
        if (event.x() >= x1 && event.x() < x1 + ACTION_W) {
            QuestItemActions.openMarket(step.item.id, step.item.name);
            return true;
        }
        if (event.x() >= x2 && event.x() < x2 + ACTION_W) {
            QuestItemActions.openRecipe(step.item.name);
            return true;
        }
        if (event.x() >= x3 && event.x() < x3 + ACTION_W) {
            QuestItemActions.openWiki(this, step.item.name);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = QuestDetailScreen.this.font;
            g.fill(0, 0, QuestDetailScreen.this.width, QuestDetailScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal(quest == null ? "Quest" : quest.name),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + panelW - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            // Column header, so the numbers on the right are not a mystery.
            int headY = listTop - 11;
            g.text(font, Component.literal("§8Required items"), innerX, headY, SBSTheme.TEXT_MUTED);
            String hint = "§8click: market  •  recipe  •  wiki";
            g.text(font, Component.literal(hint), innerX + contentW - font.width(hint), headY,
                    SBSTheme.TEXT_MUTED);

            drawRows(g, mouseX, mouseY);
            drawTotal(g);
        }

        private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = QuestDetailScreen.this.font;
            if (itemSteps.isEmpty()) {
                g.centeredText(font, Component.literal("§7This quest needs no bought items."),
                        panelX + panelW / 2, listTop + 12, SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            scrollMax = Math.max(0, itemSteps.size() - visible);
            scroll = clamp(scroll, 0, scrollMax);

            int y = listTop;
            for (int i = scroll; i < itemSteps.size() && i < scroll + visible; i++) {
                drawRow(g, itemSteps.get(i), i, y, mouseX, mouseY);
                y += ROW_H;
            }
            if (scrollMax > 0) {
                int trackH = visible * ROW_H;
                g.fill(innerX + contentW + 2, listTop, innerX + contentW + 4, listTop + trackH,
                        SBSTheme.CARD_BG_DISABLED);
                int thumbH = Math.max(8, trackH * visible / itemSteps.size());
                int thumbY = listTop + (int) ((long) (trackH - thumbH) * scroll / scrollMax);
                g.fill(innerX + contentW + 2, thumbY, innerX + contentW + 4, thumbY + thumbH,
                        SBSTheme.ACCENT);
            }
        }

        /** One item: icon, "15x Poppy", how many you hold, its live cost, then the three actions. */
        private void drawRow(GuiGraphicsExtractor g, Quest.QuestStep step, int index, int y,
                             int mouseX, int mouseY) {
            var font = QuestDetailScreen.this.font;
            Quest.QuestItem item = step.item;
            boolean current = index == currentItemIndex();
            boolean hovered = mouseX >= innerX && mouseX < innerX + contentW
                    && mouseY >= y && mouseY < y + ROW_H - 2;

            SciFiRender.roundedRectWithBorder(g, innerX, y, contentW, ROW_H - 2,
                    SBSTheme.CORNER_RADIUS, hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    current ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            g.item(SkyBlockItemIcons.getInstance().icon(item.id, null, 1), innerX + 2, y + 1);
            int textY = y + (ROW_H - 2 - font.lineHeight) / 2;

            int have = QuestTracker.countItem(item);
            boolean done = have >= item.amount;
            String name = item.amount + "x " + item.name;
            g.text(font, Component.literal((done ? "§a" : "§f") + name), innerX + ICON + 6, textY,
                    SBSTheme.TEXT);

            // Actions, right-aligned.
            int x3 = innerX + contentW - ACTION_W;
            int x2 = x3 - ACTION_W - 3;
            int x1 = x2 - ACTION_W - 3;
            action(g, x1, y, QuestItemActions.marketLabel(item.id), mouseX, mouseY);
            action(g, x2, y, "Recipe", mouseX, mouseY);
            action(g, x3, y, "Wiki", mouseX, mouseY);

            // Cost + held count, left of the buttons.
            long cost = QuestCost.stepCost(step);
            String costText = cost == QuestCost.UNKNOWN ? "§8?" : "§6" + QuestCost.format(cost);
            int costX = x1 - 8 - font.width(costText);
            g.text(font, Component.literal(costText), costX, textY, SBSTheme.TEXT);
            String held = (done ? "§a" : "§7") + have + "/" + item.amount;
            g.text(font, Component.literal(held), costX - 8 - font.width(held), textY, SBSTheme.TEXT);

            if (hovered) {
                g.setTooltipForNextFrame(font, List.of(
                                Component.literal("§f" + item.name),
                                Component.literal("§8" + item.id),
                                Component.literal("§7You have §f" + have + "§7 of §f" + item.amount)),
                        Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
            }
        }

        private void action(GuiGraphicsExtractor g, int x, int y, String label,
                            int mouseX, int mouseY) {
            var font = QuestDetailScreen.this.font;
            int h = ROW_H - 6;
            int by = y + 2;
            boolean hovered = mouseX >= x && mouseX < x + ACTION_W && mouseY >= by && mouseY < by + h;
            SciFiRender.roundedRectWithBorder(g, x, by, ACTION_W, h, SBSTheme.CORNER_RADIUS,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG_DISABLED,
                    hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.centeredText(font, Component.literal(label), x + ACTION_W / 2,
                    by + (h - font.lineHeight) / 2, hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
        }

        /** Total row: what is left from here, and what the whole quest costs end to end. */
        private void drawTotal(GuiGraphicsExtractor g) {
            var font = QuestDetailScreen.this.font;
            int y = listBottom + 4;
            QuestCost.Total remaining = QuestCost.remaining(quest, QuestTracker.getInstance().stepIndex());
            QuestCost.Total full = QuestCost.full(quest);

            String left = "Remaining §6" + remaining.display() + "  §8/  §7Full quest §6" + full.display();
            g.text(font, Component.literal(left), innerX, y, SBSTheme.TEXT);
            if (!full.complete()) {
                String note = "§8" + full.unpriced() + " item(s) unpriced";
                g.text(font, Component.literal(note), innerX + contentW - font.width(note), y,
                        SBSTheme.TEXT_MUTED);
            }
        }

        /** Which shopping-list row belongs to the step being worked on right now, or -1. */
        private int currentItemIndex() {
            Quest.QuestStep current = QuestTracker.getInstance().currentStep();
            return current == null ? -1 : itemSteps.indexOf(current);
        }
    }
}
