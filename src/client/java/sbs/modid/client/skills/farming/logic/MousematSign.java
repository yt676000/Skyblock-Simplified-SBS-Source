/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The Squeaky Mousemat's angle sign: recognising it, filling it in, and the crop panel drawn beside
 * it. The saved angles themselves live in {@link MousematAngles}; this is only the screen half.
 *
 * <p><b>How the sign is recognised.</b> Hypixel labels the two input lines itself - one line says to
 * set the yaw above it, the next says to set the pitch below it - so the labels are the anchor, not
 * a line index. The yaw input is then the line above its label and the pitch input the line below
 * its own, which means a sign that grows a line or swaps its order still parses. Nothing else in
 * SkyBlock opens a sign worded like that, so this never has to ask what the player is holding.
 *
 * <p><b>What it writes.</b> On open, the angle saved for the active crop is typed into the two input
 * lines - but only an angle that was actually saved. Filling in a default would aim the player
 * somewhere they never chose, and there is no taking it back: Minecraft's sign screen sends its
 * lines from {@code removed}, so a sign is submitted however it is left, Escape included.
 *
 * <p><b>What it learns.</b> When the sign closes, whatever is on those two lines is saved as the
 * active crop's angle. Typing a number by hand is therefore also how you teach it one, and no
 * separate "save" step exists to forget.
 */
public final class MousematSign {

    private static final MousematSign INSTANCE = new MousematSign();

    /** Hypixel's own wording on the two label lines, lower-cased. */
    private static final String YAW_LABEL = "set yaw";
    private static final String PITCH_LABEL = "set pitch";

    private static final int PAD = 6;
    private static final int ROW_H = 14;
    private static final int ROW_GAP = 2;
    private static final int PANEL_W = 188;
    private static final int MIN_PANEL_W = 120;

    /** One drawn crop row, kept so a click can be resolved back to its crop. */
    private record Row(int x, int y, int w, int h, CropType crop) {

        boolean hit(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    /** Rows from the last frame; empty whenever no panel is up. */
    private volatile List<Row> rows = List.of();

    /**
     * Writes a line of the open sign - bound while the sign is up so the panel can type into it
     * without knowing anything about the screen. {@code (lineIndex, text)}.
     */
    private volatile BiConsumer<Integer, String> writer;

    private volatile int yawLine = -1;
    private volatile int pitchLine = -1;

    private MousematSign() {
    }

    public static MousematSign getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ detection

    /**
     * Locates the two input lines on a sign, or {@code null} when it is not the Mousemat's.
     *
     * @return {@code [yawLine, pitchLine]}, both valid indices into {@code messages}
     */
    public static int[] locateInputs(String[] messages) {
        if (messages == null || messages.length < 2) {
            return null;
        }
        int yaw = -1;
        int pitch = -1;
        for (int i = 0; i < messages.length; i++) {
            String line = messages[i] == null ? ""
                    : FarmingText.strip(messages[i]).toLowerCase(Locale.ROOT);
            if (yaw < 0 && line.contains(YAW_LABEL)) {
                yaw = i - 1;        // "set yaw ABOVE" - the input is the line before the label
            } else if (pitch < 0 && line.contains(PITCH_LABEL)) {
                pitch = i + 1;      // "set pitch BELOW"
            }
        }
        if (yaw < 0 || pitch < 0 || yaw >= messages.length || pitch >= messages.length
                || yaw == pitch) {
            return null;
        }
        return new int[] {yaw, pitch};
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called when a sign edit screen is set up. Returns whether it is the Mousemat's angle sign, and
     * if it is, fills in the active crop's saved angle.
     *
     * @param writer writes one line of the sign, by index
     * @param fill   whether to type the saved angle in. False on a re-init - the screen is set up
     *               again whenever the window is resized, and re-filling then would throw away
     *               whatever the player had typed since it first opened
     */
    public boolean onSignOpened(String[] messages, BiConsumer<Integer, String> writer, boolean fill) {
        rows = List.of();
        this.writer = null;
        yawLine = -1;
        pitchLine = -1;
        // Shape first, permission second. The other order is silent in the one case worth hearing
        // about: a refusal on an angle sign that IS in front of the player looks identical to every
        // ordinary sign in the game being ignored, and "the overlay just doesn't come up here" is
        // undiagnosable without knowing which of the two happened.
        int[] inputs = locateInputs(messages);
        if (inputs == null) {
            return false;                       // not the angle sign; every other sign lands here
        }
        if (!MousematAngles.enabled()) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Mousemat] angle sign ignored: helper off or off-island (location: {})",
                    sbs.modid.client.core.location.SkyBlockLocation.describe());
            return false;
        }
        yawLine = inputs[0];
        pitchLine = inputs[1];
        this.writer = writer;

        if (!fill) {
            return true;
        }
        CropType crop = MousematAngles.getInstance().activeCrop();
        MousematAngles.Angle angle = MousematAngles.getInstance().angleFor(crop);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Mousemat] angle sign open | yawLine={} pitchLine={} crop={} saved={}",
                yawLine, pitchLine, crop == null ? "none" : crop.name(),
                angle == null ? "none" : angle.text());
        if (angle != null && ConfigManager.getInstance().get().farming.mousematAutoFill) {
            write(angle);
        }
        return true;
    }

    /** Types an angle onto the sign's two input lines. */
    private void write(MousematAngles.Angle angle) {
        BiConsumer<Integer, String> sink = writer;
        if (sink == null || angle == null) {
            return;
        }
        sink.accept(yawLine, MousematAngles.format(angle.yaw()));
        sink.accept(pitchLine, MousematAngles.format(angle.pitch()));
    }

    /**
     * Called when the sign closes: saves whatever is on the input lines as the active crop's angle.
     *
     * <p>Both halves have to read as numbers before anything is stored - a half-typed sign that was
     * escaped out of should leave the previous angle alone rather than overwrite it with a fragment.
     */
    public void onSignClosed(String[] messages) {
        BiConsumer<Integer, String> sink = writer;
        writer = null;
        rows = List.of();
        if (sink == null || messages == null || yawLine < 0 || pitchLine < 0
                || yawLine >= messages.length || pitchLine >= messages.length) {
            return;
        }
        CropType crop = MousematAngles.getInstance().activeCrop();
        double yaw = MousematAngles.parse(messages[yawLine]);
        double pitch = MousematAngles.parse(messages[pitchLine]);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Mousemat] angle sign closed | crop={} typed yaw='{}' pitch='{}'",
                crop == null ? "none" : crop.name(), messages[yawLine], messages[pitchLine]);
        if (crop != null && !Double.isNaN(yaw) && !Double.isNaN(pitch)) {
            MousematAngles.getInstance().save(crop, yaw, pitch);
        }
    }

    // ------------------------------------------------------------------ picking

    /** Moves the active crop by {@code step} through the crops that have a tool, and re-fills. */
    public void cycle(int step) {
        List<CropType> crops = CropType.withTools();
        if (crops.isEmpty()) {
            return;
        }
        CropType active = MousematAngles.getInstance().activeCrop();
        int index = active == null ? -1 : crops.indexOf(active);
        // From "no crop yet", down lands on the first and up on the last, so a single key press
        // always produces a selection instead of needing a first press to do nothing.
        int next = index < 0
                ? (step > 0 ? 0 : crops.size() - 1)
                : Math.floorMod(index + step, crops.size());
        select(crops.get(next));
    }

    /** Makes a crop active and types its angle onto the sign, if it has one saved. */
    public void select(CropType crop) {
        MousematAngles.getInstance().setActiveCrop(crop);
        write(MousematAngles.getInstance().angleFor(crop));
    }

    /** A click on the panel, in GUI coordinates. Returns whether a row took it. */
    public boolean click(double mouseX, double mouseY) {
        if (writer == null) {
            return false;
        }
        for (Row row : rows) {
            if (row.hit(mouseX, mouseY)) {
                select(row.crop());
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ render

    /**
     * Draws the crop list beside the sign.
     *
     * <p>Anchored to the left edge rather than centred, for the same reason the search panel is:
     * the sign and its Done button are both centred, and a panel over that button reads as the
     * button having gone missing.
     */
    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        if (writer == null) {
            rows = List.of();
            return;
        }
        List<CropType> crops = CropType.withTools();
        CropType active = MousematAngles.getInstance().activeCrop();

        int rowStep = ROW_H + ROW_GAP;
        int height = PAD * 2 + font.lineHeight + ROW_GAP + crops.size() * rowStep
                + font.lineHeight + ROW_GAP;
        int panelW = Math.min(PANEL_W, Math.max(MIN_PANEL_W, g.guiWidth() / 2 - PAD * 2));
        int x = PAD;
        int y = Math.max(PAD, (g.guiHeight() - height) / 2);

        SciFiRender.glow(g, x, y, panelW, height, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, panelW, height, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, panelW - 2, height - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int ix = x + PAD;
        int rowW = panelW - PAD * 2;
        int iy = y + PAD;

        g.text(font, Component.literal("Crop angles (yaw/pitch)"), ix, iy, SBSTheme.TEXT_MUTED);
        iy += font.lineHeight + ROW_GAP;

        List<Row> laid = new ArrayList<>(crops.size());
        for (CropType crop : crops) {
            boolean selected = crop == active;
            boolean hovered = mouseX >= ix && mouseX < ix + rowW && mouseY >= iy && mouseY < iy + ROW_H;
            SciFiRender.roundedRect(g, ix, iy, rowW, ROW_H, SBSTheme.CORNER_RADIUS,
                    selected || hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);

            MousematAngles.Angle angle = MousematAngles.getInstance().angleFor(crop);
            String value = angle == null ? "-" : angle.text();
            int valueW = font.width(value);
            int textY = iy + (ROW_H - font.lineHeight) / 2;
            g.text(font, Component.literal(
                            trim(font, MousematAngles.label(crop), rowW - valueW - 14)),
                    ix + 4, textY, selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            g.text(font, Component.literal(value), ix + rowW - valueW - 4, textY,
                    angle == null ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT);

            laid.add(new Row(ix, iy, rowW, ROW_H, crop));
            iy += rowStep;
        }
        rows = List.copyOf(laid);

        // The footer says what closing the sign will do, because that is the one thing about this
        // panel that is not visible from it: the typed numbers are saved to the highlighted crop.
        g.text(font, Component.literal(trim(font, active == null
                        ? "Pick a crop to save what you type"
                        : "Saves to " + MousematAngles.label(active), rowW)),
                ix, iy, SBSTheme.TEXT_MUTED);
    }

    /** Cuts a label to fit, with an ellipsis, so a long crop name cannot run under its value. */
    private static String trim(Font font, String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        StringBuilder shortened = new StringBuilder(text);
        while (shortened.length() > 1 && font.width(shortened + "...") > maxWidth) {
            shortened.setLength(shortened.length() - 1);
        }
        return shortened + "...";
    }
}
