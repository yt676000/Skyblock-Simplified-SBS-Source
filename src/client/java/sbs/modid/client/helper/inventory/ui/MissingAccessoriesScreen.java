/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.recipe.logic.PriceEstimator;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.helper.inventory.logic.AccessoryCatalog;
import sbs.modid.client.helper.inventory.logic.AccessoryIndex;
import sbs.modid.client.helper.inventory.logic.AccessoryProgress;
import sbs.modid.client.helper.inventory.model.MagicalPower;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * "Which accessories am I still missing" - the whole catalogue as a grid, with what is held lit,
 * what is genuinely absent marked, and what an owned higher tier has already replaced kept out of
 * the way.
 *
 * <p><b>The screen is honest about what it has seen.</b> The client can only learn a bag's contents
 * by being shown them, so until every page has been read the header says so and the missing count
 * is labelled as an upper bound. A confident "you are missing 340 accessories" to somebody who has
 * simply not opened their bag yet would be the single easiest way to make this feature useless.
 *
 * <p>Magical Power figures carry the certainty of the table behind them
 * ({@link MagicalPower}) and are labelled as predictions - Hypixel publishes rarities, not power.
 *
 * <p><b>Layout is measured, never assumed</b> - see {@code ui/AGENTS.md}. The filter and sort
 * controls are segmented switches because they have more than two values; they wrap to a second row
 * when they do not fit side by side, the search field takes whatever is left or drops to its own
 * row, and the header's right-aligned figure moves to its own line rather than colliding with the
 * left one. Every one of those decisions is taken from a measured width at the current GUI scale.
 */
public final class MissingAccessoriesScreen extends Screen {

    /** Grid cell: a 16px icon on a card, with room for the state pip. */
    private static final int CELL = 22;

    /** Colour of the dark veil over an accessory that is not held. */
    private static final int MISSING_VEIL = 0xB00A1626;

    /** Widest the panel is allowed to get; the viewport is the ceiling, this is only a preference. */
    private static final int PREFERRED_W = 700;
    private static final int PREFERRED_H = 480;

    /** Below this the search field is not worth putting beside the switches. */
    private static final int MIN_SEARCH_W = 90;

    private static final int GAP = 6;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;
    private int summaryY;
    private int columns;

    /** Search field frame, stored because the panel draws it and the widget only draws the text. */
    private int searchX;
    private int searchY;
    private int searchW;

    private EditBox search;

    /** Everything, state resolved: recomputed on open and whenever the scope toggles change. */
    private List<AccessoryProgress.Row> all = List.of();
    private AccessoryProgress.Summary summary =
            new AccessoryProgress.Summary(0, 0, 0, 0, 0, 0, 0);

    /** {@link #all} narrowed to the active filter and query - what the grid draws. */
    private List<AccessoryProgress.Row> shown = List.of();

    // The header strings are built once and then both measured and drawn, so what the layout
    // reserved space for and what is painted can never be two different strings.
    /** Left-column header lines, most important first - the tail is dropped when height is short. */
    private List<String> summaryLines = List.of();

    /** Right-aligned on the first header line when it fits there, else {@code null}. */
    private String summaryRight;

    private int scrollRows;
    private int scrollMax;

    public MissingAccessoriesScreen() {
        super(Component.literal("Accessories"));
    }

    /** The single entry point, so the command, the keybind and the bag button cannot diverge. */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreenAndShow(new MissingAccessoriesScreen()));
    }

    private static SBSConfig.AccessoryBagSettings cfg() {
        return ConfigManager.getInstance().get().accessoryBag;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private static List<String> labels(Object[] values) {
        List<String> out = new ArrayList<>(values.length);
        for (Object value : values) {
            out.add(value instanceof AccessoryProgress.Filter f ? f.displayName()
                    : ((AccessoryProgress.Sort) value).displayName());
        }
        return out;
    }

    @Override
    protected void init() {
        // The viewport is the ceiling, never the floor: clamping up to a minimum is what pushes a
        // panel off both edges of a small window at a large GUI scale.
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, PREFERRED_W);
        panelH = Math.min(availableH, PREFERRED_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = Math.max(1, panelW - pad * 2);
        columns = Math.max(1, (contentW - 6) / CELL);

        // The panel is the backdrop, so it is registered FIRST: renderables draw in the order they
        // were added, and a background added after the widgets is a background painted over them -
        // which is exactly what it did, hiding the filter, the sort and the search field.
        addRenderableOnly(new PanelRenderable());

        int rowH = SBSTheme.SEARCH_HEIGHT;
        int textH = this.font.lineHeight;
        int rowY = dividerY + SBSTheme.GAP_AFTER_HEADER;

        List<String> filterLabels = labels(AccessoryProgress.Filter.values());
        List<String> sortLabels = labels(AccessoryProgress.Sort.values());
        int filterW = SciFiSegmentedSwitch.widthFor(filterLabels);
        int sortW = SciFiSegmentedSwitch.widthFor(sortLabels);

        // Both switches on one row if they fit; otherwise stacked. Either is fine - what is not
        // fine is drawing them on top of each other because the labels grew.
        boolean sideBySide = filterW + GAP + sortW <= contentW;
        addRenderableWidget(new SciFiSegmentedSwitch(innerX, rowY, rowH, filterLabels,
                () -> filter().ordinal(),
                index -> selectFilter(AccessoryProgress.Filter.values()[index])));
        int usedW;
        if (sideBySide) {
            addRenderableWidget(new SciFiSegmentedSwitch(innerX + filterW + GAP, rowY, rowH,
                    sortLabels, () -> sort().ordinal(),
                    index -> selectSort(AccessoryProgress.Sort.values()[index])));
            usedW = filterW + GAP + sortW;
        } else {
            rowY += rowH + 4;
            addRenderableWidget(new SciFiSegmentedSwitch(innerX, rowY, rowH, sortLabels,
                    () -> sort().ordinal(),
                    index -> selectSort(AccessoryProgress.Sort.values()[index])));
            usedW = sortW;
        }

        // The search field takes what is left beside the switches, or a row of its own when that
        // would be too narrow to type in.
        int beside = contentW - usedW - GAP;
        if (beside >= MIN_SEARCH_W) {
            searchX = innerX + usedW + GAP;
            searchY = rowY;
            searchW = beside;
        } else {
            rowY += rowH + 4;
            searchX = innerX;
            searchY = rowY;
            searchW = contentW;
        }
        search = new EditBox(this.font, searchX + GAP, searchY + (rowH - textH) / 2,
                Math.max(1, searchW - GAP * 2), textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search..."));
        search.setResponder(query -> narrow());
        addRenderableWidget(search);
        setInitialFocus(search);

        summaryY = rowY + rowH + 5;
        listBottom = panelY + panelH - pad - textH - 4;

        recompute();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private AccessoryProgress.Filter filter() {
        try {
            return AccessoryProgress.Filter.valueOf(cfg().missingFilter);
        } catch (IllegalArgumentException | NullPointerException bad) {
            return AccessoryProgress.Filter.MISSING;
        }
    }

    private AccessoryProgress.Sort sort() {
        return AccessoryProgress.Sort.byName(cfg().missingSort);
    }

    private void selectFilter(AccessoryProgress.Filter value) {
        if (value == filter()) {
            return;
        }
        cfg().missingFilter = value.name();
        save();
        narrow();
    }

    private void selectSort(AccessoryProgress.Sort value) {
        if (value == sort()) {
            return;
        }
        cfg().missingSort = value.name();
        save();
        narrow();
    }

    /** Re-resolves every accessory's state. Only needed when the scope or the bag changes. */
    private void recompute() {
        all = AccessoryProgress.rows(new AccessoryProgress.Scope(
                cfg().includeRift, cfg().includeSuperseded));
        summary = AccessoryProgress.summarise(all);
        layoutSummary();
        narrow();
    }

    /**
     * Builds the header strings and reserves exactly the vertical space they need.
     *
     * <p>Two measurements decide the shape, both taken at the current GUI scale:
     * <ul>
     *   <li><b>Width</b> - the counts and the Magical Power share a line only when both actually fit
     *       on it. Right-aligning the power into a fixed two-line header is how the two collided.</li>
     *   <li><b>Height</b> - the header is trimmed from the least important end until at least one
     *       row of the grid survives. On a small window at a large GUI scale there is genuinely not
     *       room for everything, and a header that keeps its space by pushing the grid outside the
     *       panel is the worse answer.</li>
     * </ul>
     * The coverage caveat outranks the power figure on purpose: it is what qualifies the counts
     * directly above it, so dropping it would leave a number reading as more certain than it is.
     */
    private void layoutSummary() {
        AccessoryIndex index = AccessoryIndex.getInstance();
        boolean complete = index.complete();

        String counts = "§7Owned §a" + summary.owned() + "§8/§7" + summary.total()
                + "  §7Missing " + (complete ? "§c" : "§e≤") + summary.missing();
        if (summary.superseded() > 0) {
            counts += "  §8" + summary.superseded() + " upgraded";
        }
        String power = "§7MP §d" + NumberDisplay.format(summary.powerOwned())
                + " §8(+" + NumberDisplay.format(summary.powerAvailable()) + " available)";

        String coverage;
        if (!AccessoryCatalog.loaded()) {
            coverage = "§cAccessory catalogue unavailable - nothing to compare against.";
        } else if (index.pagesSeen() == 0) {
            coverage = "§eOpen your Accessory Bag once so its contents can be read.";
        } else if (!index.pageCountKnown()) {
            // One page recorded, but the bag never numbered itself - so we cannot tell a
            // single-page bag from several pages recorded on top of each other. Say exactly that.
            coverage = "§eRead 1 page; the bag did not say how many it has. "
                    + "Flip through every page to be sure.";
        } else if (!complete) {
            coverage = "§eRead " + index.pagesSeen() + " of " + index.pagesTotal()
                    + " pages - flip through the rest for an exact list.";
        } else {
            coverage = "§8All " + index.pagesTotal() + " bag pages read"
                    + (summary.unknownPower() > 0
                    ? " · " + summary.unknownPower() + " with no known MP" : "");
        }

        // Least-important last, so trimming the tail drops the power figure before the caveat.
        List<String> wanted = new ArrayList<>(3);
        wanted.add(counts);
        boolean sideBySide = font.width(counts) + GAP * 2 + font.width(power) <= contentW;
        summaryRight = sideBySide ? power : null;
        wanted.add(coverage);
        if (!sideBySide) {
            wanted.add(power);
        }

        int lineH = font.lineHeight + 1;
        int room = listBottom - summaryY;
        // Keep one grid row if the panel can possibly spare it; never fewer than the counts line.
        int fits = Math.max(1, (room - CELL - 2) / lineH);
        summaryLines = List.copyOf(wanted.subList(0, Math.min(wanted.size(), fits)));
        listTop = summaryY + summaryLines.size() * lineH + 2;
    }

    /** Re-applies the filter, the query and the sort. Cheap enough to run on every keystroke. */
    private void narrow() {
        List<AccessoryProgress.Row> rows = AccessoryProgress.filter(all, filter(),
                search == null ? "" : search.getValue());
        AccessoryProgress.sort(rows, sort());
        shown = rows;
        scrollRows = 0;
    }

    /** The row under the given mouse position, or {@code null}. */
    private AccessoryProgress.Row rowAt(double mx, double my) {
        if (mx < innerX || mx >= innerX + columns * CELL || my < listTop || my >= listBottom) {
            return null;
        }
        int col = (int) ((mx - innerX) / CELL);
        int row = scrollRows + (int) ((my - listTop) / CELL);
        int index = row * columns + col;
        return index >= 0 && index < shown.size() ? shown.get(index) : null;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // The two scope toggles live on the footer line as checkboxes. Hit-tested from the same
        // measured widths the footer draws with, so the target is always where the text is.
        int footerY = listBottom + 2;
        if (event.y() >= footerY && event.y() <= footerY + font.lineHeight) {
            int riftW = font.width(riftLabel());
            if (event.x() >= innerX && event.x() < innerX + riftW) {
                cfg().includeRift = !cfg().includeRift;
                save();
                recompute();
                return true;
            }
            int supX = innerX + riftW + 10;
            if (event.x() >= supX && event.x() < supX + font.width(supersededLabel())) {
                cfg().includeSuperseded = !cfg().includeSuperseded;
                save();
                recompute();
                return true;
            }
        }
        return rowAt(event.x(), event.y()) != null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (sbs.modid.client.helper.tooltip.ScrollableTooltips.getInstance().onMouseScroll(scrollY)) {
            return true;
        }
        if (scrollY != 0) {
            scrollRows = clamp(scrollRows - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (cfg().openKey != 0 && event.key() == cfg().openKey && !search.isFocused()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** {@code text} shortened with an ellipsis so it fits {@code room} pixels. */
    private static String fit(Font font, String text, int room) {
        if (room <= 0) {
            return "";
        }
        if (font.width(text) <= room) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, room - font.width("...")), false) + "...";
    }

    private String riftLabel() {
        return (cfg().includeRift ? "§a[x] " : "§8[ ] ") + "Rift";
    }

    private String supersededLabel() {
        return (cfg().includeSuperseded ? "§a[x] " : "§8[ ] ") + "Upgraded tiers";
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = MissingAccessoriesScreen.this.font;
            g.fill(0, 0, MissingAccessoriesScreen.this.width, MissingAccessoriesScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Accessories"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            SciFiRender.roundedRectWithBorder(g, searchX, searchY, searchW, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawSummary(g);
            drawGrid(g, mouseX, mouseY);
            drawFooter(g);
        }

        /**
         * The counts, the Magical Power and the coverage caveat, in the space
         * {@link #layoutSummary()} measured for them. The caveat is not optional decoration - it is
         * what stops the missing count being read as a fact when half the bag has never been seen.
         */
        private void drawSummary(GuiGraphicsExtractor g) {
            var font = MissingAccessoriesScreen.this.font;
            int y = summaryY;
            for (int i = 0; i < summaryLines.size(); i++) {
                // Line 0 may carry the right-aligned power figure; it only ever gets there when it
                // was measured to fit, so the two can never meet in the middle.
                int room = contentW;
                if (i == 0 && summaryRight != null) {
                    int rightW = font.width(summaryRight);
                    g.text(font, Component.literal(summaryRight),
                            innerX + contentW - rightW, y, SBSTheme.TEXT);
                    room -= rightW + GAP;
                }
                g.text(font, Component.literal(fit(font, summaryLines.get(i), room)), innerX, y,
                        i == 0 ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
                y += font.lineHeight + 1;
            }
        }

        private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = MissingAccessoriesScreen.this.font;
            // A window too small to hold even one row: say so instead of drawing a row of cells
            // hanging out of the bottom of the panel. Reachable at a large GUI scale on a small
            // window, where the header alone already fills the panel.
            if (listBottom - listTop < CELL) {
                g.centeredText(font, Component.literal(fit(font,
                                "§7Not enough room - lower the GUI scale.", contentW)),
                        panelX + panelW / 2, Math.max(listTop, listBottom - font.lineHeight),
                        SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            if (shown.isEmpty()) {
                String empty = !AccessoryCatalog.loaded() ? "§7No catalogue loaded."
                        : filter() == AccessoryProgress.Filter.MISSING
                        ? "§aNothing missing here." : "§7Nothing matches.";
                g.centeredText(font, Component.literal(fit(font, empty, contentW)),
                        panelX + panelW / 2, listTop + 12, SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            int visibleRows = Math.max(1, (listBottom - listTop) / CELL);
            int totalRows = (shown.size() + columns - 1) / columns;
            scrollMax = Math.max(0, totalRows - visibleRows);
            scrollRows = clamp(scrollRows, 0, scrollMax);

            AccessoryProgress.Row hovered = null;
            int start = scrollRows * columns;
            int end = Math.min(shown.size(), start + visibleRows * columns);
            for (int i = start; i < end; i++) {
                int cellIndex = i - start;
                int x = innerX + (cellIndex % columns) * CELL;
                int y = listTop + (cellIndex / columns) * CELL;
                AccessoryProgress.Row row = shown.get(i);
                boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                if (hover) {
                    hovered = row;
                }
                drawCell(g, row, x, y, hover);
            }

            if (totalRows > visibleRows) {
                int trackH = visibleRows * CELL;
                g.fill(innerX + contentW - 3, listTop, innerX + contentW, listTop + trackH,
                        SBSTheme.CARD_BG_DISABLED);
                int thumbH = Math.max(8, trackH * visibleRows / totalRows);
                int thumbY = listTop
                        + (int) ((long) (trackH - thumbH) * scrollRows / Math.max(1, scrollMax));
                g.fill(innerX + contentW - 3, thumbY, innerX + contentW, thumbY + thumbH, SBSTheme.ACCENT);
            }
            if (hovered != null) {
                g.setTooltipForNextFrame(font, tooltip(hovered), Optional.empty(),
                        mouseX, mouseY, SBSTheme.tooltipStyle());
            }
        }

        /**
         * One cell: the real item icon on a rarity-bordered card. A held accessory reads in full
         * colour; anything not held keeps its icon under a dark veil, so the list stays scannable as
         * "what does this thing even look like" rather than becoming a wall of empty boxes.
         */
        private void drawCell(GuiGraphicsExtractor g, AccessoryProgress.Row row, int x, int y,
                              boolean hover) {
            boolean owned = row.owned();
            SciFiRender.roundedRectWithBorder(g, x, y, CELL - 2, CELL - 2, 2,
                    hover ? SBSTheme.CARD_BG_HOVER
                            : owned ? SBSTheme.CARD_BG : SBSTheme.CARD_BG_DISABLED,
                    hover ? SBSTheme.ACCENT_BRIGHT : MagicalPower.argb(row.effectiveTier()));

            ItemStack icon = SkyBlockItemIcons.getInstance().icon(row.accessory().id, null, 1);
            if (icon.is(Items.BARRIER)) {
                icon = new ItemStack(Items.GOLD_NUGGET);
            }
            g.item(icon, x + (CELL - 2 - 16) / 2, y + 1);
            if (!owned) {
                g.fill(x + 1, y + 1, x + CELL - 3, y + CELL - 3, MISSING_VEIL);
            }

            // Corner pip: purple = recombobulated, amber = an upgrade is available, grey = the tier
            // has been superseded. Owned-and-finished needs no mark; that is the quiet default.
            // Shape as well as colour carries it - the duplicate mark sits in the opposite corner -
            // because colour alone is not a signal every player can read.
            int pip = row.recombobulated() ? 0xFFD060E0
                    : row.upgradable() ? 0xFFFFAA00
                    : row.state() == AccessoryProgress.State.SUPERSEDED ? 0xFF6E7C90 : 0;
            if (pip != 0) {
                g.fill(x + CELL - 6, y, x + CELL - 2, y + 3, pip);
            }
            if (row.copies() > 1) {
                // Duplicates are dead weight in the bag; the bag highlighter says so in the menu and
                // this says so here, so the two features agree.
                g.fill(x, y + CELL - 5, x + 3, y + CELL - 2, 0xFFE0605F);
            }
        }

        private List<Component> tooltip(AccessoryProgress.Row row) {
            var accessory = row.accessory();
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(MagicalPower.colorCode(row.effectiveTier())
                    + accessory.displayName()));
            tip.add(Component.literal("§7" + MagicalPower.pretty(row.effectiveTier())
                    + (row.recombobulated() ? " §d(recombobulated)" : "")));

            // The MP line carries its own certainty, because the number is predicted from rarity and
            // never read from the game.
            var power = row.power();
            tip.add(Component.literal("§7Magical Power §d" + power.display()
                    + (power.known() ? " §8(" + power.certainty().displayName() + ")" : "")));

            tip.add(Component.literal(" "));
            switch (row.state()) {
                case OWNED -> tip.add(Component.literal("§aOwned"
                        + (row.copies() > 1 ? " §8x" + row.copies() + " (duplicates give nothing)" : "")));
                case SUPERSEDED -> tip.add(Component.literal("§8Replaced by §7"
                        + row.replacement().displayName()));
                case MISSING -> tip.add(Component.literal("§cMissing"));
                default -> {
                }
            }
            if (row.upgradable()) {
                var upgrade = row.upgrade();
                var gain = MagicalPower.of(upgrade.tier);
                tip.add(Component.literal("§6Upgrades to §f" + upgrade.displayName()
                        + (gain.known() && power.known()
                        ? " §8(+" + (gain.power() - power.power()) + " MP)" : "")));
            }
            Long price = PriceEstimator.getInstance().buyPrice(accessory.id);
            if (price != null && price > 0 && row.state() != AccessoryProgress.State.OWNED) {
                tip.add(Component.literal("§7Buy §6" + NumberDisplay.format(price)));
            }
            if (accessory.rift()) {
                tip.add(Component.literal("§5Rift only"));
            }
            if (accessory.soulbound()) {
                tip.add(Component.literal("§8Soulbound ("
                        + accessory.soulbound.toLowerCase(Locale.ROOT) + ")"));
            }
            tip.add(Component.literal("§8" + accessory.id));
            return tip;
        }

        /**
         * The two scope toggles on the left, the catalogue's provenance on the right - and the
         * provenance is dropped rather than overlapped when the row is too narrow for both.
         */
        private void drawFooter(GuiGraphicsExtractor g) {
            var font = MissingAccessoriesScreen.this.font;
            int y = listBottom + 2;
            String rift = riftLabel();
            String superseded = supersededLabel();
            g.text(font, Component.literal(rift), innerX, y, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(superseded), innerX + font.width(rift) + 10, y,
                    SBSTheme.TEXT_MUTED);

            int used = font.width(rift) + 10 + font.width(superseded);
            int room = contentW - used - GAP * 2;
            if (room < 40) {
                return;
            }
            String status = fit(font, "§8" + AccessoryCatalog.status(), room);
            g.text(font, Component.literal(status), innerX + contentW - font.width(status), y,
                    SBSTheme.TEXT_MUTED);
        }
    }
}
