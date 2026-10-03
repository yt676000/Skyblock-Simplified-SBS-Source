/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.mining.logic.HotmAdvice;
import sbs.modid.client.skills.mining.logic.HotmAdvisor;
import sbs.modid.client.skills.mining.logic.HotmStrategies;
import sbs.modid.client.skills.mining.logic.HotmTreeStore;
import sbs.modid.client.skills.mining.model.HotmStrategyData;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /sbs hotm}: the Heart of the Mountain perk advisor. Pick a goal; see the next steps ranked
 * by goal-weighted effect per powder, the recommended tree with your progress, what is blocked and
 * what to skip. Display only - nothing here spends powder or clicks a perk.
 *
 * <p>The goal is a segmented switch of the profiles' short names (five fit in about 220 px, inside
 * the 320-wide viewport of 1280x720 at GUI scale 4). If a data edit ever makes them wider than the
 * panel, the switch is replaced by a line telling the player to widen the window rather than drawn
 * over the panel edge. One scrolling list below it, on the shared {@link SciFiScrollbar}.
 */
public final class HotmAdvisorScreen extends Screen {

    private static final int PREFERRED_W = 460;
    private static final int PREFERRED_H = 330;
    private static final int ROW_H = 12;
    private static final int GAP = 6;
    private static final int OK_COLOR = 0xFF57D977;
    private static final int WARN_COLOR = 0xFFE0A14D;

    /** One drawn line: left text, right text, colours, and an optional tooltip. */
    private record Line(String left, String right, int leftColor, int rightColor, List<String> tip) {
        static Line heading(String text, String right) {
            return new Line(text, right, SBSTheme.ACCENT_BRIGHT, SBSTheme.TEXT, List.of());
        }
    }

    private final SciFiScrollbar bar = new SciFiScrollbar();
    private List<Line> lines = List.of();
    private int scroll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int dividerY;
    private int listTop;
    private int listBottom;
    private int footerY;
    private boolean switchFits;

    public HotmAdvisorScreen() {
        super(Component.literal("HotM Perk Advisor"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        // Deferred: when opened from chat, the chat screen closes itself after the command returns.
        minecraft.execute(() -> minecraft.setScreenAndShow(new HotmAdvisorScreen()));
    }

    private static List<String> goalLabels() {
        List<String> labels = new ArrayList<>();
        for (HotmStrategyData.Profile profile : HotmStrategies.profiles()) {
            labels.add(profile.label());
        }
        return labels;
    }

    @Override
    protected void init() {
        int margin = Math.min(SBSTheme.SCREEN_MARGIN, Math.max(4, Math.min(this.width, this.height) / 24));
        panelW = Math.min(Math.max(1, this.width - margin * 2), PREFERRED_W);
        panelH = Math.min(Math.max(1, this.height - margin * 2), PREFERRED_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = Math.min(SBSTheme.PANEL_PADDING, Math.max(4, panelW / 30));
        innerX = panelX + pad;
        contentW = Math.max(1, panelW - pad * 2);
        dividerY = panelY + Math.min(SBSTheme.HEADER_HEIGHT, this.font.lineHeight + 10);

        addRenderableOnly(new PanelRenderable());   // backdrop first, or it is a lid

        int rowY = dividerY + 4;
        int switchH = SBSTheme.SEARCH_HEIGHT;
        List<String> labels = goalLabels();
        switchFits = !labels.isEmpty() && SciFiSegmentedSwitch.widthFor(labels) <= contentW;
        if (switchFits) {
            addRenderableWidget(new SciFiSegmentedSwitch(innerX, rowY, switchH, labels,
                    () -> Math.min(HotmAdvice.goalIndex(), labels.size() - 1),
                    index -> {
                        ConfigManager.getInstance().get().miningHelpers.hotmAdvisorGoal = index;
                        ConfigManager.getInstance().save();
                        rebuild();
                    }));
        }
        listTop = rowY + switchH + 5;
        footerY = panelY + panelH - pad - this.font.lineHeight;
        listBottom = Math.max(listTop + ROW_H, footerY - 4);
        rebuild();
    }

    private void rebuild() {
        List<Line> out = new ArrayList<>();
        HotmAdvisor.Advice advice = HotmAdvice.current();
        HotmStrategyData.Profile goal = HotmAdvice.goal();
        HotmTreeStore store = HotmTreeStore.getInstance();
        if (advice == null || goal == null) {
            out.add(new Line("The HotM data files did not load - see the log.", "", WARN_COLOR, 0, List.of()));
            lines = out;
            return;
        }
        if (!store.known()) {
            out.add(new Line("Open the Heart of the Mountain menu once (scroll to see every tier).",
                    "", WARN_COLOR, 0, List.of()));
        } else if (store.stale()) {
            out.add(new Line("You spent powder since the last reading - reopen the menu.", "",
                    WARN_COLOR, 0, List.of()));
        }

        out.add(Line.heading(goal.name, goal.certainty.toLowerCase(java.util.Locale.ROOT)));
        out.add(Line.heading("Next steps", "effect per powder"));
        List<HotmAdvisor.Step> top = advice.top(5);
        if (top.isEmpty()) {
            out.add(new Line("  Nothing left to take for this goal.", "", SBSTheme.TEXT_MUTED, 0, List.of()));
        }
        for (HotmAdvisor.Step step : top) {
            List<String> tip = new ArrayList<>();
            tip.add(HotmAdvice.title(step));
            String effect = HotmAdvice.effect(step);
            if (!effect.isEmpty()) {
                tip.add(effect);
            }
            tip.add(HotmAdvice.cost(step));
            tip.add(step.core() ? "Core perk for this goal" : "Fill perk for this goal");
            if (!step.note().isEmpty()) {
                tip.add("Note: " + step.note());
            }
            int color = !step.costKnown() && !step.unlock() ? SBSTheme.TEXT_MUTED
                    : step.affordable() ? OK_COLOR : WARN_COLOR;
            out.add(new Line("  " + HotmAdvice.title(step)
                    + (effect.isEmpty() ? "" : ": " + effect), HotmAdvice.cost(step),
                    SBSTheme.TEXT, color, tip));
        }

        out.add(Line.heading("Recommended tree", Math.round(advice.progress() * 100) + "% reached"));
        for (HotmAdvisor.Target target : advice.recommended()) {
            boolean done = target.level() >= target.target();
            out.add(new Line("  " + (target.core() ? "" : "(fill) ") + target.perk().name,
                    Math.min(target.level(), target.target()) + "/" + target.target(),
                    done ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT, done ? OK_COLOR : SBSTheme.TEXT,
                    List.of(target.perk().name, target.reason())));
        }
        if (!advice.blocked().isEmpty()) {
            out.add(Line.heading("Not reachable yet", ""));
            for (HotmAdvisor.Blocked blocked : advice.blocked()) {
                out.add(new Line("  " + blocked.perk().name, blocked.reason(), SBSTheme.TEXT_MUTED,
                        SBSTheme.TEXT_MUTED, List.of()));
            }
        }
        out.add(Line.heading("Skip for this goal", ""));
        for (HotmStrategyData.Pick pick : advice.skip()) {
            var perk = sbs.modid.client.skills.mining.logic.HotmCatalog.byId(pick.perk);
            out.add(new Line("  " + (perk == null ? pick.perk : perk.name), pick.reason, SBSTheme.TEXT_MUTED,
                    SBSTheme.TEXT_MUTED, List.of(pick.reason)));
        }
        lines = out;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visibleRows())));
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        syncBar();
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, lines.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void syncBar() {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visibleRows() * ROW_H,
                lines.size(), visibleRows());
    }

    // ------------------------------------------------------------------ drawing

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = HotmAdvisorScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, Component.literal("HotM Perk Advisor"), panelX + panelW / 2,
                    panelY + (dividerY - panelY - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + contentW, dividerY + 1, SBSTheme.ACCENT);
            if (!switchFits) {
                g.text(font, Component.literal(RowText.fit(font, "Widen the window to change the goal", contentW)),
                        innerX, dividerY + 4 + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
            }

            syncBar();
            boolean scrollable = bar.needed();
            int rowW = contentW - (scrollable ? SciFiScrollbar.WIDTH + 3 : 0);
            Line hovered = null;
            int visible = visibleRows();
            for (int i = 0; i < visible && scroll + i < lines.size(); i++) {
                Line line = lines.get(scroll + i);
                int y = listTop + i * ROW_H;
                int ty = y + (ROW_H - font.lineHeight) / 2;
                if (mouseX >= innerX && mouseX < innerX + rowW && mouseY >= y && mouseY < y + ROW_H) {
                    hovered = line;
                    g.fill(innerX, y, innerX + rowW, y + ROW_H, SBSTheme.CARD_BG_HOVER);
                }
                // The right-hand value is measured first and kept; the label gives way.
                String right = RowText.fit(font, line.right(), rowW / 2);
                int rightW = font.width(right);
                if (!right.isEmpty()) {
                    g.text(font, Component.literal(right), innerX + rowW - rightW, ty, line.rightColor());
                }
                g.text(font, Component.literal(RowText.fit(font, line.left(),
                        Math.max(0, rowW - rightW - (right.isEmpty() ? 0 : GAP)))), innerX, ty, line.leftColor());
            }
            if (scrollable) {
                bar.render(g, scroll, mouseX, mouseY);
            }
            String footer = "Display only · green = affordable · grey = cost unknown, open HotM";
            g.text(font, Component.literal(RowText.fit(font, footer, contentW)), innerX, footerY,
                    SBSTheme.TEXT_MUTED);
            if (hovered != null && !hovered.tip().isEmpty()) {
                List<Component> tip = new ArrayList<>();
                for (String text : hovered.tip()) {
                    tip.add(Component.literal(text));
                }
                g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
            }
        }
    }
}
