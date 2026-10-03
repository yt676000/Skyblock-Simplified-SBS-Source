/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ConfigProfiles;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

/**
 * The config-profile picker in the bottom-left corner of the main screen: which named copy of the
 * settings is being edited, with creating, renaming and deleting all reachable from the one control.
 *
 * <p>It is a bespoke widget rather than a {@link SciFiDropdown} because the list is not a list of
 * values – its rows are <b>manageable objects</b>. Each custom profile carries a delete button, the
 * last row creates a new profile, and picking a row swaps the entire config underneath the screen.
 * Bolting that onto the generic dropdown would push delete buttons and rename state onto the island
 * filter, which wants none of it.
 *
 * <p><b>Three states</b>, because the field is the only piece of screen this control owns:
 * <ul>
 *   <li><b>Closed</b> – shows the active profile. A double-click starts a rename.</li>
 *   <li><b>Open</b> – the scrollable list is drawn over the screen by {@link #renderOverlay}, the
 *       same top-most-pass trick {@link SciFiDropdown} uses: widgets render in insertion order, so a
 *       list drawn in this widget's own pass would end up underneath the rows below it.</li>
 *   <li><b>Naming</b> – the field itself becomes a text input, so a new profile is named exactly
 *       where its name will appear. Confirming empty is allowed and yields "Custom N".</li>
 * </ul>
 *
 * <p>The widget must be the screen's focused child while naming, which happens by itself: a click
 * that returns true makes it focused, and that is how key events reach the inner {@link EditBox}.
 */
public final class ProfileSelector extends AbstractWidget {

    private static final int ROW_H = 13;
    private static final int MAX_VISIBLE = 6;

    /** Width of the list's scrollbar, drawn inside the popup along its right edge. */
    private static final int SCROLLBAR_W = 3;

    /** Side length of a row's delete button. */
    private static final int DELETE_SIZE = 7;
    private static final int DELETE_RED = 0xFFE0433C;

    /** The row that creates a new profile, always last in the list. */
    private static final String ADD_NEW = "+ Add new";

    private enum Mode { CLOSED, OPEN, NAMING, RENAMING }

    private final Runnable onProfileChanged;

    private Mode mode = Mode.CLOSED;
    private int scroll;

    /** True while the list's scrollbar thumb is being dragged. */
    private boolean draggingThumb;
    private double thumbGrab;

    /** The inline input, alive only while naming or renaming. */
    private EditBox nameBox;
    /** Which profile is being renamed ({@code null} while creating a new one). */
    private String renaming;

    /**
     * @param onProfileChanged run after the active profile, its name or the profile list changed –
     *                         the screen rebuilds itself there, since every setting it shows may now
     *                         hold a different value
     */
    public ProfileSelector(int x, int y, int width, int height, Runnable onProfileChanged) {
        super(x, y, width, height, Component.literal("Profile"));
        this.onProfileChanged = onProfileChanged;
    }

    public boolean isOpen() {
        return mode == Mode.OPEN;
    }

    /** True while the field is a text input, so the screen knows the keyboard is spoken for. */
    public boolean isEditing() {
        return mode == Mode.NAMING || mode == Mode.RENAMING;
    }

    /** Closes the list and cancels any edit – used when the screen wants the control out of the way. */
    public void close() {
        mode = Mode.CLOSED;
        nameBox = null;
        renaming = null;
        draggingThumb = false;
    }

    // ------------------------------------------------------------------
    // List model
    // ------------------------------------------------------------------

    /** The rows of the open list: every profile, then the "add new" row. */
    private List<String> rows() {
        List<String> rows = new java.util.ArrayList<>(ConfigProfiles.getInstance().names());
        rows.add(ADD_NEW);
        return rows;
    }

    private int visibleRows() {
        return Math.min(MAX_VISIBLE, Math.max(1, rows().size()));
    }

    private int listHeight() {
        return visibleRows() * ROW_H + 2;
    }

    private int listY() {
        // The control sits at the bottom of the screen, so the list almost always opens upwards;
        // the downward case is kept for a very tall window where there is room below.
        int below = getY() + getHeight() + 1;
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return below + listHeight() <= screenH ? below : getY() - listHeight() - 1;
    }

    private int maxScroll() {
        return Math.max(0, rows().size() - MAX_VISIBLE);
    }

    private boolean overList(double mx, double my) {
        int y = listY();
        return mode == Mode.OPEN && mx >= getX() && mx < getX() + getWidth()
                && my >= y && my < y + listHeight();
    }

    /**
     * The field <b>plus</b> the open list. This is not cosmetic: a screen dispatches a click only to
     * the child {@code getChildAt} finds under the cursor, so a widget that claims only its own box
     * would never be told about clicks on the list it drew outside that box - the rows would be
     * unclickable and the click would fall through to whatever is painted beneath them.
     *
     * <p>Clicks landing outside both areas still never arrive here; the screen forwards those, which
     * is what closes the list.
     */
    @Override
    public boolean isMouseOver(double mx, double my) {
        return overField(mx, my) || overList(mx, my);
    }

    /** Just the control's own box – what the click handling below means by "on the field". */
    private boolean overField(double mx, double my) {
        return super.isMouseOver(mx, my);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean hovered = isHovered();
        boolean lit = hovered || mode != Mode.CLOSED;
        SciFiRender.roundedRectWithBorder(g, getX(), getY(), getWidth(), getHeight(),
                SBSTheme.CORNER_RADIUS, lit ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                mode == Mode.CLOSED ? SBSTheme.CARD_BORDER : SBSTheme.ACCENT_BRIGHT);

        int textY = getY() + (getHeight() - font.lineHeight) / 2;
        if (isEditing() && nameBox != null) {
            // While naming, the field IS the input – no label competing for the width.
            nameBox.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }

        String label = "Profile";
        g.text(font, Component.literal(label), getX() + 6, textY, SBSTheme.TEXT_MUTED);

        String caret = mode == Mode.OPEN ? " ▴" : " ▾";
        int caretW = font.width(caret);
        int space = getWidth() - 12 - font.width(label) - caretW;
        String shown = trim(font, ConfigProfiles.getInstance().active(), Math.max(8, space));
        g.text(font, Component.literal(shown),
                getX() + getWidth() - 6 - caretW - font.width(shown), textY, SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal(caret), getX() + getWidth() - 6 - caretW, textY, SBSTheme.TEXT_MUTED);
    }

    /**
     * Draws the open list on top of everything. The caller must invoke this <b>after</b> every other
     * widget has rendered, otherwise the rows beneath the control paint over it.
     */
    public void renderOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (mode != Mode.OPEN) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        List<String> rows = rows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));

        int y = listY();
        int h = listHeight();
        SciFiRender.glow(g, getX(), y, getWidth(), h, SBSTheme.CORNER_RADIUS, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRectWithBorder(g, getX(), y, getWidth(), h, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, SBSTheme.ACCENT);

        boolean scrollable = maxScroll() > 0;
        int rowRight = getX() + getWidth() - (scrollable ? SCROLLBAR_W + 3 : 2);
        String activeName = ConfigProfiles.getInstance().active();

        int rowY = y + 1;
        for (int i = scroll; i < rows.size() && i < scroll + MAX_VISIBLE; i++) {
            String row = rows.get(i);
            boolean addNew = ADD_NEW.equals(row);
            boolean selected = !addNew && row.equals(activeName);
            boolean rowHovered = mouseX >= getX() && mouseX < rowRight
                    && mouseY >= rowY && mouseY < rowY + ROW_H;

            if (rowHovered || selected) {
                SciFiRender.roundedRect(g, getX() + 1, rowY, rowRight - getX() - 1, ROW_H - 1, 2,
                        rowHovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
            }

            boolean deletable = !addNew && !ConfigProfiles.isDefault(row);
            int textLimit = rowRight - getX() - 10 - (deletable ? DELETE_SIZE + 3 : 0);
            int color = addNew ? SBSTheme.ACCENT
                    : (selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            g.text(font, Component.literal(trim(font, row, textLimit)), getX() + 5,
                    rowY + (ROW_H - font.lineHeight) / 2, color);

            if (deletable) {
                int[] d = deleteBounds(rowY, rowRight);
                boolean overDelete = mouseX >= d[0] && mouseX < d[0] + DELETE_SIZE
                        && mouseY >= d[1] && mouseY < d[1] + DELETE_SIZE;
                drawDeleteButton(g, d[0], d[1], overDelete);
            }
            rowY += ROW_H;
        }

        if (scrollable) {
            drawScrollbar(g, y, h, mouseX, mouseY);
        }
    }

    /** {@code {x, y}} of a row's delete button. */
    private int[] deleteBounds(int rowY, int rowRight) {
        return new int[] {rowRight - DELETE_SIZE - 2, rowY + (ROW_H - DELETE_SIZE) / 2};
    }

    /** A small red square with a white ✕ – the same visual grammar as the GUI editor's minus button. */
    private static void drawDeleteButton(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
        SciFiRender.roundedRect(g, x, y, DELETE_SIZE, DELETE_SIZE, 2,
                hovered ? DELETE_RED : (DELETE_RED & 0x00FFFFFF) | 0xAA000000);
        int color = SBSTheme.ACCENT_BRIGHT;
        for (int i = 2; i < DELETE_SIZE - 2; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + i, y + DELETE_SIZE - 1 - i, x + i + 1, y + DELETE_SIZE - i, color);
        }
    }

    /** The list's scrollbar geometry {@code {trackTop, trackH, thumbY, thumbH, travel}}. */
    private int[] scrollbar(int listY, int listH) {
        int trackTop = listY + 1;
        int trackH = listH - 2;
        int thumbH = Math.max(8, trackH * MAX_VISIBLE / Math.max(1, rows().size()));
        int travel = trackH - thumbH;
        int thumbY = trackTop + (maxScroll() == 0 ? 0 : travel * scroll / maxScroll());
        return new int[] {trackTop, trackH, thumbY, thumbH, travel};
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int listY, int listH, int mouseX, int mouseY) {
        int[] sb = scrollbar(listY, listH);
        int x = getX() + getWidth() - SCROLLBAR_W - 1;
        SciFiRender.roundedRect(g, x, sb[0], SCROLLBAR_W, sb[1], SCROLLBAR_W / 2, 0x22FFFFFF);
        boolean overThumb = draggingThumb
                || (mouseX >= x - 2 && mouseX <= x + SCROLLBAR_W + 2
                        && mouseY >= sb[2] && mouseY <= sb[2] + sb[3]);
        SciFiRender.roundedRect(g, x, sb[2], SCROLLBAR_W, sb[3], SCROLLBAR_W / 2,
                overThumb ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();

        if (isEditing()) {
            if (overField(mx, my)) {
                nameBox.mouseClicked(event, doubled);   // place the caret inside the field
                return true;
            }
            commitEdit();       // clicking away confirms, the same as pressing Enter
            return true;        // that click dismissed the editor; it does not also act elsewhere
        }

        if (mode == Mode.OPEN) {
            // A double-click's FIRST click already opened the list, so the rename gesture has to be
            // recognised here rather than in the closed branch - by the time `doubled` is set, the
            // control is never closed any more.
            if (overField(mx, my)) {
                String activeName = ConfigProfiles.getInstance().active();
                if (doubled && !ConfigProfiles.isDefault(activeName)) {
                    startEdit(Mode.RENAMING, activeName);
                } else {
                    mode = Mode.CLOSED;
                }
                return true;
            }
            int y = listY();
            int h = listHeight();
            int[] sb = scrollbar(y, h);
            int sbX = getX() + getWidth() - SCROLLBAR_W - 1;
            if (maxScroll() > 0 && mx >= sbX - 2 && mx <= sbX + SCROLLBAR_W + 2
                    && my >= sb[0] && my <= sb[0] + sb[1]) {
                if (my >= sb[2] && my <= sb[2] + sb[3]) {
                    draggingThumb = true;
                    thumbGrab = my - sb[2];
                } else {
                    applyThumb(my - sb[3] / 2.0, sb);
                }
                return true;
            }
            if (overList(mx, my)) {
                handleListClick(mx, my, y);
                return true;
            }
            // A click anywhere else only dismisses the list. Swallowing it matters: the list hangs
            // over the module rows, and a dismissing click must not also select whatever is beneath.
            mode = Mode.CLOSED;
            return true;
        }

        if (overField(mx, my)) {
            mode = Mode.OPEN;
            scroll = 0;
            return true;
        }
        return false;
    }

    /** A click inside the open list: a delete button, the "add new" row, or picking a profile. */
    private void handleListClick(double mx, double my, int listY) {
        List<String> rows = rows();
        int index = scroll + (int) ((my - listY - 1) / ROW_H);
        if (index < 0 || index >= rows.size()) {
            return;
        }
        String row = rows.get(index);
        int rowY = listY + 1 + (index - scroll) * ROW_H;
        int rowRight = getX() + getWidth() - (maxScroll() > 0 ? SCROLLBAR_W + 3 : 2);

        if (ADD_NEW.equals(row)) {
            startEdit(Mode.NAMING, "");
            return;
        }
        if (!ConfigProfiles.isDefault(row)) {
            int[] d = deleteBounds(rowY, rowRight);
            if (mx >= d[0] && mx < d[0] + DELETE_SIZE && my >= d[1] && my < d[1] + DELETE_SIZE) {
                deleteProfile(row);
                return;
            }
        }
        mode = Mode.CLOSED;
        if (ConfigManager.getInstance().switchProfile(row)) {
            onProfileChanged.run();
        }
    }

    /**
     * Deletes a profile. Deleting the one currently loaded would leave the screen editing a file that
     * no longer exists, so that case switches back to Default first – and the switch saves the config
     * on the way out, which is why it must happen <i>before</i> the file is removed.
     */
    private void deleteProfile(String name) {
        boolean wasActive = name.equals(ConfigProfiles.getInstance().active());
        if (wasActive) {
            ConfigManager.getInstance().switchProfile(ConfigProfiles.DEFAULT);
        }
        ConfigProfiles.getInstance().delete(name);
        scroll = Math.min(scroll, maxScroll());
        if (wasActive) {
            onProfileChanged.run();
        }
    }

    private void startEdit(Mode target, String initial) {
        Font font = Minecraft.getInstance().font;
        mode = target;
        renaming = target == Mode.RENAMING ? initial : null;
        nameBox = new EditBox(font, getX() + 6, getY() + (getHeight() - font.lineHeight) / 2,
                getWidth() - 12, font.lineHeight, Component.literal("Profile name"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(ConfigProfiles.MAX_NAME_LENGTH);
        nameBox.setTextColor(SBSTheme.TEXT);
        nameBox.setHint(Component.literal(target == Mode.NAMING ? "Name (optional)" : "New name"));
        nameBox.setValue(target == Mode.RENAMING ? initial : "");
        nameBox.moveCursorToEnd(false);
        nameBox.setFocused(true);
    }

    /** Confirms the inline edit: creates the new profile, or applies the rename. */
    private void commitEdit() {
        String typed = nameBox == null ? "" : nameBox.getValue();
        Mode was = mode;
        String renamed = renaming;
        close();
        if (was == Mode.NAMING) {
            String created = ConfigProfiles.getInstance().create(typed);
            if (created != null) {
                ConfigManager.getInstance().switchProfile(created);
                onProfileChanged.run();
            }
        } else if (was == Mode.RENAMING && renamed != null) {
            if (ConfigProfiles.getInstance().rename(renamed, typed) != null) {
                onProfileChanged.run();
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isEditing()) {
            return false;
        }
        int key = event.key();
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            commitEdit();
            return true;
        }
        if (key == GLFW_KEY_ESCAPE) {
            close();   // abandon the edit without touching any file
            return true;
        }
        return nameBox.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return isEditing() && nameBox.charTyped(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingThumb) {
            applyThumb(event.y() - thumbGrab, scrollbar(listY(), listHeight()));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingThumb) {
            draggingThumb = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void applyThumb(double thumbY, int[] sb) {
        int travel = sb[4];
        if (travel <= 0) {
            return;
        }
        double fraction = Math.max(0.0, Math.min(1.0, (thumbY - sb[0]) / travel));
        scroll = (int) Math.round(fraction * maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mode != Mode.OPEN || scrollY == 0 || !overList(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), maxScroll()));
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        // Losing focus mid-edit (tabbing away, the screen focusing something else) confirms rather
        // than silently discarding what was typed.
        if (!focused && isEditing()) {
            commitEdit();
        }
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.literal("Profile: " + ConfigProfiles.getInstance().active()));
    }
}
