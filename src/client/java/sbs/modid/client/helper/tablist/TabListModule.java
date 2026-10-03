/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.tablist;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * SBS Tab-List module (Interface): restyles the player list shown while the tab key is held.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. Every
 * option writes straight to {@link SBSConfig.TabListSettings} and saves, and
 * {@link TabListRenderer} reads that live, so each change lands on the next frame – no restart.
 * The look itself (colours, corner shape, surface) comes from the SBS theme and follows the active
 * UI style; the options here control how see-through it is, which rows get a face and a ping, and
 * what the stats strip along the top shows.
 */
public final class TabListModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public TabListModule() {
    }

    @Override
    public String id() {
        return "sbs_tab_list";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INTERFACE;
    }

    @Override
    public String displayName() {
        return "SBS Tab-List";
    }

    @Override
    public String description() {
        return "Restyle the player list (tab) in the SBS design: heads and ping on real players "
                + "only, a server / TPS / FPS / ping strip, column dividers and opacity sliders";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.TabListSettings cfg() {
        return ConfigManager.getInstance().get().tabList;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private static String pingLabel(int mode) {
        return switch (mode) {
            case SBSConfig.TabListSettings.PING_NUMBER -> "Number";
            case SBSConfig.TabListSettings.PING_HIDDEN -> "Hidden";
            default -> "Bars";
        };
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("SBS Tab-List", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Replaces the vanilla player list (held tab key) with an SBS "
                                + "panel: same players, header and footer, drawn in the mod's "
                                + "design and following the active UI style. Changes apply live."),
                SettingRow.label("Same content as vanilla - only the look changes"),

                SettingRow.rangeSlider("Background Opacity", 0, 100,
                        () -> cfg().backgroundOpacity,
                        value -> { cfg().backgroundOpacity = value; save(); }, "%")
                        .describe("How solid the panel and row cells are: 0 = invisible, 100 = "
                                + "fully opaque. Low by default so the list floats over the world "
                                + "instead of blacking out the screen."),
                SettingRow.rangeSlider("Text Opacity", 0, 100,
                        () -> cfg().textOpacity,
                        value -> { cfg().textOpacity = value; save(); }, "%")
                        .describe("How solid the names, heads and header/footer text are: 0 = "
                                + "invisible, 100 = fully opaque."),

                SettingRow.toggle("Player Heads", () -> cfg().showHeads,
                        () -> { cfg().showHeads = !cfg().showHeads; save(); })
                        .describe("The 8x8 skin face in front of each name. Off, rows are text "
                                + "only and the columns get a little narrower."),
                SettingRow.toggle("Heads On Players Only", () -> cfg().headsPlayersOnly,
                        () -> { cfg().headsPlayersOnly = !cfg().headsPlayersOnly; save(); })
                        .describe("Give the skin face only to rows that are actually players. On "
                                + "by default: most of Hypixel's tab is not players at all - the "
                                + "side columns are Info, Skills and Stats widgets - and every one "
                                + "of them used to get the same default Steve face, which is what "
                                + "made the columns read as a crowd instead of as text. Off puts "
                                + "a face back on every row, the way vanilla draws it."),
                SettingRow.toggle("Ping On Players Only", () -> cfg().pingPlayersOnly,
                        () -> { cfg().pingPlayersOnly = !cfg().pingPlayersOnly; save(); })
                        .describe("Show the ping only on rows that are actually players, on the "
                                + "same test as the heads above. On by default - a signal icon "
                                + "next to \"Fairy Souls: 80/80\" measures nothing. Needs Hide "
                                + "Ping off to show anything at all."),
                SettingRow.toggle("Hide Ping", () -> cfg().hidePing,
                        () -> { cfg().hidePing = !cfg().hidePing; save(); })
                        .describe("Removes the ping from every row, players included, and the one "
                                + "option here that also applies to the vanilla player list - so "
                                + "the bars are gone whether or not the SBS Tab-List above is on. "
                                + "Off by default now that Ping On Players Only takes care of the "
                                + "widget rows; if you had it on it stays on."),
                SettingRow.options("Ping Style", () -> List.of("Bars", "Number", "Hidden"),
                        () -> pingLabel(cfg().pingDisplay),
                        picked -> {
                            cfg().pingDisplay = switch (picked) {
                                case "Number" -> SBSConfig.TabListSettings.PING_NUMBER;
                                case "Hidden" -> SBSConfig.TabListSettings.PING_HIDDEN;
                                default -> SBSConfig.TabListSettings.PING_BARS;
                            };
                            save();
                        })
                        .describe("With Hide Ping off, how the ping shows at the row's right edge "
                                + "in the SBS list: the vanilla connection bars, the number in a "
                                + "traffic-light color, or hidden. The vanilla list always draws "
                                + "its bars."),
                SettingRow.toggle("Show Header", () -> cfg().showHeader,
                        () -> { cfg().showHeader = !cfg().showHeader; save(); })
                        .describe("The server's text above the player columns - on Hypixel the "
                                + "\"You are playing on...\" banner."),
                SettingRow.toggle("Show Footer", () -> cfg().showFooter,
                        () -> { cfg().showFooter = !cfg().showFooter; save(); })
                        .describe("The server's text below the player columns - on Hypixel the "
                                + "active effects, cookie buff and store lines."),
                SettingRow.toggle("Text Shadow", () -> cfg().textShadow,
                        () -> { cfg().textShadow = !cfg().textShadow; save(); })
                        .describe("The dark drop shadow behind the text - easier to read over "
                                + "bright terrain, especially with the background faded out."),

                SettingRow.toggle("Show Border", () -> cfg().showBorder,
                        () -> { cfg().showBorder = !cfg().showBorder; save(); })
                        .describe("A thin outline around the panel in the theme's accent color."),
                SettingRow.rangeSlider("Border Opacity", 0, 100,
                        () -> cfg().borderOpacity,
                        value -> { cfg().borderOpacity = value; save(); }, "%")
                        .describe("How solid the outline is: 0 = invisible, 100 = fully opaque."),

                SettingRow.toggle("Row Cells", () -> cfg().rowCells,
                        () -> { cfg().rowCells = !cfg().rowCells; save(); })
                        .describe("A shaded box behind every single row, the way vanilla draws "
                                + "them. Off by default: a four-column SkyBlock tab is eighty of "
                                + "those, and without them the columns read as blocks of text. "
                                + "Follows Background Opacity when on."),
                SettingRow.toggle("Column Dividers", () -> cfg().columnDividers,
                        () -> { cfg().columnDividers = !cfg().columnDividers; save(); })
                        .describe("A thin vertical rule between the columns, so the player column "
                                + "and the Info widgets beside it stop running together. Only "
                                + "shows when there is more than one column."),
                SettingRow.rangeSlider("Divider Opacity", 0, 100,
                        () -> cfg().dividerOpacity,
                        value -> { cfg().dividerOpacity = value; save(); }, "%")
                        .describe("How solid those rules are: 0 = invisible, 100 = fully opaque."),

                SettingRow.toggle("Stats Header", () -> cfg().statsHeader,
                        () -> { cfg().statsHeader = !cfg().statsHeader; save(); })
                        .describe("A strip along the top of the list with the instance you are on, "
                                + "the server's tick rate, your frame rate and your ping. None of "
                                + "these come from Hypixel - they are the same numbers the Server "
                                + "Stats card measures, put where you are already looking when you "
                                + "hold tab. A value that cannot be read right now is left out "
                                + "rather than shown as a guess."),
                SettingRow.toggle("Stats: Server", () -> cfg().statsServer,
                        () -> { cfg().statsServer = !cfg().statsServer; save(); })
                        .describe("The instance id of the lobby you are on (\"mini24CD\"). Hidden "
                                + "automatically while Streamer Mode is hiding it everywhere else."),
                SettingRow.toggle("Stats: TPS", () -> cfg().statsTps,
                        () -> { cfg().statsTps = !cfg().statsTps; save(); })
                        .describe("The server's measured tick rate, green at a healthy 20 and red "
                                + "once it drops far below. Estimated from packet timing, so it "
                                + "needs a few seconds on a server before it says anything."),
                SettingRow.toggle("Stats: FPS", () -> cfg().statsFps,
                        () -> { cfg().statsFps = !cfg().statsFps; save(); })
                        .describe("Your own frame rate. Left in one colour on purpose - what counts "
                                + "as a good number depends on your monitor, not on the mod."),
                SettingRow.toggle("Stats: Ping", () -> cfg().statsPing,
                        () -> { cfg().statsPing = !cfg().statsPing; save(); })
                        .describe("Your real round-trip ping, measured by the mod rather than read "
                                + "off the tab list - Hypixel reports about 1ms there, through the "
                                + "proxy, which is not your connection."));
    }
}
