/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.ui;

import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.BazaarOrderType;
import sbs.modid.client.economy.bazaar.model.BazaarStatus;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything {@link ManageOrdersPanel} draws, derived once per change of the order list or of any
 * order's status: the sorted rows with their strings, colours and x offsets, and the header tag.
 * The panel only issues the draw calls.
 *
 * <p>Pure: text measuring comes in through {@link Measure}, so the build is unit-testable without a
 * Minecraft font.
 *
 * @param rows        the orders in display order (needing attention first), at most {@link #maxRows}
 * @param orderCount  how many orders are tracked in total (for the "+N more" line and the count)
 * @param tag         the header tag: "N undercut" or the order count
 * @param tagX        the tag's x offset from the panel's inner left edge
 * @param tagColor    the tag's colour
 */
record ManageOrdersModel(List<Row> rows, int orderCount, String tag, int tagX, int tagColor) {

    /** Text measuring, as {@link net.minecraft.client.gui.Font} provides it. */
    interface Measure {
        int width(String text);

        /** The longest prefix of {@code text} no wider than {@code maxWidth}. */
        String prefixByWidth(String text, int maxWidth);
    }

    /**
     * One order row. Offsets are relative to the panel's inner left edge.
     *
     * @param statusColor the dot's and the status word's colour
     */
    record Row(int statusColor, String name, int nameX, int nameColor,
               String amount, int amountX, String status, int statusX) {
    }

    static final int BUY_COLOR = 0xFF55C7F5;
    static final int SELL_COLOR = 0xFFE0A14D;

    /** Orders needing action first: undercut, then matched, best, unknown, filled. */
    private static final Comparator<BazaarOrder> DISPLAY_ORDER = Comparator
            .comparingInt((BazaarOrder o) -> priority(o.status()))
            .thenComparing(BazaarOrder::type)
            .thenComparing(BazaarOrder::itemName);

    /**
     * Builds the model for {@code orders}.
     *
     * @param innerWidth the panel's width minus its padding on both sides
     * @param maxRows    the most rows any caller shows; rows past it are only counted
     */
    static ManageOrdersModel build(List<BazaarOrder> orders, Measure measure, int innerWidth, int maxRows) {
        List<BazaarOrder> sorted = new ArrayList<>(orders);
        sorted.sort(DISPLAY_ORDER);

        int undercut = 0;
        for (BazaarOrder o : sorted) {
            if (o.status() == BazaarStatus.OUTDATED) {
                undercut++;
            }
        }
        String tag = undercut > 0 ? undercut + " undercut" : sorted.size() + "";
        int tagColor = undercut > 0 ? SBSTheme.BAZAAR_OUTDATED_FRAME : SBSTheme.TEXT_MUTED;

        int shown = Math.min(sorted.size(), maxRows);
        List<Row> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            rows.add(row(sorted.get(i), measure, innerWidth));
        }
        return new ManageOrdersModel(List.copyOf(rows), sorted.size(), tag,
                innerWidth - measure.width(tag), tagColor);
    }

    /** One order row: [status dot] name (buy/sell tinted) … xAmount status. */
    private static Row row(BazaarOrder order, Measure measure, int right) {
        String status = statusShort(order.status());
        int statusX = right - measure.width(status);

        String amount = "x" + order.amount();
        int amountX = statusX - 6 - measure.width(amount);

        int nameX = 8;
        String name = trim(measure, order.itemName(), amountX - 4 - nameX);
        return new Row(statusColor(order.status()), name, nameX,
                order.type() == BazaarOrderType.BUY ? BUY_COLOR : SELL_COLOR,
                amount, amountX, status, statusX);
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

    private static String trim(Measure measure, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (measure.width(text) <= maxWidth) {
            return text;
        }
        return measure.prefixByWidth(text, Math.max(1, maxWidth - measure.width("…"))) + "…";
    }
}
