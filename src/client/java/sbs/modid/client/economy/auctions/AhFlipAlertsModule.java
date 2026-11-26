/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.auctions.logic.AhFlipClient;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;
import java.util.Locale;

/**
 * The AH Flip Alerts module, registering itself (see {@link SbsModule}).
 *
 * <p>Toggling it on lets {@link AhFlipClient} follow the SBS flip server's live stream
 * ({@code /flips/stream} + {@code /flips/liste}): new underpriced auctions arrive as popup cards and
 * chat lines with a click-to-open link, and leave the moment the server reports them gone.
 *
 * <p>The price range is the user's own capital band: alerts only arrive for auctions whose total
 * price falls inside it. The lower bound can never go below the server's 5m floor.
 */
public final class AhFlipAlertsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor.. */
    public AhFlipAlertsModule() {
    }

    @Override
    public String id() {
        return "ah_flip_alerts";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "AH Flip Alerts";
    }

    @Override
    public String description() {
        return "Chat alerts for underpriced auctions - price, profit, click to open";
    }

    @Override
    public int accentColor() {
        return 0xFFFFC94D;
    }

    private static SBSConfig.AhFlipAlertSettings cfg() {
        return ConfigManager.getInstance().get().ahFlips;
    }

    private static SBSConfig.LocalFlipSettings flipFinder() {
        return ConfigManager.getInstance().get().dev.localFlips;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return java.util.List.of(
                SettingRow.toggle("Middle Click to Search", () -> cfg().middleClickSearch,
                        () -> { cfg().middleClickSearch = !cfg().middleClickSearch; save(); })
                        .describe("In the Auction House, middle click any item to search the "
                                + "auctions for it by name."),
                SettingRow.toggle("AH Flip Alerts", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Tells you when an auction is listed well under its market "
                                + "value - buy it, resell it, keep the difference. Needs your SBS "
                                + "licence token.")
                        .licenced()
                        .inDevelopment(),
                SettingRow.segmented("Flip Source", List.of("Server", "Local"),
                        () -> sbs.modid.client.economy.auctions.logic.FlipServer.isLocal() ? 1 : 0,
                        index -> sbs.modid.client.core.api.RankingSource.choose(
                                cfg().flipSource, index == 1))
                        .describe("Where flips come from. Server is the SBS flip server - it watches "
                                + "the whole auction house with sale history and needs your licence "
                                + "token. Local is the flip program running on this PC, for "
                                + "development only."),
                SettingRow.label("§7Local = dev only (flip_overlay.py on this PC)"),
                SettingRow.label("Needs an active licence token"),
                SettingRow.toggle("Popup Alerts", () -> cfg().popupAlerts,
                        () -> { cfg().popupAlerts = !cfg().popupAlerts; save(); })
                        .describe("Each flip as a popup card: item, price, expected profit. Click "
                                + "the card to open the auction, the ✕ to dismiss it."),
                SettingRow.label("Cards stay while a menu is open - click = open, ✕ = dismiss"),
                SettingRow.button("Move / Resize Popups", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.AH_FLIP_POPUPS}, "Edit Flip Popups")))
                        .describe("Opens the editor where you drag the popup stack anywhere on "
                                + "the screen and scale it."),
                SettingRow.rangeSlider("Popup Duration", 15, 300,
                        () -> cfg().popupSeconds,
                        value -> { cfg().popupSeconds = value; save(); }, "s")
                        .describe("How many seconds a popup card stays before fading. Flips can "
                                + "sell within seconds, so old alerts stop being actionable "
                                + "fast."),
                SettingRow.toggle("Chat Alerts", () -> cfg().chatAlerts,
                        () -> { cfg().chatAlerts = !cfg().chatAlerts; save(); })
                        .describe("Each flip also as a clickable chat line - it stays in the chat "
                                + "history, unlike a popup."),
                SettingRow.toggle("Flips Window (Inventory & AH)", () -> cfg().windowOverlay,
                        () -> { cfg().windowOverlay = !cfg().windowOverlay; save(); })
                        .describe("A window with the current best flips beside your inventory and "
                                + "the Auction House, so you can browse them while already in the "
                                + "menu."),
                SettingRow.text("Min Price", "5m", 12,
                        () -> fmt(cfg().minPrice),
                        text -> { cfg().minPrice = Math.max(parseCoins(text), 5_000_000L); save(); })
                        .describe("Ignore flips cheaper than this (shorthand like 5m or 800k "
                                + "works). 5m is the server-side minimum - cheap flips are not "
                                + "worth the alert."),
                SettingRow.text("Max Price (empty = no limit)", "no limit", 12,
                        () -> cfg().maxPrice > 0 ? fmt(cfg().maxPrice) : "",
                        text -> { cfg().maxPrice = parseCoins(text); save(); })
                        .describe("Ignore flips costing more than this - cap it at what you can "
                                + "actually afford to buy. Empty = no limit."),
                SettingRow.label("Range is the auction's total price - 5m is the server minimum"),
                SettingRow.label("— Flip Finder window —"),
                SettingRow.toggle("Flip Finder", () -> flipFinder().enabled,
                                () -> { flipFinder().enabled = !flipFinder().enabled; save(); })
                        .describe("Every find from the flip server in a movable window: SOFORT finds, "
                                + "the flip list, bids, price ladder, confidence and bot share. It "
                                + "stays open beside the Auction House and the other SBS windows. "
                                + "Needs your licence token. Default: off.")
                        .licenced(),
                SettingRow.button("Open Flip Finder Window",
                                sbs.modid.client.economy.localflips.ui.LocalFlipsWindow::show)
                        .describe("Opens the window (and switches the Flip Finder on). Drag the header, "
                                + "drag an edge to resize, Ctrl+scroll to scale."),
                SettingRow.keybind("Open Flip Window Key", () -> flipFinder().openOverlayKey,
                                key -> { flipFinder().openOverlayKey = key; save(); })
                        .describe("A key that opens the flip window. Default: unbound."),
                SettingRow.keybind("Open Newest Find Key", () -> flipFinder().openNewestKey,
                                key -> { flipFinder().openNewestKey = key; save(); })
                        .describe("A key that opens the auction of the newest SOFORT find that is still "
                                + "valid - one /viewauction per press. Default: unbound."),
                SettingRow.toggle("Flip Chat Alerts", () -> flipFinder().chatAlerts,
                                () -> { flipFinder().chatAlerts = !flipFinder().chatAlerts; save(); })
                        .describe("One chat line per new SOFORT find with [OPEN] and [COPY]. Each auction "
                                + "once, also across reconnects. Default: on."),
                SettingRow.soundToggle("Flip Alert Sound", () -> flipFinder().sound,
                                () -> { flipFinder().sound = !flipFinder().sound; save(); })
                        .describe("A short sound with each alert. Default: on."),
                SettingRow.toggle("Flip Alert Toast", () -> flipFinder().toast,
                                () -> { flipFinder().toast = !flipFinder().toast; save(); })
                        .describe("A toast in the corner with each alert. Default: off."),
                SettingRow.intField("Alert Min Profit", 0, 2_000_000_000, () -> flipFinder().minProfit,
                                value -> { flipFinder().minProfit = value; save(); }, "coins")
                        .describe("Finds below this net profit are not alerted, and are drawn greyed in "
                                + "the window. Default: 500000."),
                SettingRow.rangeSlider("Alert Min Safety", 0, 100, () -> flipFinder().minSafety,
                                value -> { flipFinder().minSafety = value; save(); }, "%")
                        .describe("Finds below this safety are not alerted. Bids carry no safety and are "
                                + "never held back by it. Default: 0 %."),
                SettingRow.toggle("Show Flip List", () -> flipFinder().showList,
                                () -> { flipFinder().showList = !flipFinder().showList; save(); })
                        .describe("The program's flip list below the SOFORT finds. Default: on."),
                SettingRow.toggle("Show Bid Auctions", () -> flipFinder().showBids,
                                () -> { flipFinder().showBids = !flipFinder().showBids; save(); })
                        .describe("Bid auctions in the flip list, with the most worth bidding. Default: on."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }

    /** Parses "50m" / "1.5b" / "800k" / plain coins; empty or unparsable = 0. */
    private static long parseCoins(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(",", ".");
        if (t.isEmpty()) {
            return 0;
        }
        double mult = 1;
        if (t.endsWith("b")) {
            mult = 1_000_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Math.max(0, Math.round(Double.parseDouble(t) * mult));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Formats coins the same way the input parses them ("5M", "1.5B") - the modulo tests keep it
     * lossless, so what the box shows is exactly what it holds. Not
     * {@link sbs.modid.client.core.util.NumberDisplay} for that reason; the upper case is only to
     * match it, and {@link #parseCoins} lower-cases before reading anyway.
     */
    private static String fmt(long value) {
        if (value >= 1_000_000_000L && value % 100_000_000L == 0) {
            return trim(value / 1_000_000_000.0) + "B";
        }
        if (value >= 1_000_000L && value % 100_000L == 0) {
            return trim(value / 1_000_000.0) + "M";
        }
        if (value >= 1_000L && value % 100L == 0) {
            return trim(value / 1_000.0) + "K";
        }
        return String.valueOf(value);
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
