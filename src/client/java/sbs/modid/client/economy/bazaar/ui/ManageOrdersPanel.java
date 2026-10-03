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
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.economy.bazaar.logic.BazaarSyncService;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.BazaarOrderType;
import sbs.modid.client.economy.bazaar.model.BazaarStatus;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 * <p>Orders needing attention (undercut) sort to the top.
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

    /** BUY orders tinted blue, SELL offers gold – so the side you placed reads at a glance. */
    /** How often the display order is recomputed. See {@link #sortedOrders()}. */
    private static final long SORT_INTERVAL_MS = 250;

    /** The last sorted view, and when it was built. */
    private List<BazaarOrder> sorted = List.of();
    private long sortedAt;

    private static final int BUY_COLOR = 0xFF55C7F5;
    private static final int SELL_COLOR = 0xFFE0A14D;

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
        List<BazaarOrder> orders = sortedOrders();

        Font font = Minecraft.getInstance().font;
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
        List<BazaarOrder> orders = INSTANCE.sortedOrders();
        if (orders.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds bounds = HudElement.BAZAAR_ORDERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();

        HudLayout.begin(g, HudElement.BAZAAR_ORDERS);
        // Measuring is what opts the card into the growth correction, so a long order list grows
        // away from the screen edge it is anchored to instead of off it.
        HudLayout.measure(HudElement.BAZAAR_ORDERS, x, y, PANEL_W,
                panelHeight(font, orders.size(), HUD_MAX_ROWS));
        INSTANCE.drawPanel(g, font, orders, x, y, HUD_MAX_ROWS, SBSTheme.HUD_CORNER);
        HudLayout.end(g);
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
    private void drawPanel(GuiGraphicsExtractor g, Font font, List<BazaarOrder> orders,
                           int x, int y, int maxRows, int corner) {
        int shown = Math.min(orders.size(), maxRows);
        boolean overflow = orders.size() > maxRows;
        int lineH = font.lineHeight + LINE_GAP;
        int height = panelHeight(font, orders.size(), maxRows);

        HudCard.draw(g, x, y, PANEL_W, height, corner);

        int ix = x + PAD;
        int right = x + PANEL_W - PAD;
        int iy = y + PAD;

        // Header: title + a count of orders that need attention (undercut), red when > 0.
        g.text(font, Component.literal("Manage Orders"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        int undercut = 0;
        for (BazaarOrder o : orders) {
            if (o.status() == BazaarStatus.OUTDATED) {
                undercut++;
            }
        }
        String tag = undercut > 0 ? undercut + " undercut" : orders.size() + "";
        g.text(font, Component.literal(tag), right - font.width(tag), iy,
                undercut > 0 ? SBSTheme.BAZAAR_OUTDATED_FRAME : SBSTheme.TEXT_MUTED);

        int ly = iy + font.lineHeight + LINE_GAP;
        if (orders.isEmpty()) {
            g.text(font, Component.literal("No tracked orders yet."), ix, ly, SBSTheme.TEXT_MUTED);
            return;
        }
        for (int i = 0; i < shown; i++) {
            drawRow(g, font, orders.get(i), ix, right, ly);
            ly += lineH;
        }
        if (overflow) {
            String more = "+" + (orders.size() - maxRows) + " more";
            g.text(font, Component.literal(more), ix, ly, SBSTheme.TEXT_MUTED);
        }
    }

    /** One order row: [status dot] name (buy/sell tinted) … xAmount status. */
    private void drawRow(GuiGraphicsExtractor g, Font font, BazaarOrder order, int ix, int right, int ly) {
        int dotY = ly + (font.lineHeight - 4) / 2;
        g.fill(ix, dotY, ix + 4, dotY + 4, statusColor(order.status()));

        String statusShort = statusShort(order.status());
        int statusW = font.width(statusShort);
        int statusX = right - statusW;

        String amount = "x" + order.amount();
        int amountW = font.width(amount);
        int amountX = statusX - 6 - amountW;

        int nameX = ix + 8;
        int nameMax = amountX - 4 - nameX;
        String name = trim(font, order.itemName(), nameMax);
        g.text(font, Component.literal(name), nameX, ly,
                order.type() == BazaarOrderType.BUY ? BUY_COLOR : SELL_COLOR);
        g.text(font, Component.literal(amount), amountX, ly, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(statusShort), statusX, ly, statusColor(order.status()));
    }

    /** Orders sorted so the ones needing action come first: undercut, then matched, best, filled. */
    /**
     * The orders in the order they are shown, re-sorted at most every {@link #SORT_INTERVAL_MS}.
     *
     * <p>It used to copy and sort the whole list on every frame the panel was up. An order's status
     * changes on the network's cadence, not the frame's, so a quarter of a second late is invisible
     * - and this is the Bazaar, the screen where the mod can least afford per-frame allocation.
     */
    private List<BazaarOrder> sortedOrders() {
        long now = System.currentTimeMillis();
        if (now - sortedAt < SORT_INTERVAL_MS) {
            return sorted;
        }
        sortedAt = now;
        List<BazaarOrder> orders = new ArrayList<>(BazaarOrderTracker.getInstance().getOrders());
        orders.sort(Comparator
                .comparingInt((BazaarOrder o) -> priority(o.status()))
                .thenComparing(o -> o.type())
                .thenComparing(BazaarOrder::itemName));
        sorted = orders;
        return sorted;
    }

    private static int priority(BazaarStatus status) {
        return switch (status) {
            case OUTDATED -> 0;
            case MATCHED -> 1;
            case BEST_OFFER -> 2;
            case UNKNOWN -> 3;
            case FILLED -> 4;
        };
    }

    private static int statusColor(BazaarStatus status) {
        return switch (status) {
            case BEST_OFFER -> SBSTheme.BAZAAR_BEST_FRAME;
            case MATCHED -> SBSTheme.BAZAAR_MATCHED_FRAME;
            case OUTDATED -> SBSTheme.BAZAAR_OUTDATED_FRAME;
            case FILLED -> SBSTheme.HUD_MANA;
            case UNKNOWN -> SBSTheme.TEXT_MUTED;
        };
    }

    private static String statusShort(BazaarStatus status) {
        return switch (status) {
            case BEST_OFFER -> "Best";
            case MATCHED -> "Match";
            case OUTDATED -> "Undercut";
            case FILLED -> "Filled";
            case UNKNOWN -> "…";
        };
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("…")), false) + "…";
    }
}
