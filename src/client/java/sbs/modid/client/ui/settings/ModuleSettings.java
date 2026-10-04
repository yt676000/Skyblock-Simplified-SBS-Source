/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.ui.BazaarFlipsScreen;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.economy.forge.ui.ForgeFlipsScreen;
import sbs.modid.client.helper.stacktips.StackTipScope;
import sbs.modid.client.helper.warp.WarpMenuScreen;
import sbs.modid.client.core.keybind.CommandKeybindsScreen;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.economy.recipe.ui.RecipeViewerScreen;
import sbs.modid.client.ui.screen.TextEditorScreen;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.core.module.ModuleManager;
import sbs.modid.client.helper.visual.logic.WindowTitleText;
import sbs.modid.client.helper.visual.render.Chroma;
import sbs.modid.client.helper.visual.render.ChromaText;
import sbs.modid.client.ui.hud.render.SBSHudRenderer;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.stream.Stream;

/**
 * The single declaration of every module's settings page for the two-column config screen. Each
 * option appears here exactly once as a {@link SettingRow} (same widgets, same setters as the old
 * per-module screens), which powers both the right-hand settings panel and the real-time search
 * across module names AND option labels. Complex sub-editors (GUI editor, keybinds, text editor,
 * Bazaar overview, Recipe Viewer) open via buttons.
 */
public final class ModuleSettings {

    private ModuleSettings() {
    }

    /**
     * Transient (NOT persisted) "reveal the licence token" flag. Reset to {@code false} every time
     * the config screen opens ({@link sbs.modid.client.ui.screen.SBSMainScreen#init}), so the key is
     * always hidden on open and only shown while its checkbox is ticked this session.
     */
    public static boolean licenceKeyVisible = false;

    private static SBSConfig cfg() {
        return ConfigManager.getInstance().get();
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    /**
     * The shared flip ranking, so the local-flip thresholds can re-rank the moment they change.
     * {@code recomputeLocal} deliberately does no network: every one of those settings feeds the local
     * pass only, and spending a backend round trip to answer a purely local question would be absurd.
     */
    private static sbs.modid.client.economy.bazaar.logic.BazaarFlipFeed bazaarFlipFeed() {
        return sbs.modid.client.economy.bazaar.logic.BazaarFlipFeed.getInstance();
    }

    /** The same, for the forge ranking's local thresholds. */
    private static sbs.modid.client.economy.forge.logic.ForgeFlipFeed forgeFlipFeed() {
        return sbs.modid.client.economy.forge.logic.ForgeFlipFeed.getInstance();
    }

    private static String inventoryButtonSummary() {
        var buttons = sbs.modid.client.helper.inventorybuttons.logic.InventoryButtons.all();
        if (buttons.isEmpty()) {
            return "No buttons yet";
        }
        long runnable = buttons.stream()
                .filter(sbs.modid.client.helper.inventorybuttons.logic.InventoryButton::runnable).count();
        return buttons.size() + " button(s), " + runnable + " with a command";
    }

    /**
     * Waypoint count, live pathfinder state and the mobility it is routing with (snapshot from page
     * build time). The mobility is the useful bit while testing: it shows at a glance whether the
     * jump-boost potion was actually detected, and how tall a step it is allowing.
     */
    private static String pathfindingSummary() {
        int count = sbs.modid.client.core.pathfinding.WaypointStore.all().size();
        var manager = sbs.modid.client.core.pathfinding.PathfindingManager.getInstance();
        String state = manager.searching() ? "searching..."
                : manager.path().isEmpty() ? "no path" : manager.path().size() + " nodes";
        var mobility = manager.mobility();
        String mode = mobility == null ? "-"
                : mobility.mode().displayName() + ", step " + mobility.maxStepUp();
        // The teleport ranges are worth showing: they are the quickest way to tell whether the
        // Aspect in the inventory was actually recognised, and which of the two abilities it grants.
        String warp = mobility == null || !mobility.transmission().any() ? ""
                : "  •  tp " + mobility.transmission().instantRange()
                        + "/" + mobility.transmission().etherRange();
        // Search time for all routes together, last tick: the multi-route budget check - it should
        // stay near the 2 ms the single route was calibrated to however many routes are running.
        String search = String.format(java.util.Locale.ROOT, "  •  %d routes, %.2f ms",
                manager.routes().size(), manager.lastTickNanos() / 1e6);
        return count + " wp  •  " + state + "  •  " + mode + warp + search;
    }

    /** Re-derives the whole SBS palette from the configured base colours. */
    private static void themeRefresh() {
        sbs.modid.client.ui.theme.SBSTheme.refreshFromConfig();
    }

    /** Opens the theme colour picker for one base colour, returning to the current screen after. */
    private static void openPicker(String label, java.util.function.Supplier<String> getter,
                                   java.util.function.Consumer<String> setter) {
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Theme  •  " + label,
                getter.get(), setter,
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()));
    }

    /**
     * Opens the same picker for one SBS bar segment. A segment with no override yet starts the picker
     * on its <b>stock</b> colour rather than on the theme accent - the player came to adjust the red
     * of their health bar, not to start from an unrelated blue.
     */
    private static void openBarPicker(String label, java.util.function.Supplier<String> getter,
                                      int stock, java.util.function.Consumer<String> setter) {
        String current = getter.get();
        if (sbs.modid.client.core.render.OverlayColor.parseHex(current) == null) {
            current = String.format(java.util.Locale.ROOT, "%06X", stock & 0xFFFFFF);
        }
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Bar Color  •  " + label,
                current, setter,
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()));
    }

    /**
     * The picker for one Windows title-bar colour. Like {@link #openBarPicker} it starts on the
     * colour actually in force - here the theme colour the unset row is following - so opening the
     * picker never jumps to an unrelated hue before the first pick.
     *
     * <p>No explicit re-apply: the picker saves through {@code SBSTheme.refreshFromConfig()}, which
     * repaints the caption along with everything else.
     */
    private static void openTitleBarPicker(String label, java.util.function.Supplier<String> getter,
                                           int themed, java.util.function.Consumer<String> setter) {
        String current = getter.get();
        if (sbs.modid.client.core.render.OverlayColor.parseHex(current) == null) {
            current = String.format(java.util.Locale.ROOT, "%06X", themed & 0xFFFFFF);
        }
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Title Bar  •  " + label,
                current, setter,
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()));
    }

    private static SBSConfig.BarColorSettings barColors() {
        return cfg().hypixelGui.barColors;
    }

    /**
     * The bar-colour block of the Hypixel GUI page: one picker row per bar segment, plus a reset.
     *
     * <p>Every row reads and writes through {@link #barColors()} rather than through a captured
     * object, so switching config profiles while the page is open cannot leave a row editing the
     * settings of the profile the player just left.
     */
    private static List<SettingRow> barColorRows() {
        return List.of(
                SettingRow.color("Health Bar Color", () -> barColors().healthHex,
                        () -> SBSTheme.HUD_HEALTH,
                        () -> openBarPicker("Health", () -> barColors().healthHex,
                                SBSTheme.HUD_HEALTH, v -> barColors().healthHex = v))
                        .describe("The color of the filled part of the SBS health bar. Click to "
                                + "pick one; it stays red until you do."),
                SettingRow.color("Overheal Color", () -> barColors().overhealHex,
                        () -> SBSTheme.HUD_OVERHEAL,
                        () -> openBarPicker("Overheal", () -> barColors().overhealHex,
                                SBSTheme.HUD_OVERHEAL, v -> barColors().overhealHex = v))
                        .describe("The color of the absorption (overheal) part of the health bar - "
                                + "the segment past your normal maximum health."),
                SettingRow.color("Mana Bar Color", () -> barColors().manaHex,
                        () -> SBSTheme.HUD_MANA,
                        () -> openBarPicker("Mana", () -> barColors().manaHex,
                                SBSTheme.HUD_MANA, v -> barColors().manaHex = v))
                        .describe("The color of the filled part of the SBS mana bar."),
                SettingRow.color("Overflow Mana Color", () -> barColors().overflowManaHex,
                        SBSHudRenderer::stockOverflowMana,
                        () -> openBarPicker("Overflow Mana", () -> barColors().overflowManaHex,
                                SBSHudRenderer.stockOverflowMana(),
                                v -> barColors().overflowManaHex = v))
                        .describe("The color of the overflow mana segment at the left end of the "
                                + "mana bar - the extra mana that is spent first."),
                SettingRow.color("Vitality Bar Color", () -> barColors().vitalityHex,
                        () -> SBSTheme.HUD_VITALITY,
                        () -> openBarPicker("Vitality", () -> barColors().vitalityHex,
                                SBSTheme.HUD_VITALITY, v -> barColors().vitalityHex = v))
                        .describe("The color of the vitality (healing pool) bar above the health "
                                + "bar."),
                SettingRow.color("XP Bar Color", () -> barColors().xpHex,
                        () -> SBSTheme.HUD_XP,
                        () -> openBarPicker("XP", () -> barColors().xpHex,
                                SBSTheme.HUD_XP, v -> barColors().xpHex = v))
                        .describe("The color of the filled part of the SBS XP bar."),
                SettingRow.color("Bar Track Color", () -> barColors().trackHex,
                        () -> SBSTheme.HUD_TRACK,
                        () -> openBarPicker("Track", () -> barColors().trackHex,
                                SBSTheme.HUD_TRACK, v -> barColors().trackHex = v))
                        .describe("The empty part behind every SBS bar, and the outline around "
                                + "them. It keeps its see-through look whichever color you pick."),
                SettingRow.button("Reset Bar Colors", () -> { barColors().reset(); save(); })
                        .describe("Puts all the bars back to their stock colors: red health, "
                                + "yellow overheal, blue mana, pink vitality, green XP."),
                SettingRow.label("Bar colors are yours alone - the theme never repaints them"));
    }

    /**
     * The Squeaky Mousemat block of the Farming page: the toggles, the capture key, and one editable
     * row per crop that has a tool of its own.
     *
     * <p>Yaw and pitch share a single {@code "yaw/pitch"} field rather than getting a row each. That
     * is the form the angles are quoted in everywhere else - the chat listing, the panel on the sign
     * - so a pair can be pasted straight from a friend's, and it keeps a list that is already one
     * row per crop from being twice as long as the rest of the page put together.
     */
    /**
     * What the window-title placeholders currently stand for, so the player can see that {@code {mc}}
     * is a live reading rather than a word somebody typed.
     *
     * <p>Deliberately not a rendered preview of the finished title: the title bar itself updates on
     * every keystroke, which makes the window its own preview, and a second one on this page could
     * only ever be a snapshot going stale as it is read. {@code {vanilla}} is left out - it is the
     * long one, and it is different by the time the title is actually built.
     */
    private static String windowTitleValues() {
        var values = WindowTitleText.values(null);
        return "§8{mc} = " + values.get(WindowTitleText.MINECRAFT)
                + "  •  {mod} = " + values.get(WindowTitleText.MOD_NAME)
                + "  •  {modversion} = " + values.get(WindowTitleText.MOD_VERSION);
    }

    /**
     * Arms and then performs "reset the card frame to the theme".
     *
     * <p>Two-step because it throws away colours somebody picked by hand, and the answer comes back
     * in chat rather than on the button: a settings row's caption is built when the page is, so a
     * button cannot relabel itself between two clicks without rebuilding the page under the cursor.
     */
    private static final ConfirmClick CARD_FRAME_RESET = new ConfirmClick();

    private static void resetCardFrame() {
        if (!sbs.modid.client.ui.hud.render.CardChrome.anyOverride()) {
            sbs.modid.client.social.chat.logic.SBSChat.send(
                    "§7The HUD card frame already follows your theme - nothing to reset.");
            return;
        }
        if (!CARD_FRAME_RESET.click()) {
            sbs.modid.client.social.chat.logic.SBSChat.send(
                    "§eClick 'Reset Card Frame To Theme' again within 5s to clear the card colours "
                            + "you picked.");
            return;
        }
        sbs.modid.client.ui.hud.render.CardChrome.resetToTheme();
        sbs.modid.client.social.chat.logic.SBSChat.send(
                "§bHUD cards are back to your theme colours.");
    }

    private static List<SettingRow> mousematRows() {
        var angles = sbs.modid.client.skills.farming.logic.MousematAngles.getInstance();
        List<SettingRow> rows = new java.util.ArrayList<>(List.of(
                SettingRow.label("— Squeaky Mousemat —"),
                SettingRow.toggle("Crop Angle Helper", () -> cfg().farming.mousematHelper,
                        () -> { cfg().farming.mousematHelper = !cfg().farming.mousematHelper; save(); })
                        .describe("Remembers the yaw and pitch you farm each crop at, and lists "
                                + "them beside the Mousemat's sign. Whatever you type on that sign "
                                + "is saved for the crop you were last holding a tool for."),
                SettingRow.label("Click a crop on the sign to fill in its angle"),
                SettingRow.toggle("Fill The Sign Automatically",
                        () -> cfg().farming.mousematAutoFill,
                        () -> { cfg().farming.mousematAutoFill = !cfg().farming.mousematAutoFill; save(); })
                        .describe("Types the saved angle into the Mousemat's sign as it opens, so "
                                + "you only have to press Done. Only ever fills an angle you "
                                + "actually saved - never a default. Minecraft submits a sign "
                                + "however you leave it, so escaping out of a filled one still "
                                + "sets that angle."),
                SettingRow.keybind("Save Current Angle", () -> cfg().farming.mousematCaptureKey,
                        key -> { cfg().farming.mousematCaptureKey = key; save(); })
                        .describe("Aim the way the row should look, then press this key: where you "
                                + "are looking becomes the held crop's saved angle. Saves you "
                                + "reading the numbers off anything.")));

        for (var crop : sbs.modid.client.skills.farming.model.CropType.withTools()) {
            rows.add(SettingRow.valueField(
                    sbs.modid.client.skills.farming.logic.MousematAngles.label(crop),
                    "yaw/pitch", 20, 13,
                    () -> {
                        var angle = angles.angleFor(crop);
                        return angle == null ? "" : angle.text();
                    },
                    typed -> {
                        var parsed = sbs.modid.client.skills.farming.logic.MousematAngles
                                .parsePair(typed);
                        // An unreadable value is left alone rather than cleared: the setter fires on
                        // every keystroke, so "90" on the way to "90.0/0.0" must not wipe the row.
                        if (parsed != null) {
                            angles.save(crop, parsed.yaw(), parsed.pitch());
                        } else if (typed == null || typed.isBlank()) {
                            angles.clear(crop);
                        }
                    })
                    .describe("The angle the Mousemat should aim you at for "
                            + sbs.modid.client.skills.farming.logic.MousematAngles.label(crop)
                            + ", as yaw/pitch in degrees. Empty means nothing is saved yet."));
        }

        rows.add(SettingRow.button("Forget All Angles", () -> { angles.clearAll(); save(); })
                .describe("Clears every saved crop angle. The Mousemat itself is untouched - only "
                        + "what SBS remembers about it."));
        rows.add(SettingRow.label("/sbs mousemat lists them in chat"));
        return rows;
    }

    /** Joins row blocks into one page - the Hypixel GUI page is assembled from three. */
    /** The Farming Speed card's rows on the Farming page. */
    private static List<SettingRow> farmingSpeedRows() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Farming Speed —"));
        rows.add(SettingRow.toggle("Farming Speed Card", () -> cfg().farming.speedCard,
                        () -> { cfg().farming.speedCard = !cfg().farming.speedCard; save(); })
                .describe("Blocks broken per second on the Garden, always shown there. Dims when "
                        + "you stop for 3 seconds; nothing is shown or counted off the Garden. "
                        + "Default: on."));
        rows.add(SettingRow.toggle("Graph", () -> cfg().farming.speedGraph,
                        () -> { cfg().farming.speedGraph = !cfg().farming.speedGraph; save(); })
                .describe("Adds the last 60 seconds as bars under the card, with a line at your "
                        + "usual speed."));
        rows.add(SettingRow.toggle("Warn When Slower Than Usual", () -> cfg().farming.speedWarning,
                        () -> { cfg().farming.speedWarning = !cfg().farming.speedWarning; save(); })
                .describe("Tells you once when your 5-second speed stays under the threshold below "
                        + "for 3 seconds - lag, a wrong key, or stuck. Your usual speed is the median "
                        + "of this session, so it needs about 15 seconds of farming first. Stopping "
                        + "never triggers it. Off by default."));
        rows.add(SettingRow.rangeSlider("Warning Threshold", 10, 95, () -> cfg().farming.speedWarnPercent,
                        value -> { cfg().farming.speedWarnPercent = value; save(); }, "%")
                .describe("How far under your usual speed counts as slow. 70% by default."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("farming_speed",
                "the slow-farming warning fires", () -> cfg().farming.speedAlertChannels,
                mask -> { cfg().farming.speedAlertChannels = mask; save(); }));
        rows.add(SettingRow.toggle("Log Broken Blocks", () -> cfg().farming.speedLogBlocks,
                        () -> { cfg().farming.speedLogBlocks = !cfg().farming.speedLogBlocks; save(); })
                .describe("Writes every block you break to your game log with what the card counted "
                        + "it as, to check which blocks the flower crops use. Only your local log."));
        rows.add(SettingRow.button("Move / Resize Speed Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.FARMING_SPEED}, "Edit Farming Speed Card")))
                .describe("Opens the editor where you drag the speed card anywhere on the screen "
                        + "and scale it."));
        return rows;
    }

    /** The Lane End Warning's rows on the Farming page. */
    private static List<SettingRow> laneEndRows() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Lane End Warning —"));
        rows.add(SettingRow.toggle("Lane End Warning", () -> cfg().farming.laneEndWarning,
                        () -> { cfg().farming.laneEndWarning = !cfg().farming.laneEndWarning; save(); })
                .describe("Alerts you once, shortly before the end of a lane you marked yourself "
                        + "(Lane Start / Lane End keys or /sbs lane start and end). There is no automatic "
                        + "lane detection: only your marked lanes count, on the Garden, while you break "
                        + "crops. Display and sound only - nothing moves you. Off by default."));
        rows.add(SettingRow.rangeSlider("Warn At Blocks Left", 1, 20, () -> cfg().farming.laneEndBlocks,
                        value -> { cfg().farming.laneEndBlocks = value; save(); }, "")
                .describe("Warn with this many blocks of lane left. 5 by default."));
        rows.add(SettingRow.rangeSlider("Warn At Tenths Of A Second Left", 0, 50,
                        () -> cfg().farming.laneEndTenths,
                        value -> { cfg().farming.laneEndTenths = value; save(); }, "")
                .describe("Or this long before the end at your walking speed, in tenths of a second "
                        + "(10 = 1 s), whichever comes first. 0 turns the time rule off."));
        // Anchors keep the pre-farm ids: the rows were renamed, the settings behind them were not.
        rows.add(SettingRow.keybind("Lane Start", () -> cfg().farming.laneCorner1Key,
                        key -> { cfg().farming.laneCorner1Key = key; save(); })
                .anchor("lane_corner_1")
                .describe("Marks the start of a lane at your feet, on the Garden, in the farm of the plot "
                        + "you stand on (made if there is none). Same as /sbs lane start. Unbound by default."));
        rows.add(SettingRow.keybind("Lane End", () -> cfg().farming.laneCorner2Key,
                        key -> { cfg().farming.laneCorner2Key = key; save(); })
                .anchor("lane_corner_2")
                .describe("Marks the other end and adds the lane; the next Lane Start begins the next one. "
                        + "/sbs lane repeat <count> <spacing> copies it sideways, /sbs lane area marks a "
                        + "whole rectangle. Same as /sbs lane end. Unbound by default."));
        rows.add(SettingRow.rangeSlider("Lane Width", 1, 5, () -> cfg().farming.laneWidth,
                        value -> { cfg().farming.laneWidth = value; save(); }, "")
                .describe("How many blocks across a new lane still count as in it, centred on where you "
                        + "walked. 3 by default; change one lane's in the Farms screen."));
        rows.add(SettingRow.toggle("Show Lanes", () -> cfg().farming.laneAreaPreview,
                        () -> { cfg().farming.laneAreaPreview = !cfg().farming.laneAreaPreview; save(); })
                .anchor("show_lane_areas")
                .describe("While you hold a farming tool on the Garden, draws the lanes near you with a "
                        + "start and end marker and their number, the current farm brighter. What you are "
                        + "marking is always shown. On by default."));
        rows.add(SettingRow.button("Farms", sbs.modid.client.skills.farming.ui.LaneFarmsScreen::open)
                .anchor("lane_areas")
                .describe("Lists your farms and their lanes to rename, delete, change a lane's width or "
                        + "show only one farm. Also /sbs lane list."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("lane_end",
                "the lane end warning fires", () -> cfg().farming.laneEndChannels,
                mask -> { cfg().farming.laneEndChannels = mask; save(); }));
        return rows;
    }

    /** The Jacob's Contest card's rows on the Farming page. */
    /** Rare farming drops: counter, card and one alert picker per tier. */
    private static List<SettingRow> farmDropRows() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Rare Farming Drops —"));
        rows.add(SettingRow.toggle("Track Farming Drops", () -> cfg().farming.farmDrops,
                        () -> { cfg().farming.farmDrops = !cfg().farming.farmDrops; save(); })
                .describe("Counts rare drops from crops (Cropie, Squash, Fermento...) while you "
                        + "farm with a farming tool on a farming island: this session, and all-time "
                        + "per profile."));
        rows.add(SettingRow.toggle("Farm Drops Card", () -> cfg().farming.farmDropsCard,
                        () -> { cfg().farming.farmDropsCard = !cfg().farming.farmDropsCard; save(); })
                .describe("A card with this session's farming drops, their value (sell price, "
                        + "'+' when an item has none) and drops per hour."));
        rows.add(SettingRow.label("— RARE drop alert —"));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("farm_drop_rare",
                "a RARE farming drop", () -> cfg().farming.farmDropRareChannels,
                value -> { cfg().farming.farmDropRareChannels = value; save(); }));
        rows.add(SettingRow.label("— VERY RARE and rarer drop alert —"));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("farm_drop_very_rare",
                "a VERY RARE or rarer farming drop", () -> cfg().farming.farmDropVeryRareChannels,
                value -> { cfg().farming.farmDropVeryRareChannels = value; save(); }));
        return rows;
    }

    private static List<SettingRow> jacobContestRows() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Jacob's Contest —"));
        rows.add(SettingRow.toggle("Contest Card", () -> cfg().farming.contestCard,
                        () -> { cfg().farming.contestCard = !cfg().farming.contestCard; save(); })
                .describe("While a Jacob's Contest runs: the crop, time left, how much you have "
                        + "collected and your bracket, on one card. Read from the sidebar - the "
                        + "exact sidebar wording has not been checked in game yet, so some lines may "
                        + "show \"?\" until it is."));
        rows.add(SettingRow.toggle("Contest Pace Estimate", () -> cfg().farming.contestProjection,
                        () -> { cfg().farming.contestProjection = !cfg().farming.contestProjection; save(); })
                .describe("Adds your crops per minute over the last minute and where that pace ends "
                        + "up at the close. An estimate: it assumes the pace holds."));
        rows.add(SettingRow.toggle("Alert On New Bracket", () -> cfg().farming.contestBracketAlert,
                        () -> { cfg().farming.contestBracketAlert = !cfg().farming.contestBracketAlert; save(); })
                .describe("Tells you when you move up a bracket during a contest."));
        rows.add(SettingRow.toggle("Alert At Last Minute", () -> cfg().farming.contestLastMinuteAlert,
                        () -> { cfg().farming.contestLastMinuteAlert = !cfg().farming.contestLastMinuteAlert; save(); })
                .describe("Tells you once when under a minute of the contest is left."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("jacob_contest",
                "a contest alert fires", () -> cfg().farming.contestAlertChannels,
                mask -> { cfg().farming.contestAlertChannels = mask; save(); }));
        rows.add(SettingRow.toggle("Log Contest Sidebar", () -> cfg().farming.contestProbe,
                        () -> { cfg().farming.contestProbe = !cfg().farming.contestProbe; save(); })
                .describe("Writes the sidebar to your game log every 10 seconds during a contest, so "
                        + "the card can be matched to Hypixel's exact wording. Only your local log; "
                        + "nothing is sent."));
        rows.add(SettingRow.button("Move / Resize Contest Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.JACOB_CONTEST}, "Edit Jacob's Contest Card")))
                .describe("Opens the editor where you drag the contest card anywhere on the screen "
                        + "and scale it."));
        return rows;
    }

    private static final sbs.modid.client.ui.settings.ConfirmClick LAYOUT_CLEAR_CONFIRM =
            new sbs.modid.client.ui.settings.ConfirmClick();

    /** The Layout Recorder's rows at the top of the Developer page. */
    private static List<SettingRow> layoutRecorderRows() {
        return List.of(
                SettingRow.label("— Layout Recorder —"),
                SettingRow.toggle("Record UI Layouts", () -> cfg().dev.recordLayouts,
                                () -> { cfg().dev.recordLayouts = !cfg().dev.recordLayouts; save(); })
                        .describe("Stores every unique Hypixel screen layout once - menus, tab widgets, "
                                + "scoreboard lines and the action bar - in Development_Stuff/layouts. "
                                + "The same screen with other values (purse, date, timers, names) is "
                                + "not stored again, only counted. Player names and your money are "
                                + "replaced in the stored examples. Default: off; off costs nothing."),
                SettingRow.button("Open Layouts Folder",
                                () -> sbs.modid.client.core.dev.LayoutRecorder.getInstance().openFolder())
                        .describe("Opens Development_Stuff/layouts in your file browser."),
                // A button, not a cycle row: ui/AGENTS.md bans cyclers here. The first click arms and
                // says so in chat; only a second click within ConfirmClick's window deletes.
                SettingRow.button("Clear Recorded Layouts", () -> {
                            if (LAYOUT_CLEAR_CONFIRM.click()) {
                                sbs.modid.client.core.dev.LayoutRecorder.getInstance().clear();
                                sbs.modid.client.social.chat.logic.SBSChat.send("Recorded layouts deleted.");
                            } else {
                                sbs.modid.client.social.chat.logic.SBSChat.send(
                                        "Click Clear Recorded Layouts again within 5s to delete every recorded layout.");
                            }
                        })
                        .describe("Deletes every recorded layout and the index. Click once to arm, "
                                + "again within 5 seconds to confirm."));
    }

    /** The Server Scanner's rows on the Developer page, below the Layout Recorder. */
    private static List<SettingRow> serverScannerRows() {
        return List.of(
                SettingRow.label("— Server Scanner —"),
                SettingRow.toggle("Server Scanner", sbs.modid.client.core.dev.scanner.ServerScanner::isRunning,
                                sbs.modid.client.core.dev.scanner.ServerScanner::toggle)
                        .describe("Starts or stops a scan session, like /sbs scan start / stop. While it "
                                + "runs, every menu the server opens is written to "
                                + "Development_Stuff/scanner/<session>/ as a time series: the full "
                                + "contents, every slot change as old -> new with timestamps, your own "
                                + "clicks and the close. Only reads - nothing is clicked, cancelled or "
                                + "sent. Not saved: a session ends with the game."),
                SettingRow.toggle("Scan Chat", () -> cfg().dev.scannerChat,
                                () -> { cfg().dev.scannerChat = !cfg().dev.scannerChat; save(); })
                        .describe("A session started without channel words also writes every chat line "
                                + "(raw and plain) to chat.jsonl. Other players' names are replaced."),
                SettingRow.toggle("Scan Action Bar", () -> cfg().dev.scannerActionBar,
                                () -> { cfg().dev.scannerActionBar = !cfg().dev.scannerActionBar; save(); })
                        .describe("Also writes the action bar to actionbar.jsonl, one line per change."),
                SettingRow.toggle("Scan Scoreboard", () -> cfg().dev.scannerScoreboard,
                                () -> { cfg().dev.scannerScoreboard = !cfg().dev.scannerScoreboard; save(); })
                        .describe("Also writes the sidebar to scoreboard.jsonl whenever a line changes. "
                                + "Other players' names are replaced."),
                SettingRow.toggle("Scan Tab List", () -> cfg().dev.scannerTablist,
                                () -> { cfg().dev.scannerTablist = !cfg().dev.scannerTablist; save(); })
                        .describe("Also writes the tab list and its footer to tablist.jsonl whenever they "
                                + "change. Other players' names are replaced."),
                SettingRow.intField("Session Size Cap", 1, 10_000, () -> cfg().dev.scannerMaxMb,
                                value -> { cfg().dev.scannerMaxMb = value; save(); }, "MB")
                        .describe("A session stops by itself, with a chat message, once its files pass "
                                + "this size. Default: 200 MB."),
                SettingRow.button("Open Scanner Folder", () -> {
                            java.nio.file.Path dir = sbs.modid.client.core.config.SBSFiles.scannerDir();
                            try {
                                java.nio.file.Files.createDirectories(dir);
                                net.minecraft.util.Util.getPlatform().openPath(dir);
                            } catch (Exception e) {
                                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Scanner] could not open {}: {}",
                                        dir, e.toString());
                            }
                        })
                        .describe("Opens Development_Stuff/scanner in your file browser."));
    }

    /** Guards "Reset All Screen Opacity" - it drops every per-screen value at once. */
    private static final ConfirmClick SCREEN_OPACITY_RESET = new ConfirmClick();

    /**
     * One slider + reset per screen that has its own opacity, and a two-click "reset all". Rows are
     * anchored on the screen key, not the label, so a screen's row keeps its id however it is named.
     */
    private static List<SettingRow> screenOpacityRows() {
        var overrides = sbs.modid.client.ui.theme.ScreenOpacity.overrides();
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Per-screen opacity —"));
        if (overrides.isEmpty()) {
            rows.add(SettingRow.label("§8No screen has its own value - every screen uses the slider above"));
            return rows;
        }
        rows.add(SettingRow.label("§8A screen's own value replaces the slider above for that screen"));
        for (String key : new java.util.ArrayList<>(overrides.keySet())) {
            String name = sbs.modid.client.ui.theme.ScreenKeys.displayName(key);
            String slug = key.replaceAll("[^a-z0-9]+", "_");
            rows.add(SettingRow.rangeSlider(name, sbs.modid.client.ui.theme.SBSTheme.MIN_SCREEN_OPACITY, 100,
                            () -> overrides.getOrDefault(key, cfg().theme.surfaceOpacity),
                            value -> { overrides.put(key, value); save(); }, "%")
                    .anchor("screen_opacity_" + slug)
                    .describe("How solid the " + name + " screen is, instead of the Surface Opacity "
                            + "slider. Never below " + sbs.modid.client.ui.theme.SBSTheme.MIN_SCREEN_OPACITY
                            + "%, so its buttons stay findable."));
            rows.add(SettingRow.button("Use Global For " + name, () -> {
                        overrides.remove(key);
                        save();
                    })
                    .anchor("screen_opacity_reset_" + slug)
                    .describe("Drops " + name + "'s own value; it follows the Surface Opacity slider "
                            + "again. Reopen this page to see the list update."));
        }
        rows.add(SettingRow.button("Reset All Screen Opacity", () -> {
                    if (SCREEN_OPACITY_RESET.click()) {
                        overrides.clear();
                        save();
                    } else {
                        sbs.modid.client.social.chat.logic.SBSChat.send(
                                "Click Reset All Screen Opacity again within 5s to confirm.");
                    }
                })
                .describe("Drops every screen's own value, so all of them follow the Surface Opacity "
                        + "slider again. Two clicks within 5 seconds."));
        return rows;
    }

    @SafeVarargs
    private static List<SettingRow> concat(List<SettingRow>... blocks) {
        List<SettingRow> all = new java.util.ArrayList<>();
        for (List<SettingRow> block : blocks) {
            all.addAll(block);
        }
        return all;
    }

    /** How many SkyBlock mobs the Mob Highlight selection currently holds (snapshot at build time). */
    private static String mobHighlightSummary() {
        int count = cfg().mobHighlight.selectedMobs.size();
        return count == 0 ? "No mobs selected yet" : count + " mob(s) selected";
    }

    /** How many storages the item search has indexed so far (snapshot from page build time). */
    /** One on/off row per storage family the index understands (persisted in storageSearch.sources). */
    private static SettingRow storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind kind) {
        return SettingRow.toggle("Index " + kind.displayName(),
                () -> cfg().storageSearch.enabledFor(kind),
                () -> { cfg().storageSearch.toggleSource(kind); save(); });
    }

    /** Footer line under the entity-type picker: empty selection means every type, so say that. */
    private static String entityTypeSelectionSummary() {
        int count = cfg().animationScaling.scaleEntityTypes.size();
        return count == 0 ? "§8No type filter - every entity type is scaled"
                : "§8Limited to " + count + " entity type(s)";
    }

    private static String storageSourceSummary() {
        int count = sbs.modid.client.helper.storage.StorageIndex.getInstance().sourceCount();
        return count == 0 ? "No storages indexed yet this session"
                : count + " storage(s) indexed this session";
    }

    /** How many slots are locked, as a label line (a snapshot from when the page was built). */
    private static String lockedSlotSummary() {
        int count = cfg().slotLock.lockedSlots.size();
        return count == 0 ? "No slots locked yet" : count + " slot(s) currently locked";
    }

    /**
     * The settings rows of one module (empty for unknown ids).
     *
     * <p>A self-registered {@link sbs.modid.client.core.module.SbsModule} declares its own rows in its
     * own package and is asked first; the switch below is the legacy path for modules not migrated
     * yet. New modules must NOT be added here – that is the shared file this exists to get out of.
     */
    public static List<SettingRow> rowsFor(String moduleId) {
        // The favourites page owns no settings of its own - it borrows other modules' rows.
        if (Favorites.PAGE_ID.equals(moduleId)) {
            return Favorites.rows();
        }
        // Commands are not settings either: the page lists CommandRegistry, see CommandsPage.
        if (CommandsPage.PAGE_ID.equals(moduleId)) {
            return CommandsPage.rows();
        }
        var module = sbs.modid.client.core.module.ModuleRegistry.byId(moduleId);
        if (module != null) {
            return module.settings();
        }
        return switch (moduleId) {
            // Page order: the HUD bars first, then the hide toggles, then the extra HUD cards,
            // and the editor (buttons + hotkey) as the closing block.
            case ModuleManager.HYPIXEL_GUI_ID -> concat(List.of(
                    SettingRow.enumOptions("Health Bar", () -> cfg().hypixelGui.healthBar,
                            value -> { cfg().hypixelGui.healthBar = value; save(); }, v -> v.displayName())
                            .describe("How your health is drawn: the vanilla hearts, the sleek SBS "
                                    + "bar, or both. Click to cycle."),
                    SettingRow.enumOptions("Mana Bar", () -> cfg().hypixelGui.manaBar,
                            value -> { cfg().hypixelGui.manaBar = value; save(); }, v -> v.displayName())
                            .describe("How your mana is drawn: hidden, the SBS bar, or the plain "
                                    + "action-bar text. Click to cycle."),
                    SettingRow.enumOptions("Vitality Bar", () -> cfg().hypixelGui.vitalityBar,
                            value -> { cfg().hypixelGui.vitalityBar = value; save(); }, v -> v.displayName())
                            .describe("How Hypixel's healing-pool (vitality) stat is drawn - an "
                                    + "extra SBS bar above the health bar. Click to cycle."),
                    SettingRow.enumOptions("XP Bar", () -> cfg().hypixelGui.xpBar,
                            value -> { cfg().hypixelGui.xpBar = value; save(); }, v -> v.displayName())
                            .describe("How the XP bar is drawn: vanilla, the SBS bar, or hidden. "
                                    + "Click to cycle.")),
                    // One colour picker per bar segment - right after the bars they belong to.
                    barColorRows(),
                    List.of(
                    SettingRow.toggle("Hide Armor Bar", () -> cfg().hypixelGui.hideArmorBar,
                            () -> { cfg().hypixelGui.hideArmorBar = !cfg().hypixelGui.hideArmorBar; save(); })
                            .describe("Hides the vanilla armor icons above the hotbar - in "
                                    + "SkyBlock they say nothing useful, defense is a stat."),
                    SettingRow.toggle("Hide Potion Effect Status", () -> cfg().hypixelGui.hidePotionEffects,
                            () -> { cfg().hypixelGui.hidePotionEffects = !cfg().hypixelGui.hidePotionEffects; save(); })
                            .describe("Hides the potion-effect icons in the top-right corner."),
                    SettingRow.toggle("Show Active Pet", () -> cfg().hypixelGui.showActivePet,
                            () -> { cfg().hypixelGui.showActivePet = !cfg().hypixelGui.showActivePet; save(); })
                            .describe("A small card with your active pet, its level and XP "
                                    + "progress."),
                    SettingRow.toggle("Pet Name In Hypixel's Colours",
                            () -> cfg().hypixelGui.petNameServerColors,
                            () -> {
                                cfg().hypixelGui.petNameServerColors =
                                        !cfg().hypixelGui.petNameServerColors;
                                save();
                            })
                            .describe("On: the pet's name keeps the rarity colour Hypixel sends it "
                                    + "in, so a legendary pet reads gold. Off: it is drawn in your "
                                    + "theme's colours like every other card title. On by default - "
                                    + "the rarity colour is information, not decoration."),
                    SettingRow.toggle("Chroma Bar at Max Level", () -> cfg().hypixelGui.chromaMaxPetBar,
                            () -> { cfg().hypixelGui.chromaMaxPetBar = !cfg().hypixelGui.chromaMaxPetBar; save(); })
                            .describe("On a maxed pet the XP bar has nothing left to fill, so it "
                                    + "shimmers with the same travelling rainbow a maxed skill "
                                    + "gets in the profile viewer and a maxed enchantment gets in "
                                    + "a tooltip. Off, a maxed bar is solid accent. Speed follows "
                                    + "the Item Overlay's chroma speed, so every maxed thing on "
                                    + "screen moves together."),
                    SettingRow.toggle("Show Item Cooldown (HUD)", () -> cfg().hypixelGui.showCooldownHud,
                            () -> { cfg().hypixelGui.showCooldownHud = !cfg().hypixelGui.showCooldownHud; save(); })
                            .describe("A cooldown readout next to the crosshair for the item you "
                                    + "are holding."),
                    SettingRow.toggle("Show Server Stats", () -> cfg().hypixelGui.showServerStats,
                            () -> { cfg().hypixelGui.showServerStats = !cfg().hypixelGui.showServerStats; save(); })
                            .describe("A card with your ping and the server's TPS and your FPS - "
                                    + "handy for telling YOUR lag from server lag."),
                    SettingRow.toggle("Show Remaining Arrows", () -> cfg().hypixelGui.showRemainingArrows,
                            () -> { cfg().hypixelGui.showRemainingArrows = !cfg().hypixelGui.showRemainingArrows; save(); })
                            .describe("While you hold a bow: which arrows you are shooting and how "
                                    + "many are left in your quiver, beside the hotbar."),
                    SettingRow.label("While a bow is held: the arrow type and how many are left, beside the hotbar"),
                    SettingRow.enumOptions("Recipe Viewer Position",
                            () -> cfg().hypixelGui.recipeViewerPosition,
                            value -> { cfg().hypixelGui.recipeViewerPosition = value; save(); }, v -> v.displayName())
                            .describe("Which side of a container screen the Recipe Viewer's item "
                                    + "list sits on. Click to cycle."),
                    SettingRow.toggle("SBS Button in Pause Menu", () -> cfg().hypixelGui.pauseButton,
                            () -> { cfg().hypixelGui.pauseButton = !cfg().hypixelGui.pauseButton; save(); })
                            .describe("A button in the Esc menu that opens these SBS settings. "
                                    + "Drag it in the pause menu to move it, scroll over it to "
                                    + "resize."),
                    SettingRow.label("Drag the button in the pause menu to move it, scroll over it to resize it"),
                    SettingRow.rangeSlider("Pause Button Size",
                            sbs.modid.client.ui.pausemenu.PauseMenuButton.MIN_SCALE,
                            sbs.modid.client.ui.pausemenu.PauseMenuButton.MAX_SCALE,
                            sbs.modid.client.ui.pausemenu.PauseMenuButton::scale,
                            sbs.modid.client.ui.pausemenu.PauseMenuButton::setScale, "%")
                            .describe("The SBS pause-menu button's size."),
                    SettingRow.button("Reset Button Position",
                            sbs.modid.client.ui.pausemenu.PauseMenuButton::resetPosition)
                            .describe("Puts the SBS pause-menu button back to its default spot, "
                                    + "e.g. after dragging it off somewhere unfindable."),
                    SettingRow.button("Edit Pause Menu Buttons",
                            sbs.modid.client.ui.pausemenu.PauseMenuEditor::open)
                            .describe("Opens an editor for ALL pause-menu buttons - move, resize "
                                    + "and round any of them, vanilla ones included."),
                    SettingRow.label("Move, resize and round every pause menu button, the SBS button included"),
                    SettingRow.button("Edit GUI", () -> open(new HudEditorScreen()))
                            .describe("The HUD editor: drag and scale every HUD element (bars, "
                                    + "cards, trackers) anywhere on the screen."),
                    SettingRow.button("Edit GUI (visible only)",
                            () -> open(HudEditorScreen.activeOnly()))
                            .describe("The same HUD editor, but only showing the elements that "
                                    + "were actually on screen just now - less clutter when you "
                                    + "just want to nudge one thing."),
                    SettingRow.label("Only the elements that were actually on screen while you were playing"),
                    SettingRow.keybind("Edit GUI Hotkey", () -> cfg().hypixelGui.editGuiKey,
                            key -> { cfg().hypixelGui.editGuiKey = key; save(); })
                            .describe("A key that opens the full HUD editor straight from the "
                                    + "game. Click the row, press a key; Esc unbinds."),
                    SettingRow.keybind("Edit GUI (visible only) Hotkey",
                            () -> cfg().hypixelGui.editGuiVisibleKey,
                            key -> { cfg().hypixelGui.editGuiVisibleKey = key; save(); })
                            .describe("A key that opens the editor over just the cards on screen "
                                    + "at that moment - the variant worth a key, since what it "
                                    + "lists depends on where you are and what you are doing."),
                    SettingRow.label("Press either key in-game to move / scale HUD elements directly")));

            case ModuleManager.ITEM_OVERLAY_ID -> List.of(
                    SettingRow.enumOptions("Show Item Rarity", () -> cfg().itemOverlay.rarityMode,
                            value -> { cfg().itemOverlay.rarityMode = value; save(); }, v -> v.displayName())
                            .describe("Tints every item slot in its rarity color (green "
                                    + "Uncommon, purple Epic...), so a whole inventory is "
                                    + "readable at a glance. Click to pick the style."),
                    SettingRow.rangeSlider("Rarity Overlay Opacity", 5, 80,
                            () -> cfg().itemOverlay.rarityOpacity,
                            value -> { cfg().itemOverlay.rarityOpacity = value; save(); }, "%")
                            .describe("How strong that rarity tint is."),
                    SettingRow.toggle("Show LBIN", () -> cfg().itemOverlay.showLbin,
                            () -> {
                                cfg().itemOverlay.showLbin = !cfg().itemOverlay.showLbin;
                                save();
                                if (cfg().itemOverlay.showLbin) {
                                    LbinCache.getInstance().requestRefresh();
                                }
                            })
                            .describe("Adds the item's lowest Auction House buy-it-now price to "
                                    + "its tooltip - what the item is worth right now."),
                    SettingRow.toggle("Show Lowest Bazaar Price", () -> cfg().itemOverlay.showBazaarPrice,
                            () -> {
                                cfg().itemOverlay.showBazaarPrice = !cfg().itemOverlay.showBazaarPrice;
                                save();
                                if (cfg().itemOverlay.showBazaarPrice) {
                                    BazaarPriceCache.getInstance().requestRefresh();
                                }
                            })
                            .describe("Adds the item's Bazaar price to its tooltip, for items "
                                    + "traded there instead of the Auction House."),
                    SettingRow.toggle("Estimated Item Value", () -> cfg().itemOverlay.showItemValue,
                            () -> {
                                cfg().itemOverlay.showItemValue = !cfg().itemOverlay.showItemValue;
                                save();
                                if (cfg().itemOverlay.showItemValue) {
                                    LbinCache.getInstance().requestRefresh();
                                    BazaarPriceCache.getInstance().requestRefresh();
                                }
                            })
                            .describe("What the item in front of you is worth: its market price "
                                    + "plus every star, book, gem, reforge stone and potato book "
                                    + "on it. A \"+\" means a part of it has no market price, so "
                                    + "the real value is higher.")
                            .inDevelopment(),
                    SettingRow.toggle("Gemstone Slot Summary", () -> cfg().itemOverlay.showGemSlots,
                            () -> { cfg().itemOverlay.showGemSlots = !cfg().itemOverlay.showGemSlots; save(); })
                            .describe("One tooltip line listing the item's gemstone slots: a filled "
                                    + "diamond is a gem and which one, a hollow diamond an unlocked "
                                    + "empty slot, a cross a slot still locked. Only on items that "
                                    + "have gemstone slots.")
                            .inDevelopment(),
                    SettingRow.toggle("Gemstone Value", () -> cfg().itemOverlay.showGemSlotValue,
                            () -> {
                                cfg().itemOverlay.showGemSlotValue = !cfg().itemOverlay.showGemSlotValue;
                                save();
                                if (cfg().itemOverlay.showGemSlotValue) {
                                    BazaarPriceCache.getInstance().requestRefresh();
                                }
                            })
                            .describe("Ends the gemstone slot line with what the applied gems sell "
                                    + "for on the Bazaar right now. A \"+\" means one of them has "
                                    + "no price, so the real value is higher.")
                            .inDevelopment(),
                    SettingRow.toggle("Container Value", () -> cfg().itemOverlay.containerValue,
                            () -> {
                                cfg().itemOverlay.containerValue = !cfg().itemOverlay.containerValue;
                                save();
                                if (cfg().itemOverlay.containerValue) {
                                    LbinCache.getInstance().requestRefresh();
                                    BazaarPriceCache.getInstance().requestRefresh();
                                }
                            })
                            .describe("A card above any open menu totalling what the container "
                                    + "holds and what your own inventory is worth. Works in the "
                                    + "Ender Chest pages and Storage backpacks too."),
                    SettingRow.label("Chest + inventory totals above the menu  •  \"+\" = part of it is unpriced"),
                    SettingRow.toggle("Show Item Cooldown", () -> cfg().itemOverlay.showItemCooldown,
                            () -> { cfg().itemOverlay.showItemCooldown = !cfg().itemOverlay.showItemCooldown; save(); })
                            .describe("Draws the ability cooldown as a filling overlay on the "
                                    + "item's slot itself."),
                    SettingRow.label("Item Stack Tips"),
                    SettingRow.toggle("Stack Tip: Pet Level", () -> cfg().itemOverlay.stackTipPets,
                            () -> { cfg().itemOverlay.stackTipPets = !cfg().itemOverlay.stackTipPets; save(); })
                            .describe("A pet's level as a small number in the top-left corner of "
                                    + "its icon, wherever the pet is shown."),
                    SettingRow.toggle("Stack Tip: Minion Tier", () -> cfg().itemOverlay.stackTipMinions,
                            () -> { cfg().itemOverlay.stackTipMinions = !cfg().itemOverlay.stackTipMinions; save(); })
                            .describe("A minion's tier as a number in the top-left corner of its "
                                    + "icon - 11 instead of reading XI off the name."),
                    SettingRow.toggle("Stack Tip: Catacombs Pass Floor", () -> cfg().itemOverlay.stackTipDungeonPasses,
                            () -> { cfg().itemOverlay.stackTipDungeonPasses = !cfg().itemOverlay.stackTipDungeonPasses; save(); })
                            .describe("The floor a Catacombs Pass is for, top-left on its icon; "
                                    + "Master Mode passes read M7."),
                    SettingRow.toggle("Stack Tip: Skill Level", () -> cfg().itemOverlay.stackTipSkills,
                            () -> { cfg().itemOverlay.stackTipSkills = !cfg().itemOverlay.stackTipSkills; save(); })
                            .describe("Each skill's level on its icon in the Skills menu. Not yet "
                                    + "checked against the live menu, so it is off by default."),
                    SettingRow.toggle("Stack Tip: Collection Tier", () -> cfg().itemOverlay.stackTipCollections,
                            () -> { cfg().itemOverlay.stackTipCollections = !cfg().itemOverlay.stackTipCollections; save(); })
                            .describe("Each collection's tier on its icon in a collection menu. Not "
                                    + "yet checked against the live menu, so it is off by default."),
                    SettingRow.segmented("Stack Tips Show In",
                            List.of(StackTipScope.CONTAINERS.displayName(), StackTipScope.HOTBAR.displayName(),
                                    StackTipScope.BOTH.displayName()),
                            () -> (cfg().itemOverlay.stackTipScope == null
                                    ? StackTipScope.BOTH : cfg().itemOverlay.stackTipScope).ordinal(),
                            index -> { cfg().itemOverlay.stackTipScope = StackTipScope.values()[index]; save(); })
                            .describe("Whether stack tips draw over open menus, on the hotbar at the "
                                    + "bottom of the screen, or both."),
                    SettingRow.toggle("Item Renamer", () -> cfg().itemOverlay.itemRenamer,
                            () -> { cfg().itemOverlay.itemRenamer = !cfg().itemOverlay.itemRenamer; save(); })
                            .describe("Give items your own display names, only you see them: "
                                    + "hold the item and run /sbs itemrename <new name>; /sbs "
                                    + "itemoriginalname restores it."),
                    SettingRow.label("/sbs itemrename <new name>  •  /sbs itemoriginalname"),
                    SettingRow.toggle("Hold Shift: Missing Enchantments",
                            () -> cfg().itemOverlay.shiftMissingEnchants,
                            () -> { cfg().itemOverlay.shiftMissingEnchants = !cfg().itemOverlay.shiftMissingEnchants; save(); })
                            .describe("Holding Shift over an item lists the enchantments it does "
                                    + "NOT have yet - what is still missing to max it out."),
                    SettingRow.toggle("Hide Duplicate Enchant Line",
                            () -> cfg().itemOverlay.hideVanillaEnchantLines,
                            () -> {
                                cfg().itemOverlay.hideVanillaEnchantLines =
                                        !cfg().itemOverlay.hideVanillaEnchantLines;
                                save();
                            })
                            .describe("Some SkyBlock enchants are real Minecraft ones, so they show "
                                    + "twice: once under the item name (Minecraft's own line) and once "
                                    + "in Hypixel's enchant block. This drops the one under the name."),
                    SettingRow.label("§8e.g. Depth Strider showing above the stats as well."),
                    // Chroma cluster: each effect has its own toggle, spread and saturation. Speed
                    // and colours are NOT here - they are one setting for the whole mod, on the
                    // Theme page, so every shimmer on screen stays in step.
                    SettingRow.label("Chroma Animation"),
                    SettingRow.toggle("Chroma on Maxed Enchantments",
                            () -> cfg().itemOverlay.chromaMaxedEnchants,
                            () -> { cfg().itemOverlay.chromaMaxedEnchants = !cfg().itemOverlay.chromaMaxedEnchants; save(); })
                            .describe("Draws max-level NORMAL enchantments in animated rainbow "
                                    + "text, so a maxed item is recognisable at a glance. "
                                    + "Ultimates are a separate setting below."),
                    SettingRow.rangeSlider("Max Enchantments Spread",
                            ChromaText.MIN_SPREAD, ChromaText.MAX_SPREAD,
                            () -> cfg().itemOverlay.chromaEnchantSpread,
                            value -> { cfg().itemOverlay.chromaEnchantSpread = value; save(); }, " chars")
                            .describe("How many characters one full rainbow is stretched over. "
                                    + "This is what decides gradient versus stripes: a low value "
                                    + "packs several rainbows into one word and reads as banding, "
                                    + "a high value sweeps one smooth pass across the whole name."),
                    SettingRow.rangeSlider("Max Enchantments Saturation", 0, 100,
                            () -> cfg().itemOverlay.chromaEnchantSaturation,
                            value -> { cfg().itemOverlay.chromaEnchantSaturation = value; save(); }, "%")
                            .describe("How colourful the sweep is. Lower values pastel it out "
                                    + "toward white, which is easier to read on long lore."),

                    SettingRow.toggle("Chroma on Ultimate Enchantments",
                            () -> cfg().itemOverlay.chromaUltimateEnchants,
                            () -> { cfg().itemOverlay.chromaUltimateEnchants = !cfg().itemOverlay.chromaUltimateEnchants; save(); })
                            .describe("Off by default on purpose: Hypixel gives ultimates their "
                                    + "own bold light-purple styling, and that is how you pick one "
                                    + "out of a wall of lore. Painting them the same rainbow as "
                                    + "every other maxed enchant throws that away. Turn it on if "
                                    + "you want them shimmering anyway - they get their own spread "
                                    + "and saturation, so they can still look distinct."),
                    SettingRow.rangeSlider("Ultimate Spread",
                            ChromaText.MIN_SPREAD, ChromaText.MAX_SPREAD,
                            () -> cfg().itemOverlay.chromaUltimateSpread,
                            value -> { cfg().itemOverlay.chromaUltimateSpread = value; save(); }, " chars")
                            .describe("Characters per full rainbow on ultimates."),
                    SettingRow.rangeSlider("Ultimate Saturation", 0, 100,
                            () -> cfg().itemOverlay.chromaUltimateSaturation,
                            value -> { cfg().itemOverlay.chromaUltimateSaturation = value; save(); }, "%")
                            .describe("How colourful the ultimate sweep is."),
                    SettingRow.toggle("Flow Across Lore Lines",
                            () -> cfg().itemOverlay.chromaFlowAcrossLines,
                            () -> { cfg().itemOverlay.chromaFlowAcrossLines = !cfg().itemOverlay.chromaFlowAcrossLines; save(); })
                            .describe("Carries the gradient on down a multi-line enchant block, so "
                                    + "it reads as one plate scrolling behind the text instead of "
                                    + "each line running its own identical rainbow."),
                    SettingRow.label("§8Ultimates are identified from the item's own enchant data,"),
                    SettingRow.label("§8not from lore colours - so the split is always exact"),
                    SettingRow.toggle("Chroma on Maxed Skills (/pv)",
                            () -> cfg().itemOverlay.chromaMaxedSkills,
                            () -> { cfg().itemOverlay.chromaMaxedSkills = !cfg().itemOverlay.chromaMaxedSkills; save(); })
                            .describe("The same rainbow on maxed skills in the Player Viewer "
                                    + "(/pv)."),
                    SettingRow.label("§8Speed and colours are set once for all chroma,"),
                    SettingRow.label("§8on the Theme page"));

            case ModuleManager.CHAT_OPTIONS_ID -> List.of(
                    SettingRow.enumOptions("Number Format",
                            () -> sbs.modid.client.core.util.NumberTextFormat
                                    .orServer(cfg().chatOptions.numberFormat),
                            value -> {
                                cfg().chatOptions.numberFormat = value;
                                save();
                            }, v -> v.displayName())
                            .describe("How big numbers in chat are written: Server leaves them "
                                    + "alone, Shortened writes 12.7M, Full writes 12,700,000. "
                                    + "Dates, times and coordinates are never touched. The sidebar "
                                    + "has its own setting under Custom Scoreboard."),
                    SettingRow.label("§8Rewrites the server's own numbers - off by default"),
                    SettingRow.toggle("Copy Chat to Clipboard", () -> cfg().chatOptions.copyChatToClipboard,
                            () -> { cfg().chatOptions.copyChatToClipboard = !cfg().chatOptions.copyChatToClipboard; save(); })
                            .describe("Ctrl + left-click any chat message to copy its text to "
                                    + "your clipboard."),
                    SettingRow.label("Ctrl + left-click a message to copy it"),
                    SettingRow.toggle("Chat Message Selection", () -> cfg().chatOptions.chatSelection,
                            () -> { cfg().chatOptions.chatSelection = !cfg().chatOptions.chatSelection; save(); })
                            .describe("Select and copy several chat messages at once: right-click "
                                    + "copies one, Ctrl + right-click picks messages one by one, "
                                    + "Shift + right-click selects the whole range between."),
                    SettingRow.label("Right-click: copy  •  Ctrl + right: pick  •  Shift + right: range"),
                    SettingRow.toggle("Chat Coordinate Waypoints", () -> cfg().chatOptions.chatWaypoints,
                            () -> { cfg().chatOptions.chatWaypoints = !cfg().chatOptions.chatWaypoints; save(); })
                            .describe("When someone posts coordinates in chat (x: 187, y: 120, z: "
                                    + "-430 - or just three numbers), a marker appears at that "
                                    + "spot in the world."),
                    SettingRow.label("\"x: 187, y: 120, z: -430\" or \"187 120 -430\" in chat = a waypoint in the world"),
                    SettingRow.intField("Waypoint Lifetime", 0, 120,
                            () -> cfg().chatOptions.chatWaypointMinutes,
                            value -> { cfg().chatOptions.chatWaypointMinutes = value; save(); }, "min")
                            .describe("How many minutes such a marker lives. 0 keeps it until you "
                                    + "reach it or run /sbs waypoint clear."),
                    SettingRow.label("0 = keep until you reach it or run /sbs waypoint clear"),
                    SettingRow.toggle("IRC Chat", () -> cfg().chatOptions.ircEnabled,
                            () -> {
                                cfg().chatOptions.ircEnabled = !cfg().chatOptions.ircEnabled;
                                save();
                                // The IRC chat tab follows this switch. Without the refresh, a chat
                                // left on that tab stays filtered to IRC after IRC Chat goes off.
                                sbs.modid.client.social.chat.logic.ChatTabs.getInstance().refresh();
                            })
                            .describe("The SBS-wide chat channel: /sbs irc <message> talks to "
                                    + "every SBS user across all servers. Needs your licence "
                                    + "token.")
                            .licenced(),
                    SettingRow.label("/sbs irc <message>  •  needs an active licence token"),
                    SettingRow.options("IRC Filter", () -> List.of("Off", "Whitelist", "Blacklist"),
                            () -> switch (cfg().chatOptions.ircFilterMode == null
                                    ? "OFF" : cfg().chatOptions.ircFilterMode.toUpperCase(java.util.Locale.ROOT)) {
                                case "WHITELIST" -> "Whitelist";
                                case "BLACKLIST" -> "Blacklist";
                                default -> "Off";
                            },
                            picked -> {
                                cfg().chatOptions.ircFilterMode =
                                        picked.toUpperCase(java.util.Locale.ROOT);
                                save();
                            })
                            .describe("Who you see in the IRC channel: Off = everyone, Whitelist "
                                    + "= only the names on your list, Blacklist = everyone except "
                                    + "the names on your list. Click to cycle."),
                    SettingRow.button("Edit IRC Names", () -> open(
                            new sbs.modid.client.social.chat.ui.IrcNamesScreen()))
                            .describe("Opens the name list the IRC filter uses."),
                    SettingRow.label("Whitelist: only listed names  •  Blacklist: hide listed names"));

            // Two independent features that happen to share a trigger: each has its own toggle and
            // its own options, and neither reads the other's state.
            case ModuleManager.ETHER_WARP_ID -> List.of(
                    SettingRow.label("Only ever active with an Ethermerge AOTE / AOTV"),
                    SettingRow.toggle("Ether Warp Target Highlight",
                            () -> cfg().etherWarp.targetHighlight,
                            () -> { cfg().etherWarp.targetHighlight = !cfg().etherWarp.targetHighlight; save(); })
                            .describe("While you sneak with an Ethermerge AOTE/AOTV, the block "
                                    + "you would teleport to is boxed live - so you see where "
                                    + "you land before you click."),
                    SettingRow.enumOptions("Highlight Color", () -> cfg().etherWarp.highlightColor,
                            value -> { cfg().etherWarp.highlightColor = value; save(); }, v -> v.displayName())
                            .describe("The color of that landing box. Click to cycle."),
                    SettingRow.rangeSlider("Highlight Opacity", 10, 100,
                            () -> cfg().etherWarp.highlightOpacity,
                            value -> { cfg().etherWarp.highlightOpacity = value; save(); }, "%")
                            .describe("How solid the landing box is drawn."),
                    SettingRow.rangeSlider("Highlight Line Width", 1, 5,
                            () -> cfg().etherWarp.lineWidth,
                            value -> { cfg().etherWarp.lineWidth = value; save(); }, "px")
                            .describe("Thickness of the box outline in pixels."),
                    SettingRow.toggle("Mark Blocked Targets Red", () -> cfg().etherWarp.markInvalid,
                            () -> { cfg().etherWarp.markInvalid = !cfg().etherWarp.markInvalid; save(); })
                            .describe("Colors the box red when the warp would fail there (no "
                                    + "room, blocked target), so you do not waste the click."),
                    SettingRow.label("Sneak with the weapon: the landing block is boxed live"),
                    SettingRow.toggle("Ether Warp Zoom", () -> cfg().etherWarp.zoom,
                            () -> { cfg().etherWarp.zoom = !cfg().etherWarp.zoom; save(); })
                            .describe("Zooms in while you aim an ether warp, making far targets "
                                    + "hittable. Ends the moment you unsneak or switch items."),
                    SettingRow.rangeSlider("Zoom Strength",
                            sbs.modid.client.helper.visual.model.ZoomStrength.MIN,
                            sbs.modid.client.helper.visual.model.ZoomStrength.MAX,
                            () -> cfg().etherWarp.zoomStrength,
                            value -> { cfg().etherWarp.zoomStrength = value; save(); }, "%")
                            .describe("How far the aim zoom magnifies. You can also scroll while "
                                    + "aiming to adjust it on the fly."),
                    SettingRow.label("Higher = closer  •  scroll while aiming to adjust  •  ends on unsneak or item switch"),
                    SettingRow.toggle("Ether Warp Sensitivity", () -> cfg().etherWarp.reduceSensitivity,
                            () -> { cfg().etherWarp.reduceSensitivity = !cfg().etherWarp.reduceSensitivity; save(); })
                            .describe("Lowers your mouse sensitivity while aiming a warp, for "
                                    + "precise block picks at long range. Your normal Minecraft "
                                    + "sensitivity setting is never touched."),
                    SettingRow.rangeSlider("Sensitivity While Aiming",
                            sbs.modid.client.helper.etherwarp.EtherWarpSensitivity.MIN_PERCENT,
                            sbs.modid.client.helper.etherwarp.EtherWarpSensitivity.MAX_PERCENT,
                            () -> cfg().etherWarp.sensitivityPercent,
                            value -> { cfg().etherWarp.sensitivityPercent = value; save(); }, "%")
                            .describe("The aiming sensitivity as a percentage of your normal "
                                    + "one."),
                    SettingRow.keybind("Sensitivity Toggle Key", () -> cfg().etherWarp.sensitivityKey,
                            k -> { cfg().etherWarp.sensitivityKey = k; save(); })
                            .describe("A key that switches the aiming-sensitivity reduction on "
                                    + "and off in-game."),
                    SettingRow.label("% of your normal sensitivity  •  your saved setting is never changed"));

            // Two independent modes, one toggle each: the HUD overlay is view-only and never
            // interrupts play; the transparent screen is where items are actually clicked.
            case ModuleManager.INVENTORY_OVERLAY_ID -> List.of(
                    SettingRow.toggle("Inventory Overlay (HUD)", () -> cfg().inventoryOverlay.hudOverlay,
                            () -> { cfg().inventoryOverlay.hudOverlay = !cfg().inventoryOverlay.hudOverlay; save(); })
                            .describe("Draws your inventory's contents transparently above the "
                                    + "hotbar while you play - view-only, so you always know what "
                                    + "you are carrying without opening anything."),
                    SettingRow.label("Shows your inventory over the hotbar while you play"),
                    SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.INVENTORY_OVERLAY}, "Edit Inventory Overlay")))
                            .describe("Opens the editor where you drag the inventory overlay "
                                    + "anywhere on the screen and scale it."),
                    SettingRow.toggle("Transparent Inventory Screen",
                            () -> cfg().inventoryOverlay.transparentScreen,
                            () -> { cfg().inventoryOverlay.transparentScreen = !cfg().inventoryOverlay.transparentScreen; save(); })
                            .describe("Makes the real inventory screen see-through, so the game "
                                    + "stays visible behind it while you sort items."),
                    SettingRow.label("Opening the inventory keeps the game visible behind it"),
                    SettingRow.rangeSlider("Overlay Opacity",
                            sbs.modid.client.helper.inventory.ui.InventoryOverlay.MIN_OPACITY,
                            sbs.modid.client.helper.inventory.ui.InventoryOverlay.MAX_OPACITY,
                            () -> cfg().inventoryOverlay.opacity,
                            value -> { cfg().inventoryOverlay.opacity = value; save(); }, "%")
                            .describe("How solid the overlay and the transparent screen are "
                                    + "drawn."),
                    SettingRow.label("Both modes are independent  •  either works on its own"),
                    SettingRow.toggle("Inventory as a Window", () -> cfg().inventoryOverlay.windowed,
                            () -> { cfg().inventoryOverlay.windowed = !cfg().inventoryOverlay.windowed; save(); })
                            .describe("Gives the inventory a title bar: drag it to move the "
                                    + "inventory anywhere, click - to fold it away, double-click "
                                    + "the bar to put it back in the middle. Remembered across "
                                    + "restarts."),
                    SettingRow.segmented("Apply To", List.of("Inventory Only", "All Containers"),
                            () -> cfg().inventoryOverlay.windowScope,
                            value -> { cfg().inventoryOverlay.windowScope = value; save(); })
                            .describe("Only your own inventory, or every chest and SkyBlock menu "
                                    + "too. Menus share one remembered spot."),
                    SettingRow.button("Reset Window Positions",
                            sbs.modid.client.helper.inventory.ui.InventoryWindow::resetAll)
                            .describe("Puts the inventory and menus back in the middle of the "
                                    + "screen, unfolded."),
                    SettingRow.label("Drag the bar to move  •  - folds  •  double-click resets"));

            case ModuleManager.INVENTORY_BUTTONS_ID -> List.of(
                    SettingRow.toggle("Inventory Buttons", () -> cfg().inventoryButtons.enabled,
                            () -> { cfg().inventoryButtons.enabled = !cfg().inventoryButtons.enabled; save(); })
                            .describe("Your own buttons around the inventory screen, each running "
                                    + "a command of your choice - /storage, /wardrobe, /pets, one "
                                    + "click each."),
                    SettingRow.button("Edit Buttons", () -> open(
                            new sbs.modid.client.helper.inventorybuttons.ui.InventoryButtonsEditScreen()))
                            .describe("Opens the button editor: drag buttons into place (they "
                                    + "snap to the inventory edges) and set each one's name, "
                                    + "command and icon via its ... menu."),
                    SettingRow.label("Drag to place — buttons snap to the inventory edges"),
                    SettingRow.label("Click a button's … to set name, command and icon"),
                    SettingRow.label(inventoryButtonSummary()));

            case ModuleManager.SKILL_PROGRESS_ID -> List.of(
                    SettingRow.toggle("Skill Progress Overlay", () -> cfg().skillOverlay.enabled,
                            () -> { cfg().skillOverlay.enabled = !cfg().skillOverlay.enabled; save(); })
                            .describe("A card with the skill you are levelling right now: level, "
                                    + "progress and the numbers below. It figures out the skill "
                                    + "from the XP you gain and hides while nothing is "
                                    + "progressing."),
                    SettingRow.label("Detects the active skill automatically from the XP you gain"),
                    SettingRow.label("Only visible while a skill is actually progressing"),
                    SettingRow.toggle("Show XP Rate", () -> cfg().skillOverlay.showRate,
                            () -> { cfg().skillOverlay.showRate = !cfg().skillOverlay.showRate; save(); })
                            .describe("Adds an XP-per-hour line, measured from this session."),
                    SettingRow.toggle("Show Time To Next Level", () -> cfg().skillOverlay.showEta,
                            () -> { cfg().skillOverlay.showEta = !cfg().skillOverlay.showEta; save(); })
                            .describe("Adds a line estimating how long until the next level at "
                                    + "your current pace."),
                    SettingRow.toggle("Show Actions Remaining", () -> cfg().skillOverlay.showActions,
                            () -> { cfg().skillOverlay.showActions = !cfg().skillOverlay.showActions; save(); })
                            .describe("Adds a line with how many more actions (crops broken, ores "
                                    + "mined...) the next level needs."),
                    SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.SKILL_PROGRESS}, "Edit Skill Progress")))
                            .describe("Opens the editor where you drag the skill card anywhere on "
                                    + "the screen and scale it."),
                    SettingRow.label("Needs Hypixel's action-bar XP progress to be visible"));

            case ModuleManager.SLOT_LOCK_ID -> List.of(
                    SettingRow.toggle("Slot Lock", () -> cfg().slotLock.enabled,
                            () -> { cfg().slotLock.enabled = !cfg().slotLock.enabled; save(); })
                            .describe("Lock inventory slots so their items cannot be moved, "
                                    + "dropped, swapped or replaced by accident. A locked hotbar "
                                    + "item can still be selected and used - just not lost."),
                    SettingRow.holdKeybind("Lock Key (hold + left click)", () -> cfg().slotLock.lockKey,
                            key -> { cfg().slotLock.lockKey = key; save(); })
                            .describe("Hold this key and left-click a slot to lock or unlock it. "
                                    + "A padlock marks locked slots. A mouse button works too; the "
                                    + "wheel does not, because it cannot be held."),
                    SettingRow.holdKeybind("Swap Key (hold to move locked items)", () -> cfg().slotLock.swapKey,
                            key -> { cfg().slotLock.swapKey = key; save(); })
                            .describe("Hold this key to move locked items anyway - for "
                                    + "deliberately reorganising without unlocking everything "
                                    + "first. A mouse button works too; the wheel does not, "
                                    + "because it cannot be held."),
                    SettingRow.label("Hold the key and left-click a slot to lock / unlock it"),
                    SettingRow.label("Locked items can't be moved, dropped, swapped or replaced"),
                    SettingRow.label("Hotbar locks still let you select and use the item"),
                    SettingRow.toggle("Block Q-Drop From Hotbar", () -> cfg().slotLock.blockHotbarDrop,
                            () -> { cfg().slotLock.blockHotbarDrop = !cfg().slotLock.blockHotbarDrop; save(); })
                            .describe("Also swallows the drop key (Q) for locked hotbar items "
                                    + "while playing - the classic way a weapon gets thrown into "
                                    + "lava mid-fight. Never inside a dungeon, where that key is "
                                    + "your class ability and dropping is off anyway."),
                    SettingRow.label("Stops the drop key (Q) throwing a locked hotbar item while playing"),
                    SettingRow.toggle("Sound When Blocked", () -> cfg().slotLock.denySound,
                            () -> { cfg().slotLock.denySound = !cfg().slotLock.denySound; save(); })
                            .describe("A short sound when a click is blocked by a lock, so you "
                                    + "know the lock did something."),
                    SettingRow.toggle("Message When Blocked", () -> cfg().slotLock.denyMessage,
                            () -> { cfg().slotLock.denyMessage = !cfg().slotLock.denyMessage; save(); })
                            .describe("A chat line when a click is blocked by a lock."),
                    SettingRow.button("Unlock All Slots", () -> {
                        cfg().slotLock.lockedSlots.clear();
                        save();
                    })
                            .describe("Removes every slot lock at once."),
                    SettingRow.label(lockedSlotSummary()),

                    SettingRow.label("— Rarity Drop Protection —"),
                    SettingRow.toggle("Protect Rare Drops", () -> cfg().slotLock.rarityProtect,
                            () -> { cfg().slotLock.rarityProtect = !cfg().slotLock.rarityProtect; save(); })
                            .describe("Protects items by rarity instead of by slot: anything at "
                                    + "or above the chosen rarity cannot be dropped by accident, "
                                    + "wherever it sits. The in-world drop key is left alone "
                                    + "inside dungeons, where it triggers your class ability."),
                    SettingRow.options("Protected Rarity",
                            sbs.modid.client.helper.inventory.logic.DropProtection::thresholdOptions,
                            sbs.modid.client.helper.inventory.logic.DropProtection::thresholdLabel,
                            sbs.modid.client.helper.inventory.logic.DropProtection::setThresholdByLabel)
                            .describe("The lowest rarity that is protected - this one and "
                                    + "everything above it. Click to cycle."),
                    SettingRow.options("On Drop Attempt",
                            () -> List.of("Press 3× to drop", "Block entirely"),
                            () -> cfg().slotLock.rarityTriplePress ? "Press 3× to drop" : "Block entirely",
                            picked -> {
                                cfg().slotLock.rarityTriplePress = "Press 3× to drop".equals(picked);
                                save();
                            })
                            .describe("What happens when you try to drop a protected item: "
                                    + "pressing drop three times quickly lets it through, or the "
                                    + "drop is blocked entirely. Click to switch."),
                    SettingRow.label("Items of that rarity and above can't be dropped by accident"));

            case ModuleManager.FARMING_ID -> concat(List.of(
                    SettingRow.toggle("Only On Farming Islands", () -> cfg().farming.islandLock,
                            () -> { cfg().farming.islandLock = !cfg().farming.islandLock; save(); })
                            .describe("Keeps every farming feature below quiet unless you are on "
                                    + "a farming island (Garden, Farming Islands...)."),
                    SettingRow.label("Every farming feature below stays off anywhere else"),
                    SettingRow.label("§8" + sbs.modid.client.skills.SkillIslands.describe(
                            sbs.modid.client.skills.SkillIslands.FARMING_ISLANDS)),

                    SettingRow.keybind("Mouse Lock Toggle", () -> cfg().farming.mouseLockKey,
                            key -> { cfg().farming.mouseLockKey = key; save(); })
                            .describe("A key that freezes your camera rotation - your aim cannot "
                                    + "drift while you hold a farming line for minutes. Press "
                                    + "again to unfreeze."),
                    SettingRow.label("Press the key in-game to freeze/unfreeze camera rotation"),
                    SettingRow.toggle("Reduce Sensitivity While Farming",
                            () -> cfg().farming.reduceSensitivity,
                            () -> { cfg().farming.reduceSensitivity = !cfg().farming.reduceSensitivity; save(); })
                            .describe("Lowers your mouse sensitivity while a farming tool is "
                                    + "held, for precise straight lines. Restores itself the "
                                    + "moment you switch items - your Minecraft setting is never "
                                    + "changed."),
                    SettingRow.rangeSlider("Farming Sensitivity",
                            sbs.modid.client.skills.farming.logic.FarmingSensitivity.MIN_PERCENT,
                            sbs.modid.client.skills.farming.logic.FarmingSensitivity.MAX_PERCENT,
                            () -> cfg().farming.sensitivityPercent,
                            value -> { cfg().farming.sensitivityPercent = value; save(); }, "%")
                            .describe("The farming sensitivity as a percentage of your normal "
                                    + "one."),
                    SettingRow.label("% of your normal sensitivity while a farming tool is held"),
                    SettingRow.label("Restores itself instantly when you switch item"),

                    SettingRow.label("— Crop Milestones —"),
                    SettingRow.toggle("Milestone Card", () -> cfg().farming.cropMilestone,
                            () -> { cfg().farming.cropMilestone = !cfg().farming.cropMilestone; save(); })
                            .describe("A card with the milestone of the crop you are farming: "
                                    + "tier, progress bar and crops per minute. Exact numbers "
                                    + "need a tool with a counter or the Cultivating enchant."),
                    SettingRow.label("Tier, progress and crops/min for the crop you are farming"),
                    SettingRow.label("Needs a tool with a counter or Cultivating for exact numbers"),
                    SettingRow.toggle("Show ETA", () -> cfg().farming.milestoneEta,
                            () -> { cfg().farming.milestoneEta = !cfg().farming.milestoneEta; save(); })
                            .describe("Adds a line estimating how long until the next milestone "
                                    + "tier at your current pace."),
                    SettingRow.toggle("Show Coins/Hour", () -> cfg().farming.milestoneProfit,
                            () -> { cfg().farming.milestoneProfit = !cfg().farming.milestoneProfit; save(); })
                            .describe("Adds a line with what your farming earns per hour at "
                                    + "current prices."),
                    SettingRow.button("Reset Session", () ->
                            sbs.modid.client.skills.farming.logic.CropMilestoneTracker.getInstance().reset())
                            .describe("Restarts the crops/min and coins/hour measurement from "
                                    + "zero."),
                    SettingRow.button("Move / Resize Milestone Card", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.CROP_MILESTONE}, "Edit Crop Milestone Card")))
                            .describe("Opens the editor where you drag the milestone card "
                                    + "anywhere on the screen and scale it."),

                    SettingRow.label("— Farming Fortune —"),
                    SettingRow.toggle("Fortune Card", () -> cfg().farming.farmingFortune,
                            () -> { cfg().farming.farmingFortune = !cfg().farming.farmingFortune; save(); })
                            .describe("A card with your effective Farming Fortune for the held "
                                    + "tool's crop: the tab-list fortune plus the tool's "
                                    + "crop-specific bonuses."),
                    SettingRow.label("Tab fortune plus the held tool's crop-specific fortune"),
                    SettingRow.button("Move / Resize Fortune Card", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.FARMING_FORTUNE}, "Edit Farming Fortune Card")))
                            .describe("Opens the editor where you drag the fortune card anywhere "
                                    + "on the screen and scale it."),

                    SettingRow.label("— Hoe Levels —"),
                    SettingRow.toggle("Hoe Level Card", () -> cfg().farming.hoeLevels,
                            () -> { cfg().farming.hoeLevels = !cfg().farming.hoeLevels; save(); })
                            .describe("A card with the held farming tool's level and its progress "
                                    + "bar to the next one."),
                    SettingRow.toggle("Show Overflow", () -> cfg().farming.hoeOverflow,
                            () -> { cfg().farming.hoeOverflow = !cfg().farming.hoeOverflow; save(); })
                            .describe("Keeps counting tool XP past the maximum level."),
                    SettingRow.toggle("Mute Level-Up Sound", () -> cfg().farming.muteHoeSounds,
                            () -> { cfg().farming.muteHoeSounds = !cfg().farming.muteHoeSounds; save(); })
                            .describe("Silences the tool's level-up jingle - it plays a lot "
                                    + "during a long farming session."),
                    SettingRow.button("Move / Resize Hoe Card", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.HOE_LEVEL}, "Edit Hoe Level Card")))
                            .describe("Opens the editor where you drag the hoe card anywhere on "
                                    + "the screen and scale it.")),
                    farmingSpeedRows(),
                    laneEndRows(),
                    jacobContestRows(),
                    mousematRows(),
                    farmDropRows());

            case ModuleManager.MINECRAFT_OVERLAY_ID -> List.of(
                    SettingRow.toggle("Toggle SBS Overlay", () -> cfg().minecraftOverlay.enabled,
                            () -> { cfg().minecraftOverlay.enabled = !cfg().minecraftOverlay.enabled; save(); })
                            .describe("Repaints Minecraft's own screens and the hotbar in the SBS "
                                    + "design - dark panels, rounded corners, the theme's "
                                    + "colors."),

                    SettingRow.toggle("Theme Other Mods' Buttons",
                            () -> cfg().minecraftOverlay.themeOtherMods,
                            () -> {
                                cfg().minecraftOverlay.themeOtherMods =
                                        !cfg().minecraftOverlay.themeOtherMods;
                                save();
                            })
                            .describe("Repaints buttons belonging to OTHER mods in the SBS design too, "
                                    + "and a recipe book another mod puts beside your inventory in "
                                    + "place of Minecraft's (its tabs, tiles, page arrows and search "
                                    + "field; its own artwork stays as it is). "
                                    + "Off by default because it can make some mods' screens hard to "
                                    + "use - turn it on and check the mods you actually run."),
                    SettingRow.label("§8Off = other mods keep their own look."),

                    SettingRow.toggle("SBS Overlays On Top",
                            () -> cfg().minecraftOverlay.overlaysOnTop,
                            () -> {
                                cfg().minecraftOverlay.overlaysOnTop =
                                        !cfg().minecraftOverlay.overlaysOnTop;
                                save();
                            })
                            .describe("Draws the mod's own panels, windows and popups above every "
                                    + "other mod's, instead of leaving it to chance which one lands "
                                    + "on top. Turn it off to let another mod's overlay show over "
                                    + "ours."),
                    SettingRow.label("§8Off = whichever mod happens to draw last wins."),

                    SettingRow.label("— Title Screen —"),
                    SettingRow.toggle("SBS Title Screen", () -> cfg().minecraftOverlay.titleScreenBranding,
                            () -> {
                                cfg().minecraftOverlay.titleScreenBranding =
                                        !cfg().minecraftOverlay.titleScreenBranding;
                                if (!cfg().minecraftOverlay.titleScreenBranding) {
                                    sbs.modid.client.ui.titlescreen.TitleBranding.discard();
                                }
                                save();
                            })
                            .describe("Replaces the Minecraft logo on the main menu with the SBS "
                                    + "banner. Purely cosmetic."),
                    SettingRow.label("SBS brand banner around the main menu instead of the Minecraft logo"));

            case ModuleManager.VISUALS_ID -> List.of(
                    SettingRow.toggle("Borderless Window", () -> cfg().visuals.borderlessWindow,
                            sbs.modid.client.helper.visual.logic.BorderlessWindow::toggle)
                            .describe("Stretches the game window over the whole monitor without a "
                                    + "title bar - like fullscreen, but alt-tab is instant. Leave "
                                    + "F11 fullscreen before turning it on."),
                    SettingRow.label("Undecorated window over the whole monitor - leave F11 fullscreen first"),

                    SettingRow.toggle("See-Through Window", () -> cfg().visuals.opacityEnabled,
                            () -> {
                                cfg().visuals.opacityEnabled = !cfg().visuals.opacityEnabled;
                                save();
                                // Switching it off has to hand the window back straight away rather
                                // than at the next tick that happens to notice.
                                sbs.modid.client.helper.visual.logic.WindowOpacity.reset();
                            })
                            .describe("A key that makes the whole Minecraft window semi-transparent, "
                                    + "so you can read whatever is behind it - a guide, a video, a "
                                    + "spreadsheet - without alt-tabbing out of the game. This is "
                                    + "the real window, not a dark tint: you see your desktop "
                                    + "through it. Off by default, and does nothing until you bind "
                                    + "a key below.")
                            .disabledIf(!sbs.modid.client.helper.visual.logic.WindowOpacity.supported()),
                    SettingRow.keybind("See-Through Key", () -> cfg().visuals.opacityKey,
                            key -> {
                                cfg().visuals.opacityKey = key;
                                save();
                                sbs.modid.client.helper.visual.logic.WindowOpacity.reset();
                            })
                            .describe("The key that makes the window see-through. A mouse button "
                                    + "works too.")
                            .disabledIf(!sbs.modid.client.helper.visual.logic.WindowOpacity.supported()),
                    SettingRow.segmented("See-Through Mode",
                            java.util.List.of("Hold", "Press"),
                            () -> cfg().visuals.opacityHold ? 0 : 1,
                            index -> {
                                cfg().visuals.opacityHold = index == 0;
                                save();
                                sbs.modid.client.helper.visual.logic.WindowOpacity.reset();
                            })
                            .describe("Hold: the window is see-through only while you hold the key "
                                    + "down, and solid again the moment you let go. Press: one press "
                                    + "makes it see-through and stays that way until you press "
                                    + "again. Hold is the default - it cannot be left on by "
                                    + "accident.")
                            .disabledIf(!sbs.modid.client.helper.visual.logic.WindowOpacity.supported()),
                    SettingRow.rangeSlider("See-Through Amount",
                            SBSConfig.VisualsSettings.MIN_WINDOW_OPACITY, 100,
                            () -> cfg().visuals.opacityPercent,
                            value -> {
                                cfg().visuals.opacityPercent = value;
                                save();
                                // Live, so dragging the slider IS the preview while the key is held.
                                sbs.modid.client.helper.visual.logic.WindowOpacity.refresh();
                            }, "%")
                            .describe("How solid the window stays: 100% is normal, lower is more "
                                    + "see-through. It stops at "
                                    + SBSConfig.VisualsSettings.MIN_WINDOW_OPACITY + "% on purpose "
                                    + "- a window faded to nothing is one you cannot find again to "
                                    + "turn it back. Default 50%.")
                            .disabledIf(!sbs.modid.client.helper.visual.logic.WindowOpacity.supported()),
                    SettingRow.label(sbs.modid.client.helper.visual.logic.WindowOpacity.statusLine()),

                    SettingRow.toggle("Custom Title Bar", () -> cfg().visuals.titleBar,
                            sbs.modid.client.helper.visual.logic.WindowTitleBar::toggle)
                            .describe("Colors the Windows title bar - the strip above the game with "
                                    + "the minimize, maximize and close buttons - instead of "
                                    + "leaving it system white. Needs Windows 11; there is no "
                                    + "title bar to color in fullscreen or Borderless Window."),
                    SettingRow.color("Title Bar Color", () -> cfg().visuals.titleBarColorHex,
                            () -> SBSTheme.PANEL_FILL_TOP,
                            () -> openTitleBarPicker("Background", () -> cfg().visuals.titleBarColorHex,
                                    SBSTheme.PANEL_FILL_TOP, v -> cfg().visuals.titleBarColorHex = v))
                            .describe("The background of the title bar itself. Leave it unset and it "
                                    + "follows your SBS theme, so the window frame matches the mod."),
                    SettingRow.color("Title Text Color", () -> cfg().visuals.titleBarTextHex,
                            () -> SBSTheme.TEXT,
                            () -> openTitleBarPicker("Text", () -> cfg().visuals.titleBarTextHex,
                                    SBSTheme.TEXT, v -> cfg().visuals.titleBarTextHex = v))
                            .describe("The color of the window title written on that bar - SBS blue "
                                    + "out of the box. This is the title text only; the minimize, "
                                    + "maximize and close symbols are Windows' own and have their "
                                    + "own row below."),
                    SettingRow.color("Window Border Color", () -> cfg().visuals.titleBarBorderHex,
                            () -> SBSTheme.ACCENT,
                            () -> openTitleBarPicker("Border", () -> cfg().visuals.titleBarBorderHex,
                                    SBSTheme.ACCENT, v -> cfg().visuals.titleBarBorderHex = v))
                            .describe("The thin frame Windows draws around the whole game window - "
                                    + "SBS blue out of the box, so the window is outlined in the "
                                    + "mod's color."),
                    SettingRow.enumOptions("Window Buttons", () -> cfg().visuals.titleBarButtons,
                            value -> {
                                cfg().visuals.titleBarButtons = value;
                                save();
                                sbs.modid.client.helper.visual.logic.WindowTitleBar.refresh();
                            }, v -> v.displayName())
                            .describe("The minimize, maximize and close symbols. Auto reads your "
                                    + "Title Bar Color and picks whichever stays readable on it, "
                                    + "so they follow your theme; White and Black pin them. Click "
                                    + "to cycle. Those two shades are all Windows offers for these "
                                    + "symbols - it draws them itself and lets no program color "
                                    + "them, unlike the bar behind them."),
                    SettingRow.button("Reset Title Bar Colors",
                            sbs.modid.client.helper.visual.logic.WindowTitleBar::resetColors)
                            .describe("Puts the three colors back to stock: SBS blue title text and "
                                    + "window frame, over a title bar that follows your theme. The "
                                    + "window buttons go back to Auto."),
                    SettingRow.label(sbs.modid.client.helper.visual.logic.WindowTitleBar.statusLine()),
                    SettingRow.toggle("Custom Window Title",
                            () -> cfg().visuals.customWindowTitle,
                            () -> {
                                cfg().visuals.customWindowTitle = !cfg().visuals.customWindowTitle;
                                save();
                                sbs.modid.client.helper.visual.logic.WindowTitleText.apply();
                            })
                            .describe("Writes the text in the window's title bar and taskbar entry "
                                    + "yourself instead of leaving it to Minecraft. On by default, "
                                    + "reading \"Minecraft[Skyblock Simplified Mod]*\" and the game "
                                    + "version. Switch it off for Minecraft's own title."),
                    SettingRow.text("Window Title", WindowTitleText.DEFAULT_TEMPLATE, 120,
                            () -> cfg().visuals.windowTitle,
                            value -> {
                                cfg().visuals.windowTitle = value;
                                save();
                                sbs.modid.client.helper.visual.logic.WindowTitleText.apply();
                            })
                            .describe("What the title says. Anything in braces is filled in when "
                                    + "the title is drawn: {mc} is the Minecraft version, {mod} the "
                                    + "mod's name, {modversion} its version, and {vanilla} whatever "
                                    + "Minecraft would have shown. Because the version is filled in "
                                    + "rather than typed, the title stays right after the game "
                                    + "updates. Empty leaves Minecraft's title alone."),
                    SettingRow.label(windowTitleValues()),
                    SettingRow.label("§8The real title bar updates as you type - that is the preview"),
                    SettingRow.button("Reset Window Title", () -> {
                        cfg().visuals.windowTitle = WindowTitleText.DEFAULT_TEMPLATE;
                        save();
                        sbs.modid.client.helper.visual.logic.WindowTitleText.apply();
                    })
                            .describe("Puts the title back to \"Minecraft[Skyblock Simplified "
                                    + "Mod]*\" and the game version."),
                    SettingRow.holdKeybind("Zoom Key (hold)", () -> cfg().visuals.zoomKey,
                            value -> { cfg().visuals.zoomKey = value; save(); })
                            .describe("Hold this key to zoom your view in, spyglass-style, without "
                                    + "any item. Does not fire while chat or a menu is open. A "
                                    + "mouse button works too; the wheel does not, because it "
                                    + "cannot be held."),
                    SettingRow.rangeSlider("Zoom Strength",
                            sbs.modid.client.helper.visual.model.ZoomStrength.MIN,
                            sbs.modid.client.helper.visual.model.ZoomStrength.MAX,
                            () -> cfg().visuals.zoomStrength,
                            value -> { cfg().visuals.zoomStrength = value; save(); }, "%")
                            .describe("How far the zoom magnifies. You can also scroll while "
                                    + "holding the key to adjust on the fly."),
                    SettingRow.toggle("Smooth Zoom", () -> cfg().visuals.zoomAnimation,
                            () -> { cfg().visuals.zoomAnimation = !cfg().visuals.zoomAnimation; save(); })
                            .describe("Glides into and out of the zoom instead of switching the "
                                    + "view over on a single frame. Off = the old instant snap."),
                    SettingRow.rangeSlider("Smooth Zoom Speed",
                            sbs.modid.client.helper.visual.model.ZoomTransition.MIN_SPEED,
                            sbs.modid.client.helper.visual.model.ZoomTransition.MAX_SPEED,
                            () -> cfg().visuals.zoomSpeed,
                            value -> { cfg().visuals.zoomSpeed = value; save(); }, "%")
                            .describe("How fast that glide runs. 100% is about a fifth of a second "
                                    + "for a full zoom; lower is slower, higher is snappier."),
                    SettingRow.label("Hold the key to zoom  •  higher = closer  •  scroll while held to adjust"),
                    SettingRow.enumOptions("Fire Overlay Height", () -> cfg().visuals.fireOverlay,
                            value -> { cfg().visuals.fireOverlay = value; save(); }, v -> v.displayName())
                            .describe("Shrinks or hides the flame effect that covers your screen "
                                    + "while burning - in the Crimson Isle you burn a lot, and "
                                    + "the vanilla flames hide half the fight. Click to cycle."),
                    SettingRow.enumOptions("Explosion Opacity", () -> cfg().visuals.explosion,
                            value -> { cfg().visuals.explosion = value; save(); }, v -> v.displayName())
                            .describe("Fades or hides explosion particles - dungeon runs are full "
                                    + "of them and they cover everything. Click to cycle."),
                    SettingRow.enumOptions("Hide Potion Effects", () -> cfg().visuals.potionParticles,
                            value -> { cfg().visuals.potionParticles = value; save(); }, v -> v.displayName())
                            .describe("Hides the swirly particles from potion effects - your own, "
                                    + "everyone's, or none. Click to cycle."),
                    SettingRow.toggle("Hide Falling Blocks", () -> cfg().visuals.hideFallingBlocks,
                            () -> { cfg().visuals.hideFallingBlocks = !cfg().visuals.hideFallingBlocks; save(); })
                            .describe("Stops drawing falling sand, gravel and dungeon terracotta. "
                                    + "The blocks still land normally - you just do not see the "
                                    + "rain of them (Sadan's terracotta phase)."),
                    SettingRow.label("Sand, gravel and dungeon terracotta stop being drawn - they still land"),
                    SettingRow.toggle("Hide Dragon Death Animation", () -> cfg().visuals.hideDragonDeath,
                            () -> { cfg().visuals.hideDragonDeath = !cfg().visuals.hideDragonDeath; save(); })
                            .describe("A dying dragon and its light beams vanish instantly "
                                    + "instead of playing the long death animation - in M7 that "
                                    + "animation blocks the view of the next phase."),
                    SettingRow.label("A dying dragon and its beams of light vanish instantly (M7)"),
                    SettingRow.toggle("End Block Glow", () -> cfg().visuals.endBlockGlow,
                            () -> { cfg().visuals.endBlockGlow = !cfg().visuals.endBlockGlow; save(); })
                            .describe("Makes purple and pink blocks light themselves up while you "
                                    + "are on The End, so the structures you navigate by stand out "
                                    + "instead of blending into the end stone. Only runs there."),
                    SettingRow.rangeSlider("End Glow Intensity", 10, 100,
                            () -> cfg().visuals.endGlowIntensity,
                            value -> { cfg().visuals.endGlowIntensity = value; save(); }, "%")
                            .describe("How strongly those blocks glow - a hint of extra light at "
                                    + "10%, fully self-lit and vividly coloured at 100%. Where the "
                                    + "End is already fully lit, only the colour can still change."),
                    SettingRow.label("Purple, magenta and pink blocks glow - The End only"),
                    SettingRow.toggle("Dark End Blocks", () -> cfg().visuals.darkEndBlocks,
                            () -> { cfg().visuals.darkEndBlocks = !cfg().visuals.darkEndBlocks; save(); })
                            .describe("Strips the yellow out of the pale blocks The End is built "
                                    + "out of - end stone, sand, birch, quartz - and darkens them, "
                                    + "the look of the dark-end resource packs, so hours of Zealots "
                                    + "are not spent staring at bright yellow. Nothing is downloaded "
                                    + "and your own resource pack is left alone."),
                    SettingRow.rangeSlider("End Darkness", 0, 100,
                            () -> cfg().visuals.darkEndStrength,
                            value -> { cfg().visuals.darkEndStrength = value; save(); }, "%")
                            .describe("Reverse brightness for those blocks. The yellow is always "
                                    + "fully removed; this picks how dark the grey is - natural "
                                    + "brightness at 0%, pitch black at 100%."),
                    SettingRow.label("End stone, sand and birch stop glaring - The End only"),
                    SettingRow.toggle("Dim Ghosts", () -> cfg().visuals.dimGhosts,
                            () -> { cfg().visuals.dimGhosts = !cfg().visuals.dimGhosts; save(); })
                            .describe("Turns down the searing white aura of the ghosts in The Mist "
                                    + "- the pit below the Dwarven Mines - so a grinding session "
                                    + "stops being a staring contest with a light bulb. Only runs "
                                    + "there."),
                    SettingRow.rangeSlider("Ghost Brightness", 5, 100,
                            () -> cfg().visuals.ghostBrightness,
                            value -> { cfg().visuals.ghostBrightness = value; save(); }, "%")
                            .describe("How bright the aura stays - 100% is vanilla's full glare, "
                                    + "5% leaves only a faint shape. 40% keeps the ghosts easy to "
                                    + "spot without searing."),
                    SettingRow.toggle("Dim Mist Blocks", () -> cfg().visuals.dimMistBlocks,
                            () -> { cfg().visuals.dimMistBlocks = !cfg().visuals.dimMistBlocks; save(); })
                            .describe("Darkens the snow, white glass and the other white blocks "
                                    + "the ghost pit is built out of, so the floor stops glaring "
                                    + "back while you grind. The Mist only - the rest of the "
                                    + "Dwarven Mines keeps its snow."),
                    SettingRow.rangeSlider("Mist Darkness", 0, 100,
                            () -> cfg().visuals.mistDarkness,
                            value -> { cfg().visuals.mistDarkness = value; save(); }, "%")
                            .describe("Reverse brightness for those blocks, on the same scale as "
                                    + "End Darkness: natural brightness at 0%, pitch black at "
                                    + "100%."),
                    SettingRow.label("Ghost aura and white pit blocks stop glaring - The Mist only"),
                    SettingRow.toggle("Text Editor", () -> cfg().visuals.textEditorEnabled,
                            () -> { cfg().visuals.textEditorEnabled = !cfg().visuals.textEditorEnabled; save(); })
                            .describe("A small in-game notepad for scratch text - trade offers, "
                                    + "coordinates, to-do lists - that survives restarts."),
                    SettingRow.button("Open Text Editor", () -> open(new TextEditorScreen()))
                            .describe("Opens that notepad."));

            case ModuleManager.THEME_ID -> concat(List.of(
                    SettingRow.enumOptions("UI Style",
                            () -> sbs.modid.client.ui.theme.UiStyle.parse(cfg().theme.style),
                            value -> {
                                cfg().theme.style = value.name();
                                save();
                                themeRefresh();
                            }, v -> v.displayName())
                            .describe("The look of every SBS screen, window and HUD card. Classic "
                                    + "(rounded, gradients), Futuristic (square windows, flat "
                                    + "cards), Medieval (oak and iron), Vanilla (a stock "
                                    + "Minecraft screen), Steampunk (brass and rivets), Magical "
                                    + "(violet and gold runes), Bug (flat black boxes with grey "
                                    + "outlines), OG (beveled plates and drop shadows) or Glass "
                                    + "(see-through panes with a single hairline edge)."),
                    SettingRow.rangeSlider("Surface Opacity",
                            sbs.modid.client.ui.theme.SBSTheme.MIN_SURFACE_OPACITY, 100,
                            () -> cfg().theme.surfaceOpacity,
                            value -> {
                                cfg().theme.surfaceOpacity = value;
                                save();
                                themeRefresh();
                            }, "%")
                            .describe("How solid every SBS panel, card and HUD surface is, on top "
                                    + "of the style above - 100% is the style as designed, lower "
                                    + "lets the game show through it. Works on all nine styles and "
                                    + "keeps each one's own shading, so a faded Bug is still black "
                                    + "boxes and a faded Glass is still glass. Text, icons and the "
                                    + "health / mana colours never fade; outlines fade half as far. "
                                    + "At 0% the HUD's panels are gone entirely and only their "
                                    + "readouts are left over the game - screens stop at "
                                    + sbs.modid.client.ui.theme.SBSTheme.MIN_SCREEN_OPACITY
                                    + "% so this slider stays findable. Default 100%. Tooltips keep "
                                    + "their own opacity - Minecraft draws those from an image."),
                    SettingRow.label("§8Ctrl + mouse wheel over any screen gives it its own value")),
                    screenOpacityRows(),
                    List.of(
                    SettingRow.label("Medieval / Vanilla / Steampunk / Magical / Bug / OG bring"),
                    SettingRow.label("their own colours - pick your own below and yours win"),
                    SettingRow.label("Three base colours drive every SBS surface;"),
                    SettingRow.label("gradients and hover shades derive automatically"),
                    SettingRow.text("Accent Hex", "RRGGBB", 7,
                            () -> cfg().theme.accentHex,
                            v -> { cfg().theme.accentHex = v.trim(); save(); themeRefresh(); })
                            .describe("The theme's accent color (buttons, highlights, active "
                                    + "elements) as a hex code. Every shade of it is derived "
                                    + "automatically."),
                    SettingRow.button("Pick Accent...", () -> openPicker("Accent",
                            () -> cfg().theme.accentHex, v -> cfg().theme.accentHex = v))
                            .describe("Opens a color picker for the accent color, if typing hex "
                                    + "codes is not your thing."),
                    SettingRow.text("Background Hex", "RRGGBB", 7,
                            () -> cfg().theme.backgroundHex,
                            v -> { cfg().theme.backgroundHex = v.trim(); save(); themeRefresh(); })
                            .describe("The theme's panel background color as a hex code."),
                    SettingRow.button("Pick Background...", () -> openPicker("Background",
                            () -> cfg().theme.backgroundHex, v -> cfg().theme.backgroundHex = v))
                            .describe("Opens a color picker for the background color."),
                    SettingRow.text("Text Hex", "RRGGBB", 7,
                            () -> cfg().theme.textHex,
                            v -> { cfg().theme.textHex = v.trim(); save(); themeRefresh(); })
                            .describe("The theme's text color as a hex code."),
                    SettingRow.button("Pick Text...", () -> openPicker("Text",
                            () -> cfg().theme.textHex, v -> cfg().theme.textHex = v))
                            .describe("Opens a color picker for the text color."),
                    SettingRow.enumOptions("Chroma Palette",
                            () -> sbs.modid.client.helper.visual.render.ChromaPalette
                                    .parse(cfg().theme.chromaPalette),
                            value -> {
                                cfg().theme.chromaPalette = value.name();
                                save();
                            }, v -> v.displayName())
                            .describe("Which colours every chroma effect sweeps through - the "
                                    + "maxed-enchant shimmer in tooltips, the maxed pet bar and the "
                                    + "profile viewer's maxed rows, all at once. Rainbow is every "
                                    + "hue; Theme Accent shimmers around your own accent colour; "
                                    + "Fire, Ocean, Aurora, Sunset, Candy and Gold are fixed "
                                    + "schemes; Custom uses the four colours below."),
                    SettingRow.rangeSlider("Chroma Speed",
                            sbs.modid.client.helper.visual.render.Chroma.MIN_SPEED,
                            sbs.modid.client.helper.visual.render.Chroma.MAX_SPEED,
                            () -> cfg().theme.chromaSpeed,
                            value -> { cfg().theme.chromaSpeed = value; save(); }, "%")
                            .describe("How fast every chroma effect cycles. 100% is one full sweep "
                                    + "per about 1.6 seconds; lower is calmer, higher is faster. "
                                    + "One speed for all of them on purpose - the animation runs "
                                    + "off the clock, so effects sharing a speed stay in step "
                                    + "wherever they appear together."),
                    SettingRow.label("Palette and speed apply to every chroma effect at once"),
                    SettingRow.label("§8Colours are nudged into a readable brightness range, so no"),
                    SettingRow.label("§8part of a sweep vanishes into the panel behind it"),
                    SettingRow.text("Chroma 1 Hex", "RRGGBB", 7,
                            () -> cfg().theme.chromaCustom1,
                            v -> { cfg().theme.chromaCustom1 = v.trim(); save(); })
                            .describe("First stop of the Custom chroma palette. Only used while "
                                    + "the palette above is set to Custom."),
                    SettingRow.button("Pick Chroma 1...", () -> openPicker("Chroma 1",
                            () -> cfg().theme.chromaCustom1, v -> cfg().theme.chromaCustom1 = v))
                            .describe("Opens a color picker for the first Custom chroma stop."),
                    SettingRow.text("Chroma 2 Hex", "RRGGBB", 7,
                            () -> cfg().theme.chromaCustom2,
                            v -> { cfg().theme.chromaCustom2 = v.trim(); save(); })
                            .describe("Second stop of the Custom chroma palette."),
                    SettingRow.button("Pick Chroma 2...", () -> openPicker("Chroma 2",
                            () -> cfg().theme.chromaCustom2, v -> cfg().theme.chromaCustom2 = v))
                            .describe("Opens a color picker for the second Custom chroma stop."),
                    SettingRow.text("Chroma 3 Hex", "RRGGBB", 7,
                            () -> cfg().theme.chromaCustom3,
                            v -> { cfg().theme.chromaCustom3 = v.trim(); save(); })
                            .describe("Third stop of the Custom chroma palette. Leave blank to use "
                                    + "fewer colours."),
                    SettingRow.button("Pick Chroma 3...", () -> openPicker("Chroma 3",
                            () -> cfg().theme.chromaCustom3, v -> cfg().theme.chromaCustom3 = v))
                            .describe("Opens a color picker for the third Custom chroma stop."),
                    SettingRow.text("Chroma 4 Hex", "RRGGBB", 7,
                            () -> cfg().theme.chromaCustom4,
                            v -> { cfg().theme.chromaCustom4 = v.trim(); save(); })
                            .describe("Fourth stop of the Custom chroma palette. Leave blank to use "
                                    + "fewer colours."),
                    SettingRow.button("Pick Chroma 4...", () -> openPicker("Chroma 4",
                            () -> cfg().theme.chromaCustom4, v -> cfg().theme.chromaCustom4 = v))
                            .describe("Opens a color picker for the fourth Custom chroma stop."),
                    SettingRow.label("§8Blank stops are skipped - two colours make a back-and-forth,"),
                    SettingRow.label("§8four make a full loop, one is a solid colour"),
                    SettingRow.button("Reset To Default", () -> {
                        cfg().theme.accentHex = "3FB4FF";
                        cfg().theme.backgroundHex = "0C2138";
                        cfg().theme.textHex = "FFFFFF";
                        cfg().theme.chromaPalette = "RAINBOW";
                        cfg().theme.chromaSpeed = 100;
                        cfg().theme.chromaCustom1 = "FF5555";
                        cfg().theme.chromaCustom2 = "FFD64D";
                        cfg().theme.chromaCustom3 = "57D977";
                        cfg().theme.chromaCustom4 = "";
                        save();
                        themeRefresh();
                    })
                            .describe("Back to the stock SBS look: blue accent, dark blue "
                                    + "background, white text, rainbow chroma."),

                    SettingRow.label("— HUD Card Frame —"),
                    SettingRow.label("§8Every SBS card on the HUD is drawn in this one frame"),
                    SettingRow.toggle("Card Glow", () -> cfg().theme.hudCardGlow,
                            () -> { cfg().theme.hudCardGlow = !cfg().theme.hudCardGlow; save(); })
                            .describe("The soft halo behind every HUD card. On by default. Off "
                                    + "gives a flat card that sits closer to the game behind it."),
                    SettingRow.text("Card Border Hex", "follows theme", 6,
                            () -> cfg().theme.hudCardBorderHex,
                            value -> { cfg().theme.hudCardBorderHex = value; save(); })
                            .describe("Pins the card outline to one colour. Leave it EMPTY and the "
                                    + "theme decides, which is what keeps every card matching when "
                                    + "you change the accent."),
                    SettingRow.button("Pick Card Border...", () -> openPicker("Card Border",
                            () -> cfg().theme.hudCardBorderHex,
                            v -> cfg().theme.hudCardBorderHex = v))
                            .describe("Opens a colour picker for the card outline."),
                    SettingRow.text("Card Background Hex", "follows theme", 6,
                            () -> cfg().theme.hudCardFillHex,
                            value -> { cfg().theme.hudCardFillHex = value; save(); })
                            .describe("Pins the card's background. The card is drawn lit from "
                                    + "above, and the darker bottom half is worked out from this "
                                    + "one colour, so there is nothing to match up by hand. Empty "
                                    + "follows the theme."),
                    SettingRow.button("Pick Card Background...", () -> openPicker("Card Background",
                            () -> cfg().theme.hudCardFillHex,
                            v -> cfg().theme.hudCardFillHex = v))
                            .describe("Opens a colour picker for the card background."),
                    SettingRow.text("Card Glow Hex", "follows theme", 6,
                            () -> cfg().theme.hudCardGlowHex,
                            value -> { cfg().theme.hudCardGlowHex = value; save(); })
                            .describe("Pins the halo's colour. Empty follows the theme."),
                    SettingRow.button("Pick Card Glow...", () -> openPicker("Card Glow",
                            () -> cfg().theme.hudCardGlowHex, v -> cfg().theme.hudCardGlowHex = v))
                            .describe("Opens a colour picker for the card glow."),

                    SettingRow.label("— What Decides A Colour —"),
                    SettingRow.label("§81. Theme colours (above) — everything derives from these"),
                    SettingRow.label("§82. UI Style — a look brings its own defaults"),
                    SettingRow.label("§83. Card frame — the three fields above, when not empty"),
                    SettingRow.label("§84. A feature's own colour — bars, scoreboard, highlights"),
                    SettingRow.label("§8Lower numbers are overridden by higher ones"),
                    SettingRow.button("Reset Card Frame To Theme", ModuleSettings::resetCardFrame)
                            .describe("Clears the three fields above and puts the glow back on, so "
                                    + "priority 1 — your theme colours — is the only thing deciding "
                                    + "how a HUD card is drawn. Press it twice to confirm; it "
                                    + "throws away colours you picked by hand.")));

            case ModuleManager.SBS_PLAYERS_ID -> List.of(
                    SettingRow.toggle("Show SBS Badge", () -> cfg().sbsPlayers.enabled,
                            () -> { cfg().sbsPlayers.enabled = !cfg().sbsPlayers.enabled; save(); })
                            .describe("Marks fellow SBS users with a small badge in the tab list, in "
                                    + "chat and on their nametag. On by default, and on its own it "
                                    + "is entirely local: your own badge is drawn without a single "
                                    + "network call, so nobody has to be told anything for you to "
                                    + "see it.")
                            .licenced("you still see other SBS users' badges and your own, but "
                                    + "your badge does not appear on their screens"),
                    SettingRow.label("An SBS icon in the tab list, chat and nametag of every SBS user"),
                    SettingRow.label("§8Your own badge is client-side only - nothing is sent"),
                    SettingRow.label("§8Being badged on OTHER people's screens needs the Public Badge"),
                    SettingRow.label("§8privacy consent + a licence token; without it your uuid never leaves"));

            case ModuleManager.THIRD_PERSON_ID -> List.of(
                    SettingRow.toggle("Show My Nametag", () -> cfg().thirdPerson.selfNametag,
                            () -> { cfg().thirdPerson.selfNametag = !cfg().thirdPerson.selfNametag; save(); })
                            .describe("Shows your own name above your head in third-person view - "
                                    + "you see yourself the way other players do."),
                    SettingRow.label("Your own name above your head in third person, like others see it"),
                    SettingRow.toggle("Show Crosshair", () -> cfg().thirdPerson.crosshair,
                            () -> { cfg().thirdPerson.crosshair = !cfg().thirdPerson.crosshair; save(); })
                            .describe("Keeps the crosshair on screen in third person, where "
                                    + "vanilla hides it."),
                    SettingRow.label("Keep the crosshair visible in third person too"),
                    SettingRow.toggle("Own Player Transparency", () -> cfg().thirdPerson.ownTransparency,
                            () -> { cfg().thirdPerson.ownTransparency = !cfg().thirdPerson.ownTransparency; save(); })
                            .describe("Draws your own player see-through, so you can see what is "
                                    + "behind you in third person. Body, armour, cape, elytra and "
                                    + "held items fade together; your nametag stays as it is. Only "
                                    + "your own player in the world - the inventory model and the "
                                    + "Loadout previews stay solid, and nobody else sees a "
                                    + "difference. Default: off."),
                    SettingRow.rangeSlider("Opacity", sbs.modid.client.helper.visual.transparency
                                    .OwnPlayerTransparency.MIN_PERCENT,
                            sbs.modid.client.helper.visual.transparency.OwnPlayerTransparency.MAX_PERCENT,
                            () -> cfg().thirdPerson.ownOpacity,
                            v -> { cfg().thirdPerson.ownOpacity = sbs.modid.client.helper.visual.transparency
                                    .OwnPlayerTransparency.snapPercent(v); save(); }, "%")
                            .describe("How solid your own player is drawn, in steps of 5 %: 10 % is "
                                    + "barely there, 100 % looks exactly like the toggle being off. "
                                    + "Enchanted armour loses its glint while faded. Default: 50 %."),
                    SettingRow.toggle("Only In Third Person", () -> cfg().thirdPerson.ownTransparencyThirdPersonOnly,
                            () -> { cfg().thirdPerson.ownTransparencyThirdPersonOnly =
                                    !cfg().thirdPerson.ownTransparencyThirdPersonOnly; save(); })
                            .describe("Fades your player only while the camera is in third person. "
                                    + "In first person you only see your hand, which stays solid "
                                    + "either way. Default: on."),
                    SettingRow.label("See-through own player, adjustable in %"));

            case ModuleManager.MOB_HIGHLIGHT_ID -> List.of(
                    SettingRow.toggle("Enable Mob Highlight", () -> cfg().mobHighlight.enabled,
                            () -> { cfg().mobHighlight.enabled = !cfg().mobHighlight.enabled; save(); })
                            .describe("Draws a box around mobs YOU pick from the SkyBlock mob "
                                    + "catalog - for bestiary grinding or hunting one specific "
                                    + "spawn. Only mobs you can actually see are boxed, never "
                                    + "through walls."),
                    SettingRow.button("Select Mobs...", () -> open(new sbs.modid.client.combat.mobhighlight.ui
                            .MobSelectionScreen(sbs.modid.client.core.api.GuiStateManager.getInstance()
                            .getCurrentScreen())))
                            .describe("Opens the mob catalog to pick which mobs get highlighted."),
                    SettingRow.label(mobHighlightSummary()),
                    SettingRow.enumOptions("Highlight Color", () -> cfg().mobHighlight.color,
                            value -> { cfg().mobHighlight.color = value; save(); }, v -> v.displayName())
                            .describe("The color of the mob boxes. Click to cycle."),
                    SettingRow.toggle("Show Name Labels", () -> cfg().mobHighlight.showLabels,
                            () -> { cfg().mobHighlight.showLabels = !cfg().mobHighlight.showLabels; save(); })
                            .describe("Writes the mob's name above its box."),
                    SettingRow.toggle("Show Pointer Lines", () -> cfg().mobHighlight.showTracers,
                            () -> { cfg().mobHighlight.showTracers = !cfg().mobHighlight.showTracers; save(); })
                            .anchor("show_tracers")
                            .describe("A line from your crosshair to each highlighted mob."),
                    SettingRow.label("A line from your crosshair to each highlighted mob"),
                    SettingRow.label("Only mobs in your line of sight are boxed - never through walls"));

            case ModuleManager.PARTY_COMMANDS_ID -> List.of(
                    SettingRow.toggle("Party Commands", () -> cfg().partyCommands.enabled,
                            () -> { cfg().partyCommands.enabled = !cfg().partyCommands.enabled; save(); })
                            .describe("Lets party members control the party through chat: when "
                                    + "someone types !warp, !ptme etc. in party chat, your client "
                                    + "runs the matching /party command for them. Each command "
                                    + "family has its own toggle below."),
                    SettingRow.toggle("Also react to your own !commands", () -> cfg().partyCommands.reactToOwn,
                            () -> { cfg().partyCommands.reactToOwn = !cfg().partyCommands.reactToOwn; save(); })
                            .describe("Your own !commands trigger too - useful for testing, or "
                                    + "for using the shortcuts yourself."),
                    SettingRow.toggle("!warp / !w", () -> cfg().partyCommands.warp,
                            () -> { cfg().partyCommands.warp = !cfg().partyCommands.warp; save(); })
                            .describe("A member typing !warp makes you warp the party to your "
                                    + "server."),
                    SettingRow.toggle("!allinv / !allinvite", () -> cfg().partyCommands.allinv,
                            () -> { cfg().partyCommands.allinv = !cfg().partyCommands.allinv; save(); })
                            .describe("!allinv turns on all-invite, so every member can invite."),
                    SettingRow.toggle("!transfer / !ptme / !pt", () -> cfg().partyCommands.transfer,
                            () -> { cfg().partyCommands.transfer = !cfg().partyCommands.transfer; save(); })
                            .describe("!ptme hands the party leadership to whoever typed it."),
                    SettingRow.toggle("!promote / !demote", () -> cfg().partyCommands.promote,
                            () -> { cfg().partyCommands.promote = !cfg().partyCommands.promote; save(); })
                            .describe("!promote / !demote change a member's party rank."),
                    SettingRow.toggle("!kick / !kickoffline", () -> cfg().partyCommands.kick,
                            () -> { cfg().partyCommands.kick = !cfg().partyCommands.kick; save(); })
                            .describe("!kick <name> kicks a member, !kickoffline clears everyone "
                                    + "who went offline."),
                    SettingRow.toggle("!invite / !reinvite", () -> cfg().partyCommands.invite,
                            () -> { cfg().partyCommands.invite = !cfg().partyCommands.invite; save(); })
                            .describe("!invite <name> invites someone via your client; !reinvite "
                                    + "re-invites whoever just left."),
                    SettingRow.toggle("!f1-!f7 / !m1-!m7 / !t1-!t5", () -> cfg().partyCommands.queue,
                            () -> { cfg().partyCommands.queue = !cfg().partyCommands.queue; save(); })
                            .describe("Dungeon and Kuudra queue shortcuts: !f5 queues the party "
                                    + "for Floor 5, !m3 for Master 3, !t2 for Kuudra tier 2."),
                    SettingRow.toggle("!coords / !loc / !ping / !tps / !fps / !holding / !time",
                            () -> cfg().partyCommands.info,
                            () -> { cfg().partyCommands.info = !cfg().partyCommands.info; save(); })
                            .describe("Info commands: the answer (your coordinates, ping, held "
                                    + "item...) is sent back into party chat."),
                    SettingRow.toggle("!cf / !8ball / !dice", () -> cfg().partyCommands.fun,
                            () -> { cfg().partyCommands.fun = !cfg().partyCommands.fun; save(); })
                            .describe("Fun commands: coin flip, magic 8-ball, dice - answered in "
                                    + "party chat."),
                    SettingRow.toggle("!downtime / !undowntime", () -> cfg().partyCommands.downtime,
                            () -> { cfg().partyCommands.downtime = !cfg().partyCommands.downtime; save(); })
                            .describe("!downtime marks you AFK to the party until !undowntime."),
                    SettingRow.toggle("!boop", () -> cfg().partyCommands.boop,
                            () -> { cfg().partyCommands.boop = !cfg().partyCommands.boop; save(); })
                            .describe("!boop <name> sends that player a /boop."),
                    SettingRow.toggle("!help", () -> cfg().partyCommands.help,
                            () -> { cfg().partyCommands.help = !cfg().partyCommands.help; save(); })
                            .describe("!help lists the available !commands in party chat."),
                    SettingRow.label("Runs the real /party command when a PARTY MEMBER types it"),
                    SettingRow.label("!coords, !ping, !8ball ... answer back in the party chat"));

            case ModuleManager.PARTY_FINDER_ID -> List.of(
                    SettingRow.toggle("Party Finder", () -> cfg().partyFinder.enabled,
                            () -> { cfg().partyFinder.enabled = !cfg().partyFinder.enabled; save(); })
                            .describe("The SBS party finder: browse and create party listings "
                                    + "with other SBS users, with a party chat of its own. Open "
                                    + "it with /sbs party or /pf. Turning it off also frees /pf "
                                    + "for other mods.")
                            .licenced(),
                    SettingRow.toggle("Show Party Chat in Game", () -> cfg().partyFinder.chatInGame,
                            () -> { cfg().partyFinder.chatInGame = !cfg().partyFinder.chatInGame; save(); })
                            .describe("Shows the SBS party's chat messages in your normal game "
                                    + "chat, so you do not need the screen open to follow it."),
                    SettingRow.button("Open Party Finder", () -> open(
                            new sbs.modid.client.social.party.ui.PartyFinderScreen()))
                            .describe("Opens the party finder screen."),
                    SettingRow.label("/sbs party  •  opens the party finder"),
                    SettingRow.label("/sbs party <message>  •  chat to your current party"));

            case ModuleManager.PLAYER_VIEWER_ID -> List.of(
                    SettingRow.toggle("Profile Viewer (/pv)", () -> cfg().playerViewer.profileViewer,
                            () -> { cfg().playerViewer.profileViewer = !cfg().playerViewer.profileViewer; save(); })
                            .describe("The SBS profile viewer: /pv <player> shows anyone's "
                                    + "skills, slayers, dungeons, pets, networth and more in one "
                                    + "screen. Turning it off also frees /pv for other mods and the "
                                    + "server; /sbs pv still works as the SBS name for it."),
                    SettingRow.label("/pv <player>  •  profile viewer (skills, slayers, dungeons, pets)"),
                    SettingRow.toggle("Shift + Click Chat Name", () -> cfg().playerViewer.chatNameClick,
                            () -> { cfg().playerViewer.chatNameClick = !cfg().playerViewer.chatNameClick; save(); })
                            .describe("Shift + clicking a player's name in chat opens their "
                                    + "profile viewer, instead of pasting the name into your chat "
                                    + "box. Needs the Profile Viewer on.")
                            .disabledWhile(() -> !cfg().playerViewer.profileViewer),
                    SettingRow.label("Opens that player's profile instead of inserting the name"),
                    SettingRow.toggle("SkyCrypt Browser", () -> cfg().playerViewer.enabled,
                            () -> { cfg().playerViewer.enabled = !cfg().playerViewer.enabled; save(); })
                            .describe("/sbs skycrypt <player> opens the SkyCrypt profile website "
                                    + "in the in-game browser window."),
                    SettingRow.label("/sbs skycrypt <player>  •  opens sky.shiiyu.moe in the in-game browser"));

            case ModuleManager.TEXTURE_PACK_ID -> List.of(
                    SettingRow.options("Pack Theme Mode",
                            sbs.modid.client.helper.texture.command.TexturePackActions::modeOptions,
                            () -> cfg().texturePack.packTheme.displayName(),
                            sbs.modid.client.helper.texture.command.TexturePackActions::setModeByName)
                            .describe("Which look the game uses: vanilla textures or a SkyBlock "
                                    + "pack you installed. Hypixel+ and Furfsky Reborn join the "
                                    + "cycle once their file is in your resourcepacks folder - the "
                                    + "buttons below open their download pages. Click to cycle."),
                    SettingRow.label("Hypixel+ / Furfsky Reborn appear once installed in resourcepacks/"),
                    SettingRow.toggle("Ignore Enforced Texture Packs", () -> cfg().texturePack.ignoreEnforcedPacks,
                            () -> {
                                cfg().texturePack.ignoreEnforcedPacks = !cfg().texturePack.ignoreEnforcedPacks;
                                save();
                            })
                            .describe("Stops servers from forcing their own resource pack onto "
                                    + "you, so your chosen look stays. If a server pack is "
                                    + "already loaded, rejoin once after turning this on. "
                                    + "Anything your look has no texture for falls back to the "
                                    + "copy of Hypixel's pack Minecraft itself cached - join once "
                                    + "with the server pack accepted so Minecraft caches it; SBS "
                                    + "never downloads it."),
                    SettingRow.label("Blocks server-required packs  •  rejoin if one is already loaded"),
                    SettingRow.toggle("Auto-Accept Server Packs", () -> cfg().texturePack.autoAcceptServerPacks,
                            () -> {
                                cfg().texturePack.autoAcceptServerPacks = !cfg().texturePack.autoAcceptServerPacks;
                                save();
                            })
                            .describe("Answers the \"This server requires the use of a custom "
                                    + "resource pack\" screen with Proceed for you, so joining "
                                    + "never stops on it. Has no effect while Ignore Enforced "
                                    + "Texture Packs is on."),
                    SettingRow.label("Skips the required-pack prompt when you join a server"),
                    SettingRow.toggle("Keep Hypixel Pack Loaded", () -> cfg().texturePack.keepHypixelPackLoaded,
                            () -> {
                                cfg().texturePack.keepHypixelPackLoaded = !cfg().texturePack.keepHypixelPackLoaded;
                                save();
                            })
                            .describe("Faster switching between the lobby and SkyBlock: Hypixel's "
                                    + "SkyBlock pack stays loaded all session instead of loading "
                                    + "and unloading (the loading screen) on every switch. Items "
                                    + "look exactly as with the normal server pack. The pack is "
                                    + "then also active in the lobby and other game modes. Uses "
                                    + "the copy Minecraft cached on an earlier join; with none "
                                    + "cached yet, the first join loads it as usual and the next "
                                    + "start keeps it. Turning it on or off takes effect with the "
                                    + "next pack load. Has no "
                                    + "effect while Ignore Enforced Texture Packs is on."),
                    SettingRow.label("No reload on lobby <-> SkyBlock switches"),
                    SettingRow.button("Hypixel+: Open Download Page",
                            () -> sbs.modid.client.helper.texture.command.TexturePackActions
                                    .openDownloadPage(sbs.modid.client.helper.texture.logic.UserPack.HYPIXEL_PLUS))
                            .describe("Opens the official Hypixel+ page in your browser. Download "
                                    + "the pack there, put the file into your resourcepacks "
                                    + "folder, and it appears in Pack Theme Mode. SBS does not "
                                    + "download packs itself."),
                    SettingRow.label("Install it yourself - it then joins Pack Theme Mode"),
                    SettingRow.button("Furfsky Reborn: Open Download Page",
                            () -> sbs.modid.client.helper.texture.command.TexturePackActions
                                    .openDownloadPage(sbs.modid.client.helper.texture.logic.UserPack.FURFSKY_REBORN))
                            .describe("Opens the official Furfsky Reborn page in your browser. "
                                    + "Download the pack there, put the file into your "
                                    + "resourcepacks folder, and it appears in Pack Theme Mode. "
                                    + "SBS does not download packs itself."),
                    SettingRow.label("Install it yourself - it then joins Pack Theme Mode"));

            // Page order: map + waypoints first, then the in-world boxes with their colours,
            // then the route keybinds, and the GUI-editor button as the closing row.
            case ModuleManager.DUNGEONS_ID -> concat(List.of(
                    SettingRow.toggle("Chest Value Calculator", () -> cfg().dungeons.chestValue,
                            () -> { cfg().dungeons.chestValue = !cfg().dungeons.chestValue; save(); })
                            .describe("On the reward chests after a boss: what the loot is worth "
                                    + "against what opening costs, with live prices - so you see "
                                    + "instantly whether a chest is worth buying."),
                    SettingRow.label("Reward chests: loot value vs. opening cost, live from AH + Bazaar"),
                    SettingRow.label("Items with no live price are named instead of counted as zero"),
                    SettingRow.toggle("Profit On The Chest", () -> cfg().dungeons.chestProfitText,
                            () -> { cfg().dungeons.chestProfitText = !cfg().dungeons.chestProfitText; save(); })
                            .describe("Writes each chest's profit onto the chest itself, so a menu "
                                    + "of six reads at a glance instead of six hovers. A minus "
                                    + "means it loses money; a trailing + means something in it had "
                                    + "no live price, so the real number is at least that. Needs "
                                    + "the calculator above. On by default."),
                    SettingRow.toggle("Mark Losing Chests", () -> cfg().dungeons.chestLossHighlight,
                            () -> { cfg().dungeons.chestLossHighlight = !cfg().dungeons.chestLossHighlight; save(); })
                            .describe("Outlines the chests that cost more than they hold in red. "
                                    + "Off, only the profitable ones are marked and an unmarked "
                                    + "chest means either \"not worth it\" or \"not a chest\" - "
                                    + "which is fine after a boss and confusing in a long list. On "
                                    + "by default."),
                    SettingRow.toggle("Reroll Cost", () -> cfg().dungeons.kismetHint,
                            () -> { cfg().dungeons.kismetHint = !cfg().dungeons.kismetHint; save(); })
                            .describe("Adds what a Kismet Feather costs right now to a chest's "
                                    + "tooltip, so the reroll can be weighed against the chest in "
                                    + "front of you. A price and nothing more - what a reroll is "
                                    + "worth would need the floor's drop tables, which this mod "
                                    + "does not have. On by default."),
                    SettingRow.toggle("Reward Chests In The Room", () -> cfg().dungeons.rewardChestGlow,
                            () -> { cfg().dungeons.rewardChestGlow = !cfg().dungeons.rewardChestGlow; save(); })
                            .describe("After a run, outlines the reward chests themselves: green "
                                    + "for the best, yellow for another that makes money, red for "
                                    + "a loss, with the profit written above. What a chest holds "
                                    + "is only known once you have clicked it, so each one fills "
                                    + "in after you look into it; a grey ? marks one you have not "
                                    + "opened yet. A bought chest drops out. Uses the same prices "
                                    + "as the calculator above. On by default."),

                    SettingRow.label("§8—— Croesus ——"),
                    SettingRow.toggle("Croesus Run List", () -> cfg().dungeons.croesusRunHighlight,
                            () -> { cfg().dungeons.croesusRunHighlight = !cfg().dungeons.croesusRunHighlight; save(); })
                            .describe("In Croesus's list of your past runs, colours the ones that "
                                    + "still have a chest waiting and dims the finished ones, with "
                                    + "the number of chests left in the corner where the run says "
                                    + "so. Off by default: no line of that menu has been read in "
                                    + "game yet, so the wording it looks for is in the two fields "
                                    + "below rather than in the code."),
                    SettingRow.label("§8Last look: " + sbs.modid.client.dungeons.croesus.logic
                            .CroesusScan.last().describe()),
                    SettingRow.text("Chests Left Says", "unopened, chests left", 96,
                            () -> cfg().dungeons.croesusUnopened == null ? "" : cfg().dungeons.croesusUnopened,
                            value -> { cfg().dungeons.croesusUnopened = value == null ? "" : value.trim(); save(); })
                            .describe("The words a run uses when it still has a chest, separated by "
                                    + "commas. A run matching none of these and none of the row "
                                    + "below is left alone rather than guessed at - so if the row "
                                    + "above says nothing was recognised, this is the field to fix."),
                    SettingRow.text("Finished Says", "no more chests", 96,
                            () -> cfg().dungeons.croesusOpened == null ? "" : cfg().dungeons.croesusOpened,
                            value -> { cfg().dungeons.croesusOpened = value == null ? "" : value.trim(); save(); })
                            .describe("The words a finished run uses. Checked before the row above, "
                                    + "because it is the more specific claim - a run matching both "
                                    + "counts as finished, which is the safe way round."),
                    SettingRow.text("Menu Title Says", "croesus", 48,
                            () -> cfg().dungeons.croesusTitle == null ? "" : cfg().dungeons.croesusTitle,
                            value -> { cfg().dungeons.croesusTitle = value == null ? "" : value.trim(); save(); })
                            .describe("The part of the menu's title that says it is Croesus's. "
                                    + "Empty looks at every menu instead, which is the answer if "
                                    + "the list is titled something without his name in it."),
                    SettingRow.toggle("Croesus Reminder", () -> cfg().dungeons.croesusReminder,
                            () -> { cfg().dungeons.croesusReminder = !cfg().dungeons.croesusReminder; save(); })
                            .describe("One line in your own chat when you arrive in the Dungeon "
                                    + "Hub, saying how many runs still had chests the last time you "
                                    + "opened Croesus - and how long ago that was, because it is a "
                                    + "remembered number and not a live one. Off by default."),
                    SettingRow.toggle("Log Croesus Lines", () -> cfg().dungeons.croesusDebugLog,
                            () -> { cfg().dungeons.croesusDebugLog = !cfg().dungeons.croesusDebugLog; save(); })
                            .describe("Writes the menu's own lines to the log once each time you "
                                    + "open it, so the two wording fields above can be corrected "
                                    + "from what the game really says. Off by default."),

                    SettingRow.toggle("SBS Dungeon Map", () -> cfg().dungeons.sbsDungeonMap,
                            () -> { cfg().dungeons.sbsDungeonMap = !cfg().dungeons.sbsDungeonMap; save(); })
                            .describe("The SBS minimap for dungeons: rooms, checkmarks and your "
                                    + "teammates' heads, live in a corner of the screen."),
                    SettingRow.toggle("Hide Map In Boss", () -> cfg().dungeons.hideMapInBoss,
                            () -> { cfg().dungeons.hideMapInBoss = !cfg().dungeons.hideMapInBoss; save(); })
                            .describe("Take the SBS map card above off the screen once the run "
                                    + "reaches the boss, and leave it off for the loot room - there "
                                    + "is nothing to mirror down there, so it is an empty panel in "
                                    + "the way of a fight. Hypixel's own map is untouched."),
                    SettingRow.toggle("Dungeon Score", () -> cfg().dungeons.showScore,
                            () -> { cfg().dungeons.showScore = !cfg().dungeons.showScore; save(); })
                            .describe("A card with the run's numbers: floor, secrets, crypts, "
                                    + "deaths, cleared percent and the estimated score - watch "
                                    + "the 300 come together while you run."),
                    SettingRow.label("Floor, secrets, crypts, deaths, cleared % + estimated score (in dungeons)"),
                    SettingRow.toggle("Spirit Pet (score)", () -> cfg().dungeons.spiritPetForScore,
                            () -> { cfg().dungeons.spiritPetForScore = !cfg().dungeons.spiritPetForScore; save(); })
                            .describe("Tell the score estimate you run a Spirit pet: the first "
                                    + "death then costs 1 score point instead of 2."),
                    SettingRow.label("Enable if you run a Spirit pet: the first death costs −1 instead of −2"),
                    SettingRow.toggle("Blessings (score)", () -> cfg().dungeons.showBlessings,
                            () -> { cfg().dungeons.showBlessings = !cfg().dungeons.showBlessings; save(); })
                            .describe("Add a line to the score card with the blessings your team has "
                                    + "found this run - Power, Life, Wisdom, Stone and Time, each "
                                    + "with its levels added up. If you joined the run midway, the "
                                    + "ones found before you arrived cannot be seen: a number then "
                                    + "reads \"3+\" and a blessing with none counted reads \"–\"."),
                    SettingRow.label("Blessings found this run, levels added up, on the score card"),
                    SettingRow.label("§8—— Teammate Health ——"),
                    SettingRow.toggle("Teammate Low Health", () -> cfg().dungeons.teammateHealthWarn,
                            () -> { cfg().dungeons.teammateHealthWarn = !cfg().dungeons.teammateHealthWarn; save(); })
                            .describe("In a dungeon run, warns you when a teammate's health on the "
                                    + "sidebar drops below the threshold - once per crossing, never "
                                    + "for a dead teammate. Information only: it never heals, casts "
                                    + "or chats for you. Default: off, the sidebar format is not "
                                    + "confirmed in game yet."),
                    SettingRow.rangeSlider("Low Health Below", 5, 90,
                            () -> cfg().dungeons.teammateHealthThreshold,
                            value -> { cfg().dungeons.teammateHealthThreshold = value; save(); }, "%")
                            .describe("How low counts as low, as a share of the most health that "
                                    + "teammate had this run (the sidebar prints no maximum). "
                                    + "Default: 30%."),
                    SettingRow.intField("Warning Cooldown", 1, 60,
                            () -> cfg().dungeons.teammateHealthCooldownSeconds,
                            value -> { cfg().dungeons.teammateHealthCooldownSeconds = value; save(); }, "s")
                            .describe("The shortest gap between two warnings about the same "
                                    + "teammate. Default: 10s."),
                    SettingRow.toggle("Only When I'm Healer", () -> cfg().dungeons.teammateHealthHealerOnly,
                            () -> { cfg().dungeons.teammateHealthHealerOnly = !cfg().dungeons.teammateHealthHealerOnly; save(); })
                            .describe("Only warn while you are playing Healer this run. Default: off."),
                    SettingRow.toggle("Tint Low Teammate", () -> cfg().dungeons.teammateHealthTint,
                            () -> { cfg().dungeons.teammateHealthTint = !cfg().dungeons.teammateHealthTint; save(); })
                            .describe("Turns that teammate's Party Highlight box red while they are "
                                    + "low. Needs Party Highlight on. Default: off.")),
                    sbs.modid.client.core.alert.AlertChannelRows.forAlert("teammate_low_health",
                            "a teammate drops low",
                            () -> cfg().dungeons.teammateHealthChannels,
                            mask -> { cfg().dungeons.teammateHealthChannels = mask; save(); }),
                    List.of(
                    SettingRow.toggle("Prince Call-out", () -> cfg().dungeons.princeAlert,
                            () -> { cfg().dungeons.princeAlert = !cfg().dungeons.princeAlert; save(); })
                            .describe("Flashes PRINCE KILLED and writes one line to your own chat "
                                    + "the first time a Prince goes down in a run - its shard is "
                                    + "worth +1 score, and that point is easy to miss. The score "
                                    + "card counts the +1 too."),
                    SettingRow.label("First Prince kill of the run: flash + chat line, and +1 on the score card"),
                    SettingRow.toggle("Terminal Solver", () -> cfg().dungeons.terminalSolver,
                            () -> { cfg().dungeons.terminalSolver = !cfg().dungeons.terminalSolver; save(); })
                            .describe("Highlights the correct clicks in all six F7/M7 terminals - "
                                    + "order, colour, starts-with, panes, rubix (with the number of "
                                    + "clicks per tile) and melody's button. Display only - it "
                                    + "never clicks for you."),
                    SettingRow.label("Highlights the F7/M7 terminal solution (display only, never clicks)"),
                    SettingRow.toggle("Block Wrong Clicks", () -> cfg().dungeons.terminalBlockWrongClicks,
                            () -> { cfg().dungeons.terminalBlockWrongClicks = !cfg().dungeons.terminalBlockWrongClicks; save(); })
                            .describe("Refuses a terminal click the solver did not mark, so a "
                                    + "misclick never reaches the server. It only ever refuses - "
                                    + "it never clicks - and it stands aside on the melody "
                                    + "terminal, where the timing is the point."),
                    SettingRow.label("Needs the solver on; melody and unknown terminals are never blocked"),
                    SettingRow.toggle("Big Terminal Board", () -> cfg().dungeons.terminalCustomGui,
                            () -> { cfg().dungeons.terminalCustomGui = !cfg().dungeons.terminalCustomGui; save(); })
                            .describe("Draws the open terminal as one large SBS board over the "
                                    + "vanilla chest. Same puzzle and the same clicks - a cell "
                                    + "forwards to the slot behind it - only big enough to read "
                                    + "while a boss is hitting you. Needs the solver on."),
                    SettingRow.rangeSlider("Board Size", 60, 200, () -> cfg().dungeons.terminalGuiScale,
                            value -> { cfg().dungeons.terminalGuiScale = value; save(); }, "%")
                            .describe("How large the board is drawn. 100% is about twice a vanilla "
                                    + "slot."),
                    SettingRow.toggle("Terminal Times", () -> cfg().dungeons.terminalTimes,
                            () -> { cfg().dungeons.terminalTimes = !cfg().dungeons.terminalTimes; save(); })
                            .describe("Times each terminal from opening it to your own activation "
                                    + "line and writes the result - with your best for that "
                                    + "terminal - to your own chat. Local only, nothing is sent."),
                    SettingRow.toggle("Announce Terminals", () -> cfg().dungeons.terminalAnnounce,
                            () -> { cfg().dungeons.terminalAnnounce = !cfg().dungeons.terminalAnnounce; save(); })
                            .describe("Says one line in PARTY CHAT when you finish a terminal, "
                                    + "device or lever yourself. This sends a message in your name, "
                                    + "which is why it is off until you turn it on; it never fires "
                                    + "for anyone else's activation."),
                    SettingRow.text("Announce Text", "{kind} {done}/{total} ({time})", 64,
                            () -> cfg().dungeons.terminalAnnounceText,
                            value -> { cfg().dungeons.terminalAnnounceText = value; save(); })
                            .describe("What that line says. {kind} becomes terminal / device / "
                                    + "lever, {done} and {total} the server's own counter, {time} "
                                    + "how long it took you."),
                    SettingRow.toggle("Device Solver (Simon Says)", () -> cfg().dungeons.deviceSolver,
                            () -> { cfg().dungeons.deviceSolver = !cfg().dungeons.deviceSolver; save(); })
                            .describe("Marks the next button on the phase-3 Simon Says device, read "
                                    + "off the lit sequence on the wall. Display only - it shows "
                                    + "where to press, it never presses."),
                    SettingRow.toggle("Terminal Progress", () -> cfg().dungeons.terminalProgress,
                            () -> { cfg().dungeons.terminalProgress = !cfg().dungeons.terminalProgress; save(); })
                            .describe("A card for the Goldor gates: terminals, devices and levers "
                                    + "done against their totals, and which three players did the "
                                    + "most of them."),
                    SettingRow.label("Goldor gate card: terminals / devices / levers + who did them"),
                    SettingRow.toggle("M7 Dragon HP", () -> cfg().dungeons.m7DragonHp,
                            () -> { cfg().dungeons.m7DragonHp = !cfg().dungeons.m7DragonHp; save(); })
                            .describe("A card for the M7 dragon phase: which dragons are up, what "
                                    + "each has left, and whether it is standing at its own statue "
                                    + "- which is what decides whether killing it counts."),
                    SettingRow.toggle("Dragon HP in World", () -> cfg().dungeons.m7DragonHpWorld,
                            () -> { cfg().dungeons.m7DragonHpWorld = !cfg().dungeons.m7DragonHpWorld; save(); })
                            .describe("Draw each dragon's health at the dragon itself, not only on "
                                    + "the card."),
                    SettingRow.toggle("M7 Dragon Boxes", () -> cfg().dungeons.m7DragonBoxes,
                            () -> { cfg().dungeons.m7DragonBoxes = !cfg().dungeons.m7DragonBoxes; save(); })
                            .describe("Boxes on the dragon statues - the piece of room a dragon has "
                                    + "to die in for its statue to come down. The box fills in when "
                                    + "its dragon is inside it."),
                    SettingRow.rangeSlider("Dragon Box Size", 4, 24, () -> cfg().dungeons.m7DragonBoxRadius,
                            value -> { cfg().dungeons.m7DragonBoxRadius = value; save(); }, " blocks")
                            .describe("Half-width of a statue box in blocks. Hypixel does not "
                                    + "publish the radius it checks, so this is tunable."),
                    SettingRow.label("Statues learn themselves; /sbs dragons <colour> corrects one, /sbs dragons clear resets"),
                    SettingRow.toggle("Puzzle Solver (Blaze)", () -> cfg().dungeons.puzzleSolver,
                            () -> { cfg().dungeons.puzzleSolver = !cfg().dungeons.puzzleSolver; save(); })
                            .describe("Boxes the blaze to shoot next in the Higher-Or-Lower "
                                    + "puzzle room."),
                    SettingRow.toggle("Blaze Highest First", () -> cfg().dungeons.blazeHighestFirst,
                            () -> { cfg().dungeons.blazeHighestFirst = !cfg().dungeons.blazeHighestFirst; save(); })
                            .describe("Which way your puzzle variant goes: highest health first "
                                    + "or lowest first. Flip this if the solver marks the wrong "
                                    + "end."),
                    SettingRow.label("Boxes the next blaze in Higher-Or-Lower (flip order if your variant is reversed)"),
                    SettingRow.toggle("Puzzle: Quiz", () -> cfg().dungeons.puzzleQuiz,
                            () -> { cfg().dungeons.puzzleQuiz = !cfg().dungeons.puzzleQuiz; save(); })
                            .describe("Boxes the correct answer to Oruo's questions. Default on. "
                                    + "A question that is not in the answer set shows nothing "
                                    + "rather than a guess - one wrong click fails the room."),
                    SettingRow.toggle("Puzzle: Three Weirdos", () -> cfg().dungeons.puzzleThreeWeirdos,
                            () -> { cfg().dungeons.puzzleThreeWeirdos = !cfg().dungeons.puzzleThreeWeirdos; save(); })
                            .describe("Records the weirdos' statements to the log so the solver can "
                                    + "be finished from real wording. Default on. It does not "
                                    + "highlight a chest yet."),
                    SettingRow.button("Terminal Simulator",
                            () -> open(new sbs.modid.client.dungeons.terminal.TerminalSimulatorScreen()))
                            .describe("Practice the F7 terminals outside the dungeon, with a "
                                    + "timer and personal best."),
                    SettingRow.keybind("Terminal Simulator Key", () -> cfg().dungeons.terminalSimKey,
                            key -> { cfg().dungeons.terminalSimKey = key; save(); })
                            .describe("A key that opens the terminal simulator in-game."),
                    SettingRow.label("Practice the phase-3 terminals with a timer + personal best (/sbs terminals)"),
                    SettingRow.button("Terminal Solver Test",
                            () -> open(new sbs.modid.client.dungeons.terminal.TerminalTestScreen()))
                            .describe("Runs the solver against one board per terminal and shows "
                                    + "what it marked against what it should have marked - so a "
                                    + "broken terminal shows up here instead of four floors into "
                                    + "a run."),
                    SettingRow.label("Checks every terminal solver against a built board (/sbs terminaltest)"),
                    SettingRow.toggle("Leap Menu", () -> cfg().dungeons.leapMenuEnabled,
                            () -> { cfg().dungeons.leapMenuEnabled = !cfg().dungeons.leapMenuEnabled; save(); })
                            .describe("Colors the Spirit Leap menu's heads by dungeon class, and "
                                    + "lets the class keys below leap instantly while the menu is "
                                    + "open."),
                    SettingRow.label("Class-colours the Spirit Leap slots; a class key leaps to that class (menu open)"),
                    SettingRow.keybind("Leap: Mage", () -> cfg().dungeons.leapMageKey,
                            key -> { cfg().dungeons.leapMageKey = key; save(); })
                            .describe("With the Spirit Leap menu open, this key leaps to the "
                                    + "Mage."),
                    SettingRow.keybind("Leap: Archer", () -> cfg().dungeons.leapArcherKey,
                            key -> { cfg().dungeons.leapArcherKey = key; save(); })
                            .describe("With the Spirit Leap menu open, this key leaps to the "
                                    + "Archer."),
                    SettingRow.keybind("Leap: Berserk", () -> cfg().dungeons.leapBerserkKey,
                            key -> { cfg().dungeons.leapBerserkKey = key; save(); })
                            .describe("With the Spirit Leap menu open, this key leaps to the "
                                    + "Berserk."),
                    SettingRow.keybind("Leap: Tank", () -> cfg().dungeons.leapTankKey,
                            key -> { cfg().dungeons.leapTankKey = key; save(); })
                            .describe("With the Spirit Leap menu open, this key leaps to the "
                                    + "Tank."),
                    SettingRow.keybind("Leap: Healer", () -> cfg().dungeons.leapHealerKey,
                            key -> { cfg().dungeons.leapHealerKey = key; save(); })
                            .describe("With the Spirit Leap menu open, this key leaps to the "
                                    + "Healer."),
                    SettingRow.toggle("Positional Messages", () -> cfg().dungeons.positionalMessagesEnabled,
                            () -> { cfg().dungeons.positionalMessagesEnabled = !cfg().dungeons.positionalMessagesEnabled; save(); })
                            .describe("Automatically fires a saved message when you reach a saved "
                                    + "spot - e.g. 'at P3' into party chat as you land there. "
                                    + "Set them up with /sbs posmsg add or /sbs posmsg party."),
                    SettingRow.label("Fire a message near a saved spot — add with /sbs posmsg add|party <message>"),
                    SettingRow.toggle("Secret Clicked Feedback", () -> cfg().dungeons.secretClickedFeedback,
                            () -> { cfg().dungeons.secretClickedFeedback = !cfg().dungeons.secretClickedFeedback; save(); })
                            .describe("A sound and flash when you collect a secret, and its "
                                    + "Secret Routes waypoint disappears - instant confirmation "
                                    + "the click counted."),
                    SettingRow.label("Sound + overlay on collecting a secret; hides its Secret Routes waypoint"),
                    SettingRow.text("Mimic Message", "sent to party chat", 100,
                            () -> cfg().dungeons.mimicMessage,
                            value -> { cfg().dungeons.mimicMessage = value; save(); })
                            .describe("The text sent to party chat to announce the mimic kill, "
                                    + "e.g. 'Mimic dead!'. Many party setups award score bonuses "
                                    + "off that message."),
                    SettingRow.keybind("Announce Mimic Key", () -> cfg().dungeons.mimicAnnounceKey,
                            key -> { cfg().dungeons.mimicAnnounceKey = key; save(); })
                            .describe("Press to send the mimic message by hand - reliable, no "
                                    + "detection involved."),
                    SettingRow.label("Press the key to send the message to party chat (reliable, no auto-detect)"),
                    SettingRow.toggle("Mimic Auto-Announce", () -> cfg().dungeons.mimicAutoAnnounce,
                            () -> { cfg().dungeons.mimicAutoAnnounce = !cfg().dungeons.mimicAutoAnnounce; save(); })
                            .describe("Tries to detect the mimic kill itself (a broken trapped "
                                    + "chest on F6+) and send the message automatically. "
                                    + "Best-effort - it can mis-fire, hence off by default."),
                    SettingRow.label("§eBest-effort: fires on a broken trapped chest (F6+); can mis-fire, off by default"),
                    SettingRow.toggle("Room Waypoints", () -> cfg().dungeons.roomWaypoints,
                            () -> { cfg().dungeons.roomWaypoints = !cfg().dungeons.roomWaypoints; save(); })
                            .describe("Marks the known secrets of the room you are in (chests, "
                                    + "levers, bats) with waypoints from the SBS room database."),
                    SettingRow.toggle("Box Stared Mobs", () -> cfg().dungeons.boxStarredMobs,
                            () -> { cfg().dungeons.boxStarredMobs = !cfg().dungeons.boxStarredMobs; save(); })
                            .describe("Boxes the starred (✯) mobs that must die for the clear "
                                    + "percentage."),
                    SettingRow.toggle("Show Pointer Lines", () -> cfg().dungeons.showTracers,
                            () -> { cfg().dungeons.showTracers = !cfg().dungeons.showTracers; save(); })
                            .anchor("show_tracers")
                            .describe("A line from your crosshair to room secrets, starred mobs "
                                    + "and wither doors."),
                    SettingRow.label("A line from your crosshair to room secrets, starred mobs and wither doors"),
                    SettingRow.toggle("Show Tank Range", () -> cfg().dungeons.showTankRange,
                            () -> { cfg().dungeons.showTankRange = !cfg().dungeons.showTankRange; save(); })
                            .describe("Draws the Tank's protection radius around them, so you "
                                    + "know when you are inside it."),
                    SettingRow.toggle("Box Wither Doors", () -> cfg().dungeons.witherDoors,
                            () -> { cfg().dungeons.witherDoors = !cfg().dungeons.witherDoors; save(); })
                            .describe("Boxes the next wither or blood door to open, wherever it is in "
                                    + "the dungeon - the same door the map already shows. The box "
                                    + "turns to the key color once anyone in the party has the key."),
                    SettingRow.enumOptions("Wither Door Color", () -> cfg().dungeons.witherDoorColor,
                            value -> { cfg().dungeons.witherDoorColor = value; save(); }, v -> v.displayName())
                            .describe("Box color of the next door while the party has no key for it. "
                                    + "Click to cycle."),
                    SettingRow.enumOptions("With Key Color", () -> cfg().dungeons.witherDoorKeyColor,
                            value -> { cfg().dungeons.witherDoorKeyColor = value; save(); }, v -> v.displayName())
                            .describe("Box color once the party has the key for the next door. "
                                    + "Click to cycle."),
                    SettingRow.label("Switches to this color when anyone in the party picks the key up"),
                    SettingRow.toggle("Show All Wither Doors", () -> cfg().dungeons.witherDoorsShowAll,
                            () -> { cfg().dungeons.witherDoorsShowAll = !cfg().dungeons.witherDoorsShowAll; save(); })
                            .describe("Also draws every other known closed wither and blood door, "
                                    + "faintly. Off: only the next door is boxed."),
                    SettingRow.toggle("Line To Next Door", () -> cfg().dungeons.witherDoorPointer,
                            () -> { cfg().dungeons.witherDoorPointer = !cfg().dungeons.witherDoorPointer; save(); })
                            .describe("A line from your crosshair to the next wither or blood door. "
                                    + "Show Pointer Lines draws it too."),
                    SettingRow.keybind("Add Route Waypoint (Standing)", () -> cfg().dungeons.routeStandingKey,
                            key -> { cfg().dungeons.routeStandingKey = key; save(); })
                            .describe("Secret Routes recording: press to add the block you stand "
                                    + "on as a route point."),
                    SettingRow.keybind("Add Route Waypoint (Looking)", () -> cfg().dungeons.routeLookingKey,
                            key -> { cfg().dungeons.routeLookingKey = key; save(); })
                            .describe("Secret Routes recording: press to add the block you are "
                                    + "looking at as a route point."),
                    SettingRow.toggle("Boss Phase Timer (F7/M7)", () -> cfg().dungeons.phaseTimer,
                            () -> { cfg().dungeons.phaseTimer = !cfg().dungeons.phaseTimer; save(); })
                            .describe("Times each phase of the F7/M7 boss fight separately - "
                                    + "Maxor, Storm, Goldor, Necron, and the dragons in M7 - plus "
                                    + "the total."),
                    SettingRow.label("Splits per phase: Maxor, Storm, Goldor, Necron and (M7) the dragons"),
                    SettingRow.toggle("Phase Splits To Chat", () -> cfg().dungeons.phaseTimerChat,
                            () -> { cfg().dungeons.phaseTimerChat = !cfg().dungeons.phaseTimerChat; save(); })
                            .describe("Prints the phase times into your own chat at the end of "
                                    + "the run. Nothing is sent to other players."),
                    SettingRow.label("§8Printed to your own chat at the score summary - nothing is sent"),
                    SettingRow.toggle("Fire Freeze Timer (F3/M3)", () -> cfg().dungeons.fireFreezeTimer,
                            () -> { cfg().dungeons.fireFreezeTimer = !cfg().dungeons.fireFreezeTimer; save(); })
                            .describe("Counts down to the moment the Fire Freeze Staff should be "
                                    + "cast on the Professor. The staff freezes 5s AFTER the cast, "
                                    + "so it has to go out before he arrives - the card then shows "
                                    + "the wind-up and how long he stays frozen. Display only: it "
                                    + "never casts anything."),
                    SettingRow.intField("Fire Freeze Cast After", 0, 8000,
                            () -> cfg().dungeons.fireFreezeCastMs,
                            value -> { cfg().dungeons.fireFreezeCastMs = value; save(); }, "ms")
                            .describe("Milliseconds from the Professor's \"barrier down\" line "
                                    + "until you should cast. ~3500 is the common answer; your ping "
                                    + "moves it, so tune it once against your own runs."),
                    SettingRow.toggle("Fire Freeze Call-out", () -> cfg().dungeons.fireFreezeAlert,
                            () -> { cfg().dungeons.fireFreezeAlert = !cfg().dungeons.fireFreezeAlert; save(); })
                            .describe("Flashes USE FIRE FREEZE and pings at the cast moment, on top "
                                    + "of the card's countdown."),
                    SettingRow.label("Armed by the Professor's phase line - the staff freezes 5s after you cast"),
                    SettingRow.toggle("Terracotta Timer (F6/M6)", () -> cfg().dungeons.terracottaTimer,
                            () -> { cfg().dungeons.terracottaTimer = !cfg().dungeons.terracottaTimer; save(); })
                            .describe("Marks the spot of every terracotta death in Sadan's first "
                                    + "phase and counts down to it coming back - they respawn "
                                    + "exactly where they fell. Red while there is time, amber as "
                                    + "it closes, green the moment it lands."),
                    SettingRow.intField("Terracotta Respawn F6", 1, 60,
                            () -> cfg().dungeons.terracottaSecondsFloor,
                            value -> { cfg().dungeons.terracottaSecondsFloor = value; save(); }, "s")
                            .describe("How long a killed terracotta takes to come back on Floor 6. "
                                    + "15s measured - correct it here if your runs disagree."),
                    SettingRow.intField("Terracotta Respawn M6", 1, 60,
                            () -> cfg().dungeons.terracottaSecondsMaster,
                            value -> { cfg().dungeons.terracottaSecondsMaster = value; save(); }, "s")
                            .describe("How long a killed terracotta takes to come back on Master 6. "
                                    + "12s measured - correct it here if your runs disagree."),
                    SettingRow.label("Boxes each terracotta's death spot with the time until it respawns there"),
                    SettingRow.toggle("Giant HP (F6/M6)", () -> cfg().dungeons.giantHp,
                            () -> { cfg().dungeons.giantHp = !cfg().dungeons.giantHp; save(); })
                            .describe("Lists the giants that are still up with their health, "
                                    + "weakest first - their own nametags sit above twelve blocks "
                                    + "of mob, which is off the top of your screen from where you "
                                    + "are actually standing."),
                    SettingRow.toggle("Giant HP On The Giant", () -> cfg().dungeons.giantHpWorld,
                            () -> { cfg().dungeons.giantHpWorld = !cfg().dungeons.giantHpWorld; save(); })
                            .describe("Also draws each giant's name and health low on its body, at "
                                    + "the height you fight it from."),
                    SettingRow.label("Green over half, amber under, red nearly down - top line is the one to focus"),
                    SettingRow.toggle("Guardian HP (F3/M3)", () -> cfg().dungeons.guardianHp,
                            () -> { cfg().dungeons.guardianHp = !cfg().dungeons.guardianHp; save(); })
                            .describe("Lists the Professor's guardians that are still up with their "
                                    + "health and distance, weakest first - they sit apart around "
                                    + "the room and he cannot be touched until the last one dies."),
                    SettingRow.toggle("Guardian HP On The Guardian", () -> cfg().dungeons.guardianHpWorld,
                            () -> { cfg().dungeons.guardianHpWorld = !cfg().dungeons.guardianHpWorld; save(); })
                            .describe("Also draws each guardian's health over the guardian itself, "
                                    + "so the number and the target are the same thing."),
                    SettingRow.label("All four read \"Guardian\", so the card carries how far each one is"),
                    SettingRow.toggle("Necron 3x3 Waypoint", () -> cfg().dungeons.necronHealerWaypoint,
                            () -> { cfg().dungeons.necronHealerWaypoint = !cfg().dungeons.necronHealerWaypoint; save(); })
                            .describe("Marks the healer's 3x3 stand spot during the Necron phase, "
                                    + "when you have line of sight to it."),
                    SettingRow.label("The healer's 3x3 block at 54.5 / 63.7 / 114.5 - Necron phase, line of sight only"),
                    SettingRow.toggle("Livid Tracker (F5/M5)", () -> cfg().dungeons.lividTracker,
                            () -> { cfg().dungeons.lividTracker = !cfg().dungeons.lividTracker; save(); })
                            .describe("Finds the REAL Livid among the nine copies in F5/M5, from "
                                    + "the ceiling wool's color or the first one to take damage. "
                                    + "The rows below choose how the find is shown."),
                    SettingRow.label("Finds the real Livid: the ceiling wool's colour, or the first one to take damage"),
                    SettingRow.toggle("Livid Box", () -> cfg().dungeons.lividBox,
                            () -> { cfg().dungeons.lividBox = !cfg().dungeons.lividBox; save(); })
                            .describe("Boxes the real Livid. A thin box with a ? means the pick "
                                    + "is still a guess, not yet confirmed."),
                    SettingRow.toggle("Livid Pointer Line", () -> cfg().dungeons.lividTracer,
                            () -> { cfg().dungeons.lividTracer = !cfg().dungeons.lividTracer; save(); })
                            .anchor("livid_tracer")
                            .describe("A line from your crosshair to the real Livid."),
                    SettingRow.toggle("Livid HUD", () -> cfg().dungeons.lividHud,
                            () -> { cfg().dungeons.lividHud = !cfg().dungeons.lividHud; save(); })
                            .describe("A card with the identified Livid, its health and the fight "
                                    + "clock."),
                    SettingRow.toggle("Livid Chat Message", () -> cfg().dungeons.lividChatMessage,
                            () -> { cfg().dungeons.lividChatMessage = !cfg().dungeons.lividChatMessage; save(); })
                            .describe("One line in your own chat naming the real Livid once it is "
                                    + "identified."),
                    SettingRow.label("§8A thin box + \"?\" means the pick is still the guess, not proof"),
                    SettingRow.button("Edit Dungeon GUI", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.DUNGEON_MAP, HudElement.LIVID_TRACKER,
                                    HudElement.F7_PHASE_TIMER, HudElement.FIRE_FREEZE,
                                    HudElement.GIANT_HP, HudElement.GUARDIAN_HP},
                            "Edit Dungeon GUI")))
                            .describe("Opens the editor where you drag the dungeon map, Livid "
                                    + "card, phase timer, Fire Freeze, Giant HP and Guardian HP "
                                    + "cards anywhere on the screen and scale them.")));

            // Licence + privacy share a page because they are the same subject from the user's
            // side: the token is what lets us talk to the backend at all, and the privacy rows are
            // what decide whether we may say anything about them when we do. Built with a stream so
            // the generated privacy section (one row per ConsentScope) can be appended to the
            // hand-written token rows.
            case ModuleManager.LICENCE_TOKEN_ID -> Stream.concat(Stream.of(
                    SettingRow.label("Token for the SkyBlock Simplified price API"),
                    // Transient: always off on open (not saved), just un-masks the field this session.
                    SettingRow.toggle("Show License Key", () -> licenceKeyVisible,
                            () -> licenceKeyVisible = !licenceKeyVisible)
                            .describe("Un-masks the token field below while ticked, so you can "
                                    + "read or check it. Always off again the next time the "
                                    + "settings open - the token never shows by accident."),
                    SettingRow.maskedText("Licence Token", "Paste your licence token...", 128,
                            () -> sbs.modid.client.core.config.LicenceToken.getInstance().get(),
                            value -> sbs.modid.client.core.config.LicenceToken.getInstance().set(value),
                            () -> licenceKeyVisible)
                            .describe("Your personal SBS licence token - paste it here once. It "
                                    + "unlocks every online feature (prices, flips, IRC...); "
                                    + "without one the mod contacts no SBS server at all. Treat "
                                    + "it like a password."),
                    SettingRow.label("Stored in config/sbs/license/token.json - shared by every profile"),
                    SettingRow.label("§8Without a token the mod contacts no SBS server at all."),
                    SettingRow.toggle("Mark Licence Features",
                            () -> cfg().licence.markLicenceFeatures,
                            () -> {
                                cfg().licence.markLicenceFeatures = !cfg().licence.markLicenceFeatures;
                                save();
                            })
                            .describe("While you have no token, paints the settings that need one "
                                    + "red - the same red an undercut Bazaar order wears - in this "
                                    + "list and in the sidebar beside it. Hover a red row to read "
                                    + "what it still does without a token, since several of them "
                                    + "fall back to something rather than stop. Nothing is marked "
                                    + "once a token is set. On by default."),
                    SettingRow.label("§8Red = needs a token. Hover one to see what it still does.")),
                    PrivacyRows.rows().stream()).toList();

            case ModuleManager.ITEM_PRICE_HISTORY_ID -> List.of(
                    SettingRow.label("Hover an item + press the key to open it, or press for a general search"),
                    SettingRow.keybind("Change Keybind", () -> cfg().priceHistory.openKey,
                            key -> { cfg().priceHistory.openKey = key; save(); })
                            .describe("Hover an item and press this key to open its price-history "
                                    + "chart; pressed over nothing it opens a general item "
                                    + "search.")
                            .licenced()
                            .inDevelopment(),
                    SettingRow.label("Hover + press the key to check item value"),
                    SettingRow.keybind("Change Value Keybind", () -> cfg().priceHistory.valueKey,
                            key -> { cfg().priceHistory.valueKey = key; save(); })
                            .describe("Hover an item and press this key for its appraised value - "
                                    + "what this exact item with its enchants and upgrades is "
                                    + "worth.")
                            .licenced("the value is still worked out from prices you already have; "
                                    + "the historic columns beside it are what goes missing")
                            .inDevelopment(),
                    SettingRow.label("Hover + press the key for live & historic similar auctions"),
                    SettingRow.keybind("Show Similar Auctions", () -> cfg().priceHistory.similarAuctionsKey,
                            key -> { cfg().priceHistory.similarAuctionsKey = key; save(); })
                            .describe("Hover an item and press this key to list live and past "
                                    + "auctions of similar items - what they actually sold for.")
                            .licenced()
                            .inDevelopment());

            case ModuleManager.ANIMATION_SCALING_ID -> List.of(
                    SettingRow.intField("Swing Animation Speed", 0, 999999,
                            () -> cfg().animationScaling.swingSpeed,
                            value -> { cfg().animationScaling.swingSpeed = value; save(); }, "%")
                            .describe("How fast your arm swing animates, visually only. 100% is "
                                    + "vanilla; it changes nothing about real attack speed."),
                    SettingRow.intField("Item Size", 0, 1000,
                            () -> cfg().animationScaling.itemSize,
                            value -> { cfg().animationScaling.itemSize = value; save(); }, "%")
                            .describe("The size of the item in your hand. 100% is vanilla, 0% "
                                    + "hides it - a big sword covers a lot of screen."),
                    SettingRow.enumOptions("Item Applies To", () -> cfg().animationScaling.itemViewMode,
                            value -> { cfg().animationScaling.itemViewMode = value; save(); }, v -> v.displayName())
                            .describe("Whether the item size/offset applies in first person, "
                                    + "third person, or both. Click to cycle."),
                    SettingRow.intField("Item Offset X", -500, 500,
                            () -> cfg().animationScaling.itemOffsetX,
                            value -> { cfg().animationScaling.itemOffsetX = value; save(); }, "")
                            .describe("Moves the held item left/right, in steps of 1/100 block."),
                    SettingRow.intField("Item Offset Y", -500, 500,
                            () -> cfg().animationScaling.itemOffsetY,
                            value -> { cfg().animationScaling.itemOffsetY = value; save(); }, "")
                            .describe("Moves the held item up/down, in steps of 1/100 block."),
                    SettingRow.intField("Item Offset Z", -500, 500,
                            () -> cfg().animationScaling.itemOffsetZ,
                            value -> { cfg().animationScaling.itemOffsetZ = value; save(); }, "")
                            .describe("Moves the held item closer/further, in steps of 1/100 "
                                    + "block."),
                    SettingRow.toggle("Separate Off-Hand Offset",
                            () -> cfg().animationScaling.separateOffHandOffset,
                            () -> { cfg().animationScaling.separateOffHandOffset = !cfg().animationScaling.separateOffHandOffset; save(); })
                            .describe("By default one offset moves whatever is in either hand. "
                                    + "Turn this on to give the off hand its own three values, so "
                                    + "a scaled-down item can sit centred on screen without "
                                    + "dragging the main hand along with it."),
                    SettingRow.intField("Off-Hand Offset X", -500, 500,
                            () -> cfg().animationScaling.offHandOffsetX,
                            value -> { cfg().animationScaling.offHandOffsetX = value; save(); }, "")
                            .describe("Moves the off-hand item left/right. Only used while "
                                    + "Separate Off-Hand Offset is on."),
                    SettingRow.intField("Off-Hand Offset Y", -500, 500,
                            () -> cfg().animationScaling.offHandOffsetY,
                            value -> { cfg().animationScaling.offHandOffsetY = value; save(); }, "")
                            .describe("Moves the off-hand item up/down."),
                    SettingRow.intField("Off-Hand Offset Z", -500, 500,
                            () -> cfg().animationScaling.offHandOffsetZ,
                            value -> { cfg().animationScaling.offHandOffsetZ = value; save(); }, "")
                            .describe("Moves the off-hand item closer/further."),
                    SettingRow.button("Reset Item Position & Size", () -> {
                        var scaling = cfg().animationScaling;
                        scaling.itemSize = 100;
                        scaling.itemOffsetX = 0;
                        scaling.itemOffsetY = 0;
                        scaling.itemOffsetZ = 0;
                        scaling.offHandOffsetX = 0;
                        scaling.offHandOffsetY = 0;
                        scaling.offHandOffsetZ = 0;
                        scaling.separateOffHandOffset = false;
                        save();
                    })
                            .describe("Puts the held item back to vanilla size and position, both "
                                    + "hands. Nothing else on this page is touched."),
                    SettingRow.label("§8Offsets are a render transform only - reach, hitboxes and"),
                    SettingRow.label("§8what the server sees are all unaffected"),
                    SettingRow.label("100% = vanilla  •  0% hides the item  •  Offset steps = 1/100 block"),
                    SettingRow.toggle("Dropped Item Scale", () -> cfg().animationScaling.droppedItemScaleEnabled,
                            () -> { cfg().animationScaling.droppedItemScaleEnabled = !cfg().animationScaling.droppedItemScaleEnabled; save(); })
                            .describe("Resizes items lying on the ground - bigger makes drops "
                                    + "easier to spot."),
                    SettingRow.intField("Dropped Item Size", 10, 1000,
                            () -> cfg().animationScaling.droppedItemScale,
                            value -> { cfg().animationScaling.droppedItemScale = value; save(); }, "%")
                            .describe("The dropped items' size. 100% is vanilla."),
                    SettingRow.intField("Player Scale X", 0, 1000,
                            () -> cfg().animationScaling.entityScaleX,
                            value -> { cfg().animationScaling.entityScaleX = value; save(); }, "%")
                            .describe("Stretches players in width. Visual only - hitboxes are "
                                    + "untouched."),
                    SettingRow.intField("Player Scale Y", 0, 1000,
                            () -> cfg().animationScaling.entityScaleY,
                            value -> { cfg().animationScaling.entityScaleY = value; save(); }, "%")
                            .describe("Stretches players in height. Visual only."),
                    SettingRow.intField("Player Scale Z", 0, 1000,
                            () -> cfg().animationScaling.entityScaleZ,
                            value -> { cfg().animationScaling.entityScaleZ = value; save(); }, "%")
                            .describe("Stretches players in depth. Visual only."),
                    SettingRow.toggle("Scale Yourself", () -> cfg().animationScaling.scaleSelf,
                            () -> { cfg().animationScaling.scaleSelf = !cfg().animationScaling.scaleSelf; save(); })
                            .describe("Apply the player scaling to your own model."),
                    SettingRow.toggle("Scale Other Players", () -> cfg().animationScaling.scaleOtherPlayers,
                            () -> { cfg().animationScaling.scaleOtherPlayers = !cfg().animationScaling.scaleOtherPlayers; save(); })
                            .describe("Apply the player scaling to everyone else."),
                    SettingRow.toggle("Scale NPCs", () -> cfg().animationScaling.scaleNpcs,
                            () -> { cfg().animationScaling.scaleNpcs = !cfg().animationScaling.scaleNpcs; save(); })
                            .describe("Whether player-shaped NPCs count as other players. They "
                                    + "always did - nothing told them apart from real players - "
                                    + "which is why turning on player scaling used to resize half "
                                    + "the hub's shopkeepers too. Turn this off to leave NPCs at "
                                    + "vanilla size while players scale."),
                    SettingRow.label("§8NPCs are told from players by the player list, not the model"),
                    SettingRow.toggle("Scale All Entities", () -> cfg().animationScaling.scaleAllEntities,
                            () -> { cfg().animationScaling.scaleAllEntities = !cfg().animationScaling.scaleAllEntities; save(); })
                            .describe("Apply the scaling to every other entity - mobs, villagers, "
                                    + "pets, armor stands. This is the switch that was missing "
                                    + "when only players appeared to scale: players and mobs are "
                                    + "deliberately separate toggles."),
                    SettingRow.button("Limit To Entity Types...", () -> open(
                            new sbs.modid.client.helper.visual.ui.EntityTypeSelectionScreen(
                                    sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen())))
                            .describe("Narrows Scale All Entities to a chosen set of mob types - "
                                    + "villagers only, say. With nothing ticked it stays every "
                                    + "type, which is how it has always behaved."),
                    SettingRow.label(entityTypeSelectionSummary()));

            case ModuleManager.SKYBLOCK_MENU_ID -> List.of(
                    SettingRow.toggle("Sack Overlay", () -> cfg().skyblockMenu.sackOverlay,
                            () -> { cfg().skyblockMenu.sackOverlay = !cfg().skyblockMenu.sackOverlay; save(); })
                            .describe("Opening a sack shows a panel beside it listing everything "
                                    + "inside with how much you have and what it is worth. The "
                                    + "sack itself stays exactly as clickable as before. Off by "
                                    + "default: the amounts are read from the sack's tooltip in a "
                                    + "format that has not been checked against a real sack yet, "
                                    + "so they may be wrong."),
                    SettingRow.label("Off until the sack menu layout is confirmed in-game"),
                    SettingRow.segmented("Sack Price",
                            sbs.modid.client.helper.sacks.model.SackPriceMode.labels(),
                            () -> cfg().skyblockMenu.sackPriceMode.ordinal(),
                            index -> {
                                cfg().skyblockMenu.sackPriceMode =
                                        sbs.modid.client.helper.sacks.model.SackPriceMode.values()[index];
                                save();
                            })
                            .describe("Which price the sack panel uses. Sell Offer is the top "
                                    + "offer after tax and has to fill; Insta-Sell is the top buy "
                                    + "order after tax, paid now; NPC is what a shop gives, "
                                    + "untaxed. Also switchable on the panel itself."),
                    SettingRow.label("Sell Offer / Insta-Sell are after the Bazaar sell tax"),
                    SettingRow.segmented("Sack Sort",
                            sbs.modid.client.helper.sacks.model.SackSort.labels(),
                            () -> cfg().skyblockMenu.sackSort.ordinal(),
                            index -> {
                                cfg().skyblockMenu.sackSort =
                                        sbs.modid.client.helper.sacks.model.SackSort.values()[index];
                                save();
                            })
                            .describe("The order the sack panel lists items in: what is worth "
                                    + "most, what there is most of, or alphabetically."),
                    SettingRow.toggle("Hide Empty Sack Slots",
                            () -> cfg().skyblockMenu.sackHideEmpty,
                            () -> { cfg().skyblockMenu.sackHideEmpty = !cfg().skyblockMenu.sackHideEmpty; save(); })
                            .describe("Leaves out the items the sack is holding none of. An item "
                                    + "whose amount could not be read is always shown, so a "
                                    + "misread never looks like an empty sack."),
                    SettingRow.enumOptions("Show Enderchest Preview",
                            () -> cfg().skyblockMenu.previewMode,
                            value -> { cfg().skyblockMenu.previewMode = value; save(); }, v -> v.displayName())
                            .describe("Hovering an Ender Chest page or Backpack shows its "
                                    + "contents without opening it. Full UI adds the central "
                                    + "item search across all your storage. Click to cycle "
                                    + "Off / Preview / Full UI."),
                    SettingRow.label("Off  •  Preview: hover an Ender Chest / Backpack"),
                    SettingRow.label("Full UI: preview + the central item search below"),
                    SettingRow.keybind("Open Item Search", () -> cfg().storageSearch.openKey,
                            key -> { cfg().storageSearch.openKey = key; save(); })
                            .describe("A key that opens the storage item search in-game: type a "
                                    + "name and see which storage holds the item."),
                    // Shares its label with the keybind row above it; the keybind keeps the derived
                    // id (it is the setting - this row is the action that opens it once).
                    SettingRow.button("Open Item Search",
                            sbs.modid.client.helper.storage.StorageSearchScreen::open)
                            .anchor("open_item_search_now")
                            .describe("Opens that storage search now."),
                    SettingRow.toggle("Search Item Lore Too", () -> cfg().storageSearch.searchLore,
                            () -> { cfg().storageSearch.searchLore = !cfg().storageSearch.searchLore; save(); })
                            .describe("The search also looks inside item descriptions, so 'sharp' "
                                    + "finds every item with Sharpness - not just items named "
                                    + "sharp."),
                    SettingRow.toggle("Show Storage Value", () -> cfg().storageSearch.showStorageValue,
                            () -> { cfg().storageSearch.showStorageValue = !cfg().storageSearch.showStorageValue; save(); })
                            .describe("In the Full UI, shows what each Ender Chest page and "
                                    + "Backpack is worth next to its name, and the total of all "
                                    + "pages next to the search bar. Uses the prices the mod "
                                    + "already has loaded. A '+' after a value means some item in "
                                    + "it has no known price, so the real value is higher."),
                    SettingRow.label("§8Full UI keeps one workspace over Storage, Ender Chest and Backpacks"),
                    SettingRow.label("Storages are indexed as you open them"),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.INVENTORY)
                            .describe("Whether your inventory is part of the item search index."),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.ENDER_CHEST)
                            .describe("Whether your Ender Chest pages are part of the index. "
                                    + "Open each page once so it can be learned."),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.BACKPACK)
                            .describe("Whether your backpacks are part of the index. Open each "
                                    + "one once so it can be learned."),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.CHEST)
                            .describe("Whether island chests you open are part of the index."),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.MUSEUM)
                            .describe("Whether your Museum is part of the index."),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.SACKS)
                            .describe("Whether your sacks are part of the index."),
                    storageToggle(sbs.modid.client.helper.storage.StorageSource.Kind.VAULT)
                            .describe("Whether your personal vault is part of the index."),
                    SettingRow.label(storageSourceSummary()),
                    SettingRow.toggle("SBS Loadouts", () -> cfg().skyblockMenu.sbsWardrobe,
                            () -> {
                                cfg().skyblockMenu.sbsWardrobe = !cfg().skyblockMenu.sbsWardrobe;
                                SettingRow.logChange("SBS Loadouts", cfg().skyblockMenu.sbsWardrobe);
                                save();
                            })
                            .describe("Covers Hypixel's \"(1/3) Loadouts\" menu with a card grid: "
                                    + "each loadout shows your full player model with its armor, "
                                    + "plus equipment and pet - pick by look instead of by slot "
                                    + "number. Only the Loadouts menu: the Armor Sets menu is SBS "
                                    + "Wardrobe View's. Default: off."),
                    SettingRow.label("Card grid over \"(1/3) Loadouts\": your player model, equipment and pet"),
                    SettingRow.toggle("SBS Wardrobe View", () -> cfg().skyblockMenu.sbsWardrobeView,
                            () -> {
                                cfg().skyblockMenu.sbsWardrobeView = !cfg().skyblockMenu.sbsWardrobeView;
                                SettingRow.logChange("SBS Wardrobe View", cfg().skyblockMenu.sbsWardrobeView);
                                save();
                            })
                            .describe("Covers Hypixel's \"(1/3) Armor Sets\" menu - the wardrobe, "
                                    + "behind the chestplate slot in Loadouts - with one grid of "
                                    + "every set from all its pages, each on an armour stand. Click "
                                    + "a set to equip it; a set on another page takes one click to "
                                    + "turn the menu there and one to equip. Pages not opened this "
                                    + "session show from the cache. Only the Armor Sets menu: the "
                                    + "Loadouts menu is SBS Loadouts'. Default: on."),
                    SettingRow.label("Grid over \"(1/3) Armor Sets\": every set from every page"),
                    SettingRow.toggle("Equipped Loadout Widget",
                            () -> cfg().skyblockMenu.loadoutWidget.shows(),
                            () -> { cfg().skyblockMenu.loadoutWidget = cfg().skyblockMenu.loadoutWidget.next(); save(); })
                            .describe("Puts the SBS Loadouts card of the loadout you are wearing "
                                    + "on your HUD - the same card, one to one: player model, "
                                    + "armor, equipment, pet and HOTF / HOTM / Power Stone / "
                                    + "Tuning. Which loadout it is comes from the helmet on your "
                                    + "head, so it is right however you equipped it."),
                    SettingRow.label("§8Your armor shows at once; the rest once the menu has been opened"),
                    SettingRow.label("§8Move / scale / hide it in the GUI editor (\"Equipped Loadout\")"),
                    SettingRow.toggle("Mirror My Player", () -> cfg().skyblockMenu.loadoutMirror,
                            () -> { cfg().skyblockMenu.loadoutMirror = !cfg().skyblockMenu.loadoutMirror; save(); })
                            .describe("The widget's player model does what you do: walks and sneaks "
                                    + "with you, holds your items, draws your bow, swings your sword "
                                    + "and looks where you look - still wearing the loadout's own "
                                    + "armour. Off, it stands still as before. Default: on."),
                    SettingRow.toggle("SBS Pets", () -> cfg().skyblockMenu.sbsPets,
                            () -> { cfg().skyblockMenu.sbsPets = !cfg().skyblockMenu.sbsPets; save(); })
                            .describe("Shows ALL pages of the Pets menu in one big grid, so any "
                                    + "pet is one click away. Visit each page once so it can be "
                                    + "cached."),
                    SettingRow.label("Every page of the Pets menu in one grid - visit each page once to fill it"));

            case ModuleManager.RECIPE_VIEWER_ID -> List.of(
                    SettingRow.toggle("Enabled", () -> cfg().recipeViewer.enabled,
                            () -> { cfg().recipeViewer.enabled = !cfg().recipeViewer.enabled; save(); })
                            .describe("The SBS recipe viewer: an item list beside container "
                                    + "screens - click any item for its crafting recipe, forge "
                                    + "path or NPC source."),
                    SettingRow.options("Panel Behavior", () -> List.of("Static", "Windowed"),
                            () -> cfg().recipeViewer.windowed ? "Windowed" : "Static",
                            picked -> {
                                cfg().recipeViewer.windowed = "Windowed".equals(picked);
                                save();
                            })
                            .describe("Static keeps the panel docked beside the screen; Windowed "
                                    + "makes it a free window - drag the header, Ctrl+Scroll to "
                                    + "resize, '-' to minimize. Click to switch."),
                    SettingRow.label("Windowed: drag the header, Ctrl+Scroll = size, '-' minimizes"),
                    SettingRow.rangeSlider("Panel Size",
                            sbs.modid.client.economy.recipe.ui.RecipeOverlay.MIN_PANEL_SIZE, 100,
                            () -> cfg().recipeViewer.panelSize,
                            value -> { cfg().recipeViewer.panelSize = value; save(); }, "%")
                            .describe("How much of the space beside the container the static item "
                                    + "grid fills. 100% runs from the top of the screen to the "
                                    + "search bar; lower keeps it docked to the menu and compact."),
                    SettingRow.label("Static panel only - the windowed panel is sized by dragging it"),
                    SettingRow.toggle("Group Enchant Books", () -> cfg().recipeViewer.groupEnchants,
                            () -> { cfg().recipeViewer.groupEnchants = !cfg().recipeViewer.groupEnchants; save(); })
                            .describe("Collapses each enchantment's levels (Legion 1 to max) "
                                    + "into one row instead of listing every book separately; "
                                    + "hovering shows the whole group."),
                    SettingRow.label("Legion 1–max collapse into one row; hover shows the group"),
                    SettingRow.toggle("Search Tooltips", () -> cfg().recipeViewer.searchTooltips,
                            () -> { cfg().recipeViewer.searchTooltips = !cfg().recipeViewer.searchTooltips; save(); })
                            .describe("Highlight mode matches each item's whole tooltip, not just "
                                    + "its name - so an auction page can be filtered by an "
                                    + "enchantment (\"sharpness 6\", \"sharpness vi\") or by any "
                                    + "line of lore. Off matches the item name only."),
                    SettingRow.label("Double-click the search bar to highlight; every word must match"),
                    SettingRow.toggle("NPC Locator", () -> cfg().recipeViewer.npcLocator,
                            () -> { cfg().recipeViewer.npcLocator = !cfg().recipeViewer.npcLocator; save(); })
                            .describe("Shift-clicking an item sold by an NPC shows which island "
                                    + "the NPC stands on and at what coordinates."),
                    SettingRow.label("Shift-click an \"(NPC)\" entry for its island + coordinates"),
                    SettingRow.toggle("NPC Pathfinding", () -> cfg().recipeViewer.npcPathfinding,
                            () -> { cfg().recipeViewer.npcPathfinding = !cfg().recipeViewer.npcPathfinding; save(); })
                            .describe("Once you are on that NPC's island, a walkable path is "
                                    + "drawn to them."),
                    SettingRow.label("Routes to the NPC once you are on its island"),
                    SettingRow.button("Open Recipe Viewer", () -> {
                        if (cfg().recipeViewer.enabled) {
                            open(new RecipeViewerScreen());
                        }
                    })
                            .describe("Opens the recipe viewer as its own screen."));

            case ModuleManager.SCROLLABLE_TOOLTIPS_ID -> List.of(
                    SettingRow.toggle("Enabled", () -> cfg().scrollableTooltips.enabled,
                            () -> { cfg().scrollableTooltips.enabled = !cfg().scrollableTooltips.enabled; save(); })
                            .describe("Tooltips too long for the screen become scrollable: hover "
                                    + "the item and use the mouse wheel to read the rest, instead "
                                    + "of the text being cut off."));

            case ModuleManager.COMMAND_KEYBINDS_ID -> List.of(
                    SettingRow.button("Open Keybind Editor", () -> open(new CommandKeybindsScreen()))
                            .describe("Opens the editor for your own keybinds and command "
                                    + "shortcuts: bind any key (with Shift/Ctrl/Alt) to a command "
                                    + "or a cycling message, limited to chosen islands if you "
                                    + "want; the Shortcuts tab maps words to commands (garden -> "
                                    + "/warp garden)."),
                    SettingRow.label("Also reachable as /sbs keybind (and /sbs gui for the main menu)"),
                    SettingRow.label("Keybinds: key + modifiers, commands or cycled messages"),
                    SettingRow.label("Conditions: run only on a chosen island / area"),
                    SettingRow.label("Shortcuts tab: e.g. garden -> /warp garden, usable anywhere"),
                    SettingRow.toggle("Built-in Short Commands", () -> cfg().shortCommands.enabled,
                            () -> { cfg().shortCommands.enabled = !cfg().shortCommands.enabled; save(); })
                            .describe("The ready-made command shortcuts below - party shorthand, "
                                    + "prefix-free warps. Master switch for all of them."),
                    SettingRow.toggle("Party: /pw /pt /pp /pdm /pd /pk /pko /pi /pa",
                            () -> cfg().shortCommands.party,
                            () -> { cfg().shortCommands.party = !cfg().shortCommands.party; save(); })
                            .describe("Two-letter party commands: /pw = party warp, /pt = "
                                    + "transfer, /pk = kick, /pi = invite, and so on."),
                    SettingRow.toggle("/pa accepts the last invite", () -> cfg().shortCommands.acceptLastInvite,
                            () -> { cfg().shortCommands.acceptLastInvite = !cfg().shortCommands.acceptLastInvite; save(); })
                            .describe("/pa with no name accepts the most recent party invite - "
                                    + "no retyping the inviter's name."),
                    SettingRow.toggle("/pk <name> <reason> announces the reason",
                            () -> cfg().shortCommands.kickReason,
                            () -> { cfg().shortCommands.kickReason = !cfg().shortCommands.kickReason; save(); })
                            .describe("Anything after the name in /pk is posted to party chat as "
                                    + "the kick reason before the kick."),
                    SettingRow.toggle("Warp without the prefix (/wizard, /garden, ...)",
                            () -> cfg().shortCommands.shortWarp,
                            () -> { cfg().shortCommands.shortWarp = !cfg().shortCommands.shortWarp; save(); })
                            .describe("Warp destinations become their own commands: /garden "
                                    + "instead of /warp garden. Hypixel's own commands (/hub, "
                                    + "/home...) are left alone."),
                    SettingRow.label(sbs.modid.client.core.command.ShortCommands.warpHint()),
                    SettingRow.label("/hub, /bank, /museum and /home stay Hypixel's own commands"),
                    SettingRow.toggle("/warp is -> /is", () -> cfg().shortCommands.warpIs,
                            () -> { cfg().shortCommands.warpIs = !cfg().shortCommands.warpIs; save(); })
                            .describe("Rewrites /warp is to Hypixel's /is, the actual "
                                    + "private-island command."),
                    SettingRow.toggle("Wait out the transfer cooldown", () -> cfg().shortCommands.fixTransferCooldown,
                            () -> { cfg().shortCommands.fixTransferCooldown = !cfg().shortCommands.fixTransferCooldown; save(); })
                            .describe("A warp sent during the after-a-server-switch cooldown is "
                                    + "held back and sent automatically once the cooldown ends, "
                                    + "instead of just failing."),
                    SettingRow.toggle("Say when the held-back warp is sent",
                            () -> cfg().shortCommands.transferCooldownMessage,
                            () -> { cfg().shortCommands.transferCooldownMessage = !cfg().shortCommands.transferCooldownMessage; save(); })
                            .describe("A chat line when that held-back warp actually goes out."),
                    SettingRow.toggle("Lower-case /viewrecipe ids", () -> cfg().shortCommands.lowercaseViewrecipe,
                            () -> { cfg().shortCommands.lowercaseViewrecipe = !cfg().shortCommands.lowercaseViewrecipe; save(); })
                            .describe("Lets /viewrecipe accept lower-case item ids by upper-casing "
                                    + "them for Hypixel, which is case-sensitive there."),
                    SettingRow.label("Your own shortcuts always win over a built-in of the same name"));

            case ModuleManager.DEVELOPER_ID -> concat(layoutRecorderRows(), serverScannerRows(), List.of(
                    SettingRow.button("Scanned Rooms...", () -> open(
                            new sbs.modid.client.core.dev.ScannedRoomsScreen(
                                    sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen())))
                            .describe("Lists every scanned room from both sources - your own "
                                    + "scans in Waypoints.json and the rooms bundled with the mod - "
                                    + "with each room's blocks per 32x32 cell, so you can see which "
                                    + "ones still need a re-scan."),
                    SettingRow.button("Scan Room", sbs.modid.client.core.dev.DevActions::beginScan)
                            .describe("Dev tool: records the dungeon room you stand in into the "
                                    + "room database."),
                    SettingRow.button("Add Standing Waypoint", sbs.modid.client.core.dev.DevActions::beginStandingWaypoint)
                            .describe("Dev tool: saves your feet position as a room waypoint."),
                    SettingRow.button("Add Looking Waypoint", sbs.modid.client.core.dev.DevActions::beginLookingWaypoint)
                            .describe("Dev tool: saves the block you look at as a room waypoint."),
                    SettingRow.button("Scan Secrets (Start/Stop)",
                            () -> sbs.modid.client.core.dev.SecretScanner.getInstance().toggle())
                            .describe("Dev tool: while running, secrets you collect (chests, "
                                    + "levers, bats, essence) are auto-recorded for the room "
                                    + "database."),
                    SettingRow.keybind("Scan Secrets Key", () -> cfg().dev.scanSecretsKey,
                            key -> { cfg().dev.scanSecretsKey = key; save(); })
                            .describe("A key that starts/stops the secret scanner in-game."),
                    SettingRow.button("Save Item Secret (legs)",
                            () -> sbs.modid.client.core.dev.SecretScanner.getInstance().saveItemSecretAtLegs())
                            .describe("Dev tool: records an item secret at your leg height - for "
                                    + "the drop-item secrets the auto-scan cannot catch."),
                    SettingRow.keybind("Save Item Secret Key", () -> cfg().dev.saveItemSecretKey,
                            key -> { cfg().dev.saveItemSecretKey = key; save(); })
                            .describe("A key for saving an item secret in-game."),
                    // DEV-ONLY: the Developer Mode toggle itself
                    SettingRow.toggle("Developer Mode", () -> sbs.modid.client.core.dev.DevMode.ACTIVE,
                            // DEV-ONLY: the Developer Mode toggle itself
                            sbs.modid.client.core.dev.DevMode::toggle)
                            .describe("Enables the development helpers: debug logs, the room "
                                    + "scanner, gates like the Catacombs check are bypassed. Not "
                                    + "needed for playing - leave it off unless you are "
                                    + "recording data."),

                    SettingRow.label("Keybinds: Numpad 9 = Scan, 7 = Standing, 8 = Looking"),
                    SettingRow.label("Numpad 6 = Scan Secrets, 3 = Item Secret (at legs)"),
                    SettingRow.label("Saved to config/sbs/Development_Stuff/Waypoints.json"),
                    // --- Pathfinding module (in testing; lives in sbs.modid.client.pathfinding) ---
                    SettingRow.label("Pathfinding (testing)"),
                    SettingRow.button("Add Waypoint Here",
                            () -> sbs.modid.client.core.pathfinding.WaypointStore.addAtPlayer())
                            .describe("Adds a pathfinding test waypoint at your position."),
                    SettingRow.keybind("Add Waypoint Key", () -> cfg().pathfinding.addWaypointKey,
                            key -> { cfg().pathfinding.addWaypointKey = key; save(); })
                            .describe("A key that adds a test waypoint in-game."),
                    SettingRow.keybind("Remove Nearest Waypoint Key",
                            () -> cfg().pathfinding.removeWaypointKey,
                            key -> { cfg().pathfinding.removeWaypointKey = key; save(); })
                            .describe("A key that deletes the waypoint closest to you."),
                    SettingRow.toggle("Render Waypoints", () -> cfg().pathfinding.renderWaypoints,
                            () -> { cfg().pathfinding.renderWaypoints = !cfg().pathfinding.renderWaypoints; save(); })
                            .describe("Draws the stored waypoints in the world."),
                    SettingRow.toggle("Render Paths", () -> cfg().pathfinding.renderPaths,
                            () -> {
                                cfg().pathfinding.renderPaths = !cfg().pathfinding.renderPaths;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            })
                            .describe("Computes and draws a walkable route from you to the "
                                    + "nearest waypoint, around obstacles."),
                    SettingRow.toggle("Allow Sneak Gaps", () -> cfg().pathfinding.allowSneakGaps,
                            () -> {
                                cfg().pathfinding.allowSneakGaps = !cfg().pathfinding.allowSneakGaps;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            })
                            .describe("Lets the route go through gaps only 1.5 blocks high, which "
                                    + "you can pass by sneaking - a slab overhead, or standing on a "
                                    + "slab under a block. Those parts are drawn in amber with "
                                    + "\"Sneak\" where they start. Sneaking is slow, so the route only "
                                    + "takes a gap when it saves real distance; it also keeps you "
                                    + "from walking off edges."),
                    // Preset cycle for a quick pick, hex field for anything else. Cycling clears the
                    // hex so the preset visibly wins back instead of appearing to do nothing.
                    SettingRow.enumOptions("Waypoint Color", () -> cfg().pathfinding.waypointColor,
                            value -> {
                                cfg().pathfinding.waypointColor = value;
                                cfg().pathfinding.waypointColorHex = "";
                                save();
                            }, v -> v.displayName())
                            .describe("Preset color for waypoint markers - shared by every SBS "
                                    + "feature that draws them. Click to cycle; picking one "
                                    + "clears the hex override below."),
                    SettingRow.text("Waypoint Color Hex", "RRGGBB (empty = preset)", 7,
                            () -> cfg().pathfinding.waypointColorHex,
                            value -> { cfg().pathfinding.waypointColorHex = value.trim(); save(); })
                            .describe("Any exact waypoint color as a hex code; empty falls back "
                                    + "to the preset."),
                    SettingRow.enumOptions("Path Color", () -> cfg().pathfinding.pathColor,
                            value -> {
                                cfg().pathfinding.pathColor = value;
                                cfg().pathfinding.pathColorHex = "";
                                save();
                            }, v -> v.displayName())
                            .describe("Preset color for drawn paths - shared by every SBS "
                                    + "feature that draws them (Quest Guide, NPC routing...). "
                                    + "Click to cycle."),
                    SettingRow.text("Path Color Hex", "RRGGBB (empty = preset)", 7,
                            () -> cfg().pathfinding.pathColorHex,
                            value -> { cfg().pathfinding.pathColorHex = value.trim(); save(); })
                            .describe("Any exact path color as a hex code; empty falls back to "
                                    + "the preset."),
                    SettingRow.enumOptions("Path Style", () -> cfg().pathfinding.pathStyle,
                            value -> { cfg().pathfinding.pathStyle = value; save(); }, v -> v.displayName())
                            .describe("How paths are drawn: a line, floating cubes... Click to "
                                    + "cycle."),
                    SettingRow.intField("Cube Size", 5, 90,
                            () -> cfg().pathfinding.cubeSize,
                            value -> { cfg().pathfinding.cubeSize = value; save(); }, "%")
                            .describe("Size of the path cubes, as a share of a block."),
                    SettingRow.intField("Cube Spacing", 1, 8,
                            () -> cfg().pathfinding.cubeSpacing,
                            value -> { cfg().pathfinding.cubeSpacing = value; save(); }, "")
                            .describe("Blocks between two path cubes."),
                    SettingRow.intField("Path Tolerance", 1, 16,
                            () -> cfg().pathfinding.pathTolerance,
                            value -> { cfg().pathfinding.pathTolerance = value; save(); }, "")
                            .describe("How many blocks you may stray from the route before a new "
                                    + "one is searched."),
                    SettingRow.label("Blocks you may stray from the route before it re-searches"),
                    SettingRow.enumOptions("Path Mode", () -> cfg().pathfinding.pathMode,
                            value -> {
                                cfg().pathfinding.pathMode = value;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            }, v -> v.displayName())
                            .describe("How routes may move: walking, flying, or Auto - which "
                                    + "follows whether you are flying and your live jump height. "
                                    + "Click to cycle."),
                    SettingRow.label("Auto follows flying + your live jump height"),
                    SettingRow.toggle("Use Transmission", () -> cfg().pathfinding.useTransmission,
                            () -> {
                                cfg().pathfinding.useTransmission = !cfg().pathfinding.useTransmission;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            })
                            .describe("Lets routes teleport with an Aspect of the End or of the "
                                    + "Void carried anywhere in your inventory - Instant "
                                    + "Transmission to dash ahead, and Ether Transmission (with "
                                    + "Ethermerge) to warp to a block in sight. Jumps are drawn "
                                    + "dashed. Off routes you on foot only."),
                    SettingRow.label("Teleport hops need an AOTE/AOTV in your inventory"),
                    SettingRow.intField("Jump Height Override", 0, 32,
                            () -> cfg().pathfinding.jumpHeightOverride,
                            value -> {
                                cfg().pathfinding.jumpHeightOverride = value;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            }, "")
                            .describe("Forces the jump height routes plan with. 0 measures it "
                                    + "live - only set this if a jump-boost potion is not being "
                                    + "detected."),
                    SettingRow.label("0 = measure it  •  set only if the potion isn't detected"),
                    SettingRow.intField("Max Fall", 1, 32,
                            () -> cfg().pathfinding.maxFall,
                            value -> {
                                cfg().pathfinding.maxFall = value;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            }, "")
                            .describe("The biggest drop a route may take in one step. Raise it "
                                    + "if you have fall-damage protection."),
                    SettingRow.label("How far a route may drop in one step"),
                    SettingRow.intField("Path Search Budget", 1000, 200000,
                            () -> cfg().pathfinding.maxNodes,
                            value -> { cfg().pathfinding.maxNodes = value; save(); }, "")
                            .describe("How much work one route search may do. Higher finds "
                                    + "longer routes but costs more time when no route exists."),
                    SettingRow.toggle("Fast Open-Terrain Routes", () -> cfg().pathfinding.fastOpenTerrain,
                            () -> {
                                cfg().pathfinding.fastOpenTerrain = !cfg().pathfinding.fastOpenTerrain;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            })
                            .describe("Long walking routes cross open ground in straight pieces "
                                    + "and only search block by block where it is covered, a "
                                    + "cave, a house, steep or water - much faster across a big "
                                    + "island. Used straight away for far routes under open sky, "
                                    + "and when the normal search takes too long."),
                    SettingRow.intField("Switch After (base)", 0, 2000,
                            () -> cfg().pathfinding.switchBaseMs,
                            value -> { cfg().pathfinding.switchBaseMs = value; save(); }, "ms")
                            .describe("Advanced. How long the normal search may run before the "
                                    + "fast open-terrain planner takes over, in milliseconds - "
                                    + "plus the per-block time below for every block of distance "
                                    + "(always between 250 ms and 2 s). It also switches early "
                                    + "when the search is clearly too slow."),
                    SettingRow.intField("Switch After (per block)", 0, 20,
                            () -> cfg().pathfinding.switchPerBlockMs,
                            value -> { cfg().pathfinding.switchPerBlockMs = value; save(); }, "ms")
                            .describe("Advanced. Extra milliseconds the normal search gets per "
                                    + "block of distance to the target, so far routes get longer "
                                    + "before the switch."),
                    SettingRow.toggle("Deep Search When No Route", () -> cfg().pathfinding.deepSearch,
                            () -> {
                                cfg().pathfinding.deepSearch = !cfg().pathfinding.deepSearch;
                                save();
                                sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                            })
                            .describe("When no route is found, keeps searching the whole loaded "
                                    + "island in the background - for hidden targets like fairy "
                                    + "souls behind hidden entrances, in caves or on parkour. "
                                    + "Uses a small slice of each tick so the game stays smooth; "
                                    + "the marker shows its progress. A found route is remembered, "
                                    + "so each hidden target is only solved once."),
                    SettingRow.intField("Deep Search Max Time", 10, 300,
                            () -> cfg().pathfinding.deepSearchMaxSeconds,
                            value -> { cfg().pathfinding.deepSearchMaxSeconds = value; save(); }, "s")
                            .describe("How long one deep search may run before it gives up, in "
                                    + "seconds."),
                    SettingRow.button("Clear Waypoints",
                            sbs.modid.client.core.pathfinding.WaypointStore::clear)
                            .describe("Deletes all pathfinding test waypoints."),
                    SettingRow.label(pathfindingSummary()),
                    SettingRow.label("Routes to the NEAREST waypoint  •  avoids obstacles")));

            case ModuleManager.BAZAAR_ID -> List.of(
                    SettingRow.toggle("Highlight Items", () -> cfg().bazaar.highlightItems,
                            () -> { cfg().bazaar.highlightItems = !cfg().bazaar.highlightItems; save(); })
                            .describe("Highlights your own orders' items in the Bazaar menus, so "
                                    + "your slots stand out from the catalog."),
                    SettingRow.toggle("Highlight Claimable (Chroma)", () -> cfg().bazaar.highlightClaimable,
                            () -> { cfg().bazaar.highlightClaimable = !cfg().bazaar.highlightClaimable; save(); })
                            .describe("Rainbow-highlights orders that have items or coins ready "
                                    + "to claim."),
                    SettingRow.toggle("Send Chat Info", () -> cfg().bazaar.sendChatInfo,
                            () -> { cfg().bazaar.sendChatInfo = !cfg().bazaar.sendChatInfo; save(); })
                            .describe("Prints extra order info into your own chat as you trade."),
                    SettingRow.toggle("Copy Cancelled Amount", () -> cfg().bazaar.copyCancelledAmount,
                            () -> {
                                cfg().bazaar.copyCancelledAmount = !cfg().bazaar.copyCancelledAmount;
                                save();
                            })
                            .describe("Cancelling a BUY order puts the amount you still had coming "
                                    + "on the clipboard, so you can paste it straight back into the "
                                    + "custom amount sign. A cancelled sell offer already states its "
                                    + "amount in chat. Default on."),
                    SettingRow.label("§8Cancel a buy order → what was left of it is in your clipboard."),
                    SettingRow.toggle("Order History", () -> cfg().bazaar.orderHistory,
                            () -> { cfg().bazaar.orderHistory = !cfg().bazaar.orderHistory; save(); })
                            .describe("Remembers what was left of every buy order you cancel before "
                                    + "it filled, so you can put the rest back on in one click "
                                    + "instead of working the number out again. Only recorded once "
                                    + "Hypixel confirms the cancellation. Default on."),
                    SettingRow.toggle("Order History Panel", () -> cfg().bazaar.orderHistoryPanel,
                            () -> { cfg().bazaar.orderHistoryPanel = !cfg().bazaar.orderHistoryPanel; save(); })
                            .describe("Shows the remembered remainders beside the Bazaar menu, "
                                    + "opposite Manage Orders. Click a row to re-order what is left "
                                    + "of it; the X forgets it. Off still records them. Default on.")
                            .disabledWhile(() -> !cfg().bazaar.orderHistory),
                    SettingRow.label(cfg().bazaar.orderHistory
                            ? "§8Click a row → its item opens and the leftover amount is filled in."
                            : "§8The three rows below need Order History switched on."),
                    SettingRow.toggle("Show Order History Numbers", () -> cfg().bazaar.orderHistoryNumbers,
                            () -> { cfg().bazaar.orderHistoryNumbers = !cfg().bazaar.orderHistoryNumbers; save(); })
                            .describe("Prints the exact remaining count on each row as well as its "
                                    + "bar. The bar shows how much of the order filled before you "
                                    + "cancelled, and the exact figures are in the tooltip either "
                                    + "way. Default off.")
                            .disabledWhile(() -> !cfg().bazaar.orderHistory),
                    SettingRow.rangeSlider("Order History Expiry", 0, 336,
                            () -> cfg().bazaar.orderHistoryExpiryHours,
                            value -> { cfg().bazaar.orderHistoryExpiryHours = value; save(); }, "h")
                            .describe("How long a remembered remainder stays on offer. An old note "
                                    + "about a price that has moved invites re-ordering at a figure "
                                    + "that is no longer competitive. 0 never expires. Default 48h.")
                            .disabledWhile(() -> !cfg().bazaar.orderHistory),
                    SettingRow.button("Clear Order History",
                            () -> sbs.modid.client.economy.bazaar.logic.BazaarOrderHistory.getInstance()
                                    .clear())
                            .describe("Forgets every remembered remainder for this profile. Your "
                                    + "live Bazaar orders are not touched."),
                    SettingRow.label(sbs.modid.client.ui.render.LocalRankingNotice.settingsNote()),
                    SettingRow.segmented("Flip Ranking", java.util.List.of("Server", "Local"),
                            () -> cfg().bazaar.flipSource.preferLocal ? 1 : 0,
                            index -> {
                                sbs.modid.client.core.api.RankingSource.choose(
                                        cfg().bazaar.flipSource, index == 1);
                                bazaarFlipFeed().request(true);
                            })
                            .describe("Which ranking this window shows. Server is ranked by us with price "
                                    + "history behind it and needs a licence token; Local is worked "
                                    + "out on your own machine from live prices alone, which is "
                                    + "weaker. Server falls back to Local on its own if it cannot be "
                                    + "reached, and tells you why."),
                    SettingRow.toggle("Best Flips", () -> cfg().bazaar.bestFlips,
                            () -> { cfg().bazaar.bestFlips = !cfg().bazaar.bestFlips; save(); })
                            .describe("A panel of the currently best bazaar flips - items whose "
                                    + "buy-order to sell-offer gap earns the most. Clicking one "
                                    + "opens its bazaar page.")
                            .licenced("flips are still found, but worked out locally from one "
                                    + "Bazaar snapshot under the assumptions set below, instead of "
                                    + "ranked by the server")
                            .inDevelopment(),
                    SettingRow.toggle("Manage Orders Panel", () -> cfg().bazaar.manageOrdersPanel,
                            () -> { cfg().bazaar.manageOrdersPanel = !cfg().bazaar.manageOrdersPanel; save(); })
                            .describe("A side panel next to the Bazaar menu listing your orders "
                                    + "with their live status - filled, outbid, waiting - without "
                                    + "clicking into Manage Orders."),
                    SettingRow.label("§8Your orders + live status beside the Bazaar menu."),
                    SettingRow.toggle("Manage Orders on HUD", () -> cfg().bazaar.manageOrdersHud,
                            () -> { cfg().bazaar.manageOrdersHud = !cfg().bazaar.manageOrdersHud; save(); })
                            .describe("Shows that same order panel on screen while you play, so you "
                                    + "notice an order being undercut without walking back to the "
                                    + "Bazaar. It updates in the background either way. The card "
                                    + "hides itself when you have no orders, and shows the eight "
                                    + "most urgent with a count of the rest. Off by default."),
                    SettingRow.button("Move / Resize Orders Card", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.BAZAAR_ORDERS}, "Edit Bazaar Orders")))
                            .describe("Opens the editor where you drag the on-screen orders card "
                                    + "anywhere you like and scale it."),
                    SettingRow.toggle("Search History", () -> cfg().bazaar.searchHistoryEnabled,
                            () -> { cfg().bazaar.searchHistoryEnabled = !cfg().bazaar.searchHistoryEnabled; save(); })
                            .describe("Shows your recent searches on the Bazaar/AH search sign - "
                                    + "click one to search it again. The Bazaar and the Auction "
                                    + "House keep separate lists, so one does not bury the other."),
                    SettingRow.label("§8Separate lists per shop - the panel says which one it is showing."),
                    SettingRow.button("Clear Bazaar History",
                            () -> sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory.getInstance()
                                    .clear(sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory.Source.BAZAAR))
                            .describe("Empties the Bazaar search history. The Auction House list is "
                                    + "left alone."),
                    SettingRow.button("Clear Auction History",
                            () -> sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory.getInstance()
                                    .clear(sbs.modid.client.economy.bazaar.logic.BazaarSearchHistory.Source.AUCTION))
                            .describe("Empties the Auction House search history. The Bazaar list is "
                                    + "left alone."),
                    SettingRow.button("Open Bazaar Flips", () -> open(new BazaarFlipsScreen()))
                            .describe("Opens the ranked list of bazaar flips - what to buy, what "
                                    + "it sells for and the profit per flip - in the same window "
                                    + "the Forge flips use. Click a row to open /bz for it."),

                    SettingRow.label("§8— Local flips (used when the server ranking is unavailable) —"),
                    SettingRow.toggle("Local Flip Fallback", () -> cfg().bazaar.localFlipFallback,
                            () -> {
                                cfg().bazaar.localFlipFallback = !cfg().bazaar.localFlipFallback;
                                save();
                                bazaarFlipFeed().request(true);
                            })
                            .describe("When the server ranking cannot be reached - no licence, an "
                                    + "expired one, or the server being down - work the flips out on "
                                    + "your own machine from the bazaar data the mod already has. "
                                    + "These estimates have no price history behind them, so the "
                                    + "window says so and says what that costs you. Off leaves the "
                                    + "window empty on those paths instead."),
                    SettingRow.intField("Bazaar Flipper Level", 0, 2,
                            () -> cfg().bazaar.bazaarFlipperLevel,
                            value -> {
                                cfg().bazaar.bazaarFlipperLevel = value;
                                save();
                                bazaarFlipFeed().recomputeLocal();
                            }, "")
                            .describe("Your Bazaar Flipper account upgrade (0-2). It sets both your "
                                    + "sell tax (1.25% / 1.125% / 1.00%) and how many orders you may "
                                    + "have open at once (14 / 21 / 28), so local estimates need it "
                                    + "to be right. Left at 0 they assume the worst of both, which "
                                    + "under-promises rather than over-promises."),

                    SettingRow.label("— NPC flips —"),
                    SettingRow.toggle("NPC Flips", () -> cfg().bazaar.npcFlips,
                            () -> { cfg().bazaar.npcFlips = !cfg().bazaar.npcFlips; save(); })
                            .describe("Learns what NPC shops charge whenever you have one open (it "
                                    + "reads the prices on the items, never clicks), and frames the "
                                    + "offers you could sell on the Bazaar for more - the profit per "
                                    + "item, after the Bazaar's sell tax, is on the tooltip. Only "
                                    + "shops you have opened are known. Default: on."),
                    SettingRow.toggle("NPC Flips In Best Flips", () -> cfg().bazaar.npcFlipsInBestFlips,
                            () -> { cfg().bazaar.npcFlipsInBestFlips = !cfg().bazaar.npcFlipsInBestFlips;
                                save(); })
                            .describe("Adds an NPC flips section to the Best Flips window: the best "
                                    + "learned offers, click one to open it on the Bazaar. Default: on."),
                    SettingRow.segmented("NPC Flip Sells At", java.util.List.of("Insta-Sell", "Sell Offer"),
                            () -> cfg().bazaar.npcFlipSellSide,
                            index -> { cfg().bazaar.npcFlipSellSide = index; save(); })
                            .describe("Insta-Sell prices a flip at the best buy order - what you get "
                                    + "right now. Sell Offer prices it at the lowest sell offer - more, "
                                    + "but only once someone buys it. Both after tax. Default: "
                                    + "Insta-Sell."),
                    SettingRow.intField("NPC Flip Min Profit", 0, 10_000_000,
                            () -> cfg().bazaar.npcFlipMinProfit,
                            value -> { cfg().bazaar.npcFlipMinProfit = value; save(); }, " coins")
                            .describe("Profit per item, after tax, below which an NPC flip is not "
                                    + "framed or listed. Default: 1 coin."),
                    SettingRow.intField("NPC Flip Min Volume", 0, 100_000_000,
                            () -> cfg().bazaar.npcFlipMinVolume,
                            value -> { cfg().bazaar.npcFlipMinVolume = value; save(); }, "/wk")
                            .describe("How many of the item must sell on the Bazaar in a week for a "
                                    + "flip to count, so something nobody buys never tops the list. "
                                    + "Default: 1,000."),
                    SettingRow.intField("Assumed Market Share", 1, 100,
                            () -> cfg().bazaar.localFlipSharePct,
                            value -> {
                                cfg().bazaar.localFlipSharePct = value;
                                save();
                                bazaarFlipFeed().recomputeLocal();
                            }, "%")
                            .describe("How much of an item's hourly trade a local estimate assumes "
                                    + "you personally capture. You are not the only flipper in the "
                                    + "market and the rest of them are competing for the same fills, "
                                    + "so the default is a deliberately pessimistic 8%."),
                    SettingRow.intField("Max Spread", 1, 500,
                            () -> cfg().bazaar.localFlipMaxSpreadPct,
                            value -> {
                                cfg().bazaar.localFlipMaxSpreadPct = value;
                                save();
                                bazaarFlipFeed().recomputeLocal();
                            }, "%")
                            .describe("Hide local flips whose spread is wider than this. A very wide "
                                    + "gap is usually a thin or manipulated book rather than free "
                                    + "money - but it is only one signal of several, not the rule."),
                    SettingRow.intField("Min Weekly Volume", 0, 1_000_000,
                            () -> (int) Math.min(Integer.MAX_VALUE, cfg().bazaar.localFlipMinWeeklyVolume),
                            value -> {
                                cfg().bazaar.localFlipMinWeeklyVolume = value;
                                save();
                                bazaarFlipFeed().recomputeLocal();
                            }, "")
                            .describe("Units an item must trade per week on BOTH sides to be offered "
                                    + "locally. An item people dump constantly but nobody buys does "
                                    + "not flip, and neither does the reverse."),
                    SettingRow.intField("Min Orders Per Side", 0, 500,
                            () -> cfg().bazaar.localFlipMinOrders,
                            value -> {
                                cfg().bazaar.localFlipMinOrders = value;
                                save();
                                bazaarFlipFeed().recomputeLocal();
                            }, "")
                            .describe("How many distinct orders each side of the book needs. A thin "
                                    + "book is one person's to move, and the price you see is then "
                                    + "whatever they decided it is."),
                    SettingRow.intField("Max Book Concentration", 10, 100,
                            () -> cfg().bazaar.localFlipMaxConcentrationPct,
                            value -> {
                                cfg().bazaar.localFlipMaxConcentrationPct = value;
                                save();
                                bazaarFlipFeed().recomputeLocal();
                            }, "%")
                            .describe("Hide local flips where this much of one side's visible book "
                                    + "sits at a single price. One order holding up the whole book is "
                                    + "a wall, and a wall is what manipulation looks like from a "
                                    + "single snapshot."),
                    SettingRow.toggle("Show Filtered Flips", () -> cfg().bazaar.localFlipShowFiltered,
                            () -> {
                                cfg().bazaar.localFlipShowFiltered = !cfg().bazaar.localFlipShowFiltered;
                                save();
                            })
                            .describe("List the local flips the filters above removed, each labelled "
                                    + "with the number that tripped it. The filters are heuristics "
                                    + "over one snapshot, so if you read a market better than they "
                                    + "do you should be able to see what they took away."),
                    SettingRow.label("§8Local estimates are projections, never promises."));

            case ModuleManager.FORGE_ID -> {
                List<SettingRow> forge = new java.util.ArrayList<>(List.of(
                    SettingRow.toggle("Show Forge Flips", () -> cfg().forge.showFlips,
                            () -> { cfg().forge.showFlips = !cfg().forge.showFlips; save(); })
                            .describe("Ranks what is worth forging right now: material cost vs. "
                                    + "sale price vs. forge time. Shown over the Forge menu while "
                                    + "it is open.")
                            .licenced("recipes are still ranked, but worked out locally from one "
                                    + "Bazaar snapshot with no price history, instead of ranked by "
                                    + "the server")
                            .inDevelopment(),
                    SettingRow.label("§8Shown over the Forge menu while it is open."),
                    // Shortened whatever "Shorten Numbers" says: this box is typed back in, and
                    // parseCoins reads "50m" but not "50,000,000".
                    SettingRow.text("Budget", "e.g. 50m (empty = any)", 16,
                            () -> cfg().forge.budget > 0
                                    ? sbs.modid.client.core.util.NumberDisplay.shorten(cfg().forge.budget) : "",
                            v -> { cfg().forge.budget = ForgeFlipsScreen.parseCoins(v); save(); })
                            .describe("Hide forge flips whose materials cost more than this "
                                    + "(shorthand like 50m works). Empty shows everything."),
                    SettingRow.keybind("Open Forge Flips", () -> cfg().forge.openKey,
                            k -> { cfg().forge.openKey = k; save(); })
                            .describe("A key that opens the forge-flips list in-game."),
                    SettingRow.button("Open Forge Flips", () -> open(new ForgeFlipsScreen()))
                            .anchor("open_forge_flips_now")
                            .describe("Opens the forge-flips list now."),

                    SettingRow.label(sbs.modid.client.ui.render.LocalRankingNotice.settingsNote()),
                    SettingRow.segmented("Flip Ranking", java.util.List.of("Server", "Local"),
                            () -> cfg().forge.flipSource.preferLocal ? 1 : 0,
                            index -> {
                                sbs.modid.client.core.api.RankingSource.choose(
                                        cfg().forge.flipSource, index == 1);
                                forgeFlipFeed().request(true);
                            })
                            .describe("Which ranking this window shows. Server is ranked by us with price "
                                    + "history behind it and needs a licence token; Local is worked "
                                    + "out on your own machine from live prices alone, which is "
                                    + "weaker. Server falls back to Local on its own if it cannot be "
                                    + "reached, and tells you why."),
                    SettingRow.label("§8Local ranking - also used when the server's cannot be reached."),
                    SettingRow.toggle("Local Forge Fallback", () -> cfg().forge.localFallback,
                            () -> { cfg().forge.localFallback = !cfg().forge.localFallback; save(); })
                            .describe("Rank forge recipes on your own machine whenever the server "
                                    + "ranking is unavailable - no licence token, an expired one, or "
                                    + "no connection. It prices every recipe against the live Bazaar "
                                    + "book, but it has no price history, so it cannot tell a normal "
                                    + "price from an unusual one. Off leaves the window empty on "
                                    + "those paths."),
                    SettingRow.text("Min Profit Per Run", "e.g. 100k", 16,
                            () -> cfg().forge.localMinProfit > 0
                                    ? sbs.modid.client.core.util.NumberDisplay.shorten(
                                            cfg().forge.localMinProfit) : "",
                            v -> {
                                cfg().forge.localMinProfit = ForgeFlipsScreen.parseCoins(v);
                                save();
                                forgeFlipFeed().recomputeLocal();
                            })
                            .describe("Coins one forge run must clear before the local ranking "
                                    + "recommends it. Recipes under it are still listed behind "
                                    + "\"Show Filtered Forge Flips\" - too small for you is a "
                                    + "judgement, not a defect."),
                    SettingRow.intField("Min Weekly Runs", 0, 10_000,
                            () -> cfg().forge.localMinWeeklyRuns,
                            value -> {
                                cfg().forge.localMinWeeklyRuns = value;
                                save();
                                forgeFlipFeed().recomputeLocal();
                            }, "")
                            .describe("How many runs' worth must actually trade per week: of the "
                                    + "result, so your sell offer fills, and of the tightest "
                                    + "ingredient, so your buy order fills. Counted in runs rather "
                                    + "than units because a recipe that yields eight at a time and "
                                    + "one that yields one are not comparable in units."),
                    SettingRow.toggle("Show Filtered Forge Flips", () -> cfg().forge.localShowFiltered,
                            () -> {
                                cfg().forge.localShowFiltered = !cfg().forge.localShowFiltered;
                                save();
                            })
                            .describe("List the local forge flips the checks above removed, each "
                                    + "labelled with the number that tripped it."),
                    SettingRow.label("§8Local estimates are projections, never promises.")));
                forge.addAll(forgeTimerRows());
                yield forge;
            }

            case ModuleManager.FISHING_ID -> {
                List<SettingRow> fishing = new java.util.ArrayList<>(List.of(
                    SettingRow.toggle("Enable Fishing", () -> cfg().fishing.enabled,
                            () -> { cfg().fishing.enabled = !cfg().fishing.enabled; save(); })
                            .describe("Master switch for every fishing helper: the sea-creature "
                                    + "spawn alert, the bite alert, and all the trackers."),
                    SettingRow.label("§8Master switch for the alert and every tracker."),
                    SettingRow.toggle("Spawn Alert", () -> cfg().fishing.spawnAlert,
                            () -> { cfg().fishing.spawnAlert = !cfg().fishing.spawnAlert; save(); })
                            .describe("A big ! above the crosshair when a sea creature spawns "
                                    + "from your hook."),
                    SettingRow.enumOptions("Alert For", () -> cfg().fishing.alertFilter(),
                            value -> { cfg().fishing.alertFilter = value; save(); },
                            v -> v.displayName())
                            .describe("Which spawns fire the alert: everything, or only the rare "
                                    + "catches worth reacting to. Click to cycle."),
                    SettingRow.text("Alert Color Hex", "RRGGBB (0x ok)", 10,
                            () -> cfg().fishing.alertColorHex,
                            v -> { cfg().fishing.alertColorHex = v.trim(); save(); })
                            .describe("The spawn alert's color as a hex code."),
                    SettingRow.toggle("Alert Sound", () -> cfg().fishing.alertSound,
                            () -> { cfg().fishing.alertSound = !cfg().fishing.alertSound; save(); })
                            .describe("A ping along with the spawn alert."),
                    SettingRow.toggle("Alert Animation", () -> cfg().fishing.alertAnimation,
                            () -> { cfg().fishing.alertAnimation = !cfg().fishing.alertAnimation; save(); })
                            .describe("Animates the alert (pulse) instead of static text."),
                    SettingRow.intField("Alert Duration", 200, 10000,
                            () -> cfg().fishing.alertDurationMs,
                            v -> { cfg().fishing.alertDurationMs = v; save(); }, "ms")
                            .describe("How long the spawn alert stays on screen, in "
                                    + "milliseconds."),
                    SettingRow.toggle("Catch Tracker", () -> cfg().fishing.catchTracker,
                            () -> { cfg().fishing.catchTracker = !cfg().fishing.catchTracker; save(); })
                            .describe("A panel counting everything you fish up this session."),
                    SettingRow.toggle("Sea Creature Tracker", () -> cfg().fishing.seaCreatureTracker,
                            () -> { cfg().fishing.seaCreatureTracker = !cfg().fishing.seaCreatureTracker; save(); })
                            .describe("A list counting each sea creature type you have caught "
                                    + "this session."),
                    SettingRow.toggle("Shard Tracker", () -> cfg().fishing.shardTracker,
                            () -> { cfg().fishing.shardTracker = !cfg().fishing.shardTracker; save(); })
                            .describe("A panel counting the Attribute Shards you fish up."),
                    SettingRow.toggle("Profit Tracker", () -> cfg().fishing.profitTracker,
                            () -> { cfg().fishing.profitTracker = !cfg().fishing.profitTracker; save(); })
                            .describe("A panel with what this fishing session has earned, at "
                                    + "live prices."),
                    SettingRow.toggle("Show Values", () -> cfg().fishing.showValues,
                            () -> { cfg().fishing.showValues = !cfg().fishing.showValues; save(); })
                            .describe("Adds each drop's coin value to the tracker rows."),
                    SettingRow.toggle("Scroll Full List in Inventory", () -> cfg().fishing.expandInInventory,
                            () -> { cfg().fishing.expandInInventory = !cfg().fishing.expandInInventory; save(); })
                            .describe("With the inventory open, the trackers show their full "
                                    + "scrollable list instead of the shortened HUD version."),
                    SettingRow.toggle("Show Remaining Baits", () -> cfg().fishing.showRemainingBaits,
                            () -> { cfg().fishing.showRemainingBaits = !cfg().fishing.showRemainingBaits; save(); })
                            .describe("A small chip with your current bait and how many are "
                                    + "left, read from the rod's own bait display."),
                    SettingRow.enumOptions("Tracker Visibility",
                            () -> cfg().fishing.hudVisibility(),
                            value -> { cfg().fishing.hudVisibility = value; save(); },
                            v -> v.displayName())
                            .describe("When the tracker panels show: only while holding a rod, "
                                    + "always, or the default mix. Click to cycle."),
                    SettingRow.label("Tracker Visibility: rod in hand, permanently, or the default mix"),
                    SettingRow.rangeSlider("HUD Opacity", 0, 100, () -> cfg().fishing.hudOpacity,
                            v -> { cfg().fishing.hudOpacity = v; save(); }, "%")
                            .describe("How solid the fishing panels are drawn. At 0% the panel is "
                                    + "gone and only its lines are left over the water."),
                    SettingRow.toggle("Bite Alert (!!!)", () -> cfg().fishing.biteAlert,
                            () -> { cfg().fishing.biteAlert = !cfg().fishing.biteAlert; save(); })
                            .describe("A !!! under the crosshair the moment a fish bites - the "
                                    + "signal to reel in, without staring at the bobber."),
                    SettingRow.text("Bite Color Hex", "RRGGBB (0x ok)", 10,
                            () -> cfg().fishing.biteColorHex,
                            v -> { cfg().fishing.biteColorHex = v.trim(); save(); })
                            .describe("The bite alert's color as a hex code."),
                    SettingRow.toggle("\"Reel in now!\" Text", () -> cfg().fishing.reelInText,
                            () -> { cfg().fishing.reelInText = !cfg().fishing.reelInText; save(); })
                            .describe("Adds the words 'Reel in now!' under the !!!."),
                    SettingRow.button("Move / Resize HUD", () -> open(new HudEditorScreen(
                            new HudElement[] {HudElement.FISHING_HUD, HudElement.FISHING_ALERT,
                                    HudElement.SEA_CREATURE_LIST, HudElement.BITE_ALERT,
                                    HudElement.BAIT_COUNTER, HudElement.GOLDEN_FISH},
                            "Edit Fishing HUD")))
                            .describe("Opens the editor where you drag every fishing panel and "
                                    + "alert anywhere on the screen and scale them."),
                    SettingRow.button("Reset Session", () -> FishingTracker.getInstance().reset())
                            .describe("Sets all fishing session counters back to zero."),
                    SettingRow.toggle("Golden Fish Timer", () -> cfg().fishing.goldenFishTimer,
                            () -> { cfg().fishing.goldenFishTimer = !cfg().fishing.goldenFishTimer; save(); })
                            .describe("While lava fishing on the Crimson Isle: how long you have "
                                    + "fished without a break, time since your last cast, and the "
                                    + "Golden Fish's time left and hooks once it is up. Off by "
                                    + "default because every timing is an estimate that has not "
                                    + "been checked in game yet.")
                            .inDevelopment(),
                    SettingRow.toggle("Golden Fish Reset Warning", () -> cfg().fishing.goldenFishResetWarning,
                            () -> { cfg().fishing.goldenFishResetWarning = !cfg().fishing.goldenFishResetWarning; save(); })
                            .describe("Warns about 30 seconds before going without a lava cast "
                                    + "would throw your Golden Fish progress away. The reset time "
                                    + "itself is an estimate.")
                            .inDevelopment()));
                fishing.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("golden_fish_spawn",
                        "a Golden Fish surfaces",
                        () -> cfg().fishing.goldenFishSpawnChannels,
                        v -> { cfg().fishing.goldenFishSpawnChannels = v; save(); }));
                fishing.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("golden_fish_reset",
                        "your Golden Fish progress is about to reset",
                        () -> cfg().fishing.goldenFishResetChannels,
                        v -> { cfg().fishing.goldenFishResetChannels = v; save(); }));
                yield fishing;
            }

            case ModuleManager.HUNTING_ID -> List.of(
                    SettingRow.keybind("Hunting Key", () -> cfg().hunting.huntingKey,
                            k -> { cfg().hunting.huntingKey = k; save(); })
                            .describe("A key that opens the hunting menu (runs the command "
                                    + "below)."),
                    SettingRow.text("Hunting Command", "/hunting", 32,
                            () -> cfg().hunting.huntingCommand,
                            v -> { cfg().hunting.huntingCommand = v; save(); })
                            .describe("The command that key runs - change it if Hypixel renames "
                                    + "it."),
                    SettingRow.keybind("Shard Fusion Key", () -> cfg().hunting.shardFusionKey,
                            k -> { cfg().hunting.shardFusionKey = k; save(); })
                            .describe("A key that opens the shard-fusion menu."),
                    SettingRow.text("Shard Fusion Command", "/fusion", 32,
                            () -> cfg().hunting.shardFusionCommand,
                            v -> { cfg().hunting.shardFusionCommand = v; save(); })
                            .describe("The command that key runs."),
                    SettingRow.label("Both open their menu on a fresh in-world key press"),
                    SettingRow.keybind("Accept Fusion", () -> cfg().hunting.acceptFusionKey,
                            k -> { cfg().hunting.acceptFusionKey = k; save(); })
                            .describe("Inside the Fusion menu: this key clicks the confirm "
                                    + "button for you."),
                    SettingRow.keybind("Repeat Last Fusion", () -> cfg().hunting.repeatFusionKey,
                            k -> { cfg().hunting.repeatFusionKey = k; save(); })
                            .describe("Inside the Fusion menu: this key re-selects the shards of "
                                    + "your previous fusion - grinding the same fusion becomes "
                                    + "two key presses."),
                    SettingRow.label("Pressed inside the Fusion menu: accept clicks the confirm"),
                    SettingRow.label("button; repeat re-selects the shards of the last fusion"),
                    SettingRow.toggle("Hunting Box Value", () -> cfg().hunting.boxValue,
                            () -> { cfg().hunting.boxValue = !cfg().hunting.boxValue; save(); })
                            .describe("Reads the Hunting Box while it is open and says what is in "
                                    + "it: how many shards are still short of maxing an attribute, "
                                    + "how many are surplus beyond that, and what only the surplus "
                                    + "would sell for."),
                    SettingRow.toggle("Box Value Panel", () -> cfg().hunting.boxPanel,
                            () -> { cfg().hunting.boxPanel = !cfg().hunting.boxPanel; save(); })
                            .describe("The summary card beside the box menu."),
                    SettingRow.toggle("Box Shard Tooltips", () -> cfg().hunting.boxTooltip,
                            () -> { cfg().hunting.boxTooltip = !cfg().hunting.boxTooltip; save(); })
                            .describe("The per-shard breakdown on a shard's own tooltip, inside "
                                    + "the box only."),
                    SettingRow.label("Surplus is what is left after setting aside a whole"),
                    SettingRow.label("attribute's worth - it never counts shards you may need"),
                    SettingRow.label("§8Syphon counts are unconfirmed; fusion value is not counted"),

                    SettingRow.toggle("Missing Shards", () -> cfg().hunting.missingShards,
                            () -> {
                                cfg().hunting.missingShards = !cfg().hunting.missingShards;
                                save();
                                if (cfg().hunting.missingShards) {
                                    sbs.modid.client.economy.prices.BazaarPriceCache.getInstance()
                                            .requestRefresh();
                                }
                            })
                            .describe("While the Attribute Menu is open, lists every shard you have "
                                    + "not finished yet, cheapest first, with what closing each gap "
                                    + "would cost on the Bazaar. Off by default: the menu's wording "
                                    + "has never been read off a live client, so the list may be "
                                    + "short until it has been."),
                    SettingRow.label("§8Open the menu and page through it - only what you"),
                    SettingRow.label("§8have been shown can be read, and the footer says"),
                    SettingRow.label("§8how much of it that is"),
                    SettingRow.toggle("Missing Shards Panel", () -> cfg().hunting.missingShardsPanel,
                            () -> { cfg().hunting.missingShardsPanel = !cfg().hunting.missingShardsPanel; save(); })
                            .describe("The list itself, beside the menu. Off leaves the reading on "
                                    + "so what you page through is still recorded. Default on."),
                    SettingRow.segmented("Sort Shards By",
                            sbs.modid.client.skills.hunting.model.ShardSort.labels(),
                            () -> sbs.modid.client.skills.hunting.model.ShardSort
                                    .byName(cfg().hunting.missingShardsSort).ordinal(),
                            index -> {
                                var values = sbs.modid.client.skills.hunting.model.ShardSort.values();
                                if (index >= 0 && index < values.length) {
                                    cfg().hunting.missingShardsSort = values[index].name();
                                    save();
                                }
                            })
                            .describe("What the list is ordered by. Cost is price times the amount "
                                    + "still needed, which is the one that answers what to buy "
                                    + "next - a cheap shard you need ninety-six of is not cheap. "
                                    + "Default Price."),
                    SettingRow.segmented("Shard Order", List.of("Asc", "Desc"),
                            () -> cfg().hunting.missingShardsDescending ? 1 : 0,
                            index -> { cfg().hunting.missingShardsDescending = index == 1; save(); })
                            .describe("Ascending puts the cheapest first, which is what you can go "
                                    + "and get now. Default ascending."),
                    SettingRow.segmented("Shard Price Source",
                            sbs.modid.client.skills.hunting.model.ShardPriceSource.labels(),
                            () -> sbs.modid.client.skills.hunting.model.ShardPriceSource
                                    .byName(cfg().hunting.missingShardsPriceSource).ordinal(),
                            index -> {
                                var values = sbs.modid.client.skills.hunting.model.ShardPriceSource.values();
                                if (index >= 0 && index < values.length) {
                                    cfg().hunting.missingShardsPriceSource = values[index].name();
                                    save();
                                }
                            })
                            .describe("Instant buy is what the shard costs this second. Buy order is "
                                    + "the cheaper price you get by waiting for one to fill. "
                                    + "Default instant buy."),
                    SettingRow.toggle("Show Finished Shards", () -> cfg().hunting.missingShardsShowOwned,
                            () -> { cfg().hunting.missingShardsShowOwned = !cfg().hunting.missingShardsShowOwned; save(); })
                            .describe("Include shards whose attribute is already maxed. Default off "
                                    + "- they are not a gap."),
                    SettingRow.toggle("Show Part-Collected Shards", () -> cfg().hunting.missingShardsShowPartial,
                            () -> { cfg().hunting.missingShardsShowPartial = !cfg().hunting.missingShardsShowPartial; save(); })
                            .describe("Include shards you have started but not finished. Default on "
                                    + "- a partly collected shard is still a gap."),
                    SettingRow.toggle("Shard Row Opens Bazaar", () -> cfg().hunting.missingShardsClickOpensBazaar,
                            () -> { cfg().hunting.missingShardsClickOpensBazaar = !cfg().hunting.missingShardsClickOpensBazaar; save(); })
                            .describe("Clicking a row closes the menu and runs /bz once for that "
                                    + "shard. Off makes the list a pure display. Default on."),
                    SettingRow.text("Attribute Menu Title", "attribute", 32,
                            () -> cfg().hunting.missingShardsTitle,
                            v -> { cfg().hunting.missingShardsTitle = v; save(); })
                            .describe("The words the Attribute Menu is recognised by, matched "
                                    + "anywhere in its title bar. Change it if Hypixel renames the "
                                    + "menu. Default \"attribute\"."),
                    SettingRow.button("Forget Read Shards",
                            () -> sbs.modid.client.skills.hunting.logic.ShardOwnership.getInstance().clear())
                            .describe("Throws away everything read from the Attribute Menu for this "
                                    + "profile. Nothing else is lost - open the menu again and it "
                                    + "refills. Use it if the list disagrees with what the menu "
                                    + "actually shows."),
                    SettingRow.label("§8Amounts marked ~ come from the shard's rarity, not the menu"));

            case ModuleManager.CASE_OPENING_ID -> List.of(
                    SettingRow.toggle("Enable Animation", () -> cfg().caseOpening.enabled,
                            () -> { cfg().caseOpening.enabled = !cfg().caseOpening.enabled; save(); })
                            .describe("Plays a case-opening style spinner over dungeon reward "
                                    + "chests. Purely cosmetic - the reward is decided by the "
                                    + "server long before the animation runs."),
                    SettingRow.label("§8Cosmetic only - never changes the reward."),
                    SettingRow.intField("Duration", 2000, 5000, () -> cfg().caseOpening.durationMs,
                            v -> { cfg().caseOpening.durationMs = v; save(); }, "ms")
                            .describe("How long the spinner runs, in milliseconds."),
                    SettingRow.toggle("Top-Tier Effects", () -> cfg().caseOpening.topTierEffects,
                            () -> { cfg().caseOpening.topTierEffects = !cfg().caseOpening.topTierEffects; save(); })
                            .describe("Extra flash and sound when the spin lands on a top "
                                    + "reward."),
                    SettingRow.button("Preview Animation",
                            () -> sbs.modid.client.dungeons.casing.logic.CaseOpeningManager.preview())
                            .describe("Plays the animation with dummy loot, so you can tune it "
                                    + "outside a dungeon."));

            case ModuleManager.CONVENIENCE_ID -> List.of(
                    SettingRow.toggle("Keep Mouse Position", () -> cfg().convenience.keepMousePosition,
                            () -> { cfg().convenience.keepMousePosition = !cfg().convenience.keepMousePosition; save(); })
                            .describe("The cursor stays where it was when a menu opens, instead "
                                    + "of jumping to the screen center - clicking through "
                                    + "repeated menus keeps its rhythm."),
                    SettingRow.label("§8The cursor stays put when a menu opens."),
                    SettingRow.toggle("Auto Sprint", () -> cfg().convenience.autoSprint,
                            () -> { cfg().convenience.autoSprint = !cfg().convenience.autoSprint; save(); })
                            .describe("You sprint automatically whenever you walk - no more "
                                    + "holding the sprint key."),
                    SettingRow.label("§8Keep sprinting without holding the sprint key."),
                    SettingRow.toggle("Pin Hypixel To Server List",
                            () -> cfg().convenience.pinHypixelServer,
                            () -> {
                                cfg().convenience.pinHypixelServer = !cfg().convenience.pinHypixelServer;
                                save();
                            })
                            .describe("Hypixel always sits at the top of the multiplayer list, and is "
                                    + "added if it is missing. An entry you already have is moved, not "
                                    + "replaced - your name and address stay. Turn this off if you want "
                                    + "to order or delete it yourself."),
                    SettingRow.label("§8Hypixel stays first in the multiplayer list."),
                    SettingRow.toggle("Hypixel Button In Main Menu",
                            () -> cfg().convenience.hypixelMenuButton,
                            () -> {
                                cfg().convenience.hypixelMenuButton = !cfg().convenience.hypixelMenuButton;
                                save();
                            })
                            .describe("Adds a Hypixel row to the title screen under Multiplayer that "
                                    + "connects straight to "
                                    + sbs.modid.client.core.util.HypixelServerEntry.ADDRESS
                                    + ", skipping the server list."),
                    SettingRow.label("§8One click from the main menu onto Hypixel."),
                    SettingRow.toggle("Highlight SkyBlock in the Game Menu",
                            () -> cfg().convenience.highlightGameMenuSkyBlock,
                            () -> {
                                cfg().convenience.highlightGameMenuSkyBlock =
                                        !cfg().convenience.highlightGameMenuSkyBlock;
                                save();
                            })
                            .describe("In a Hypixel lobby's Game Menu, the SkyBlock button gets the "
                                    + "same moving chroma frame as a claimable Bazaar order, so you "
                                    + "find it at a glance. It follows your Theme's chroma palette "
                                    + "and speed."),
                    SettingRow.label("§8Frames SkyBlock in the lobby game selector."),
                    SettingRow.toggle("Setting Tooltips", () -> cfg().convenience.settingTooltips,
                            () -> { cfg().convenience.settingTooltips = !cfg().convenience.settingTooltips; save(); })
                            .describe("Hover any setting in this menu to see a short, plain-language "
                                    + "explanation of what it does. Turn this off once you know your way "
                                    + "around."),
                    SettingRow.label("§8Explains each setting when you hover it."),
                    SettingRow.toggle("Shorten Numbers", () -> cfg().convenience.shortenNumbers,
                            () -> { cfg().convenience.shortenNumbers = !cfg().convenience.shortenNumbers; save(); })
                            .describe("Every big number SBS shows is written short - 1.4K, 12.7M, "
                                    + "3.2B, the way Hypixel writes them - instead of the full "
                                    + "figure. Turn it off to read every digit: 1,400 and "
                                    + "12,700,000. Applies mod-wide, to the HUD cards, the overlays "
                                    + "and the price tooltips alike."),
                    SettingRow.label("§8K / M / B instead of the full number, everywhere in SBS."),
                    SettingRow.toggle("Tag Sent Messages", () -> cfg().convenience.chatMessageTag,
                            () -> { cfg().convenience.chatMessageTag = !cfg().convenience.chatMessageTag; save(); })
                            .describe("Every chat message SBS sends for you starts with "
                                    + sbs.modid.client.core.util.ChatTag.TAG + " - the !command "
                                    + "answers, the Kuudra call-outs, the carry counter. Your party "
                                    + "sees those lines as if you had typed them, so the tag is how "
                                    + "they can tell. Turn it off to send them unmarked. Messages SBS "
                                    + "shows only to you are not affected."),
                    SettingRow.label("§8Marks the lines SBS sends to chat for you."));

            case ModuleManager.WARP_MENU_ID -> List.of(
                    SettingRow.keybind("Open Warp Menu", () -> cfg().warpMenu.openKey,
                            k -> { cfg().warpMenu.openKey = k; save(); })
                            .describe("A key that opens the SBS warp menu in-game: every warp "
                                    + "destination as a clickable map, one click to travel."),
                    SettingRow.button("Open Warp Menu", () -> open(new WarpMenuScreen()))
                            .anchor("open_warp_menu_now")
                            .describe("Opens that warp menu now."));

            default -> List.of();
        };
    }

    /** The Forge page's timer rows: the card, the finished alert, the menu match. */
    private static List<SettingRow> forgeTimerRows() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("§8Forge timers - your forge slots and when each is ready."));
        rows.add(SettingRow.toggle("Forge Timers", () -> cfg().forge.timersEnabled,
                        () -> { cfg().forge.timersEnabled = !cfg().forge.timersEnabled; save(); })
                .describe("Reads your Dwarven Forge slots each time you open the forge menu: what is "
                        + "in each and when it is ready. The time left is read off the menu, so Quick "
                        + "Forge is already included, and it keeps counting while you are offline. "
                        + "Default: off - see the note below.")
                .anchor("forge_timers")
                .inDevelopment());
        rows.add(SettingRow.label("§8Unverified: the forge menu's layout has not been checked yet. Each"));
        rows.add(SettingRow.label("§8menu read is logged as §f[SBS][ForgeTimer]§8 so the wording can be fixed."));
        // A button, not a status label: labels are built with the page, and reading the config
        // there breaks the row walk outside a running game (LicenceMarksTest).
        rows.add(SettingRow.button("Forge Timer Status", () -> sbs.modid.client.social.chat.logic.SBSChat
                        .send("Forge timers: " + sbs.modid.client.economy.forge.logic.ForgeTimers
                                .getInstance().status()))
                .describe("Says in chat what the last forge menu read found, or why nothing is tracked."));
        rows.add(SettingRow.toggle("Forge Timers Card", () -> cfg().forge.timerCard,
                        () -> { cfg().forge.timerCard = !cfg().forge.timerCard; save(); })
                .describe("A movable card with each forge slot and its time left, the next one on "
                        + "top. Says \"last seen\" when the forge has not been opened for a while. "
                        + "Hidden when nothing is forging. Default: on.")
                .anchor("forge_timer_card"));
        rows.add(SettingRow.button("Move / Resize Forge Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.FORGE_TIMERS}, "Edit Forge Timers")))
                .describe("Drag the Forge Timers card anywhere on the screen and scale it."));
        rows.add(SettingRow.toggle("Forge Slot Ready Alert", () -> cfg().forge.timerAlert,
                        () -> { cfg().forge.timerAlert = !cfg().forge.timerAlert; save(); })
                .describe("Tells you once when a forge slot is ready - also right after you join, "
                        + "if it finished while you were offline. Slots finishing together are "
                        + "one message. Default: on.")
                .anchor("forge_timer_alert"));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("forge_ready",
                "a forge slot is ready",
                () -> cfg().forge.timerChannels,
                v -> { cfg().forge.timerChannels = v; save(); }));
        rows.add(SettingRow.rangeSlider("Say Last Seen After", 1, 72,
                        () -> cfg().forge.timerStaleHours,
                        v -> { cfg().forge.timerStaleHours = v; save(); }, "h")
                .describe("How long since you last opened the forge before the card says \"last "
                        + "seen\". A slot you claimed somewhere else is only noticed on the next "
                        + "visit. Default: 6h.")
                .anchor("forge_timer_stale"));
        rows.add(SettingRow.text("Forge Menu Title", SBSConfig.ForgeSettings.DEFAULT_TIMER_MENU_TITLE, 32,
                        () -> cfg().forge.timerMenuTitle,
                        v -> { cfg().forge.timerMenuTitle = v; save(); })
                .describe("How the forge menu's title starts. Only change it if the log shows the "
                        + "real title is different. Not just \"Forge\": the recipe list behind an "
                        + "empty slot lists forge times too, and must not be read as your slots.")
                .anchor("forge_timer_menu_title"));
        rows.add(SettingRow.button("Clear Forge Timers", () -> {
                    int n = sbs.modid.client.economy.forge.logic.ForgeTimerStore.getInstance().clear();
                    sbs.modid.client.social.chat.logic.SBSChat.send(n == 0 ? "No forge timers to clear."
                            : "Cleared " + n + " forge timer(s).");
                })
                .describe("Forgets the tracked slots on this profile. Opening the forge reads them again."));
        return rows;
    }
}
