/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.buffs.BuffColors;
import sbs.modid.client.helper.buffs.BuffDuration;
import sbs.modid.client.helper.buffs.BuffTracker;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.DisplayedText;
import sbs.modid.client.core.util.NumberTextFormat;
import sbs.modid.client.economy.mayor.MayorVoteTracker;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.UiStyle;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.helper.streamer.logic.StreamerNames;
import sbs.modid.client.helper.visual.logic.TextReplacer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Draws the Custom Scoreboard: a fully styleable replacement for Hypixel's plain sidebar.
 *
 * <p>Everything is read straight from {@link SBSConfig.CustomScoreboardSettings} every frame, so any
 * change – a hidden line, a new colour, more opacity, a different alignment – shows on the very next
 * frame with no restart and no cached state. The lines and title come live from
 * {@link ScoreboardReader}; hide and reorder are applied here from the persisted signatures.
 *
 * <p>Position and scale are the shared GUI editor's job via {@link HudElement#CUSTOM_SCOREBOARD}, so
 * this class never touches placement beyond the element's default anchor – it only measures the panel
 * from its content (so it never clips a long line) and paints it.
 *
 * <p><b>Measure and draw share one list.</b> Every row is resolved to the exact {@link Row} it will be
 * drawn as – component, outline stand-in and colour – <i>before</i> the panel is measured. A row's
 * width depends on which of the three colour modes is in force (a stripped literal and the server's
 * own component are not the same glyphs once anything is bold), and measuring one form while drawing
 * another is how a panel ends up a few pixels too narrow for its own text.
 */
public final class CustomScoreboardRenderer {

    /** How many blank spacer rows may follow each other at most – see {@code maxEmptyLines}. */
    public static final int MAX_BLANK_RUN = 5;

    /** Passed as an SBS row's label colour to leave the label the white every other row uses. */
    private static final int NO_LABEL_COLOR = -1;

    /**
     * Last-resort colours for the three tab-fed rows, used only when the tab's own component is not
     * available - see {@link #tabColoredRow}. They are a best guess at what the tab list is drawing,
     * and a guess is exactly what they must never be while the real thing can be had.
     */
    private static final int GEMS_COLOR = 0x55FF55;
    private static final int PROFILE_COLOR = 0x55FF55;
    private static final int INTEREST_COLOR = 0xFFAA00;
    /** The client-info rows: a cool grey-blue that does not compete with the server's own colours. */
    private static final int CLOCK_COLOR = 0x9BD8FF;
    private static final int FPS_COLOR = 0x8FE3A0;
    /** The vote row: green once it is cast, red while it is still owed. */
    private static final int VOTED_COLOR = 0x55FF55;
    private static final int NOT_VOTED_COLOR = 0xFF5555;

    /** Ping thresholds, in ms, for the three colours the Ping row is written in. */
    private static final int PING_GOOD_MS = 100;
    private static final int PING_FAIR_MS = 250;
    private static final int PING_GOOD_COLOR = 0x55FF55;
    private static final int PING_FAIR_COLOR = 0xFFD24A;
    private static final int PING_BAD_COLOR = 0xFF5555;

    /** One full trip around the colour wheel for the rainbow title. */
    private static final long RAINBOW_PERIOD_MS = 4_000L;

    /** How solid a placed rule is drawn - a divider that competes with the text is a worse divider. */
    private static final int RULE_OPACITY_PERCENT = 45;

    /** The editor's preview colour for a row the layout places but the game is not showing now. */
    private static final int FADED_PREVIEW_COLOR = 0x80FFFFFF;

    private static final DateTimeFormatter CLOCK_24H = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter CLOCK_12H = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);

    private CustomScoreboardRenderer() {
    }

    /**
     * One row exactly as it will be painted: the component to draw, the plain stand-in the text
     * outline is traced from, and the colour handed to the font.
     *
     * <p>The outline needs its own component because a black colour argument only reaches text that
     * carries no colour of its own – tracing the server's own coloured component would paint the
     * outline in the server's colours instead of black.
     */
    private record Row(Component text, Component outline, int color, boolean rule) {

        static Row of(Component text, Component outline, int color) {
            return new Row(text, outline, color, false);
        }
    }

    /** The measured size of a drawn panel, for a caller that has to lay something out beside it. */
    public record PanelSize(int width, int height) {
    }

    /** The panel's spacing, all of it player-set and clamped once per frame. */
    private record Metrics(int padX, int padY, int lineSpacing, int titleGap) {

        static Metrics of(SBSConfig.CustomScoreboardSettings cfg) {
            return new Metrics(clamp(cfg.paddingX, 0, 20), clamp(cfg.paddingY, 0, 20),
                    clamp(cfg.lineSpacing, 0, 10), clamp(cfg.titleSpacing, 0, 12));
        }
    }

    private static SBSConfig.CustomScoreboardSettings cfg() {
        return ConfigManager.getInstance().get().customScoreboard;
    }

    /** Whether the custom scoreboard should take over the vanilla sidebar right now. */
    public static boolean active() {
        return cfg().enabled && ScoreboardReader.hasSidebar();
    }

    /**
     * Draws the custom scoreboard under its GUI-editor transform. Assumes {@link #active()}; the
     * caller (the HUD mixin) checks that and cancels the vanilla render. Does nothing when the element
     * is hidden via the editor's minus button.
     */
    public static void render(GuiGraphicsExtractor g) {
        if (HudLayout.isHidden(HudElement.CUSTOM_SCOREBOARD)) {
            return;
        }
        SBSConfig.CustomScoreboardSettings cfg = cfg();
        // Text Editor rules are applied HERE rather than being left to the central draw hook: the
        // panel sizes itself to its text, so a rule that changes a line's length has to change it
        // before the width is measured, or the background no longer fits what is written on it. The
        // number format runs before the SBS rows are added, since those are already written our way.
        // Streamer Mode's redaction is last for the same measuring reason and for its own: taking
        // the instance id off the date line shortens that line, and this sidebar is the one place
        // the id is written at all.
        List<ScoreboardLine> lines =
                redacted(replaced(cappedBlanks(cfg, numbered(cfg, visibleLines(cfg, allRows(cfg))))));
        Component title = title(cfg);
        List<Row> rows = rows(cfg, lines, Set.of());
        if (rows.isEmpty() && title == null) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        Metrics metrics = Metrics.of(cfg);
        int lineStride = font.lineHeight + metrics.lineSpacing();

        int contentWidth = title != null ? font.width(title) : 0;
        for (Row row : rows) {
            contentWidth = Math.max(contentWidth, font.width(row.text()));
        }

        int width = contentWidth + metrics.padX() * 2;
        int height = metrics.padY() * 2
                + (title != null ? font.lineHeight + metrics.titleGap() : 0)
                + rows.size() * lineStride
                - (rows.isEmpty() ? 0 : metrics.lineSpacing()); // no trailing gap under the last line

        HudElement.Bounds bounds = HudElement.CUSTOM_SCOREBOARD.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = anchoredX(g, bounds, width);
        int y = Math.round(bounds.y());
        HudLayout.measure(HudElement.CUSTOM_SCOREBOARD, x, y, width, height);

        HudLayout.begin(g, HudElement.CUSTOM_SCOREBOARD);
        DisplayedText.runTextRulesApplied(() ->
                drawPanel(g, cfg, metrics, rows, title, x, y, width, height));
        HudLayout.end(g);
    }

    /**
     * Every line with the Text Editor rules applied to its coloured form, and its stripped form and
     * signature re-derived from the result. The signature has to follow: it is what "hide this line"
     * and the line ordering are keyed on, and those must keep matching the line the player sees.
     */
    private static List<ScoreboardLine> replaced(List<ScoreboardLine> lines) {
        TextReplacer replacer = TextReplacer.getInstance();
        if (!replacer.hasRules()) {
            return lines;
        }
        List<ScoreboardLine> out = new ArrayList<>(lines.size());
        for (ScoreboardLine line : lines) {
            // A row with no text has nothing to replace, and re-deriving it would throw away the
            // signature the blanks and rules the player placed are told apart by.
            if (line.isBlank()) {
                out.add(line);
                continue;
            }
            Component display = replacer.apply(line.display());
            if (display == line.display()) {
                out.add(line);
                continue;
            }
            out.add(rederived(display));
        }
        return out;
    }

    /**
     * Every line with its values re-written in the chosen number format ({@code 12.7M} /
     * {@code 12,700,000}).
     *
     * <p>Runs <b>after</b> {@link #visibleLines}, and that order is not an accident: a line's
     * signature collapses its digits, so rewriting "Purse: 12,700,000" to "Purse: 12.7M" changes the
     * signature it would produce. Hiding and reordering therefore have to key on the untouched line,
     * exactly as the editor lists it.
     *
     * <p>SBS's own rows are skipped. They carry the tab list's wording, which is already short, and
     * they are the mod's text rather than the server's - this setting is about how <i>Hypixel's</i>
     * figures are written.
     */
    private static List<ScoreboardLine> numbered(SBSConfig.CustomScoreboardSettings cfg,
                                                 List<ScoreboardLine> lines) {
        NumberTextFormat format = NumberTextFormat.orServer(cfg.numberFormat).resolved();
        if (format == NumberTextFormat.SERVER) {
            return lines;
        }
        List<ScoreboardLine> out = new ArrayList<>(lines.size());
        for (ScoreboardLine line : lines) {
            if (line.signature().startsWith("sbs:")) {
                out.add(line);
                continue;
            }
            Component display = format.apply(line.display());
            out.add(display == line.display() ? line : rederived(display));
        }
        return out;
    }

    /**
     * Every line with Streamer Mode's redaction applied - names out of the party rows, the Hypixel
     * instance id off the date line.
     *
     * <p>Runs last, after {@link #visibleLines} has already decided what to show and in what order,
     * so a redacted line's changed signature cannot move it or unhide it. Same reasoning as
     * {@link #numbered}, and the same reason it is safe to re-derive the signature here.
     */
    private static List<ScoreboardLine> redacted(List<ScoreboardLine> lines) {
        StreamerNames streamer = StreamerNames.getInstance();
        if (!streamer.active()) {
            return lines;
        }
        List<ScoreboardLine> out = new ArrayList<>(lines.size());
        for (ScoreboardLine line : lines) {
            if (line.isBlank()) {
                out.add(line);
                continue;
            }
            Component display = streamer.apply(line.display());
            out.add(display == line.display() ? line : rederived(display));
        }
        return out;
    }

    /** A line rebuilt around a changed component, with its stripped form and signature to match. */
    private static ScoreboardLine rederived(Component display) {
        String stripped = ScoreboardReader.strip(display.getString());
        return new ScoreboardLine(display, stripped, ScoreboardReader.signatureOf(stripped));
    }

    /**
     * The lines with no more than {@code maxEmptyLines} blank spacers in a row.
     *
     * <p>Hypixel pads its sidebar with blank rows to group the block, and how much of that padding is
     * worth the screen space is a matter of taste rather than a yes/no: "Hide Empty Lines" throws the
     * grouping away entirely, this only tightens it. Nothing to do while that switch is on – the
     * blanks are already gone.
     */
    private static List<ScoreboardLine> cappedBlanks(SBSConfig.CustomScoreboardSettings cfg,
                                                     List<ScoreboardLine> lines) {
        int max = clamp(cfg.maxEmptyLines, 0, MAX_BLANK_RUN);
        if (cfg.hideEmptyLines || max >= MAX_BLANK_RUN) {
            return lines;
        }
        List<ScoreboardLine> out = new ArrayList<>(lines.size());
        int run = 0;
        for (ScoreboardLine line : lines) {
            // A blank the player placed in the editor is not padding to be tidied away - it is the
            // layout. Only Hypixel's own spacers are counted against the cap.
            if (!line.isBlank() || ScoreboardLayout.isPlacedSpacer(line)
                    || ScoreboardLayout.isSeparator(line)) {
                run = 0;
                out.add(line);
            } else if (++run <= max) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * The title to draw, or {@code null} when there is none to draw.
     *
     * <p>The server's colour codes are stripped here rather than at paint time so the Title Colour
     * setting always takes effect, and the bold style is part of the returned component so the panel
     * is measured against the glyphs that actually get drawn.
     */
    private static Component title(SBSConfig.CustomScoreboardSettings cfg) {
        if (!cfg.showTitle) {
            return null;
        }
        String text = ScoreboardReader.strip(
                TextReplacer.getInstance().apply(titleComponent(cfg)).getString());
        if (text.isBlank()) {
            return null;
        }
        MutableComponent title = Component.literal(text);
        return cfg.titleBold ? title.withStyle(ChatFormatting.BOLD) : title;
    }

    /**
     * Each line resolved to the component and colour it will be drawn with, under whichever of the
     * three colour modes is in force: the player's single colour, one colour per row taken from the
     * server's value, or the server's own component untouched.
     */
    private static List<Row> rows(SBSConfig.CustomScoreboardSettings cfg, List<ScoreboardLine> lines,
                                  Set<ScoreboardLine> faded) {
        int lineColor = 0xFF000000 | resolveRgb(cfg.lineColorHex,
                SBSConfig.CustomScoreboardSettings.STOCK_LINE_COLOR, SBSTheme.TEXT);
        List<Row> rows = new ArrayList<>(lines.size());
        for (ScoreboardLine line : lines) {
            if (ScoreboardLayout.isSeparator(line)) {
                rows.add(new Row(Component.empty(), Component.empty(), lineColor, true));
            } else if (faded.contains(line)) {
                // Preview only: a row the layout places but the game is not showing right now. Drawn
                // in one muted colour whatever the colour mode is, because the point of it is to read
                // as "this is where it will go", not as a real row.
                Component plain = Component.literal(line.stripped());
                rows.add(Row.of(plain, plain, FADED_PREVIEW_COLOR));
            } else if (!cfg.useOriginalColors) {
                Component plain = Component.literal(line.stripped());
                rows.add(Row.of(plain, plain, lineColor));
            } else if (cfg.solidLineColors) {
                Component plain = Component.literal(line.stripped());
                int solid = valueColorOf(line.display());
                rows.add(Row.of(plain, plain, solid != 0 ? solid : 0xFFFFFFFF));
            } else {
                rows.add(Row.of(line.display(), Component.literal(line.stripped()), 0xFFFFFFFF));
            }
        }
        return rows;
    }

    /** The panel itself, drawn from rows the rules and the number format have already been applied to. */
    private static void drawPanel(GuiGraphicsExtractor g, SBSConfig.CustomScoreboardSettings cfg,
                                  Metrics metrics, List<Row> rows, Component title,
                                  int x, int y, int width, int height) {
        Font font = Minecraft.getInstance().font;
        drawBackground(g, cfg, x, y, width, height);

        int textY = y + metrics.padY();
        if (title != null) {
            int titleColor = cfg.rainbowTitle ? rainbow() : 0xFF000000 | resolveRgb(cfg.titleColorHex,
                    SBSConfig.CustomScoreboardSettings.STOCK_TITLE_COLOR, SBSTheme.ACCENT);
            ScoreboardAlignment align = ScoreboardAlignment.orDefault(
                    cfg.titleAlignment, ScoreboardAlignment.CENTER);
            drawRow(g, cfg, font, title, title, titleColor,
                    align.startX(x, width, metrics.padX(), font.width(title)), textY);
            textY += font.lineHeight + metrics.titleGap();
        }

        ScoreboardAlignment align = ScoreboardAlignment.orDefault(cfg.alignment, ScoreboardAlignment.LEFT);
        int lineStride = font.lineHeight + metrics.lineSpacing();
        for (Row row : rows) {
            if (row.rule()) {
                // A rule spans the panel rather than its own text, so it is drawn rather than
                // written - centred in the row's height so the gaps above and below it match.
                int ruleY = textY + font.lineHeight / 2;
                g.fill(x + metrics.padX(), ruleY, x + width - metrics.padX(), ruleY + 1,
                        withOpacity(row.color(), RULE_OPACITY_PERCENT));
            } else {
                drawRow(g, cfg, font, row.text(), row.outline(), row.color(),
                        align.startX(x, width, metrics.padX(), font.width(row.text())), textY);
            }
            textY += lineStride;
        }
    }

    /**
     * One row of text, outlined or shadowed as the player asked.
     *
     * <p>Never both: an outline already darkens all four sides, and adding the drop shadow on top of
     * it only smears the bottom-right corner into a blob.
     */
    private static void drawRow(GuiGraphicsExtractor g, SBSConfig.CustomScoreboardSettings cfg,
                                Font font, Component text, Component outline, int color, int x, int y) {
        if (cfg.textOutline) {
            g.text(font, outline, x - 1, y, 0xFF000000, false);
            g.text(font, outline, x + 1, y, 0xFF000000, false);
            g.text(font, outline, x, y - 1, 0xFF000000, false);
            g.text(font, outline, x, y + 1, 0xFF000000, false);
        }
        g.text(font, text, x, y, color, cfg.textShadow && !cfg.textOutline);
    }

    /** The title colour on this frame while the rainbow is on: one trip round the wheel every 4s. */
    private static int rainbow() {
        float hue = (System.currentTimeMillis() % RAINBOW_PERIOD_MS) / (float) RAINBOW_PERIOD_MS;
        return 0xFF000000 | SBSTheme.hsbToRgb(hue, 0.8f, 1f);
    }

    /**
     * The colour to paint a whole line in for "Solid Line Colors": the one in force at the line's
     * <b>last</b> visible character, or {@code 0} when the line carries no colour at all.
     *
     * <p>Hypixel writes its rows label-then-value and colours only the value ("Bits:" plain, the
     * count aqua), so the end of the line is where the colour that identifies the row lives. Taking
     * the first colour instead would paint every row the same washed-out label grey, and taking the
     * most common one would follow whichever half happened to be longer.
     *
     * <p>Both ways of colouring text are honoured: a component's own {@link TextColor} and the
     * legacy {@code §} codes Hypixel embeds in the text, with the codes winning inside the run that
     * carries them - which is the order the font itself resolves them in.
     */
    private static int valueColorOf(Component display) {
        int[] last = {0};
        display.visit((style, string) -> {
            int structural = style.getColor() != null ? 0xFF000000 | style.getColor().getValue() : 0;
            int active = structural;
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                if (c == '§' && i + 1 < string.length()) {
                    ChatFormatting code = ChatFormatting.getByCode(string.charAt(++i));
                    // fromLegacyFormat is the colour test as well as the lookup: it answers null for
                    // the format codes (§l, §o, ...), which leave the colour alone.
                    TextColor legacy = code == null ? null : TextColor.fromLegacyFormat(code);
                    if (code == ChatFormatting.RESET) {
                        active = structural;
                    } else if (legacy != null) {
                        active = 0xFF000000 | legacy.getValue();
                    }
                    continue;
                }
                if (!Character.isWhitespace(c) && active != 0) {
                    last[0] = active;
                }
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return last[0];
    }

    /**
     * The panel's left edge for a given content width, anchored to whichever screen side the panel
     * sits on.
     *
     * <p>The width is content-derived and Hypixel's sidebar text changes constantly (a coordinate
     * gaining a digit, "Late Winter" becoming "Early Spring"). With a fixed LEFT edge every such
     * change moved the RIGHT edge, so a panel dragged flush against the right screen border kept
     * jumping between sticking out past it and sitting a gap away from it. Anchoring the side the
     * panel was pushed against pins that edge and lets the width grow inwards instead – the vanilla
     * sidebar behaves exactly this way, which is what {@link HudElement#CUSTOM_SCOREBOARD}'s default
     * bounds always meant.
     *
     * <p>The side follows the panel's own position, so a scoreboard moved to the left half keeps
     * growing rightwards as before. The editor box tracks it either way: the {@code x} returned here
     * is what gets reported to {@link HudLayout#measure}.
     */
    private static int anchoredX(GuiGraphicsExtractor g, HudElement.Bounds bounds, int width) {
        double offsetX = HudLayout.get(HudElement.CUSTOM_SCOREBOARD).x;
        double centre = bounds.x() + offsetX + bounds.w() / 2.0;
        if (centre > g.guiWidth() / 2.0) {
            // Right-anchored: the default bounds' right edge is the fixed point, never the left one.
            return Math.round(bounds.x() + bounds.w()) - width;
        }
        return Math.round(bounds.x());
    }

    /** The panel background + optional border, each at its own opacity and the border at its width. */
    private static void drawBackground(GuiGraphicsExtractor g, SBSConfig.CustomScoreboardSettings cfg,
                                       int x, int y, int width, int height) {
        // The shape follows the style, because a style IS the shape opinion; the player's own radius
        // wins under CLASSIC, where there is no style opinion to follow.
        int radius = SBSTheme.style() == UiStyle.CLASSIC
                ? Math.max(0, cfg.cornerRadius) : SBSTheme.HUD_CORNER;
        int bg = withOpacity(resolveRgb(cfg.backgroundColorHex,
                SBSConfig.CustomScoreboardSettings.STOCK_BACKGROUND_COLOR, SBSTheme.HUD_CARD_BG),
                cfg.backgroundOpacity);
        if (cfg.showBorder) {
            int thickness = clamp(cfg.borderWidth, 1, 3);
            int border = withOpacity(resolveRgb(cfg.borderColorHex,
                    SBSConfig.CustomScoreboardSettings.STOCK_BORDER_COLOR, SBSTheme.ACCENT),
                    cfg.borderOpacity);
            SciFiRender.roundedRect(g, x - thickness, y - thickness,
                    width + thickness * 2, height + thickness * 2, radius + thickness, border);
        }
        SciFiRender.roundedRect(g, x, y, width, height, radius, bg);
    }

    /**
     * One scoreboard colour, resolved against the SBS theme: {@code RRGGBB} with no alpha.
     *
     * <p><b>A colour the player picked is never overridden</b> – not by re-theming the mod and not by
     * switching the UI style. Only a picker still sitting at its stock value follows the theme, which
     * is what keeps a freshly enabled sidebar from being the one stock-blue island in a re-themed
     * client. The style deliberately has no say here at all: it owns shape and opacity (the corner
     * radius above), and reading it as "the look was customised" was exactly what made a style switch
     * throw away colours that had been chosen on purpose.
     */
    private static int resolveRgb(String hex, String stockHex, int themedColor) {
        Integer picked = OverlayColor.parseHex(hex);
        Integer stock = OverlayColor.parseHex(stockHex);
        if (picked == null || picked.equals(stock)) {
            return themedColor & 0xFFFFFF;
        }
        return picked;
    }

    /**
     * Whether this is the server's advertising line. Matched on the domain rather than on a line
     * signature: the signature collapses digits, which the URL has none of, so it would work - but
     * Hypixel has moved this line's wording and position before, and the domain is the part that
     * cannot change without it no longer being the same row.
     */
    private static boolean isWebsite(ScoreboardLine line) {
        return line.stripped().toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    /**
     * The lines to actually draw: the website row and Hypixel's spacers dropped if the player asked,
     * then handed to {@link ScoreboardLayout} to be put in the saved order.
     *
     * <p>The two filters run <b>before</b> the layout rather than being folded into it because they
     * are settings about the server's own output, not positions - "Hide Website Row" means the row
     * is not there to place, which is a different statement from "this element sits at slot 4".
     */
    static List<ScoreboardLine> visibleLines(SBSConfig.CustomScoreboardSettings cfg,
                                             List<ScoreboardLine> all) {
        List<ScoreboardLine> filtered = new ArrayList<>(all.size());
        for (ScoreboardLine line : all) {
            if (cfg.hideEmptyLines && line.isBlank()) {
                continue;
            }
            if (cfg.hideWebsite && isWebsite(line)) {
                continue;
            }
            filtered.add(line);
        }
        return ScoreboardLayout.apply(filtered, cfg.elementOrder, cfg.hiddenElements);
    }

    /**
     * The wall-clock row, as the <b>first</b> body line - directly under the title, above the
     * server's own first line.
     *
     * <p>It sits apart from the other client-info rows on purpose. Those are diagnostics you glance
     * at now and then, and they belong in the block at the bottom; the time is the one line you look
     * for deliberately, because a full-screen game is covering the clock you would otherwise read.
     * Putting it under the title makes it findable without reading the panel.
     */
    private static List<ScoreboardLine> withTimeRow(SBSConfig.CustomScoreboardSettings cfg,
                                                    List<ScoreboardLine> lines) {
        if (!cfg.showRealTime) {
            return lines;
        }
        String now = LocalTime.now().format(cfg.realTime12Hour ? CLOCK_12H : CLOCK_24H);
        List<ScoreboardLine> out = new ArrayList<>(lines.size() + 1);
        out.add(sbsRow("Time", now, CLOCK_COLOR));
        out.addAll(lines);
        return out;
    }

    /**
     * Appends the rows SBS invented – the buff timers, then the client-info rows – under the server's
     * own lines, as one block behind a single spacer.
     *
     * <p>These are things the sidebar cannot tell you: the two timers the tab list hides behind a
     * keypress (read by {@link BuffTracker}), the real-world clock a full-screen game covers up, and
     * the two numbers you would otherwise open F3 for. They live here rather than in HUD cards of
     * their own because the sidebar is already where the eye goes for status - a second panel
     * elsewhere on the screen for a few lines of text was never worth the space.
     *
     * <p>They are appended <b>after</b> {@link #visibleLines} rather than fed through it: hiding and
     * reordering work on the server's line signatures, and a row SBS invented has its own toggle
     * instead. A spacer separates the block from the server's, unless empty lines are being collapsed
     * anyway.
     */
    private static List<ScoreboardLine> withSbsRows(SBSConfig.CustomScoreboardSettings cfg,
                                                    List<ScoreboardLine> lines) {
        List<ScoreboardLine> extras = new ArrayList<>(5);
        addBuffRows(cfg, extras);
        addInfoRows(cfg, extras);
        if (extras.isEmpty()) {
            return lines;
        }
        List<ScoreboardLine> out = new ArrayList<>(lines.size() + extras.size() + 1);
        out.addAll(lines);
        if (!cfg.hideEmptyLines && !out.isEmpty() && !out.getLast().isBlank()) {
            out.add(new ScoreboardLine(Component.empty(), "", "sbs extras spacer"));
        }
        out.addAll(extras);
        return out;
    }

    /**
     * The God Potion / Booster Cookie rows, each only while its buff is actually active – nothing
     * here reserves space to say "none".
     *
     * <p>These are the one pair of rows that colour their label as well as their value, and both
     * colours are the player's to set - see {@link BuffColors}, which owns the defaults and is also
     * what the HUD cards read, so the two drawings of one number cannot disagree. They are not
     * {@link #tabColoredRow} rows: that hands the server's whole component through, which is not
     * available to a row whose value is deliberately re-worded.
     *
     * <p>The value is re-worded by {@link BuffDuration} before it is drawn: the server's prose is the
     * widest thing on the panel and changes shape as it ticks down. Short by default, spelled out in
     * full when {@code buffTimersLongForm} is on.
     */
    private static void addBuffRows(SBSConfig.CustomScoreboardSettings cfg, List<ScoreboardLine> out) {
        BuffTracker tracker = BuffTracker.getInstance();
        // Hypixel's own wording is whatever it feels like; the rows are re-worded in one style.
        String godPotion = BuffDuration.format(
                cfg.showGodPotion ? tracker.godPotion() : null, cfg.buffTimersLongForm);
        String cookie = BuffDuration.format(
                cfg.showCookieBuff ? tracker.cookieBuff() : null, cfg.buffTimersLongForm);
        if (godPotion != null) {
            out.add(sbsRow("God Potion", godPotion,
                    BuffColors.godPotionName(), BuffColors.godPotionTime()));
        }
        if (cookie != null) {
            out.add(sbsRow("Cookie Buff", cookie,
                    BuffColors.cookieBuffName(), BuffColors.cookieBuffTime()));
        }
    }

    /**
     * The client-info rows: the wall clock, the frame rate and the ping.
     *
     * <p>The ping is the measured round trip {@link ServerStatsTracker} keeps, not the tab list's
     * number, and asking for it is also what drives its 2s send cadence – so the row is fed by the
     * same call that draws it. It stays off the panel until the first pong answers rather than
     * showing a placeholder, since a sidebar row that says "unknown" is worse than no row.
     */
    private static void addInfoRows(SBSConfig.CustomScoreboardSettings cfg, List<ScoreboardLine> out) {
        TabInfoTracker tab = TabInfoTracker.getInstance();
        if (cfg.showProfile) {
            String profile = tab.value(TabInfoTracker.Row.PROFILE);
            if (profile != null) {
                out.add(tabColoredRow(TabInfoTracker.Row.PROFILE, "Profile", profile, PROFILE_COLOR));
            }
        }
        if (cfg.showSbLevel) {
            // Verbatim from the tab by default: the level's own colour changes as it rises, and
            // reproducing the server's component is the only version of "match the tab list" that
            // stays right for a colour Hypixel has not shipped yet. Level Colours is the player
            // opting out of that - and while it is off, or has nothing to say about this level,
            // recolour() hands the very same component straight back.
            Component level = tab.component(TabInfoTracker.Row.SB_LEVEL);
            if (level != null) {
                out.add(tabRow("SB Level",
                        sbs.modid.client.core.level.LevelText.recolour(level,
                                sbLevelNumber(tab))));
            }
        }
        if (cfg.showFps) {
            out.add(sbsRow("FPS", Integer.toString(Math.max(0, Minecraft.getInstance().getFps())),
                    FPS_COLOR));
        }
        if (cfg.showPing) {
            int ping = ServerStatsTracker.getInstance().ping(Minecraft.getInstance());
            if (ping >= 0) {
                out.add(sbsRow("Ping", ping + "ms", pingColor(ping)));
            }
        }
        addVoteRow(cfg, out);
    }

    /**
     * The mayor-vote row, and <b>only while an election is actually running</b>.
     *
     * <p>A permanent "Vote: voted" for the eleven-twelfths of the SkyBlock year with no election
     * would be a row that is right and useless - it is the open election that makes it worth the
     * space. Red until the vote is cast is the whole point, so the colour carries the state and the
     * row does not have to be read to be understood.
     */
    private static void addVoteRow(SBSConfig.CustomScoreboardSettings cfg, List<ScoreboardLine> out) {
        if (!cfg.showMayorVote || !ConfigManager.getInstance().get().mayorVote.enabled) {
            return;
        }
        MayorVoteTracker vote = MayorVoteTracker.getInstance();
        if (!vote.electionOpen()) {
            return;
        }
        if (vote.hasVoted()) {
            String who = vote.votedFor();
            out.add(sbsRow("Vote", who.isEmpty() ? "Voted" : "Voted - " + who, VOTED_COLOR));
        } else {
            String left = vote.timeLeft();
            out.add(sbsRow("Vote", left.isEmpty() ? "Not voted" : "Not voted - " + left + " left",
                    NOT_VOTED_COLOR));
        }
    }

    /** Green under {@value #PING_GOOD_MS} ms, amber under {@value #PING_FAIR_MS}, red past it. */
    private static int pingColor(int ping) {
        if (ping < PING_GOOD_MS) {
            return PING_GOOD_COLOR;
        }
        return ping < PING_FAIR_MS ? PING_FAIR_COLOR : PING_BAD_COLOR;
    }

    /**
     * Inserts the Bank row(s) <b>directly under the server's Purse line</b> rather than at the end of
     * the panel. Bank and purse are the same question asked twice, so the eye should find them
     * together; the buff rows below are countdowns and belong in their own block.
     *
     * <p>Falls back to appending when the sidebar has no purse line at all - the line is missing on
     * some islands, and a row the player switched on must still appear somewhere.
     *
     * <p>The row carries the tab list's own wording. On a co-op profile that is two figures either
     * side of a slash ({@code "1B / 100.8M"}) - the profile's bank and your personal one - and both
     * belong on the row: dropping the tail would quietly lose a real balance for every co-op player.
     * A solo profile simply has no second figure.
     */
    private static List<ScoreboardLine> withBankRow(SBSConfig.CustomScoreboardSettings cfg,
                                                    List<ScoreboardLine> lines) {
        if (!cfg.showBank) {
            return lines;
        }
        // Verbatim from the tab, like the SB Level row: the separator between the two balances is a
        // grey the panel would otherwise have to guess at, and guessing is what made it read as one
        // unbroken block of gold.
        Component bank = TabInfoTracker.getInstance().component(TabInfoTracker.Row.BANK);
        if (bank == null) {
            return lines;
        }
        return insertAfter(lines, tabRow("Bank", bank), indexOfLabel(lines, "purse:", "piggy:"));
    }

    /**
     * Inserts the Gems row <b>directly under the server's Bits line</b>. Bits and gems are the two
     * premium currencies, so they belong next to each other for the same reason bank sits under
     * purse; and like the bank, the count is only ever in the tab list.
     */
    private static List<ScoreboardLine> withGemsRow(SBSConfig.CustomScoreboardSettings cfg,
                                                    List<ScoreboardLine> lines) {
        if (!cfg.showGems) {
            return lines;
        }
        String gems = TabInfoTracker.getInstance().value(TabInfoTracker.Row.GEMS);
        if (gems == null) {
            return lines;
        }
        return insertAfter(lines,
                tabColoredRow(TabInfoTracker.Row.GEMS, "Gems", gems, GEMS_COLOR),
                indexOfLabel(lines, "bits:"));
    }

    /**
     * Inserts the Interest row directly under the Bank row - it is that row's next instalment, and
     * the two are read together. With the Bank row off there is nothing to follow, so
     * {@link #insertAfter} appends it instead of dropping it.
     */
    private static List<ScoreboardLine> withInterestRow(SBSConfig.CustomScoreboardSettings cfg,
                                                        List<ScoreboardLine> lines) {
        if (!cfg.showInterest) {
            return lines;
        }
        String interest = TabInfoTracker.getInstance().value(TabInfoTracker.Row.INTEREST);
        if (interest == null) {
            return lines;
        }
        return insertAfter(lines,
                tabColoredRow(TabInfoTracker.Row.INTEREST, "Interest", interest, INTEREST_COLOR),
                indexOfLabel(lines, "bank:"));
    }

    /** {@code row} placed after {@code index}, or appended when there was no such line to follow. */
    private static List<ScoreboardLine> insertAfter(List<ScoreboardLine> lines, ScoreboardLine row,
                                                    int index) {
        List<ScoreboardLine> out = new ArrayList<>(lines.size() + 1);
        out.addAll(lines);
        if (index < 0) {
            out.add(row);
        } else {
            out.add(index + 1, row);
        }
        return out;
    }

    /**
     * The first line carrying one of {@code labels}, or {@code -1}. Matched on the label rather than
     * on a line signature because the amount beside it changes constantly. {@code Piggy} counts as a
     * purse line - a piggy bank in the inventory is what replaces the word "Purse" on the sidebar.
     */
    private static int indexOfLabel(List<ScoreboardLine> lines, String... labels) {
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).stripped().toLowerCase(Locale.ROOT);
            for (String label : labels) {
                if (text.contains(label)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * One SBS row: <b>white label, coloured value</b> - the way Hypixel writes its own sidebar rows
     * ("Purse:" plain with a gold number, "Bits:" plain with an aqua one).
     *
     * <p>This started out the other way round, colouring the label and leaving the value white, which
     * made an SBS row the only one on the panel with its colours the wrong way round. Matching the
     * server's rows is what makes the block read as one list rather than as ours bolted underneath.
     * "Solid Line Colors" then picks the value's colour for the whole row, exactly as it does for
     * the server's own lines.
     */
    /**
     * A tab-fed row in the tab list's <b>own</b> colours, falling back to {@code rgb} only when the
     * tab's component is not to hand.
     *
     * <p>These rows exist to put a tab-list number on the sidebar, so "the colour the tab list writes
     * it in" is the only colour that can be right - and it is not a colour this file can hold as a
     * constant. Hypixel greys out an interest figure that is not due yet and re-colours it when it
     * is; the profile name follows the profile's own colour; gems have been more than one green. Each
     * of those was a hard-coded constant here, so the row read as ours bolted on rather than as the
     * tab list's row moved over - and every one of them would go stale on the next Hypixel change,
     * silently, because a wrong colour looks exactly like a right one.
     *
     * <p>The fallback is not dead code: {@link TabInfoTracker} reads values from the widget lines
     * <i>and</i> the footer, but only the widget lines carry components. A value that came from the
     * footer has a string and no component.
     */
    private static ScoreboardLine tabColoredRow(TabInfoTracker.Row row, String label, String value,
                                                int rgb) {
        Component component = TabInfoTracker.getInstance().component(row);
        return component != null ? tabRow(label, component) : sbsRow(label, value, rgb);
    }

    /**
     * The SkyBlock level as a number, or {@code 0} when the tab has not said.
     *
     * <p>Read off the row's plain text - the first run of digits in it - because the colouring needs
     * a number and the tab only ever gives a sentence. {@code 0} is the "do not know" answer and
     * {@link sbs.modid.client.core.level.LevelColors} refuses to colour it, which is the behaviour
     * that matters: a level nobody could read is left exactly as Hypixel drew it.
     */
    private static int sbLevelNumber(TabInfoTracker tab) {
        String value = tab.value(TabInfoTracker.Row.SB_LEVEL);
        if (value == null) {
            return 0;
        }
        java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d+").matcher(value);
        if (!digits.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.group());
        } catch (NumberFormatException tooBig) {
            return 0;
        }
    }

    private static ScoreboardLine tabRow(String label, Component line) {
        Component trimmed = trimLeading(line);
        return new ScoreboardLine(trimmed, ScoreboardReader.strip(trimmed.getString()),
                sbsSignature(label));
    }

    /**
     * The component without the leading whitespace Hypixel pads its tab rows with, styles otherwise
     * untouched. The tab list indents its widget block; the sidebar does not, so passing a tab line
     * through verbatim brought that indent along and left the row sitting a space to the right of
     * every other one.
     */
    private static Component trimLeading(Component line) {
        MutableComponent out = Component.empty();
        boolean[] trimming = {true};
        line.visit((style, text) -> {
            String part = text;
            if (trimming[0]) {
                int i = 0;
                while (i < part.length() && Character.isWhitespace(part.charAt(i))) {
                    i++;
                }
                part = part.substring(i);
                trimming[0] = part.isEmpty();
            }
            if (!part.isEmpty()) {
                out.append(Component.literal(part).setStyle(style));
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return out;
    }

    private static ScoreboardLine sbsRow(String label, String value, int rgb) {
        return sbsRow(label, value, NO_LABEL_COLOR, rgb);
    }

    /**
     * An SBS row whose label carries a colour of its own.
     *
     * <p>{@link #NO_LABEL_COLOR} keeps vanilla white, which is what every row but the two buff rows
     * wants: a panel where each label picked its own colour would be a panel with no columns left to
     * read down.
     */
    private static ScoreboardLine sbsRow(String label, String value, int labelRgb, int rgb) {
        String text = label + ": " + value;
        MutableComponent name = Component.literal(label + ": ");
        Component display = (labelRgb == NO_LABEL_COLOR
                ? name.withStyle(ChatFormatting.WHITE)
                : name.withStyle(style -> style.withColor(TextColor.fromRgb(labelRgb))))
                .append(Component.literal(value)
                        .withStyle(style -> style.withColor(TextColor.fromRgb(rgb))));
        return new ScoreboardLine(display, text, sbsSignature(label));
    }

    /**
     * The signature of an SBS row, keyed on its <b>label</b> rather than on its text.
     *
     * <p>The server's lines are keyed by a signature that collapses digits, which is stable because
     * only the number in them moves. That does not hold here: "Bank: 1B" and "Bank: 1.2B" collapse
     * to different shapes, and the clock changes every minute - so a content-derived key would lose
     * track of the row the moment its value ticked, taking the player's hide and order with it. The
     * label is the part of an SBS row that never changes.
     */
    private static String sbsSignature(String label) {
        return "sbs:" + label.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /**
     * Every row the panel could show, each at its default position: the server's own lines with the
     * SBS rows inserted where they belong (bank under purse, gems under bits, interest under bank,
     * the clock first, the rest as a block at the end).
     *
     * <p>Shared with {@link CustomScoreboardScreen} so the line editor lists exactly what the panel
     * draws - which is what lets an SBS row be hidden and re-ordered like any other. Hiding and
     * ordering are applied <i>after</i> this, over the combined list.
     */
    static List<ScoreboardLine> allRows(SBSConfig.CustomScoreboardSettings cfg) {
        return withTimeRow(cfg, withSbsRows(cfg, withInterestRow(cfg,
                withGemsRow(cfg, withBankRow(cfg, ScoreboardReader.lines())))));
    }

    /**
     * Draws the panel exactly as the HUD would, from a caller-supplied row list, and answers how big
     * it came out.
     *
     * <p>The editor's live preview. It goes through the same {@link #rows} resolution and the same
     * {@link #drawPanel} as the real thing rather than approximating it, so what the player arranges
     * is what the sidebar draws - the whole reason for showing a preview instead of a list of names.
     *
     * @param faded rows to grey out: the ones the layout places but the game is not showing right now
     */
    public static PanelSize drawPreview(GuiGraphicsExtractor g, SBSConfig.CustomScoreboardSettings cfg,
                                        List<ScoreboardLine> lines, Set<ScoreboardLine> faded,
                                        int x, int y) {
        Font font = Minecraft.getInstance().font;
        Metrics metrics = Metrics.of(cfg);
        Component title = title(cfg);
        List<Row> rows = rows(cfg, lines, faded == null ? Set.of() : faded);

        int contentWidth = title != null ? font.width(title) : 0;
        for (Row row : rows) {
            contentWidth = Math.max(contentWidth, font.width(row.text()));
        }
        int width = contentWidth + metrics.padX() * 2;
        int height = metrics.padY() * 2
                + (title != null ? font.lineHeight + metrics.titleGap() : 0)
                + rows.size() * (font.lineHeight + metrics.lineSpacing())
                - (rows.isEmpty() ? 0 : metrics.lineSpacing());

        drawPanel(g, cfg, metrics, rows, title, x, y, width, height);
        return new PanelSize(width, height);
    }

    /** The title to draw: the user's override when set, otherwise the server's title. */
    private static Component titleComponent(SBSConfig.CustomScoreboardSettings cfg) {
        if (cfg.customTitle != null && !cfg.customTitle.isBlank()) {
            return Component.literal(cfg.customTitle.trim());
        }
        Component serverTitle = ScoreboardReader.title();
        return serverTitle != null ? serverTitle : Component.empty();
    }

    /** ARGB re-alpha'd to an opacity percentage; the input's own alpha (e.g. a theme constant) is
     *  dropped so only its RGB carries over. */
    private static int withOpacity(int argbOrRgb, int opacityPercent) {
        int alpha = clamp(opacityPercent, 0, 100) * 255 / 100;
        return (alpha << 24) | (argbOrRgb & 0xFFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
