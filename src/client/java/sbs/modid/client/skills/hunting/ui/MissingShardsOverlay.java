/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.hunting.logic.AttributeMenuReader;
import sbs.modid.client.skills.hunting.logic.ShardBazaar;
import sbs.modid.client.skills.hunting.logic.ShardCatalog;
import sbs.modid.client.skills.hunting.logic.ShardOwnership;
import sbs.modid.client.skills.hunting.logic.ShardProgress;
import sbs.modid.client.skills.hunting.model.ShardPriceSource;
import sbs.modid.client.skills.hunting.model.ShardRarity;
import sbs.modid.client.skills.hunting.model.ShardSort;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The list beside an open Attribute Menu: every shard still to collect, what it costs, and cheapest
 * first.
 *
 * <p>It answers the question the menu itself cannot. Hypixel shows what you have; the shopping list
 * is the complement of that against everything the Bazaar trades, priced live - and the complement is
 * exactly what no menu can display.
 *
 * <p><b>It never touches a slot.</b> The panel is drawn outside the menu and takes clicks only inside
 * its own rectangle. A click on an entry is still Hypixel's click and reaches the server untouched:
 * repurposing one would either buy something the player did not choose or swallow something they did.
 *
 * <p><b>Nothing is stated more confidently than it is known.</b> Entries no reader could parse are
 * left out of the list and counted in the footer; amounts assumed from the rarity are drawn with the
 * {@link Certainty} tag those numbers carry; unpriced rows show a dash and sort last rather than as
 * zero; and the footer says how many of the catalogue this profile has actually been seen to own,
 * so a first-run list never reads as a claim that everything is missing.
 */
public final class MissingShardsOverlay {

    private static final MissingShardsOverlay INSTANCE = new MissingShardsOverlay();

    private static final int HEADER_H = 15;
    private static final int PAD = 5;
    private static final int MARGIN = 2;
    private static final int PREFERRED_W = 252;
    private static final int MAX_HEIGHT = 260;
    private static final int MIN_BAR_W = 118;
    private static final int ROW_H = 11;
    private static final int CONTROL_H = 13;
    private static final int GAP_TO_CONTAINER = 8;

    /** How long a computed list is reused. Rows are derived, not read - never per frame. */
    private static final long RECOMPUTE_MS = 500L;

    /** Direction labels. Two values, so a segmented switch and never a toggle - they are not on/off. */
    private static final List<String> DIRECTIONS = List.of("Asc", "Desc");

    private int posX = Integer.MIN_VALUE;
    private int posY;
    private int panelW = PREFERRED_W;
    private int panelH = MAX_HEIGHT;

    private boolean minimized;
    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private int scroll;

    private final SciFiScrollbar bar = new SciFiScrollbar();
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.MISSING_SHARDS);

    private SciFiSegmentedSwitch sortSwitch;
    private SciFiSegmentedSwitch directionSwitch;

    /** Collapsed-bar rectangle from the last frame, so clicks hit exactly what was drawn. */
    private int minBarX;
    private int minBarY;
    private int minBarW;
    private int minBarH;

    /** The list area from the last frame, and the rows drawn in it. */
    private int listTop;
    private int listBottom;
    private List<ShardProgress.Row> visibleRows = List.of();

    private List<ShardProgress.Row> rows = List.of();
    private ShardProgress.Summary summary;
    private int seenCount;
    private long computedAt;
    private boolean stale = true;

    private MissingShardsOverlay() {
    }

    public static MissingShardsOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.HuntingSettings cfg() {
        return ConfigManager.getInstance().get().hunting;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private static ShardSort sort() {
        return ShardSort.byName(cfg().missingShardsSort);
    }

    private static ShardPriceSource source() {
        return ShardPriceSource.byName(cfg().missingShardsPriceSource);
    }

    /** Whether the panel should be on screen for this container at all. */
    private boolean active(AbstractContainerScreen<?> container) {
        return cfg().missingShards && cfg().missingShardsPanel
                && AttributeMenuReader.getInstance().isOpen(container);
    }

    // ------------------------------------------------------------------
    // The derived list
    // ------------------------------------------------------------------

    /**
     * Recomputes the rows at most twice a second.
     *
     * <p>The inputs move on their own - the menu is re-read on a page flip, the Bazaar map is rebuilt
     * every 60s - so a panel that only recomputed on a click would go quietly stale. A panel that
     * recomputed per frame would join a 320-row catalogue sixty times a second to answer a question
     * whose answer changed once.
     */
    private void refresh() {
        long now = System.currentTimeMillis();
        if (!stale && now - computedAt < RECOMPUTE_MS) {
            return;
        }
        stale = false;
        computedAt = now;
        List<ShardProgress.Row> computed = new ArrayList<>(ShardProgress.rows(
                new ShardProgress.Scope(cfg().missingShardsShowOwned, cfg().missingShardsShowPartial),
                source()));
        ShardProgress.sort(computed, sort(), cfg().missingShardsDescending);
        rows = computed;
        summary = ShardProgress.summarise(computed);
        // Counted here rather than in the footer: the footer is built every frame and this walks
        // every recorded page.
        seenCount = ShardOwnership.getInstance().ownedCount();
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** Drawn on the floating-window pass, above the menu's own slots. */
    public void renderTopMost(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!active(container)) {
            return;
        }
        refresh();
        Font font = Minecraft.getInstance().font;

        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            minimized = state.minimized;
        });
        if (minimized) {
            drawMinimizedBar(container, g, font, mouseX, mouseY);
            return;
        }

        // Size from the viewport, never to a fixed minimum: on a viewport narrower than the panel's
        // preferred width the available space is the ceiling, not the floor.
        int available = Math.max(60, container.width - MARGIN * 2);
        panelW = Math.min(available, Math.max(PREFERRED_W, sortWidth() + PAD * 2));

        List<String[]> notes = notes();
        int chrome = HEADER_H + 2 + controlsHeight() + footerHeight(font, notes);
        int wanted = chrome + Math.max(ROW_H, Math.min(rows.size(), 18) * ROW_H) + PAD;
        panelH = Math.min(Math.min(MAX_HEIGHT, wanted), Math.max(60, container.height - MARGIN * 2));

        place(container);

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER,
                SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        drawHeader(g, font, mouseX, mouseY);
        int controlsBottom = drawControls(g, font, mouseX, mouseY);
        listTop = controlsBottom;
        listBottom = Math.max(listTop, posY + panelH - PAD - footerHeight(font, notes));
        // The rows are what yields when the panel is too short for everything: the list scrolls, so
        // nothing is lost, whereas a footer drawn over a row loses both. Overlapping text renders
        // silently, so this is a check rather than a hope.
        if (listBottom - listTop >= ROW_H) {
            drawRows(g, font, mouseX, mouseY);
        } else {
            visibleRows = List.of();
        }
        drawFooter(g, font, notes, listBottom);
    }

    /**
     * The panel's spot: beside the menu on whichever side has room, which is what makes "never
     * overlaps the vanilla GUI" true by construction rather than by hoping. A remembered position
     * from a drag wins, and everything is clamped into the viewport either way.
     */
    private void place(AbstractContainerScreen<?> container) {
        if (posX == Integer.MIN_VALUE) {
            AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) container;
            int left = bounds.skyblockSimplified$leftPos();
            int right = left + bounds.skyblockSimplified$imageWidth();
            int candidate = left - panelW - GAP_TO_CONTAINER;
            if (candidate < MARGIN) {
                // No room on the left: try the right, and if neither side fits take the wider one and
                // let the clamp below deal with it. The player can drag it from there.
                candidate = right + GAP_TO_CONTAINER + panelW + MARGIN <= container.width
                        ? right + GAP_TO_CONTAINER
                        : (left > container.width - right ? MARGIN : container.width - panelW - MARGIN);
            }
            posX = candidate;
            posY = bounds.skyblockSimplified$topPos();
        }
        posX = clamp(posX, MARGIN, Math.max(MARGIN, container.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, container.height - panelH - MARGIN));
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        int listed = summary == null ? 0 : summary.listed();
        String title = RowText.fit(font, "Missing Shards (" + listed + ")",
                minimizeGlyphX() - 4 - (posX + PAD));
        g.text(font, Component.literal(title), posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        boolean minHover = inMinimizeBox(mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeGlyphX() + 4, textY,
                minHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1,
                SBSTheme.ACCENT_SOFT);
    }

    // ------------------------------------------------------------------
    // The sort controls
    // ------------------------------------------------------------------

    private static int sortWidth() {
        return SciFiSegmentedSwitch.widthFor(ShardSort.labels());
    }

    private static int directionWidth() {
        return SciFiSegmentedSwitch.widthFor(DIRECTIONS);
    }

    /** Whether the sort keys fit the panel at all, which decides the whole control block's height. */
    private boolean sortFits() {
        return sortWidth() <= panelW - PAD * 2;
    }

    private boolean directionOnSameLine() {
        return sortFits() && sortWidth() + 4 + directionWidth() <= panelW - PAD * 2;
    }

    private int controlsHeight() {
        if (!sortFits()) {
            return Minecraft.getInstance().font.lineHeight + 4;
        }
        return directionOnSameLine() ? CONTROL_H + 4 : CONTROL_H * 2 + 6;
    }

    /**
     * Draws the sort key switch and the direction switch, and returns the y the list starts at.
     *
     * <p>Every option is visible and one click away - the rule {@code ui/AGENTS.md} states for two to
     * five exclusive values, which both of these are. When the panel is too narrow for even the key
     * switch (a small viewport at GUI scale 4) the controls are <b>replaced by a line saying where to
     * set the sort instead</b>, rather than being drawn clipped or silently dropped: the same choice
     * is a persisted row under Skills - Hunting, so the option is never actually unreachable.
     */
    private int drawControls(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int y = posY + HEADER_H + 3;
        if (!sortFits()) {
            g.text(font, Component.literal(RowText.fit(font, "Sort: Skills - Hunting",
                    panelW - PAD * 2)), posX + PAD, y, SBSTheme.TEXT_MUTED);
            return y + font.lineHeight + 1;
        }

        ensureControls();
        sortSwitch.setX(posX + PAD);
        sortSwitch.setY(y);
        sortSwitch.extractRenderState(g, mouseX, mouseY, 0f);

        boolean sameLine = directionOnSameLine();
        directionSwitch.setX(sameLine ? posX + PAD + sortWidth() + 4 : posX + PAD);
        directionSwitch.setY(sameLine ? y : y + CONTROL_H + 2);
        directionSwitch.extractRenderState(g, mouseX, mouseY, 0f);

        return (sameLine ? y + CONTROL_H : y + CONTROL_H * 2 + 2) + 3;
    }

    /**
     * Builds the two switches once.
     *
     * <p>They hold no state: the selection is read live out of the config and a click writes it back,
     * so the stored setting stays the single source of truth. Clicking the <i>active</i> sort key
     * flips the direction instead of re-selecting it - a shortcut, never the only way to get there,
     * since the direction has its own visible control beside it.
     */
    private void ensureControls() {
        if (sortSwitch != null) {
            return;
        }
        sortSwitch = new SciFiSegmentedSwitch(posX, posY, CONTROL_H, ShardSort.labels(),
                () -> sort().ordinal(),
                index -> {
                    ShardSort[] all = ShardSort.values();
                    if (index < 0 || index >= all.length) {
                        return;
                    }
                    if (all[index] == sort()) {
                        cfg().missingShardsDescending = !cfg().missingShardsDescending;
                    } else {
                        cfg().missingShardsSort = all[index].name();
                    }
                    save();
                    stale = true;
                    scroll = 0;
                });
        directionSwitch = new SciFiSegmentedSwitch(posX, posY, CONTROL_H, DIRECTIONS,
                () -> cfg().missingShardsDescending ? 1 : 0,
                index -> {
                    cfg().missingShardsDescending = index == 1;
                    save();
                    stale = true;
                    scroll = 0;
                });
    }

    // ------------------------------------------------------------------
    // The rows
    // ------------------------------------------------------------------

    private void drawRows(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        bar.set(posX + panelW - PAD - SciFiScrollbar.WIDTH, listTop, listBottom - listTop,
                rows.size(), visible);
        scroll = clamp(scroll, 0, Math.max(0, rows.size() - visible));
        int right = posX + panelW - PAD - (bar.needed() ? SciFiScrollbar.WIDTH + 3 : 0);

        if (rows.isEmpty()) {
            g.text(font, Component.literal(RowText.fit(font, emptyText(), panelW - PAD * 2)),
                    posX + PAD, listTop + 1, SBSTheme.TEXT_MUTED);
            visibleRows = List.of();
            bar.render(g, scroll, mouseX, mouseY);
            return;
        }

        // Both value columns are reserved from the widest string they can produce, not from the
        // current one, or the name column jitters as prices move and the list flickers as it scrolls.
        int rarityW = widestRarity(font);
        int amountW = 0;
        int priceW = 0;
        for (ShardProgress.Row row : rows) {
            amountW = Math.max(amountW, font.width(amountText(row)));
            priceW = Math.max(priceW, font.width(priceText(row)));
        }
        int nameW = Math.max(16, right - (posX + PAD) - rarityW - amountW - priceW - 12);

        List<ShardProgress.Row> drawn = new ArrayList<>(visible);
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            ShardProgress.Row row = rows.get(i + scroll);
            drawn.add(row);
            int rowY = listTop + i * ROW_H;
            int y = rowY + (ROW_H - font.lineHeight) / 2;
            boolean hovered = mouseX >= posX + PAD && mouseX < right
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hovered) {
                g.fill(posX + PAD - 1, rowY, right + 1, rowY + ROW_H, SBSTheme.CARD_BG_HOVER);
            }

            g.text(font, Component.literal(RowText.fit(font, row.name(), nameW)), posX + PAD, y,
                    row.nameKnown() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
            // Rarity is a coloured label, never a colour on its own: a meaningful share of players
            // cannot separate red from green, and the letters carry the same answer as the hue.
            String rarity = rarityText(row.rarity());
            g.text(font, Component.literal(rarity),
                    right - priceW - amountW - 8 - rarityW, y, row.rarity().color());
            String amount = amountText(row);
            g.text(font, Component.literal(amount), right - priceW - 6 - font.width(amount), y,
                    row.needMeasured() ? SBSTheme.TEXT_MUTED : SBSTheme.WARN);
            String price = priceText(row);
            g.text(font, Component.literal(price), right - font.width(price), y,
                    row.wideSpread() ? SBSTheme.WARN
                            : row.priced() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);

            if (hovered) {
                tooltip(g, font, row, mouseX, mouseY);
            }
        }
        visibleRows = drawn;
        bar.render(g, scroll, mouseX, mouseY);
    }

    private String emptyText() {
        if (summary != null && summary.total() == 0) {
            return "Waiting for the Bazaar to answer.";
        }
        return "Nothing left on this list.";
    }

    private static int widestRarity(Font font) {
        int widest = 0;
        for (ShardRarity rarity : ShardRarity.values()) {
            widest = Math.max(widest, font.width(rarityText(rarity)));
        }
        return widest;
    }

    /** Four letters at most, so the column stays narrow without becoming a colour-only signal. */
    private static String rarityText(ShardRarity rarity) {
        return switch (rarity) {
            case COMMON -> "Com";
            case UNCOMMON -> "Unc";
            case RARE -> "Rare";
            case EPIC -> "Epic";
            case LEGENDARY -> "Leg";
            case UNKNOWN -> "?";
        };
    }

    /** How many are still needed - with a tilde when the figure came from the rarity, not the menu. */
    private static String amountText(ShardProgress.Row row) {
        if (!row.needKnown()) {
            return "?";
        }
        return (row.needMeasured() ? "" : "~") + row.need();
    }

    private static String priceText(ShardProgress.Row row) {
        Long cost = row.totalCost();
        if (cost == null) {
            return "-";   // never "0": a zero sorts first and reads as free
        }
        return NumberDisplay.format(cost);
    }

    private void tooltip(GuiGraphicsExtractor g, Font font, ShardProgress.Row row,
                         int mouseX, int mouseY) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(row.name()));
        tip.add(Component.literal("§8" + row.rarity().displayName() + " · "
                + row.state().displayName()));

        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(row.id());
        if (price == null) {
            tip.add(Component.literal("§8The Bazaar has no row for this shard"));
        } else {
            tip.add(Component.literal("§7Instant buy  §f" + NumberDisplay.format(price.buy())));
            tip.add(Component.literal("§7Buy order    §f" + NumberDisplay.format(price.sell())));
        }
        BazaarPriceCache.BzVolume volume = BazaarPriceCache.getInstance().volumeOf(row.id());
        if (volume != null) {
            tip.add(Component.literal("§7Sold this week §f" + NumberDisplay.format(volume.sellWeek())));
        }
        if (row.wideSpread()) {
            tip.add(Component.literal("§cThe book is wide - buying costs far more than selling pays"));
        }
        if (row.inBox() > 0) {
            // Acquired but not spent. Kept apart from the ownership test on purpose: a shard sitting
            // in the box is not attribute progress, and conflating the two would be a correctness bug.
            tip.add(Component.literal("§aYou already hold " + row.inBox() + " in the Hunting Box"));
        }
        if (row.needKnown() && !row.needMeasured()) {
            tip.add(Component.literal("§8Amount from the rarity, "
                    + ShardRarity.COUNT_CERTAINTY.displayName() + " - the menu did not state one"));
        }
        if (!row.nameKnown()) {
            tip.add(Component.literal("§8Name derived from the item id - never seen in the menu"));
        }
        if (cfg().missingShardsClickOpensBazaar) {
            tip.add(Component.literal("§8Click to close the menu and open the Bazaar for it"));
        }
        g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
    }

    // ------------------------------------------------------------------
    // The footer
    // ------------------------------------------------------------------

    /**
     * The footer's lines of caveat, built from what was actually found. Each is {@code {text, colour}}
     * with the colour as a key, so the height is measured from the same list the drawing walks and the
     * two can never disagree.
     */
    private List<String[]> notes() {
        List<String[]> notes = new ArrayList<>();
        if (summary == null) {
            return notes;
        }
        // How much of the catalogue this profile has been seen to own. There is no page count here
        // any more: the catalogue is a bundled file, so the list is complete whether or not the menu
        // has been paged through, and the old "N of M pages read - at least these" caveat was
        // answering a question the list no longer depends on.
        notes.add(new String[]{seenCount + " of " + summary.total() + " shard(s) owned · "
                + ShardCatalog.size() + " catalogued"
                + (ShardCatalog.unconsumableCount() > 0
                        ? ", " + ShardCatalog.unconsumableCount() + " unconsumable" : ""),
                "muted"});
        if (ShardCatalog.unconsumableCount() == 0) {
            // Ships empty, so say so: the total is high by however many of these there turn out to
            // be, and a silently-too-high denominator is the kind of wrong nobody can see.
            notes.add(new String[]{"no unconsumable list yet - the total may be slightly high",
                    "muted"});
        }
        if (summary.estimated() > 0) {
            notes.add(new String[]{"~ " + summary.estimated() + " amount(s) from the rarity ("
                    + ShardRarity.COUNT_CERTAINTY.displayName() + ")", "muted"});
        }
        if (summary.unpriced() > 0) {
            notes.add(new String[]{summary.unpriced() + " shard(s) the Bazaar does not price", "muted"});
        }
        // What this menu, right now, gave up. A list built from the bundled catalogue looks identical
        // whether the open menu was read or not - every shard simply reads as missing - so the panel
        // has to say which of the two it is, on the screen where the player can see both.
        AttributeMenuReader.Facts facts = AttributeMenuReader.getInstance().lastFacts();
        if (facts != null && facts.entries() == 0) {
            notes.add(new String[]{"no entry here carried a \"Source:\" line ("
                    + facts.filled() + " item(s) looked at) - /sbs sharddump", "warn"});
        } else if (facts != null && facts.unmatched() > 0) {
            notes.add(new String[]{"this page: " + facts.entries() + " read, "
                    + facts.unmatched() + " item(s) not recognised", "muted"});
        }
        if (facts != null && facts.entries() > 0 && facts.derived() == 0) {
            notes.add(new String[]{"amounts not derivable - shards.json has no levelling table",
                    "muted"});
        }
        return notes;
    }

    private int footerHeight(Font font, List<String[]> notes) {
        int lines = 1 + notes.size();          // the total, then the caveats
        return 3 + lines * font.lineHeight + 2;
    }

    private void drawFooter(GuiGraphicsExtractor g, Font font, List<String[]> notes, int top) {
        g.fill(posX + PAD, top, posX + panelW - PAD, top + 1, SBSTheme.ACCENT_SOFT);
        int y = top + 3;
        int right = posX + panelW - PAD;

        Long total = summary == null ? null : summary.totalCost();
        String value = total == null ? "unavailable" : NumberDisplay.format(total) + " (est.)";
        String label = "To close every gap";
        int valueW = font.width(value);
        g.text(font, Component.literal(RowText.fit(font, label, right - (posX + PAD) - valueW - 6)),
                posX + PAD, y, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), right - valueW, y,
                total == null ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT_BRIGHT);
        y += font.lineHeight;

        for (String[] note : notes) {
            g.text(font, Component.literal(RowText.fit(font, note[0], panelW - PAD * 2)),
                    posX + PAD, y, "warn".equals(note[1]) ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
            y += font.lineHeight;
        }
    }

    private void drawMinimizedBar(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                                  Font font, int mouseX, int mouseY) {
        minBarW = MIN_BAR_W;
        minBarH = HEADER_H;
        minBarX = clamp(posX == Integer.MIN_VALUE ? MARGIN : posX, MARGIN,
                Math.max(MARGIN, container.width - minBarW - MARGIN));
        minBarY = clamp(posY, MARGIN, Math.max(MARGIN, container.height - minBarH - MARGIN));
        boolean hovered = mouseX >= minBarX && mouseX < minBarX + minBarW
                && mouseY >= minBarY && mouseY < minBarY + minBarH;
        SciFiRender.roundedRectWithBorder(g, minBarX, minBarY, minBarW, minBarH,
                SBSTheme.CORNER_RADIUS, hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        int listed = summary == null ? 0 : summary.listed();
        g.text(font, Component.literal("Missing Shards (" + listed + ")"), minBarX + PAD,
                minBarY + (minBarH - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------
    // Input - forwarded from ContainerSearchBarMixin
    // ------------------------------------------------------------------

    /** @return whether the click was consumed (and must not reach the menu's slots) */
    public boolean handleClick(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (!active(container)) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (event.button() == 0 && mx >= minBarX && mx < minBarX + minBarW
                    && my >= minBarY && my < minBarY + minBarH) {
                minimized = false;
                rememberWindow();
                FloatingWindows.raise(FloatingWindows.Layer.MISSING_SHARDS);
                return true;
            }
            return false;   // everything else passes through to the menu
        }
        if (!inPanel(mx, my)) {
            return false;
        }
        if (event.button() == 0 && inMinimizeBox(mx, my)) {
            minimized = true;
            rememberWindow();
            return true;
        }
        if (event.button() == 0 && inHeader(mx, my)) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        // The scrollbar is offered the click before the rows: a click on the bar must not also land
        // on whatever is painted underneath it.
        if (event.button() == 0 && bar.handleClick(mx, my, scroll, value -> scroll = value)) {
            return true;
        }
        if (event.button() == 0 && sortSwitch != null && sortFits()
                && (sortSwitch.mouseClicked(event, false) || directionSwitch.mouseClicked(event, false))) {
            return true;
        }
        if (event.button() == 0 && cfg().missingShardsClickOpensBazaar) {
            ShardProgress.Row row = rowAt(mx, my);
            if (row != null) {
                // The one place this panel sends anything, and only ever on a left click the player
                // aimed at a row drawn last frame. The name, not the id: /bz is a search box.
                ShardBazaar.open(row.name());
                return true;
            }
        }
        // Swallow every other click inside the panel: it is ours, not the menu's underneath.
        return true;
    }

    /** The row under the cursor, from the rectangle drawn last frame, or {@code null}. */
    private ShardProgress.Row rowAt(double mx, double my) {
        if (visibleRows.isEmpty() || my < listTop || my >= listBottom
                || mx < posX + PAD || mx >= posX + panelW - PAD) {
            return null;
        }
        int index = (int) ((my - listTop) / ROW_H);
        return index >= 0 && index < visibleRows.size() ? visibleRows.get(index) : null;
    }

    public boolean handleDrag(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (!active(container) || minimized) {
            return false;
        }
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        if (!dragging) {
            return false;
        }
        posX = clamp((int) (event.x() - grabDX), MARGIN,
                Math.max(MARGIN, container.width - panelW - MARGIN));
        posY = clamp((int) (event.y() - grabDY), MARGIN,
                Math.max(MARGIN, container.height - panelH - MARGIN));
        return true;
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (bar.release()) {
            return true;
        }
        if (!dragging) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    public boolean handleScroll(AbstractContainerScreen<?> container, double mouseX, double mouseY,
                                double scrollY) {
        if (!active(container) || minimized || !inPanel(mouseX, mouseY) || scrollY == 0) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    // ------------------------------------------------------------------

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    private boolean inHeader(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + HEADER_H;
    }

    private int minimizeGlyphX() {
        return posX + panelW - 14;
    }

    private boolean inMinimizeBox(double mx, double my) {
        return mx >= minimizeGlyphX() && mx < minimizeGlyphX() + 12
                && my >= posY + 2 && my < posY + HEADER_H;
    }

    /** Remembered when a drag or a minimise finishes, never per frame. */
    private void rememberWindow() {
        memory.remember(posX, posY, minimized);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
