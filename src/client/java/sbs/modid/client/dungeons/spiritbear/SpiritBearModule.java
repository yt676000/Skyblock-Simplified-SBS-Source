/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.spiritbear;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Spirit Bear module (Dungeons): Thorn's bear in the F4/M4 boss room - the arena's lantern ring read
 * as a progress bar, the spawn countdown once it fills, the box + call-out when the bear lands, and
 * the beam on the Spirit Bow it drops. The bow is here rather than in a module of its own because it
 * only exists as the bear's corpse's leftovers; splitting them would mean two toggles for one fight.
 *
 * <p>Its own module rather than part of the Blood Helper: the bear belongs to the boss fight and the
 * blood room belongs to the Watcher, and the two share nothing but a floor number. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class SpiritBearModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SpiritBearModule() {
    }

    @Override
    public String id() {
        return "spirit_bear";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public String displayName() {
        return "Spirit Bear";
    }

    @Override
    public String description() {
        return "Thorn's lantern ring, the bear countdown and the Spirit Bow drop (F4/M4 boss room)";
    }

    @Override
    public int accentColor() {
        return 0xFFFFC020;
    }

    private static SBSConfig.SpiritBearSettings cfg() {
        return ConfigManager.getInstance().get().spiritBear;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Spirit Bear", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Reads the lantern ring in Thorn's arena, counts the bear down "
                                + "once the ring is full and boxes it when it lands. Only ever "
                                + "active in the F4 and M4 boss room."),
                SettingRow.label("The ring by the arena wall is the kill counter: coal = unlit, lantern = lit"),
                SettingRow.label("It is found by shape each fight - no coordinates, no per-floor numbers"),

                SettingRow.toggle("Highlight The Bear", () -> cfg().highlightBear,
                        () -> { cfg().highlightBear = !cfg().highlightBear; save(); })
                        .describe("Boxes the Spirit Bear through the arena the moment it appears, "
                                + "with a pointer line from your crosshair to it."),
                SettingRow.toggle("Highlight The Bow", () -> cfg().highlightBow,
                        () -> { cfg().highlightBow = !cfg().highlightBow; save(); })
                        .describe("Marks the Spirit Bow the bear drops with a beam you can see from "
                                + "the far wall, plus a box, a pointer line and how far away it "
                                + "is. Goes away the moment somebody picks it up."),
                SettingRow.label("§8Thorn cannot be finished until the bow is in somebody's hand"),
                SettingRow.toggle("Bear Call-out", () -> cfg().alert,
                        () -> { cfg().alert = !cfg().alert; save(); })
                        .describe("Flashes RING FULL when the last lantern lights, SPIRIT BEAR when "
                                + "it lands and SPIRIT BOW when the drop hits the floor, with a ping "
                                + "and one chat line each."),
                SettingRow.intField("Spawn Delay", 1, 30, () -> cfg().spawnDelaySeconds,
                        value -> { cfg().spawnDelaySeconds = value; save(); }, "s")
                        .describe("How long the bear takes to materialise after the ring fills. "
                                + "Only the value the countdown starts from - the card switches to "
                                + "the time actually measured as soon as a bear has landed."),
                SettingRow.label("§8Estimates show a ~; a measured spawn gap is printed plainly"),

                SettingRow.toggle("Spirit Bear HUD", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                        .describe("The card with the ring progress, the spawn countdown and the "
                                + "bear's state."),
                SettingRow.button("Edit Spirit Bear GUI", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.SPIRIT_BEAR}, "Edit Spirit Bear GUI")))
                        .describe("Opens the editor where you drag the Spirit Bear card anywhere "
                                + "on the screen and scale it."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
