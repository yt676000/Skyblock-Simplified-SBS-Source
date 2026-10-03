/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.recipe.logic.RecipeRegistry;
import sbs.modid.client.economy.recipe.logic.RepoLoreCache;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.RecipeEntryKind;
import sbs.modid.client.economy.recipe.model.ItemWikiLinks;
import sbs.modid.client.economy.recipe.model.PanelSide;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.economy.recipe.ui.RecipeViewerScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.util.MathEval;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Recipe Viewer overlay: all <b>UI rendering</b> (search bar bottom-centre, item
 * list beside the inventory) and <b>click / scroll logic</b> in one place.
 *
 * <p>Rendering is driven from {@code OverlayRenderMixin}, which hooks the <b>final</b>
 * {@code Screen#extractRenderStateWithTooltipAndSubtitles} – the one method every screen goes
 * through. That makes the overlay universal: it shows on the plain player inventory, chests,
 * crafting tables and every other {@link AbstractContainerScreen}, even when a subclass (like
 * {@code InventoryScreen}) overrides the regular render method – which is exactly why the previous
 * hook missed the player inventory. Input is forwarded from {@code ContainerSearchBarMixin}.
 *
 * <p>Hover shows the item's real tooltip (name, rarity lore, injected prices). It is drawn as the
 * last thing in the frame – after the container, the panel and every floating window – so it is
 * never covered; see {@link #renderHoverTooltip}.
 */
public final class RecipeOverlay {

    private static final RecipeOverlay INSTANCE = new RecipeOverlay();

    private static final int BAR_WIDTH = 180;
    private static final int BAR_HEIGHT = 14;
    private static final int CELL = 18;
    private static final int PANEL_MARGIN = 6;
    private static final int PANEL_TOP = 18;
    private static final int SEARCH_LIMIT = 800;
    private static final int HINT_COLOR = 0x8FA9C8;

    /** Smallest static-panel footprint the size setting offers, in percent of the free area. */
    public static final int MIN_PANEL_SIZE = 30;

    /** Item-list state (query-scoped; reset whenever the query changes). */
    private int scrollRow;
    private String lastQuery;
    private RecipeEntryKind.Filter lastFilter;
    /** Whether the last unfiltered list was empty (catalogue not loaded yet): rebuild next time. */
    private boolean baseEmpty = true;
    private List<ItemRef> items = List.of();

    /** The panel item under the mouse in the last rendered frame (for hover-sensitive hotkeys). */
    private ItemRef lastHovered;

    // ------------------------------------------------------------------
    // Windowed mode (config toggle): a movable, Ctrl+scroll-resizable window like the browser
    // windows – never closed, only minimized to a reopen button next to the search bar.
    // Position / size / minimized state are remembered across restarts (WindowMemory).
    // ------------------------------------------------------------------

    private static final int WIN_HEADER = 16;
    private static final int WIN_PAD = 6;
    private static final int WIN_MARGIN = 2;
    private static final int WIN_MIN_W = 120;
    private static final int WIN_MAX_W = 700;
    private static final int WIN_MIN_H = 110;
    private static final int WIN_MAX_H = 500;

    private int winX = Integer.MIN_VALUE;
    private int winY = Integer.MIN_VALUE;
    private int winW = 230;
    private int winH = 250;
    private boolean minimized;
    private boolean draggingWindow;
    private double grabDX;
    private double grabDY;

    /** Shared edge/corner resize mechanics (identical to the Price History window). */
    private final sbs.modid.client.ui.window.WindowResizer windowResizer = new sbs.modid.client.ui.window.WindowResizer();

    /** Where the player left the window: position, size and whether it was collapsed. */
    private final sbs.modid.client.ui.window.WindowMemory memory =
            new sbs.modid.client.ui.window.WindowMemory(
                    sbs.modid.client.ui.window.FloatingWindows.Layer.RECIPE);

    private RecipeOverlay() {
    }

    public static RecipeOverlay getInstance() {
        return INSTANCE;
    }

    public boolean active() {
        return ConfigManager.getInstance().get().recipeViewer.enabled;
    }

    private static boolean windowed() {
        return ConfigManager.getInstance().get().recipeViewer.windowed;
    }

    /** The window rectangle, clamped into the screen (also lazily places the default spot). */
    private int[] windowRect(Screen screen) {
        // Every path into the window's geometry comes through here, so this is where the remembered
        // spot is adopted - before the "never placed" default below can claim it.
        restoreRemembered();
        winW = clamp(winW, WIN_MIN_W, Math.max(WIN_MIN_W, screen.width - WIN_MARGIN * 2));
        winH = clamp(winH, WIN_MIN_H, Math.max(WIN_MIN_H, screen.height - WIN_MARGIN * 2));
        if (winX == Integer.MIN_VALUE) {
            winX = screen.width - winW - 8;
            winY = PANEL_TOP;
        }
        winX = clamp(winX, WIN_MARGIN, screen.width - winW - WIN_MARGIN);
        winY = clamp(winY, WIN_MARGIN, screen.height - winH - WIN_MARGIN);
        return new int[]{winX, winY, winW, winH};
    }

    private boolean inWindow(Screen screen, double mx, double my) {
        int[] r = windowRect(screen);
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /**
     * Whether the overlay covers {@code (mx,my)} with something opaque - its floating window, its
     * search bar or its item grid. The container-tooltip mixin calls this to suppress the vanilla
     * hovered-slot tooltip that would otherwise bleed out from BEHIND the overlay.
     *
     * <p><b>Only where this overlay actually PAINTS this frame.</b> Suppression is allowed to hide
     * the item lore behind a panel; it is not allowed to hide it behind nothing. Every rectangle
     * answered here must correspond to pixels the player can see, because from the player's side an
     * invisible rectangle that swallows tooltips is indistinguishable from tooltips being broken -
     * they cannot even tell which overlay to close. Two rectangles used to fail that test:
     * <ul>
     *   <li>a <b>minimized</b> window paints only its little reopen button, yet its remembered
     *       230x250 rectangle went on eating every tooltip inside it;</li>
     *   <li>the <b>static panel</b> draws no background of its own - only item cells - so the empty
     *       area of a short result list (or of no search at all) was a dead zone the width of the
     *       free space beside the menu.</li>
     * </ul>
     */
    public boolean coversForTooltip(AbstractContainerScreen<?> screen, double mx, double my) {
        if (!active()) {
            return false;
        }
        // The search bar and the math chip are drawn on every container screen, so they always count.
        if (inBar(screen, mx, my) || inMathChip(screen, mx, my)) {
            return true;
        }
        if (windowed()) {
            // The window chrome is an opaque panel, so the whole rectangle covers - but only while
            // the window is actually up.
            return !minimized && inWindow(screen, mx, my);
        }
        // The static panel is transparent between its cells: it covers exactly where an item is.
        return hoveredPanelItem(screen, mx, my) != null;
    }

    private int minimizeX(Screen screen) {
        return windowRect(screen)[0] + windowRect(screen)[2] - 14;
    }

    private boolean inMinimizeBox(Screen screen, double mx, double my) {
        int[] r = windowRect(screen);
        return mx >= minimizeX(screen) && mx < minimizeX(screen) + 12
                && my >= r[1] + 2 && my < r[1] + WIN_HEADER;
    }

    /** The reopen button sits right of the search bar while the window is minimized. */
    private boolean inReopenButton(Screen screen, double mx, double my) {
        int x = barX(screen) + BAR_WIDTH + 4;
        int y = barY(screen);
        return mx >= x && mx < x + BAR_HEIGHT && my >= y && my < y + BAR_HEIGHT;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                        com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    public int barX(Screen screen) {
        return (screen.width - BAR_WIDTH) / 2;
    }

    public int barY(Screen screen) {
        return screen.height - BAR_HEIGHT - 6;
    }

    public boolean inBar(Screen screen, double mx, double my) {
        int x = barX(screen);
        int y = barY(screen);
        return mx >= x && mx <= x + BAR_WIDTH && my >= y && my <= y + BAR_HEIGHT;
    }

    /**
     * Item-grid rectangle {x, y, w, h}: in windowed mode the window's content area, otherwise the
     * static panel beside the container for the configured side.
     *
     * <p>The static panel is trimmed to {@code recipeViewer.panelSize} percent of the free area
     * beside the container. It shrinks <b>towards the container</b>, not away from it: the edge
     * touching the menu stays put, so a compact panel still reads as belonging to the screen it is
     * docked to instead of floating off at the far edge.
     */
    private int[] panelBounds(AbstractContainerScreen<?> screen) {
        if (windowed()) {
            int[] r = windowRect(screen);
            return new int[]{r[0] + WIN_PAD, r[1] + WIN_HEADER + 4,
                    Math.max(0, r[2] - WIN_PAD * 2), Math.max(0, r[3] - WIN_HEADER - 4 - WIN_PAD)};
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        PanelSide side = ConfigManager.getInstance().get().hypixelGui.recipeViewerPosition;
        int x0;
        int x1;
        if (side == PanelSide.LEFT) {
            x0 = PANEL_MARGIN;
            x1 = bounds.skyblockSimplified$leftPos() - PANEL_MARGIN;
        } else {
            x0 = bounds.skyblockSimplified$leftPos() + bounds.skyblockSimplified$imageWidth() + PANEL_MARGIN;
            x1 = screen.width - PANEL_MARGIN;
        }
        int fullW = Math.max(0, x1 - x0);
        int fullH = Math.max(0, barY(screen) - 8 - PANEL_TOP);
        int percent = clamp(ConfigManager.getInstance().get().recipeViewer.panelSize, MIN_PANEL_SIZE, 100);
        if (percent >= 100) {
            return new int[]{x0, PANEL_TOP, fullW, fullH};
        }
        // Never trim below a single column / row - a "compact" panel that shows nothing is just a
        // broken one, and at small GUI scales the free area is only a few cells wide to begin with.
        int w = Math.min(fullW, Math.max(CELL, fullW * percent / 100));
        int h = Math.min(fullH, Math.max(CELL, fullH * percent / 100));
        // LEFT docks against the container's left edge, RIGHT against its right edge.
        return new int[]{side == PanelSide.LEFT ? x1 - w : x0, PANEL_TOP, w, h};
    }

    /**
     * The current item list: the full catalogue, or the search results when there is a query - then
     * narrowed to the kind chip (All / Items / Pets / NPCs). Filtered <b>after</b> the search, so a
     * query and a chip combine. Rebuilt, and scrolled back to the top, when either changes.
     */
    private List<ItemRef> itemList() {
        String query = SearchHighlightState.getInstance().rawQuery();
        RecipeEntryKind.Filter filter = kindFilter();
        // Rebuilt while the UNFILTERED list is empty, as before: the catalogue loads asynchronously and
        // an empty first answer must not stick. Keyed on the base list, so an empty filter result
        // (no pets known yet) does not rebuild every frame.
        if (query.equals(lastQuery) && filter == lastFilter && !baseEmpty) {
            return items;
        }
        lastQuery = query;
        lastFilter = filter;
        scrollRow = 0;
        List<ItemRef> base;
        if (query.isBlank()) {
            List<ItemRef> all = new ArrayList<>();
            for (SkyBlockItemCatalog.Entry entry : SkyBlockItemCatalog.getInstance().allSorted()) {
                all.add(entry.toRef());
            }
            base = all;
        } else {
            base = RecipeRegistry.getInstance().search(query, SEARCH_LIMIT);
        }
        baseEmpty = base.isEmpty();
        items = RecipeEntryKind.filter(base, filter, RecipeOverlay::kindOf);
        return items;
    }

    /** The kind of one entry - {@link RecipeEntryKind#of}, fed from the catalogue and the NPC list. */
    static RecipeEntryKind kindOf(ItemRef ref) {
        SkyBlockItemCatalog.Entry entry = ref.skyblockId == null ? null
                : SkyBlockItemCatalog.getInstance().byId(ref.skyblockId);
        return RecipeEntryKind.of(ref.skyblockId, ref.name, entry == null ? null : entry.category,
                sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider::isPetKey,
                sbs.modid.client.helper.npc.SkyblockNpcs::isNpcEntry);
    }

    private static RecipeEntryKind.Filter kindFilter() {
        return RecipeEntryKind.Filter.byName(ConfigManager.getInstance().get().recipeViewer.kindFilter);
    }

    // ------------------------------------------------------------------
    // Kind chips (All / Items / Pets / NPCs), right-aligned above the search bar
    // ------------------------------------------------------------------

    private static final String[] KIND_LABELS = RecipeEntryKind.Filter.labels();

    private int chipsX(Screen screen) {
        return barX(screen) + BAR_WIDTH - sbs.modid.client.ui.render.ChipSwitch.width(
                Minecraft.getInstance().font, KIND_LABELS);
    }

    private int chipsY(Screen screen) {
        return barY(screen) - sbs.modid.client.ui.render.ChipSwitch.HEIGHT - 2;
    }

    /** The chip row, and "12 pets" to its left when there is room between the bar's edge and the chips. */
    private void drawKindChips(Screen screen, GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int x = chipsX(screen);
        int y = chipsY(screen);
        RecipeEntryKind.Filter filter = kindFilter();
        sbs.modid.client.ui.render.ChipSwitch.draw(g, font, x, y, filter.ordinal(), mouseX, mouseY, KIND_LABELS);
        // "12 pets" when it fits; at 1280x720 GUI scale 4 the row leaves ~50 px, too little for
        // "7461 entries", so the bare number is the fallback (measured, ui/AGENTS.md).
        int room = x - barX(screen) - 4;
        String count = itemList().size() + " " + filter.noun();
        if (font.width(count) > room) {
            count = String.valueOf(itemList().size());
        }
        if (font.width(count) <= room) {
            g.text(font, Component.literal(count), x - 4 - font.width(count),
                    y + (sbs.modid.client.ui.render.ChipSwitch.HEIGHT - font.lineHeight) / 2 + 1, SBSTheme.TEXT_MUTED);
        }
    }

    /** Steps the kind chip by {@code delta} (Tab / Shift-Tab), wrapping. */
    public void cycleKind(int delta) {
        RecipeEntryKind.Filter[] all = RecipeEntryKind.Filter.values();
        int next = Math.floorMod(kindFilter().ordinal() + delta, all.length);
        ConfigManager.getInstance().get().recipeViewer.kindFilter = all[next].name();
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------
    // UI rendering (called from OverlayRenderMixin at the very end of every frame)
    // ------------------------------------------------------------------

    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // The NPC location panel draws over any container screen (it may outlive the Recipe panel).
        sbs.modid.client.helper.npc.NpcLocator.getInstance().render(g);
        lastHovered = null; // re-established below / in renderWindow while the panel is visible
        if (!active()) {
            return;
        }
        drawSearchBar(screen, g, mouseX, mouseY);
        if (!windowed()) {
            drawItemPanel(screen, g, mouseX, mouseY);
        }
    }

    /**
     * The windowed-mode window, rendered in the {@code FloatingWindows} z-order pass at the very
     * end of the frame (so it can stack above/below the browser and item-value windows). The hover
     * tooltip is drawn after that pass, so it stays on top of every window.
     */
    public void renderWindow(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                             int mouseX, int mouseY) {
        if (!active() || !windowed()) {
            return;
        }
        // Before the collapsed branch: a window remembered as collapsed must not flash open for a
        // frame, and while collapsed nothing else here reaches the geometry that would restore it.
        restoreRemembered();
        if (minimized) {
            drawReopenButton(screen, g, mouseX, mouseY);
            return;
        }
        drawWindowChrome(screen, g, mouseX, mouseY);
        drawItemPanel(screen, g, mouseX, mouseY);
    }

    /** The window shell: SBS panel, header (drag handle), size hint and the minimize button. */
    private void drawWindowChrome(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                  int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int[] r = windowRect(screen);
        // Edge/corner resize grips – identical mechanics and look to the Price History window.
        windowResizer.renderGrips(g, r[0], r[1], r[2], r[3], mouseX, mouseY);
        SciFiRender.glow(g, r[0], r[1], r[2], r[3], SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, r[0], r[1], r[2], r[3], SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, r[0] + 1, r[1] + 1, r[2] - 2, r[3] - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = r[1] + (WIN_HEADER - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("Recipe Viewer"), r[0] + WIN_PAD, textY, SBSTheme.ACCENT_BRIGHT);
        String sizeHint = "Ctrl+Scroll = size";
        int hintW = font.width(sizeHint);
        if (r[0] + WIN_PAD + font.width("Recipe Viewer") + 12 + hintW < minimizeX(screen)) {
            g.text(font, Component.literal(sizeHint), minimizeX(screen) - 6 - hintW, textY, SBSTheme.TEXT_MUTED);
        }
        boolean minimizeHover = inMinimizeBox(screen, mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeX(screen) + 4, textY,
                minimizeHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(r[0] + WIN_PAD, r[1] + WIN_HEADER, r[0] + r[2] - WIN_PAD, r[1] + WIN_HEADER + 1,
                SBSTheme.ACCENT_SOFT);
    }

    /** Small window-glyph button right of the search bar that restores the minimized window. */
    private void drawReopenButton(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = barX(screen) + BAR_WIDTH + 4;
        int y = barY(screen);
        boolean hover = inReopenButton(screen, mouseX, mouseY);
        SciFiRender.roundedRectWithBorder(g, x, y, BAR_HEIGHT, BAR_HEIGHT, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        // Tiny window glyph: title bar + body outline in accent.
        int gx = x + 3;
        int gy = y + 4;
        int gw = BAR_HEIGHT - 6;
        int gh = BAR_HEIGHT - 8;
        g.fill(gx, gy, gx + gw, gy + 2, SBSTheme.ACCENT);
        g.outline(gx, gy, gw, gh, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
    }

    /** The panel item currently under the mouse (tracked per rendered frame), or {@code null}. */
    public ItemRef hoveredItem() {
        return active() ? lastHovered : null;
    }

    /**
     * Draws the hovered panel item's tooltip <b>immediately</b>, rather than queueing it into the
     * deferred pipeline.
     *
     * <p>The deferred queue is flushed by vanilla straight after the screen content, but the
     * floating windows ({@code FloatingWindows}) paint after that flush – so a queued tooltip was
     * covered by any window overlapping it. Calling {@code tooltip(...)} directly lets
     * {@code OverlayRenderMixin} render the tooltip as the very last thing in the frame, which puts
     * it above the panel, the windows and the container alike. It still travels through the same
     * {@code GuiGraphicsExtractor#tooltip} choke point, so the Scrollable Tooltips module and the
     * SBS tooltip skin keep applying unchanged.
     */
    public void renderHoverTooltip(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                   int mouseX, int mouseY) {
        if (!active()) {
            return;
        }
        ItemRef hovered = hoveredPanelItem(screen, mouseX, mouseY);
        if (hovered == null) {
            return;
        }
        List<Component> lines = RepoLoreCache.buildTooltip(hovered.iconStack(), hovered.lookupId());
        lines.add(Component.literal("Click to view recipe").withColor(HINT_COLOR));
        lines.add(Component.literal("Right-Click to view usages").withColor(HINT_COLOR));
        if (ConfigManager.getInstance().get().recipeViewer.npcLocator
                && sbs.modid.client.helper.npc.SkyblockNpcs.isNpcEntry(hovered.name)) {
            lines.add(Component.literal("Shift + Click for NPC location").withColor(HINT_COLOR));
        } else {
            lines.add(Component.literal("Shift + Click to open Bazaar / AH").withColor(HINT_COLOR));
        }
        lines.add(Component.literal("Ctrl + Click to open Wiki").withColor(HINT_COLOR));
        lines.add(Component.literal("Alt + Click to open SkyBlock Wiki").withColor(HINT_COLOR));

        Font font = Minecraft.getInstance().font;
        List<ClientTooltipComponent> components = new ArrayList<>(lines.size());
        for (Component line : lines) {
            components.add(ClientTooltipComponent.create(line.getVisualOrderText()));
        }
        g.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE,
                sbs.modid.client.ui.theme.SBSTheme.tooltipStyle());
    }

    /** The panel item currently under the mouse, or {@code null}. */
    private ItemRef hoveredPanelItem(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        if (windowed() && minimized) {
            return null;
        }
        int[] b = panelBounds(screen);
        int cols = b[2] / CELL;
        int rows = b[3] / CELL;
        if (cols < 1 || rows < 1
                || mouseX < b[0] || mouseX >= b[0] + cols * CELL
                || mouseY < b[1] || mouseY >= b[1] + rows * CELL) {
            return null;
        }
        int index = (scrollRow + (int) ((mouseY - b[1]) / CELL)) * cols + (int) ((mouseX - b[0]) / CELL);
        List<ItemRef> list = itemList();
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private void drawSearchBar(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SearchHighlightState state = SearchHighlightState.getInstance();
        Font font = Minecraft.getInstance().font;
        int x = barX(screen);
        int y = barY(screen);

        int border = state.isEnabled() ? SBSTheme.TOGGLE_ON
                : (state.isSearchFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        SciFiRender.roundedRectWithBorder(g, x, y, BAR_WIDTH, BAR_HEIGHT,
                SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, border);

        String raw = state.rawQuery();
        int textX = x + 5;
        int textY = y + (BAR_HEIGHT - font.lineHeight) / 2 + 1;
        if (raw.isEmpty() && !state.isSearchFocused()) {
            g.text(font, Component.literal("Search..."), textX, textY, SBSTheme.TEXT_MUTED);
        } else {
            String shown = font.plainSubstrByWidth(raw, BAR_WIDTH - 12, true);
            if (state.isAllSelected()) { // Ctrl+A selection backdrop
                g.fill(textX - 1, textY - 1, textX + font.width(shown) + 1,
                        textY + font.lineHeight, 0x803FB4FF);
            }
            g.text(font, Component.literal(shown), textX, textY, SBSTheme.TEXT);
            if (state.isSearchFocused() && !state.isAllSelected()
                    && (System.currentTimeMillis() / 500) % 2 == 0) {
                int caretX = textX + font.width(shown) + 1;
                g.fill(caretX, textY - 1, caretX + 1, textY + font.lineHeight, SBSTheme.ACCENT_BRIGHT);
            }
        }
        drawKindChips(screen, g, font, mouseX, mouseY);
        drawMathResult(g, font, screen, mouseX, mouseY);
    }

    /** The text of the result chip for a query, or {@code null} when the query is not a sum. */
    private static String mathChipText(String raw) {
        if (!MathEval.isExpression(raw)) {
            return null;
        }
        Double value = MathEval.eval(raw);
        return value == null ? null : "§8= §b" + MathEval.format(value);
    }

    /**
     * The result chip's rectangle {x, y, w, h} above the search bar, or {@code null} when the query
     * is not a sum. Shared by the renderer and the click handler so the hit box is the drawn box.
     */
    private int[] mathChipRect(Screen screen) {
        String text = mathChipText(SearchHighlightState.getInstance().rawQuery());
        if (text == null) {
            return null;
        }
        Font font = Minecraft.getInstance().font;
        int h = font.lineHeight + 4;
        // Above the kind chips, which own the row directly over the bar.
        return new int[]{barX(screen), chipsY(screen) - h - 2, font.width(text) + 10, h};
    }

    /**
     * Shows the search text's value when it is a sum – {@code 64*1.2m} → {@code = 76.8m}.
     *
     * <p>Drawn in its own chip <b>above</b> the bar rather than inside it: the typed text and the
     * caret already own the bar, and working out "what is a stack worth" is exactly what you are
     * doing while stood in a container.
     *
     * <p>The chip is a button: clicking it writes the result back into the bar (see
     * {@link #handleClick}), so the next operator carries on from it instead of retyping the number.
     */
    private void drawMathResult(GuiGraphicsExtractor g, Font font, Screen screen, int mouseX, int mouseY) {
        int[] chip = mathChipRect(screen);
        String text = mathChipText(SearchHighlightState.getInstance().rawQuery());
        if (chip == null || text == null) {
            return;
        }
        boolean hover = clickableMath() && inMathChip(screen, mouseX, mouseY);
        SciFiRender.roundedRectWithBorder(g, chip[0], chip[1], chip[2], chip[3], SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.SEARCH_FILL,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
        g.text(font, Component.literal(text), chip[0] + 5, chip[1] + 2, SBSTheme.ACCENT_BRIGHT);
    }

    private static boolean clickableMath() {
        return ConfigManager.getInstance().get().calculator.clickableSearchResult;
    }

    private boolean inMathChip(Screen screen, double mx, double my) {
        int[] chip = mathChipRect(screen);
        return chip != null && mx >= chip[0] && mx < chip[0] + chip[2]
                && my >= chip[1] && my < chip[1] + chip[3];
    }

    private void drawItemPanel(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] b = panelBounds(screen);
        int cols = b[2] / CELL;
        int rows = b[3] / CELL;
        if (cols < 1 || rows < 1) {
            return; // no room beside this container at this GUI scale
        }
        Font font = Minecraft.getInstance().font;
        List<ItemRef> list = itemList();
        int totalRows = (list.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, totalRows - rows);
        scrollRow = Math.min(scrollRow, maxScroll);

        ItemRef hovered = null;
        int start = scrollRow * cols;
        for (int i = 0; i < cols * rows && start + i < list.size(); i++) {
            ItemRef ref = list.get(start + i);
            int cx = b[0] + (i % cols) * CELL;
            int cy = b[1] + (i / cols) * CELL;
            boolean over = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
            if (over) {
                hovered = ref;
                SciFiRender.roundedRect(g, cx, cy, CELL - 1, CELL - 1, SBSTheme.CORNER_RADIUS,
                        SBSTheme.CARD_BG_HOVER);
            }
            g.item(ref.displayStack(), cx + 1, cy + 1);
        }
        lastHovered = hovered;

        // The hover tooltip itself is queued in prepareHoverTooltip (HEAD of the render pipeline –
        // a TAIL set would miss the deferred flush); here only the row indicator is drawn. In
        // windowed mode the indicator's spot above the grid is the window header – skip it there.
        if (hovered == null && maxScroll > 0 && !windowed()) {
            g.text(font, Component.literal((scrollRow + 1) + "-" + Math.min(totalRows, scrollRow + rows)
                    + " / " + totalRows), b[0], b[1] - font.lineHeight - 2, SBSTheme.TEXT_MUTED);
        }
    }

    // ------------------------------------------------------------------
    // Click / scroll logic (forwarded from ContainerSearchBarMixin)
    // ------------------------------------------------------------------

    /**
     * Windowed-mode window input, dispatched via the {@code FloatingWindows} z-order (before the
     * static overlay parts). Everything inside the window is consumed so no click reaches a
     * container slot underneath; everything outside passes through untouched.
     */
    public boolean handleWindowClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active() || !windowed()) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (inReopenButton(screen, mx, my)) {
                minimized = false;
                rememberWindow();
                return true;
            }
            return false;
        }
        if (!inWindow(screen, mx, my)) {
            return false; // outside the window – the container stays fully usable
        }
        SearchHighlightState.getInstance().setSearchFocused(false);
        if (inMinimizeBox(screen, mx, my) && event.button() == 0) {
            minimized = true;
            rememberWindow();
            return true;
        }
        int[] r = windowRect(screen);
        if (event.button() == 0 && windowResizer.begin(mx, my, r[0], r[1], r[2], r[3])) {
            return true;
        }
        if (my < r[1] + WIN_HEADER && event.button() == 0) {
            draggingWindow = true;
            grabDX = mx - r[0];
            grabDY = my - r[1];
            return true;
        }
        gridClick(screen, event);
        return true; // clicks inside the window that hit no item are swallowed
    }

    /** Handles a mouse click on the search bar / static panel; returns true when consumed. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event, boolean doubled) {
        if (!active()) {
            return false;
        }
        SearchHighlightState state = SearchHighlightState.getInstance();
        // The result chip is a button: it replaces the query with its own value, so the sum can be
        // continued ("64*1.2m" → "76800000" → "+5k"). The value goes in as plain digits, never as
        // the grouped display form – "7,500" would read back as 7.5.
        if (clickableMath() && event.button() == 0 && inMathChip(screen, event.x(), event.y())) {
            Double value = MathEval.eval(state.rawQuery());
            if (value != null) {
                state.setQuery(MathEval.toInput(value));
                state.setSearchFocused(true);
                return true;
            }
        }
        if (event.button() == 0) {
            int chip = sbs.modid.client.ui.render.ChipSwitch.hit(Minecraft.getInstance().font,
                    chipsX(screen), chipsY(screen), event.x(), event.y(), KIND_LABELS);
            if (chip >= 0) {
                ConfigManager.getInstance().get().recipeViewer.kindFilter =
                        RecipeEntryKind.Filter.values()[chip].name();
                ConfigManager.getInstance().save();
                return true;
            }
        }
        if (inBar(screen, event.x(), event.y())) {
            if (doubled) {
                state.setEnabled(!state.isEnabled()); // double-click highlight toggle
            }
            state.setSearchFocused(true);
            return true;
        }
        state.setSearchFocused(false); // clicking anywhere else releases focus (click still processed)
        if (windowed()) {
            return false; // the window had its turn in the z-ordered pass
        }
        return gridClick(screen, event);
    }

    /** A click on the item grid (static panel or window content); true when an item action ran. */
    private boolean gridClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        double mx = event.x();
        double my = event.y();
        int[] b = panelBounds(screen);
        int cols = b[2] / CELL;
        int rows = b[3] / CELL;
        if (cols < 1 || rows < 1 || mx < b[0] || mx >= b[0] + cols * CELL || my < b[1] || my >= b[1] + rows * CELL) {
            return false;
        }
        int index = (scrollRow + (int) ((my - b[1]) / CELL)) * cols + (int) ((mx - b[0]) / CELL);
        List<ItemRef> list = itemList();
        if (index < 0 || index >= list.size()) {
            return false;
        }
        ItemRef item = list.get(index);

        // Ctrl + Click: official Hypixel Wiki, Alt + Click: community SkyBlock wiki
        // (hypixelskyblock.minecraft.wiki) – both in an in-game browser window (like the price
        // history, but token-free); external link flow as fallback.
        if (event.hasControlDown()) {
            openWikiWindow(screen, ItemWikiLinks.officialUrl(item.name),
                    "Wiki: " + ItemWikiLinks.cleanName(item.name));
            return true;
        }
        if (event.hasAltDown()) {
            openWikiWindow(screen, ItemWikiLinks.skyblockWikiUrl(item.name),
                    "SkyBlock Wiki: " + ItemWikiLinks.cleanName(item.name));
            return true;
        }
        // Shift + Click on an NPC entry ("... (NPC)"): open the location panel + pathfind there,
        // instead of the Bazaar / AH search (an NPC is not a tradeable item).
        if (event.hasShiftDown()
                && ConfigManager.getInstance().get().recipeViewer.npcLocator
                && sbs.modid.client.helper.npc.SkyblockNpcs.isNpcEntry(item.name)) {
            sbs.modid.client.helper.npc.NpcLocator.getInstance().show(item.name);
            return true;
        }
        // Shift + Click: /bz for bazaar items, /ahsearch for everything else.
        if (event.hasShiftDown()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                String id = item.lookupId();
                boolean bazaar = BazaarPriceCache.getInstance().getBuy(id) != null
                        || ChatPriceCache.getInstance().getUnitPrice(id) != null;
                minecraft.player.connection.sendCommand(
                        (bazaar ? "bz " : "ahsearch ") + item.name.trim().toLowerCase(Locale.ROOT));
            }
            return true;
        }
        // Plain click: recipe view; right-click: usages.
        Minecraft.getInstance().setScreenAndShow(new RecipeViewerScreen(item, event.button() == 1));
        return true;
    }

    /**
     * Opens a wiki page in a new in-game browser window; if the browser engine is unavailable or
     * the window limit is reached, the URL goes through Minecraft's native external link flow.
     */
    private static void openWikiWindow(AbstractContainerScreen<?> screen, String url, String title) {
        if (!sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager.getInstance().openNewUrl(url, title)) {
            ConfirmLinkScreen.confirmLinkNow(screen, url);
        }
    }

    /** Window drag: while the header is grabbed, the window follows the mouse (stays on screen). */
    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (windowResizer.isActive()) {
            int[] rect = windowResizer.drag(event.x(), event.y(), WIN_MIN_W, WIN_MAX_W, WIN_MIN_H, WIN_MAX_H);
            winX = rect[0];
            winY = rect[1];
            winW = rect[2];
            winH = rect[3];
            windowRect(screen); // re-clamp into the screen
            return true;
        }
        if (!draggingWindow) {
            return false;
        }
        winX = (int) (event.x() - grabDX);
        winY = (int) (event.y() - grabDY);
        int[] snapped = sbs.modid.client.ui.window.EdgeSnap.toBorders(
                winX, winY, winW, winH, screen.width, screen.height, WIN_MARGIN);
        winX = snapped[0];
        winY = snapped[1];
        windowRect(screen); // re-clamp into the screen
        return true;
    }

    /** Ends a window drag / resize on mouse release. */
    public boolean handleRelease() {
        boolean wasResizing = windowResizer.end();
        if (!draggingWindow && !wasResizing) {
            return false;
        }
        draggingWindow = false;
        rememberWindow();
        return true;
    }

    /** Takes the window back to where the player last left it (first call only). */
    private void restoreRemembered() {
        memory.restore(state -> {
            winX = state.x;
            winY = state.y;
            winW = state.width(winW);
            winH = state.height(winH);
            minimized = state.minimized;
        });
    }

    /** Persists position, size and collapsed state so the window comes back where it was left. */
    private void rememberWindow() {
        memory.remember(winX, winY, winW, winH, minimized);
    }

    /**
     * Windowed-mode wheel input (z-order dispatched): Ctrl+scroll resizes the window
     * (browser-window convention), any other scroll inside the window scrolls the item grid.
     */
    public boolean handleWindowScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY,
                                      double scrollY) {
        if (!active() || !windowed() || scrollY == 0
                || minimized || !inWindow(screen, mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            winW = clamp(winW + step * 30, WIN_MIN_W, WIN_MAX_W);
            winH = clamp(winH + step * 20, WIN_MIN_H, WIN_MAX_H);
            rememberWindow();
            return true;
        }
        scrollGrid(screen, scrollY);
        return true;
    }

    /** Handles mouse-wheel scrolling over the static item panel; returns true when consumed. */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY, double scrollY) {
        if (!active() || scrollY == 0 || windowed()) {
            return false; // windowed: the window had its turn in the z-ordered pass
        }
        int[] b = panelBounds(screen);
        if (mouseX < b[0] || mouseX > b[0] + b[2] || mouseY < b[1] || mouseY > b[1] + b[3]) {
            return false;
        }
        scrollGrid(screen, scrollY);
        return true;
    }

    private void scrollGrid(AbstractContainerScreen<?> screen, double scrollY) {
        int[] b = panelBounds(screen);
        int cols = Math.max(1, b[2] / CELL);
        int rows = Math.max(1, b[3] / CELL);
        int totalRows = (itemList().size() + cols - 1) / cols;
        int maxScroll = Math.max(0, totalRows - rows);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow - (int) Math.signum(scrollY)));
    }
}
