/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.IntConsumer;

/**
 * <b>The</b> numeric settings row: label on the left, a bounded track with a handle on the right,
 * and the live value in a box of its own at the far edge. Every bounded number in the mod – every
 * percentage, opacity, radius, delay and count – is drawn this way; see the "Numeric rows" section
 * of {@code ui/AGENTS.md}, which is binding and describes the anatomy below.
 *
 * <p><b>The three parts, in reading order.</b> The label says what the setting is. The track says
 * where in its range the value sits, at a glance and without reading a number – it is a fixed-width
 * area, so a column of these rows shows its values as one comparable bar chart. The value box says
 * the exact number, and is deliberately a box: it lands in the same column as a toggle row's
 * {@code ON}/{@code OFF} pill and is the same height, so a mixed list of toggles and sliders has one
 * right-hand edge instead of two.
 *
 * <p><b>Why the track is bounded rather than the whole row.</b> A full-width slider treats every
 * pixel of the row as its track, so clicking the <i>label</i> jumps the value – a player who clicks a
 * row to read its tooltip has silently changed the setting, and there is no undo. Only clicks and
 * drags inside the track move anything here. Arrow keys still work through
 * {@link AbstractSliderButton} once the row has focus.
 *
 * <p>The track shrinks before it would collide with the label, and its width is reserved from the
 * widest value the range can produce, so nothing on the row shifts while dragging.
 */
public class SciFiRangeSlider extends AbstractSliderButton {

    /** Left padding for the label – the value every other row type uses. */
    private static final int LABEL_PAD = 8;
    /** Right padding for the value box, matching {@link SciFiToggleButton}'s pill. */
    private static final int RIGHT_PAD = 6;
    /** Gap between label and track, and between track and value box. */
    private static final int GAP = 6;

    private static final int HANDLE_W = 6;
    private static final int TRACK_MAX_W = 110;
    private static final int TRACK_MIN_W = 24;
    /** Height of the track itself: a thin rail, so the handle riding it reads as the moving part. */
    private static final int TRACK_H = 6;
    /**
     * Vertical inset of the handle and the value box inside the row. Also
     * {@link SciFiToggleButton}'s pill inset – that is what keeps the two row types the same shape.
     */
    private static final int INSET_Y = 3;
    /** Minimum value-box width, again the ON/OFF pill's, so the right edges line up. */
    private static final int VALUE_BOX_MIN_W = 28;
    /** Padding inside the value box, left and right of the number. */
    private static final int VALUE_BOX_PAD = 5;

    private final Component label;
    private final int min;
    private final int max;
    private final String unit;
    private final IntConsumer onChange;

    /** True while a drag that STARTED inside the track is in progress. */
    private boolean draggingTrack;

    /**
     * The last value handed to {@code onChange}, so a drag only reports each step once.
     *
     * <p>{@link AbstractSliderButton#setValue} fires {@link #applyValue()} whenever the underlying
     * <i>double</i> moves, which during a drag is every mouse-move event - hundreds of them across a
     * 110 px track. Every one of those used to run the row's setter, and a settings row's setter
     * ends in {@code save()}, i.e. a full rewrite of {@code config.json}. Dragging one slider wrote
     * the whole config file several hundred times, nearly always with an identical integer.
     */
    private int lastReported = Integer.MIN_VALUE;

    public SciFiRangeSlider(int x, int y, int width, int height, Component label,
                            int min, int max, int initial, String unit, IntConsumer onChange) {
        super(x, y, width, height, label,
                (Math.max(min, Math.min(max, initial)) - min) / (double) (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.unit = unit;
        this.onChange = onChange;
    }

    private int intValue() {
        return min + (int) Math.round(this.value * (max - min));
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** Reserved from the widest value the range can produce, so nothing shifts as the number changes. */
    private int valueBoxWidth() {
        var font = Minecraft.getInstance().font;
        int widest = Math.max(font.width(min + unit), font.width(max + unit));
        return Math.max(VALUE_BOX_MIN_W, widest + VALUE_BOX_PAD * 2);
    }

    private int valueBoxX() {
        return getX() + getWidth() - RIGHT_PAD - valueBoxWidth();
    }

    /**
     * The track's width, in the order the row gives ground: it takes what is left after the label,
     * never less than {@link #TRACK_MIN_W} - at which point the label is the side that gets
     * ellipsised - and never so much that it would start left of the label's own padding.
     *
     * <p>That last clamp is the narrow-viewport case (1280x720 at GUI scale 4, a panel squeezed
     * against its minimum): without it the minimum-width track keeps its size by sliding off the left
     * edge of the row. Zero here means the row genuinely has no room for a track, and it draws as
     * label + value only rather than painting outside itself.
     */
    private int trackWidth() {
        var font = Minecraft.getInstance().font;
        int leftLimit = getX() + LABEL_PAD;
        int room = valueBoxX() - GAP - leftLimit;
        int free = room - font.width(label) - GAP;
        return Math.max(0, Math.min(Math.min(TRACK_MAX_W, room), Math.max(TRACK_MIN_W, free)));
    }

    private int trackX() {
        return valueBoxX() - GAP - trackWidth();
    }

    /**
     * The grabbable area. Deliberately taller than the drawn rail: the rail is 6 px so it reads as a
     * rail, and a 6 px click target at GUI scale 1 is not one.
     */
    private boolean inTrack(double mx, double my) {
        int tw = trackWidth();
        if (tw <= 0) {
            return false;
        }
        int tx = trackX();
        return mx >= tx && mx <= tx + tw
                && my >= getY() + INSET_Y && my <= getY() + getHeight() - INSET_Y;
    }

    /** Maps a mouse x inside the track onto [0,1]; {@link #setValue} clamps and fires the change. */
    private void setValueFromTrack(double mouseX) {
        int usable = trackWidth() - HANDLE_W;
        if (usable <= 0) {
            return;
        }
        setValue((mouseX - (trackX() + HANDLE_W / 2.0)) / usable);
    }

    // ------------------------------------------------------------------
    // Input – restricted to the bounded track
    // ------------------------------------------------------------------

    @Override
    public void onClick(MouseButtonEvent event, boolean doubled) {
        draggingTrack = inTrack(event.x(), event.y());
        if (draggingTrack) {
            setValueFromTrack(event.x());
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingTrack) {
            setValueFromTrack(event.x());
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        draggingTrack = false;
        super.onRelease(event);
    }

    @Override
    protected void updateMessage() {
        // The value is painted manually in extractWidgetRenderState; nothing to do here.
    }

    @Override
    protected void applyValue() {
        int current = intValue();
        if (current == lastReported) {
            return;   // same step, nothing to persist - see lastReported
        }
        lastReported = current;
        if (onChange != null) {
            onChange.accept(current);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        int textY = y + (h - font.lineHeight) / 2;

        boolean hovered = isHoveredOrFocused() && this.active;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

        int tx = trackX();
        int tw = trackWidth();
        // The track is clamped to a usable minimum width, so on a narrow row the label is what has to
        // give: without this it runs on under the track and the value.
        g.text(font, RowText.fit(font, label, tx - (x + LABEL_PAD) - GAP), x + LABEL_PAD, textY,
                SBSTheme.TEXT);

        boolean hot = (draggingTrack || inTrack(mouseX, mouseY)) && this.active;

        if (tw > 0) {
            // The rail: thin, centred, with its own outline so it reads as a bounded area rather than
            // as decoration on the card.
            int ty = y + (h - TRACK_H) / 2;
            SciFiRender.roundedRectWithBorder(g, tx, ty, tw, TRACK_H, 2, SBSTheme.CARD_BG_DISABLED,
                    hot ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT_SOFT);

            // Filled portion left of the handle: says how full the range is without reading the
            // number, which is also the non-colour cue for anyone the accent hue does not separate.
            int handleX = tx + (int) Math.round(this.value * Math.max(0, tw - HANDLE_W));
            if (handleX > tx + 1) {
                SciFiRender.roundedRect(g, tx + 1, ty + 1, handleX - tx - 1, TRACK_H - 2, 1,
                        SBSTheme.ACCENT_SOFT);
            }

            // The handle stands proud of the rail, top and bottom - it is the part you grab.
            SciFiRender.roundedRectWithBorder(g, handleX, y + INSET_Y, Math.min(HANDLE_W, tw),
                    h - INSET_Y * 2, 2, SBSTheme.ACCENT, SBSTheme.ACCENT_BRIGHT);
        }

        // The value box, in the same column and at the same height as a toggle row's ON/OFF pill.
        int boxW = valueBoxWidth();
        int boxX = valueBoxX();
        SciFiRender.roundedRectWithBorder(g, boxX, y + INSET_Y, boxW, h - INSET_Y * 2, 3,
                SBSTheme.CARD_BG_DISABLED, hot ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(intValue() + unit), boxX + boxW / 2, textY,
                this.active ? SBSTheme.ACCENT : SBSTheme.TEXT_MUTED);
    }
}
