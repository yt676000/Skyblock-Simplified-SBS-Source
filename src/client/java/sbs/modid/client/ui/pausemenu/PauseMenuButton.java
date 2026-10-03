/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.pausemenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.component.SciFiButton;

/**
 * The SBS button in the vanilla pause menu – clickable to open the SBS menu, draggable to put it
 * anywhere on the screen, and scrollable to resize it.
 *
 * <p><b>One layout system.</b> Its position, size and corner radius live in the same
 * {@link PauseButtonStyle} map as every vanilla pause button (under {@link #ID}), so the pause-menu
 * editor treats it exactly like the others – and the drag / scroll gestures here write into that same
 * style. Changing it in the pause menu directly and changing it in the editor are therefore two ways
 * into one setting, not two settings that can disagree.
 *
 * <p><b>Click vs. drag.</b> {@link net.minecraft.client.gui.components.AbstractButton} fires its
 * action on <i>press</i>, which would open the menu the instant you grab the button to move it. So
 * the press is intercepted ({@link #onClick} deliberately does not call super) and the action runs on
 * <i>release</i> instead, but only when the mouse stayed within {@link #DRAG_THRESHOLD} pixels –
 * beyond that it was a drag and the new position is saved rather than the menu opened. The threshold
 * is what keeps an ordinary click from nudging the button by a pixel.
 *
 * <p><b>Anchor.</b> The style's offset is measured from the default bottom-left corner, recomputed
 * for the current screen. That is what lets a button placed in fullscreen still make sense in a small
 * window, and it is the same "base + offset" rule {@link PauseMenuLayout} applies to the vanilla
 * buttons.
 */
public final class PauseMenuButton extends SciFiButton {

    /** Config key for this button's {@link PauseButtonStyle} – never rename it. */
    public static final String ID = "sbs_menu";

    /** The untouched size; {@link PauseButtonStyle} overrides it once the button is resized. */
    public static final int BASE_WIDTH = 70;
    public static final int BASE_HEIGHT = 20;

    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 250;

    /** Percentage points one scroll notch adds or removes. */
    private static final int SCALE_STEP = 10;

    private static final Component LABEL = Component.literal("SBS Menu");
    private static final Component SHORT_LABEL = Component.literal("SBS");

    /** Distance from the screen edges for the default (never-moved) bottom-left position. */
    private static final int DEFAULT_MARGIN = 8;

    /** Movement in GUI px that turns a press into a drag instead of a click. */
    private static final int DRAG_THRESHOLD = 4;

    /** Where inside the button it was grabbed, so it does not jump to the cursor on the first drag. */
    private double grabOffsetX;
    private double grabOffsetY;

    /** Total distance moved since the press, used against {@link #DRAG_THRESHOLD}. */
    private double travelled;

    private boolean dragged;

    public PauseMenuButton(Runnable openAction) {
        super(0, 0, width(), height(), LABEL, openAction);
        applyStyle();
        setTooltip(Tooltip.create(Component.literal(
                "Click: open the SBS menu  •  Drag: move it  •  Scroll: resize it")));
    }

    private static SBSConfig.HypixelGuiSettings cfg() {
        return ConfigManager.getInstance().get().hypixelGui;
    }

    private static PauseButtonStyle style() {
        return PauseMenuStyles.get(ID);
    }

    /** Whether the button should be shown in the pause menu at all. */
    public static boolean enabled() {
        return cfg().pauseButton;
    }

    /** The button's live width in GUI px. */
    public static int width() {
        return style().width(BASE_WIDTH);
    }

    /** The button's live height in GUI px. */
    public static int height() {
        return style().height(BASE_HEIGHT);
    }

    /**
     * Size as a percentage of the base, for the settings slider. Read off the width, because a slider
     * has one number and the editor can set width and height independently.
     */
    public static int scale() {
        return Math.clamp(width() * 100 / BASE_WIDTH, MIN_SCALE, MAX_SCALE);
    }

    /** Applies a percentage size, keeping the base proportions. */
    public static void setScale(int percent) {
        int clamped = Math.clamp(percent, MIN_SCALE, MAX_SCALE);
        PauseMenuStyles.getOrCreate(ID).resize(BASE_WIDTH * clamped / 100, BASE_HEIGHT * clamped / 100);
        PauseMenuStyles.save();
    }

    /** Forgets a dragged position, putting the button back in the bottom-left corner. */
    public static void resetPosition() {
        PauseButtonStyle style = PauseMenuStyles.getOrCreate(ID);
        style.dx = 0;
        style.dy = 0;
        PauseMenuStyles.save();
    }

    /**
     * The default anchor for the current screen – bottom-left, and the point every offset is measured
     * from. Deliberately computed from the <b>base</b> height rather than the live one: the anchor has
     * to be independent of the style, or resetting the style would leave the button measured against a
     * size it no longer has.
     */
    private static int defaultX() {
        return DEFAULT_MARGIN;
    }

    private static int defaultY(int screenHeight) {
        return screenHeight - BASE_HEIGHT - DEFAULT_MARGIN;
    }

    /** Applies the configured size, corner radius and label to this instance. */
    public void applyStyle() {
        setSize(width(), height());
        setCornerRadius(style().corner);
        applyLabel();
    }

    /** The full label whenever it fits inside the button, the short one when it would spill out. */
    private void applyLabel() {
        var font = Minecraft.getInstance().font;
        setMessage(font.width(LABEL) + 6 <= getWidth() ? LABEL : SHORT_LABEL);
    }

    /**
     * Parks the button, unstyled, at its default bottom-left anchor.
     *
     * <p>Deliberately neither the saved position nor the styled size: {@link PauseMenuLayout} captures
     * this spot as the base and applies the style on top, exactly as it does for a vanilla button
     * sitting where the grid put it. Applying the style here as well would apply it twice.
     */
    public void applyDefaultPosition(int screenWidth, int screenHeight) {
        setSize(BASE_WIDTH, BASE_HEIGHT);
        applyLabel();
        setX(defaultX());
        setY(Math.max(0, defaultY(screenHeight)));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // Deliberately NOT super.onClick: that would fire the action on press, before we can tell
        // a click from the start of a drag. Released below instead.
        grabOffsetX = event.x() - getX();
        grabOffsetY = event.y() - getY();
        travelled = 0;
        dragged = false;
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        travelled += Math.abs(dragX) + Math.abs(dragY);
        if (!dragged && travelled < DRAG_THRESHOLD) {
            return;
        }
        dragged = true;
        var window = Minecraft.getInstance().getWindow();
        int screenWidth = window.getGuiScaledWidth();
        int screenHeight = window.getGuiScaledHeight();
        setX(Math.clamp((int) Math.round(event.x() - grabOffsetX), 0,
                Math.max(0, screenWidth - getWidth())));
        setY(Math.clamp((int) Math.round(event.y() - grabOffsetY), 0,
                Math.max(0, screenHeight - getHeight())));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (dragged) {
            // Stored as the offset from the default anchor, so the same style works at any window size.
            var window = Minecraft.getInstance().getWindow();
            PauseButtonStyle style = PauseMenuStyles.getOrCreate(ID);
            style.dx = getX() - defaultX();
            style.dy = getY() - defaultY(window.getGuiScaledHeight());
            PauseMenuStyles.save();
        } else {
            onPress(event);
        }
        travelled = 0;
        dragged = false;
    }

    /**
     * Scrolling over the button resizes it. It grows from its top-left corner, so the corner you
     * dragged it to is the one that stays put, and the new size is clamped back into the screen.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        int updated = Math.clamp(scale() + (int) Math.signum(scrollY) * SCALE_STEP, MIN_SCALE, MAX_SCALE);
        if (updated == scale()) {
            return true;   // already at the limit - still ours, so the scroll goes nowhere else
        }
        setScale(updated);
        applyStyle();
        var window = Minecraft.getInstance().getWindow();
        setX(Math.clamp(getX(), 0, Math.max(0, window.getGuiScaledWidth() - getWidth())));
        setY(Math.clamp(getY(), 0, Math.max(0, window.getGuiScaledHeight() - getHeight())));
        return true;
    }
}
