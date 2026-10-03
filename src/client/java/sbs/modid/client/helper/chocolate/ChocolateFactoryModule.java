/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.share.ShareValues;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.chocolate.logic.ChocolateLore;
import sbs.modid.client.helper.chocolate.logic.ChocolateStore;
import sbs.modid.client.helper.chocolate.model.ChocolateSnapshot;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Chocolate Factory helper (Economy): which upgrade pays for itself soonest, and what in the
 * factory needs attention.
 *
 * <p><b>Why Economy.</b> The question this answers is "what do I buy next, and how long until it
 * has paid for itself". That is the Minion Calculator's question with different nouns, and the
 * Minion Calculator is in Economy for the same reason. Filing a cost-benefit calculator under an
 * event heading would put it next to timers and reminders and make the one page a player opens
 * while spending the hardest to find.
 *
 * <p><b>Read-only, and deliberately without a keybind.</b> Chocolate Factory auto-clicking is what
 * Hypixel bans for. Nothing here clicks, and there is no key on this page at all - not even a
 * harmless one - so that somebody looking at a screenshot of these settings can see there is no
 * key because nothing here presses anything.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class ChocolateFactoryModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ChocolateFactoryModule() {
    }

    @Override
    public String id() {
        return "chocolate_factory";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Chocolate Factory";
    }

    @Override
    public String description() {
        return "Ranks factory upgrades by how fast they pay for themselves, and flags what needs you";
    }

    @Override
    public int accentColor() {
        return 0xFFA9714B;
    }

    private static SBSConfig.ChocolateFactorySettings cfg() {
        return ConfigManager.getInstance().get().chocolateFactory;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(32);

        rows.add(SettingRow.toggle("Chocolate Factory", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Reads the open Chocolate Factory menu and works out which upgrade pays "
                        + "for itself soonest, from the menu's own prices. Nothing is clicked for "
                        + "you, here or anywhere in this feature. Default: off - see below.")
                .anchor("chocolate_enabled")
                .inDevelopment());
        rows.add(SettingRow.label("§8Off by default: nobody has opened the factory with §f/sbs probe§8"));
        rows.add(SettingRow.label("§8armed, so the words it looks for below are a guess. Everything it"));
        rows.add(SettingRow.label("§8sees is written to the log as §f[SBS][Chocolate]§8, which is how the"));
        rows.add(SettingRow.label("§8guess gets replaced. An upgrade it cannot read is left out of the"));
        rows.add(SettingRow.label("§8ranking rather than guessed at, so it is never confidently wrong."));
        rows.add(SettingRow.label("§8Status: " + status()));

        // ---------------------------------------------------------------- what it draws
        rows.add(SettingRow.toggle("Highlight The Best Upgrade", () -> cfg().bestUpgradeHighlight,
                        () -> { cfg().bestUpgradeHighlight = !cfg().bestUpgradeHighlight; save(); })
                .describe("Rings the upgrade with the shortest payback and writes each one's "
                        + "payback in its slot, in a single unit so it fits. The exact figures are "
                        + "on the card. Default: on.")
                .anchor("chocolate_best_highlight"));
        rows.add(SettingRow.toggle("Show The Card", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                .describe("A movable card with your chocolate per second, the best buy and what it "
                        + "is still short of, plus the Time Tower and barn. It always says how old "
                        + "the reading is, because the menu is the only place any of it comes from. "
                        + "Default: off.")
                .anchor("chocolate_show_hud"));
        rows.add(SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.CHOCOLATE_FACTORY}, "Edit Chocolate Factory")))
                .describe("Opens the editor where you drag the card anywhere on the screen and "
                        + "scale it."));

        // ---------------------------------------------------------------- stray rabbits
        rows.add(SettingRow.toggle("Stray Rabbit Alert", () -> cfg().strayAlert,
                        () -> { cfg().strayAlert = !cfg().strayAlert; save(); })
                .describe("Rings a stray rabbit's slot and tells you once that it is there. §eIt "
                        + "does not click it§r, and there is no setting that makes it - clicking "
                        + "the factory for you is the one thing this mod will not do. Default: on.")
                .anchor("chocolate_stray_alert"));
        rows.addAll(AlertChannelRows.forAlert("chocolate_stray", "a stray rabbit appears",
                () -> cfg().strayChannels,
                value -> { cfg().strayChannels = value; save(); }));

        // ---------------------------------------------------------------- barn + tower
        rows.add(SettingRow.toggle("Rabbit Barn Warning", () -> cfg().barnWarning,
                        () -> { cfg().barnWarning = !cfg().barnWarning; save(); })
                .describe("Warns when the barn is nearly full, while there is still time to make "
                        + "room before a new rabbit is lost. Default: on.")
                .anchor("chocolate_barn_warning"));
        rows.add(SettingRow.intField("Warn At", 50, 100,
                        () -> cfg().barnWarnPercent,
                        value -> { cfg().barnWarnPercent = value; save(); }, "%")
                .describe("How full the barn has to be before it is worth saying so. A typed field "
                        + "rather than a slider because the exact number is the whole setting. "
                        + "Default: 90%.")
                .anchor("chocolate_barn_percent"));
        rows.add(SettingRow.toggle("Time Tower Warning", () -> cfg().towerAlert,
                        () -> { cfg().towerAlert = !cfg().towerAlert; save(); })
                .describe("Warns when a Time Tower charge is ready and the tower is not running, "
                        + "so a charge is not sitting idle. Read from the last time you had the "
                        + "menu open. Default: on.")
                .anchor("chocolate_tower_alert"));
        rows.addAll(AlertChannelRows.forAlert("chocolate_status",
                "the barn is nearly full or a Time Tower charge is idle",
                () -> cfg().statusChannels,
                value -> { cfg().statusChannels = value; save(); }));

        // ---------------------------------------------------------------- the guessed words
        rows.add(SettingRow.label("§8The words below are what the reader looks for. Correct them from"));
        rows.add(SettingRow.label("§8the log and the whole feature sharpens without a new build."));
        rows.add(textRow("Words For A Price", ChocolateLore.DEFAULT_COST_WORDS,
                () -> cfg().costWords, value -> cfg().costWords = value,
                "Comma-separated pieces of the lore line that states what an upgrade costs. "
                        + "Matched anywhere in the line, case ignored.",
                "chocolate_cost_words"));
        rows.add(textRow("Words For A Rate", ChocolateLore.DEFAULT_RATE_WORDS,
                () -> cfg().rateWords, value -> cfg().rateWords = value,
                "The same, for the line stating chocolate per second. A line with a §f+§r in it "
                        + "is read as what the upgrade adds; one without is read as what the "
                        + "factory already makes.",
                "chocolate_rate_words"));
        rows.add(textRow("Words For A Stray", "stray",
                () -> cfg().strayWords, value -> cfg().strayWords = value,
                "Pieces of a slot's §oname§r that mean it is a stray rabbit.",
                "chocolate_stray_words"));
        rows.add(textRow("Words For The Time Tower", "time tower",
                () -> cfg().towerWords, value -> cfg().towerWords = value,
                "Pieces of a slot's §oname§r that mean it is the Time Tower.",
                "chocolate_tower_words"));
        rows.add(textRow("Words For The Barn", "rabbit barn, barn",
                () -> cfg().barnWords, value -> cfg().barnWords = value,
                "Pieces of a slot's §oname§r that mean it is the Rabbit Barn.",
                "chocolate_barn_words"));

        rows.add(SettingRow.button("Forget The Last Reading",
                        () -> ChocolateStore.getInstance().reset())
                .describe("Clears this profile's captured factory. It fills back in the next time "
                        + "you open the menu."));
        return rows;
    }

    /**
     * One keyword row.
     *
     * <p>The field length comes from {@link ShareValues#MAX_TEXT} rather than a number that looked
     * right: a shareable text setting is truncated to that on import, so a field that accepts more
     * lets the player type something a shared config would silently shorten.
     */
    private static SettingRow textRow(String label, String placeholder,
                                      java.util.function.Supplier<String> get,
                                      java.util.function.Consumer<String> set,
                                      String description, String anchor) {
        return SettingRow.text(label, placeholder, ShareValues.MAX_TEXT, get,
                        value -> { set.accept(value); save(); })
                .describe(description)
                .anchor(anchor);
    }

    /** What the last menu read produced, with its age - never presented as live. */
    private static String status() {
        if (!cfg().enabled) {
            return "§7switched off";
        }
        ChocolateSnapshot snapshot = ChocolateStore.getInstance().snapshot();
        if (snapshot.empty()) {
            return "§7the factory has not been opened yet";
        }
        int ranked = 0;
        for (var upgrade : snapshot.upgrades) {
            if (upgrade.rankable()) {
                ranked++;
            }
        }
        long minutes = (System.currentTimeMillis() - snapshot.capturedAt) / 60_000L;
        String age = minutes < 1 ? "just now"
                : minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        return "§f" + ranked + "§7 of §f" + snapshot.upgrades.size()
                + "§7 slot(s) could be ranked (read " + age + ")";
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
