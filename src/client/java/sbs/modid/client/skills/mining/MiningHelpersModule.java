/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.logic.MiningTracker;
import sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Mining Helpers module (Skills): the commission card, the Heart of the Mountain / powder card and
 * the remaining-uses card for limited-use tools. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class MiningHelpersModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MiningHelpersModule() {
    }

    @Override
    public String id() {
        return "mining_helpers";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.MINING;
    }

    @Override
    public String displayName() {
        return "Mining Helpers";
    }

    @Override
    public String description() {
        return "Commission progress, Heart of the Mountain / powder, and tool uses left";
    }

    @Override
    public int accentColor() {
        return 0xFF4DC3E0;
    }

    private static SBSConfig.MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new java.util.ArrayList<>(baseSettings());
        rows.addAll(precisionSettings());
        rows.addAll(hotmReminderSettings());
        rows.addAll(abilityReadySettings());
        rows.addAll(coldSettings());
        rows.addAll(effectiveBlockSettings());
        return rows;
    }

    /**
     * Effective Ore Blocks. Its own master switch rather than the cards' one above: it is a world
     * overlay, not a card, and it ships off until a Dwarven Mines and a Glacite session confirm it.
     */
    private static List<SettingRow> effectiveBlockSettings() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Effective Ore Blocks —"));
        rows.add(SettingRow.toggle("Effective Ore Blocks", () -> cfg().effectiveBlocks,
                        () -> { cfg().effectiveBlocks = !cfg().effectiveBlocks; save(); })
                .describe("Tints the Mithril, Umber and Tungsten blocks you can see by how much they "
                        + "give per second of mining: green for the best block of that ore, yellow "
                        + "for the middle. Only faces you can actually see are tinted - nothing "
                        + "behind a wall. Mithril in the Dwarven Mines, the Crystal Hollows (light "
                        + "blue wool only) and the Mineshafts; Umber and Tungsten in the Glacite "
                        + "Tunnels and the Mineshafts; nowhere else. Display only. Default: off - the "
                        + "block values are the wiki's and not confirmed in game yet."));
        rows.add(SettingRow.toggle("Mithril", () -> cfg().effectiveMithril,
                        () -> { cfg().effectiveMithril = !cfg().effectiveMithril; save(); })
                .describe("Light blue wool (5 per block) over prismarine (2) over gray wool and cyan "
                        + "terracotta (1), counted against how long each takes to break."));
        rows.add(SettingRow.toggle("Umber", () -> cfg().effectiveUmber,
                        () -> { cfg().effectiveUmber = !cfg().effectiveUmber; save(); })
                .describe("Red sandstone (3) over brown terracotta (2) over terracotta (1)."));
        rows.add(SettingRow.toggle("Tungsten", () -> cfg().effectiveTungsten,
                        () -> { cfg().effectiveTungsten = !cfg().effectiveTungsten; save(); })
                .describe("Clay (3) over cobblestone (1)."));
        rows.add(SettingRow.toggle("Count Slabs And Stairs", () -> cfg().effectiveHalfBlocks,
                        () -> { cfg().effectiveHalfBlocks = !cfg().effectiveHalfBlocks; save(); })
                .describe("Cobblestone slabs and stairs give half a Tungsten each. With this on they "
                        + "are the lowest tier and cobblestone moves to the middle. Default: off."));
        rows.add(SettingRow.toggle("I Insta-Mine Soft Mithril", () -> cfg().effectiveInstaMineSoft,
                        () -> { cfg().effectiveInstaMineSoft = !cfg().effectiveInstaMineSoft; save(); })
                .describe("Turn on if you break gray wool and cyan terracotta instantly. Then they "
                        + "give more per second than anything else and are tinted best. Your mining "
                        + "speed is never read - this is your call. Default: off."));
        rows.add(SettingRow.intField("Radius", 1, 24, () -> cfg().effectiveRadius,
                        v -> { cfg().effectiveRadius = v; save(); }, " blocks")
                .describe("How far around you blocks are looked at. 12 by default, 24 at most."));
        rows.add(SettingRow.toggle("Best Tier Only", () -> cfg().effectiveBestOnly,
                        () -> { cfg().effectiveBestOnly = !cfg().effectiveBestOnly; save(); })
                .describe("Tint only the best block of each ore. Default: off."));
        rows.add(SettingRow.toggle("Show Lowest Tier", () -> cfg().effectiveShowLow,
                        () -> { cfg().effectiveShowLow = !cfg().effectiveShowLow; save(); })
                .describe("Tint the worst block of an ore as well, in the colour below. Only an ore "
                        + "with three different values has a lowest tier. Default: off."));
        rows.add(SettingRow.toggle("Outline Faces", () -> cfg().effectiveOutline,
                        () -> { cfg().effectiveOutline = !cfg().effectiveOutline; save(); })
                .describe("Draws the edges of each tinted face too. Default: off."));
        rows.add(SettingRow.color("Best Colour", () -> cfg().effectiveBestColorHex,
                        () -> 0xFF000000 | sbs.modid.client.skills.mining.render.EffectiveBlockHighlight.DEFAULT_BEST,
                        () -> openPicker("Best Ore Block", () -> cfg().effectiveBestColorHex,
                                hex -> cfg().effectiveBestColorHex = hex))
                .describe("The block that gives the most per second of mining."));
        rows.add(SettingRow.color("Middle Colour", () -> cfg().effectiveMiddleColorHex,
                        () -> 0xFF000000 | sbs.modid.client.skills.mining.render.EffectiveBlockHighlight.DEFAULT_MIDDLE,
                        () -> openPicker("Middle Ore Block", () -> cfg().effectiveMiddleColorHex,
                                hex -> cfg().effectiveMiddleColorHex = hex))
                .describe("Worth mining, but not first."));
        rows.add(SettingRow.color("Lowest Colour", () -> cfg().effectiveLowColorHex,
                        () -> 0xFF000000 | sbs.modid.client.skills.mining.render.EffectiveBlockHighlight.DEFAULT_LOW,
                        () -> openPicker("Lowest Ore Block", () -> cfg().effectiveLowColorHex,
                                hex -> cfg().effectiveLowColorHex = hex))
                .describe("Only used with Show Lowest Tier on."));
        rows.add(SettingRow.label("§8Block values are the wiki's (unconfirmed in game)"));
        return rows;
    }

    /**
     * Precision Mining Target. Off by default until a probe has captured the perk's particle: the
     * matcher is a guess, and a guess that marks the wrong thing should not be on for everyone.
     */
    private static List<SettingRow> precisionSettings() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Precision Mining —"));
        rows.add(SettingRow.toggle("Precision Mining Target", () -> cfg().precisionTarget,
                        () -> { cfg().precisionTarget = !cfg().precisionTarget; save(); })
                .describe("With the Heart of the Mountain perk Precision Mining, puts a clear square on "
                        + "the perk's particle on the block you are mining. Red while your crosshair is "
                        + "off it, green while it is on it. Display only: you move the mouse, nothing "
                        + "aims for you. Only ever on the block you are mining, and never through a "
                        + "wall. Default: off - the particle has not been captured in game yet."));
        rows.add(SettingRow.color("On Target Colour", () -> cfg().precisionOnColorHex, () -> 0xFF55FF55,
                        () -> openPicker("Precision On Target", () -> cfg().precisionOnColorHex,
                                hex -> cfg().precisionOnColorHex = hex))
                .describe("The marker's colour while your crosshair is on the target."));
        rows.add(SettingRow.color("Off Target Colour", () -> cfg().precisionOffColorHex, () -> 0xFFFF5555,
                        () -> openPicker("Precision Off Target", () -> cfg().precisionOffColorHex,
                                hex -> cfg().precisionOffColorHex = hex))
                .describe("The marker's colour while it is not."));
        rows.add(SettingRow.rangeSlider("Marker Size", 4, 50, () -> cfg().precisionMarkerSize,
                        v -> { cfg().precisionMarkerSize = v; save(); }, "%")
                .describe("How wide the square is, as a share of the block's width. 16% by default."));
        rows.add(SettingRow.rangeSlider("On Target Radius", 3, 50, () -> cfg().precisionRadius,
                        v -> { cfg().precisionRadius = v; save(); }, "%")
                .describe("How close your crosshair's spot on the block has to be to the particle "
                        + "before the marker turns green, as a share of the block's width. 15% by "
                        + "default. How close the server itself counts has not been measured, so "
                        + "this is a guess you can tune."));
        rows.add(SettingRow.toggle("Crosshair Square", () -> cfg().precisionCrosshairRing,
                        () -> { cfg().precisionCrosshairRing = !cfg().precisionCrosshairRing; save(); })
                .describe("A small square around your crosshair in the same red or green while a "
                        + "target is shown. Default: on."));
        rows.add(SettingRow.toggle("Precision Line", () -> cfg().precisionHud,
                        () -> { cfg().precisionHud = !cfg().precisionHud; save(); })
                .describe("\"Precision: on target\" or \"off target\" as a line under the crosshair "
                        + "while a target is shown. Default: off."));
        rows.add(SettingRow.button("Move / Resize Precision Line", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.PRECISION_MINING}, "Edit Precision Line")))
                .describe("Opens the editor where you drag the line anywhere on the screen and "
                        + "scale it."));
        return rows;
    }

    private static void openPicker(String label, java.util.function.Supplier<String> current,
                                   java.util.function.Consumer<String> setter) {
        net.minecraft.client.gui.screens.Screen previous =
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen(
                "Mining  •  " + label, current.get(),
                value -> {
                    setter.accept(value == null ? "" : value);
                    save();
                }, previous));
    }

    /**
     * HotM Upgrade Reminder, and the list of perks it watches. The list is per profile and comes from
     * the cached tree, so it is empty until the Heart of the Mountain menu has been opened once.
     */
    private static List<SettingRow> hotmReminderSettings() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— HotM Upgrade Reminder —"));
        rows.add(SettingRow.toggle("HotM Upgrade Reminder", () -> cfg().hotmReminder,
                        () -> { cfg().hotmReminder = !cfg().hotmReminder; save(); })
                .describe("Tells you when a perk you watch can be levelled with the powder you have: "
                        + "\"HotM: Efficient Miner 55 affordable (35,098 Mithril)\". Costs are the ones "
                        + "the Heart of the Mountain menu showed the last time you opened it - nothing "
                        + "is guessed, and after an upgrade outside the menu or a tier-up it waits "
                        + "until you open /hotm again. Never clicks anything. Default: on."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("hotm_reminder",
                "a HotM upgrade is affordable", () -> cfg().hotmReminderChannels,
                mask -> { cfg().hotmReminderChannels = mask; save(); }));
        rows.add(SettingRow.toggle("Affordable Upgrades Summary", () -> cfg().hotmReminderSummary,
                        () -> { cfg().hotmReminderSummary = !cfg().hotmReminderSummary; save(); })
                .describe("\"HotM: 3 upgrades affordable\" whenever that number goes up, at most once "
                        + "every 10 minutes. Counts every perk, watched or not. Default: on."));
        rows.add(SettingRow.toggle("Unspent Token Reminder", () -> cfg().hotmReminderTokens,
                        () -> { cfg().hotmReminderTokens = !cfg().hotmReminderTokens; save(); })
                .describe("Once per session, when you arrive on a mining island with Tokens of the "
                        + "Mountain the menu said you had not spent. Also after a HotM tier-up - that "
                        + "chat line has not been confirmed yet, so this half may stay quiet. "
                        + "Default: on."));
        rows.add(SettingRow.toggle("Highlight Affordable Perks", () -> cfg().hotmReminderHighlight,
                        () -> { cfg().hotmReminderHighlight = !cfg().hotmReminderHighlight; save(); })
                .describe("In the Heart of the Mountain menu, a green outline on every perk the menu "
                        + "says you can upgrade or unlock right now, and a gold corner on the perks "
                        + "you watch. Display only. Default: on."));
        rows.add(SettingRow.keybind("Watch Hovered Perk Key", () -> cfg().hotmWatchKey,
                        key -> { cfg().hotmWatchKey = key; save(); })
                .describe("In the Heart of the Mountain menu, press it over a perk to watch it or stop "
                        + "watching it. Nothing in the menu is clicked. Unbound by default. Ignored when "
                        + "it is the same as a key the menu uses itself (Escape, inventory, hotbar 1-9, "
                        + "drop, swap hands, pick block)."));
        rows.add(SettingRow.toggle("HotM Upgrades Line", () -> cfg().hotmReminderHud,
                        () -> { cfg().hotmReminderHud = !cfg().hotmReminderHud; save(); })
                .describe("\"HotM: 3 affordable · 2 tokens\" on mining islands, or \"open /hotm to "
                        + "refresh\" while the costs are out of date. Default: off."));
        rows.add(SettingRow.button("Move / Resize HotM Upgrades Line", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.HOTM_REMINDER}, "Edit HotM Upgrades Line")))
                .describe("Opens the editor where you drag the line anywhere on the screen and "
                        + "scale it."));

        rows.add(SettingRow.label("Watched perks"));
        var store = sbs.modid.client.skills.mining.logic.HotmTreeStore.getInstance();
        boolean any = false;
        for (var perk : sbs.modid.client.skills.mining.logic.HotmReminder.perks(store)) {
            boolean levelling = perk.level() > 0 && perk.level() < perk.maxLevel();
            if (!levelling && !store.watched().contains(perk.id())) {
                continue;   // locked or maxed: nothing to be reminded of, unless already watched
            }
            any = true;
            String next = perk.upgradeKnown()
                    ? String.format(java.util.Locale.ROOT, "%,d", perk.nextCost()) + " "
                            + perk.powder().charAt(0) + perk.powder().substring(1).toLowerCase(java.util.Locale.ROOT)
                            + " Powder"
                    : "not known - open /hotm";
            rows.add(SettingRow.toggle(perk.name() + " " + perk.level() + "/" + perk.maxLevel(),
                            () -> store.watched().contains(perk.id()),
                            () -> store.toggleWatched(perk.id()))
                    .anchor("hotm_watch_" + perk.id())
                    .describe(levelling
                            ? "Remind me when " + perk.name() + " " + (perk.level() + 1)
                                    + " is affordable. Next level costs " + next + "."
                            : perk.name() + " is maxed or locked, so it is never reminded. "
                                    + "Turn this off to drop it from the list."));
        }
        if (!any) {
            rows.add(SettingRow.label("§8Open the Heart of the Mountain menu once - your perks"));
            rows.add(SettingRow.label("§8appear here to watch."));
        }
        return rows;
    }

    /** Glacite Cold card + warning. The line Hypixel shows it on is not captured yet. */
    private static List<SettingRow> coldSettings() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Glacite Cold —"));
        rows.add(SettingRow.toggle("Cold Card", () -> cfg().coldCard,
                        () -> { cfg().coldCard = !cfg().coldCard; save(); })
                .describe("Shows \"Cold: N\" in the Glacite Tunnels and Mineshafts, green to red as it "
                        + "nears the cap. Hidden while no Cold reading has been seen - it never shows "
                        + "a guessed number."));
        rows.add(SettingRow.button("Move / Resize Cold Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.GLACITE_COLD}, "Edit Cold Card")))
                .describe("Opens the editor where you drag the Cold card anywhere on the screen "
                        + "and scale it."));
        rows.add(SettingRow.toggle("Cold Warning", () -> cfg().coldWarning,
                        () -> { cfg().coldWarning = !cfg().coldWarning; save(); })
                .describe("Warns once when Cold reaches the threshold below, and again only after "
                        + "it has dropped back under it."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("cold_warning",
                "Cold gets dangerous", () -> cfg().coldWarningChannels,
                mask -> { cfg().coldWarningChannels = mask; save(); }));
        rows.add(SettingRow.intField("Warn At % Of Cap", 1, 100, () -> cfg().coldWarnPercent,
                        v -> { cfg().coldWarnPercent = v; save(); }, "%")
                .describe("The warning fires at this share of the cap. 75 by default."));
        rows.add(SettingRow.intField("Cold Cap", 1, 1000, () -> cfg().coldCap,
                        v -> { cfg().coldCap = v; save(); }, "")
                .describe("The Cold at which you are removed from the area. 100 is the wiki's "
                        + "value and has not been seen in game yet - correct it here if it differs."));
        return rows;
    }

    /**
     * Ability Ready Alert. Here rather than on the Item Overlay page because mining abilities are
     * what it is for first; it is not held to the mining-island switch above, since axe abilities
     * are used while foraging.
     */
    private static List<SettingRow> abilityReadySettings() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Ability Ready —"));
        rows.add(SettingRow.toggle("Ability Ready Alert", () -> cfg().abilityReady,
                        () -> { cfg().abilityReady = !cfg().abilityReady; save(); })
                .describe("Tells you the moment Hypixel says an ability is available again - "
                        + "\"Pickobulus is now available!\" becomes \"Pickobulus ready\" on the "
                        + "channels below. Works on every island. Axes: only an axe with a "
                        + "right-click ability AND a \"Cooldown: Ns\" lore line gets an alert, "
                        + "marked ESTIMATED when Hypixel prints no ready line. Passive axe "
                        + "abilities (Fig Hew's Frenzy, a Huntaxe's Vis Temperata) and tree "
                        + "felling never alert - there is nothing to be ready."));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("ability_ready",
                "an ability is ready", () -> cfg().abilityReadyChannels,
                mask -> { cfg().abilityReadyChannels = mask; save(); }));
        rows.add(SettingRow.toggle("Only While Holding The Tool", () -> cfg().abilityReadyOnlyHolding,
                        () -> { cfg().abilityReadyOnlyHolding = !cfg().abilityReadyOnlyHolding; save(); })
                .describe("Stays quiet unless the item you last used the ability with is in your "
                        + "hand. Off by default: the alert matters most when you are holding "
                        + "something else. An ability never used with this client alerts anyway."));
        rows.add(SettingRow.toggle("Countdown Line", () -> cfg().abilityReadyHud,
                        () -> { cfg().abilityReadyHud = !cfg().abilityReadyHud; save(); })
                .describe("A small line such as \"Pickobulus: in 42s\" while an ability cools down. "
                        + "Shows \"?\" until the cooldown has been learned once - from the time "
                        + "between using it and Hypixel saying it is available again."));
        rows.add(SettingRow.button("Move / Resize Countdown Line", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.ABILITY_READY}, "Edit Ability Countdown")))
                .describe("Opens the editor where you drag the countdown line anywhere on the "
                        + "screen and scale it."));
        var abilities = cfg().abilityReadyAbilities;
        if (abilities.isEmpty()) {
            rows.add(SettingRow.label("§8No ability seen yet - each one appears here the first"));
            rows.add(SettingRow.label("§8time Hypixel announces it. Mining and axe ones start on."));
        }
        for (String ability : new java.util.ArrayList<>(abilities.keySet())) {
            Integer learned = cfg().abilityReadyLearned.get(ability);
            rows.add(SettingRow.toggle(ability, () -> Boolean.TRUE.equals(abilities.get(ability)),
                            () -> { abilities.put(ability, !Boolean.TRUE.equals(abilities.get(ability))); save(); })
                    .anchor("ability_ready_" + SettingRow.slug(ability))
                    .describe("Alert when " + ability + " is available again. Cooldown: "
                            + (learned == null ? "not learned yet" : learned + "s, learned")
                            + "."));
        }
        return rows;
    }

    private static List<SettingRow> baseSettings() {
        return List.of(
                SettingRow.toggle("Only On Mining Islands", MiningRoutesModule::islandLock,
                        MiningRoutesModule::toggleIslandLock)
                        .describe("Keeps every mining feature quiet unless you are on a mining "
                                + "island. One shared switch - changing it here changes it for "
                                + "all mining modules. The tool card ignores it, because the same "
                                + "tools are swung while foraging."),
                SettingRow.label("Shared by every mining feature; off anywhere else"),
                SettingRow.label("§8" + SkillIslands.describe(SkillIslands.MINING_ISLANDS)),

                SettingRow.toggle("Mining Helpers", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Master switch for the three cards below: commissions, Heart of "
                                + "the Mountain and tool uses."),

                SettingRow.label("— Commissions —"),
                SettingRow.toggle("Commission Card", () -> cfg().commissions,
                        () -> { cfg().commissions = !cfg().commissions; save(); })
                        .describe("A card with your active commissions and a progress bar for "
                                + "each, read live from the tab list - so you can see how close "
                                + "you are without holding tab."),
                SettingRow.toggle("Announce When Done", () -> cfg().commissionDoneAlert,
                        () -> { cfg().commissionDoneAlert = !cfg().commissionDoneAlert; save(); })
                        .describe("A message above your hotbar and a ping the moment a commission "
                                + "hits 100%, so you stop mining it the second it is finished."),
                SettingRow.toggle("Hide Finished Commissions", () -> cfg().hideCompletedCommissions,
                        () -> { cfg().hideCompletedCommissions = !cfg().hideCompletedCommissions; save(); })
                        .describe("Drops a commission from the card once it is done, leaving only "
                                + "the ones you still have to do."),
                SettingRow.button("Move / Resize Commission Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.MINING_COMMISSIONS}, "Edit Commission Card")))
                        .describe("Opens the editor where you drag the commission card anywhere "
                                + "on the screen and scale it."),

                SettingRow.toggle("Show The Way There", () -> cfg().commissionRoute,
                                () -> { cfg().commissionRoute = !cfg().commissionRoute; save(); })
                        .describe("Marks where each running commission is actually done and draws "
                                + "the route to the nearest one. It only shows you the way - nothing "
                                + "walks, warps or clicks for you."),
                SettingRow.label("§8Markers and route are drawn through terrain: the world overlay"),
                SettingRow.label("§8has no depth test, so there is nothing to switch off there"),
                SettingRow.label("§8A commission whose place is unknown says so instead of guessing"),

                SettingRow.toggle("Follow The Nearest", () -> cfg().commissionRouteAuto,
                                () -> { cfg().commissionRouteAuto = !cfg().commissionRouteAuto; save(); })
                        .describe("Routes to whichever commission is nearest by route - not by "
                                + "straight line - and moves on by itself as each one is finished. "
                                + "Off, the route stays where you put it with the cycle key."),
                SettingRow.toggle("Mark Every Commission", () -> cfg().commissionRouteMarkAll,
                                () -> { cfg().commissionRouteMarkAll = !cfg().commissionRouteMarkAll; save(); })
                        .describe("Puts a marker on every commission whose place is known. Off, only "
                                + "the one being routed to is marked, which keeps the screen quiet "
                                + "when four are running at once."),
                SettingRow.keybind("Route Next Commission", () -> cfg().commissionRouteKey,
                                key -> { cfg().commissionRouteKey = key; save(); })
                        .describe("Pins the route to the next commission in the list, and lets go "
                                + "again past the last one."),
                SettingRow.label("§8§f/sbs commission§8 lists them and says what was made of each"),

                SettingRow.label("— Powders —"),
                SettingRow.toggle("Powder Card", () -> cfg().powder,
                        () -> { cfg().powder = !cfg().powder; save(); })
                        .describe("A card with your Mithril, Gemstone and Glacite totals, plus how "
                                + "much of each you have gained this session. Its own card, so it "
                                + "can sit somewhere different from the HotM tier."),
                SettingRow.toggle("Show Powder Per Hour", () -> cfg().powderRate,
                        () -> { cfg().powderRate = !cfg().powderRate; save(); })
                        .describe("Adds a measured per-hour rate beside each powder, so you can "
                                + "compare mining spots. Appears once the session is a minute "
                                + "old - anything shorter is not a rate, it is noise."),
                SettingRow.button("Reset Session Gain", () -> {
                            MiningTracker.getInstance().resetSession();
                            TreasureChestTracker.getInstance().resetSession();
                        })
                        .describe("Sets the powder gained / per hour back to zero and starts "
                                + "measuring again from your current totals. Also clears the "
                                + "treasure chest count."),
                SettingRow.button("Move / Resize Powder Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.MINING_POWDER}, "Edit Powder Card")))
                        .describe("Opens the editor where you drag the powder card anywhere on the "
                                + "screen and scale it."),

                SettingRow.label("— Treasure Chests —"),
                SettingRow.toggle("Treasure Chest Box", () -> cfg().treasureChestBox,
                        () -> { cfg().treasureChestBox = !cfg().treasureChestBox; save(); })
                        .describe("In the Crystal Hollows, outlines the treasure chest you just "
                                + "uncovered until it is opened or disappears. Only your own chest: "
                                + "it is matched to the \"You uncovered a treasure chest!\" line, "
                                + "so a chest another player uncovers beside you is left alone. "
                                + "Default: off."),
                SettingRow.toggle("Lockpick Spot Marker", () -> cfg().lockpickMarker,
                        () -> { cfg().lockpickMarker = !cfg().lockpickMarker; save(); })
                        .describe("While you pick the lock, puts a small box on the spot the "
                                + "particles are showing on your chest, and clears it when you click. "
                                + "It only marks the spot - you aim and click yourself. Particles on "
                                + "anyone else's chest are ignored. Default: off."),
                SettingRow.toggle("Treasure Chest Counter", () -> cfg().treasureChestCounter,
                        () -> { cfg().treasureChestCounter = !cfg().treasureChestCounter; save(); })
                        .describe("Adds the chests you opened this session, and the powder they "
                                + "paid by type with a per-hour rate, to the Powder card - read from "
                                + "the chest's reward lines in chat. Default: off."),
                SettingRow.label("§8Off until the chest's chat lines are confirmed in game"),

                SettingRow.label("— Heart of the Mountain —"),
                SettingRow.toggle("HotM Tier Card", () -> cfg().hotm,
                        () -> { cfg().hotm = !cfg().hotm; save(); })
                        .describe("A small card naming your Heart of the Mountain tier. The powder "
                                + "totals used to share this card and now have their own above."),
                SettingRow.button("Move / Resize HotM Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.MINING_HOTM}, "Edit HotM Card")))
                        .describe("Opens the editor where you drag the Heart of the Mountain card "
                                + "anywhere on the screen and scale it."),

                SettingRow.label("— Tool Uses —"),
                SettingRow.toggle("Tool Uses Card", () -> cfg().toolDurability,
                        () -> { cfg().toolDurability = !cfg().toolDurability; save(); })
                        .describe("Shows how much is left of a limited-use tool you are holding - "
                                + "a Pickonimbus 2000, a foraging axe - as a bar that turns "
                                + "orange and then red as it runs out. Works anywhere, not just "
                                + "on mining islands."),
                SettingRow.toggle("Warn When Nearly Used Up", () -> cfg().toolWarn,
                        () -> { cfg().toolWarn = !cfg().toolWarn; save(); })
                        .describe("A message and a sound once the held tool drops below the "
                                + "percentage below - fires once per tool, not once per swing."),
                SettingRow.intField("Warn Below", 1, 90, () -> cfg().toolWarnPercent,
                        value -> { cfg().toolWarnPercent = value; save(); }, "%")
                        .describe("The remaining-uses percentage that triggers that warning."),
                SettingRow.button("Move / Resize Tool Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.MINING_TOOL}, "Edit Tool Uses Card")))
                        .describe("Opens the editor where you drag the tool card anywhere on the "
                                + "screen and scale it."),
                SettingRow.label("§8Commission + powder lines come from the tab list."),

                SettingRow.label("— Powder Ghast —"),
                SettingRow.label(ghastStatus()),
                SettingRow.label("§8No countdown is shown until the interval has been observed."),
                SettingRow.label("§8A guessed timer costs you real time; unknown does not."),

                SettingRow.label("— Heart of the Mountain tree —"),
                SettingRow.label(hotmStatus()),
                SettingRow.toggle("HotM Advisor Panel", () -> cfg().hotmAdvisorPanel,
                        () -> { cfg().hotmAdvisorPanel = !cfg().hotmAdvisorPanel; save(); })
                        .describe("Shows the advisor's top five next perk levels for your chosen goal "
                                + "beside the Heart of the Mountain menu. Display only - it never clicks "
                                + "a perk. Default: on."),
                SettingRow.button("Open HotM Perk Advisor", () -> open(
                        new sbs.modid.client.skills.mining.ui.HotmAdvisorScreen()))
                        .describe("Pick a goal (Mithril, Gemstone, Glacite, Commissions, Scatha) and see "
                                + "which perk level is worth taking next, your progress towards the "
                                + "recommended tree, and what to skip. Also /sbs hotm."));
    }

    /**
     * What the Powder Ghast observer has, in the player's words. Deliberately states the shortfall
     * rather than a provisional figure - the whole point is that no number is shown until the
     * observations support one.
     */
    private static String ghastStatus() {
        var observer = sbs.modid.client.skills.mining.logic.GhastObserver.getInstance();
        var estimate = observer.estimate();
        return "§8" + observer.sightingCount() + " sighting(s) recorded — " + estimate.describe();
    }

    /** Whether the Heart of the Mountain menu has been read, and what it gave up when it was. */
    private static String hotmStatus() {
        var store = sbs.modid.client.skills.mining.logic.HotmTreeStore.getInstance();
        var report = sbs.modid.client.skills.mining.logic.HotmTreeReader.getInstance().lastReport();
        if (!store.known()) {
            return "§8Not read yet — open the Heart of the Mountain menu once. " + report.describe();
        }
        String age = (store.ageMs() / 60_000L) + " min ago";
        return "§8" + store.levels().size() + " perk level(s), read " + age
                + (store.stale() ? " §e(you have spent powder since — reopen the menu)" : "");
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
