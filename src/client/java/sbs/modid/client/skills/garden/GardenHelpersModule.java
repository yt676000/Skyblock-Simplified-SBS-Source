/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.logic.VisitorGuard;
import sbs.modid.client.skills.garden.ui.ComposterOverlay;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Garden Helpers module (Skills): the visitor refuse-guard (valuable rewards need Ctrl+Shift to
 * decline) and the Composter status card. Logic lives in {@link VisitorGuard} and
 * {@link ComposterOverlay}; this class only registers the module and its settings rows.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class GardenHelpersModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GardenHelpersModule() {
    }

    @Override
    public String id() {
        return "garden_helpers";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FARMING_GARDEN;
    }

    @Override
    public String displayName() {
        return "Garden Helpers";
    }

    @Override
    public String description() {
        return "Visitor refuse-guard for rare rewards + a Composter status card";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new java.util.ArrayList<>(List.of(
                SettingRow.toggle("Visitor Refuse-Guard", () -> cfg().visitorGuard,
                        () -> { cfg().visitorGuard = !cfg().visitorGuard; save(); })
                        .describe("Blocks the Refuse button on a Garden visitor whose offer "
                                + "includes a rare reward (Overgrown Grass, Visitor's Gratitude), "
                                + "unless you hold Ctrl+Shift. Protects against refusing a "
                                + "jackpot by reflex."),
                SettingRow.label("Refusing an offer with Overgrown Grass / Visitor's Gratitude"),
                SettingRow.label("only works while holding Ctrl+Shift"),

                SettingRow.toggle("Highlight Valuable Visitors", () -> cfg().visitorHighlight,
                        () -> { cfg().visitorHighlight = !cfg().visitorHighlight; save(); })
                        .describe("On the Garden, a visitor whose offer includes a rare reward (the "
                                + "same list the Refuse-Guard uses) glows gold with \"★ Name · reward\" "
                                + "above them. Only offers you have opened are known - a reward is "
                                + "never guessed. Drawing only; nothing is clicked. Default: on."),
                SettingRow.toggle("Mark Unchecked Visitors", () -> cfg().visitorHighlightUnknown,
                        () -> { cfg().visitorHighlightUnknown = !cfg().visitorHighlightUnknown; save(); })
                        .describe("A small grey \"? Name · open to check\" over a visitor whose menu "
                                + "you have not opened yet, so you know whose offer is still unknown. "
                                + "Default: on."),
                SettingRow.toggle("Highlight Visitors By Rarity", () -> cfg().visitorHighlightRarity,
                        () -> { cfg().visitorHighlightRarity = !cfg().visitorHighlightRarity; save(); })
                        .describe("A separate outline on Legendary, Mythic and Special visitors, read "
                                + "from the colour of their name. Independent of their reward. "
                                + "Default: off."),
                SettingRow.color("Visitor Highlight Colour", () -> cfg().visitorHighlightColorHex,
                        () -> 0xFFFFB300, () -> openPicker())
                        .describe("The colour of the valuable-visitor glow and label. Clear it in "
                                + "the picker to go back to gold."),
                SettingRow.toggle("Greenhouse Capture", () -> cfg().greenhouseCapture,
                        () -> { cfg().greenhouseCapture = !cfg().greenhouseCapture; save(); })
                        .describe("Research only: while on the Garden, writes every greenhouse or "
                                + "mutation chat line, greenhouse tab row, menu title and zone change "
                                + "to the game log, so Greenhouse helpers can be built on what the "
                                + "game really shows. Also logs the Crop Analyzer frame by frame "
                                + "wherever you open it, with the chat around it. Shows nothing and "
                                + "clicks nothing. Default: off."),

                SettingRow.toggle("Visitor Bazaar Buttons", () -> cfg().visitorBazaarButtons,
                        () -> { cfg().visitorBazaarButtons = !cfg().visitorBazaarButtons; save(); })
                        .describe("Beside a Garden visitor's menu, one button per item the visitor "
                                + "wants (\"Bazaar: Enchanted Hay Bale ×2\"). Clicking one opens that "
                                + "item on the Bazaar with /bz - one command, only when you click, "
                                + "nothing clicked for you after that. If the Bazaar refuses because "
                                + "you have no Cookie Buff, a chat line says so. Default: on."),
                SettingRow.toggle("Show Cost On Visitor Buttons", () -> cfg().visitorBazaarPrice,
                        () -> { cfg().visitorBazaarPrice = !cfg().visitorBazaarPrice; save(); })
                        .describe("Adds what buying the amount would cost right now (\"≈ 1.2M\", "
                                + "insta-buy) to each visitor button, from the prices the mod "
                                + "already has - no extra request. Nothing is shown when an item "
                                + "has no price. Default: on."),
                SettingRow.toggle("Visitor Shopping List", () -> cfg().visitorShoppingList,
                        () -> { cfg().visitorShoppingList = !cfg().visitorShoppingList; save(); })
                        .describe("Remembers what each waiting visitor wants once you open their "
                                + "menu, and adds it all up: per item how many you need, how many "
                                + "you carry and what the rest costs on the Bazaar, and per visitor "
                                + "the coins per copper. A visitor whose menu you never opened says "
                                + "so instead of being guessed. /sbs visitors prints it in chat with "
                                + "each item a /bz link. Counts your inventory only - sacks are not "
                                + "read yet. Default: on."),
                SettingRow.toggle("Visitor Shopping Card", () -> cfg().visitorShoppingHud,
                        () -> { cfg().visitorShoppingHud = !cfg().visitorShoppingHud; save(); })
                        .describe("Shows the shopping list as a movable card while you are on the "
                                + "Garden. Off, the list is still kept and /sbs visitors still "
                                + "works. Default: on."),

                SettingRow.toggle("Visitor Timer", () -> cfg().visitorTimer,
                        () -> { cfg().visitorTimer = !cfg().visitorTimer; save(); })
                        .describe("A card on the Garden with how many visitors are waiting and when "
                                + "the next one arrives, or \"Queue full\" when no more can come. "
                                + "Read from the Visitors tab widget, which must be on (/widgets)."),
                SettingRow.button("Move / Resize Visitor Timer", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.VISITOR_TIMER}, "Edit Visitor Timer")))
                        .describe("Opens the editor where you drag the visitor timer anywhere on "
                                + "the screen and scale it."),
                SettingRow.label("— Visitor queue full —"),
                SettingRow.label("— New visitor arrived —"),

                SettingRow.toggle("Composter Card", () -> cfg().composterOverlay,
                        () -> { cfg().composterOverlay = !cfg().composterOverlay; save(); })
                        .describe("A card with your Composter's organic matter, fuel and finished "
                                + "compost - so you see it running low without walking over. Open "
                                + "the Composter once to refresh the numbers."),
                SettingRow.label("Matter / fuel / compost - refreshed by opening the Composter"),
                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.COMPOSTER}, "Edit Composter Card")))
                        .describe("Opens the editor where you drag the composter card anywhere on "
                                + "the screen and scale it."),

                SettingRow.toggle("Farming Tracker", () -> cfg().farmingTracker,
                        () -> { cfg().farmingTracker = !cfg().farmingTracker; save(); })
                        .describe("A card with your farming numbers while on the Garden: farming "
                                + "fortune, Overbloom, pest chance and the coins per hour of the "
                                + "crop you are farming."),
                SettingRow.label("Fortune / Overbloom / pest chance (tab widgets) + crop profit"),
                SettingRow.label("Profit needs the Collection widget on (/widgets), priced instasell"),
                SettingRow.button("Move / Resize Tracker", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.FARMING_TRACKER}, "Edit Farming Tracker")))
                        .describe("Opens the editor where you drag the farming card anywhere on "
                                + "the screen and scale it."),

                SettingRow.toggle("Pest Profit", () -> cfg().pestProfit,
                        () -> { cfg().pestProfit = !cfg().pestProfit; save(); })
                        .describe("A card on the Garden once you kill a pest: pests killed this "
                                + "session, what they dropped and what it is worth at the Bazaar "
                                + "(instasell), per hour and per pest, plus your running total on "
                                + "this profile. A \"+\" means some drop has no price. Kills are "
                                + "read from the Pests tab widget; the drop and kill chat lines have "
                                + "not been checked in game yet, so the numbers may be incomplete."),
                SettingRow.toggle("Per-Pest Breakdown", () -> cfg().pestProfitBreakdown,
                        () -> { cfg().pestProfitBreakdown = !cfg().pestProfitBreakdown; save(); })
                        .describe("Adds how many of each pest you killed to the card. A kill the chat "
                                + "did not name shows as Unknown."),
                SettingRow.toggle("Include Pest Trap loot", () -> cfg().pestProfitTrapLoot,
                        () -> { cfg().pestProfitTrapLoot = !cfg().pestProfitTrapLoot; save(); })
                        .describe("Counts what your Pest Traps pay out when you empty them in the "
                                + "card's value and per-hour figure. Either way the card shows a "
                                + "\"from traps\" line with that loot on its own. Trap collection "
                                + "has not been checked in game yet, so this is an estimate."),
                SettingRow.button("Reset Pest Session", () -> sbs.modid.client.skills.garden.pests
                        .PestProfitTracker.getInstance().resetSession())
                        .describe("Starts this session's pest count and drops from zero. The profile "
                                + "total is kept."),
                SettingRow.button("Reset Pest Totals", () -> sbs.modid.client.skills.garden.pests
                        .PestProfitTracker.getInstance().resetTotal())
                        .describe("Clears the running pest total for this profile."),
                SettingRow.button("Move / Resize Pest Profit", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.PEST_PROFIT}, "Edit Pest Profit")))
                        .describe("Opens the editor where you drag the pest profit card anywhere on "
                                + "the screen and scale it."),

                SettingRow.toggle("Farming Session Summary", () -> cfg().farmingSession,
                        () -> { cfg().farmingSession = !cfg().farmingSession; save(); })
                        .describe("When you stop farming, a summary of the session: active time, "
                                + "crops per crop, crop coins (Bazaar instasell), pest kills and "
                                + "loot, rare drops, total coins and coins per hour - each next to "
                                + "your previous session. Starts with the first crop you break with "
                                + "a farming tool on a farming island. Crops need the tab Collection "
                                + "widget (/widgets). /sbs farming opens it any time."),
                SettingRow.intField("Pause After", 5, 600, () -> cfg().farmingSessionPauseSeconds,
                        value -> { cfg().farmingSessionPauseSeconds = value; save(); }, "s")
                        .describe("A gap between two crops longer than this is a break: it does not "
                                + "count as farming time, so coins per hour is about the time you "
                                + "actually farmed."),
                SettingRow.intField("End After Idle", 1, 120, () -> cfg().farmingSessionEndMinutes,
                        value -> { cfg().farmingSessionEndMinutes = value; save(); }, "min")
                        .describe("No crop broken for this long ends the session. Leaving the farming "
                                + "islands ends it at once."),
                SettingRow.toggle("Show Summary When It Ends", () -> cfg().farmingSessionAutoShow,
                        () -> { cfg().farmingSessionAutoShow = !cfg().farmingSessionAutoShow; save(); })
                        .describe("Opens the summary as soon as a session ends - only if no other "
                                + "screen is open, so it never closes a menu you are in."),
                SettingRow.toggle("Summary In Chat", () -> cfg().farmingSessionChat,
                        () -> { cfg().farmingSessionChat = !cfg().farmingSessionChat; save(); })
                        .describe("Also writes one line with the session's time, crops and coins "
                                + "into your own chat. Only you see it."),
                SettingRow.intField("Sessions Kept", 1, 50, () -> cfg().farmingSessionHistory,
                        value -> { cfg().farmingSessionHistory = value; save(); }, "")
                        .describe("How many finished sessions this profile remembers for the "
                                + "history list."),
                SettingRow.button("End Session Now", () -> {
                    sbs.modid.client.skills.farming.session.FarmingSessionTracker.getInstance().endNow();
                    open(new sbs.modid.client.skills.farming.session.FarmingSessionScreen(
                            sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()));
                })
                        .describe("Ends the running farming session and opens the summary. With "
                                + "no session running it just opens the summary."),
                SettingRow.button("Open Summary", () -> open(new sbs.modid.client.skills.farming.session
                        .FarmingSessionScreen(sbs.modid.client.core.api.GuiStateManager.getInstance()
                        .getCurrentScreen())))
                        .describe("Shows the last session and your history (also /sbs farming).")));

        // The two visitor alerts get the shared channel picker right after their labels.
        insertAfter(rows, "— Visitor queue full —",
                AlertChannelRows.forAlert("visitor_full", "the visitor queue fills up",
                        () -> cfg().visitorFullChannels,
                        value -> { cfg().visitorFullChannels = value; save(); }));
        insertAfter(rows, "— New visitor arrived —",
                AlertChannelRows.forAlert("visitor_arrived", "a new visitor arrives",
                        () -> cfg().visitorArrivedChannels,
                        value -> { cfg().visitorArrivedChannels = value; save(); }));
        return rows;
    }

    /** Puts {@code extra} directly after the row whose label is {@code marker}. */
    private static void insertAfter(List<SettingRow> rows, String marker, List<SettingRow> extra) {
        for (int i = 0; i < rows.size(); i++) {
            if (marker.equals(rows.get(i).label())) {
                rows.addAll(i + 1, extra);
                return;
            }
        }
        rows.addAll(extra);
    }

    private static void openPicker() {
        net.minecraft.client.gui.screens.Screen previous =
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Garden  •  Visitor Highlight",
                cfg().visitorHighlightColorHex, value -> {
                    cfg().visitorHighlightColorHex = value == null ? "" : value;
                    save();
                }, previous));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
