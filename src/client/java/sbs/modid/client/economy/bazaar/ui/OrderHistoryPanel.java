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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderHistory;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.economy.bazaar.logic.BazaarReorder;
import sbs.modid.client.economy.bazaar.model.CancelledOrder;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The <b>Order History</b> panel: the unfilled remainder of every buy order you cancelled, each one
 * a click away from being re-placed.
 *
 * <p>Drawn beside the Bazaar menu from {@code OverlayRenderMixin}, opposite
 * {@link ManageOrdersPanel} - that one is your live orders, this one is what you gave up on. Rows
 * come from the profile-scoped {@link BazaarOrderHistory}, so nothing here is container-derived and
 * the list is the same whichever Bazaar screen you are standing in.
 *
 * <p><b>No quantity is printed as plain text.</b> Each row carries a two-part bar instead: the
 * portion that filled before you cancelled, and the portion still owed. That is the comparison the
 * row exists to support - "how much of this did I actually get" - and a bar answers it at a glance
 * where a pair of numbers has to be read and divided. The exact figures are one hover away in the
 * tooltip, so nothing is lost; they are simply not what the row shouts. {@code orderHistoryNumbers}
 * puts the plain count back for anyone who would rather read it directly.
 *
 * <p><b>Clicking a row re-orders it</b> through {@link BazaarReorder}: the item's Bazaar page opens
 * and the remembered quantity is held ready for the Custom Amount sign. The row's X forgets it
 * instead. A remainder too large for a single Bazaar order cannot be re-placed in one go, so its bar
 * is drawn in the warning colour and the click is refused with a reason rather than arming a
 * quantity the sign would reject.
 */
public final class OrderHistoryPanel {

    private static final OrderHistoryPanel INSTANCE = new OrderHistoryPanel();

    private static final int PREFERRED_W = 176;
    /** Below this the item name has no room left at all, so the panel is not drawn. */
    private static final int MIN_W = 120;
    private static final int PAD = 6;
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 2;
    private static final int MAX_ROWS = 8;
    private static final int GAP_TO_CONTAINER = 8;
    private static final int FORGET_W = 12;
    private static final int ICON_W = 18;
    /** The bar's own width. Fixed, so a column of rows reads as one comparable chart. */
    private static final int BAR_W = 46;
    private static final int BAR_H = 5;

    private static final int FORGET_COLOR = 0xFFFF4040;
    /** The share that filled before the cancel - coins already spent. */
    private static final int BAR_FILLED = SBSTheme.BAZAAR_BEST_FRAME;
    /** The share still owed - what a click re-orders. */
    private static final int BAR_REMAINING = SBSTheme.HUD_MANA;

    /** One clickable row of the drawn panel: where it is, what it holds, and what a click does. */
    private record Row(int x, int y, int width, int height, CancelledOrder order, boolean forget) {

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    /** The rows drawn last frame, in draw order - the panel's hit-test map. */
    private volatile List<Row> rows = List.of();

    private OrderHistoryPanel() {
    }

    public static OrderHistoryPanel getInstance() {
        return INSTANCE;
    }

    private static boolean showPanel() {
        return BazaarOrderHistory.enabled()
                && ConfigManager.getInstance().get().bazaar.orderHistoryPanel;
    }

    /** Whether the exact counts are printed on the rows as well as drawn as a bar. */
    private static boolean showNumbers() {
        return ConfigManager.getInstance().get().bazaar.orderHistoryNumbers;
    }

    // ------------------------------------------------------------------ render

    /** Called for every container screen from the overlay render hook. */
    public void render(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                       int mouseX, int mouseY) {
        if (!showPanel() || !onBazaarScreen(container)) {
            rows = List.of();
            return;
        }
        List<CancelledOrder> entries = BazaarOrderHistory.getInstance().entries();
        if (entries.isEmpty()) {
            // Unlike Manage Orders, an empty history says nothing: it is the normal state for anyone
            // who has not cancelled a partly filled order, and a permanent "nothing here" panel is
            // clutter beside a menu that already has plenty.
            rows = List.of();
            return;
        }

        Font font = Minecraft.getInstance().font;
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) container;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        int imageW = bounds.skyblockSimplified$imageWidth();

        // The available space is the ceiling, never the floor: on a viewport with no room for a
        // readable panel this draws nothing rather than hanging off the edge.
        int rightRoom = g.guiWidth() - (left + imageW + GAP_TO_CONTAINER) - PAD;
        int leftRoom = left - GAP_TO_CONTAINER - PAD;
        // Right by default - Manage Orders takes the left side of the same menu. When that panel has
        // itself been pushed right for want of room, this one yields and goes left, so the two never
        // draw into the same pixels.
        boolean preferLeft = ManageOrdersPanel.occupiesRightSide(container);
        int panelW;
        int x;
        if (!preferLeft && rightRoom >= MIN_W) {
            panelW = Math.min(PREFERRED_W, rightRoom);
            x = left + imageW + GAP_TO_CONTAINER;
        } else if (leftRoom >= MIN_W) {
            panelW = Math.min(PREFERRED_W, leftRoom);
            x = left - panelW - GAP_TO_CONTAINER;
        } else if (rightRoom >= MIN_W) {
            panelW = Math.min(PREFERRED_W, rightRoom);
            x = left + imageW + GAP_TO_CONTAINER;
        } else {
            rows = List.of();
            return;
        }

        int shown = Math.min(entries.size(), MAX_ROWS);
        int lineH = ROW_H + ROW_GAP;
        int height = PAD * 2 + font.lineHeight + ROW_GAP
                + shown * lineH
                + (entries.size() > shown ? font.lineHeight : 0);
        int y = Math.max(PAD, Math.min(top, g.guiHeight() - height - PAD));

        HudCard.draw(g, x, y, panelW, height, SBSTheme.PANEL_CORNER);

        int ix = x + PAD;
        int right = x + panelW - PAD;
        int iy = y + PAD;

        String header = "Order History";
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        String count = Integer.toString(entries.size());
        int countX = right - font.width(count);
        // Only when it clears the header - two strings anchored to opposite edges of a narrow panel
        // otherwise meet in the middle.
        if (countX > ix + font.width(header) + 4) {
            g.text(font, Component.literal(count), countX, iy, SBSTheme.TEXT_MUTED);
        }
        iy += font.lineHeight + ROW_GAP;

        List<Row> laid = new ArrayList<>(shown * 2);
        for (int i = 0; i < shown; i++) {
            drawRow(g, font, entries.get(i), ix, right - ix, iy, mouseX, mouseY, laid);
            iy += lineH;
        }
        if (entries.size() > shown) {
            g.text(font, Component.literal("+" + (entries.size() - shown) + " more"), ix, iy,
                    SBSTheme.TEXT_MUTED);
        }
        rows = List.copyOf(laid);
    }

    /**
     * One row: icon, item name, the fill bar, and an X to forget it.
     *
     * <p>Laid out right-to-left from the fixed parts, so the name - the only elastic element - gets
     * whatever is left and is ellipsised into it. Every box is appended to {@code laid} exactly as it
     * is drawn, so the click handler hit-tests what is on screen rather than recomputing the layout
     * and hoping the two agree.
     */
    private void drawRow(GuiGraphicsExtractor g, Font font, CancelledOrder order,
                         int ix, int rowW, int iy, int mouseX, int mouseY, List<Row> laid) {
        int entryW = rowW - FORGET_W - ROW_GAP;
        boolean overRow = mouseX >= ix && mouseX < ix + entryW && mouseY >= iy && mouseY < iy + ROW_H;
        boolean placeable = order.withinOrderLimit();

        SciFiRender.roundedRect(g, ix, iy, entryW, ROW_H, SBSTheme.CORNER_RADIUS,
                overRow ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);

        String material = materialOf(order.itemId());
        g.item(SkyBlockItemIcons.getInstance().iconShared(order.itemId(), material, 1), ix + 1, iy + 1);

        // The bar sits hard against the row's right edge; the count, when the player asked for it,
        // goes between the bar and the name.
        int barX = ix + entryW - BAR_W - 3;
        int barY = iy + (ROW_H - BAR_H) / 2;
        drawBar(g, order, barX, barY, placeable);

        int textRight = barX - 3;
        if (showNumbers()) {
            String amount = "x" + order.remaining();
            int amountW = font.width(amount);
            if (textRight - amountW > ix + ICON_W + 4) {
                g.text(font, Component.literal(amount), textRight - amountW,
                        iy + (ROW_H - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
                textRight -= amountW + 4;
            }
        }

        int nameX = ix + ICON_W;
        int nameMax = textRight - nameX;
        if (nameMax > 0) {
            String name = trim(font, order.itemName(), nameMax);
            g.text(font, Component.literal(name), nameX, iy + (ROW_H - font.lineHeight) / 2,
                    overRow ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
        }
        laid.add(new Row(ix, iy, entryW, ROW_H, order, false));

        // The X is its own hit box, so dismissing a row never also re-orders it.
        int fx = ix + entryW + ROW_GAP;
        boolean overForget = mouseX >= fx && mouseX < fx + FORGET_W
                && mouseY >= iy && mouseY < iy + ROW_H;
        SciFiRender.roundedRect(g, fx, iy, FORGET_W, ROW_H, SBSTheme.CORNER_RADIUS,
                overForget ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
        String cross = "x";
        g.text(font, Component.literal(cross), fx + (FORGET_W - font.width(cross)) / 2,
                iy + (ROW_H - font.lineHeight) / 2, FORGET_COLOR);
        laid.add(new Row(fx, iy, FORGET_W, ROW_H, order, true));

        if (overRow) {
            g.setTooltipForNextFrame(font, tooltip(order, placeable), Optional.empty(),
                    mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    /**
     * The two-part bar that stands in for the quantities: filled behind, still-owed in front, over a
     * dim track that is the whole original order.
     *
     * <p>An order that filled nothing draws as a full remaining bar, which is the truthful picture -
     * all of it is still owed. A remainder the Bazaar will not take in one order is drawn in the
     * warning colour, so the row that cannot be clicked looks different before it is clicked.
     */
    private static void drawBar(GuiGraphicsExtractor g, CancelledOrder order, int x, int y,
                                boolean placeable) {
        g.fill(x, y, x + BAR_W, y + BAR_H, SBSTheme.CARD_BG_DISABLED);
        int filledW = Math.round(BAR_W * order.filledFraction());
        if (filledW > 0) {
            g.fill(x, y, x + filledW, y + BAR_H, BAR_FILLED);
        }
        if (filledW < BAR_W) {
            g.fill(x + filledW, y, x + BAR_W, y + BAR_H, placeable ? BAR_REMAINING : SBSTheme.WARN);
        }
    }

    /** The exact figures the row deliberately does not print. */
    private static List<Component> tooltip(CancelledOrder order, boolean placeable) {
        List<Component> tip = new ArrayList<>(6);
        tip.add(Component.literal(order.itemName()));
        tip.add(Component.literal("Still owed: " + order.remaining()
                + (order.ordered() > 0 ? " of " + order.ordered() + " ordered" : "")));
        if (order.ordered() > 0) {
            tip.add(Component.literal("Filled before cancel: " + order.filled()));
        }
        tip.add(Component.literal("Your price: " + FlipFormat.coins(order.price()) + " each"));
        tip.add(Component.literal("Cancelled " + FlipFormat.duration(
                Math.max(0, System.currentTimeMillis() - order.cancelledAt()) / 1000L) + " ago"));
        if (placeable) {
            tip.add(Component.literal("Click to re-order the remainder - price left to you"));
        } else {
            tip.add(Component.literal("Too large for one Bazaar order (max "
                    + CancelledOrder.MAX_ORDER_UNITS + ")"));
        }
        return tip;
    }

    // ------------------------------------------------------------------ input

    /**
     * Routes one click onto the panel, returning {@code true} when a row took it so the caller
     * swallows it instead of letting it reach the container behind.
     */
    public boolean handleClick(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (!showPanel() || !onBazaarScreen(container)) {
            return false;
        }
        for (Row row : rows) {
            if (!row.contains(event.x(), event.y())) {
                continue;
            }
            if (row.forget()) {
                BazaarOrderHistory.getInstance().forget(row.order().key());
                return true;
            }
            if (BazaarReorder.getInstance().start(row.order())) {
                // The row stays until the re-order is actually set up - BazaarOrderTracker clears it
                // from the "Setup!" chat line, so backing out of the flow does not lose it.
                return true;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal(
                        "[SBS] " + row.order().itemName() + " cannot be re-ordered in one go: "
                                + row.order().remaining() + " is over the Bazaar's per-order limit of "
                                + CancelledOrder.MAX_ORDER_UNITS + "."));
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    /** The Bazaar screens the panel belongs beside - everywhere except the orders menu itself. */
    private static boolean onBazaarScreen(AbstractContainerScreen<?> container) {
        if (container == null) {
            return false;
        }
        String title = MenuFrame.of(container).normalised();
        return BazaarOrderTracker.isBazaarGui(title) && !BazaarOrderTracker.isOrdersMenu(title);
    }

    /** The vanilla material to draw an item id with, or {@code null} to let the icon cache decide. */
    private static String materialOf(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        return entry != null ? entry.material : null;
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }
}
