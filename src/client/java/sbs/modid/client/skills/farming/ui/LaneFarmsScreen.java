/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.skills.farming.logic.LaneAreaStore;
import sbs.modid.client.skills.farming.model.Farm;
import sbs.modid.client.skills.farming.model.Lane;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /sbs lane list}: the Lane End Warning's farms, then one farm's lanes.
 *
 * <ul>
 *   <li><b>Farms</b>: rename (type in the name), plot and lane count, "Lanes" opens the farm, "Only"
 *       draws just this farm in the world, Delete (two clicks: the button turns into "Confirm").</li>
 *   <li><b>Lanes</b> of one farm: number, axis, length, ends; width 1-5 (a five-way switch); Delete
 *       (two clicks). A rectangle lane group shows its size instead of a width switch.</li>
 * </ul>
 *
 * <p>The name field's responder only saves; it never rebuilds the widgets, so typing keeps focus.
 * Widgets are rebuilt on discrete actions only: scrolling, a switch pick, a delete, a page change.
 */
public final class LaneFarmsScreen extends Screen {

    private static final int PREFERRED_W = 460;
    private static final int PREFERRED_H = 300;
    private static final int ROW_H = 22;
    private static final int GAP = 4;
    private static final List<String> WIDTHS = List.of("1", "2", "3", "4", "5");

    private final SciFiScrollbar bar = new SciFiScrollbar();
    private int scroll;
    /** The farm whose lanes are shown, or {@code null} on the farm list. */
    private Farm open;
    /** The farm or lane whose Delete was clicked once; the next click on it deletes. */
    private Object confirming;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;
    private int footerY;
    /** Text drawn by the panel per visible row: {x, y, width} and the string. */
    private final List<int[]> rowTextBoxes = new ArrayList<>();
    private final List<String> rowTexts = new ArrayList<>();

    public LaneFarmsScreen() {
        super(Component.literal("Farms"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreenAndShow(new LaneFarmsScreen()));
    }

    private static LaneAreaStore store() {
        return LaneAreaStore.getInstance();
    }

    private int rowCount() {
        return open == null ? store().farms().size() : open.lanes.size();
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
        int headerH = Math.max(Math.min(SBSTheme.HEADER_HEIGHT, this.font.lineHeight + 10), ROW_H - 4);
        listTop = panelY + headerH + 6;
        footerY = panelY + panelH - pad - this.font.lineHeight;
        listBottom = Math.max(listTop + ROW_H, footerY - 4);
        rowTextBoxes.clear();
        rowTexts.clear();

        addRenderableOnly(new PanelRenderable());   // backdrop first, or it is a lid

        if (open != null && !store().farms().contains(open)) {
            open = null;
        }
        if (open != null) {
            String back = "Back";
            addRenderableWidget(new SciFiButton(innerX, panelY + 4, this.font.width(back) + 12, ROW_H - 6,
                    Component.literal(back), () -> show(null)));
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rowCount() - visibleRows())));
        int rowW = contentW - (rowCount() > visibleRows() ? SciFiScrollbar.WIDTH + 3 : 0);
        for (int i = 0; i < visibleRows() && scroll + i < rowCount(); i++) {
            int y = listTop + i * ROW_H;
            if (open == null) {
                farmRow(store().farms().get(scroll + i), y, rowW);
            } else {
                laneRow(open.lanes.get(scroll + i), scroll + i, y, rowW);
            }
        }
    }

    private int deleteWidth() {
        return Math.max(this.font.width("Confirm"), this.font.width("Delete")) + 12;
    }

    private void farmRow(Farm farm, int y, int rowW) {
        int right = innerX + rowW;
        int deleteW = deleteWidth();
        addRenderableWidget(new SciFiButton(right - deleteW, y + 2, deleteW, ROW_H - 4,
                Component.literal(confirming == farm ? "Confirm" : "Delete"), () -> delete(farm)));
        right -= deleteW + GAP;
        int onlyW = this.font.width("Only") + 16;
        addRenderableWidget(new SciFiToggleButton(right - onlyW, y + 2, onlyW, ROW_H - 4, Component.literal("Only"),
                () -> store().onlyFarm() == farm.id,
                () -> store().setOnlyFarm(store().onlyFarm() == farm.id ? 0 : farm.id)));
        right -= onlyW + GAP;
        int lanesW = this.font.width("Lanes") + 12;
        addRenderableWidget(new SciFiButton(right - lanesW, y + 2, lanesW, ROW_H - 4, Component.literal("Lanes"),
                () -> show(farm)));
        right -= lanesW + GAP;
        // The info yields before the name gets unusably short; the name never does.
        String info = farm.plotLabel() + " · " + farm.lanes.size() + " lanes";
        int infoW = this.font.width(info);
        boolean showInfo = right - innerX - infoW - GAP >= 90;
        if (showInfo) {
            right -= infoW + GAP;
            rowTextBoxes.add(new int[] {right + GAP, y + (ROW_H - this.font.lineHeight) / 2, infoW});
            rowTexts.add(info);
        }
        int nameW = Math.max(40, right - innerX);
        addRenderableWidget(SciFiTextField.forRow(innerX, y + 1, nameW, ROW_H - 2, "Name", "Farm name", 32,
                () -> farm.name, value -> store().renameFarm(farm, value)));
    }

    private void laneRow(Lane lane, int index, int y, int rowW) {
        int right = innerX + rowW;
        int deleteW = deleteWidth();
        addRenderableWidget(new SciFiButton(right - deleteW, y + 2, deleteW, ROW_H - 4,
                Component.literal(confirming == lane ? "Confirm" : "Delete"), () -> delete(lane)));
        right -= deleteW + GAP;
        int switchW = SciFiSegmentedSwitch.widthFor(WIDTHS);
        String text;
        if (lane.rows) {
            text = "#" + (index + 1) + " · rows along " + lane.axis() + " · " + lane.length() + " x "
                    + lane.width();
        } else {
            text = "#" + (index + 1) + " · " + lane.axis() + " · " + lane.length() + " blocks · width";
            // The switch is dropped before the text gets unreadable; /sbs lane width still sets it.
            if (right - innerX - switchW - GAP >= 110) {
                addRenderableWidget(new SciFiSegmentedSwitch(right - switchW, y + 2, ROW_H - 4, WIDTHS,
                        () -> Math.max(0, Math.min(4, lane.width() - 1)),
                        pick -> {
                            lane.setWidth(pick + 1);
                            store().changed();
                            rebuildWidgets();
                        }));
                right -= switchW + GAP;
            } else {
                text += " " + lane.width();
            }
        }
        rowTextBoxes.add(new int[] {innerX + 4, y + (ROW_H - this.font.lineHeight) / 2, Math.max(1, right - innerX - 4)});
        rowTexts.add(text);
    }

    private void show(Farm farm) {
        open = farm;
        scroll = 0;
        confirming = null;
        rebuildWidgets();
    }

    private void delete(Object target) {
        if (confirming != target) {
            confirming = target;
        } else {
            if (target instanceof Farm farm) {
                store().removeFarm(farm);
            } else if (target instanceof Lane lane && open != null) {
                store().removeLane(open, lane);
            }
            confirming = null;
        }
        rebuildWidgets();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    private void syncBar() {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visibleRows() * ROW_H,
                rowCount(), visibleRows());
    }

    private void scrollTo(int value) {
        int clamped = Math.max(0, Math.min(value, Math.max(0, rowCount() - visibleRows())));
        if (clamped != scroll) {
            scroll = clamped;
            confirming = null;
            rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        syncBar();
        if (bar.handleClick(event.x(), event.y(), scroll, this::scrollTo)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (bar.handleDrag(event.y(), this::scrollTo)) {
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
        scrollTo(scroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = LaneFarmsScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            // The Back button sits left in the lane view; keep the title clear of it.
            int titleRoom = open == null ? contentW : Math.max(1, contentW - (font.width("Back") + 12 + GAP) * 2);
            String title = RowText.fit(font, open == null ? "Farms" : open.name + " · lanes", titleRoom);
            g.centeredText(font, Component.literal(title), panelX + panelW / 2, panelY + 6, SBSTheme.ACCENT_BRIGHT);
            if (rowCount() == 0) {
                String empty = open == null
                        ? "No farms yet. On the Garden: Lane Start, walk, Lane End (or /sbs lane start / end)."
                        : "No lanes in this farm. Stand on its plot: Lane Start, walk, Lane End.";
                g.text(font, Component.literal(RowText.fit(font, empty, contentW)), innerX, listTop + 4,
                        SBSTheme.TEXT_MUTED);
            }
            for (int i = 0; i < rowTexts.size(); i++) {
                int[] box = rowTextBoxes.get(i);
                g.text(font, Component.literal(RowText.fit(font, rowTexts.get(i), box[2])), box[0], box[1],
                        SBSTheme.TEXT_MUTED);
            }
            syncBar();
            if (bar.needed()) {
                bar.render(g, scroll, mouseX, mouseY);
            }
            String footer = open == null
                    ? store().farms().size() + " farm(s) · Only draws just that farm · Delete asks twice"
                    : open.lanes.size() + " lane(s) · width = blocks across that count as in the lane";
            g.text(font, Component.literal(RowText.fit(font, footer, contentW)), innerX, footerY,
                    SBSTheme.TEXT_MUTED);
        }
    }
}
