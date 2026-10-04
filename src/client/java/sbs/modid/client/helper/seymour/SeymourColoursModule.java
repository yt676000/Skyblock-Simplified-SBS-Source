/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.seymour.ui.SeymourCollectionScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Seymour Colours (Inventory &amp; Items): how special the random colour of a piece of Seymour's
 * Special Armor is - its hex, the closest armor or dye colour, and its classes and hex patterns - on
 * the tooltip and in a collection read from the storage index. Display only, and everything comes
 * from data SBS already holds: no request of its own. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class SeymourColoursModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SeymourColoursModule() {
    }

    @Override
    public String id() {
        return "seymour_colours";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INVENTORY_ITEMS;
    }

    @Override
    public String displayName() {
        return "Seymour Colours";
    }

    @Override
    public String description() {
        return "How special a Seymour piece's colour is, and every piece across your storages";
    }

    @Override
    public int accentColor() {
        return 0xFFE07AC8;
    }

    private static SBSConfig.SeymourColourSettings cfg() {
        return ConfigManager.getInstance().get().seymourColours;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Seymour Colours", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Rates the random colour on Seymour's Special Armor (Velvet Top Hat, "
                                + "Cashmere Jacket, Satin Trousers, Oxford Shoes): its hex, the armor "
                                + "piece or dye colour it is closest to, and whether it is a pure grey, a "
                                + "pure colour or a hex pattern. Only shows information; colours come "
                                + "from the item data SBS already has. Default: on."),
                SettingRow.button("Open Seymour Collection", SeymourCollectionScreen::open)
                        .describe("Every Seymour piece in your inventory, worn armor and every storage "
                                + "SBS has captured, with its colour, best match and where it is. "
                                + "Sort, search, filter by piece, and copy the list as CSV. Same as "
                                + "/sbs seymour."),
                SettingRow.toggle("Colour Tooltip", () -> cfg().tooltip,
                        () -> { cfg().tooltip = !cfg().tooltip; save(); })
                        .describe("Up to three lines under a Seymour piece's tooltip: its hex, the "
                                + "closest colour with its ΔE (colour difference) and tier, and its tags. "
                                + "A piece with a dye applied says its own hex cannot be read. "
                                + "Default: on."),
                SettingRow.toggle("Also For All Leather Armor", () -> cfg().allLeather,
                        () -> { cfg().allLeather = !cfg().allLeather; save(); })
                        .describe("Shows the same lines on every dyed leather piece, not only "
                                + "Seymour's. A piece is never matched against its own colour. "
                                + "Default: off."),
                decimal("Exact Match Up To", "ΔE, default 1.0", () -> cfg().exactUpTo,
                        value -> cfg().exactUpTo = value)
                        .describe("Largest colour difference (ΔE) still called an exact match. "
                                + "Around 1 is about the smallest difference the eye can see. "
                                + "Default: 1.0."),
                decimal("Near Match Up To", "ΔE, default 2.0", () -> cfg().nearUpTo,
                        value -> cfg().nearUpTo = value)
                        .describe("Largest ΔE called a near match. Never below the exact bound. "
                                + "Default: 2.0."),
                decimal("Close Match Up To", "ΔE, default 5.0", () -> cfg().closeUpTo,
                        value -> cfg().closeUpTo = value)
                        .describe("Largest ΔE called a close match; anything further reads as no "
                                + "close match. Never below the near bound. Default: 5.0."),
                SettingRow.valueField("Hex Words", "C0FFEE, BEEF", 120, 14, () -> cfg().words,
                        value -> { cfg().words = value; save(); })
                        .describe("Your own hex words, separated by commas: 2 to 6 hex digits each "
                                + "(0-9, A-F). A piece whose hex contains one gets a \"Word\" tag. "
                                + "Kept on this computer, not in shared settings. Default: none."));
    }

    /**
     * A text row for a ΔE bound. The field is typed into, so an unfinished or invalid entry is simply
     * not stored; a value is clamped to 0-100.
     */
    private static SettingRow decimal(String label, String hint, java.util.function.DoubleSupplier getter,
                                      DoubleConsumer setter) {
        return SettingRow.valueField(label, hint, 5, 5,
                () -> String.format(Locale.ROOT, "%.1f", getter.getAsDouble()),
                text -> {
                    try {
                        double value = Double.parseDouble(text.trim().replace(',', '.'));
                        if (!Double.isNaN(value)) {
                            setter.accept(Math.max(0, Math.min(100, value)));
                            save();
                        }
                    } catch (NumberFormatException unfinished) {
                        // Still being typed ("", "1."): keep the stored value until it parses.
                    }
                });
    }
}
