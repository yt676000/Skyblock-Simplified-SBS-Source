/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.component.ProfileSelector;
import sbs.modid.client.ui.font.SbsFonts;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.component.SciFiSkeleton;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.settings.CategoryFolding;
import sbs.modid.client.ui.settings.ConfigNavigator;
import sbs.modid.client.ui.settings.Favorites;
import sbs.modid.client.ui.settings.ModuleSettings;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.settings.SettingRowList;
import sbs.modid.client.ui.settings.layout.SidebarLayout;
import sbs.modid.client.ui.settings.layout.SidebarLayoutResolver;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.module.ModuleCategory;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.ModuleManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * The main SBS config screen – a two-column layout in the SBS design language:
 * <ul>
 *   <li><b>Left sidebar</b>: the vertical, scrollable module list, grouped under the fixed
 *       {@link ModuleGroup} headers (Economy, Skills, Combat, ...). The Licence Token
 *       is pinned to the very top, above every group. Clicking a header folds its group away
 *       (arrow + module count while folded); a search force-expands everything, since a hit must
 *       never hide behind a fold; what the folds are when the config is opened is the player's
 *       choice, held by {@link CategoryFolding};
 *       clicking a module selects it. A group some of whose modules declare a
 *       {@link ModuleSubgroup} gets a second level - a quieter, indented sub-header per family,
 *       folded the same way - so Skills reads as Farming, Mining, Foraging... rather than thirty
 *       cards in one alphabet.</li>
 *   <li><b>Right panel</b>: the selected module's settings (from the central
 *       {@link ModuleSettings} declaration), scrollable, with the search bar on top.</li>
 *   <li><b>Search</b>: real-time filtering of BOTH columns – a module stays listed when its name
 *       or any of its option labels matches (its group header stays with it), and the right
 *       panel narrows down to matching options. The {@link SearchMode} switch left of the field
 *       decides how wide that net is thrown.</li>
 * </ul>
 * Complex sub-editors (GUI editor, keybinds, text editor, Bazaar, Recipe Viewer) open from buttons
 * inside the settings rows; their Back buttons return here with the selection preserved.
 */
public final class SBSMainScreen extends Screen implements sbs.modid.client.ui.theme.KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "config";
    }


    private static final int SIDEBAR_WIDTH = 132;
    private static final int SIDEBAR_ROW_HEIGHT = 18;
    /** Where a module name starts, before its indent. */
    private static final int MODULE_TEXT_X = 8;
    /** A module's indent under an ordinary group header - unchanged since before subgroups. */
    private static final int MODULE_INDENT = 6;
    /**
     * A module's indent in a subdivided group, where a sub-header sits between it and the group.
     *
     * <p>Four pixels deeper than {@link #MODULE_INDENT}, not six: at six, "Experimentation Table"
     * (109px in the default font) is cut by a single pixel, and a name shortened by one pixel reads
     * as a bug. Measured in {@code docs/features/settings-subgroups.md}.
     */
    private static final int MODULE_INDENT_SUBDIVIDED = 10;
    /** Where a sub-header's fold arrow starts: under the group header's label. */
    private static final int SUB_ARROW_X = 8;
    private static final int SEARCH_TEXT_INSET = 13;
    /** Gap between the search-mode switch and the search field it belongs to. */
    private static final int SEARCH_MODE_GAP = 5;
    /** Gap between the module list and the profile selector pinned under it. */
    private static final int PROFILE_GAP = 6;

    /** Selection survives sub-screen round-trips and reopening. */
    private static String lastSelectedId;

    /**
     * How wide the search throws its net. Both modes match a module by its own name and by the group
     * header above it ("Skills" lists every skill module); they differ in whether the <i>settings</i>
     * inside a module count too.
     */
    private enum SearchMode {

        /**
         * Whole modules only – type "fishing" and you get the fishing pages, not the twenty
         * individual options that happen to mention fishing. The right panel always shows the
         * selected module in full.
         */
        CATEGORY("Category", "Search categories..."),

        /** Modules <b>and</b> every option label; the right panel narrows to the matching options. */
        ALL("All", "Search modules & settings...");

        private final String label;
        private final String hint;

        SearchMode(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        /** Segment text on the switch. */
        String label() {
            return label;
        }

        /** Placeholder shown in the empty search field, so the mode explains itself. */
        String hint() {
            return hint;
        }

        static List<String> labels() {
            List<String> labels = new ArrayList<>();
            for (SearchMode mode : values()) {
                labels.add(mode.label());
            }
            return labels;
        }
    }

    /**
     * The active search mode. Static like {@link #lastSelectedId} and the fold state in
     * {@link CategoryFolding}, so reopening the config keeps the mode the player last worked in; a
     * fresh game starts on {@link SearchMode#ALL}, the mode that can find anything.
     */
    private static SearchMode searchMode = SearchMode.ALL;

    private final ModuleManager moduleManager;

    private EditBox searchBox;
    /** The two-segment Category / All switch left of the search field. */
    private SciFiSegmentedSwitch searchModeSwitch;
    private String query = "";

    private List<ModuleCategory> filtered = List.of();
    /** The rendered sidebar rows: group headers interleaved with the filtered modules. */
    private List<SidebarEntry> sidebarEntries = List.of();
    private String selectedId;
    private int sidebarScroll;
    /** The module list's scrollbar - the mod's shared one; see {@link SciFiScrollbar}. */
    private final SciFiScrollbar sidebarBar = new SciFiScrollbar();

    /**
     * One sidebar row – a module, a group header or a sub-header. A header carries its
     * {@link ModuleGroup} (and a sub-header its {@link ModuleSubgroup}, {@code null} for General) so
     * clicking it can fold that part away; {@code count} is how many modules it holds, which is what
     * makes a collapsed header still readable ("Skills  4").
     *
     * <p>Sub-headers are real rows rather than decorations drawn between modules because the sidebar
     * is hit-tested by row index: a header that took up space without taking up a row would shift
     * every click below it by one.
     */
    private record SidebarEntry(ModuleCategory module, String header, ModuleGroup group,
                                ModuleSubgroup subgroup, boolean subHeader, int count,
                                String customId, Integer indent, boolean fresh) {
        static SidebarEntry module(ModuleCategory module) {
            return new SidebarEntry(module, null, null, null, false, 0, null, null, false);
        }

        /** A module row placed by a custom layout, which decides its own indent and "new" mark. */
        static SidebarEntry placed(ModuleCategory module, int indent, boolean fresh) {
            return new SidebarEntry(module, null, null, null, false, 0, null, indent, fresh);
        }

        static SidebarEntry header(ModuleGroup group, int count) {
            return new SidebarEntry(null, group.displayName(), group, null, false, count, null, null, false);
        }

        /** The header of a player-made category: folded by its generated id, never by its name. */
        static SidebarEntry customHeader(String id, String name, int count) {
            return new SidebarEntry(null, name, null, null, false, count, id, null, false);
        }

        static SidebarEntry subHeader(ModuleGroup group, ModuleSubgroup subgroup, int count) {
            String label = subgroup == null ? ModuleSubgroup.GENERAL_NAME : subgroup.displayName();
            return new SidebarEntry(null, label, group, subgroup, true, count, null, null, false);
        }

        /** Whether this header is folded right now. */
        boolean folded() {
            if (customId != null) {
                return CategoryFolding.collapsedCustom(customId);
            }
            return subHeader ? CategoryFolding.collapsed(group, subgroup) : CategoryFolding.collapsed(group);
        }

        /** A group header or a sub-header - anything that folds instead of selecting. */
        boolean isHeader() {
            return module == null;
        }
    }

    /**
     * The groups drawn with sub-headers, as of the last {@link #refilter()} - see
     * {@link ModuleManager#subdividedGroups}. Taken from the whole catalogue, never from the search
     * result, or a group would flip between subdivided and flat while the player types.
     */
    private Set<ModuleGroup> subdivided = Set.of();

    /** Under a custom layout, the category each shown module is filed in; empty otherwise. */
    private java.util.Map<String, SidebarLayout.Category> homeOf = java.util.Map.of();

    /**
     * Set when this screen is the config being opened afresh: the first {@link #init()} then reveals
     * the selected module. Consumed there, so a resize - which runs {@code init()} again - does not
     * undo a fold the player has made since.
     */
    private boolean revealOnInit;

    /**
     * The fold state {@link CategoryFolding#revision()} was last read at.
     *
     * <p>Folds can change from outside this screen - the "Categories" row on the SBS Settings page
     * is on the right while the list it folds is on the left - so the sidebar is rebuilt when the
     * revision moves rather than only when this screen is what moved it.
     */
    private int foldRevision;

    /**
     * How long one frame may spend indexing licence marks, in nanoseconds.
     *
     * <p>Small enough that a frame carrying a slice still lands inside a 60Hz budget. The index is
     * every module's rows, so it is spread over frames rather than taken in one - the whole reason
     * opening this screen used to stall.
     */
    private static final long MARK_BUDGET_NS = 2_000_000L;

    /** Modules indexed per step inside {@link #MARK_BUDGET_NS}. */
    private static final int MARK_SLICE = 2;

    /**
     * Set while the settings rows have not been built yet: the content column draws placeholders
     * instead. Cleared by {@link #drainDeferredWork()} the frame the real rows land.
     */
    private boolean rowsPending;

    /**
     * Whether a frame has been drawn since {@link #init()}.
     *
     * <p>The drain runs before the render pass, so without this the rows would be built on the same
     * frame the screen first appears and the placeholders would never be seen — which is the stall
     * this whole path exists to remove, just with extra steps. The first frame does no work.
     */
    private boolean firstFrameDrawn;

    /**
     * The settings column: the rows, their widgets, the scroll and the dropdown handling. Swapped
     * without touching the search box, and shared with every other screen that shows setting rows -
     * see {@link SettingRowList} for why the dropdown parts in particular are not worth re-deriving.
     */
    private final SettingRowList rowList =
            new SettingRowList(new SettingRowList.WidgetSink() {
                @Override
                public void add(AbstractWidget widget) {
                    addRenderableWidget(widget);
                }

                @Override
                public void remove(AbstractWidget widget) {
                    removeWidget(widget);
                }
            });

    /** Row index marked by a jump, and when the mark expires. {@code -1} when nothing is marked. */
    private int highlightRow = -1;
    private long highlightUntil;

    /** Width the favourite star takes out of every interactive row. */
    private static final int STAR_COLUMN = 13;

    /** Filled / hollow star. One constant so the affordance can be swapped in one place. */
    private static final String STAR_ON = "★";
    private static final String STAR_OFF = "☆";

    // Cached layout.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int sidebarX;
    private int sidebarTop;
    private int sidebarBottom;
    private int contentX;
    private int contentWidth;
    private int searchY;
    /** The search field's own box – the content column minus the mode switch in front of it. */
    private int searchX;
    private int searchWidth;
    private int rowsTop;
    private int rowsBottom;
    private int profileY;

    /** The config-profile picker pinned to the bottom-left corner, under the module list. */
    private ProfileSelector profileSelector;

    public SBSMainScreen() {
        this(ModuleManager.getInstance());
    }

    /**
     * Applies a pending {@link sbs.modid.client.ui.settings.ConfigNavigator} jump: select the module,
     * scroll the option into view and mark it.
     *
     * <p>Runs after {@link #refilter()} (which decides the selection) and before the settings widgets
     * are built (which clamps the scroll), because those two steps are what a jump has to land
     * between. An unknown module id is written through the same {@link #lastSelectedId} the sidebar
     * uses, so it degrades to the usual "first module" fallback rather than an empty panel.
     */
    private void applyPendingJump() {
        String moduleId = ConfigNavigator.pendingModuleId();
        if (moduleId == null) {
            return;
        }
        // A jump is an exact destination; leaving a filter in place could hide it, and the row index
        // the target was resolved at counts rows unfiltered.
        this.query = "";
        if (searchBox != null) {
            searchBox.setValue("");
        }
        refilter();
        if (filtered.stream().anyMatch(c -> c.id().equals(moduleId))) {
            selectedId = moduleId;
            lastSelectedId = moduleId;
            // A jump is an exact destination - landing on settings whose row is folded away would
            // leave the player unable to see where they were sent.
            revealSelected();
        }

        int rowIndex = ConfigNavigator.pendingRowIndex();
        if (rowIndex >= 0) {
            // Centred rather than scrolled to the top edge: an option pinned against the top of the
            // list reads as "the first setting", not as "the one you asked for".
            rowList.setScroll(Math.max(0, rowIndex - rowList.maxVisible() / 2));
            highlightRow = rowIndex;
            highlightUntil = System.currentTimeMillis() + ConfigNavigator.HIGHLIGHT_MS;
        }
        ConfigNavigator.consume();
    }

    public SBSMainScreen(ModuleManager moduleManager) {
        // 26.2 lets a Screen be handed its own Font. Passing the UI font here is what makes the parts
        // vanilla draws for us - widget labels, tooltips - match the parts we draw ourselves. Screen
        // holds it in a final field, so this copy is fixed for the life of the screen; everything
        // below reads SbsFonts.ui() per frame instead, and follows a change while the screen is open.
        super(Minecraft.getInstance(), SbsFonts.ui(), Component.literal("Skyblock Simplified"));
        this.moduleManager = moduleManager;
        if (openedAfresh()) {
            CategoryFolding.configOpened();
            // The selection outlives the folds, so a folded group can hide the very module whose
            // settings are on the right. Revealed in init(), which is where the rows are known.
            revealOnInit = true;
        }
        this.foldRevision = CategoryFolding.revision();
    }

    /**
     * Whether this instance is the player opening the config, rather than a sub-editor's Back button
     * building it again.
     *
     * <p>Both do the same thing - {@code new SBSMainScreen()} - from twenty-odd places, and the
     * difference matters: {@link CategoryFolding.Mode#ALWAYS_CLOSED} re-folding the group you were
     * working in the moment you step into the GUI editor and back is not what "always closed" means.
     * The screen being replaced answers it, because every one of those Back buttons is itself one of
     * ours: the construction runs before {@code setScreenAndShow}, so what is open at this moment is
     * still the screen we are leaving.
     *
     * <p>Deliberately a package test rather than a list of screen classes - a new sub-editor is then
     * covered the day it is written, and the failure direction is the harmless one either way (a
     * mode applied once too often, never a fold state that cannot be reached).
     */
    private static boolean openedAfresh() {
        Screen previous = sbs.modid.client.core.api.ScreenAccess.current();
        return previous == null || !previous.getClass().getName().startsWith("sbs.modid.");
    }

    @Override
    protected void init() {
        // The licence token is always hidden when the config opens; the "Show License Key" checkbox
        // reveals it only for this viewing (the flag is transient, never written to the config).
        sbs.modid.client.ui.settings.ModuleSettings.licenceKeyVisible = false;

        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 340, 640);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 200, 380);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int pad = SBSTheme.PANEL_PADDING;
        sidebarX = panelX + pad;
        sidebarTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        // The profile picker is pinned to the bottom of the left column; the module list ends above it.
        profileY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        sidebarBottom = profileY - PROFILE_GAP;

        contentX = sidebarX + SIDEBAR_WIDTH + pad;
        contentWidth = panelX + panelW - pad - contentX;
        searchY = sidebarTop;
        rowsTop = searchY + SBSTheme.SEARCH_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        rowsBottom = panelY + panelH - pad;

        // Before applyPendingJump below, which centres the jumped-to row and therefore needs the
        // visible row count - which comes out of this box.
        rowList.setBounds(contentX, rowsTop, contentWidth, rowsBottom);
        rowList.setRightGutter(STAR_COLUMN);

        // The mode switch sits in front of the field and takes its width out of the search bar's.
        List<String> modeLabels = SearchMode.labels();
        searchX = contentX + SciFiSegmentedSwitch.widthFor(modeLabels) + SEARCH_MODE_GAP;
        searchWidth = contentX + contentWidth - searchX;

        addRenderableOnly(new PanelRenderable());

        // Search bar, top right.
        int textHeight = SbsFonts.ui().lineHeight;
        int editY = searchY + (SBSTheme.SEARCH_HEIGHT - textHeight) / 2;
        searchBox = new EditBox(SbsFonts.ui(), searchX + SEARCH_TEXT_INSET, editY,
                searchWidth - SEARCH_TEXT_INSET - 4, textHeight, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(128);
        searchBox.setTextColor(SBSTheme.TEXT);
        searchBox.setHint(hintFor(searchMode));
        searchBox.setValue(query);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        searchModeSwitch = new SciFiSegmentedSwitch(contentX, searchY, SBSTheme.SEARCH_HEIGHT,
                modeLabels, () -> searchMode.ordinal(), this::onSearchModeChanged);
        addRenderableWidget(searchModeSwitch);

        // Config profile picker, bottom-left. Switching swaps the whole config, so the screen is
        // rebuilt from scratch afterwards - every widget on it was built from the old values.
        profileSelector = new ProfileSelector(sidebarX, profileY, SIDEBAR_WIDTH,
                SBSTheme.SEARCH_HEIGHT, this::onProfileChanged);
        addRenderableWidget(profileSelector);

        refilter();
        if (revealOnInit) {
            revealOnInit = false;
            revealSelected();
        }
        applyPendingJump();
        // init() runs on a screen whose widget list was just cleared, so the list has nothing of its
        // own left to remove - but it still holds the references, and clearing them here is what
        // stops it trying to remove widgets that belong to the previous incarnation of the screen.
        rowList.clear();
        // The rows are NOT built here. Building them is what the player waits for when the menu
        // opens, so the screen goes up without them and fills itself in from the render loop - see
        // drainDeferredWork(). Everything above this line is chrome that costs nothing to draw.
        //
        // init() runs again on every resize and profile switch, so both flags are set here rather
        // than at construction: a resized screen re-enters the same deferral instead of rebuilding
        // every row inside the resize callback.
        rowsPending = true;
        firstFrameDrawn = false;

        // Hover tooltips for settings draw last (deferred), so they sit over the whole screen.
        addRenderableOnly(new SettingTooltipRenderable());
        // ...and the open profile list after even those: it is the one thing that must never be
        // covered, since it is drawn outside its widget's own bounds.
        addRenderableOnly(new ProfileOverlayRenderable());
        // An open setting dropdown is drawn later still - see extractRenderState.
    }

    /**
     * Draws the open setting dropdown's option list, after everything else on the screen.
     *
     * <p>This is an override rather than one more pass registered in {@link #init()}, which is where
     * it was and why the list came out <b>behind</b> the rows: {@link #refreshSettingsWidgets()}
     * appends the rebuilt rows to the end of the render order, so from the first category click,
     * search keystroke or wheel turn onwards every row was drawn after a pass registered before them.
     * A render override cannot be overtaken that way - nothing this screen draws happens after it.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // A fold made from somewhere other than this sidebar - the Categories row on the SBS
        // Settings page is the one that does it - only reaches the list through here. Rebuilding the
        // rows is refilter's job alone; no widget is touched, so this is safe before the drain.
        if (foldRevision != CategoryFolding.revision()) {
            foldRevision = CategoryFolding.revision();
            refilter();
        }
        // Before super, and that is the whole point: super iterates the renderables, and the drain
        // adds and removes widgets. Doing this after it - or from any pass registered inside it -
        // mutates the list vanilla is walking.
        drainDeferredWork();
        // A row greyed out by another setting follows it live: flipping IRC Chat brings the IRC Tab
        // row back on this frame, on the Favourites page too, without a rebuild.
        rowList.syncAvailability();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (rowsPending) {
            // Drawn after super so it sits on the panel rather than under it. There are no row
            // widgets to draw over while this is up - the list is empty by construction.
            SciFiSkeleton.render(g, contentX, rowsTop, contentWidth, rowsBottom,
                    rowList.rowStep(), STAR_COLUMN);
        }
        // Before the dropdown overlay: an open option list is drawn on top of the row it belongs to,
        // and tinting it red would say the list itself is unavailable.
        rowList.renderLicenceMarks(g);
        rowList.renderScrollbar(g, mouseX, mouseY);
        rowList.renderDropdownOverlay(g, mouseX, mouseY);
    }

    /**
     * Rebuilds the screen after the active profile changed. A targeted refresh is not enough: every
     * toggle, slider and text field was created from the previous profile's values, so they would all
     * keep showing – and writing back – the settings of the profile that is no longer loaded.
     */
    private void onProfileChanged() {
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // Search + selection
    // ------------------------------------------------------------------

    private void onSearchChanged(String value) {
        this.query = value;
        this.sidebarScroll = 0;
        rowList.setScroll(0);
        refilter();
        // Swap ONLY the settings widgets – a full rebuild would recreate the search box and kick
        // the keyboard focus out of it after every typed character.
        refreshSettingsWidgets();
    }

    /**
     * The mode's placeholder, shortened to whatever the field can hold: the switch takes its width
     * out of the search bar, and an {@link EditBox} hint is not clipped to the box it sits in – on
     * the narrowest panel the full text would run out over the panel edge.
     */
    private Component hintFor(SearchMode mode) {
        int room = searchWidth - SEARCH_TEXT_INSET - 6;
        for (String candidate : new String[] {mode.hint(), "Search...", ""}) {
            if (SbsFonts.ui().width(candidate) <= room) {
                return Component.literal(candidate);
            }
        }
        return Component.empty();
    }

    /** A click on the Category / All switch: the query stays, only its reach changes. */
    private void onSearchModeChanged(int index) {
        SearchMode mode = SearchMode.values()[index];
        if (mode == searchMode) {
            return;
        }
        searchMode = mode;
        searchBox.setHint(hintFor(mode));
        this.sidebarScroll = 0;
        rowList.setScroll(0);
        refilter();
        refreshSettingsWidgets();
    }

    /** Replaces the dynamic settings widgets without rebuilding the whole screen. */
    private void refreshSettingsWidgets() {
        rowList.rebuild(visibleRows());
        // A refresh is an answer to something the player just did, so it is built now rather than
        // deferred: the rows are already on screen and swapping them for placeholders would read as
        // the screen breaking, not as it loading.
        rowsPending = false;
    }

    /**
     * The work {@link #init()} refused to do, taken a slice at a time from the render loop.
     *
     * <p>Order matters: the selected module's rows come first and get a frame to themselves, because
     * they are what the placeholders are standing in for. The licence-mark index follows, in slices,
     * and only when marks are actually being drawn — with a token set nothing needs it and it is
     * never built at all.
     */
    private void drainDeferredWork() {
        if (!firstFrameDrawn) {
            // The frame the player is waiting for draws the panel and the placeholders and nothing
            // else. Work starts once something is on screen.
            firstFrameDrawn = true;
            return;
        }
        if (rowsPending) {
            rowList.rebuild(visibleRows());
            rowsPending = false;
            return;
        }
        if (!sbs.modid.client.core.licence.LicenceMarks.marking()
                || sbs.modid.client.core.licence.LicenceMarks.ready()) {
            return;
        }
        long deadline = System.nanoTime() + MARK_BUDGET_NS;
        while (System.nanoTime() < deadline
                && !sbs.modid.client.core.licence.LicenceMarks.advance(MARK_SLICE)) {
            // advance() reports its own completion; the deadline caps what this frame gives it.
        }
    }

    /**
     * A module matches when the module itself matches, and – outside
     * {@link SearchMode#CATEGORY} – when any of its option labels does.
     */
    private void refilter() {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ModuleCategory> all = moduleManager.getCategories();
        this.subdivided = ModuleManager.subdividedGroups(all);
        List<ModuleCategory> result = new ArrayList<>();
        for (ModuleCategory category : all) {
            if (q.isEmpty() || moduleMatches(category, q)) {
                result.add(category);
            }
        }
        this.filtered = result;

        SidebarLayout layout = SidebarLayout.decode(ConfigManager.getInstance().get().gui.sidebarLayout);
        this.homeOf = java.util.Map.of();
        if (layout != null) {
            buildLayoutEntries(layout, all, q);
            finishRefilter();
            return;
        }

        // Sidebar rows: getCategories() is already in group, then subgroup order, so a header is
        // inserted whenever the group changes and a sub-header whenever the subgroup does. PINNED
        // entries (Licence Token) stay header-less on top; groups and subgroups whose modules are
        // all filtered away never appear.
        List<SidebarEntry> entries = new ArrayList<>();
        ModuleGroup lastGroup = null;
        ModuleSubgroup lastSubgroup = null;
        // null is a real subgroup here (General), so "same as last" needs a flag of its own.
        boolean subgroupStarted = false;
        boolean searching = !q.isEmpty();
        for (ModuleCategory category : result) {
            ModuleGroup group = category.group();
            if (group != lastGroup) {
                if (group != ModuleGroup.PINNED) {
                    int count = 0;
                    for (ModuleCategory other : result) {
                        if (other.group() == group) {
                            count++;
                        }
                    }
                    entries.add(SidebarEntry.header(group, count));
                }
                lastGroup = group;
                subgroupStarted = false;
            }
            // Collapsed: the header stays (that is what you click to unfold), everything under it -
            // sub-headers included - does not. A search force-expands both levels: a hit must never
            // hide behind a fold.
            if (!searching && group != ModuleGroup.PINNED && CategoryFolding.collapsed(group)) {
                continue;
            }
            if (subdivided.contains(group)) {
                ModuleSubgroup subgroup = category.subgroup();
                if (!subgroupStarted || subgroup != lastSubgroup) {
                    int count = 0;
                    for (ModuleCategory other : result) {
                        if (other.group() == group && other.subgroup() == subgroup) {
                            count++;
                        }
                    }
                    entries.add(SidebarEntry.subHeader(group, subgroup, count));
                    lastSubgroup = subgroup;
                    subgroupStarted = true;
                }
                if (!searching && CategoryFolding.collapsed(group, subgroup)) {
                    continue;
                }
            }
            entries.add(SidebarEntry.module(category));
        }
        this.sidebarEntries = entries;
        finishRefilter();
    }

    /** What every refilter ends with: clamp the scroll, keep a visible selection. */
    private void finishRefilter() {
        List<SidebarEntry> entries = sidebarEntries;
        // Folding a group shortens the list, so a scroll offset from the longer one would leave the
        // sidebar showing empty rows. Same bound the wheel handler uses.
        int visibleRows = Math.max(1, (sidebarBottom - sidebarTop) / SIDEBAR_ROW_HEIGHT);
        sidebarScroll = clamp(sidebarScroll, 0, Math.max(0, entries.size() - visibleRows));

        if (selectedId == null && lastSelectedId != null) {
            selectedId = lastSelectedId;
        }
        boolean selectedVisible = filtered.stream().anyMatch(c -> c.id().equals(selectedId));
        if (!selectedVisible) {
            selectedId = filtered.isEmpty() ? null : filtered.get(0).id();
        }
        lastSelectedId = selectedId;
    }

    /**
     * The sidebar in the player's own arrangement (see {@link SidebarLayout}).
     *
     * <p>Same rules as the default path, applied per category: pinned entries first and header-less;
     * empty categories never drawn; a fold hides everything under a header and a search force-opens
     * every fold. Built-in categories keep their sub-headers (families in first-appearance order); a
     * custom category is flat. A custom category's name is searched like a group header, so typing
     * it lists everything filed there. A module the layout does not know yet is marked "new".
     */
    private void buildLayoutEntries(SidebarLayout layout, List<ModuleCategory> all, String q) {
        boolean searching = !q.isEmpty();
        java.util.Map<String, ModuleCategory> byId = new java.util.HashMap<>();
        List<SidebarLayoutResolver.ModuleRef> refs = new ArrayList<>(all.size());
        for (ModuleCategory category : all) {
            byId.put(category.id(), category);
            refs.add(new SidebarLayoutResolver.ModuleRef(category.id(), category.group(), category.subgroup()));
        }
        List<SidebarEntry> entries = new ArrayList<>();
        List<ModuleCategory> shown = new ArrayList<>();
        java.util.Map<String, SidebarLayout.Category> home = new java.util.HashMap<>();
        for (ModuleCategory category : all) {
            if (category.group() == ModuleGroup.PINNED && (!searching || moduleMatches(category, q))) {
                entries.add(SidebarEntry.module(category));
                shown.add(category);
            }
        }
        for (SidebarLayoutResolver.Resolved resolved : SidebarLayoutResolver.resolve(layout, refs)) {
            SidebarLayout.Category cat = resolved.category();
            boolean nameHit = SidebarLayout.nameMatches(cat, q);
            List<SidebarLayoutResolver.ModuleRef> members = new ArrayList<>();
            for (SidebarLayoutResolver.ModuleRef ref : resolved.modules()) {
                ModuleCategory module = byId.get(ref.id());
                if (!searching || nameHit || moduleMatches(module, q)) {
                    members.add(ref);
                    shown.add(module);
                    home.put(ref.id(), cat);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            SidebarEntry header = cat.custom()
                    ? SidebarEntry.customHeader(cat.id, cat.displayName(), members.size())
                    : SidebarEntry.header(cat.builtin, members.size());
            entries.add(header);
            if (!searching && header.folded()) {
                continue;
            }
            if (!cat.custom() && subdivided.contains(cat.builtin)) {
                var families = SidebarLayoutResolver.families(new SidebarLayoutResolver.Resolved(
                        cat, members, resolved.newIds()));
                for (var family : families.entrySet()) {
                    SidebarEntry sub = SidebarEntry.subHeader(cat.builtin, family.getKey(),
                            family.getValue().size());
                    entries.add(sub);
                    if (!searching && sub.folded()) {
                        continue;
                    }
                    for (SidebarLayoutResolver.ModuleRef ref : family.getValue()) {
                        entries.add(SidebarEntry.placed(byId.get(ref.id()), MODULE_INDENT_SUBDIVIDED,
                                resolved.newIds().contains(ref.id())));
                    }
                }
            } else {
                for (SidebarLayoutResolver.ModuleRef ref : members) {
                    entries.add(SidebarEntry.placed(byId.get(ref.id()), MODULE_INDENT,
                            resolved.newIds().contains(ref.id())));
                }
            }
        }
        this.filtered = shown;
        this.sidebarEntries = entries;
        this.homeOf = home;
    }

    /**
     * Unfolds the selected module's group and sub-header, and scrolls its row into view.
     *
     * <p>Runs once when the config is opened afresh and once when a jump lands - never from
     * {@link #refilter()}, which runs on every fold click and would pop the selection's group open
     * again the instant the player folded it. See {@link CategoryFolding#reveal} for why the reveal
     * is not written to disk.
     *
     * <p>The alternatives were worse: blanking the panel reads as broken, and moving the selection
     * to the first visible module changes what is on the right for a reason the player cannot see.
     */
    private void revealSelected() {
        ModuleCategory selected = filtered.stream()
                .filter(c -> c.id().equals(selectedId)).findFirst().orElse(null);
        if (selected == null) {
            return;
        }
        SidebarLayout.Category home = homeOf.get(selected.id());
        if (home != null && home.custom()) {
            CategoryFolding.revealCustom(home.id);
        } else if (home != null) {
            CategoryFolding.reveal(home.builtin,
                    selected.group() == home.builtin ? selected.subgroup() : null,
                    subdivided.contains(home.builtin));
        } else {
            CategoryFolding.reveal(selected.group(), selected.subgroup(),
                    subdivided.contains(selected.group()));
        }
        foldRevision = CategoryFolding.revision();
        refilter();
        for (int i = 0; i < sidebarEntries.size(); i++) {
            SidebarEntry entry = sidebarEntries.get(i);
            if (!entry.isHeader() && entry.module().id().equals(selectedId)) {
                int visible = Math.max(1, (sidebarBottom - sidebarTop) / SIDEBAR_ROW_HEIGHT);
                if (i < sidebarScroll) {
                    sidebarScroll = i;
                } else if (i >= sidebarScroll + visible) {
                    sidebarScroll = i - visible + 1;
                }
                sidebarScroll = clamp(sidebarScroll, 0, Math.max(0, sidebarEntries.size() - visible));
                break;
            }
        }
    }

    /** Whether the module is a hit in the current mode – see {@link SearchMode}. */
    private static boolean moduleMatches(ModuleCategory category, String q) {
        return category.matchesByName(q)
                || (searchMode != SearchMode.CATEGORY && anyRowMatches(category, q));
    }

    // "The module itself matches" - name, description, group or subgroup header - is
    // ModuleCategory.matchesByName, and is deliberately still true in Category mode: that switch is
    // about whether single settings are searched, not about how a module itself is recognised.

    /**
     * Asked once per module per keystroke, so it reads the option index rather than building every
     * module's rows to look at their labels – which is what this used to do, for all 48 modules, on
     * every character typed.
     */
    private static boolean anyRowMatches(ModuleCategory category, String q) {
        if (Favorites.PAGE_ID.equals(category.id())) {
            // Not in the index (it owns no options of its own); its rows are other modules' rows and
            // are found under those modules.
            return false;
        }
        return sbs.modid.client.ui.settings.OptionIndex.moduleMatches(category.id(), q);
    }

    /**
     * The rows shown on the right: all rows, narrowed to matches while searching – except in
     * {@link SearchMode#CATEGORY}, which never looks at single settings and therefore never hides
     * any either.
     */
    private List<SettingRow> visibleRows() {
        if (selectedId == null) {
            return List.of();
        }
        List<SettingRow> rows = ModuleSettings.rowsFor(selectedId);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || searchMode == SearchMode.CATEGORY) {
            return rows;
        }
        ModuleCategory selected = filtered.stream()
                .filter(c -> c.id().equals(selectedId)).findFirst().orElse(null);
        if (selected != null && selected.matchesByName(q)) {
            return rows; // the module itself matched – show everything
        }
        List<SettingRow> matching = new ArrayList<>();
        for (SettingRow row : rows) {
            if (row.matches(q)) {
                matching.add(row);
            }
        }
        return matching;
    }

    // ------------------------------------------------------------------
    // Input: sidebar clicks + independent scrolling per column
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // A screen dispatches a click only to the child under the cursor, so a click meant to dismiss
        // the open profile list (or confirm a name being typed) would never be delivered to it.
        // Forwarding it here is what makes "click anywhere else" work at all.
        if (profileSelector != null && (profileSelector.isOpen() || profileSelector.isEditing())
                && !profileSelector.isMouseOver(event.x(), event.y())) {
            return profileSelector.mouseClicked(event, doubled);
        }
        // An open setting dropdown draws its list outside its own bounds, so a click on an option
        // (or anywhere else, to dismiss it) has to reach it before the widgets underneath do.
        if (rowList.mouseClicked(event, doubled)) {
            return true;
        }
        // The star strip sits outside every row widget's width, so it is checked before the widgets
        // rather than after: nothing else can claim that click, and going first keeps it responsive.
        if (handleStarClick(event.x(), event.y())) {
            return true;
        }
        if (super.mouseClicked(event, doubled)) {
            // Switching the mode must not steal the keyboard: the player is usually mid-query, and
            // the click would otherwise move the focus onto the switch and stop their typing.
            if (searchModeSwitch != null && searchBox != null && getFocused() == searchModeSwitch) {
                setFocused(searchBox);
                searchBox.setFocused(true);
            }
            return true;
        }
        double mx = event.x();
        double my = event.y();
        // Module-list scrollbar: grab the thumb to drag, or click the track to jump there. Offered
        // the click before the rows, or it would also select whatever module is painted under the bar.
        syncSidebarScrollbar();
        if (sidebarBar.handleClick(mx, my, sidebarScroll, value -> sidebarScroll = value)) {
            return true;
        }
        if (mx >= sidebarX && mx <= sidebarX + SIDEBAR_WIDTH && my >= sidebarTop && my <= sidebarBottom) {
            int index = sidebarScroll + (int) ((my - sidebarTop) / SIDEBAR_ROW_HEIGHT);
            if (index >= 0 && index < sidebarEntries.size()) {
                SidebarEntry row = sidebarEntries.get(index);
                if (row.isHeader()) {
                    // Fold / unfold the group or the sub-header - never a selection. Kept out of
                    // the way while searching, where both levels are force-expanded anyway.
                    if ((row.group() != null || row.customId() != null) && query.trim().isEmpty()) {
                        if (row.customId() != null) {
                            CategoryFolding.toggleCustom(row.customId());
                        } else if (row.subHeader()) {
                            CategoryFolding.toggle(row.group(), row.subgroup());
                        } else {
                            CategoryFolding.toggle(row.group());
                        }
                        foldRevision = CategoryFolding.revision();
                        refilter();
                    }
                } else {
                    ModuleCategory clicked = row.module();
                    selectedId = clicked.id();
                    lastSelectedId = selectedId;
                    rowList.setScroll(0);
                    refreshSettingsWidgets();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        syncSidebarScrollbar();
        if (sidebarBar.handleDrag(event.y(), value -> sidebarScroll = value)) {
            return true;
        }
        if (rowList.mouseDragged(event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (sidebarBar.release() || rowList.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // The open profile list hangs over the module column, so it has to be asked first – otherwise
        // the wheel would scroll the list underneath the one the cursor is actually on.
        if (profileSelector != null && profileSelector.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        // Same for an open setting dropdown: the wheel belongs to its list, not to the rows behind
        // it (scrolling those would move the dropdown itself out from under the cursor). It is asked
        // before the module column and not after, because an open list claims the wheel wherever the
        // cursor is - the settings column and the module column are disjoint, so their order relative
        // to each other does not matter, but this one does.
        if (rowList.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_WIDTH
                && mouseY >= sidebarTop && mouseY <= sidebarBottom) {
            int visible = Math.max(1, (sidebarBottom - sidebarTop) / SIDEBAR_ROW_HEIGHT);
            int maxScroll = Math.max(0, sidebarEntries.size() - visible);
            sidebarScroll = clamp(sidebarScroll - (int) Math.signum(scrollY), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Escape closes the open profile list (or abandons a name being typed) instead of the whole
     * config screen – the list is the innermost thing open, the same rule the keybind editor follows.
     */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && profileSelector != null
                && (profileSelector.isOpen() || profileSelector.isEditing())) {
            profileSelector.close();
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

    // ------------------------------------------------------------------
    // Module-list scrollbar (draggable slider next to the scroll wheel)
    // ------------------------------------------------------------------

    /**
     * Feeds the shared scrollbar the module list's current track box and size.
     *
     * <p>Called from render <b>and</b> from every input handler rather than only at init: the list
     * grows and shrinks as groups fold and the search filters it, and a bar hit-tested against last
     * frame's size is a bar that catches clicks where it is no longer drawn.
     */
    private void syncSidebarScrollbar() {
        int visible = Math.max(1, (sidebarBottom - sidebarTop) / SIDEBAR_ROW_HEIGHT);
        sidebarBar.set(sidebarX + SIDEBAR_WIDTH + 1, sidebarTop, sidebarBottom - sidebarTop,
                sidebarEntries.size(), visible);
    }

    // ------------------------------------------------------------------
    // Setting hover tooltips (beginner-friendly "what does this do")
    // ------------------------------------------------------------------

    /** Max width of a setting tooltip before its description wraps to the next line. */
    private static final int TOOLTIP_WIDTH = 210;

    /**
     * Shows the hovered setting's beginner tooltip (name + short description), when the feature is on.
     * The description is the row's explicit one, or – derived from context – the explanatory
     * {@code label(...)} row(s) that follow it in the same module page.
     */
    private void renderSettingTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!ConfigManager.getInstance().get().convenience.settingTooltips) {
            return;
        }
        rowList.renderTooltip(g, mouseX, mouseY);
    }

    /**
     * Rings the row a jump landed on, fading out over its lifetime.
     *
     * <p>Drawn over the widgets rather than behind them: the row's own widget fills its rectangle, so
     * a highlight underneath would be invisible – which is the one thing this must not be.
     */
    private void renderJumpHighlight(GuiGraphicsExtractor g) {
        if (highlightRow < 0) {
            return;
        }
        long remaining = highlightUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            highlightRow = -1;
            return;
        }
        int y = rowList.yOf(highlightRow);
        if (y < 0) {
            return;   // scrolled away from it: the mark follows the row, it does not stay on screen
        }
        float fade = Math.min(1f, remaining / (float) ConfigNavigator.HIGHLIGHT_MS);
        int alpha = Math.round(255 * fade) << 24;
        int color = alpha | (SBSTheme.ACCENT_BRIGHT & 0xFFFFFF);
        int x0 = contentX - 2;
        int x1 = contentX + contentWidth + 2;
        int y1 = y + SBSTheme.ENTRY_HEIGHT;
        g.fill(x0, y - 2, x1, y, color);
        g.fill(x0, y1, x1, y1 + 2, color);
        g.fill(x0, y, contentX, y1, color);
        g.fill(contentX + contentWidth, y, x1, y1, color);
    }

    /**
     * The full option id of a visible row.
     *
     * <p>A row shown on the favourites page belongs to another module and says so
     * ({@link SettingRow#ownerModuleId()}); every other row belongs to the page it is drawn on.
     */
    private String optionIdOf(SettingRow row) {
        if (row == null || row.isLabel()) {
            return null;
        }
        // On the favourites page the only real options are the borrowed rows, which say who owns
        // them. The rest is that page's own furniture - the jump buttons - and pinning a jump button
        // to the favourites is not a thing that means anything.
        if (Favorites.PAGE_ID.equals(selectedId) && row.ownerModuleId() == null) {
            return null;
        }
        // Commands are not settings: nothing on that page can be a favourite.
        if (sbs.modid.client.ui.settings.CommandsPage.PAGE_ID.equals(selectedId)) {
            return null;
        }
        String moduleId = row.ownerModuleId() != null ? row.ownerModuleId() : selectedId;
        return moduleId == null ? null : moduleId + ":" + row.id();
    }

    /**
     * The favourite star at the right of every interactive row – on every page, which is also what
     * makes a favourite recognisable in search results without a second marker for it.
     */
    private void renderFavoriteStars(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = contentX + contentWidth - STAR_COLUMN + 2;
        for (int i = 0; i < rowList.rows().size(); i++) {
            SettingRow row = rowList.rows().get(i);
            String optionId = optionIdOf(row);
            if (optionId == null) {
                continue;
            }
            int y = rowList.yOf(i);
            if (y < 0) {
                continue;
            }
            boolean on = Favorites.isFavorite(optionId);
            boolean hovered = mouseX >= x - 2 && mouseX <= x + STAR_COLUMN
                    && mouseY >= y && mouseY <= y + SBSTheme.ENTRY_HEIGHT;
            int color = on ? 0xFFFFD24B : (hovered ? SBSTheme.TEXT : SBSTheme.CARD_BORDER);
            g.text(SbsFonts.ui(), Component.literal(on ? STAR_ON : STAR_OFF), x,
                    y + (SBSTheme.ENTRY_HEIGHT - SbsFonts.ui().lineHeight) / 2, color);
        }
    }

    /** Toggles the favourite whose star was clicked. Returns true when a star took the click. */
    private boolean handleStarClick(double mouseX, double mouseY) {
        int x = contentX + contentWidth - STAR_COLUMN;
        if (mouseX < x || mouseX > contentX + contentWidth || mouseY < rowsTop || mouseY > rowsBottom) {
            return false;
        }
        for (int i = 0; i < rowList.rows().size(); i++) {
            String optionId = optionIdOf(rowList.rows().get(i));
            if (optionId == null) {
                continue;
            }
            int y = rowList.yOf(i);
            if (y < 0 || mouseY < y || mouseY > y + SBSTheme.ENTRY_HEIGHT) {
                continue;
            }
            Favorites.toggle(optionId);
            // Unpinning from the favourites page removes the row under the cursor, so that page has
            // to be rebuilt; elsewhere only the star's own colour changed.
            if (Favorites.PAGE_ID.equals(selectedId)) {
                refreshSettingsWidgets();
            }
            return true;
        }
        return false;
    }

    /** Draws the setting hover tooltip last (deferred), so it sits over every widget. */
    private final class SettingTooltipRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            renderFavoriteStars(g, mouseX, mouseY);
            renderJumpHighlight(g);
            renderSettingTooltip(g, mouseX, mouseY);
        }
    }

    /** Draws the open profile list over everything – it extends far outside its widget's bounds. */
    private final class ProfileOverlayRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            if (profileSelector != null) {
                profileSelector.renderOverlay(g, mouseX, mouseY);
            }
        }
    }

    // ------------------------------------------------------------------
    // Panel + sidebar rendering (behind the widgets)
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = SbsFonts.ui();

            g.fill(0, 0, SBSMainScreen.this.width, SBSMainScreen.this.height, SBSTheme.BG_TINT);

            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            // Header + accent divider.
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Skyblock Simplified"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            g.fill(panelX + pad, dividerY, panelX + pad + 10, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + panelW - pad - 10, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT_BRIGHT);

            // Vertical divider between the columns.
            int colX = sidebarX + SIDEBAR_WIDTH + pad / 2;
            g.fill(colX, sidebarTop, colX + 1, sidebarBottom, SBSTheme.ACCENT_SOFT);

            drawSidebar(g, mouseX, mouseY);

            // Search field background (border highlights when focused) + magnifier icon. The mode
            // switch draws itself as a widget, in the strip left of searchX this leaves free.
            int searchBorder = (searchBox != null && searchBox.isFocused())
                    ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
            SciFiRender.roundedRectWithBorder(g, searchX, searchY, searchWidth, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, searchBorder);
            int iconX = searchX + 4;
            int iconY = searchY + (SBSTheme.SEARCH_HEIGHT - 5) / 2;
            g.outline(iconX, iconY, 5, 5, SBSTheme.ACCENT);
            g.fill(iconX + 4, iconY + 4, iconX + 6, iconY + 6, SBSTheme.ACCENT);

            if (visibleRows().isEmpty()) {
                g.centeredText(font, Component.literal(
                                filtered.isEmpty() ? "No modules match your search." : "No matching settings."),
                        contentX + contentWidth / 2, rowsTop + 10, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = SbsFonts.ui();
            int visible = Math.max(1, (sidebarBottom - sidebarTop) / SIDEBAR_ROW_HEIGHT);
            for (int row = 0; row < visible; row++) {
                int index = sidebarScroll + row;
                if (index >= sidebarEntries.size()) {
                    break;
                }
                SidebarEntry entry = sidebarEntries.get(index);
                int y = sidebarTop + row * SIDEBAR_ROW_HEIGHT;
                int textY = y + (SIDEBAR_ROW_HEIGHT - font.lineHeight) / 2;

                if (entry.subHeader()) {
                    drawSubHeader(g, font, entry, y, textY, mouseX, mouseY);
                    continue;
                }
                if (entry.isHeader()) {
                    // Group header: a fold arrow, the accent label, the module count while folded,
                    // and a divider line running to the sidebar edge. Clicking anywhere on the row
                    // folds the group (see mouseClicked).
                    boolean folded = entry.folded();
                    boolean headerHover = mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_WIDTH
                            && mouseY >= y && mouseY < y + SIDEBAR_ROW_HEIGHT;
                    int headerColor = headerHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT;
                    String arrow = folded ? "▸" : "▾";   // ▸ folded, ▾ open
                    g.text(font, Component.literal(arrow), sidebarX + 2, textY, headerColor);
                    int labelX = sidebarX + 2 + font.width(arrow) + 3;
                    g.text(font, Component.literal(entry.header()), labelX, textY, headerColor);
                    int lineX = labelX + font.width(entry.header()) + 4;
                    if (folded) {
                        String count = String.valueOf(entry.count());
                        g.text(font, Component.literal(count), lineX, textY, SBSTheme.TEXT_MUTED);
                        lineX += font.width(count) + 4;
                    }
                    int lineY = y + SIDEBAR_ROW_HEIGHT / 2;
                    if (lineX < sidebarX + SIDEBAR_WIDTH - 2) {
                        g.fill(lineX, lineY, sidebarX + SIDEBAR_WIDTH, lineY + 1, SBSTheme.ACCENT_SOFT);
                    }
                    continue;
                }

                ModuleCategory category = entry.module();
                boolean selected = category.id().equals(selectedId);
                boolean hovered = mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_WIDTH
                        && mouseY >= y && mouseY < y + SIDEBAR_ROW_HEIGHT;

                if (selected || hovered) {
                    SciFiRender.roundedRect(g, sidebarX, y, SIDEBAR_WIDTH, SIDEBAR_ROW_HEIGHT - 2,
                            SBSTheme.CORNER_RADIUS, selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                }
                if (selected) {
                    g.fill(sidebarX + 1, y + 2, sidebarX + 3, y + SIDEBAR_ROW_HEIGHT - 4, SBSTheme.ACCENT);
                }
                // A module holding licence-gated rows wears the same red as those rows, so the
                // sidebar answers "where is the stuff I cannot use" without opening every page.
                // Derived from the rows themselves - the two cannot disagree. See LicenceMarks.
                if (sbs.modid.client.core.licence.LicenceMarks.markedModule(category.id())) {
                    SciFiRender.roundedRectWithBorder(g, sidebarX, y, SIDEBAR_WIDTH,
                            SIDEBAR_ROW_HEIGHT - 2, SBSTheme.CORNER_RADIUS,
                            SBSTheme.BAZAAR_OUTDATED_FILL, SBSTheme.BAZAAR_OUTDATED_FRAME);
                }
                // Modules sit indented under their group header - a little deeper where a sub-header
                // sits in between; the pinned Licence Token has no header and stays flush left,
                // visibly "above" the grouping.
                int indent = entry.indent() != null ? entry.indent()
                        : category.group() == ModuleGroup.PINNED ? 0
                        : subdivided.contains(category.group()) ? MODULE_INDENT_SUBDIVIDED : MODULE_INDENT;
                int textX = sidebarX + MODULE_TEXT_X + indent;
                // A page a custom layout has not placed yet (new since the layout was saved): a
                // "new" at the right edge, the name fitted to the room left of it.
                int nameRight = sidebarX + SIDEBAR_WIDTH;
                if (entry.fresh()) {
                    String mark = "new";
                    nameRight -= font.width(mark) + 3;
                    g.text(font, Component.literal(mark), nameRight + 3, textY, SBSTheme.ACCENT);
                }
                // Fitted against the true sidebar edge, no margin: a name that fitted before is drawn
                // exactly as before, and one that did not ("Sea Creature Announcer" ran 8px over the
                // scrollbar) now ends in an ellipsis inside the column.
                g.text(font, RowText.fit(font, category.displayName(), nameRight - textX),
                        textX, textY, selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            }
            // Draggable scrollbar in the gap between the list and the column divider.
            syncSidebarScrollbar();
            sidebarBar.render(g, sidebarScroll, mouseX, mouseY);
        }

        /**
         * A sub-header: indented under the group, quieter than it, and folded the same way -
         * arrow, label, and the module count while folded. No divider line: the group header
         * already draws one, and a line per family would turn the list into a ruled page.
         *
         * <p>The label starts after a fixed arrow column. {@code ▸} and {@code ▾} are different widths
         * (3 and 4px in the default font, both from unifont), so a label placed after the arrow it
         * happens to show would jump a pixel on every click.
         */
        private void drawSubHeader(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                   SidebarEntry entry, int y, int textY, int mouseX, int mouseY) {
            boolean folded = CategoryFolding.collapsed(entry.group(), entry.subgroup());
            boolean hover = mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_WIDTH
                    && mouseY >= y && mouseY < y + SIDEBAR_ROW_HEIGHT;
            int color = hover ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
            int arrowX = sidebarX + SUB_ARROW_X;
            g.text(font, Component.literal(folded ? "▸" : "▾"), arrowX, textY, color);
            int labelX = arrowX + Math.max(font.width("▸"), font.width("▾")) + 3;
            String count = folded ? String.valueOf(entry.count()) : null;
            int countRoom = count == null ? 0 : font.width(count) + 4;
            String label = RowText.fit(font, entry.header(), sidebarX + SIDEBAR_WIDTH - labelX - countRoom);
            g.text(font, Component.literal(label), labelX, textY, color);
            if (count != null) {
                g.text(font, Component.literal(count), labelX + font.width(label) + 4, textY,
                        SBSTheme.TEXT_MUTED);
            }
        }
    }
}
