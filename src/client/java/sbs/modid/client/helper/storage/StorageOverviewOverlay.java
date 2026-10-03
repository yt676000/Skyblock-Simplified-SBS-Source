/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.helper.rarity.RarityOverlay;
import sbs.modid.client.helper.rarity.RarityOverlayMode;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The unified storage workspace, drawn over Hypixel's Storage menu <b>and</b> over any open Ender
 * Chest page / Backpack, to one fixed layout:
 *
 * <pre>
 *   [ page ] [ page ] [ page ]      a 3-wide grid of every known page, in FIXED order and size
 *   [ page ] [ ACTIVE] [ page ]     the open page is highlighted in place (not relocated)
 *              [ Search ]
 *   [ Backpack Slots ]  [ Player Inv ]  [ Settings ]
 * </pre>
 *
 * <p>The layout is identical no matter what is open underneath – Storage menu, any Ender Chest page,
 * any Backpack – only the yellow highlight moves. Clicking a page runs its
 * open command ({@code /enderchest <page>} / {@code /backpack <n>}) so the overlay is the navigator.
 * The <b>open</b> page's cells are live – they map 1:1 onto the real menu slots and clicks forward
 * through {@code handleContainerInput}, exactly like the vanilla menu – while every other page is a
 * read-only cached view. Bottom-left sits the Storage menu's backpack <b>slot strip</b>: live over
 * the Storage menu (swap backpacks for bigger ones right there), a navigator everywhere else. The
 * player inventory along the bottom is always live, so items move in and out normally.
 *
 * <p>Search is a real filter across every page at once: a page with no hit is <b>hidden</b>, and the
 * pages that do have one <b>shrink</b> to just their matching items, packed together – so a search
 * answers "which storage holds this, and how much" in one look instead of leaving a grid of mostly
 * empty cards to scroll through.
 */
public final class StorageOverviewOverlay {

    private static final StorageOverviewOverlay INSTANCE = new StorageOverviewOverlay();

    /** Item cell size inside a page card, and the live player-inventory cell size (~12% larger). */
    private static final int MINI = 18;
    private static final int CELL = 20;
    private static final int GRID_COLS = 9;
    private static final int CARD_COLS = 3;      // page cards across
    private static final int CARD_ROWS = 6;      // item rows a card can hold (largest page)
    private static final int TITLE_H = 13;
    private static final int CARD_W = GRID_COLS * MINI + 6;
    private static final int CARD_H = TITLE_H + CARD_ROWS * MINI + 4;
    private static final int GAP = 6;
    private static final int SEARCH_H = 16;

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    private boolean active;
    private int scroll;
    private String query = "";
    private boolean searchFocused;

    /**
     * The tooltip to draw this frame, or null. This overlay renders at TAIL - AFTER vanilla's
     * deferred-tooltip flush - so a {@code setTooltipForNextFrame} here is painted over next frame
     * and never seen. Instead every hover sets this, and it is drawn DIRECTLY at the very end (same
     * fix as the Loadouts overlay).
     */
    private List<Component> pendingTooltip;

    /**
     * When on, the whole SBS workspace steps aside so the real Hypixel menu shows through and stays
     * clickable (swap backpacks the vanilla way). Only a small "Back to SBS UI" button is drawn.
     * Resets whenever the storage screen closes.
     */
    private boolean revealVanilla;

    /** Per-frame hit rects. Openers -> command; live cells -> real menu slot. */
    private final List<int[]> openRects = new ArrayList<>();
    private final List<String> openCommands = new ArrayList<>();
    private final List<int[]> liveRects = new ArrayList<>();
    private final List<Integer> liveSlots = new ArrayList<>();
    private int[] searchRect = new int[4];
    private int[] settingsRect = new int[4];
    private int[] vanillaRect = new int[4];    // the "show the real menu" button
    private int[] revealRect = new int[4];     // the "back to SBS UI" button (reveal mode only)
    private int[] gridClip = new int[2];   // {top, bottom} scroll-clip band of the card grid

    /** Every page the Storage menu says the player owns: id -> {label, openCommand}. */
    private final Map<String, String[]> knownPages = new LinkedHashMap<>();

    /**
     * One backpack slot of Hypixel's Storage menu, as last seen there.
     *
     * @param slot   the container slot index inside the Storage menu (live-clickable there)
     * @param stack  a copy of the slot's item, for drawing while a page is open instead
     * @param number the backpack's number ("Slot #4"), or null for an empty / locked slot
     * @param locked whether the slot is still locked
     */
    private record BackpackSlot(int slot, ItemStack stack, Integer number, boolean locked) {
    }

    /** The Storage menu's backpack slot strip, bottom-left; kept across screens once seen. */
    private List<BackpackSlot> backpackSlots = List.of();

    /**
     * Menu-scan throttle: {@code learnPages}/{@code learnBackpackSlots} walk every container slot
     * doing string strips and stack copies, and {@code pages()} rebuilds and sorts the card list.
     * Doing all of that <b>every frame</b> is what made the workspace feel heavy – the menu only
     * changes when the server syncs it, so a refresh a few times a second reads the exact same data.
     * A fresh screen instance always refreshes immediately, so opening feels instant.
     */
    private static final long LEARN_INTERVAL_MS = 150;
    private long lastLearnAt;
    private AbstractContainerScreen<?> lastLearnScreen;
    /** The card list drawn between refreshes ({@link #pages()} recomputed on the same cadence). */
    private List<PageCard> cachedPages = List.of();

    /**
     * The cards actually drawn this frame: every page, or – while searching – only the pages that
     * hold a hit, each carrying the cells to draw. Recomputed on the same cadence as
     * {@link #cachedPages} (so the open page's hits follow items being moved) and immediately on
     * every keystroke.
     */
    private List<ShownCard> shownCards = List.of();

    /** Set when the query changed, so the next frame re-filters instead of waiting out the cadence. */
    private boolean filterDirty;

    /** One page of the grid: its id, label, open command and cached contents (null = never opened). */
    private record PageCard(String id, String label, String command, StorageIndex.Snapshot snapshot) {
    }

    /**
     * A page card as it is drawn this frame.
     *
     * @param page   the page
     * @param active whether it is the page open underneath – its cells are live
     * @param cells  while searching, the matching cells packed in reading order: <b>slot</b> indices
     *               for the {@code active} card, snapshot <b>item</b> indices for every other one.
     *               {@code null} when not searching, meaning "draw the whole page at its real
     *               positions".
     */
    private record ShownCard(PageCard page, boolean active, int[] cells) {
    }

    private StorageOverviewOverlay() {
    }

    public static StorageOverviewOverlay getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Whether the exclusive storage workspace is up over {@code screen}. Other overlays (Recipe
     * Viewer, floating windows) query this to stand down entirely while it covers the screen, so it
     * is the only interactive/tooltip layer - no bleed-through, no half-usable windows on top.
     */
    public boolean isWorkspaceActive(AbstractContainerScreen<?> screen) {
        return shouldShow(screen) && !revealVanilla;
    }

    private boolean shouldShow(AbstractContainerScreen<?> screen) {
        if (!ConfigManager.getInstance().get().skyblockMenu.previewMode.indexes()) {
            return false;
        }
        String title = title(screen);
        if (title.equalsIgnoreCase("Storage")) {
            return true;
        }
        // Every Ender Chest page / Backpack shows the same workspace - Full UI means the overlay
        // NEVER switches back to the vanilla chest, only the active-page highlight moves.
        return pageIdOf(title) != null;
    }

    private static String title(AbstractContainerScreen<?> screen) {
        return screen.getTitle() == null ? "" : StorageIndex.strip(screen.getTitle().getString()).trim();
    }

    /** "Ender Chest (2/9)" -> "ender_chest:2", "Jumbo Backpack (Slot #4)" -> "backpack:4", else null. */
    private static String pageIdOf(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        Integer n = firstNumber(lower);
        if (n == null) {
            return null;
        }
        if (lower.contains("ender chest")) {
            return "ender_chest:" + n;
        }
        if (lower.contains("backpack")) {
            return "backpack:" + n;
        }
        return null;
    }

    private void learnPages(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = StorageIndex.strip(stack.getHoverName().getString()).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            Integer number = firstNumber(name);
            if (number == null) {
                number = firstNumberInLore(stack);
            }
            if (lower.contains("ender chest page") && number != null) {
                knownPages.put("ender_chest:" + number,
                        new String[]{"Ender Chest " + number, "enderchest " + number});
            } else if (lower.contains("backpack") && !lower.contains("slot upgrade")
                    && !lower.contains("empty") && !lower.contains("locked") && number != null) {
                // "empty"/"locked" backpack SLOTS are not pages - they go to the slot strip below.
                String label = name.contains(String.valueOf(number)) ? name : name + " " + number;
                knownPages.put("backpack:" + number, new String[]{label, "backpack " + number});
            }
        }
    }

    /**
     * Records the Storage menu's backpack slots so the bottom-left strip can show them on every
     * screen. Only the Storage menu itself has these as real slots, so this only refreshes there.
     *
     * <p>Two passes: pass 1 finds the menu <b>rows</b> that hold at least one item named like a
     * backpack; pass 2 then takes <b>every</b> slot of those rows – in Hypixel's Storage menu the
     * backpack rows are backpack slots wall to wall, so this also catches the empty and locked
     * slots whatever their item name is (a pure name filter missed them, which made swapping in a
     * bigger backpack impossible: the empty slot to click was never shown).
     */
    private void learnBackpackSlots(AbstractContainerScreen<?> screen) {
        if (!title(screen).equalsIgnoreCase("Storage")) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int containerSlots = Math.max(0, menu.getItems().size() - 36);

        // Pass 1: which rows contain a backpack by name?
        java.util.Set<Integer> backpackRows = new java.util.LinkedHashSet<>();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String lower = StorageIndex.strip(stack.getHoverName().getString())
                    .trim().toLowerCase(Locale.ROOT);
            if (lower.contains("backpack") && !lower.contains("slot upgrade")
                    && !lower.contains("ender chest")) {
                backpackRows.add(i / GRID_COLS);
            }
        }
        if (backpackRows.isEmpty()) {
            return;
        }

        // Pass 2: every slot of those rows is a backpack slot - occupied, empty or locked.
        List<BackpackSlot> found = new ArrayList<>();
        for (int row : backpackRows) {
            for (int col = 0; col < GRID_COLS; col++) {
                int i = row * GRID_COLS + col;
                if (i >= containerSlots) {
                    break;
                }
                ItemStack stack = menu.getSlot(i).getItem();
                String name = stack == null || stack.isEmpty() ? ""
                        : StorageIndex.strip(stack.getHoverName().getString()).trim();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean locked = lower.contains("locked");
                Integer number = null;
                if (lower.contains("backpack") && !locked && !lower.contains("empty")
                        && !lower.contains("slot upgrade")) {
                    number = firstNumber(name);
                    if (number == null) {
                        number = firstNumberInLore(stack);
                    }
                }
                found.add(new BackpackSlot(i,
                        stack == null ? ItemStack.EMPTY : stack.copy(), number, locked));
            }
        }
        backpackSlots = found;
    }

    /** Compiled once: this runs per slot per refresh and inside the page-sort comparator. */
    private static final java.util.regex.Pattern FIRST_NUMBER =
            java.util.regex.Pattern.compile("([0-9]+)");

    private static Integer firstNumber(String text) {
        var m = FIRST_NUMBER.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static Integer firstNumberInLore(ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (var line : lore.lines()) {
            String text = StorageIndex.strip(line.getString());
            if (text.toLowerCase(Locale.ROOT).contains("slot #")) {
                return firstNumber(text);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /**
     * All pages to show: known (placeholder when uncached) plus any cached the scan missed – in a
     * <b>fixed order</b> (Ender Chest pages by number, then Backpacks by number), never discovery
     * order. The card grid therefore has the exact same layout no matter which screen is open or
     * which page was seen first; only the highlight moves.
     */
    private List<PageCard> pages() {
        Map<String, StorageIndex.Snapshot> cached = new LinkedHashMap<>();
        for (StorageIndex.Snapshot snap : StorageIndex.getInstance()
                .snapshotsOf(StorageSource.Kind.ENDER_CHEST, StorageSource.Kind.BACKPACK)) {
            cached.put(snap.source().id(), snap);
        }
        List<PageCard> out = new ArrayList<>();
        for (var e : knownPages.entrySet()) {
            out.add(new PageCard(e.getKey(), e.getValue()[0], e.getValue()[1], cached.remove(e.getKey())));
        }
        for (StorageIndex.Snapshot snap : cached.values()) {
            out.add(new PageCard(snap.source().id(), snap.source().displayName(),
                    snap.source().openCommand(), snap));
        }
        out.sort((a, b) -> {
            String ia = a.id();
            String ib = b.id();
            int kindA = ia.startsWith("ender_chest") ? 0 : 1;
            int kindB = ib.startsWith("ender_chest") ? 0 : 1;
            if (kindA != kindB) {
                return Integer.compare(kindA, kindB);
            }
            Integer na = firstNumber(ia);
            Integer nb = firstNumber(ib);
            return Integer.compare(na == null ? 0 : na, nb == null ? 0 : nb);
        });
        return out;
    }

    /**
     * Turns {@link #cachedPages} into the cards to draw. Without a query that is simply every page
     * at full size; with one, only the pages holding a hit survive, each reduced to its matching
     * cells. The open page is matched against the <b>live</b> menu (so an item just dropped in
     * counts), every other one against its cached snapshot – a page that was never opened has no
     * snapshot and therefore never matches, which is correct: we genuinely do not know what is in it.
     */
    private List<ShownCard> visibleCards(AbstractContainerScreen<?> screen, String activeId) {
        List<ShownCard> out = new ArrayList<>(cachedPages.size());
        for (PageCard page : cachedPages) {
            boolean isActive = page.id().equals(activeId);
            if (query.isEmpty()) {
                out.add(new ShownCard(page, isActive, null));
                continue;
            }
            int[] hits = hitsOf(screen, page, isActive);
            if (hits.length > 0) {
                out.add(new ShownCard(page, isActive, hits));
            }
        }
        return out;
    }

    /** The cells of one page matching the current query, packed in reading order; empty = hide it. */
    private int[] hitsOf(AbstractContainerScreen<?> screen, PageCard page, boolean isActive) {
        int limit = GRID_COLS * CARD_ROWS;
        List<Integer> hits = new ArrayList<>();
        if (isActive) {
            AbstractContainerMenu menu = screen.getMenu();
            int containerSlots = Math.max(0, menu.getItems().size() - 36);
            int start = Math.min(GRID_COLS, containerSlots);   // the nav row is not storage
            for (int i = start; i < containerSlots && hits.size() < limit; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack != null && !stack.isEmpty() && matchesQuery(stack)) {
                    hits.add(i);
                }
            }
        } else if (page.snapshot() != null) {
            List<ItemStack> items = page.snapshot().items();
            for (int i = 0; i < items.size() && i < limit && hits.size() < limit; i++) {
                ItemStack item = items.get(i);
                if (item != null && !item.isEmpty() && matchesQuery(item)) {
                    hits.add(i);
                }
            }
        }
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = hits.get(i);
        }
        return out;
    }

    /** A card's height: full size normally, shrunk to the rows its search hits actually need. */
    private static int cardHeight(ShownCard card) {
        if (card.cells() == null) {
            return CARD_H;
        }
        int rows = Math.max(1, Math.min(CARD_ROWS, (card.cells().length + GRID_COLS - 1) / GRID_COLS));
        return TITLE_H + rows * MINI + 4;
    }

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        openRects.clear();
        openCommands.clear();
        liveRects.clear();
        liveSlots.clear();
        pendingTooltip = null;
        boolean show = shouldShow(screen);
        if (!show) {
            active = false;
            revealVanilla = false;   // fresh workspace next time the storage opens
            searchFocused = false;
            lastLearnScreen = null;  // the next open refreshes immediately (and drops the screen ref)
            return;
        }
        Font font = Minecraft.getInstance().font;
        // Reveal mode: leave the real Hypixel menu on screen and clickable, only draw the way back.
        if (revealVanilla) {
            active = false;
            searchFocused = false;
            drawRevealButton(g, font, screen.width, mouseX, mouseY);
            return;
        }
        active = true;
        String activeId = pageIdOf(title(screen));   // null over the Storage menu
        boolean storageOpen = title(screen).equalsIgnoreCase("Storage");
        long nowMs = System.currentTimeMillis();
        if (screen != lastLearnScreen || nowMs - lastLearnAt >= LEARN_INTERVAL_MS || filterDirty) {
            lastLearnScreen = screen;
            lastLearnAt = nowMs;
            filterDirty = false;
            learnPages(screen);
            learnBackpackSlots(screen);
            cachedPages = pages();
            shownCards = visibleCards(screen, activeId);
        }
        int w = screen.width;
        int h = screen.height;
        AbstractContainerMenu menu = screen.getMenu();
        int containerSlots = Math.max(0, menu.getItems().size() - 36);

        // Opaque full-screen backdrop: the workspace is EXCLUSIVE. Drawn at TAIL (after vanilla's
        // deferred-tooltip flush), it fully buries the real menu AND its hovered-slot tooltip, which
        // used to bleed through the gaps between cards. Fully opaque on purpose - the smallest bit of
        // translucency let bright tooltip text ghost through. The Recipe Viewer / floating windows
        // are skipped entirely while this is up (see OverlayRenderMixin), so nothing paints over it.
        g.fill(0, 0, w, h, 0xFF0A1420);

        // --- bottom row: backpack slots | player inventory | settings + vanilla-menu button ---
        int invX = (w - GRID_COLS * CELL) / 2;
        int invY = h - 12 - (3 * CELL + 4 + CELL);
        drawPlayerInventory(g, menu, containerSlots, invX, invY, mouseX, mouseY);
        drawSideButtons(g, font, invX + GRID_COLS * CELL + 12, invY, mouseX, mouseY);
        drawBackpackSlots(g, font, menu, 10, invY, storageOpen, activeId, mouseX, mouseY);

        // --- search bar, centred above the inventory ---
        int searchW = GRID_COLS * CELL + 40;
        int searchX = (w - searchW) / 2;
        int searchY = invY - SEARCH_H - 8;
        drawSearch(g, font, searchX, searchY, searchW);

        // --- the page grid fills the space above the search ---
        // Scissored to its band so scrolled cards can never bleed over the search bar / inventory.
        int gridBottom = searchY - 10;
        g.enableScissor(0, 10, w, gridBottom);
        drawPageGrid(g, font, screen, w, 10, gridBottom, mouseX, mouseY);
        g.disableScissor();

        // The carried stack rides the cursor and must paint ABOVE everything (vanilla draws its own
        // below our backdrop; drawing it earlier let the page cards cover it - "invisible item").
        ItemStack carried = menu.getCarried();
        if (carried != null && !carried.isEmpty()) {
            g.item(carried, mouseX - 8, mouseY - 8);
            if (carried.getCount() > 1) {
                g.itemDecorations(font, carried, mouseX - 8, mouseY - 8);
            }
        }

        // Drawn LAST and DIRECTLY, above everything: a deferred tooltip from this TAIL overlay would
        // be flushed before our stratum next frame and painted over (invisible).
        if (pendingTooltip != null && (carried == null || carried.isEmpty())) {
            List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> parts =
                    new ArrayList<>(pendingTooltip.size());
            for (Component line : pendingTooltip) {
                parts.add(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
                        .create(line.getVisualOrderText()));
            }
            g.tooltip(font, parts, mouseX, mouseY,
                    net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE,
                    SBSTheme.tooltipStyle());
        }
    }

    private void drawPageGrid(GuiGraphicsExtractor g, Font font, AbstractContainerScreen<?> screen,
                              int w, int top, int bottom, int mouseX, int mouseY) {
        List<ShownCard> cards = shownCards;   // rebuilt on the learn cadence, not per frame
        gridClip = new int[]{top, bottom};
        if (cards.isEmpty()) {
            g.centeredText(font, Component.literal(query.isEmpty()
                            ? "§7Open your Ender Chest pages and Backpacks once"
                            : "§7No storage holds \"" + query + "\""),
                    w / 2, (top + bottom) / 2, SBSTheme.TEXT_MUTED);
            return;
        }
        int gridW = CARD_COLS * CARD_W + (CARD_COLS - 1) * GAP;
        int left = (w - gridW) / 2;
        // Rows are stacked one after another, each as tall as its tallest card, instead of on a fixed
        // CARD_H step: while searching the cards shrink to their hits, and the results pack together.
        int y = top - scroll;
        int content = 0;
        for (int i = 0; i < cards.size(); i += CARD_COLS) {
            int end = Math.min(i + CARD_COLS, cards.size());
            int rowH = 0;
            for (int j = i; j < end; j++) {
                rowH = Math.max(rowH, cardHeight(cards.get(j)));
            }
            for (int j = i; j < end; j++) {
                ShownCard card = cards.get(j);
                int cardH = cardHeight(card);
                if (y + cardH >= top && y <= bottom) {
                    drawCard(g, font, screen, card, cardH, left + (j - i) * (CARD_W + GAP), y,
                            top, bottom, mouseX, mouseY);
                }
            }
            y += rowH + GAP;
            content += rowH + GAP;
        }
        int maxScroll = Math.max(0, content - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /**
     * One page card: title (opens on click) + item grid; the active page's grid is live.
     *
     * <p>Outside search the grid is the page 1:1 – cell {@code i} sits at the item's real position,
     * gaps included. While searching the card carries only its hits, drawn packed from the top-left,
     * and {@code cardH} is already the shrunk height for exactly those rows. Positions are decoupled
     * from indices either way: a live cell registers its <b>real</b> menu slot in {@link #liveSlots},
     * so clicking a packed cell still moves the right item.
     */
    private void drawCard(GuiGraphicsExtractor g, Font font, AbstractContainerScreen<?> screen,
                          ShownCard card, int cardH, int x, int y, int clipTop, int clipBottom,
                          int mouseX, int mouseY) {
        String label = card.page().label();
        String command = card.page().command();
        boolean isActive = card.active();
        int[] cells = card.cells();
        boolean titleHovered = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + TITLE_H;
        SciFiRender.roundedRectWithBorder(g, x - 1, y - 1, CARD_W + 2, cardH + 2, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, isActive ? 0xFFFFE24B : SBSTheme.CARD_BORDER);
        if (isActive) {
            // Yellow frame in place - the active page is highlighted, never relocated.
            SciFiRender.roundedRectWithBorder(g, x - 2, y - 2, CARD_W + 4, cardH + 4,
                    SBSTheme.CORNER_RADIUS, 0x00000000, 0xFFFFE24B);
        }
        // While searching the title carries the hit count - that IS the answer to "where is my stuff".
        String title = trim(font, label, CARD_W - 6 - (cells == null ? 0 : font.width(" 00")));
        g.text(font, Component.literal((isActive ? "§e" : titleHovered ? "§b" : "§f") + title
                        + (cells == null ? "" : " §7" + cells.length)),
                x + 2, y + 2, SBSTheme.ACCENT_BRIGHT);
        // The active page is already open - re-sending its command would just reload the menu
        // under the cursor, so only the other cards are click-to-open. The hit rect is clamped to
        // the visible scroll band: the scissor clips the pixels, this clips the click.
        if (!isActive && command != null && !command.isBlank()) {
            int hitTop = Math.max(y, clipTop);
            int hitBottom = Math.min(y + cardH, clipBottom);
            if (hitBottom > hitTop) {
                openRects.add(new int[]{x, hitTop, CARD_W, hitBottom - hitTop});
                openCommands.add(command);
            }
        }

        int gx = x + 3;
        int gy = y + TITLE_H;
        if (isActive) {
            // Live: draw the real menu's container slots (skip the top nav row), fully interactive.
            AbstractContainerMenu menu = screen.getMenu();
            int containerSlots = Math.max(0, menu.getItems().size() - 36);
            int start = Math.min(GRID_COLS, containerSlots);
            int count = cells != null ? cells.length : Math.max(0, containerSlots - start);
            for (int p = 0; p < count; p++) {
                int slot = cells != null ? cells[p] : start + p;
                if (slot >= containerSlots) {
                    continue;   // the menu shrank since the last refresh
                }
                int cx = gx + (p % GRID_COLS) * MINI;
                int cy = gy + (p / GRID_COLS) * MINI;
                if (cy + MINI < clipTop || cy > clipBottom) {
                    continue;
                }
                drawLiveMini(g, font, menu, slot, cx, cy, clipTop, clipBottom, label, mouseX, mouseY);
            }
        } else if (card.page().snapshot() != null) {
            List<ItemStack> items = card.page().snapshot().items();
            int count = cells != null ? cells.length : Math.min(items.size(), GRID_COLS * CARD_ROWS);
            for (int p = 0; p < count; p++) {
                int index = cells != null ? cells[p] : p;
                if (index >= items.size()) {
                    continue;
                }
                ItemStack item = items.get(index);
                if (item == null || item.isEmpty()) {
                    continue;   // empty placeholders preserve the gaps of an unfiltered page
                }
                int cx = gx + (p % GRID_COLS) * MINI;
                int cy = gy + (p / GRID_COLS) * MINI;
                if (cy + MINI < clipTop || cy > clipBottom) {
                    continue;
                }
                drawMini(g, font, item, cx, cy, clipTop, clipBottom, label, mouseX, mouseY);
            }
        } else {
            g.text(font, Component.literal("§8click once to open"), gx + 2,
                    gy + (MINI - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
        }
    }

    private void drawLiveMini(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int slot,
                              int x, int y, int clipTop, int clipBottom, String where,
                              int mouseX, int mouseY) {
        ItemStack stack = menu.getSlot(slot).getItem();
        boolean hovered = mouseX >= x && mouseX < x + MINI && mouseY >= y && mouseY < y + MINI
                && mouseY >= clipTop && mouseY <= clipBottom;
        g.fill(x, y, x + MINI - 1, y + MINI - 1, hovered ? 0x44FFE24B : 0x33000000);
        // Only the part of the cell inside the scroll band is clickable - a cell half under the
        // search bar must not steal the search bar's click.
        int hitTop = Math.max(y, clipTop);
        int hitBottom = Math.min(y + MINI, clipBottom);
        if (hitBottom > hitTop) {
            liveRects.add(new int[]{x, hitTop, MINI, hitBottom - hitTop});
            liveSlots.add(slot);
        }
        if (stack != null && !stack.isEmpty()) {
            drawRarity(g, stack, x, y);
            g.item(stack, x, y);
            if (stack.getCount() > 1) {
                g.itemDecorations(font, stack, x, y);
            }
            dimIfFiltered(g, stack, x, y);
            if (hovered && (menu.getCarried() == null || menu.getCarried().isEmpty())) {
                pendingTooltip = tooltip(stack, where);
            }
        }
    }

    private void drawMini(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y,
                          int clipTop, int clipBottom, String where, int mouseX, int mouseY) {
        drawRarity(g, stack, x, y);
        g.item(stack, x, y);
        if (stack.getCount() > 1) {
            g.itemDecorations(font, stack, x, y);
        }
        dimIfFiltered(g, stack, x, y);
        if (mouseX >= x && mouseX < x + MINI && mouseY >= y && mouseY < y + MINI
                && mouseY >= clipTop && mouseY <= clipBottom) {
            pendingTooltip = tooltip(stack, where);
        }
    }

    /**
     * The item-rarity tint under one cell's item, honouring the same Overlays setting the real slots
     * use. The workspace draws its own item grid instead of vanilla's slots, so the container-slot
     * mixin never sees these cells – without this the rarity colours simply stopped at the edge of
     * the workspace, which reads as the feature being broken rather than as a different surface.
     */
    private static void drawRarity(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        RarityOverlayMode mode = ConfigManager.getInstance().get().itemOverlay.rarityMode;
        if (mode == RarityOverlayMode.OFF) {
            return;
        }
        Rarity rarity = Rarity.detect(stack);
        if (rarity != null) {
            RarityOverlay.draw(g, x, y, 16, rarity, mode);
        }
    }

    private void dimIfFiltered(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (query.isEmpty()) {
            return;
        }
        if (matchesQuery(stack)) {
            // A hit is a 2px yellow OUTLINE hugging the item, drawn as four thin bars so the icon
            // stays fully visible. (roundedRectWithBorder with a transparent fill paints the whole
            // cell solid - the border colour fills first, the transparent inset changes nothing -
            // which is exactly the solid yellow block that hid the item.)
            int c = 0xFFFFE24B;
            g.fill(x - 1, y - 1, x + 17, y + 1, c);        // top
            g.fill(x - 1, y + 15, x + 17, y + 17, c);      // bottom
            g.fill(x - 1, y - 1, x + 1, y + 17, c);        // left
            g.fill(x + 15, y - 1, x + 17, y + 17, c);      // right
        } else {
            // A light veil, not a blackout - the non-matching items stay readable.
            g.fill(x, y, x + MINI - 1, y + MINI - 1, 0x66000000);
        }
    }

    private void drawPlayerInventory(GuiGraphicsExtractor g, AbstractContainerMenu menu,
                                     int containerSlots, int x, int y, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        for (int j = 0; j < 36; j++) {
            int row = j / GRID_COLS;
            int cx = x + (j % GRID_COLS) * CELL;
            int cy = y + row * CELL + (row == 3 ? 4 : 0);
            ItemStack stack = menu.getSlot(containerSlots + j).getItem();
            boolean hovered = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
            SciFiRender.roundedRectWithBorder(g, cx, cy, CELL - 1, CELL - 1, 2,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            liveRects.add(new int[]{cx, cy, CELL, CELL});
            liveSlots.add(containerSlots + j);
            if (stack != null && !stack.isEmpty()) {
                drawRarity(g, stack, cx + 1, cy + 1);
                g.item(stack, cx + 1, cy + 1);
                if (stack.getCount() > 1) {
                    g.itemDecorations(font, stack, cx + 1, cy + 1);
                }
                if (hovered && (menu.getCarried() == null || menu.getCarried().isEmpty())) {
                    pendingTooltip = tooltip(stack, "Inventory");
                }
            }
        }
    }

    /**
     * The Storage menu's backpack slot strip, bottom-left – the part of the menu where backpacks
     * are swapped for bigger ones. Over the Storage menu the cells are <b>live</b>: they map 1:1
     * onto the real backpack slots, so picking a backpack up, placing a bigger one into an empty
     * slot, or opening one works exactly like the vanilla menu. Over an open page the same grid is
     * the cached view: clicking a backpack opens it, clicking an empty / locked slot opens the
     * Storage menu (the only place a slot can be changed).
     */
    private void drawBackpackSlots(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu,
                                   int x, int y, boolean storageOpen, String activeId,
                                   int mouseX, int mouseY) {
        g.text(font, Component.literal("§7Backpack Slots"), x, y - font.lineHeight - 2, SBSTheme.TEXT_MUTED);
        List<BackpackSlot> slots = backpackSlots;
        if (slots.isEmpty()) {
            // The Storage menu has not been seen yet: same raster, numbered cells from the pages
            // we do know, so the overlay's shape never changes.
            int i = 0;
            for (var e : knownPages.entrySet()) {
                if (!e.getKey().startsWith("backpack")) {
                    continue;
                }
                int cx = x + (i % GRID_COLS) * MINI;
                int cy = y + (i / GRID_COLS) * MINI;
                i++;
                boolean current = e.getKey().equals(activeId);
                boolean hovered = mouseX >= cx && mouseX < cx + MINI && mouseY >= cy && mouseY < cy + MINI;
                SciFiRender.roundedRectWithBorder(g, cx, cy, MINI - 1, MINI - 1, 2,
                        hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        current ? 0xFFFFE24B : SBSTheme.CARD_BORDER);
                Integer number = firstNumber(e.getKey());
                g.centeredText(font, Component.literal((current ? "§e" : "§7")
                                + (number == null ? "?" : String.valueOf(number))),
                        cx + MINI / 2, cy + (MINI - font.lineHeight) / 2, SBSTheme.TEXT);
                if (!current) {
                    openRects.add(new int[]{cx, cy, MINI, MINI});
                    openCommands.add(e.getValue()[1]);
                }
            }
            if (i == 0) {
                g.text(font, Component.literal("§8open Storage once"), x, y, SBSTheme.TEXT_MUTED);
            }
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            BackpackSlot bp = slots.get(i);
            int cx = x + (i % GRID_COLS) * MINI;
            int cy = y + (i / GRID_COLS) * MINI;
            boolean current = bp.number() != null && ("backpack:" + bp.number()).equals(activeId);
            boolean hovered = mouseX >= cx && mouseX < cx + MINI && mouseY >= cy && mouseY < cy + MINI;
            SciFiRender.roundedRectWithBorder(g, cx, cy, MINI - 1, MINI - 1, 2,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    current ? 0xFFFFE24B : SBSTheme.CARD_BORDER);
            ItemStack shown = bp.stack();
            String hint;
            if (storageOpen && bp.slot() < menu.getItems().size()) {
                // Live cell over the real Storage menu slot - the actual place backpacks change.
                shown = menu.getSlot(bp.slot()).getItem();
                liveRects.add(new int[]{cx, cy, MINI, MINI});
                liveSlots.add(bp.slot());
                hint = "§8Click to open · place a bigger backpack here to swap";
            } else if (bp.number() != null) {
                openRects.add(new int[]{cx, cy, MINI, MINI});
                openCommands.add("backpack " + bp.number());
                hint = "§8Click to open";
            } else {
                openRects.add(new int[]{cx, cy, MINI, MINI});
                openCommands.add("storage");
                hint = "§8Open the Storage menu to change this slot";
            }
            if (shown != null && !shown.isEmpty()) {
                g.item(shown, cx, cy);
            }
            if (hovered && (menu.getCarried() == null || menu.getCarried().isEmpty())
                    && shown != null && !shown.isEmpty()) {
                List<Component> tip = tooltip(shown, "Storage");
                tip.add(Component.literal(hint));
                pendingTooltip = tip;
            }
        }
    }

    /** Bottom-right stack: Settings, plus a "Real Menu" button that reveals the vanilla screen. */
    private void drawSideButtons(GuiGraphicsExtractor g, Font font, int x, int y, int mouseX, int mouseY) {
        int w = 70;
        int h = SEARCH_H;
        settingsRect = new int[]{x, y, w, h};
        boolean sHover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                sHover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal("Settings"), x + w / 2,
                y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);

        int vy = y + h + 4;
        vanillaRect = new int[]{x, vy, w, h};
        boolean vHover = mouseX >= x && mouseX < x + w && mouseY >= vy && mouseY < vy + h;
        SciFiRender.roundedRectWithBorder(g, x, vy, w, h, SBSTheme.CORNER_RADIUS,
                vHover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal("Real Menu"), x + w / 2,
                vy + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        if (vHover) {
            pendingTooltip = List.of(
                    Component.literal("§fShow the real Hypixel menu"),
                    Component.literal("§8Swap backpacks the vanilla way; click again to return"));
        }
    }

    /** The lone button drawn in reveal mode: brings the SBS workspace back over the vanilla menu. */
    private void drawRevealButton(GuiGraphicsExtractor g, Font font, int screenW, int mouseX, int mouseY) {
        int w = 96;
        int h = SEARCH_H;
        int x = (screenW - w) / 2;
        int y = 6;
        revealRect = new int[]{x, y, w, h};
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal("§bBack to SBS UI"), x + w / 2,
                y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
    }

    private void drawSearch(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
        searchRect = new int[]{x, y, w, SEARCH_H};
        SciFiRender.roundedRectWithBorder(g, x, y, w, SEARCH_H, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, searchFocused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        String shown = query.isEmpty() && !searchFocused ? "§8Search..." : query;
        g.text(font, Component.literal(shown), x + 5, y + (SEARCH_H - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = x + 5 + font.width(query) + 1;
            g.fill(caretX, y + 2, caretX + 1, y + SEARCH_H - 2, SBSTheme.ACCENT_BRIGHT);
        }
    }

    private static List<Component> tooltip(ItemStack stack, String where) {
        List<Component> tip = new ArrayList<>();
        tip.add(stack.getHoverName());
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) {
            tip.addAll(lore.lines());
        }
        tip.add(Component.literal("§8" + where));
        return tip;
    }

    private boolean matchesQuery(ItemStack stack) {
        return query.isEmpty() || StorageIndex.strip(stack.getHoverName().getString())
                .toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static boolean hit(int[] r, double mx, double my) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private String trim(Font font, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    // ------------------------------------------------------------------
    // Input – forwarded from ContainerSearchBarMixin
    // ------------------------------------------------------------------

    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        double mx = event.x();
        double my = event.y();
        // In reveal mode the workspace is stood down: only the "back to SBS UI" button is ours,
        // every other click falls through to the real menu.
        if (revealVanilla) {
            if (event.button() == 0 && hit(revealRect, mx, my)) {
                revealVanilla = false;
                return true;
            }
            return false;
        }
        if (!active) {
            return false;
        }

        if (event.button() == 0 && hit(settingsRect, mx, my)) {
            Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
            return true;
        }
        if (event.button() == 0 && hit(vanillaRect, mx, my)) {
            revealVanilla = true;   // step aside so the real menu is usable
            searchFocused = false;
            return true;
        }
        searchFocused = hit(searchRect, mx, my);
        if (searchFocused) {
            return true;
        }
        // Live cells (open page + player inventory) take priority over the page-open rects that sit
        // under the active card, so moving items works before "click a card to open" does.
        for (int i = 0; i < liveRects.size(); i++) {
            if (hit(liveRects.get(i), mx, my)) {
                clickRealSlot(screen, liveSlots.get(i), event.button());
                return true;
            }
        }
        if (event.button() == 0) {
            for (int i = 0; i < openRects.size(); i++) {
                if (hit(openRects.get(i), mx, my) && openCommands.get(i) != null) {
                    var player = Minecraft.getInstance().player;
                    if (player != null && player.connection != null) {
                        player.connection.sendCommand(openCommands.get(i));   // /enderchest N or /backpack N
                    }
                    return true;
                }
            }
        }
        return true;   // the workspace swallows every click over it
    }

    private void clickRealSlot(AbstractContainerScreen<?> screen, int slot, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        var window = mc.getWindow();
        boolean shift = InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
        ContainerInput input = shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot,
                button == 1 ? 1 : 0, input, mc.player);
    }

    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY,
                                double scrollY) {
        if (!active || scrollY == 0 || mouseY < gridClip[0] || mouseY > gridClip[1]) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) (scrollY * MINI));
        return true;
    }

    /**
     * Swallows mouse release and drag while the workspace is up. Our item moves are complete on the
     * <b>press</b> ({@link #clickRealSlot} runs a full {@code PICKUP}), so the vanilla screen must not
     * also see the release / drag: left to run, it finds the carried item over "nothing" (the real
     * container GUI is hidden under our backdrop) and drops it on the floor via slot -999, or starts
     * a quick-craft drag that scatters it into the wrong real slots.
     */
    public boolean handleRelease() {
        return active;
    }

    public boolean handleDrag() {
        return active;
    }

    public boolean handleKey(KeyEvent event) {
        if (!active || !searchFocused) {
            return false;
        }
        int key = event.key();
        if (key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
            searchFocused = false;
        } else if (key == KEY_BACKSPACE && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            queryChanged();
        }
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!active || !searchFocused) {
            return false;
        }
        if (event.isAllowedChatCharacter() && query.length() < 40) {
            query += event.codepointAsString();
            queryChanged();
        }
        return true;
    }

    /**
     * Re-filters on the next frame instead of waiting out the refresh cadence – typing has to feel
     * immediate – and jumps back to the top, since the result list the scroll offset belonged to is
     * gone.
     */
    private void queryChanged() {
        filterDirty = true;
        scroll = 0;
    }
}
