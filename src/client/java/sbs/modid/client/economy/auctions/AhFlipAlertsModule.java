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
 * <p>Toggling it on lets {@link AhFlipClient} long-poll the SBS flip server: every AH crawl the
 * server appraises all live BIN auctions from 5m upwards with the same value model as the
 * Similar Auctions window and pushes the underpriced ones (10%+ below their real value, ranked by
 * profit x discount x weekly sales) to every subscribed player as a chat line with a
 * click-to-open link!
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

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("AH Flip Alerts", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Tells you when an auction is listed well under its market "
                                + "value - buy it, resell it, keep the difference. Needs your SBS "
                                + "licence token.")
                        .licenced()
                        .inDevelopment(),
                SettingRow.label(sbs.modid.client.ui.render.LocalRankingNotice.settingsNote()),
                SettingRow.segmented("Flip Source", List.of("Server", "Local"),
                        () -> cfg().flipSource.preferLocal ? 1 : 0,
                        index -> sbs.modid.client.core.api.RankingSource.choose(
                                cfg().flipSource, index == 1))
                        .describe("Where flips come from. Server watches the whole auction house "
                                + "for us and needs a licence token. Local scans the live auctions "
                                + "your own client already downloads and compares each item's "
                                + "cheapest listing against the next cheapest - no sale history, so "
                                + "it can only tell you something is cheap for its own market right "
                                + "now, not that it is worth more than that."),
                SettingRow.segmented("Similar Auctions Source", List.of("Server", "Local"),
                        () -> cfg().similarSource.preferLocal ? 1 : 0,
                        index -> sbs.modid.client.core.api.RankingSource.choose(
                                cfg().similarSource, index == 1))
                        .describe("What the Similar Auctions window shows. Server appraises your "
                                + "exact item and lists what comparable ones sold for. Local lists "
                                + "what is on sale right now under that name and nothing else - no "
                                + "value, no sale history, no matching of your item's stars and "
                                + "books against a listing's."),
                SettingRow.intField("Local Min Discount", 1, 95,
                        () -> cfg().localMinDiscountPct,
                        value -> { cfg().localMinDiscountPct = value; save(); }, "%")
                        .describe("How far under the next-cheapest listing something must sit "
                                + "before the local scan calls it a flip. This one threshold is the "
                                + "whole of its judgement, so setting it low turns ordinary price "
                                + "spread into alerts."),
                SettingRow.intField("Local Min Listings", 2, 100,
                        () -> cfg().localMinListings,
                        value -> { cfg().localMinListings = value; save(); }, "")
                        .describe("How many of an item must be on sale before the local scan will "
                                + "judge it. Two listings are two opinions, not a market, and the "
                                + "gap between them says nothing about what the item is worth."),
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
                SettingRow.label("Range is the auction's total price - 5m is the server minimum"));
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
