/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvNav;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.social.playerviewer.ui.page.ItemSearchPage;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.player.PlayerProfileApi;
import sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The native SBS profile viewer ({@code /pv [player]}), fed by the cloud's {@code /api/pv}
 * (15-min cache).
 *
 * <p>This class is only the <b>shell</b>: it owns the window, the two-level navigation (a top bar of
 * categories from {@link PvNav}, and per category a left sidebar of its sub-pages), the profile
 * cycler, the recent-player strip and the scroll wheel. Every page draws itself through
 * {@link PvPage} inside the rectangle the shell hands it, so pages can never overlap the chrome and
 * adding one does not touch this file.
 */
public final class PlayerViewerScreen extends Screen {

    /** Width of the right-side recent-players head strip, and how many to keep. */
    private static final int STRIP_W = 20;
    private static final int RECENT_MAX = 12;

    /** The header's top-left back button. */
    private static final int BACK_W = 54;
    private static final int BACK_H = 16;

    /** Category tab strip: height of one tab, the gaps around them, and the label padding. */
    private static final int TAB_H = 15;
    private static final int TAB_GAP = 3;
    private static final int TAB_PAD = 7;

    /** Left sidebar: width and one entry's height. Wide enough for the longest sub-page label
     *  ("Accessory Bag", 76px) to render untrimmed. */
    private static final int SIDEBAR_W = 92;
    /** Preferred sidebar row height, and the floor below which the label stops being readable. */
    private static final int SIDE_ROW_H = 13;
    private static final int SIDE_ROW_MIN = 11;

    /** Cached player-head icons for the recent strip (name -> head, skin resolves async). */
    private static final Map<String, ItemStack> HEAD_CACHE = new HashMap<>();

    private final String playerName;
    private final List<PvNav.Category> nav = PvNav.build();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    /** Width available to the navigation + content, i.e. everything left of the recent strip. */
    private int contentWidth;
    private int stripX;
    /** Top of the sidebar + content band, below the category tabs and the profile caption. */
    private int contentTop;
    private int contentBottom;
    /** Per-frame layout of the category tabs: {x, y, w} per category, filled by {@link #layoutTabs}. */
    private final List<int[]> tabRects = new ArrayList<>();
    private int tabsBottom;

    private volatile JsonObject data;
    private volatile String status = "Loading...";
    private int profileIndex;

    private int category;
    private int sub;
    private int scroll;

    /** The Inventory-category item search (all containers), and the page that renders its results. */
    private EditBox itemSearch;
    private ItemSearchPage searchPage;

    /** The bottom-right "load another profile by gametag" box. */
    private EditBox nameSearch;
    private int nameSearchX;
    private int nameSearchY;
    private int nameSearchW;

    /** Scroll ceiling the current page reported last frame; keeps the wheel inside the real list. */
    private int scrollMax;

    public PlayerViewerScreen(String playerName) {
        super(Component.literal("Profile Viewer"));
        this.playerName = playerName;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 460, 660);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 430);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2 - STRIP_W - 4;   // leave room for the recent strip
        stripX = innerX + contentWidth + 4;

        layoutTabs();
        // The caption row under the tabs names the profile and the open page.
        contentTop = tabsBottom + this.font.lineHeight + 5;
        int buttonY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        contentBottom = buttonY - 6;

        addRenderableOnly(new PanelRenderable());

        // Back: top-left in the header, the obvious way out of the viewer.
        addRenderableWidget(new SciFiButton(panelX + pad, panelY + (SBSTheme.HEADER_HEIGHT - BACK_H) / 2,
                BACK_W, BACK_H, Component.literal("◀ Back"),
                () -> Minecraft.getInstance().setScreenAndShow(new SBSMainScreen())));
        // Profile cycler: top-right in the header, opposite Back.
        addRenderableWidget(new SciFiButton(panelX + panelW - pad - 74,
                panelY + (SBSTheme.HEADER_HEIGHT - BACK_H) / 2, 74, BACK_H,
                Component.literal("Profile ▶"), this::cycleProfile));

        // Inventory item search: sits in the caption row, right of the profile name.
        int textH = this.font.lineHeight;
        itemSearch = new EditBox(this.font, innerX + SIDEBAR_W + 10, tabsBottom + 2,
                Math.max(40, contentWidth - SIDEBAR_W - 10 - 4), textH,
                Component.literal("Search items"));
        itemSearch.setBordered(false);
        itemSearch.setMaxLength(48);
        itemSearch.setTextColor(SBSTheme.TEXT);
        itemSearch.setHint(Component.literal("Search all containers..."));
        itemSearch.setVisible(isInventory());
        addRenderableWidget(itemSearch);
        searchPage = new ItemSearchPage(() -> itemSearch.getValue());

        // Bottom row: the two actions on the left, the gametag search on the right.
        int bw = 64;
        addRenderableWidget(new SciFiButton(innerX, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("SkyCrypt"), this::openSkycrypt));
        addRenderableWidget(new SciFiButton(innerX + bw + 6, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Refresh"), this::load));

        nameSearchX = innerX + (bw + 6) * 2;
        nameSearchY = buttonY;
        nameSearchW = contentWidth - (bw + 6) * 2;
        int goW = 20;
        nameSearch = new EditBox(this.font, nameSearchX + 6,
                buttonY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                nameSearchW - goW - 12, textH, Component.literal("Load profile"));
        nameSearch.setBordered(false);
        nameSearch.setMaxLength(16);
        nameSearch.setTextColor(SBSTheme.TEXT);
        nameSearch.setHint(Component.literal("Load player..."));
        addRenderableWidget(nameSearch);
        addRenderableWidget(new SciFiButton(nameSearchX + nameSearchW - goW, buttonY, goW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("▶"), this::loadSearchedPlayer));

        // The SkyCrypt / price-browser windows draw on top of everything (added last = topmost),
        // so clicking SkyCrypt lays the site over the viewer instead of leaving it.
        addRenderableOnly(new BrowserRenderable());

        if (data == null) {
            load();
        }
    }

    /**
     * Flows the category tabs over as many rows as they need, each tab sized to its own label.
     * Because nothing here is hard-coded to a category count, a new entry in {@link PvNav} simply
     * re-flows the strip instead of squeezing every label into an ever-narrower fixed column.
     */
    private void layoutTabs() {
        tabRects.clear();
        int x = innerX;
        int y = dividerY + SBSTheme.GAP_AFTER_HEADER;
        for (PvNav.Category c : nav) {
            int w = this.font.width(c.label()) + TAB_PAD * 2;
            if (x > innerX && x + w > innerX + contentWidth) {
                x = innerX;
                y += TAB_H + TAB_GAP;
            }
            tabRects.add(new int[]{x, y, w});
            x += w + TAB_GAP;
        }
        tabsBottom = y + TAB_H + 4;
    }

    // ------------------------------------------------------------------
    // Navigation state
    // ------------------------------------------------------------------

    private PvNav.Category currentCategory() {
        return nav.get(clamp(category, 0, nav.size() - 1));
    }

    private PvNav.Sub currentSub() {
        List<PvNav.Sub> subs = currentCategory().subs();
        return subs.get(clamp(sub, 0, subs.size() - 1));
    }

    private boolean isInventory() {
        return "Inventory".equals(currentCategory().label());
    }

    /**
     * Row height for the current category's sidebar. The tallest category (Inventory, 11 entries)
     * does not fit at the preferred height on the smallest panel, so rows compress just enough to
     * keep every entry on screen – an entry you cannot click is worse than a tighter list.
     */
    private int sideRowH() {
        int subs = Math.max(1, currentCategory().subs().size());
        int fit = (contentBottom - contentTop) / subs;
        return clamp(fit, SIDE_ROW_MIN, SIDE_ROW_H);
    }

    /** True while the Inventory search box has text – the results then replace the sub-page. */
    private boolean searching() {
        return isInventory() && itemSearch != null && !itemSearch.getValue().trim().isEmpty();
    }

    private void selectCategory(int index) {
        if (index == category) {
            return;
        }
        currentSub().page().reset();
        category = index;
        sub = 0;
        scroll = 0;
        if (itemSearch != null) {
            itemSearch.setVisible(isInventory());
            if (!isInventory()) {
                itemSearch.setValue("");
            }
        }
    }

    private void selectSub(int index) {
        if (index == sub) {
            return;
        }
        currentSub().page().reset();
        sub = index;
        scroll = 0;
    }

    /** The rectangle the current page draws into: right of the sidebar, left of the recent strip. */
    private PvContext context(int mouseX, int mouseY, JsonObject profile) {
        int x = innerX + SIDEBAR_W + 6;
        return new PvContext(this.font, data, profile, x, contentTop,
                innerX + contentWidth - x, contentBottom - contentTop, mouseX, mouseY, scroll);
    }

    private PvPage activePage() {
        return searching() ? searchPage : currentSub().page();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void load() {
        status = "Loading " + playerName + "...";
        data = null;
        PlayerProfileApi.getInstance().fetch(playerName, (result, error) -> {
            if (error != null) {
                status = error;
                return;
            }
            data = result;
            profileIndex = 0;
            scroll = 0;
            status = result.getAsJsonArray("profiles").isEmpty() ? "No SkyBlock profiles." : "";
            if (!result.getAsJsonArray("profiles").isEmpty()) {
                recordRecent(result.has("name") ? result.get("name").getAsString() : playerName);
            }
        });
    }

    /** Prepends {@code name} to the persisted recent-players list (dedup, capped). */
    private static void recordRecent(String name) {
        var recent = sbs.modid.client.core.config.ConfigManager.getInstance().get().playerViewer.recentPlayers;
        recent.removeIf(n -> n.equalsIgnoreCase(name));
        recent.add(0, name);
        while (recent.size() > RECENT_MAX) {
            recent.remove(recent.size() - 1);
        }
        sbs.modid.client.core.config.ConfigManager.getInstance().save();
    }

    /** The cached player-head icon for the recent strip (skin resolves asynchronously). */
    private static ItemStack head(String name) {
        return HEAD_CACHE.computeIfAbsent(name, n -> {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            stack.set(net.minecraft.core.component.DataComponents.PROFILE,
                    ResolvableProfile.createUnresolved(n));
            return stack;
        });
    }

    private void cycleProfile() {
        JsonObject current = data;
        if (current != null && !current.getAsJsonArray("profiles").isEmpty()) {
            profileIndex = (profileIndex + 1) % current.getAsJsonArray("profiles").size();
            scroll = 0;
            currentSub().page().reset();
        }
    }

    private void openSkycrypt() {
        Minecraft.getInstance().execute(() ->
                sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager.getInstance()
                        .openNewUrl("https://sky.shiiyu.moe/stats/" + playerName, "SkyCrypt: " + playerName));
    }

    private JsonObject profile() {
        JsonObject current = data;
        if (current == null || current.getAsJsonArray("profiles").isEmpty()) {
            return null;
        }
        JsonArray profiles = current.getAsJsonArray("profiles");
        return profiles.get(Math.min(profileIndex, profiles.size() - 1)).getAsJsonObject();
    }

    /** Opens the profile of the gametag typed into the bottom-right search box. */
    private void loadSearchedPlayer() {
        String name = nameSearch.getValue().trim();
        if (name.isEmpty() || name.equalsIgnoreCase(playerName)) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new PlayerViewerScreen(name));
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Browser windows sit on top of the viewer, so they get first pick of every click.
        if (PriceBrowserManager.getInstance().handleClick(this, event)) {
            return true;
        }
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        double mx = event.x();
        double my = event.y();

        for (int i = 0; i < tabRects.size(); i++) {
            int[] r = tabRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + TAB_H) {
                selectCategory(i);
                return true;
            }
        }
        List<PvNav.Sub> subs = currentCategory().subs();
        if (mx >= innerX && mx < innerX + SIDEBAR_W && my >= contentTop && my < contentBottom) {
            int idx = (int) ((my - contentTop) / sideRowH());
            if (idx >= 0 && idx < subs.size()) {
                selectSub(idx);
                return true;
            }
        }
        // Recent-players strip on the right: click a head to view that player.
        var recent = sbs.modid.client.core.config.ConfigManager.getInstance().get().playerViewer.recentPlayers;
        if (mx >= stripX && mx <= stripX + PvDraw.CELL && my >= contentTop) {
            int idx = (int) ((my - contentTop) / PvDraw.CELL);
            if (idx >= 0 && idx < recent.size()) {
                String name = recent.get(idx);
                if (!name.equalsIgnoreCase(playerName)) {
                    Minecraft.getInstance().setScreenAndShow(new PlayerViewerScreen(name));
                }
                return true;
            }
        }
        JsonObject profile = profile();
        if (profile != null) {
            return activePage().mouseClicked(context((int) mx, (int) my, profile), mx, my);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (PriceBrowserManager.getInstance().handleDrag(this, event)) {
            return true;
        }
        if (super.mouseDragged(event, dragX, dragY)) {
            return true;
        }
        JsonObject profile = profile();
        if (profile == null) {
            return false;
        }
        return activePage().mouseDragged(context((int) event.x(), (int) event.y(), profile),
                event.x(), event.y(), dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (PriceBrowserManager.getInstance().handleRelease(event)) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (PriceBrowserManager.getInstance().charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // An open browser window (typing / Escape to release focus) gets the key first.
        if (PriceBrowserManager.getInstance().handleKey(event)) {
            return true;
        }
        // Enter in the gametag box loads that player, so the search needs no mouse at all.
        if (nameSearch != null && nameSearch.isFocused()
                && (event.key() == 257 || event.key() == 335)) {  // Enter / keypad Enter
            loadSearchedPlayer();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (PriceBrowserManager.getInstance().handleScroll(this, mouseX, mouseY, scrollY)) {
            return true;
        }
        if (scrollY != 0) {
            // Clamp against the row count the page reported last frame: the list can always reach
            // its end, and never scrolls past it into empty space.
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Draws the floating SkyCrypt / price-browser windows over the viewer (added last = on top). */
    private final class BrowserRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            PriceBrowserManager.getInstance().renderAll(PlayerViewerScreen.this, g, mouseX, mouseY);
        }
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = PlayerViewerScreen.this.font;
            g.fill(0, 0, PlayerViewerScreen.this.width, PlayerViewerScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Profile: §b" + playerName),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            drawNameSearch(g);
            drawTabs(g, mouseX, mouseY);

            JsonObject profile = profile();
            if (profile == null) {
                g.centeredText(font, Component.literal(status.isEmpty() ? "..." : status),
                        panelX + panelW / 2, contentTop + 12, SBSTheme.TEXT_MUTED);
                return;
            }
            drawCaption(g, profile);
            drawSidebar(g, mouseX, mouseY);

            PvContext ctx = context(mouseX, mouseY, profile);
            activePage().render(g, ctx);
            scrollMax = ctx.scrollMax();
            scroll = clamp(scroll, 0, scrollMax);

            drawRecentStrip(g, mouseX, mouseY);
        }

        /** The category strip: one tab per {@link PvNav} category, flowed over as many rows as fit. */
        private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = PlayerViewerScreen.this.font;
            for (int i = 0; i < tabRects.size(); i++) {
                int[] r = tabRects.get(i);
                boolean active = i == category;
                boolean hovered = mouseX >= r[0] && mouseX < r[0] + r[2]
                        && mouseY >= r[1] && mouseY < r[1] + TAB_H;
                SciFiRender.roundedRectWithBorder(g, r[0], r[1], r[2], TAB_H, SBSTheme.CORNER_RADIUS,
                        active || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                if (active) {
                    g.fill(r[0] + 3, r[1] + TAB_H - 2, r[0] + r[2] - 3, r[1] + TAB_H - 1, SBSTheme.ACCENT);
                }
                g.centeredText(font, Component.literal(nav.get(i).label()), r[0] + r[2] / 2,
                        r[1] + (TAB_H - font.lineHeight) / 2, active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            }
        }

        /**
         * The caption row: which profile is open on the left, and the breadcrumb on the right – or
         * the item-search field, which takes the row over for the Inventory category.
         */
        private void drawCaption(GuiGraphicsExtractor g, JsonObject profile) {
            var font = PlayerViewerScreen.this.font;
            String head = "§f" + PvDraw.str(profile, "cute_name")
                    + (profile.get("selected").getAsBoolean() ? " §a✔" : "")
                    + " §7" + PvDraw.str(profile, "game_mode");
            int y = tabsBottom + 2;

            if (isInventory()) {
                // The search box takes the rest of the row, so the caption keeps the sidebar's
                // column. Frame the box so it reads as a field.
                int sx = innerX + SIDEBAR_W + 6;
                boolean focused = itemSearch != null && itemSearch.isFocused();
                SciFiRender.roundedRectWithBorder(g, sx, y - 2, innerX + contentWidth - sx,
                        font.lineHeight + 4, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                        focused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                g.text(font, Component.literal(PvDraw.trim(font, head, SIDEBAR_W + 4)), innerX, y,
                        SBSTheme.TEXT);
                return;
            }
            // Otherwise the breadcrumb sits hard right and the caption takes everything left of
            // it, so both stay whole instead of the profile name being needlessly cut.
            String crumb = "§7" + currentCategory().label() + " §8▸ §b" + currentSub().label();
            int crumbW = font.width(crumb);
            g.text(font, Component.literal(crumb), innerX + contentWidth - crumbW, y,
                    SBSTheme.ACCENT_BRIGHT);
            g.text(font, Component.literal(PvDraw.trim(font, head, contentWidth - crumbW - 8)),
                    innerX, y, SBSTheme.TEXT);
        }

        /** The left sidebar: the current category's sub-pages, the open one accented. */
        private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = PlayerViewerScreen.this.font;
            List<PvNav.Sub> subs = currentCategory().subs();
            int rowH = sideRowH();
            int y = contentTop;
            for (int i = 0; i < subs.size() && y + rowH <= contentBottom; i++, y += rowH) {
                boolean active = i == sub && !searching();
                boolean hovered = mouseX >= innerX && mouseX < innerX + SIDEBAR_W
                        && mouseY >= y && mouseY < y + rowH;
                if (active || hovered) {
                    SciFiRender.roundedRect(g, innerX, y, SIDEBAR_W, rowH - 1,
                            SBSTheme.CORNER_RADIUS, active ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                }
                if (active) {
                    g.fill(innerX + 1, y + 2, innerX + 3, y + rowH - 3, SBSTheme.ACCENT);
                }
                g.text(font, Component.literal(PvDraw.trim(font, subs.get(i).label(), SIDEBAR_W - 12)),
                        innerX + 7, y + (rowH - font.lineHeight) / 2,
                        active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            }
            // The divider doubles as the page's left edge, so content never touches the sidebar.
            g.fill(innerX + SIDEBAR_W + 2, contentTop, innerX + SIDEBAR_W + 3, contentBottom,
                    SBSTheme.ACCENT_SOFT);
        }

        /** The bottom-right gametag box: type a name, press Enter (or ▶) to open that profile. */
        private void drawNameSearch(GuiGraphicsExtractor g) {
            if (nameSearch == null) {
                return;
            }
            int border = nameSearch.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
            SciFiRender.roundedRectWithBorder(g, nameSearchX, nameSearchY, nameSearchW,
                    SBSTheme.SEARCH_HEIGHT, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, border);
        }

        /** The right-side history strip: a player head per recently viewed player, current highlighted. */
        private void drawRecentStrip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var recent = sbs.modid.client.core.config.ConfigManager.getInstance().get().playerViewer.recentPlayers;
            g.fill(stripX - 3, contentTop, stripX - 2, contentBottom, SBSTheme.ACCENT_SOFT);
            int y = contentTop;
            for (int i = 0; i < recent.size() && y + PvDraw.CELL <= contentBottom; i++, y += PvDraw.CELL) {
                String name = recent.get(i);
                boolean current = name.equalsIgnoreCase(playerName);
                boolean hovered = mouseX >= stripX && mouseX < stripX + PvDraw.CELL
                        && mouseY >= y && mouseY < y + PvDraw.CELL;
                SciFiRender.roundedRectWithBorder(g, stripX, y, PvDraw.CELL - 1, PvDraw.CELL - 1, 2,
                        current || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        current ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                g.item(head(name), stripX + 1, y + 1);
                if (hovered) {
                    PvDraw.tooltip(g, PlayerViewerScreen.this.font,
                            List.of(Component.literal("§b" + name), Component.literal("§8click to view")),
                            mouseX, mouseY);
                }
            }
        }
    }
}
