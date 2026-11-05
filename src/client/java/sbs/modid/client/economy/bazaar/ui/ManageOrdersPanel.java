/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.economy.bazaar.logic.BazaarSyncService;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.BazaarStatus;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

/**
 * The "Manage Orders" side panel: your live Bazaar orders shown next to the Bazaar main menu, so you
 * can see at a glance which offers got undercut without opening "Your Bazaar Orders". Read-only – it
 * mirrors the tracked orders {@link BazaarOrderTracker} already scans and the live
 * {@link BazaarStatus} the background {@link BazaarSyncService} computes.
 *
 * <p><b>Two places, one panel.</b> Beside the Bazaar menu it is drawn from
 * {@link sbs.modid.client.core.mixin.OverlayRenderMixin} on every Bazaar GUI except the orders menu
 * itself (where the real slots already carry the highlight), positioned to the left of the container
 * and flipping to the right when there is no room. {@link #renderHud} draws the same rows as a
 * movable HUD card during play, for watching orders while you do something else - off by default,
 * see {@code manageOrdersHud}. Both paths share {@link #drawPanel}, so the panel cannot drift into
 * two designs.
 *
 * <p>Nothing about the content is container-derived: the rows come from the persisted,
 * profile-scoped {@link BazaarOrderTracker} cache and the statuses from {@link BazaarSyncService},
 * which polls on its own thread whether or not a Bazaar GUI is open. That is what makes the HUD card
 * show live data rather than whatever was on screen the last time you stood at the Bazaar.
 *
 * <p>Orders needing attention (undercut) sort to the top. Sorting, strings, colours and offsets are
 * built once per change into a {@link ManageOrdersModel}; a frame only draws it.
 */
public final class ManageOrdersPanel {

    private static final ManageOrdersPanel INSTANCE = new ManageOrdersPanel();

    private static final int PANEL_W = 172;
    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MAX_ROWS = 16;
    private static final int GAP_TO_CONTAINER = 8;

    /**
     * Row cap for the HUD card, deliberately lower than the panel's. Beside the Bazaar the panel has
     * a whole empty screen half to itself; on the HUD it competes with everything else drawn there,
     * and sixteen rows at GUI scale 3 is a wall. Anything past the cap is still counted in the
     * "+N more" line, and the ones that matter sort to the top.
     */
    private static final int HUD_MAX_ROWS = 8;

    private static final Component TITLE = Component.literal("Manage Orders");
    private static final Component EMPTY_HINT = Component.literal("No tracked orders yet.");

    /**
     * What the model is derived from: the tracker's list revision (orders added, removed, rescanned),
     * the order status revision (statuses are written in place by the API sync), the theme revision
     * (colours are theme-tinted) and the font (widths). The panel width is a constant and widths are
     * in GUI units, so the GUI scale does not enter; no row shows a relative time, so no clock.
     */
    record ModelKey(long ordersRevision, long statusRevision, int themeRevision, Font font) {
    }

    /**
     * The model plus its draw-ready {@link Component}s, built with it so a frame never wraps a
     * string. {@code name/amount/status} are parallel to {@code model.rows()}; {@code more} is the
     * "+N more" line for each cap ({@link #MAX_ROWS} and {@link #HUD_MAX_ROWS}), null when unused.
     */
    record Drawn(ManageOrdersModel model, FormattedCharSequence title, FormattedCharSequence emptyHint,
                 FormattedCharSequence tag, FormattedCharSequence[] name, FormattedCharSequence[] amount,
                 FormattedCharSequence[] status, FormattedCharSequence morePanel,
                 FormattedCharSequence moreHud) {
        FormattedCharSequence more(int maxRows) {
            return maxRows == HUD_MAX_ROWS ? moreHud : morePanel;
        }
    }

    /** Rows for the current orders, rebuilt only when the {@link ModelKey} changes. */
    private final CachedModel<ModelKey, Drawn> model =
            new CachedModel<>("bazaar.manageOrders", ManageOrdersPanel::buildModel);

    /** The last key handed to {@link #model}, reused while its inputs are unchanged (no per-frame key). */
    private ModelKey lastKey;

    private ManageOrdersPanel() {
    }

    public static ManageOrdersPanel getInstance() {
        return INSTANCE;
    }

    /** Called for every container screen from the overlay render hook. */
    public void render(AbstractContainerScreen<?> container, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!ConfigManager.getInstance().get().bazaar.manageOrdersPanel) {
            return;
        }
        String title = MenuFrame.of(container).normalised();
        // Show it across the Bazaar, but not inside the orders menu (the real slots are highlighted
        // there already) and not while a search/confirm sign flow is up.
        if (!BazaarOrderTracker.isBazaarGui(title) || BazaarOrderTracker.isOrdersMenu(title)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Drawn orders = currentModel(font);

        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) container;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        int imageW = bounds.skyblockSimplified$imageWidth();

        // Left of the container by default; flip to the right if it would clip off-screen.
        int x = left - PANEL_W - GAP_TO_CONTAINER;
        if (x < 4) {
            x = left + imageW + GAP_TO_CONTAINER;
        }
        drawPanel(g, font, orders, x, top, MAX_ROWS, SBSTheme.PANEL_CORNER);
    }

    /**
     * Whether this panel is currently drawing to the <b>right</b> of the container.
     *
     * <p>Asked by {@link OrderHistoryPanel}, which prefers that same side. Both panels flip when
     * their preferred side runs out of room, and on a narrow viewport that lands them on top of one
     * another - two lists of orders in the same pixels, each looking like a corrupted version of the
     * other. Reproducing the decision rather than exporting the resulting x keeps this cheap and
     * order-independent: the answer must not depend on which panel rendered first this frame.
     */
    static boolean occupiesRightSide(AbstractContainerScreen<?> container) {
        if (!ConfigManager.getInstance().get().bazaar.manageOrdersPanel || container == null) {
            return false;
        }
        String title = MenuFrame.of(container).normalised();
        if (!BazaarOrderTracker.isBazaarGui(title) || BazaarOrderTracker.isOrdersMenu(title)) {
            return false;
        }
        int left = ((AbstractContainerScreenAccessor) container).skyblockSimplified$leftPos();
        return left - PANEL_W - GAP_TO_CONTAINER < 4;
    }

    /**
     * The same panel as a movable HUD card, drawn from the HUD render hook during play.
     *
     * <p>Self-hiding on two counts: it is off unless the setting is on, and it draws nothing when
     * there are no tracked orders. The container panel says "No tracked orders yet." because you
     * opened the Bazaar and are owed an answer; a permanent empty card on the HUD is just clutter,
     * and the card reappearing on its own is the signal that you have orders again.
     */
    public static void renderHud(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().bazaar.manageOrdersHud
                || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.BAZAAR_ORDERS)
                // No sidebar means not on SkyBlock: the orders belong to a profile that is not
                // loaded, so showing them in a lobby or another game would be stating stale facts.
                || SkyBlockLocation.zone().isEmpty()) {
            return;
        }
        // Cheap emptiness check before touching the model: the card is off for most players.
        if (BazaarOrderTracker.getInstance().getOrders().isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Drawn orders = INSTANCE.currentModel(font);
        if (orders.model().orderCount() == 0) {
            return;
        }
        HudElement.Bounds bounds = HudElement.BAZAAR_ORDERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();

        HudLayout.begin(g, HudElement.BAZAAR_ORDERS);
        // Measuring is what opts the card into the growth correction, so a long order list grows
        // away from the screen edge it is anchored to instead of off it.
        HudLayout.measure(HudElement.BAZAAR_ORDERS, x, y, PANEL_W,
                panelHeight(font, orders.model().orderCount(), HUD_MAX_ROWS));
        INSTANCE.drawPanel(g, font, orders, x, y, HUD_MAX_ROWS, SBSTheme.HUD_CORNER);
        HudLayout.end(g);
    }

    private Drawn currentModel(Font font) {
        long ordersRev = BazaarOrderTracker.getInstance().revision();
        long statusRev = BazaarOrder.statusRevision();
        int themeRev = SBSTheme.revision();
        ModelKey key = lastKey;
        if (key == null || key.ordersRevision() != ordersRev || key.statusRevision() != statusRev
                || key.themeRevision() != themeRev || key.font() != font) {
            key = new ModelKey(ordersRev, statusRev, themeRev, font);
            lastKey = key;
        }
        return model.get(key);
    }

    private static Drawn buildModel(ModelKey key) {
        Font font = key.font();
        ManageOrdersModel.Measure measure = new ManageOrdersModel.Measure() {
            @Override
            public int width(String text) {
                return font.width(text);
            }

            @Override
            public String prefixByWidth(String text, int maxWidth) {
                return font.plainSubstrByWidth(text, maxWidth, false);
            }
        };
        ManageOrdersModel m = ManageOrdersModel.build(BazaarOrderTracker.getInstance().getOrders(), measure,
                PANEL_W - 2 * PAD, MAX_ROWS);
        int n = m.rows().size();
        FormattedCharSequence[] name = new FormattedCharSequence[n];
        FormattedCharSequence[] amount = new FormattedCharSequence[n];
        FormattedCharSequence[] status = new FormattedCharSequence[n];
        for (int i = 0; i < n; i++) {
            ManageOrdersModel.Row row = m.rows().get(i);
            name[i] = seq(row.name());
            amount[i] = seq(row.amount());
            status[i] = seq(row.status());
        }
        int count = m.orderCount();
        return new Drawn(m, Language.getInstance().getVisualOrder(TITLE),
                Language.getInstance().getVisualOrder(EMPTY_HINT), seq(m.tag()), name, amount, status,
                count > MAX_ROWS ? seq("+" + (count - MAX_ROWS) + " more") : null,
                count > HUD_MAX_ROWS ? seq("+" + (count - HUD_MAX_ROWS) + " more") : null);
    }

    /** What {@code g.text(font, Component, ...)} would compute every frame, computed once here. */
    private static FormattedCharSequence seq(String text) {
        return Language.getInstance().getVisualOrder(Component.literal(text));
    }

    /** The panel's height for a given order count, so a caller can reserve space before drawing. */
    private static int panelHeight(Font font, int orderCount, int maxRows) {
        int lineH = font.lineHeight + LINE_GAP;
        int shown = Math.max(1, Math.min(orderCount, maxRows));   // 1 = the "no orders" hint's row
        return PAD * 2 + font.lineHeight + LINE_GAP               // header
                + shown * lineH                                    // rows (or the empty hint)
                + (orderCount > maxRows ? lineH : 0);              // "+N more"
    }

    /** Shell, header and rows at an absolute position - everything both call sites have in common. */
    private void drawPanel(GuiGraphicsExtractor g, Font font, Drawn drawn,
                           int x, int y, int maxRows, int corner) {
        ManageOrdersModel orders = drawn.model();
        int count = orders.orderCount();
        int shown = Math.min(Math.min(count, maxRows), orders.rows().size());
        int lineH = font.lineHeight + LINE_GAP;
        int height = panelHeight(font, count, maxRows);

        HudCard.draw(g, x, y, PANEL_W, height, corner);

        int ix = x + PAD;
        int iy = y + PAD;

        // Header: title + a count of orders that need attention (undercut), red when > 0.
        g.text(font, drawn.title(), ix, iy, SBSTheme.ACCENT_BRIGHT);
        g.text(font, drawn.tag(), ix + orders.tagX(), iy, orders.tagColor());

        int ly = iy + font.lineHeight + LINE_GAP;
        if (count == 0) {
            g.text(font, drawn.emptyHint(), ix, ly, SBSTheme.TEXT_MUTED);
            return;
        }
        int dotOffset = (font.lineHeight - 4) / 2;
        for (int i = 0; i < shown; i++) {
            ManageOrdersModel.Row row = orders.rows().get(i);
            int dotY = ly + dotOffset;
            g.fill(ix, dotY, ix + 4, dotY + 4, row.statusColor());
            g.text(font, drawn.name()[i], ix + row.nameX(), ly, row.nameColor());
            g.text(font, drawn.amount()[i], ix + row.amountX(), ly, SBSTheme.TEXT_MUTED);
            g.text(font, drawn.status()[i], ix + row.statusX(), ly, row.statusColor());
            ly += lineH;
        }
        if (count > maxRows) {
            g.text(font, drawn.more(maxRows), ix, ly, SBSTheme.TEXT_MUTED);
        }
    }
}
