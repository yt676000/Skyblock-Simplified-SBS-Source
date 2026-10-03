/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage;

import sbs.modid.client.combat.damage.logic.MeleeDamageTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Damage Attribution module (Combat): hides foreign damage splashes on whitelisted mobs
 * (Diana / Slayer) and attributes your own via click + timer + value matching, with a LootShare
 * eligibility alert. Purely cosmetic/informative – nothing is sent to the server. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class DamageAttributionModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public DamageAttributionModule() {
    }

    @Override
    public String id() {
        return "damage_attribution";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Damage Attribution";
    }

    @Override
    public String description() {
        return "Hide other players' damage splashes on Diana/Slayer mobs; melee damage HUD; "
                + "LootShare alert";
    }

    @Override
    public int accentColor() {
        return 0xFFE0605F;
    }

    private static SBSConfig.DamageAttributionSettings cfg() {
        return ConfigManager.getInstance().get().damageAttribution;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Damage Attribution", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Works out which of the floating damage numbers are YOUR hits, "
                                + "by matching them to your clicks and your usual damage values. "
                                + "It learns those values as you fight - hit mobs solo a few times "
                                + "first so it can calibrate."),
                SettingRow.keybind("Toggle Key", () -> cfg().toggleKey,
                        key -> { cfg().toggleKey = key; save(); })
                        .describe("A key that switches the whole module on and off while playing - "
                                + "handy when you suddenly want to see everyone's numbers again. "
                                + "Click the row, then press a key; Esc unbinds."),
                SettingRow.label("Attributes splashes by click + timer + learned damage values"),
                SettingRow.label("Self-calibrates: hit whitelisted mobs solo a few times first"),

                SettingRow.toggle("Hide Foreign Splashes", () -> cfg().hideForeign,
                        () -> { cfg().hideForeign = !cfg().hideForeign; save(); })
                        .describe("Hides damage numbers that are NOT yours, so a crowded boss "
                                + "arena stops being a wall of other people's numbers."),
                SettingRow.toggle("Hide On Low Confidence", () -> cfg().hideOnLowConfidence,
                        () -> { cfg().hideOnLowConfidence = !cfg().hideOnLowConfidence; save(); })
                        .describe("When the module cannot tell whose number one is, this decides "
                                + "what happens: on = hide it, off = keep it visible. Your own "
                                + "confirmed hits are never hidden either way."),
                SettingRow.label("Off = when uncertain, keep everything visible (own hits never vanish)"),
                SettingRow.toggle("Mark Own Splashes (✔)", () -> cfg().markOwn,
                        () -> { cfg().markOwn = !cfg().markOwn; save(); })
                        .describe("Puts a small check mark on the numbers recognised as your own "
                                + "hits, so you can verify the matching is working."),

                SettingRow.rangeSlider("Match Window", 150, 600, () -> cfg().windowMs,
                        v -> { cfg().windowMs = v; save(); }, "ms")
                        .describe("How long after your click a damage number may appear and still "
                                + "count as that click's hit. Raise it if your ping is high and "
                                + "your own hits get hidden; lower it for stricter matching."),
                SettingRow.rangeSlider("Value Tolerance", 5, 30, () -> cfg().tolerancePct,
                        v -> { cfg().tolerancePct = v; save(); }, "%")
                        .describe("How far a number may differ from your learned damage and still "
                                + "count as yours. Raise it if your damage varies a lot (crits, "
                                + "buffs); lower it for stricter matching."),
                SettingRow.rangeSlider("Max Ferocity Procs", 0, 5, () -> cfg().maxFerocityProcs,
                        v -> { cfg().maxFerocityProcs = v; save(); }, "")
                        .describe("How many extra Ferocity hits one click of yours may produce. "
                                + "Set it to roughly your Ferocity / 100 so those follow-up "
                                + "numbers are kept as yours."),
                SettingRow.rangeSlider("Time Weight", 0, 100, () -> cfg().timeWeightPct,
                        v -> { cfg().timeWeightPct = v; save(); }, "%")
                        .describe("Balances the two clues when deciding whose number one is: high "
                                + "= trust the timing of your click more, low = trust the damage "
                                + "value more. The default middle works for most setups."),

                SettingRow.toggle("Diana / Mythological Mobs", () -> cfg().dianaMobs,
                        () -> { cfg().dianaMobs = !cfg().dianaMobs; save(); })
                        .describe("Runs the attribution on Diana's mythological mobs, where "
                                + "several players often burst the same mob at once."),
                SettingRow.toggle("Slayer Bosses", () -> cfg().slayerBosses,
                        () -> { cfg().slayerBosses = !cfg().slayerBosses; save(); })
                        .describe("Runs the attribution on slayer bosses and minibosses."),
                SettingRow.text("Extra Mobs", "names, comma-separated", 128,
                        () -> cfg().extraMobs == null ? "" : cfg().extraMobs,
                        v -> { cfg().extraMobs = v == null ? "" : v; save(); })
                        .describe("More mob names the attribution should also cover, separated by "
                                + "commas - for anything the two toggles above do not include."),

                SettingRow.toggle("LootShare Alert", () -> cfg().lootshareEnabled,
                        () -> { cfg().lootshareEnabled = !cfg().lootshareEnabled; save(); })
                        .describe("Tells you when you have dealt enough damage to a shared mob to "
                                + "qualify for its loot, so you can stop attacking and move on."),
                SettingRow.rangeSlider("Mob Share", 1, 50, () -> cfg().lootsharePctMobs,
                        v -> { cfg().lootsharePctMobs = v; save(); }, "%")
                        .describe("The damage share (percent of the mob's max health) at which the "
                                + "alert fires on normal mobs. The official threshold is 1%."),
                SettingRow.rangeSlider("Slayer Share", 1, 50, () -> cfg().lootsharePctSlayer,
                        v -> { cfg().lootsharePctSlayer = v; save(); }, "%")
                        .describe("The damage share at which the alert fires on slayer bosses. The "
                                + "official threshold is 10%."),
                SettingRow.label("Wiki eligibility: 1% of max HP on mobs, 10% on slayer bosses"),
                SettingRow.toggle("LootShare Sound", () -> cfg().lootshareSound,
                        () -> { cfg().lootshareSound = !cfg().lootshareSound; save(); })
                        .describe("Plays a ping along with the loot-share alert."),

                SettingRow.label("— Melee Damage HUD —"),
                SettingRow.toggle("Melee Damage Card", () -> cfg().meleeHud,
                        () -> {
                            cfg().meleeHud = !cfg().meleeHud;
                            if (!cfg().meleeHud) {
                                MeleeDamageTracker.getInstance().clear();
                            }
                            save();
                        })
                        .describe("A small card with your last melee hit, your best hit and your "
                                + "damage per second - built from the hits the attribution above "
                                + "recognised as yours."),
                SettingRow.label("Last hit, best hit and DPS - fed by the attribution above"),
                SettingRow.rangeSlider("Melee Window", 2, 30, () -> cfg().meleeHoldSeconds,
                        v -> { cfg().meleeHoldSeconds = v; save(); }, "s")
                        .describe("How many seconds of hits the DPS is averaged over, and how long "
                                + "the card stays up after your last hit."),
                SettingRow.toggle("Show Best Hit", () -> cfg().meleeShowMax,
                        () -> { cfg().meleeShowMax = !cfg().meleeShowMax; save(); })
                        .describe("Adds a line with the biggest single hit of the current fight."),
                SettingRow.toggle("Show DPS", () -> cfg().meleeShowDps,
                        () -> { cfg().meleeShowDps = !cfg().meleeShowDps; save(); })
                        .describe("Adds a line with your average damage per second."),
                SettingRow.button("Move / Resize Melee Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.MELEE_DAMAGE}, "Edit Melee Damage")))
                        .describe("Opens the editor where you drag the melee card anywhere on the "
                                + "screen and scale it."),

                SettingRow.toggle("Debug Chat Log", () -> cfg().debugLog,
                        () -> { cfg().debugLog = !cfg().debugLog; save(); })
                        .describe("Prints one chat line per decision (kept / hidden and why). Only "
                                + "for checking why a number was misjudged - noisy, leave it off "
                                + "normally."),
                SettingRow.label("Heuristic, not a guarantee - dense crowds lower the confidence"));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
