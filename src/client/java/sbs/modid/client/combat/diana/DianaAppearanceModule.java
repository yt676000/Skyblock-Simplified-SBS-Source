/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.diana.logic.DianaHudLayout;
import sbs.modid.client.combat.diana.logic.DianaMarkerStyles;
import sbs.modid.client.combat.diana.model.DianaMarker;
import sbs.modid.client.combat.diana.model.MarkerStyle;
import sbs.modid.client.combat.diana.ui.DianaLayoutScreen;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.pathfinding.MarkerBox;
import sbs.modid.client.core.pathfinding.MarkerLabelSize;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Diana: Appearance (Combat): how the Diana toolkit's two cards and its world markers look.
 *
 * <p>A card of its own beside {@code Diana} rather than another section of that page, which is
 * already the longest in Combat; the name sorts it directly under {@code Diana}. Nothing here
 * changes what the toolkit detects or decides - only how it is shown. See
 * {@code docs/features/diana-appearance.md}.
 */
public final class DianaAppearanceModule implements SbsModule {

    private static final List<String> BOX_OPTIONS = labels(MarkerBox.values());
    private static final List<String> LABEL_OPTIONS = labels(MarkerLabelSize.values());

    /** ServiceLoader needs a public no-arg constructor. */
    public DianaAppearanceModule() {
    }

    @Override
    public String id() {
        return "diana_appearance";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Diana: Appearance";
    }

    @Override
    public String description() {
        return "Which lines the Diana cards show, and how burrow, guess and creature markers look";
    }

    @Override
    public int accentColor() {
        return 0xFFFFD65A;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    private static SBSConfig.DianaAppearanceSettings appearance() {
        SBSConfig.DianaSettings cfg = cfg();
        if (cfg.appearance == null) {
            cfg.appearance = new SBSConfig.DianaAppearanceSettings();
        }
        return cfg.appearance;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        // --- Cards ---
        rows.add(SettingRow.label("§bCards"));

        rows.add(SettingRow.button("Arrange Card Lines", () -> Minecraft.getInstance()
                        .setScreenAndShow(new DianaLayoutScreen(
                                GuiStateManager.getInstance().getCurrentScreen())))
                .describe("Opens the line editor: choose which lines each Diana card shows and in "
                        + "what order, and move a line from one card to the other. Both cards are "
                        + "previewed beside the lists with sample numbers. Tab, Shift+arrows and "
                        + "Enter do the same job without dragging."));

        rows.add(SettingRow.button("Move Cards", () -> Minecraft.getInstance().setScreenAndShow(
                        new HudEditorScreen(new HudElement[] {HudElement.DIANA_TRACKER,
                                HudElement.DIANA_CREATURES}, "Edit Diana Cards")))
                .describe("Opens the HUD editor with the two Diana cards, to drag and scale them. "
                        + "Switch on Preview While Editing first to see them outside the Hub."));

        rows.add(SettingRow.rangeSlider("Per-Creature Rows", 0, 14,
                        () -> appearance().perCreatureRows,
                        v -> { appearance().perCreatureRows = v; save(); }, "")
                .describe("Most rows the Per-Creature Counts line may add - one per creature type "
                        + "spawned this session. The card never grows past 14 rows either way. "
                        + "Default 14."));

        rows.add(SettingRow.toggle("Tracker Card Title", () -> appearance().trackerTitle,
                        () -> { appearance().trackerTitle = !appearance().trackerTitle; save(); })
                .disabledWhile(() -> appearance().trackerCompact)
                .describe("Draws \"Diana\" at the top of the tracker card. Compact mode has no "
                        + "title. Default on."));

        rows.add(SettingRow.toggle("Tracker Card Compact", () -> appearance().trackerCompact,
                        () -> { appearance().trackerCompact = !appearance().trackerCompact; save(); })
                .describe("No title, tighter padding and a narrower minimum width. Default off."));

        rows.add(SettingRow.toggle("Creature Card Title", () -> appearance().creatureTitle,
                        () -> { appearance().creatureTitle = !appearance().creatureTitle; save(); })
                .disabledWhile(() -> appearance().creatureCompact)
                .describe("Draws \"Mythological\" at the top of the creature card. Compact mode has "
                        + "no title. Default on."));

        rows.add(SettingRow.toggle("Creature Card Compact", () -> appearance().creatureCompact,
                        () -> { appearance().creatureCompact = !appearance().creatureCompact; save(); })
                .describe("No title, tighter padding and a narrower minimum width. Default off."));

        // --- Markers, one block per type ---
        for (DianaMarker type : DianaMarker.values()) {
            markerRows(rows, type);
        }

        // --- Preview and reset ---
        rows.add(SettingRow.label("§bPreview"));

        rows.add(SettingRow.toggle("Preview While Editing", () -> appearance().preview,
                        () -> { appearance().preview = !appearance().preview; save(); })
                .describe("While this settings screen, the line editor or the HUD editor is open, "
                        + "draws both Diana cards with sample numbers and one sample marker of each "
                        + "kind in front of you - anywhere, event or not. Nothing real is touched, "
                        + "and the samples vanish when the screen closes. Default off."));

        rows.add(SettingRow.button("Reset Appearance", DianaAppearanceModule::confirmReset)
                .describe("Puts every line, card option, marker style and the four Diana marker "
                        + "colours back to how they shipped. Asks first."));

        return rows;
    }

    /**
     * The seven rows for one marker type. Short labels under a heading that names the type, with an
     * anchor each - "Box" appears six times on this page, and a label-derived id would collide.
     */
    private static void markerRows(List<SettingRow> rows, DianaMarker type) {
        String name = type.displayName();
        String key = type.key();
        Supplier<MarkerStyle> style = () -> DianaMarkerStyles.editable(cfg(), type);

        rows.add(SettingRow.label("§b" + name + " Marker"));

        rows.add(colorRow(type).anchor(key + "_color")
                .describe(colorDescription(type)));

        rows.add(SettingRow.segmented("Box", BOX_OPTIONS,
                        () -> ordinal(style.get().box, MarkerBox.OUTLINE),
                        i -> { style.get().box = MarkerBox.values()[i]; save(); })
                .anchor(key + "_box")
                .describe(name + " marker: an outline, a filled glow over the block, or both. "
                        + "Default Outline."));

        rows.add(SettingRow.toggle("Beam", () -> style.get().beam,
                        () -> { style.get().beam = !style.get().beam; save(); })
                .anchor(key + "_beam")
                .describe(name + " marker: the light beam rising from it. Default on."));

        rows.add(SettingRow.segmented("Label Size", LABEL_OPTIONS,
                        () -> ordinal(style.get().labelSize, MarkerLabelSize.NORMAL),
                        i -> { style.get().labelSize = MarkerLabelSize.values()[i]; save(); })
                .anchor(key + "_label_size")
                .describe(name + " marker: how large its name is drawn. Default Normal."));

        rows.add(SettingRow.toggle("Distance", () -> style.get().showDistance,
                        () -> { style.get().showDistance = !style.get().showDistance; save(); })
                .anchor(key + "_distance")
                .describe(name + " marker: the distance in blocks after its name. Default on."));

        rows.add(SettingRow.rangeSlider("Opacity", MarkerStyle.MIN_OPACITY, 100,
                        () -> style.get().opacity,
                        v -> { style.get().opacity = v; save(); }, "%")
                .anchor(key + "_opacity")
                .describe(name + " marker: how strongly the box, beam and label are drawn."
                        + (type == DianaMarker.GUESS ? " Runner-up guesses are drawn at a third of "
                        + "this." : "") + " Default 100%."));

        if (type.drawsThroughWallsToday()) {
            rows.add(SettingRow.toggle("Through Walls", () -> style.get().throughWalls,
                            () -> { style.get().throughWalls = !style.get().throughWalls; save(); })
                    .anchor(key + "_through_walls")
                    .describe(name + " marker: keep drawing it when terrain is between you and it. "
                            + "Off, it hides behind hills and walls. Default on."));
        }
    }

    private static SettingRow colorRow(DianaMarker type) {
        return switch (type) {
            case START_BURROW -> color(type, () -> cfg().startColorHex, 0xFF7FD9FF,
                    hex -> cfg().startColorHex = hex);
            case MOB_BURROW -> color(type, () -> cfg().mobColorHex, 0xFFFF8A6B,
                    hex -> cfg().mobColorHex = hex);
            case TREASURE_BURROW -> color(type, () -> cfg().treasureColorHex, 0xFFFFD65A,
                    hex -> cfg().treasureColorHex = hex);
            case GUESS -> color(type, () -> cfg().guessColorHex, 0xFF9BE37F,
                    hex -> cfg().guessColorHex = hex);
            case RARE_CREATURE -> color(type, () -> appearance().rareCreatureColorHex, 0xFFD679FF,
                    hex -> appearance().rareCreatureColorHex = hex);
            case SHARED_CREATURE -> color(type, () -> appearance().sharedCreatureColorHex, 0xFFD679FF,
                    hex -> appearance().sharedCreatureColorHex = hex);
        };
    }

    private static SettingRow color(DianaMarker type, Supplier<String> hex, int fallback,
                                    Consumer<String> setter) {
        return SettingRow.color("Colour", hex, () -> fallback,
                () -> DianaModule.openPicker(type.displayName(), hex, setter));
    }

    private static String colorDescription(DianaMarker type) {
        return switch (type) {
            case RARE_CREATURE, SHARED_CREATURE -> type.displayName() + " marker colour. Empty "
                    + "keeps each creature's own colour, which is the default.";
            default -> type.displayName() + " marker colour - the same setting as on the Diana "
                    + "page.";
        };
    }

    private static void confirmReset() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen back = GuiStateManager.getInstance().getCurrentScreen();
        minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            if (yes) {
                reset(cfg());
                save();
            }
            minecraft.setScreenAndShow(back);
        }, Component.literal("Reset Diana appearance?"),
                Component.literal("Card lines, card options, every marker style and the four "
                        + "marker colours go back to how they shipped. Preview stays as it is.")));
    }

    /** Everything this page edits, back to the shipped look; the preview switch is left alone. */
    static void reset(SBSConfig.DianaSettings cfg) {
        boolean preview = cfg.appearance != null && cfg.appearance.preview;
        SBSConfig.DianaSettings shipped = new SBSConfig.DianaSettings();
        cfg.appearance = new SBSConfig.DianaAppearanceSettings();
        cfg.appearance.preview = preview;
        DianaHudLayout.write(cfg, DianaHudLayout.defaults());
        cfg.startColorHex = shipped.startColorHex;
        cfg.mobColorHex = shipped.mobColorHex;
        cfg.treasureColorHex = shipped.treasureColorHex;
        cfg.guessColorHex = shipped.guessColorHex;
    }

    private static int ordinal(Enum<?> value, Enum<?> fallback) {
        return (value == null ? fallback : value).ordinal();
    }

    private static List<String> labels(MarkerBox[] values) {
        List<String> out = new ArrayList<>();
        for (MarkerBox value : values) {
            out.add(value.label());
        }
        return List.copyOf(out);
    }

    private static List<String> labels(MarkerLabelSize[] values) {
        List<String> out = new ArrayList<>();
        for (MarkerLabelSize value : values) {
            out.add(value.label());
        }
        return List.copyOf(out);
    }
}
