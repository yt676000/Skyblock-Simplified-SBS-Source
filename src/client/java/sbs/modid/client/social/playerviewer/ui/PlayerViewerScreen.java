/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

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
import sbs.modid.client.social.playerviewer.logic.PvSections;
import sbs.modid.client.social.playerviewer.ui.page.ItemSearchPage;
import sbs.modid.client.social.playerviewer.ui.page.home.HomeOverviewPage;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.player.PlayerProfileApi;
import sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The native SBS profile viewer ({@code /pv [player]}), fed by the raw Hypixel profile the
 * SBS backend relays; every value is computed on the client by
 * {@link sbs.modid.client.social.playerviewer.logic.PvCompute}.
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

    /** The resolved player and their profile list; {@code null} until loaded. */
    private volatile PlayerProfileApi.Profiles profiles;
    /** Root object the pages read ({@code name}, {@code uuid}); built locally, not from the server. */
    private volatile JsonObject data;
    /** Computed views by profile id, filled as each profile is fetched. */
    private final Map<String, JsonObject> views = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> viewErrors = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String status = "Loading...";
    /**
     * Set when the status text is a consent refusal ({@link sbs.modid.client.core.api.ApiFailure#NO_CONSENT}):
     * the screen then offers the way to the privacy setting under the message. {@code refusedScope}
     * may be null for a refusal with no declared scope.
     */
    private volatile boolean consentRefused;
    private volatile sbs.modid.client.core.licence.privacy.ConsentScope refusedScope;
    private SciFiButton privacyButton;
    /** Header refresh: drops the cached data of the shown player and loads it again. */
    private SciFiButton refreshButton;
    private int refreshShownSeconds = -1;
    /** When each profile's view arrived here; the fallback for "Updated X min ago". */
    private final Map<String, Long> arrivedAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** The header strings, rebuilt only when their inputs change (never measured per frame). */
    private String headerTitle = "";
    private String headerUpdated = "";
    private long headerUpdatedMinutes = Long.MIN_VALUE;
    private String headerKey = "";
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

    /**
     * First sidebar row shown. The sidebar scrolls only when a category has more sub-pages than fit
     * at the minimum row height - on a small screen that is the difference between an entry you can
     * reach and one that is simply not drawn.
     */
    private int sideScroll;
    private final sbs.modid.client.ui.component.SciFiScrollbar sideBar = new sbs.modid.client.ui.component.SciFiScrollbar();

    public PlayerViewerScreen(String playerName) {
        super(Component.literal("Profile Viewer"));
        this.playerName = playerName;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // Sized from the viewport: the preferred maximum on a large screen, never more than the
        // screen on a small one (a fixed minimum hung the panel off both edges at 1280x720).
        panelW = Math.min(Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2), 660);
        panelH = Math.min(Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2), 430);
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
        // Refresh: left of the profile cycler, sized for its longest label so the cooldown count
        // never changes its width.
        int refreshW = this.font.width(net.minecraft.client.resources.language.I18n.get("sbs.pv.refresh.cooldown", 10)) + 12;
        refreshButton = new SciFiButton(panelX + panelW - pad - 74 - 4 - refreshW,
                panelY + (SBSTheme.HEADER_HEIGHT - BACK_H) / 2, refreshW, BACK_H,
                Component.translatable("sbs.pv.refresh"), this::refresh);
        addRenderableWidget(refreshButton);
        refreshShownSeconds = -1;
        headerKey = "";
        PlayerProfileApi.getInstance().viewerOpened();

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

        nameSearchX = innerX + bw + 6;
        nameSearchY = buttonY;
        nameSearchW = contentWidth - (bw + 6);
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

        // Under the status message, only while it is a consent refusal; placed per frame below the
        // wrapped message by PanelRenderable, since the message's line count depends on its text.
        Component privacyLabel = Component.translatable("sbs.api.failure.no_consent.button");
        privacyButton = new SciFiButton(0, 0, this.font.width(privacyLabel) + 16, SBSTheme.SEARCH_HEIGHT,
                privacyLabel, () -> sbs.modid.client.ui.settings.PrivacyRows.openSettings(refusedScope));
        privacyButton.visible = false;
        addRenderableWidget(privacyButton);

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

    /** Sidebar rows that fit in the content height. */
    private int sideVisible() {
        return Math.max(1, (contentBottom - contentTop) / sideRowH());
    }

    private int sideMax() {
        return Math.max(0, currentCategory().subs().size() - sideVisible());
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
        sideScroll = 0;
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
        profiles = null;
        views.clear();
        viewErrors.clear();
        consentRefused = false;
        refusedScope = null;
        museumRequested.clear();
        statusRequestedAt = 0;
        // A player in the tab list needs no Mojang lookup; read it here, on the render thread.
        String knownUuid = null;
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            var info = connection.getPlayerInfo(playerName);
            if (info != null) {
                knownUuid = info.getProfile().id().toString();
            }
        }
        PlayerProfileApi.getInstance().profiles(playerName, knownUuid, result -> {
            if (result.error() != null) {
                noteRefusal(result);
                status = result.error();
                return;
            }
            PlayerProfileApi.Profiles loaded = result.value();
            JsonObject root = new JsonObject();
            root.addProperty("name", playerName);
            root.addProperty("uuid", loaded.uuid());
            data = root;
            profiles = loaded;
            profileIndex = 0;
            scroll = 0;
            if (loaded.profiles().isEmpty()) {
                status = "No SkyBlock profiles.";
                return;
            }
            status = "";
            loadProfile(0);
            Minecraft.getInstance().execute(() -> recordRecent(playerName));
        });
    }

    /**
     * The unavailable sections that matter for the open sub-page: its own sections, or for an
     * overview (no sections of its own) every unavailable section of the profile.
     */
    private List<String> unavailableFor(JsonObject profile) {
        List<String> out = new ArrayList<>();
        if (!profile.has("unavailable") || !profile.get("unavailable").isJsonArray()) {
            return out;
        }
        List<String> own = currentSub().sections();
        for (var e : profile.getAsJsonArray("unavailable")) {
            String id = e.getAsString();
            if (own.isEmpty() || own.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Museum requests already started, by profile id (one per profile per screen). */
    private final java.util.Set<String> museumRequested = new java.util.HashSet<>();
    private long statusRequestedAt;
    private boolean statusInFlight;
    private static final long STATUS_REFRESH_MS = 60_000L;

    /**
     * Starts the side-section requests the open page needs: the museum when the museum page is
     * open (once per profile), and the online status while the overview that shows it is open (at
     * most once a minute). Leaving those pages simply stops asking. Runs on the render thread;
     * results are applied back on it.
     */
    private void tickSideSections(JsonObject profile) {
        PlayerProfileApi.Profiles current = profiles;
        if (current == null || current.profiles().isEmpty() || searching()) {
            return;
        }
        var ref = current.profiles().get(Math.min(profileIndex, current.profiles().size() - 1));
        if (currentSub().sections().contains(PvSections.MUSEUM)
                && !profile.has("museum") && museumRequested.add(ref.profileId())) {
            profile.addProperty("museum_state", "Loading museum...");
            PlayerProfileApi.getInstance().museum(current.uuid(), ref.profileId(), result ->
                    Minecraft.getInstance().execute(() -> {
                        profile.remove("museum_state");
                        if (result.error() != null) {
                            profile.addProperty("museum_state", result.error());
                        } else if (result.value().invalid()) {
                            markUnavailable(profile, PvSections.MUSEUM);
                        } else if (result.value().view() != null) {
                            profile.add("museum", result.value().view());
                        }
                    }));
        }
        long now = System.currentTimeMillis();
        if (currentSub().page() instanceof HomeOverviewPage
                && !statusInFlight && now - statusRequestedAt >= STATUS_REFRESH_MS) {
            statusInFlight = true;
            statusRequestedAt = now;
            PlayerProfileApi.getInstance().status(current.uuid(), result ->
                    Minecraft.getInstance().execute(() -> {
                        statusInFlight = false;
                        JsonObject root = data;
                        if (root == null || result.error() != null) {
                            return;
                        }
                        JsonObject next = root.deepCopy();
                        next.remove("location");
                        next.remove("location_unavailable");
                        if (result.value().invalid()) {
                            next.addProperty("location_unavailable", true);
                        } else if (result.value().view() != null) {
                            next.add("location", result.value().view());
                        }
                        data = next;
                    }));
        }
    }

    private static void markUnavailable(JsonObject view, String section) {
        if (!view.has("unavailable") || !view.get("unavailable").isJsonArray()) {
            view.add("unavailable", new com.google.gson.JsonArray());
        }
        view.getAsJsonArray("unavailable").add(section);
    }

    /** Fetches and computes profile {@code index} unless it is already loaded or loading. */
    private void loadProfile(int index) {
        PlayerProfileApi.Profiles current = profiles;
        if (current == null || index >= current.profiles().size()) {
            return;
        }
        var ref = current.profiles().get(index);
        if (views.containsKey(ref.profileId()) || viewErrors.containsKey(ref.profileId())) {
            return;
        }
        viewErrors.put(ref.profileId(), "Loading profile...");
        PlayerProfileApi.getInstance().profile(current.uuid(), ref, result -> {
            if (result.value() != null) {
                views.put(ref.profileId(), result.value());
                arrivedAt.put(ref.profileId(), System.currentTimeMillis());
                viewErrors.remove(ref.profileId());
            } else {
                noteRefusal(result);
                viewErrors.put(ref.profileId(), result.error());
            }
        });
    }

    private void noteRefusal(PlayerProfileApi.Result<?> result) {
        if (result.refused() != null) {
            refusedScope = result.refused();
            consentRefused = true;
        }
    }

    // ------------------------------------------------------------------
    // Refresh and the header
    // ------------------------------------------------------------------

    /** The key the refresh cooldown is kept under: the uuid once known, else the typed name. */
    private String refreshKey() {
        PlayerProfileApi.Profiles current = profiles;
        return current != null ? current.uuid() : "name:" + playerName.toLowerCase(java.util.Locale.ROOT);
    }

    private void refresh() {
        PlayerProfileApi api = PlayerProfileApi.getInstance();
        String key = refreshKey();
        boolean allowed = profiles != null ? api.refresh(key) : api.cache().refresh(key);
        if (allowed) {
            arrivedAt.clear();
            load();
        }
    }

    /** Disabled with the remaining seconds while the cooldown runs; the label changes once a second. */
    private void tickRefreshButton() {
        long left = PlayerProfileApi.getInstance().cache().refreshCooldownMs(refreshKey());
        int seconds = (int) ((left + 999) / 1000);
        if (seconds != refreshShownSeconds) {
            refreshShownSeconds = seconds;
            refreshButton.active = seconds == 0;
            refreshButton.setMessage(seconds == 0 ? Component.translatable("sbs.pv.refresh")
                    : Component.translatable("sbs.pv.refresh.cooldown", seconds));
        }
    }

    /** When the shown profile was fetched: the relay's time when cached, else when it arrived. */
    private long shownFetchedAt() {
        PlayerProfileApi.Profiles current = profiles;
        if (current == null || current.profiles().isEmpty()) {
            return -1;
        }
        var ref = current.profiles().get(Math.min(profileIndex, current.profiles().size() - 1));
        long at = PlayerProfileApi.getInstance().viewFetchedAt(current.uuid(), ref.profileId());
        return at > 0 ? at : arrivedAt.getOrDefault(ref.profileId(), -1L);
    }

    /**
     * Title on the left after Back, "Updated X min ago" on the right before Refresh. When both do not
     * fit, the updated note goes first and then the name is shortened - measured against the actual
     * gap, rebuilt only when the name, the minute or the panel changes.
     */
    private void layoutHeader() {
        long fetched = shownFetchedAt();
        long minutes = fetched <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - fetched) / 60_000);
        String key = playerName + "|" + panelW + "|" + refreshButton.getX();
        if (key.equals(headerKey) && minutes == headerUpdatedMinutes) {
            return;
        }
        headerKey = key;
        headerUpdatedMinutes = minutes;
        int left = panelX + SBSTheme.PANEL_PADDING + BACK_W + 8;
        int room = refreshButton.getX() - 6 - left;
        String updated = minutes < 0 ? ""
                : minutes == 0 ? net.minecraft.client.resources.language.I18n.get("sbs.pv.updated.now")
                : net.minecraft.client.resources.language.I18n.get("sbs.pv.updated.minutes", minutes);
        String title = "Profile: §b" + playerName;
        int titleW = font.width(title);
        if (!updated.isEmpty() && titleW + 8 + font.width(updated) > room) {
            updated = "";
        }
        if (titleW > room) {
            title = font.plainSubstrByWidth(title, Math.max(0, room - font.width("..."))) + "...";
        }
        headerTitle = title;
        headerUpdated = updated;
    }

    @Override
    public void removed() {
        super.removed();
        PlayerProfileApi.getInstance().viewerClosed();
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
        PlayerProfileApi.Profiles current = profiles;
        if (current != null && !current.profiles().isEmpty()) {
            profileIndex = (profileIndex + 1) % current.profiles().size();
            scroll = 0;
            currentSub().page().reset();
            loadProfile(profileIndex);
        }
    }

    private void openSkycrypt() {
        if (!sbs.modid.client.core.net.Identity.isValidName(playerName)) {
            return;
        }
        // Allowlisted host, opened only after the player's click and the link confirmation.
        sbs.modid.client.core.net.UrlGuard.openAfterClick(this, "https://sky.shiiyu.moe/stats/" + playerName);
    }

    private JsonObject profile() {
        PlayerProfileApi.Profiles current = profiles;
        if (current == null || current.profiles().isEmpty() || data == null) {
            return null;
        }
        var ref = current.profiles().get(Math.min(profileIndex, current.profiles().size() - 1));
        JsonObject view = views.get(ref.profileId());
        if (view == null) {
            String pending = viewErrors.get(ref.profileId());
            status = pending == null ? "Loading profile..." : pending;
        }
        return view;
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
        if (sideBar.handleClick(mx, my, sideScroll, v -> sideScroll = clamp(v, 0, sideMax()))) {
            return true;
        }
        if (mx >= innerX && mx < innerX + SIDEBAR_W && my >= contentTop && my < contentBottom) {
            int idx = sideScroll + (int) ((my - contentTop) / sideRowH());
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
            // The page's scrollbar sits inside the page's rectangle, so it is offered the click first.
            if (PvDraw.scrollbarClick(mx, my, scroll, v -> scroll = clamp(v, 0, scrollMax))) {
                return true;
            }
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
        if (PvDraw.scrollbarDrag(event.y(), v -> scroll = clamp(v, 0, scrollMax))) {
            return true;
        }
        if (sideBar.handleDrag(event.y(), v -> sideScroll = clamp(v, 0, sideMax()))) {
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
        if (PvDraw.scrollbarRelease() | sideBar.release()) {
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
        if (scrollY != 0 && sideMax() > 0 && mouseX >= innerX && mouseX < innerX + SIDEBAR_W
                && mouseY >= contentTop && mouseY < contentBottom) {
            sideScroll = clamp(sideScroll - (int) Math.signum(scrollY), 0, sideMax());
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
            int pad = SBSTheme.PANEL_PADDING;
            tickRefreshButton();
            layoutHeader();
            int titleX = panelX + pad + BACK_W + 8;
            g.text(font, Component.literal(headerTitle), titleX, titleY, SBSTheme.ACCENT_BRIGHT);
            if (!headerUpdated.isEmpty()) {
                g.text(font, Component.literal(headerUpdated),
                        refreshButton.getX() - 6 - font.width(headerUpdated), titleY, SBSTheme.TEXT_MUTED);
            }
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            drawNameSearch(g);
            drawTabs(g, mouseX, mouseY);

            JsonObject profile = profile();
            if (profile == null) {
                // Wrapped: a consent message is a full sentence and outruns the narrowest panel.
                int y = contentTop + 12;
                for (var line : font.split(Component.literal(status.isEmpty() ? "..." : status), contentWidth)) {
                    g.centeredText(font, line, panelX + panelW / 2, y, SBSTheme.TEXT_MUTED);
                    y += font.lineHeight + 2;
                }
                privacyButton.visible = consentRefused;
                privacyButton.setX(panelX + (panelW - privacyButton.getWidth()) / 2);
                privacyButton.setY(y + 4);
                return;
            }
            privacyButton.visible = false;
            drawCaption(g, profile);
            drawSidebar(g, mouseX, mouseY);

            PvContext ctx = context(mouseX, mouseY, profile);
            PvDraw.beginFrame(mouseX, mouseY);
            tickSideSections(profile);
            List<String> missing = unavailableFor(profile);
            if (!searching() && !missing.isEmpty() && !currentSub().sections().isEmpty()) {
                PvDraw.unavailable(g, ctx, "This section is unavailable - the data for it could not be read.");
            } else {
                activePage().render(g, ctx);
                if (!searching() && currentSub().sections().isEmpty() && !missing.isEmpty()) {
                    String note = "§8Unavailable: " + String.join(", ", missing);
                    g.text(font, Component.literal(PvDraw.trim(font, note, ctx.width)), ctx.x,
                            ctx.bottom() - font.lineHeight, SBSTheme.TEXT_MUTED);
                }
            }
            scrollMax = ctx.scrollMax();
            scroll = clamp(scroll, 0, scrollMax);

            if (profile.has("notice")) {
                g.centeredText(font, Component.literal(PvDraw.str(profile, "notice")),
                        ctx.x + ctx.width / 2, ctx.y + 26, SBSTheme.TEXT_MUTED);
            }
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
            int visible = sideVisible();
            sideScroll = clamp(sideScroll, 0, sideMax());
            int barW = sbs.modid.client.ui.component.SciFiScrollbar.WIDTH;
            sideBar.set(innerX + SIDEBAR_W - barW, contentTop, visible * rowH, subs.size(), visible);
            // The bar's strip is taken from the rows only while it is shown.
            int rowW = sideBar.needed() ? SIDEBAR_W - barW - 2 : SIDEBAR_W;
            int y = contentTop;
            for (int i = sideScroll; i < subs.size() && y + rowH <= contentBottom; i++, y += rowH) {
                boolean active = i == sub && !searching();
                boolean hovered = mouseX >= innerX && mouseX < innerX + rowW
                        && mouseY >= y && mouseY < y + rowH;
                if (active || hovered) {
                    SciFiRender.roundedRect(g, innerX, y, rowW, rowH - 1,
                            SBSTheme.CORNER_RADIUS, active ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                }
                if (active) {
                    g.fill(innerX + 1, y + 2, innerX + 3, y + rowH - 3, SBSTheme.ACCENT);
                }
                g.text(font, Component.literal(PvDraw.trim(font, subs.get(i).label(), rowW - 12)),
                        innerX + 7, y + (rowH - font.lineHeight) / 2,
                        active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            }
            sideBar.render(g, sideScroll, mouseX, mouseY);
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
