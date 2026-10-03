/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberTextFormat;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.helper.scoreboard.CustomScoreboardScreen;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Custom Scoreboard module (Visuals): a fully styleable replacement for Hypixel's plain sidebar.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; declares its own
 * identity and settings page here so it touches no shared file. Every option writes straight to
 * {@link SBSConfig.CustomScoreboardSettings} and saves, and the renderer reads that live, so each
 * change lands on the next frame – no restart. Line hide/reorder and position/scale open their own
 * editors.
 *
 * <p>The page is long enough to need signposting, so it is split into the four questions a player
 * actually asks in order: what does the title look like, what do the lines look like, what does the
 * panel around them look like, and what is written on it.
 */
public final class CustomScoreboardModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CustomScoreboardModule() {
    }

    @Override
    public String id() {
        return "custom_scoreboard";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INTERFACE;
    }

    @Override
    public String displayName() {
        return "Custom Scoreboard";
    }

    @Override
    public String description() {
        return "Replace the sidebar with a styleable one: hide / reorder lines, set colours, alignment, spacing, background and extra rows";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.CustomScoreboardSettings cfg() {
        return ConfigManager.getInstance().get().customScoreboard;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    /**
     * The stored hex, or {@code ""} while it is still the stock value.
     *
     * <p>A field sitting at its stock value is one the player never picked, and the renderer paints
     * those in the SBS theme's colour instead. Answering "unset" here is what makes the row's swatch
     * show that themed colour and read {@code Default}, rather than a stock hex the panel is not
     * actually drawn in.
     */
    private static String picked(String hex, String stockHex) {
        Integer value = OverlayColor.parseHex(hex);
        Integer stock = OverlayColor.parseHex(stockHex);
        return value == null || value.equals(stock) ? "" : hex;
    }

    /** Opens the shared colour picker for one of the panel's colours, seeded with what is drawn now. */
    private static void openPicker(String label, Supplier<String> getter, String stockHex,
                                   int themedColor, Consumer<String> setter) {
        String current = picked(getter.get(), stockHex);
        if (current.isEmpty()) {
            current = String.format(Locale.ROOT, "%06X", themedColor & 0xFFFFFF);
        }
        open(new ThemeColorPickerScreen("Scoreboard  •  " + label, current, setter,
                GuiStateManager.getInstance().getCurrentScreen()));
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Custom Scoreboard", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Replaces the sidebar on the right with a styleable SBS one: "
                                + "hide or reorder lines, pick colors, alignment, spacing, background "
                                + "and rounded corners. Changes apply live."),
                SettingRow.label("Replaces the vanilla sidebar - applied live, no restart"),

                SettingRow.button("Reorder Lines", () -> open(new CustomScoreboardScreen()))
                        .describe("Opens the layout editor: drag rows between the palette and the "
                                + "scoreboard to choose which appear and in what order, with the "
                                + "real panel previewed beside them. Rows that are not on screen "
                                + "right now - dungeon, event, Garden - can be positioned from "
                                + "anywhere, and blanks and separators can be dropped in for "
                                + "spacing. Tab, Shift+arrows and Enter do the same job without "
                                + "dragging."),
                SettingRow.toggle("Hide Website Row", () -> cfg().hideWebsite,
                        () -> { cfg().hideWebsite = !cfg().hideWebsite; save(); })
                        .describe("Drops the server's www.hypixel.net advertising line. It is the "
                                + "one sidebar row that tells you nothing you did not already know, "
                                + "and it costs a line plus the blank above it."),
                SettingRow.button("Move / Resize", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.CUSTOM_SCOREBOARD}, "Edit Custom Scoreboard")))
                        .describe("Opens the editor where you drag the scoreboard anywhere on the "
                                + "screen and scale it."),

                SettingRow.label("— Title —"),
                SettingRow.toggle("Show Title", () -> cfg().showTitle,
                        () -> { cfg().showTitle = !cfg().showTitle; save(); })
                        .describe("The SKYBLOCK title line at the top of the sidebar."),
                SettingRow.text("Custom Title", "empty = server title", 32,
                        () -> cfg().customTitle,
                        value -> { cfg().customTitle = value; save(); })
                        .describe("Your own text for the title line. Leave it empty to keep the "
                                + "server's title."),
                SettingRow.color("Title Color",
                        () -> picked(cfg().titleColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_TITLE_COLOR),
                        () -> SBSTheme.ACCENT,
                        () -> openPicker("Title", () -> cfg().titleColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_TITLE_COLOR, SBSTheme.ACCENT,
                                value -> cfg().titleColorHex = value))
                        .describe("The title's color. Click to pick one; until you do it follows "
                                + "your SBS theme."),
                SettingRow.enumOptions("Title Alignment",
                        () -> ScoreboardAlignment.orDefault(cfg().titleAlignment,
                                ScoreboardAlignment.CENTER),
                        value -> { cfg().titleAlignment = value; save(); },
                        v -> v.displayName())
                        .describe("Which edge the title lines up against: left, centered like "
                                + "Hypixel's own heading, or right. Click to cycle."),
                SettingRow.toggle("Bold Title", () -> cfg().titleBold,
                        () -> { cfg().titleBold = !cfg().titleBold; save(); })
                        .describe("Draws the title in bold so it reads as a heading rather than as "
                                + "the first row of the list."),
                SettingRow.toggle("Rainbow Title", () -> cfg().rainbowTitle,
                        () -> { cfg().rainbowTitle = !cfg().rainbowTitle; save(); })
                        .describe("Cycles the title through the color wheel instead of painting it "
                                + "one color. Overrides Title Color while it is on."),

                SettingRow.label("— Lines —"),
                SettingRow.toggle("Keep Original Line Colors", () -> cfg().useOriginalColors,
                        () -> { cfg().useOriginalColors = !cfg().useOriginalColors; save(); })
                        .describe("Keeps the colors Hypixel gives each line (red purse, green "
                                + "location...). Off, every line uses the single color below."),
                SettingRow.toggle("Solid Line Colors", () -> cfg().solidLineColors,
                        () -> { cfg().solidLineColors = !cfg().solidLineColors; save(); })
                        .describe("Gives each row one colour instead of a plain label followed by a "
                                + "coloured number - the whole Purse row gold, the whole Bits row "
                                + "blue. Uses the colour Hypixel gave the value. Needs Keep "
                                + "Original Line Colors on."),
                SettingRow.label("§8\"Bits: 11,315\" all blue instead of a white label"),
                SettingRow.color("Line Color",
                        () -> picked(cfg().lineColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_LINE_COLOR),
                        () -> SBSTheme.TEXT,
                        () -> openPicker("Lines", () -> cfg().lineColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_LINE_COLOR, SBSTheme.TEXT,
                                value -> cfg().lineColorHex = value))
                        .describe("The one color for all lines while Keep Original Line Colors is "
                                + "off. Click to pick it."),
                SettingRow.enumOptions("Line Alignment",
                        () -> ScoreboardAlignment.orDefault(cfg().alignment,
                                ScoreboardAlignment.LEFT),
                        value -> { cfg().alignment = value; save(); },
                        v -> v.displayName())
                        .describe("Which edge the rows line up against. Right-aligned reads well "
                                + "when the panel sits against the right screen border. Click to "
                                + "cycle."),
                SettingRow.enumOptions("Number Format",
                        () -> NumberTextFormat.orServer(cfg().numberFormat),
                        value -> { cfg().numberFormat = value; save(); },
                        v -> v.displayName())
                        .describe("How the big values are written: Server leaves them alone, "
                                + "Shortened writes 12.7M, Full writes 12,700,000. Dates, times and "
                                + "coordinates are never touched."),
                SettingRow.toggle("Text Shadow", () -> cfg().textShadow,
                        () -> { cfg().textShadow = !cfg().textShadow; save(); })
                        .describe("The dark drop shadow behind the text - easier to read over "
                                + "bright terrain, cleaner look without."),
                SettingRow.toggle("Text Outline", () -> cfg().textOutline,
                        () -> { cfg().textOutline = !cfg().textOutline; save(); })
                        .describe("Traces every line in black on all four sides instead of dropping "
                                + "one shadow. The most readable option over snow or bright walls, "
                                + "and it replaces the shadow while it is on."),

                SettingRow.label("— Panel —"),
                SettingRow.color("Background Color",
                        () -> picked(cfg().backgroundColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_BACKGROUND_COLOR),
                        () -> SBSTheme.HUD_CARD_BG,
                        () -> openPicker("Background", () -> cfg().backgroundColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_BACKGROUND_COLOR,
                                SBSTheme.HUD_CARD_BG,
                                value -> cfg().backgroundColorHex = value))
                        .describe("The panel's background color. Click to pick one; until you do it "
                                + "follows your SBS theme."),
                SettingRow.rangeSlider("Background Opacity", 0, 100,
                        () -> cfg().backgroundOpacity,
                        value -> { cfg().backgroundOpacity = value; save(); }, "%")
                        .describe("How solid the background is: 0 = invisible, 100 = fully "
                                + "opaque."),
                SettingRow.intField("Corner Radius", 0, 12,
                        () -> cfg().cornerRadius,
                        value -> { cfg().cornerRadius = value; save(); }, "px")
                        .describe("How rounded the panel's corners are, in pixels. 0 = sharp "
                                + "rectangle."),
                SettingRow.toggle("Show Border", () -> cfg().showBorder,
                        () -> { cfg().showBorder = !cfg().showBorder; save(); })
                        .describe("A thin outline around the panel."),
                SettingRow.color("Border Color",
                        () -> picked(cfg().borderColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_BORDER_COLOR),
                        () -> SBSTheme.ACCENT,
                        () -> openPicker("Border", () -> cfg().borderColorHex,
                                SBSConfig.CustomScoreboardSettings.STOCK_BORDER_COLOR, SBSTheme.ACCENT,
                                value -> cfg().borderColorHex = value))
                        .describe("The outline's color. Click to pick one; until you do it follows "
                                + "your SBS theme."),
                SettingRow.rangeSlider("Border Opacity", 0, 100,
                        () -> cfg().borderOpacity,
                        value -> { cfg().borderOpacity = value; save(); }, "%")
                        .describe("How solid the outline is: 0 = invisible, 100 = fully opaque."),
                SettingRow.intField("Border Width", 1, 3,
                        () -> cfg().borderWidth,
                        value -> { cfg().borderWidth = value; save(); }, "px")
                        .describe("How thick the outline is, in pixels."),
                SettingRow.intField("Horizontal Padding", 0, 20,
                        () -> cfg().paddingX,
                        value -> { cfg().paddingX = value; save(); }, "px")
                        .describe("The gap between the text and the panel's left and right edges."),
                SettingRow.intField("Vertical Padding", 0, 20,
                        () -> cfg().paddingY,
                        value -> { cfg().paddingY = value; save(); }, "px")
                        .describe("The gap between the text and the panel's top and bottom edges."),
                SettingRow.intField("Line Spacing", 0, 10,
                        () -> cfg().lineSpacing,
                        value -> { cfg().lineSpacing = value; save(); }, "px")
                        .describe("Extra space between rows. 0 packs them as tightly as the font "
                                + "allows; raise it to spread a long sidebar out."),
                SettingRow.intField("Title Spacing", 0, 12,
                        () -> cfg().titleSpacing,
                        value -> { cfg().titleSpacing = value; save(); }, "px")
                        .describe("The gap between the title and the first row under it."),

                SettingRow.label("— Rows —"),
                SettingRow.toggle("Hide Empty Lines", () -> cfg().hideEmptyLines,
                        () -> { cfg().hideEmptyLines = !cfg().hideEmptyLines; save(); })
                        .describe("Skips the blank spacer lines Hypixel puts in the sidebar, "
                                + "making the panel more compact."),
                SettingRow.intField("Max Blank Rows", 0, CustomScoreboardRenderer.MAX_BLANK_RUN,
                        () -> cfg().maxEmptyLines,
                        value -> { cfg().maxEmptyLines = value; save(); }, "")
                        .describe("How many blank rows may follow each other. Tightens the panel "
                                + "while keeping Hypixel's grouping, instead of throwing every "
                                + "spacer away like Hide Empty Lines. 5 leaves the sidebar as it is."),
                SettingRow.label("Reorder Lines edits which rows show and their order"),

                SettingRow.toggle("God Potion Row", () -> cfg().showGodPotion,
                        () -> { cfg().showGodPotion = !cfg().showGodPotion; save(); })
                        .describe("Adds your God Potion time left as a row under the server's lines, "
                                + "so you see it without holding the tab key. Only while one is "
                                + "active."),
                SettingRow.toggle("Cookie Buff Row", () -> cfg().showCookieBuff,
                        () -> { cfg().showCookieBuff = !cfg().showCookieBuff; save(); })
                        .describe("Adds your Booster Cookie time left as a row under the server's "
                                + "lines. Only while the buff is active."),
                SettingRow.toggle("Bank Row", () -> cfg().showBank,
                        () -> { cfg().showBank = !cfg().showBank; save(); })
                        .describe("Adds your bank balance as a row directly under the Purse line, "
                                + "read off the tab list. The sidebar only ever shows your purse, "
                                + "so this is the one number you otherwise have to hold tab for."),
                SettingRow.label("§8Sits right under Purse, worded like the tab: 1B / 100.8M"),
                SettingRow.toggle("Gems Row", () -> cfg().showGems,
                        () -> { cfg().showGems = !cfg().showGems; save(); })
                        .describe("Adds your gem count as a row directly under the Bits line, read "
                                + "off the tab list. The sidebar carries bits but never gems, so "
                                + "this is the other premium currency you have to hold tab for."),
                SettingRow.label("§8Written in the tab list's own colours"),
                SettingRow.toggle("Interest Row", () -> cfg().showInterest,
                        () -> { cfg().showInterest = !cfg().showInterest; save(); })
                        .describe("Adds when your bank pays interest next, and how much, directly "
                                + "under the Bank row."),
                SettingRow.toggle("Profile Row", () -> cfg().showProfile,
                        () -> { cfg().showProfile = !cfg().showProfile; save(); })
                        .describe("Adds your SkyBlock profile name - handy when you keep several "
                                + "and the sidebar never says which one you are on."),
                SettingRow.toggle("SB Level Row", () -> cfg().showSbLevel,
                        () -> { cfg().showSbLevel = !cfg().showSbLevel; save(); })
                        .describe("Adds your SkyBlock level and its XP progress, as the tab list "
                                + "writes it."),
                SettingRow.toggle("Written-Out Buff Timers", () -> cfg().buffTimersLongForm,
                        () -> { cfg().buffTimersLongForm = !cfg().buffTimersLongForm; save(); })
                        .describe("Writes the two buff timers out in full - 3 years, 2 months, "
                                + "4 days. Off, they use the short form 3y 2m 4d 3min 3s."),

                SettingRow.toggle("Clock Row", () -> cfg().showRealTime,
                        () -> { cfg().showRealTime = !cfg().showRealTime; save(); })
                        .describe("Adds a row with the real-world time, near the top of the panel. "
                                + "Playing full screen hides your system clock, and the sidebar is "
                                + "already where you look."),
                SettingRow.label("§8Move it anywhere you like under Reorder Lines"),
                SettingRow.toggle("12-Hour Clock", () -> cfg().realTime12Hour,
                        () -> { cfg().realTime12Hour = !cfg().realTime12Hour; save(); })
                        .describe("Writes the clock row as 9:05 PM. Turn it off for the 24-hour "
                                + "21:05 instead."),
                SettingRow.toggle("FPS Row", () -> cfg().showFps,
                        () -> { cfg().showFps = !cfg().showFps; save(); })
                        .describe("Adds a row with your frame rate, so you can watch it without "
                                + "the F3 screen covering half the game."),
                SettingRow.toggle("Ping Row", () -> cfg().showPing,
                        () -> { cfg().showPing = !cfg().showPing; save(); })
                        .describe("Adds a row with your ping to the server, colored green / amber / "
                                + "red as it gets worse. Measured as a real round trip, not read "
                                + "off the tab list."),
                SettingRow.toggle("Mayor Vote Row", () -> cfg().showMayorVote,
                        () -> { cfg().showMayorVote = !cfg().showMayorVote; save(); })
                        .describe("Adds a row saying whether you have voted in the running mayor "
                                + "election - red until you have, green once the vote is cast. "
                                + "Only while an election is actually running, so it costs no "
                                + "space the rest of the year. Needs the Mayor Vote Reminder "
                                + "module, which is what tracks the vote."),
                SettingRow.label("The switches above decide whether an SBS row exists at all"),
                SettingRow.label("§8Where it sits is Reorder Lines' business, like any other row"));
    }
}
