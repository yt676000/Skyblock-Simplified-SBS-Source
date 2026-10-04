/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.seymour.logic.ColourAnalyzer;
import sbs.modid.client.helper.seymour.logic.SeymourCollection;
import sbs.modid.client.helper.seymour.logic.SeymourCollection.Line;
import sbs.modid.client.helper.seymour.logic.SeymourCollection.Row;
import sbs.modid.client.helper.seymour.logic.SeymourColour;
import sbs.modid.client.helper.seymour.logic.SeymourPieces.Piece;
import sbs.modid.client.helper.seymour.render.SeymourTooltip;
import sbs.modid.client.helper.storage.StorageOverviewOverlay;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /sbs seymour}: every Seymour piece SBS has seen - the inventory, the worn armor and every
 * captured storage - with its colour, best match, tier, tags and location.
 *
 * <p>Each piece is two text lines: swatch, hex and piece type with the tier and ΔE on the right;
 * the best match and tags below with the location on the right. The right-hand text is measured
 * from the strings drawn and the left-hand text gives way ({@code RowText.fit}).
 *
 * <p>Sized from the viewport, never to a minimum. The sort (four values) and the piece filter (five)
 * are segmented switches side by side when they fit and stacked when they do not: at 1280x720 on
 * GUI scale 4 (320x180) the content column is about 290 px, the filter alone about 190 px and both
 * together about 320, so they stack there and the list keeps three pieces. The search field only
 * re-filters - it never rebuilds the widgets - and the scrollbar is the shared
 * {@link SciFiScrollbar}, fed its box every frame and offered clicks before anything else.
 */
public final class SeymourCollectionScreen extends Screen {

    private static final int PREFERRED_W = 480;
    private static final int PREFERRED_H = 340;
    private static final int GAP = 6;
    private static final int SWATCH = 10;
    private static final List<String> SORTS = sortLabels();
    private static final List<String> FILTERS = List.of("All", "Hat", "Jacket", "Trousers", "Shoes");

    private final SciFiScrollbar bar = new SciFiScrollbar();
    private List<Row> all = List.of();
    private List<Row> shown = List.of();
    private String status = "";
    private List<String> uncaptured = List.of();
    private int scroll;

    private EditBox search;
    private SciFiButton copy;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int dividerY;
    private int searchY;
    private int rowH;
    private int listTop;
    private int listBottom;
    private int footerY;
    private int statusW;

    public SeymourCollectionScreen() {
        super(Component.literal("Seymour Collection"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        // Deferred: when opened from chat, the chat screen closes itself after the command returns.
        minecraft.execute(() -> minecraft.setScreenAndShow(new SeymourCollectionScreen()));
    }

    private static List<String> sortLabels() {
        List<String> out = new ArrayList<>();
        for (SeymourCollection.Sort sort : SeymourCollection.Sort.values()) {
            out.add(sort.label());
        }
        return List.copyOf(out);
    }

    private static int sortIndex() {
        return SeymourCollection.Sort.at(ColourAnalyzer.cfg().sort).ordinal();
    }

    private static int filterIndex() {
        int filter = ColourAnalyzer.cfg().filter;
        return filter < 0 || filter >= FILTERS.size() ? 0 : filter;
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
        rowH = this.font.lineHeight * 2 + 4;
        int controlH = Math.min(SBSTheme.SEARCH_HEIGHT, this.font.lineHeight + 6);

        addRenderableOnly(new PanelRenderable());   // backdrop first, or it is a lid

        searchY = dividerY + 4;
        String previous = search == null ? "" : search.getValue();
        search = new EditBox(this.font, innerX + 4, searchY + (controlH - this.font.lineHeight) / 2,
                contentW - 8, this.font.lineHeight, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search hex, piece, match, tag or place..."));
        search.setValue(previous);
        search.setResponder(query -> refilter());   // re-filter only: never rebuild from a field
        addRenderableWidget(search);

        int switchY = searchY + controlH + 4;
        int sortW = SciFiSegmentedSwitch.widthFor(SORTS);
        int filterW = SciFiSegmentedSwitch.widthFor(FILTERS);
        boolean sideBySide = sortW + GAP + filterW <= contentW;
        addRenderableWidget(new SciFiSegmentedSwitch(innerX, switchY, controlH, SORTS,
                SeymourCollectionScreen::sortIndex, index -> {
                    ColourAnalyzer.cfg().sort = index;
                    ConfigManager.getInstance().save();
                    refilter();
                }));
        int filterX = sideBySide ? innerX + contentW - filterW : innerX;
        int filterY = sideBySide ? switchY : switchY + controlH + 3;
        addRenderableWidget(new SciFiSegmentedSwitch(filterX, filterY, controlH, FILTERS,
                SeymourCollectionScreen::filterIndex, index -> {
                    ColourAnalyzer.cfg().filter = index;
                    ConfigManager.getInstance().save();
                    refilter();
                }));
        listTop = filterY + controlH + 5;

        String copyLabel = "Copy CSV";
        int copyW = this.font.width(copyLabel) + 12;
        footerY = panelY + panelH - pad - controlH;
        copy = new SciFiButton(innerX + contentW - copyW, footerY, copyW, controlH, Component.literal(copyLabel),
                this::copyCsv);
        addRenderableWidget(copy);
        statusW = Math.max(0, contentW - copyW - GAP);
        listBottom = Math.max(listTop + rowH, footerY - 4);
        setInitialFocus(search);
        reload();
    }

    /** Reads the collection again from the storage index and the inventory. */
    private void reload() {
        all = SeymourCollection.collect();
        StorageOverviewOverlay overlay = StorageOverviewOverlay.getInstance();
        uncaptured = overlay.uncapturedPages();
        if (!ConfigManager.getInstance().get().skyblockMenu.previewMode.indexes()) {
            status = "Storage indexing is off (Show Enderchest Preview) - inventory only";
        } else if (!overlay.knowsPages()) {
            status = "Open /storage once to see which pages are not captured yet";
        } else if (uncaptured.isEmpty()) {
            status = "Every storage page is captured";
        } else {
            status = uncaptured.size() + " not captured yet: " + String.join(", ", uncaptured);
        }
        refilter();
    }

    private void refilter() {
        Piece filter = filterIndex() == 0 ? null : Piece.values()[filterIndex() - 1];
        String query = search == null ? "" : search.getValue();
        List<Row> out = new ArrayList<>();
        for (Row row : all) {
            if (SeymourCollection.matches(row.line(), filter, query)) {
                out.add(row);
            }
        }
        out.sort((a, b) -> SeymourCollection.comparator(SeymourCollection.Sort.at(sortIndex()))
                .compare(a.line(), b.line()));
        shown = out;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - visibleRows())));
    }

    private void copyCsv() {
        List<Line> lines = new ArrayList<>(shown.size());
        for (Row row : shown) {
            lines.add(row.line());
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(SeymourCollection.toCsv(lines));
        copy.setMessage(Component.literal("Copied " + lines.size()));
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / rowH);
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
        int max = Math.max(0, shown.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void syncBar() {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visibleRows() * rowH, shown.size(),
                visibleRows());
    }

    // ------------------------------------------------------------------ drawing

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = SeymourCollectionScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, Component.literal(RowText.fit(font, "Seymour Collection · " + all.size()
                            + (all.size() == 1 ? " piece" : " pieces"), contentW)), panelX + panelW / 2,
                    panelY + (dividerY - panelY - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + contentW, dividerY + 1, SBSTheme.ACCENT);

            int controlH = Math.min(SBSTheme.SEARCH_HEIGHT, font.lineHeight + 6);
            SciFiRender.roundedRect(g, innerX, searchY, contentW, controlH, SBSTheme.CORNER_RADIUS,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            syncBar();
            boolean scrollable = bar.needed();
            int rowW = contentW - (scrollable ? SciFiScrollbar.WIDTH + 3 : 0);
            Row hovered = null;
            if (shown.isEmpty()) {
                String empty = all.isEmpty() ? "No Seymour piece seen yet - open the storages that hold them"
                        : "Nothing matches the search and filter";
                g.text(font, Component.literal(RowText.fit(font, empty, contentW)), innerX, listTop + 4,
                        SBSTheme.TEXT_MUTED);
            }
            int visible = visibleRows();
            for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
                Row row = shown.get(scroll + i);
                int y = listTop + i * rowH;
                boolean over = mouseX >= innerX && mouseX < innerX + rowW && mouseY >= y && mouseY < y + rowH;
                if (over) {
                    hovered = row;
                    g.fill(innerX, y, innerX + rowW, y + rowH, SBSTheme.CARD_BG_HOVER);
                }
                drawRow(g, font, row, innerX, y, rowW);
            }
            if (scrollable) {
                bar.render(g, scroll, mouseX, mouseY);
            }

            int statusY = footerY + (controlH - font.lineHeight) / 2;
            g.text(font, Component.literal(RowText.fit(font, status, statusW)), innerX, statusY,
                    SBSTheme.TEXT_MUTED);
            boolean overStatus = mouseX >= innerX && mouseX < innerX + statusW
                    && mouseY >= footerY && mouseY < footerY + controlH;
            if (hovered != null) {
                g.setTooltipForNextFrame(font, tooltip(hovered.line()), Optional.empty(), mouseX, mouseY,
                        SBSTheme.tooltipStyle());
            } else if (overStatus && !uncaptured.isEmpty()) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal("Not captured yet - open each once:").withColor(0xFFFFFF));
                for (String page : uncaptured) {
                    tip.add(Component.literal(page).withColor(0xAAAAAA));
                }
                g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
            }
        }
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, Row row, int x, int y, int w) {
        Line line = row.line();
        g.item(row.icon(), x + 1, y + (rowH - 16) / 2);   // the stored stack: drawn, never changed
        int swatchX = x + 20;
        int swatchY = y + (rowH - SWATCH) / 2;
        int textX = swatchX + SWATCH + 5;
        int textW = Math.max(0, x + w - textX);
        int top = y + 2;
        int bottom = top + font.lineHeight + 1;

        SeymourColour.Analysis analysis = line.analysis();
        if (analysis != null) {
            g.fill(swatchX - 1, swatchY - 1, swatchX + SWATCH + 1, swatchY + SWATCH + 1, SBSTheme.CARD_BORDER);
            g.fill(swatchX, swatchY, swatchX + SWATCH, swatchY + SWATCH, 0xFF000000 | line.rgb());
        }

        // Line 1: hex and piece left, tier and ΔE right.
        String right1;
        int color1;
        if (analysis == null) {
            right1 = line.dyeItem().isEmpty() ? "no colour" : "dyed";
            color1 = SBSTheme.TEXT_MUTED;
        } else {
            SeymourColour.Match best = analysis.best();
            right1 = analysis.tier().label() + (best == null ? "" : " · ΔE " + SeymourColour.formatDeltaE(best.deltaE()));
            color1 = 0xFF000000 | SeymourTooltip.tierColor(analysis.tier());
        }
        int right1W = font.width(right1);
        g.text(font, Component.literal(right1), x + w - right1W, top, color1);
        String left1 = (analysis == null ? "#------" : SeymourColour.hex(line.rgb())) + "  " + line.piece().shortName();
        g.text(font, Component.literal(RowText.fit(font, left1, Math.max(0, textW - right1W - GAP))), textX, top,
                SBSTheme.TEXT);

        // Line 2: best match and tags left, location right (the location yields first past 40 %).
        String location = RowText.fit(font, line.location(), Math.max(0, textW * 2 / 5));
        int locationW = font.width(location);
        g.text(font, Component.literal(location), x + w - locationW, bottom, SBSTheme.TEXT_MUTED);
        String left2;
        if (analysis == null) {
            left2 = line.dyeItem().isEmpty() ? "No colour on this piece"
                    : "Dyed with " + SeymourTooltip.dyeName(line.dyeItem());
        } else {
            SeymourColour.Match best = analysis.best();
            List<String> parts = new ArrayList<>();
            parts.add(best == null ? "no targets loaded" : "≈ " + best.target().name());
            parts.addAll(analysis.tagLabels());
            left2 = String.join(" · ", parts);
        }
        g.text(font, Component.literal(RowText.fit(font, left2, Math.max(0, textW - locationW - GAP))), textX,
                bottom, 0xFFAAAAAA);
    }

    private static List<Component> tooltip(Line line) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(line.name()).withColor(0xFFFFFF));
        SeymourColour.Analysis analysis = line.analysis();
        if (analysis == null) {
            tip.add(Component.literal(line.dyeItem().isEmpty() ? "No colour on this piece"
                    : "Dyed with " + SeymourTooltip.dyeName(line.dyeItem()) + ": original hex not readable")
                    .withColor(0xAAAAAA));
        } else {
            tip.add(Component.literal("■ ").withColor(line.rgb())
                    .append(Component.literal("Hex " + SeymourColour.hex(line.rgb())).withColor(0xFFFFFF)));
            tip.add(Component.literal("Tier: " + analysis.tier().label())
                    .withColor(SeymourTooltip.tierColor(analysis.tier())));
            int rank = 1;
            for (SeymourColour.Match match : analysis.matches()) {
                tip.add(Component.literal(rank++ + ". " + match.target().name() + " · ΔE "
                        + SeymourColour.formatDeltaE(match.deltaE()) + " · "
                        + (match.target().kind() == sbs.modid.client.helper.seymour.model.ColourTarget.Kind.DYE
                        ? "dye" : "armor") + " " + SeymourColour.hex(match.target().rgb()))
                        .withColor(0xAAAAAA));
            }
            if (!analysis.matches().isEmpty()) {
                tip.add(Component.literal("Target colours " + analysis.matches().get(0).target().certainty()
                        .displayName() + " in game").withColor(0x777777));
            }
            if (!analysis.tagLabels().isEmpty()) {
                tip.add(Component.literal(String.join(" · ", analysis.tagLabels())).withColor(0xFFAA00));
            }
        }
        tip.add(Component.literal("In: " + line.location()).withColor(0xAAAAAA));
        long minutes = (System.currentTimeMillis() - line.seenAt()) / 60_000L;
        tip.add(Component.literal(minutes < 1 ? "Seen just now"
                : minutes < 120 ? "Seen " + minutes + " min ago"
                : "Seen " + (minutes / 60 < 48 ? minutes / 60 + " h ago" : minutes / 1440 + " d ago"))
                .withColor(0x777777));
        return tip;
    }
}
