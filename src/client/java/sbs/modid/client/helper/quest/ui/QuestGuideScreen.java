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
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.quest.model.Quest;
import sbs.modid.client.helper.quest.logic.QuestDatabase;
import sbs.modid.client.helper.quest.logic.QuestTracker;

import java.util.List;

/**
 * The quest list: what is available, and a Quest Start button for each.
 *
 * <p>The list comes straight out of {@link QuestDatabase}, which is bundled in the jar – no licence
 * token, no request, no loading state. An empty list therefore means the jar carries no quest data,
 * which the screen says plainly instead of showing nothing and looking broken.
 */
public final class QuestGuideScreen extends Screen {

    private static final int ROW_H = 42;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int listTop;

    private List<Quest> quests = List.of();
    private String status = "";

    public QuestGuideScreen() {
        super(Component.literal("Quest Guide"));
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 340, 480);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 220, 380);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        listTop = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER;

        addRenderableOnly(new PanelRenderable());

        addRenderableWidget(new SciFiButton(innerX, panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT,
                contentW, SBSTheme.SEARCH_HEIGHT, Component.literal("Back"),
                () -> Minecraft.getInstance().setScreenAndShow(new SBSMainScreen())));

        quests = QuestDatabase.all();
        status = quests.isEmpty() ? "No quests available." : "";
        buildRows();
    }

    /** One Start / Stop button per quest, depending on whether it is the active one. */
    private void buildRows() {
        List<Quest> list = quests;
        QuestTracker tracker = QuestTracker.getInstance();
        String active = sbs.modid.client.helper.quest.logic.QuestProgressStore.getInstance().activeQuest();

        int y = listTop + 4;
        for (Quest quest : list) {
            if (y + ROW_H > panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT - 6) {
                break;
            }
            boolean running = quest.id.equals(active);
            int buttonW = 78;
            int buttonY = y + (ROW_H - SBSTheme.SEARCH_HEIGHT) / 2 - 4;
            addRenderableWidget(new SciFiButton(innerX + contentW - buttonW - 4, buttonY,
                    buttonW, SBSTheme.SEARCH_HEIGHT,
                    Component.literal(running ? "Stop" : "Quest Start"), () -> {
                        if (running) {
                            tracker.stop();
                            rebuildWidgets();
                        } else {
                            ConfigManager.getInstance().get().questGuide.enabled = true;
                            ConfigManager.getInstance().save();
                            tracker.start(quest.id, this::rebuildWidgets);
                        }
                    }));
            // Every quest's steps are in memory, so the shopping list no longer has to wait for a
            // quest to be the active one.
            addRenderableWidget(new SciFiButton(innerX + contentW - buttonW * 2 - 10, buttonY,
                    buttonW, SBSTheme.SEARCH_HEIGHT, Component.literal("Items & Cost"),
                    () -> Minecraft.getInstance().setScreenAndShow(
                            new QuestDetailScreen(quest, this))));
            y += ROW_H;
        }
    }

    @Override
    protected void rebuildWidgets() {
        super.rebuildWidgets();
        buildRows();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = QuestGuideScreen.this.font;
            g.fill(0, 0, QuestGuideScreen.this.width, QuestGuideScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Quest Guide"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + panelW - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            List<Quest> list = quests;
            if (list.isEmpty()) {
                g.centeredText(font, Component.literal("§7" + status), panelX + panelW / 2,
                        listTop + 20, SBSTheme.TEXT_MUTED);
                return;
            }
            drawRows(g, list, mouseX, mouseY);
        }

        private void drawRows(GuiGraphicsExtractor g, List<Quest> list, int mouseX, int mouseY) {
            var font = QuestGuideScreen.this.font;
            QuestTracker tracker = QuestTracker.getInstance();
            String active = sbs.modid.client.helper.quest.logic.QuestProgressStore.getInstance().activeQuest();

            int y = listTop + 4;
            for (Quest quest : list) {
                if (y + ROW_H > panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT - 6) {
                    break;
                }
                boolean running = quest.id.equals(active);
                boolean hovered = mouseX >= innerX && mouseX < innerX + contentW
                        && mouseY >= y && mouseY < y + ROW_H - 4;
                SciFiRender.roundedRectWithBorder(g, innerX, y, contentW, ROW_H - 4,
                        SBSTheme.CORNER_RADIUS,
                        hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        running ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

                g.text(font, Component.literal("§f" + quest.name), innerX + 8, y + 6, SBSTheme.TEXT);
                String sub = quest.description == null ? "" : quest.description;
                g.text(font, Component.literal("§8" + trim(sub, contentW - 100)),
                        innerX + 8, y + 6 + font.lineHeight + 2, SBSTheme.TEXT_MUTED);

                if (running) {
                    String progress = tracker.stepIndex() + "/" + quest.stepCount();
                    g.text(font, Component.literal("§b" + progress),
                            innerX + 8, y + ROW_H - 4 - font.lineHeight - 3, SBSTheme.ACCENT);
                } else {
                    g.text(font, Component.literal("§8" + quest.stepCount() + " steps"),
                            innerX + 8, y + ROW_H - 4 - font.lineHeight - 3, SBSTheme.TEXT_MUTED);
                }
                y += ROW_H;
            }
        }

        private String trim(String text, int maxWidth) {
            var font = QuestGuideScreen.this.font;
            if (font.width(text) <= maxWidth) {
                return text;
            }
            return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
        }
    }
}
