/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.pricehistory.ui.PriceHistoryScreen;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.model.ItemFilter;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.logic.RecipeRegistry;
import sbs.modid.client.economy.recipe.model.SbsRecipe;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;
import sbs.modid.client.core.util.MathEval;
import sbs.modid.client.economy.recipe.logic.SupercraftHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The Recipe Viewer: SBS-styled item search plus recipe display.
 *
 * <p>Two modes inside one panel: a scrollable search-result list (icon + name + id) under a row of
 * {@link ItemFilter} category buttons – weapons, armor, equipment, mobs &amp; NPCs, ... – which
 * narrow the list and, with an empty search box, turn it into a browsable category listing; and –
 * once an item is clicked – a detail view showing the 3x3 recipe layout, the result, aggregated
 * ingredient amounts, acquisition methods, and Prev/Next switching when multiple recipes exist. The
 * filter row belongs to the list mode only, so the detail view keeps its full height. Clicking an
 * ingredient in the grid jumps to <i>its</i> recipes. All drawing reuses
 * {@link SciFiRender}/{@link SBSTheme}; data comes from the cached {@link RecipeRegistry} so every
 * interaction is a map lookup.
 *
 * <p><b>Search Highlight Mode</b>: double-clicking the search bar toggles it; while on, matching
 * items in any open container are highlighted by the existing container-highlight render pass.
 * Closing this screen always disables it.
 */
public final class RecipeViewerScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int CELL = 20;
    private static final int RESULT_LIMIT = 100;
    /**
     * Cap for a category browse. Higher than {@link #RESULT_LIMIT} because "show me all armor" is
     * meant to be a complete list to scroll through, not a top-N answer to a query.
     */
    private static final int BROWSE_LIMIT = 400;

    /** The filter buttons, laid out as {@link #FILTER_COLUMNS} per row above the result list. */
    private static final ItemFilter[] FILTERS = ItemFilter.values();
    private static final int FILTER_COLUMNS = 4;
    private static final int CHIP_HEIGHT = 13;
    private static final int CHIP_GAP = 2;

    private final RecipeRegistry registry = RecipeRegistry.getInstance();

    private String query;
    private ItemFilter filter = ItemFilter.ALL;
    private List<ItemRef> results = new ArrayList<>();
    private int scrollOffset;

    private ItemRef selected;
    private List<SbsRecipe> recipes = List.of();
    private int recipeIndex;

    private EditBox searchBox;

    // Cached layout.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int searchY;
    private int filterTop;
    private int listTop;
    /** Detail-view content top – the filter row is list-mode only, so the detail starts higher. */
    private int detailTop;
    private int listBottom;
    private int backY;
    // Detail-mode manual buttons (hit-tested, not widgets).
    private int navY;
    private int supercraftY;

    /** Where the viewer was opened from – decides where the final "Back" step returns to. */
    private enum Origin {
        /** Opened from the SBS module overlay → Back returns to the module screen. */
        MODULE,
        /** Opened from the inventory overlay (panel / search bar / R key) → Back reopens the inventory. */
        INVENTORY
    }

    /** One step of in-viewer navigation history (a previously viewed recipe state). */
    private record DetailState(ItemRef selected, List<SbsRecipe> recipes, int recipeIndex) {
    }

    /** Stack-based one-step-back history: pushed whenever a sub-item is opened from a recipe. */
    private final Deque<DetailState> history = new ArrayDeque<>();

    private final Origin origin;

    /** Pre-selected item (from the side panel / R key); applied on init, surviving resizes. */
    private ItemRef pendingSelect;
    private boolean pendingUsages;

    /** The icon/row under the mouse in the last rendered frame (for the price-history hotkey). */
    private ItemRef priceKeyHovered;

    /** Opened from the SBS module overlay. */
    public RecipeViewerScreen() {
        super(Component.literal("Recipe Viewer"));
        this.query = "";
        this.origin = Origin.MODULE;
    }

    /** Opens the viewer pre-seeded with a query (used by the R-key lookup on hovered items). */
    public RecipeViewerScreen(String initialQuery) {
        super(Component.literal("Recipe Viewer"));
        this.query = initialQuery == null ? "" : initialQuery;
        this.origin = Origin.INVENTORY;
    }

    /** Opens the viewer directly on an item's recipes (or its usages via right-click). */
    public RecipeViewerScreen(ItemRef item, boolean usages) {
        super(Component.literal("Recipe Viewer"));
        this.query = item != null ? item.name : "";
        this.origin = Origin.INVENTORY;
        this.pendingSelect = item;
        this.pendingUsages = usages;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_WIDTH, SBSTheme.PANEL_MAX_WIDTH);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, SBSTheme.PANEL_MIN_HEIGHT, SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;

        searchY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        detailTop = searchY + SBSTheme.SEARCH_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        filterTop = searchY + SBSTheme.SEARCH_HEIGHT + 4;
        listTop = filterTop + filterRows() * (CHIP_HEIGHT + CHIP_GAP) + SBSTheme.GAP_AFTER_SEARCH;
        listBottom = backY - SBSTheme.GAP_AFTER_SEARCH;
        navY = listBottom - SBSTheme.SEARCH_HEIGHT;
        supercraftY = navY - SBSTheme.SEARCH_HEIGHT - 4;

        addRenderableOnly(new PanelRenderable());

        int textHeight = this.font.lineHeight;
        int editY = searchY + (SBSTheme.SEARCH_HEIGHT - textHeight) / 2;
        searchBox = new EditBox(this.font, innerX + 13, editY, contentWidth - 17, textHeight,
                Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(128);
        searchBox.setTextColor(SBSTheme.TEXT);
        searchBox.setHint(Component.literal("Search items & recipes... (double-click: highlight mode)"));
        searchBox.setValue(query);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));

        setInitialFocus(searchBox);
        onSearchChanged(query);

        // Direct open from the side panel / R key: jump straight to the item's recipes or usages.
        if (pendingSelect != null) {
            this.selected = pendingSelect;
            this.recipes = pendingUsages
                    ? registry.usagesFor(pendingSelect.lookupId())
                    : registry.recipesFor(pendingSelect.lookupId());
            this.recipeIndex = 0;
        }
        petLevelSlider = null;   // init cleared every widget
        petSliderFor = null;
        syncPetControls();
    }

    // ------------------------------------------------------------------
    // Search / selection
    // ------------------------------------------------------------------

    private void onSearchChanged(String value) {
        this.query = value;
        SearchHighlightState.getInstance().setQuery(value);
        refreshResults();
        this.selected = null; // typing returns to the result list
        syncPetControls();
        this.history.clear(); // a new search starts a fresh navigation history
    }

    /** Re-runs the query through the active filter (a filter alone is a valid, blank-query browse). */
    private void refreshResults() {
        this.results = filter == ItemFilter.ALL
                ? registry.search(query, RESULT_LIMIT)
                : registry.browse(query, filter, BROWSE_LIMIT);
        this.scrollOffset = 0;
    }

    /** Clicking the active filter again clears it, so one button is always enough to get back. */
    private void selectFilter(ItemFilter picked) {
        this.filter = this.filter == picked ? ItemFilter.ALL : picked;
        refreshResults();
        this.selected = null; // a changed filter means the result list, not a stale detail view
        syncPetControls();
        this.history.clear();
    }

    private static int filterRows() {
        return (FILTERS.length + FILTER_COLUMNS - 1) / FILTER_COLUMNS;
    }

    /** Left edge of chip {@code index}; the columns divide {@link #contentWidth} exactly. */
    private int chipX(int index) {
        return innerX + (index % FILTER_COLUMNS) * contentWidth / FILTER_COLUMNS;
    }

    private int chipWidth(int index) {
        int column = index % FILTER_COLUMNS;
        return (column + 1) * contentWidth / FILTER_COLUMNS - column * contentWidth / FILTER_COLUMNS - CHIP_GAP;
    }

    private int chipY(int index) {
        return filterTop + (index / FILTER_COLUMNS) * (CHIP_HEIGHT + CHIP_GAP);
    }

    // ------------------------------------------------------------------ pet controls

    /** The level slider, present only while a pet's detail view is open. */
    private sbs.modid.client.ui.component.SciFiRangeSlider petLevelSlider;
    /** Which pet the slider was built for (its range is that pet's max level). */
    private String petSliderFor;

    /** The pet key of the open detail view, or {@code null} when it is not a pet. */
    private String selectedPet() {
        String id = selected == null ? null : selected.lookupId();
        return id != null && sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider.isPetKey(id) ? id : null;
    }

    private int petChipsY() {
        return detailTop + this.font.lineHeight + 8;
    }

    private int petSliderY() {
        return petChipsY() + sbs.modid.client.ui.render.ChipSwitch.HEIGHT + 4;
    }

    /**
     * Adds, rebuilds or removes the level slider so it matches the open detail view. Called wherever
     * the selection changes - never from the render pass, which is iterating the widget list.
     */
    private void syncPetControls() {
        String pet = selectedPet();
        if (java.util.Objects.equals(pet, petSliderFor) && (pet == null) == (petLevelSlider == null)) {
            return;
        }
        if (petLevelSlider != null) {
            removeWidget(petLevelSlider);
            petLevelSlider = null;
        }
        petSliderFor = pet;
        if (pet == null) {
            return;
        }
        int max = sbs.modid.client.economy.recipe.logic.PetData.maxLevel(pet);
        int level = sbs.modid.client.economy.recipe.logic.PetData.chosen(pet).level();
        petLevelSlider = new sbs.modid.client.ui.component.SciFiRangeSlider(innerX, petSliderY(), contentWidth,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Level"), 1, max, level, "",
                value -> sbs.modid.client.economy.recipe.logic.PetData.choose(pet,
                        sbs.modid.client.economy.recipe.logic.PetData.chosen(pet).rarity(), value));
        addRenderableWidget(petLevelSlider);
    }

    /** Rarity chip labels for the pet: full names when they fit the panel, short ones otherwise. */
    private String[] rarityLabels(java.util.List<Integer> rarities) {
        String[] full = new String[rarities.size()];
        String[] shortNames = new String[rarities.size()];
        for (int i = 0; i < rarities.size(); i++) {
            String name = sbs.modid.client.economy.recipe.logic.PetLore.RARITIES[rarities.get(i)];
            full[i] = name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
            shortNames[i] = full[i].length() > 4 ? full[i].substring(0, 3) : full[i];
        }
        return sbs.modid.client.ui.render.ChipSwitch.width(this.font, full) <= contentWidth ? full : shortNames;
    }

    private void select(ItemRef ref) {
        // Navigating into a sub-item from an open recipe pushes the current view onto the history
        // stack, so "Back" returns exactly one step, never to the very start.
        if (this.selected != null) {
            history.push(new DetailState(this.selected, this.recipes, this.recipeIndex));
        }
        this.selected = ref;
        syncPetControls();
        this.recipes = registry.recipesFor(ref.lookupId());
        this.recipeIndex = 0;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    /**
     * Stack-based one-step-back navigation:
     * <ol>
     *   <li>a previously viewed recipe on the history stack → restore it,</li>
     *   <li>otherwise an open detail view → back to the result list,</li>
     *   <li>otherwise leave the viewer toward where it was opened from: the SBS module overlay, or
     *       the player inventory (whose overlay search bar is the other entry point).</li>
     * </ol>
     */
    private void onBack() {
        if (!history.isEmpty()) {
            DetailState state = history.pop();
            this.selected = state.selected();
            syncPetControls();
            this.recipes = state.recipes();
            this.recipeIndex = state.recipeIndex();
            return;
        }
        if (selected != null) {
            selected = null;
            syncPetControls();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (origin == Origin.MODULE) {
            minecraft.setScreenAndShow(new RecipeViewerModuleScreen());
        } else {
            minecraft.setScreenAndShow(minecraft.player != null
                    ? new InventoryScreen(minecraft.player) : null);
        }
    }

    @Override
    public void removed() {
        // Closing the Recipe Viewer always disables Search Highlight Mode.
        SearchHighlightState.getInstance().setEnabled(false);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        boolean handled = super.mouseClicked(event, doubled);
        double mx = event.x();
        double my = event.y();

        // Double-click on the search bar toggles Search Highlight Mode.
        if (doubled && mx >= innerX && mx <= innerX + contentWidth
                && my >= searchY && my <= searchY + SBSTheme.SEARCH_HEIGHT) {
            SearchHighlightState state = SearchHighlightState.getInstance();
            state.setEnabled(!state.isEnabled());
            return true;
        }
        if (handled) {
            return true;
        }

        String pet = selectedPet();
        if (pet != null && event.button() == 0) {
            java.util.List<Integer> rarities = sbs.modid.client.economy.recipe.logic.PetData.raritiesOf(pet);
            int chip = sbs.modid.client.ui.render.ChipSwitch.hit(this.font, innerX, petChipsY(), mx, my,
                    rarityLabels(rarities));
            if (chip >= 0) {
                sbs.modid.client.economy.recipe.logic.PetData.choose(pet, rarities.get(chip),
                        sbs.modid.client.economy.recipe.logic.PetData.chosen(pet).level());
                return true;
            }
        }

        if (selected == null) {
            // Category filter row (drawn above the list, list mode only).
            for (int i = 0; i < FILTERS.length; i++) {
                int cx = chipX(i);
                int cy = chipY(i);
                if (mx >= cx && mx < cx + chipWidth(i) && my >= cy && my < cy + CHIP_HEIGHT) {
                    selectFilter(FILTERS[i]);
                    return true;
                }
            }
            // Result-list row click.
            if (mx >= innerX && mx <= innerX + contentWidth && my >= listTop && my <= listBottom) {
                int index = scrollOffset + (int) ((my - listTop) / ROW_HEIGHT);
                if (index >= 0 && index < results.size()) {
                    select(results.get(index));
                    return true;
                }
            }
            return false;
        }

        // Detail mode: Supercraft button (auto-craft via Hypixel's own menu).
        if (sbs$canSupercraft() && mx >= innerX && mx <= innerX + contentWidth
                && my >= supercraftY && my <= supercraftY + SBSTheme.SEARCH_HEIGHT) {
            SupercraftHelper.getInstance().request(selected.lookupId());
            // Close the overlay so the server-side recipe menu can open on top.
            Minecraft.getInstance().setScreenAndShow(null);
            return true;
        }

        // Detail mode: nav "buttons" and grid-cell navigation.
        if (my >= navY && my <= navY + SBSTheme.SEARCH_HEIGHT) {
            int third = contentWidth / 3;
            if (mx >= innerX && mx < innerX + third) {                    // < Prev
                if (recipes.size() > 1) {
                    recipeIndex = (recipeIndex - 1 + recipes.size()) % recipes.size();
                }
                return true;
            }
            if (mx >= innerX + third && mx < innerX + third * 2) {        // Results (clears history)
                selected = null;
                syncPetControls();
                history.clear();
                return true;
            }
            if (mx >= innerX + third * 2 && mx <= innerX + contentWidth) { // Next >
                if (recipes.size() > 1) {
                    recipeIndex = (recipeIndex + 1) % recipes.size();
                }
                return true;
            }
        }
        SbsRecipe recipe = currentRecipe();
        if (recipe != null && recipe.grid != null) {
            int gridX = innerX + 8;
            int gridY = detailTop + this.font.lineHeight + 8;
            for (int i = 0; i < 9; i++) {
                ItemRef cell = recipe.grid[i];
                if (cell == null) {
                    continue;
                }
                int cx = gridX + (i % 3) * CELL;
                int cy = gridY + (i / 3) * CELL;
                if (mx >= cx && mx <= cx + 18 && my >= cy && my <= cy + 18) {
                    select(cell); // jump to the ingredient's own recipes
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // A hovered, screen-overflowing item tooltip scrolls its own lore first (this is a plain
        // Screen, so it is not covered by the container-side tooltip scroll routing).
        if (sbs.modid.client.helper.tooltip.ScrollableTooltips.getInstance().onMouseScroll(scrollY)) {
            return true;
        }
        if (selected == null && !results.isEmpty()) {
            int maxOffset = Math.max(0, results.size() - visibleRows());
            scrollOffset = clamp((int) (scrollOffset - Math.signum(scrollY)), 0, maxOffset);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The Item Price History hotkey: pressed over any item icon/row it opens that item's page. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        int openKey = ConfigManager.getInstance().get().priceHistory.openKey;
        if (openKey != 0 && event.key() == openKey
                && (searchBox == null || !searchBox.isFocused())
                && priceKeyHovered != null) {
            Minecraft.getInstance().setScreenAndShow(new PriceHistoryScreen(
                    sbs.modid.client.economy.pricehistory.logic.PriceLookup.candidatesFor(priceKeyHovered)));
            return true;
        }
        return super.keyPressed(event);
    }

    private SbsRecipe currentRecipe() {
        return (recipes.isEmpty() || recipeIndex >= recipes.size()) ? null : recipes.get(recipeIndex);
    }

    /**
     * The enchant KEY of a grouped enchant-book result row, or {@code null} when grouping is off or the
     * row is not an enchant book – decides whether the hover shows the group tooltip or the item one.
     */
    private String groupTooltipKey(ItemRef ref) {
        if (ref == null
                || !ConfigManager.getInstance().get().recipeViewer.groupEnchants) {
            return null;
        }
        return sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider.keyOf(ref.lookupId());
    }

    /** Supercraft needs a selected SkyBlock item with a known recipe. */
    private boolean sbs$canSupercraft() {
        return selected != null && selected.skyblockId != null && !selected.skyblockId.isEmpty()
                && currentRecipe() != null;
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
            var font = RecipeViewerScreen.this.font;
            priceKeyHovered = null; // re-established below by whichever icon/row is hovered

            g.fill(0, 0, RecipeViewerScreen.this.width, RecipeViewerScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Recipe Viewer"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            // Search field background; the frame turns accent-green while highlight mode is on.
            boolean highlight = SearchHighlightState.getInstance().isEnabled();
            int searchBorder = highlight ? SBSTheme.TOGGLE_ON
                    : (searchBox != null && searchBox.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            SciFiRender.roundedRectWithBorder(g, innerX, searchY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, searchBorder);
            int iconX = innerX + 4;
            int iconY = searchY + (SBSTheme.SEARCH_HEIGHT - 5) / 2;
            g.outline(iconX, iconY, 5, 5, highlight ? SBSTheme.TOGGLE_ON : SBSTheme.ACCENT);
            g.fill(iconX + 4, iconY + 4, iconX + 6, iconY + 6, highlight ? SBSTheme.TOGGLE_ON : SBSTheme.ACCENT);

            if (selected == null) {
                drawFilters(g, mouseX, mouseY);
                drawResults(g, mouseX, mouseY);
            } else {
                drawDetail(g, mouseX, mouseY);
            }
        }

        /** The category button row: the active one is accent-framed, the rest read as muted chips. */
        private void drawFilters(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = RecipeViewerScreen.this.font;
            for (int i = 0; i < FILTERS.length; i++) {
                ItemFilter entry = FILTERS[i];
                int x = chipX(i);
                int y = chipY(i);
                int w = chipWidth(i);
                boolean active = entry == filter;
                boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CHIP_HEIGHT;
                SciFiRender.roundedRectWithBorder(g, x, y, w, CHIP_HEIGHT, SBSTheme.CORNER_RADIUS,
                        active ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        active ? SBSTheme.ACCENT : (hovered ? SBSTheme.ACCENT_SOFT : SBSTheme.CARD_BORDER));
                // The panel is narrow at small GUI scales - clip rather than spill over the border.
                String label = font.plainSubstrByWidth(entry.displayName(), w - 4, false);
                g.centeredText(font, Component.literal(label), x + w / 2,
                        y + (CHIP_HEIGHT - font.lineHeight) / 2,
                        active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
            }
        }

        /** Queues the real item tooltip for whatever icon is hovered (set inside the screen's own
         *  extract pass, i.e. before the deferred-tooltip flush – so it reliably renders). */
        private void hoverTooltip(GuiGraphicsExtractor g, sbs.modid.client.economy.recipe.model.ItemRef ref,
                                  int mouseX, int mouseY) {
            g.setTooltipForNextFrame(RecipeViewerScreen.this.font,
                    sbs.modid.client.economy.recipe.logic.RepoLoreCache.buildTooltip(ref.iconStack(), ref.lookupId()),
                    Optional.empty(), mouseX, mouseY,
                    sbs.modid.client.ui.theme.SBSTheme.tooltipStyle());
        }

        /**
         * Shows the search box's query as a sum when it is one – {@code 64*1.2m} → {@code = 76.8m}.
         * Handy right here, because working out "how much is a stack worth" is what you are doing
         * anyway while looking at a recipe.
         *
         * @return whether anything was drawn (i.e. the query really was maths)
         */
        private boolean drawMathResult(GuiGraphicsExtractor g, int y, boolean centered) {
            if (!MathEval.isExpression(query)) {
                return false;
            }
            Double value = MathEval.eval(query);
            if (value == null) {
                return false;
            }
            var font = RecipeViewerScreen.this.font;
            String text = "§7" + query.trim() + " §8= §b" + MathEval.format(value);
            if (centered) {
                g.centeredText(font, Component.literal(text), panelX + panelW / 2, y,
                        SBSTheme.ACCENT_BRIGHT);
            } else {
                g.text(font, Component.literal(text), innerX, y, SBSTheme.ACCENT_BRIGHT);
            }
            return true;
        }

        private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = RecipeViewerScreen.this.font;
            if (results.isEmpty()) {
                // A maths query has no item matches, so its result IS the answer here.
                if (drawMathResult(g, listTop + 10, true)) {
                    return;
                }
                String hint;
                if (query != null && !query.isBlank()) {
                    hint = "No matching items found.";
                } else if (filter != ItemFilter.ALL) {
                    // Blank query + filter is a browse, so an empty list means the category itself
                    // is empty - most likely the catalogue has not been fetched yet.
                    hint = "No " + filter.displayName().toLowerCase(java.util.Locale.ROOT)
                            + " in the catalogue (yet).";
                } else {
                    hint = "Type to search, or pick a category above.";
                }
                g.centeredText(font, Component.literal(hint), panelX + panelW / 2,
                        listTop + 10, SBSTheme.TEXT_MUTED);
                return;
            }
            int rows = visibleRows();
            for (int row = 0; row < rows; row++) {
                int index = scrollOffset + row;
                if (index >= results.size()) {
                    break;
                }
                ItemRef ref = results.get(index);
                int y = listTop + row * ROW_HEIGHT;
                boolean hovered = mouseY >= y && mouseY < y + ROW_HEIGHT
                        && mouseX >= innerX && mouseX <= innerX + contentWidth;
                if (hovered) {
                    SciFiRender.roundedRect(g, innerX, y, contentWidth, ROW_HEIGHT - 2,
                            SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG_HOVER);
                    String groupKey = groupTooltipKey(ref);
                    if (groupKey != null) {
                        g.setTooltipForNextFrame(font,
                                sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider.groupTooltip(groupKey),
                                Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
                    } else {
                        hoverTooltip(g, ref, mouseX, mouseY);
                    }
                    priceKeyHovered = ref;
                }
                g.item(ref.displayStack(), innerX + 2, y + 1);
                int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
                g.text(font, Component.literal(ref.name), innerX + 24, textY, SBSTheme.TEXT);
                String id = ref.lookupId();
                g.text(font, Component.literal(id), innerX + contentWidth - 6 - font.width(id), textY,
                        SBSTheme.TEXT_MUTED);
            }
            if (results.size() > rows) {
                String more = (scrollOffset + rows) + " / " + results.size();
                g.centeredText(font, Component.literal(more), panelX + panelW / 2,
                        listBottom + 2, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawDetail(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = RecipeViewerScreen.this.font;
            int y = detailTop;

            g.item(selected.displayStack(), innerX, y - 2);
            g.text(font, Component.literal(selected.name), innerX + 22, y + 2, SBSTheme.ACCENT_BRIGHT);
            y += font.lineHeight + 8;

            String pet = selectedPet();
            if (pet != null) {
                y = drawPetSection(g, pet, mouseX, mouseY);
            }
            SbsRecipe recipe = pet != null ? null : currentRecipe();
            if (pet != null) {
                // the pet section above replaces the recipe block
            } else if (recipe == null) {
                g.text(font, Component.literal("No crafting recipe known for this item."),
                        innerX, y, SBSTheme.TEXT_MUTED);
                g.text(font, Component.literal("It may be obtained another way (see below)."),
                        innerX, y + font.lineHeight + 2, SBSTheme.TEXT_MUTED);
                y += (font.lineHeight + 2) * 2 + 6;
            } else {
                // 3x3 grid + arrow + result (each icon hover queues its real item tooltip).
                int gridX = innerX + 8;
                for (int i = 0; i < 9; i++) {
                    int cx = gridX + (i % 3) * CELL;
                    int cy = y + (i / 3) * CELL;
                    SciFiRender.roundedRectWithBorder(g, cx - 1, cy - 1, 18, 18,
                            SBSTheme.SLOT_CORNER, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
                    ItemRef cell = recipe.grid != null ? recipe.grid[i] : null;
                    if (cell != null) {
                        ItemStack cellIcon = cell.displayStack();
                        g.item(cellIcon, cx, cy);
                        g.itemDecorations(font, cellIcon, cx, cy);
                        if (mouseX >= cx && mouseX < cx + 18 && mouseY >= cy && mouseY < cy + 18) {
                            hoverTooltip(g, cell, mouseX, mouseY);
                            priceKeyHovered = cell;
                        }
                    }
                }
                int arrowX = gridX + 3 * CELL + 10;
                int midY = y + CELL + 4;
                g.text(font, Component.literal("->"), arrowX, midY, SBSTheme.ACCENT);
                int resX = arrowX + 20;
                SciFiRender.roundedRectWithBorder(g, resX - 1, midY - 5, 18, 18,
                        SBSTheme.SLOT_CORNER, SBSTheme.CARD_BG, SBSTheme.ACCENT);
                ItemStack resultIcon = recipe.result.displayStack();
                g.item(resultIcon, resX, midY - 4);
                g.itemDecorations(font, resultIcon, resX, midY - 4);
                if (mouseX >= resX && mouseX < resX + 18 && mouseY >= midY - 4 && mouseY < midY + 14) {
                    hoverTooltip(g, recipe.result, mouseX, mouseY);
                    priceKeyHovered = recipe.result;
                }

                // Ingredient amounts summary to the right / below.
                int infoY = y + 3 * CELL + 6;
                StringBuilder counter = new StringBuilder();
                for (ItemRef ing : recipe.ingredients) {
                    if (counter.length() > 0) {
                        counter.append(", ");
                    }
                    counter.append(ing.count).append("x ").append(ing.name);
                }
                g.textWithWordWrap(font, Component.literal("Needs: " + counter), innerX, infoY,
                        contentWidth, SBSTheme.TEXT);
                y = infoY + font.lineHeight * 2 + 6;
                if (recipes.size() > 1) {
                    g.text(font, Component.literal("Recipe " + (recipeIndex + 1) + " / " + recipes.size()
                            + "  •  " + recipe.category), innerX, y, SBSTheme.TEXT_MUTED);
                    y += font.lineHeight + 4;
                }
            }

            // Acquisition methods.
            List<String> methods = registry.acquisitionMethods(selected.lookupId());
            if (!methods.isEmpty()) {
                g.text(font, Component.literal("Obtain via: " + String.join(", ", methods)),
                        innerX, y, SBSTheme.HUD_MANA);
            }

            // Supercraft button (auto-craft on Hypixel), accent-framed above the nav row.
            if (sbs$canSupercraft()) {
                SciFiRender.roundedRectWithBorder(g, innerX, supercraftY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                        SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.ACCENT);
                g.centeredText(font, Component.literal("Supercraft"), panelX + panelW / 2,
                        supercraftY + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT);
            }

            // Manual nav row: < Prev | Results | Next >.
            int third = contentWidth / 3;
            drawNavButton(g, innerX, third, "< Prev", recipes.size() > 1);
            drawNavButton(g, innerX + third, third, "Results", true);
            drawNavButton(g, innerX + third * 2, contentWidth - third * 2, "Next >", recipes.size() > 1);
        }

        /**
         * A pet's detail: rarity chips (only the rarities it exists in), the level slider (a widget,
         * placed by {@link #syncPetControls}), and the pet's tooltip at that rarity and level, clipped
         * above the buttons. Returns where the next block may start.
         */
        private int drawPetSection(GuiGraphicsExtractor g, String pet, int mouseX, int mouseY) {
            var font = RecipeViewerScreen.this.font;
            var data = sbs.modid.client.economy.recipe.logic.PetData.chosen(pet);
            java.util.List<Integer> rarities = sbs.modid.client.economy.recipe.logic.PetData.raritiesOf(pet);
            int lit = Math.max(0, rarities.indexOf(data.rarity()));
            sbs.modid.client.ui.render.ChipSwitch.draw(g, font, innerX, petChipsY(), lit, mouseX, mouseY,
                    rarityLabels(rarities));
            int y = petSliderY() + SBSTheme.SEARCH_HEIGHT + 6;
            int bottom = (sbs$canSupercraft() ? supercraftY : navY) - font.lineHeight - 4;
            for (String line : sbs.modid.client.economy.recipe.logic.PetData.tooltip(pet, data)) {
                if (y + font.lineHeight > bottom) {
                    break;
                }
                g.text(font, Component.literal(line), innerX, y, SBSTheme.TEXT);
                y += font.lineHeight + 1;
            }
            return y + 4;
        }

        private void drawNavButton(GuiGraphicsExtractor g, int x, int w, String label, boolean active) {
            var font = RecipeViewerScreen.this.font;
            SciFiRender.roundedRectWithBorder(g, x + 1, navY, w - 2, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, active ? SBSTheme.CARD_BG : SBSTheme.CARD_BG_DISABLED,
                    SBSTheme.CARD_BORDER);
            g.centeredText(font, Component.literal(label), x + w / 2,
                    navY + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2,
                    active ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
        }
    }
}
