/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fonts module (Interface &amp; Theme): which font each kind of SBS text is drawn in.
 *
 * <p>Four rows rather than one, because the four kinds of text want different things - a column of
 * coin counts wants fixed-width digits, a paragraph of tooltip prose does not. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>The dropdown works in display names while the config stores {@link SbsFont#id()}, and the two
 * are mapped through {@link #labels()} on every read <i>and</i> every write, from the same builder.
 * Deriving one from the other in only one direction is how a font list picks the wrong entry the
 * first time two fonts share a name.
 */
public final class FontsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FontsModule() {
    }

    @Override
    public String id() {
        return "fonts";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INTERFACE;
    }

    @Override
    public String displayName() {
        return "Fonts";
    }

    @Override
    public String description() {
        return "The font SBS menus, HUD, chat lines and tooltips are drawn in";
    }

    @Override
    public int accentColor() {
        return 0xFFB48EF0;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /**
     * Display label for every registered font, mapped to its id.
     *
     * <p>Labels are made unique by appending the id to any name that appears twice - two files both
     * calling themselves "Inter" is entirely possible once the player supplies their own, and a
     * dropdown with two identical rows cannot say which one is selected.
     */
    private static Map<String, String> labels() {
        List<SbsFont> fonts = SbsFontRegistry.all();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (SbsFont font : fonts) {
            seen.merge(decorate(font), 1, Integer::sum);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (SbsFont font : fonts) {
            String label = decorate(font);
            out.put(seen.get(label) > 1 ? label + " [" + font.id() + "]" : label, font.id());
        }
        return out;
    }

    /** User fonts are marked, so it is obvious which entries came off the player's own disk. */
    private static String decorate(SbsFont font) {
        return font.origin() == FontOrigin.USER
                ? font.displayName() + " (yours)"
                : font.displayName();
    }

    private static SettingRow fontRow(FontSlot slot) {
        return SettingRow.options(slot.displayName(),
                        () -> List.copyOf(labels().keySet()),
                        () -> labelOf(slot),
                        label -> {
                            String id = labels().get(label);
                            if (id != null) {
                                slot.store().accept(id);
                                save();
                            }
                        })
                .describe(slot.blurb() + ". Applies to SBS text only - Minecraft's own text keeps "
                        + "the game font unless you switch that on separately. Takes effect "
                        + "immediately; no restart and no resource reload. "
                        + "Default: Minecraft (default).");
    }

    /** The label currently selected, or the missing-font notice when the id no longer resolves. */
    private static String labelOf(FontSlot slot) {
        String id = slot.stored().get();
        for (Map.Entry<String, String> entry : labels().entrySet()) {
            if (entry.getValue().equals(id)) {
                return entry.getKey();
            }
        }
        return "Minecraft (default)";
    }

    /**
     * One line per slot whose selection no longer exists, naming what was lost.
     *
     * <p>A font can vanish between sessions - the player deletes the file, or a build stops shipping
     * one. Falling back silently would leave somebody staring at the game font wondering which
     * setting broke, so the page says which selection is gone and keeps the id in the config so
     * putting the file back restores it.
     */
    private static List<SettingRow> missingNotices() {
        List<SettingRow> rows = new ArrayList<>();
        for (FontSlot slot : FontSlot.values()) {
            String id = slot.stored().get();
            if (SbsFontRegistry.isMissing(id)) {
                rows.add(SettingRow.label("§c" + slot.displayName() + ": \"" + id
                        + "\" is not installed - using the game font"));
            }
        }
        return rows;
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(missingNotices());
        rows.add(SettingRow.label("Each kind of SBS text picks its own font"));
        for (FontSlot slot : FontSlot.values()) {
            rows.add(fontRow(slot));
        }
        rows.add(SettingRow.label("§8Minecraft's own text is left alone"));
        rows.add(SettingRow.label("§8Runes are the enchanting-table alphabet - unreadable on purpose"));
        return List.copyOf(rows);
    }
}
