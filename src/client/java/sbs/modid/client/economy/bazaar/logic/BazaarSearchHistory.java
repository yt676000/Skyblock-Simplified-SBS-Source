/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search history for the Bazaar / Auction House search sign: your recent search
 * terms, shown on the search sign so you can click one to run it again.
 *
 * <p>The trick that makes it reliable without parsing Hypixel's sign hint lines is a
 * <b>context timestamp</b>: {@link BazaarOrderTracker} calls {@link #noteSearchContext()} every tick
 * a Bazaar or Auction House GUI is open, and Hypixel opens the search sign immediately afterwards –
 * so {@link #recentSearchContext()} being true when a sign screen inits is what marks it a search
 * sign. Rendering, recording and the ↑/↓ browse are driven by {@code SignSearchMixin}.
 *
 * <p>The history list itself is persisted in {@link sbs.modid.client.core.config.SBSConfig.BazaarSettings}
 * so it survives restarts; queries are de-duplicated case-insensitively and most-recent-first.
 *
 * <p><b>Clicking.</b> Neither {@code Screen} nor the sign screen declares {@code mouseClicked} (it is
 * a default method on {@code ContainerEventHandler}), so there is no screen method to inject into.
 * The click therefore comes from one level down – {@code SearchSignMouseMixin} on the mouse handler's
 * own button callback – and is routed here only while a search sign is open. ↑/↓ still browse the
 * history for anyone who would rather not leave the keyboard.
 */
public final class BazaarSearchHistory {

    private static final BazaarSearchHistory INSTANCE = new BazaarSearchHistory();

    private static final int MAX_ENTRIES = 24;
    private static final int MAX_SHOWN = 6;
    /** Item matches offered above the history while something is typed. */
    private static final int MAX_SUGGESTIONS = 5;
    /** A sign opened within this long of a Bazaar/AH GUI being up is treated as its search sign. */
    private static final long CONTEXT_WINDOW_MS = 2_500L;

    private static final int PAD = 6;
    private static final int ROW_GAP = 2;
    private static final int ROW_H = 18;
    private static final int PANEL_W = 260;
    /** Floor for the width cap - below this the rows stop being readable at all. */
    private static final int MIN_PANEL_W = 140;
    private static final int DELETE_W = 18;
    private static final int DELETE_COLOR = 0xFFFF4040;

    /**
     * Which shop a search belongs to. The two histories are separate lists: the Bazaar and the
     * Auction House sell different things, so a shared list means every Bazaar search is buried
     * under the armour pieces you last looked up on the AH.
     */
    public enum Source {
        BAZAAR,
        AUCTION
    }

    private volatile long lastContextAt;

    /** The shop whose GUI was open most recently - what the next sign to open belongs to. */
    private volatile Source context = Source.BAZAAR;

    /**
     * The shop the currently open sign was opened from. Frozen when the sign inits rather than read
     * live, so a context note arriving while the sign is up cannot move the sign's history under it.
     */
    private volatile Source signSource = Source.BAZAAR;

    /**
     * What the open search sign wants done with a chosen query: put it in the input line and submit.
     * Registered by the sign's own mixin while it is open, so nothing here has to reach into a screen
     * it cannot see, and a click that arrives after the sign closed simply finds nobody home.
     */
    public interface SearchSign {
        void apply(String query);
    }

    private volatile SearchSign activeSign;
    /** The text currently typed on that sign, pushed in each frame so the panel can filter on it. */
    private volatile String typed = "";

    /** One clickable row of the drawn panel: where it is, and what a click on it does. */
    private record Row(int x, int y, int width, int height, String query, boolean delete) {

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    /** The rows drawn last frame, in draw order – the panel's hit-test map. */
    private volatile List<Row> rows = List.of();

    private BazaarSearchHistory() {
    }

    public static BazaarSearchHistory getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ context + history

    /** Master toggle. */
    public boolean enabled() {
        return ConfigManager.getInstance().get().bazaar.searchHistoryEnabled;
    }

    /**
     * Called every tick a Bazaar / Auction House GUI is open (from {@link BazaarOrderTracker}),
     * with which of the two it was - that is what decides the list the next sign reads and writes.
     */
    public void noteSearchContext(Source source) {
        lastContextAt = System.currentTimeMillis();
        context = source;
    }

    /** Called when a search sign opens: locks in the shop it belongs to for as long as it is up. */
    public void beginSign() {
        signSource = context;
    }

    /** The shop the open sign belongs to (the Bazaar until a sign says otherwise). */
    public Source signSource() {
        return signSource;
    }

    /** Whether a Bazaar/AH GUI was open just before now – i.e. this sign is likely its search input. */
    public boolean recentSearchContext() {
        return System.currentTimeMillis() - lastContextAt < CONTEXT_WINDOW_MS;
    }

    /** True for an Auction House GUI title (Bazaar is covered by {@link BazaarOrderTracker#isBazaarGui}). */
    public static boolean isAuctionGui(String title) {
        if (title == null) {
            return false;
        }
        return title.toLowerCase(Locale.ROOT).contains("auction");
    }

    /** Records a search term, most-recent-first, de-duplicated case-insensitively. */
    public void record(String query) {
        if (query == null) {
            return;
        }
        String q = query.trim();
        if (q.length() < 2 || isHintLine(q)) {
            return;
        }
        List<String> history = recent();
        history.removeIf(existing -> existing.equalsIgnoreCase(q));
        history.add(0, q);
        while (history.size() > MAX_ENTRIES) {
            history.remove(history.size() - 1);
        }
        ConfigManager.getInstance().save();
    }

    /** The history of the shop the open sign belongs to. */
    public List<String> recent() {
        return listFor(signSource);
    }

    /** The stored list for one shop. */
    public List<String> listFor(Source source) {
        var cfg = ConfigManager.getInstance().get().bazaar;
        return source == Source.AUCTION ? cfg.auctionSearchHistory : cfg.searchHistory;
    }

    public int size() {
        return recent().size();
    }

    /** Clears one shop's history - the other is left alone. */
    public void clear(Source source) {
        listFor(source).clear();
        ConfigManager.getInstance().save();
    }

    /** A sign line that is decoration / a Hypixel hint, not a real query. */
    private static boolean isHintLine(String s) {
        String l = s.toLowerCase(Locale.ROOT).trim();
        if (l.chars().allMatch(c -> c == '^' || c == '-' || c == ' ')) {
            return true; // the "^^^^^" pointer line
        }
        return l.contains("search this") || l.contains("enter query") || l.contains("click to")
                || l.equals("bazaar") || l.equals("auction house") || l.contains("query above");
    }

    // ------------------------------------------------------------------ render

    /** Called by the sign's mixin while it is open: who to hand a chosen query to, and what is typed. */
    public void bindSign(SearchSign sign, String typedText) {
        this.activeSign = sign;
        this.typed = typedText == null ? "" : typedText.trim();
    }

    /** Called when the search sign closes – a later click then lands on nobody rather than a ghost. */
    public void unbindSign() {
        this.activeSign = null;
        this.typed = "";
        this.rows = List.of();
    }

    /**
     * Routes one left click at GUI coordinates onto the panel. Returns {@code true} when a row took
     * it, so the caller swallows the click instead of letting it fall through to the sign behind.
     */
    public boolean click(double mouseX, double mouseY) {
        SearchSign sign = activeSign;
        for (Row row : rows) {
            if (!row.contains(mouseX, mouseY)) {
                continue;
            }
            if (row.delete()) {
                recent().removeIf(existing -> existing.equalsIgnoreCase(row.query()));
                ConfigManager.getInstance().save();
                return true;
            }
            if (sign != null) {
                sign.apply(row.query());
            }
            return true;
        }
        return false;
    }

    /**
     * Draws the search panel over the open sign: what you have typed, the items it matches, and your
     * recent searches with a delete button each. {@code selectedIndex} is the history entry currently
     * browsed in with ↑/↓ ({@code -1} for none). Called from {@code SignSearchMixin}.
     *
     * <p>Every drawn row is recorded in {@link #rows} as it is laid out, so the click handler
     * hit-tests exactly what is on screen rather than recomputing the layout and hoping the two
     * agree.
     */
    public void render(GuiGraphicsExtractor g, Font font, int selectedIndex, int mouseX, int mouseY) {
        List<String> history = recent();
        String query = typed;
        List<SkyBlockItemCatalog.Entry> matches = query.length() >= 2
                ? SkyBlockItemCatalog.getInstance().search(query, MAX_SUGGESTIONS)
                : List.of();
        int shownHistory = Math.min(history.size(), MAX_SHOWN);
        if (matches.isEmpty() && shownHistory == 0) {
            rows = List.of();
            return;
        }

        int rowStep = ROW_H + ROW_GAP;
        int height = PAD * 2 + ROW_H + ROW_GAP                       // the query field
                + matches.size() * rowStep
                + (shownHistory > 0 ? font.lineHeight + ROW_GAP + shownHistory * rowStep : 0);
        // Anchored to the LEFT EDGE, never centred. Everything vanilla puts on the sign screen - the
        // sign itself and the Done button under it - is centred, and a centred panel this tall lands
        // squarely on that button: the search then cannot be submitted at all, which reads as the
        // search button having gone missing. The width is capped at half the screen for the same
        // reason, so the vanilla controls stay reachable at every GUI scale.
        int panelW = Math.min(PANEL_W, Math.max(MIN_PANEL_W, g.guiWidth() / 2 - PAD * 2));
        int x = PAD;
        int y = Math.max(PAD, (g.guiHeight() - height) / 2);

        SciFiRender.glow(g, x, y, panelW, height, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, panelW, height, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, panelW - 2, height - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        List<Row> laid = new ArrayList<>(matches.size() + shownHistory);
        int ix = x + PAD;
        int rowW = panelW - PAD * 2;
        int iy = y + PAD;

        // The query field: the sign's own text is drawn tiny and at an angle, so it is echoed here.
        SciFiRender.roundedRectWithBorder(g, ix, iy, rowW, ROW_H, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, SBSTheme.ACCENT);
        String shown = query.isEmpty() ? "Type to search..." : query;
        g.text(font, Component.literal(trim(font, shown, rowW - 10)), ix + 5,
                iy + (ROW_H - font.lineHeight) / 2, query.isEmpty() ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT);
        iy += ROW_H + ROW_GAP;

        // Item matches for what is typed - clicking one searches for that exact item.
        for (SkyBlockItemCatalog.Entry entry : matches) {
            boolean hovered = mouseX >= ix && mouseX < ix + rowW && mouseY >= iy && mouseY < iy + ROW_H;
            SciFiRender.roundedRect(g, ix, iy, rowW, ROW_H, SBSTheme.CORNER_RADIUS,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
            g.item(SkyBlockItemIcons.getInstance().iconShared(entry.id, entry.material, 1),
                    ix + 2, iy + 1);
            g.text(font, Component.literal(trim(font, entry.name, rowW - 24)), ix + 22,
                    iy + (ROW_H - font.lineHeight) / 2, hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            laid.add(new Row(ix, iy, rowW, ROW_H, entry.name, false));
            iy += rowStep;
        }

        if (shownHistory > 0) {
            // Named, not just "History" - the two shops keep separate lists and the panel should say
            // which one you are looking at rather than leaving it to be inferred from the contents.
            g.text(font, Component.literal(
                            signSource == Source.AUCTION ? "Auction history:" : "Bazaar history:"),
                    ix, iy, SBSTheme.TEXT_MUTED);
            iy += font.lineHeight + ROW_GAP;
            int entryW = rowW - DELETE_W - ROW_GAP;
            for (int i = 0; i < shownHistory; i++) {
                String entry = history.get(i);
                boolean selected = i == selectedIndex;
                boolean hovered = mouseX >= ix && mouseX < ix + entryW
                        && mouseY >= iy && mouseY < iy + ROW_H;
                SciFiRender.roundedRect(g, ix, iy, entryW, ROW_H, SBSTheme.CORNER_RADIUS,
                        selected || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                String label = trim(font, entry, entryW - 8);
                g.text(font, Component.literal(label), ix + (entryW - font.width(label)) / 2,
                        iy + (ROW_H - font.lineHeight) / 2,
                        selected || hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
                laid.add(new Row(ix, iy, entryW, ROW_H, entry, false));

                // The delete button, its own row entry so a click on the X never runs the search.
                int dx = ix + entryW + ROW_GAP;
                boolean overDelete = mouseX >= dx && mouseX < dx + DELETE_W
                        && mouseY >= iy && mouseY < iy + ROW_H;
                SciFiRender.roundedRect(g, dx, iy, DELETE_W, ROW_H, SBSTheme.CORNER_RADIUS,
                        overDelete ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                g.text(font, Component.literal("✕"), dx + (DELETE_W - font.width("✕")) / 2,
                        iy + (ROW_H - font.lineHeight) / 2, DELETE_COLOR);
                laid.add(new Row(dx, iy, DELETE_W, ROW_H, entry, true));
                iy += rowStep;
            }
        }
        rows = List.copyOf(laid);
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("…")), false) + "…";
    }
}
