/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hitboxes;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Entity Hitboxes (Visuals): the vanilla hitbox for chosen entity types, never through walls. */
public final class EntityHitboxesModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public EntityHitboxesModule() {
    }

    @Override
    public String id() {
        return "entity_hitboxes";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Entity Hitboxes";
    }

    @Override
    public String description() {
        return "The hitbox F3+B shows, for only the entity types you pick - hidden behind walls";
    }

    @Override
    public int accentColor() {
        return 0xFFFFAA00;
    }

    private static SBSConfig.EntityHitboxSettings cfg() {
        return ConfigManager.getInstance().get().entityHitboxes;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Entity Hitboxes", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Draws the same hitbox as F3+B, but only around the kinds of entities you "
                        + "pick below, each in its own colour. Boxes are hidden behind walls like "
                        + "vanilla's. F3+B keeps working on its own: while it is on, it shows every "
                        + "box and this adds nothing. Default: off."));
        rows.add(SettingRow.label("F3+B boxes for the entity types you choose, not through walls"));

        rows.add(SettingRow.label("— Categories —"));
        category(rows, "Players", "Other real players - the ones on the tab list.",
                () -> cfg().players, v -> cfg().players = v, () -> cfg().playersHex, v -> cfg().playersHex = v,
                HitboxCategory.PLAYER);
        category(rows, "Self", "Your own player, in third person (in first person there is nothing to see).",
                () -> cfg().self, v -> cfg().self = v, () -> cfg().selfHex, v -> cfg().selfHex = v,
                HitboxCategory.SELF);
        category(rows, "SkyBlock Mobs", "Mobs with a SkyBlock nametag (\"[Lv5] Lapis Zombie\"), read the "
                        + "same way Mob Highlight reads them.",
                () -> cfg().skyblockMobs, v -> cfg().skyblockMobs = v,
                () -> cfg().skyblockMobsHex, v -> cfg().skyblockMobsHex = v, HitboxCategory.SKYBLOCK_MOB);
        category(rows, "Passive Animals", "Animals and other living entities that are not hostile and "
                        + "carry no SkyBlock nametag.",
                () -> cfg().passive, v -> cfg().passive = v, () -> cfg().passiveHex, v -> cfg().passiveHex = v,
                HitboxCategory.PASSIVE);
        category(rows, "Hostile Mobs", "Vanilla hostile mobs without a SkyBlock nametag.",
                () -> cfg().hostile, v -> cfg().hostile = v, () -> cfg().hostileHex, v -> cfg().hostileHex = v,
                HitboxCategory.HOSTILE);
        category(rows, "Armor Stands", "Armour stands. Off by default: Hypixel builds nametags and "
                        + "holograms out of thousands of them, and they are only looked at while this is on.",
                () -> cfg().armorStands, v -> cfg().armorStands = v,
                () -> cfg().armorStandsHex, v -> cfg().armorStandsHex = v, HitboxCategory.ARMOR_STAND);
        category(rows, "Item Drops", "Items lying on the ground.",
                () -> cfg().items, v -> cfg().items = v, () -> cfg().itemsHex, v -> cfg().itemsHex = v,
                HitboxCategory.ITEM);
        category(rows, "Projectiles", "Arrows, pearls, fireballs and other things in flight.",
                () -> cfg().projectiles, v -> cfg().projectiles = v,
                () -> cfg().projectilesHex, v -> cfg().projectilesHex = v, HitboxCategory.PROJECTILE);
        category(rows, "Other", "Everything else - minecarts, boats, item frames - and NPCs while Hide "
                        + "NPCs is off.",
                () -> cfg().other, v -> cfg().other = v, () -> cfg().otherHex, v -> cfg().otherHex = v,
                HitboxCategory.OTHER);

        rows.add(SettingRow.label("— Options —"));
        rows.add(SettingRow.rangeSlider("Max Distance", 4, 128, () -> cfg().maxDistance,
                        v -> { cfg().maxDistance = v; save(); }, " blocks")
                .describe("Only entities within this distance of the camera get a box. Default: 32."));
        rows.add(SettingRow.toggle("Hide NPCs", () -> cfg().hideNpcs,
                        () -> { cfg().hideNpcs = !cfg().hideNpcs; save(); })
                .describe("Leaves out player-shaped NPCs: anything shaped like a player that is not "
                        + "on the tab list, the same rule Hide Players uses. Off, NPCs are boxed as "
                        + "Other. Default: on."));
        rows.add(SettingRow.toggle("Include Invisible", () -> cfg().includeInvisible,
                        () -> { cfg().includeInvisible = !cfg().includeInvisible; save(); })
                .describe("Also boxes invisible entities. Off by default: invisible armour stands "
                        + "are how Hypixel draws floating text, and boxing them clutters every hub."));
        rows.add(SettingRow.toggle("Eye Line And Look Direction", () -> cfg().eyeAndLook,
                        () -> { cfg().eyeAndLook = !cfg().eyeAndLook; save(); })
                .describe("Vanilla's extras inside the box: a red line at eye height and a blue arrow "
                        + "in the direction the entity is looking. Default: on."));
        return rows;
    }

    /** One category: its toggle and its colour. */
    private static void category(List<SettingRow> rows, String label, String what, BooleanSupplier get,
                                 Consumer<Boolean> set, Supplier<String> hex, Consumer<String> setHex,
                                 HitboxCategory category) {
        rows.add(SettingRow.toggle(label, get, () -> { set.accept(!get.getAsBoolean()); save(); })
                .describe(what));
        rows.add(SettingRow.color(label + " Colour", hex, () -> 0xFF000000 | category.defaultRgb(), () -> {
                    var previous = GuiStateManager.getInstance().getCurrentScreen();
                    Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                            "Entity Hitboxes  •  " + label, hex.get(), value -> {
                                setHex.accept(value == null ? "" : value);
                                save();
                            }, previous));
                })
                .describe("The box colour for " + label.toLowerCase(java.util.Locale.ROOT)
                        + ". Leave it empty for the default."));
    }
}
