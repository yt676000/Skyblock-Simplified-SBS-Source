/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Active Buffs module (Quality of Life): the God Potion and Booster Cookie timers the tab list hides
 * behind a keypress, as movable HUD cards. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class BuffsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BuffsModule() {
    }

    @Override
    public String id() {
        return "buffs";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.TIMERS;
    }

    @Override
    public String displayName() {
        return "Active Buffs";
    }

    @Override
    public String description() {
        return "God Potion, Booster Cookie, Century Cake and consumable timers as HUD cards (the Custom "
                + "Scoreboard shows the first two as rows by default)";
    }

    @Override
    public int accentColor() {
        return 0xFFE0A0FF;
    }

    private static SBSConfig.BuffsSettings cfg() {
        return ConfigManager.getInstance().get().buffs;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new java.util.ArrayList<>(List.of(
                SettingRow.toggle("Active Buffs", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Puts your God Potion, Booster Cookie and Century Cake remaining "
                                + "times on the HUD as their own movable cards."),
                SettingRow.label("The Custom Scoreboard shows both as rows - these cards are extra"),

                SettingRow.toggle("God Potion Card", () -> cfg().godPotionCard,
                        () -> { cfg().godPotionCard = !cfg().godPotionCard; save(); })
                        .describe("A separate HUD card with your God Potion time left. Off by "
                                + "default because the Custom Scoreboard already has the row - turn "
                                + "it on to have the timer somewhere of its own."),
                SettingRow.toggle("Cookie Buff Card", () -> cfg().cookieCard,
                        () -> { cfg().cookieCard = !cfg().cookieCard; save(); })
                        .describe("A separate HUD card with your Booster Cookie time left. Off by "
                                + "default for the same reason as the God Potion card."),
                SettingRow.label("Each card only shows while that buff is active"),
                SettingRow.label("Hypixel only publishes rough times, so that is what is shown"),

                SettingRow.toggle("Century Cake Card", () -> cfg().cakeCard,
                        () -> { cfg().cakeCard = !cfg().cakeCard; save(); })
                        .describe("A HUD card with the Century Cake buffs you have active and when "
                                + "each runs out. Learned from the \"Yum!\" line when you eat a "
                                + "cake, which says 48 hours, and kept across restarts."),
                SettingRow.toggle("Cakes On One Line", () -> cfg().cakeCollapsed,
                        () -> { cfg().cakeCollapsed = !cfg().cakeCollapsed; save(); })
                        .describe("Shows the cakes as one line - how many are active and when the "
                                + "first one runs out. Off lists every cake with its own time."),
                SettingRow.intField("Cake Warning", 0, 47, () -> cfg().cakeWarnHours,
                                value -> { cfg().cakeWarnHours = value; save(); }, "h before")
                        .describe("Tells you this many hours before your first cake buff runs "
                                + "out, so you can eat a new one in time. 0 turns the warning off."),

                SettingRow.label("Colours - used by the scoreboard rows and the cards alike"),
                SettingRow.color("God Potion Name", () -> cfg().godPotionNameHex,
                                () -> 0xFF000000 | BuffColors.GOD_POTION_NAME_DEFAULT,
                                () -> pick("God Potion Name", () -> cfg().godPotionNameHex,
                                        hex -> cfg().godPotionNameHex = hex))
                        .describe("The words \"God Potion\". Red by default, which is the colour "
                                + "Hypixel writes the potion's own name in."),
                SettingRow.color("God Potion Time", () -> cfg().godPotionTimeHex,
                                () -> 0xFF000000 | BuffColors.GOD_POTION_TIME_DEFAULT,
                                () -> pick("God Potion Time", () -> cfg().godPotionTimeHex,
                                        hex -> cfg().godPotionTimeHex = hex))
                        .describe("The time left beside it. Pink by default, matching the duration "
                                + "in Hypixel's own Active Effects widget."),
                SettingRow.color("Cookie Buff Name", () -> cfg().cookieBuffNameHex,
                                () -> 0xFF000000 | BuffColors.COOKIE_BUFF_NAME_DEFAULT,
                                () -> pick("Cookie Buff Name", () -> cfg().cookieBuffNameHex,
                                        hex -> cfg().cookieBuffNameHex = hex))
                        .describe("The words \"Cookie Buff\". Legendary gold by default - the "
                                + "Booster Cookie's own rarity colour."),
                SettingRow.color("Cookie Buff Time", () -> cfg().cookieBuffTimeHex,
                                () -> 0xFF000000 | BuffColors.COOKIE_BUFF_TIME_DEFAULT,
                                () -> pick("Cookie Buff Time", () -> cfg().cookieBuffTimeHex,
                                        hex -> cfg().cookieBuffTimeHex = hex))
                        .describe("The time left beside it. Green by default, the colour of the "
                                + "cookie's \"Duration:\" line in game."),
                SettingRow.label("Clear a colour to put it back to its default")));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("cake_warning",
                "a cake buff is about to run out", () -> cfg().cakeWarnChannels,
                mask -> { cfg().cakeWarnChannels = mask; save(); }));
        rows.addAll(consumableRows());
        return rows;
    }

    /** Consumable Timers: the card, its sources and one alert block per kind. */
    private static List<SettingRow> consumableRows() {
        List<SettingRow> rows = new java.util.ArrayList<>(List.of(
                SettingRow.label("Consumable Timers"),
                SettingRow.toggle("Consumable Timers", () -> cfg().consumablesEnabled,
                        () -> { cfg().consumablesEnabled = !cfg().consumablesEnabled; save(); })
                        .describe("Tracks every buff you consume that runs out - God Potion, Booster "
                                + "Cookie, mixins, potions, truffles - from chat and the tab list, and "
                                + "alerts before and when each ends. Display and alerts only. Off by "
                                + "default: not yet tested in game."),
                SettingRow.toggle("Consumable Timers Card", () -> cfg().consumablesCard,
                        () -> { cfg().consumablesCard = !cfg().consumablesCard; save(); })
                        .describe("A HUD card listing each active timer, soonest end first. A ~ marks "
                                + "an estimate, a ‖ a paused timer (in a dungeon, or a mixin with no God "
                                + "Potion). On by default."),
                SettingRow.intField("Hide Long Timers", 0, 2000, () -> cfg().consumablesHideAboveHours,
                                value -> { cfg().consumablesHideAboveHours = value; save(); }, "h left")
                        .describe("Leaves timers with more than this many hours left off the card - a "
                                + "cookie with three months to go is not news. 0 shows everything "
                                + "(default)."),
                SettingRow.toggle("Read The Active Effects Menu", () -> cfg().consumablesReadMenu,
                        () -> { cfg().consumablesReadMenu = !cfg().consumablesReadMenu; save(); })
                        .describe("When you open /effects yourself, takes the exact times from it. Off "
                                + "by default: the menu's layout has not been confirmed yet. SBS never "
                                + "opens the menu for you."),
                SettingRow.toggle("Alert When A Buff Runs Out", () -> cfg().consumableEndAlert,
                        () -> { cfg().consumableEndAlert = !cfg().consumableEndAlert; save(); })
                        .describe("Besides the early warning, an alert at the moment a buff ends. On by "
                                + "default.")));
        rows.addAll(kindRows("God Potion", () -> cfg().consumableAlertGodPotion,
                () -> cfg().consumableAlertGodPotion = !cfg().consumableAlertGodPotion,
                () -> cfg().consumableWarnGodPotionMinutes, v -> cfg().consumableWarnGodPotionMinutes = v,
                600, "min before", 30));
        rows.addAll(kindRows("Booster Cookie", () -> cfg().consumableAlertCookie,
                () -> cfg().consumableAlertCookie = !cfg().consumableAlertCookie,
                () -> cfg().consumableWarnCookieHours, v -> cfg().consumableWarnCookieHours = v,
                96, "h before", 12));
        rows.addAll(kindRows("Potion", () -> cfg().consumableAlertPotion,
                () -> cfg().consumableAlertPotion = !cfg().consumableAlertPotion,
                () -> cfg().consumableWarnPotionMinutes, v -> cfg().consumableWarnPotionMinutes = v,
                600, "min before", 5));
        rows.addAll(kindRows("Mixin", () -> cfg().consumableAlertMixin,
                () -> cfg().consumableAlertMixin = !cfg().consumableAlertMixin,
                () -> cfg().consumableWarnMixinMinutes, v -> cfg().consumableWarnMixinMinutes = v,
                600, "min before", 0));
        rows.addAll(kindRows("Other Consumable", () -> cfg().consumableAlertOther,
                () -> cfg().consumableAlertOther = !cfg().consumableAlertOther,
                () -> cfg().consumableWarnOtherMinutes, v -> cfg().consumableWarnOtherMinutes = v,
                600, "min before", 5));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("consumable_alert",
                "a consumable buff is running out or has run out", () -> cfg().consumableAlertChannels,
                mask -> { cfg().consumableAlertChannels = mask; save(); }));
        return rows;
    }

    /** The on/off toggle and the warning lead time of one kind of consumable. */
    private static List<SettingRow> kindRows(String name, java.util.function.BooleanSupplier on,
                                             Runnable flip, java.util.function.IntSupplier lead,
                                             java.util.function.IntConsumer setLead, int max,
                                             String unit, int defaultLead) {
        return List.of(
                SettingRow.toggle(name + " Alerts", on, () -> { flip.run(); save(); })
                        .describe("Alerts for " + name + " timers - the warning below and the \"ran "
                                + "out\" alert. On by default."),
                SettingRow.intField(name + " Warning", 0, max, lead,
                                value -> { setLead.accept(value); save(); }, unit)
                        .describe("Warns this long before a " + name + " runs out, once per drink or "
                                + "eat. 0 leaves only the \"ran out\" alert. Default " + defaultLead
                                + " " + unit.replace(" before", "") + "."));
    }

    /** Opens the shared colour picker on one of the four buff colours. */
    private static void pick(String label, java.util.function.Supplier<String> current,
                             java.util.function.Consumer<String> setter) {
        net.minecraft.client.gui.screens.Screen previous =
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.ui.theme.ThemeColorPickerScreen(
                        "Active Buffs  •  " + label, current.get(),
                        value -> {
                            setter.accept(value == null ? "" : value);
                            save();
                        }, previous));
    }
}
