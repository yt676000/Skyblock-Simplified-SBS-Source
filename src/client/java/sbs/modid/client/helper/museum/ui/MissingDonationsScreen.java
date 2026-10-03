/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.helper.museum.logic.MissingDonations;
import sbs.modid.client.helper.museum.logic.MissingDonations.Row;
import sbs.modid.client.helper.museum.logic.MissingDonations.Section;
import sbs.modid.client.helper.museum.logic.MissingDonations.Sort;
import sbs.modid.client.helper.museum.model.MuseumCatalog;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /sbs museum}: what is still missing from your museum, per category, cheapest XP first.
 *
 * <p>One scrolling list of lines: a heading per category (its XP left, or which pages still have to
 * be visited before it can say anything), then its missing donations - an armor set is one line.
 * Each line is the icon, the name, and on the right the XP, the price and, when there is room, XP
 * per million coins. The right-hand block is measured from the strings actually drawn and the name
 * gives way ({@code RowText.fit}), never the numbers. The sort is a three-way segmented switch.
 *
 * <p>Sized from the viewport, never to a minimum: at 1280x720 on GUI scale 4 (320x180) the list gets
 * about six lines and the XP-per-coin column is dropped before anything overlaps. The scrollbar is
 * the shared {@link SciFiScrollbar}, fed its box every frame and offered clicks before the rows.
 */
public final class MissingDonationsScreen extends Screen {

    private static final int PREFERRED_W = 460;
    private static final int PREFERRED_H = 330;
    private static final int ROW_H = 18;
    private static final int GAP = 6;
    private static final List<String> SORTS = List.of(
            Sort.XP_PER_COIN.label(), Sort.XP.label(), Sort.PRICE.label());

    /** One drawn line: a category heading (row == null) or a missing donation. */
    private record Line(Section section, Row row) {
    }

    private final SciFiScrollbar bar = new SciFiScrollbar();
    private List<Line> lines = List.of();
    private int xpLeft;
    private int knownCategories;
    private int scroll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int dividerY;
    private int summaryY;
    private int listTop;
    private int listBottom;
    private int footerY;

    public MissingDonationsScreen() {
        super(Component.literal("Missing Donations"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        // Deferred: when opened from chat, the chat screen closes itself after the command returns.
        minecraft.execute(() -> minecraft.setScreenAndShow(new MissingDonationsScreen()));
    }

    private static int sortIndex() {
        int sort = ConfigManager.getInstance().get().museumHelper.sort;
        return sort < 0 || sort >= Sort.values().length ? 0 : sort;
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
        addRenderableWidget(new SciFiSegmentedSwitch(innerX, rowY, switchH, SORTS, MissingDonationsScreen::sortIndex,
                index -> {
                    ConfigManager.getInstance().get().museumHelper.sort = index;
                    ConfigManager.getInstance().save();
                    rebuild();
                }));
        int switchW = SciFiSegmentedSwitch.widthFor(SORTS);
        // The summary sits beside the switch when it fits and under it when it does not.
        summaryY = contentW - switchW - GAP >= this.font.width("XP left: 9999 of 9999") ? -1
                : rowY + switchH + 3;
        listTop = (summaryY < 0 ? rowY + switchH : summaryY + this.font.lineHeight) + 5;
        footerY = panelY + panelH - pad - this.font.lineHeight;
        listBottom = Math.max(listTop + ROW_H, footerY - 4);
        rebuild();
    }

    private void rebuild() {
        List<Line> out = new ArrayList<>();
        xpLeft = 0;
        knownCategories = 0;
        for (Section section : MissingDonations.sections(Sort.values()[sortIndex()])) {
            out.add(new Line(section, null));
            if (section.known()) {
                knownCategories++;
                xpLeft += section.xpLeft();
                for (Row row : section.rows()) {
                    out.add(new Line(section, row));
                }
            }
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
            Font font = MissingDonationsScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, Component.literal("Missing Donations"), panelX + panelW / 2,
                    panelY + (dividerY - panelY - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + contentW, dividerY + 1, SBSTheme.ACCENT);

            String summary = knownCategories == 0 ? "No category seen yet"
                    : "XP left: " + xpLeft + " of " + MuseumCatalog.current().totalXp();
            int sumW = font.width(summary);
            if (summaryY < 0) {
                g.text(font, Component.literal(summary), innerX + contentW - sumW,
                        dividerY + 4 + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT);
            } else {
                g.text(font, Component.literal(RowText.fit(font, summary, contentW)), innerX, summaryY,
                        SBSTheme.TEXT);
            }

            syncBar();
            boolean scrollable = bar.needed();
            int rowW = contentW - (scrollable ? SciFiScrollbar.WIDTH + 3 : 0);
            Line hovered = null;
            int visible = visibleRows();
            for (int i = 0; i < visible && scroll + i < lines.size(); i++) {
                Line line = lines.get(scroll + i);
                int y = listTop + i * ROW_H;
                boolean over = mouseX >= innerX && mouseX < innerX + rowW && mouseY >= y && mouseY < y + ROW_H;
                if (over) {
                    hovered = line;
                    g.fill(innerX, y, innerX + rowW, y + ROW_H, SBSTheme.CARD_BG_HOVER);
                }
                if (line.row() == null) {
                    drawHeading(g, font, line.section(), innerX, y, rowW);
                } else {
                    drawRow(g, font, line.row(), innerX, y, rowW);
                }
            }
            if (scrollable) {
                bar.render(g, scroll, mouseX, mouseY);
            }
            String footer = "Prices from cached market data  ·  ? no price  ·  + some parts unpriced";
            g.text(font, Component.literal(RowText.fit(font, footer, contentW)), innerX, footerY,
                    SBSTheme.TEXT_MUTED);
            if (hovered != null && hovered.row() != null) {
                g.setTooltipForNextFrame(font, tooltip(hovered), Optional.empty(), mouseX, mouseY,
                        SBSTheme.tooltipStyle());
            }
        }
    }

    private void drawHeading(GuiGraphicsExtractor g, Font font, Section section, int x, int y, int w) {
        int ty = y + (ROW_H - font.lineHeight) / 2;
        String right;
        if (section.known()) {
            right = section.rows().isEmpty() ? "complete" : section.xpLeft() + " XP left";
        } else if (section.pages() == 0) {
            right = "not visited yet";
        } else {
            right = "seen " + section.pagesSeen() + "/" + section.pages() + " pages";
        }
        int rightW = font.width(right);
        g.text(font, Component.literal(right), x + w - rightW, ty,
                section.known() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(RowText.fit(font, section.category().label(), w - rightW - GAP)),
                x, ty, SBSTheme.ACCENT_BRIGHT);
        g.fill(x, y + ROW_H - 2, x + w, y + ROW_H - 1, SBSTheme.CARD_BORDER);
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, Row row, int x, int y, int w) {
        ItemStack icon = icon(row);
        if (icon != null && !icon.isEmpty()) {
            g.item(icon, x + 1, y + 1);   // shared stack: drawn, never changed
        }
        int ty = y + (ROW_H - font.lineHeight) / 2;
        String xp = "+" + row.donation().xp() + " XP";
        String price = priceText(row);
        String rate = row.xpPerMillion() < 0 ? "" : String.format(java.util.Locale.ROOT, "%.1f/m",
                row.xpPerMillion());
        int nameX = x + 20;
        int xpW = font.width(xp);
        int priceW = font.width(price);
        int rateW = font.width(rate);
        int right = xpW + GAP + priceW;
        // XP per coin is the column that gives way when the row is narrow; the name comes next.
        boolean showRate = !rate.isEmpty() && w - 20 - right - GAP - rateW >= 60;
        if (showRate) {
            right += GAP + rateW;
        }
        int cursor = x + w - right;
        g.text(font, Component.literal(xp), cursor, ty, 0xFF55FFFF);
        cursor += xpW + GAP;
        g.text(font, Component.literal(price), cursor, ty, row.price() == null ? SBSTheme.TEXT_MUTED : 0xFFFFD166);
        cursor += priceW + GAP;
        if (showRate) {
            g.text(font, Component.literal(rate), cursor, ty, SBSTheme.TEXT_MUTED);
        }
        String name = row.donation().displayName() + (row.higherTierDonated() ? " *" : "");
        g.text(font, Component.literal(RowText.fit(font, name, Math.max(0, w - 20 - right - GAP))), nameX, ty,
                SBSTheme.TEXT);
    }

    private static String priceText(Row row) {
        if (row.price() == null) {
            return "?";
        }
        return NumberDisplay.format(row.price()) + (row.partial() ? "+" : "");
    }

    private static ItemStack icon(Row row) {
        String id = row.donation().set() ? firstPiece(row) : row.donation().key();
        if (id == null) {
            return null;
        }
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
        return SkyBlockItemIcons.getInstance().iconShared(id, entry == null ? null : entry.material, 1);
    }

    /** A set's icon is its helmet when it has one - it is how the museum itself shows the set. */
    private static String firstPiece(Row row) {
        List<String> pieces = row.donation().pieces();
        for (String piece : pieces) {
            if (piece.contains("HELMET") || piece.contains("HAT") || piece.contains("HOOD")) {
                return piece;
            }
        }
        return pieces.isEmpty() ? null : pieces.get(0);
    }

    private static List<Component> tooltip(Line line) {
        Row row = line.row();
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(row.donation().displayName()).withColor(0xFFFFFF));
        tip.add(Component.literal(line.section().category().label()
                + (row.donation().set() ? " · armor set, " + row.donation().pieces().size() + " pieces" : ""))
                .withColor(0xAAAAAA));
        tip.add(Component.literal("Donation XP: +" + row.donation().xp()).withColor(0x55FFFF));
        if (row.price() == null) {
            tip.add(Component.literal("No price in the cached market data").withColor(0xAAAAAA));
        } else {
            tip.add(Component.literal("Price: " + NumberDisplay.format(row.price())
                    + (row.partial() ? "+ (some pieces have no price, so at least this)" : ""))
                    .withColor(0xFFD166));
            tip.add(Component.literal(String.format(java.util.Locale.ROOT, "%.2f XP per million coins",
                    row.xpPerMillion())).withColor(0xAAAAAA));
        }
        if (row.higherTierDonated()) {
            tip.add(Component.literal("* A higher tier is donated - whether it counts for this one is")
                    .withColor(0xAAAAAA));
            tip.add(Component.literal("  not verified, so it stays on the list.").withColor(0xAAAAAA));
        }
        long seenAt = line.section().seenAt();
        if (seenAt > 0) {
            long days = (System.currentTimeMillis() - seenAt) / 86_400_000L;
            tip.add(Component.literal("Museum pages last seen " + (days == 0 ? "today" : days + "d ago"))
                    .withColor(0x777777));
        }
        return tip;
    }
}
