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
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertical list whose entries can be dragged into a new order, and dragged across into a paired
 * list.
 *
 * <p>Written once and shared, because there is more than one place in this mod that wants "arrange
 * these into an order you like": the Custom Scoreboard's layout editor, and the Favourites page's
 * ordering. Two hand-rolled drag implementations diverge - one grows a keyboard path and the other
 * does not, one indicates the drop point and the other guesses - so there is exactly one here and
 * both call it.
 *
 * <p><b>Drag is never the only way to do anything.</b> Every operation the mouse can perform has a
 * key: {@code ↑}/{@code ↓} move the selection, {@code Shift+↑}/{@code Shift+↓} move the selected
 * entry, and {@code Enter} / {@code Delete} send it across to the paired list. A list that can only
 * be ordered by dragging is a list some people cannot order at all.
 *
 * <p>Not an {@link net.minecraft.client.gui.components.AbstractWidget}: a drag that starts in one
 * list and ends in another needs the two to see each other and needs the dragged entry drawn above
 * both of them, neither of which survives being dispatched per-widget. The owning screen drives it -
 * {@link #render}, then the mouse and key methods, then one static {@link #renderDrag} pass last.
 *
 * @param <T> the entry type; both paired lists must agree on it
 */
public final class ReorderableList<T> {

    /** Row geometry. Tighter than {@link SBSTheme#ENTRY_HEIGHT}: these lists are long. */
    private static final int ROW_HEIGHT = 15;
    private static final int ROW_GAP = 2;
    private static final int SCROLLBAR_WIDTH = 3;
    private static final int SCROLL_STEP = ROW_HEIGHT + ROW_GAP;

    /** How far the cursor must travel before a press becomes a drag rather than a click. */
    private static final int DRAG_THRESHOLD = 3;

    /** What the list needs to know about an entry. Everything else is the caller's business. */
    public interface Adapter<T> {

        /** The text drawn on the row. */
        String label(T item);

        /** A short qualifier drawn right-aligned and muted, or {@code ""}. */
        default String note(T item) {
            return "";
        }

        /** The row's text colour. */
        default int color(T item) {
            return SBSTheme.TEXT;
        }

        /** Whether dragging this entry out of a copy-source list leaves it behind. */
        default boolean repeatable(T item) {
            return false;
        }
    }

    /**
     * The drag in progress, if any.
     *
     * <p>Static because a drag belongs to the cursor rather than to either list - the entry leaves
     * one and may or may not arrive in the other, and in between it is drawn over both. One cursor
     * means one session; a second would be a bug rather than a case to support.
     */
    private static Drag active;

    private record Drag(ReorderableList<?> source, Object item, int fromIndex, boolean copy) {
    }

    private final String title;
    private final Adapter<T> adapter;
    private final List<T> items = new ArrayList<>();

    private int x;
    private int y;
    private int width;
    private int height;

    private ReorderableList<T> peer;
    private Runnable onChange = () -> { };
    private boolean orderable = true;
    private boolean copySource;
    private boolean focused;

    private int scroll;
    private int selected = -1;
    private int pressIndex = -1;
    private double pressX;
    private double pressY;

    public ReorderableList(String title, Adapter<T> adapter) {
        this.title = title;
        this.adapter = adapter;
    }

    // ------------------------------------------------------------------ configuration

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        clampScroll();
    }

    /** The list a dragged-out entry travels to, and that {@code Enter} sends the selection to. */
    public void setPeer(ReorderableList<T> peer) {
        this.peer = peer;
    }

    /** Called after any change this list made to either side's contents. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    /**
     * Whether entries can be moved <i>within</i> this list. A palette is a set rather than an order,
     * so it takes drops but keeps whatever order its owner rebuilds it in.
     */
    public void setOrderable(boolean orderable) {
        this.orderable = orderable;
    }

    /** Whether a {@link Adapter#repeatable} entry dragged out of here is copied rather than moved. */
    public void setCopySource(boolean copySource) {
        this.copySource = copySource;
    }

    public void setItems(List<T> next) {
        items.clear();
        items.addAll(next);
        if (selected >= items.size()) {
            selected = items.size() - 1;
        }
        clampScroll();
    }

    public List<T> items() {
        return List.copyOf(items);
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public boolean isFocused() {
        return focused;
    }

    /** The entry the keyboard is on, or {@code null}. */
    public T selection() {
        return selected >= 0 && selected < items.size() ? items.get(selected) : null;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // ------------------------------------------------------------------ rendering

    /** Draws the list. Call {@link #renderDrag} once, after every list, for the floating entry. */
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        // A list that would take the drop lights up even when it has no insertion point to show,
        // which is the only feedback a palette being used as the remove target ever gets.
        boolean dropTarget = active != null && contains(mouseX, mouseY)
                && (active.source() == this || active.source().peer == this);
        int border = dropTarget ? SBSTheme.ACCENT_BRIGHT
                : (focused ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
        SciFiRender.roundedRectWithBorder(g, x, y, width, height, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, border);

        int insertion = insertionIndex(mouseX, mouseY);
        g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        if (items.isEmpty() && insertion < 0) {
            g.centeredText(font, Component.literal(title + " - empty"),
                    x + width / 2, y + height / 2 - font.lineHeight / 2, SBSTheme.TEXT_MUTED);
        }
        int rowY = y + ROW_GAP - scroll;
        for (int i = 0; i <= items.size(); i++) {
            if (i == insertion) {
                // The rows below part, and the gap is where the drop lands - the indicator and the
                // behaviour are the same thing rather than a marker drawn near it.
                g.fill(x + 3, rowY + ROW_GAP, x + width - 3, rowY + ROW_GAP + 2, SBSTheme.ACCENT_BRIGHT);
                rowY += ROW_HEIGHT + ROW_GAP;
            }
            if (i == items.size()) {
                break;
            }
            drawRow(g, font, items.get(i), i, rowY, mouseX, mouseY);
            rowY += ROW_HEIGHT + ROW_GAP;
        }
        g.disableScissor();

        if (maxScroll() > 0) {
            drawScrollbar(g);
        }
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, T item, int index, int rowY,
                         int mouseX, int mouseY) {
        if (rowY + ROW_HEIGHT < y || rowY > y + height) {
            return;
        }
        int rowW = rowWidth();
        boolean hovered = mouseX >= x && mouseX < x + rowW && mouseY >= rowY
                && mouseY < rowY + ROW_HEIGHT;
        boolean current = index == selected;
        int fill = current ? SBSTheme.CARD_BG_HOVER : (hovered ? SBSTheme.SEARCH_FILL : 0);
        if (fill != 0) {
            SciFiRender.roundedRect(g, x + 2, rowY, rowW - 4, ROW_HEIGHT, 2, fill);
        }
        if (current && focused) {
            g.fill(x + 2, rowY, x + 4, rowY + ROW_HEIGHT, SBSTheme.ACCENT);
        }

        int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2;
        // The grip is what says "this is draggable" before anyone tries it.
        g.text(font, Component.literal("⁝⁝"), x + 6, textY, SBSTheme.TEXT_MUTED, false);

        String note = adapter.note(item);
        int noteWidth = note.isEmpty() ? 0 : font.width(note) + 6;
        int labelLeft = x + 16;
        int labelRoom = rowW - (labelLeft - x) - noteWidth - 6;
        g.text(font, Component.literal(ellipsise(font, adapter.label(item), labelRoom)),
                labelLeft, textY, adapter.color(item), false);
        if (!note.isEmpty()) {
            g.text(font, Component.literal(note), x + rowW - 4 - font.width(note), textY,
                    SBSTheme.TEXT_MUTED, false);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int trackX = x + width - SCROLLBAR_WIDTH - 2;
        int content = contentHeight();
        int thumbH = Math.max(12, height * height / Math.max(1, content));
        int travel = height - thumbH;
        int thumbY = y + (maxScroll() <= 0 ? 0 : scroll * travel / maxScroll());
        g.fill(trackX, y + 2, trackX + SCROLLBAR_WIDTH, y + height - 2, SBSTheme.CARD_BORDER);
        SciFiRender.roundedRect(g, trackX, thumbY, SCROLLBAR_WIDTH, thumbH, 1, SBSTheme.ACCENT);
    }

    /**
     * Draws the entry being dragged, following the cursor. Call once per frame, after every list, so
     * it is above all of them.
     */
    public static void renderDrag(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Drag drag = active;
        if (drag == null) {
            return;
        }
        drag.source().drawGhost(g, mouseX, mouseY);
    }

    @SuppressWarnings("unchecked")
    private void drawGhost(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        T item = (T) active.item();
        String label = adapter.label(item);
        int w = font.width(label) + 20;
        int gx = mouseX + 8;
        int gy = mouseY - ROW_HEIGHT / 2;
        SciFiRender.roundedRectWithBorder(g, gx, gy, w, ROW_HEIGHT, 2,
                SBSTheme.CARD_BG_HOVER, SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal(label), gx + 6, gy + (ROW_HEIGHT - font.lineHeight) / 2,
                adapter.color(item), false);
    }

    // ------------------------------------------------------------------ mouse

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        focused = true;
        if (peer != null) {
            peer.focused = false;
        }
        int index = rowAt(mouseY);
        pressIndex = index;
        pressX = mouseX;
        pressY = mouseY;
        if (index >= 0) {
            selected = index;
        }
        return true;
    }

    /** Starts the drag once the cursor has actually travelled; returns whether it owns the gesture. */
    public boolean mouseDragged(double mouseX, double mouseY) {
        if (active != null) {
            return active.source() == this;
        }
        if (pressIndex < 0 || pressIndex >= items.size()) {
            return false;
        }
        if (Math.abs(mouseX - pressX) < DRAG_THRESHOLD && Math.abs(mouseY - pressY) < DRAG_THRESHOLD) {
            return false;
        }
        T item = items.get(pressIndex);
        boolean copy = copySource && adapter.repeatable(item);
        active = new Drag(this, item, pressIndex, copy);
        if (!copy) {
            items.remove(pressIndex);
            clampScroll();
        }
        selected = -1;
        return true;
    }

    /** Finishes a drag this list started. Dropped nowhere useful, the entry goes back where it was. */
    @SuppressWarnings("unchecked")
    public boolean mouseReleased(double mouseX, double mouseY) {
        pressIndex = -1;
        Drag drag = active;
        if (drag == null || drag.source() != this) {
            return false;
        }
        active = null;
        T item = (T) drag.item();
        ReorderableList<T> target = null;
        if (contains(mouseX, mouseY)) {
            target = this;
        } else if (peer != null && peer.contains(mouseX, mouseY)) {
            target = peer;
        }
        if (target == null) {
            // Cancelled: a mis-drop must cost nothing, so a copy evaporates and a move goes home.
            if (!drag.copy()) {
                items.add(Math.min(drag.fromIndex(), items.size()), item);
            }
            return true;
        }
        int index = target.orderable ? target.insertionRow(mouseY) : target.items.size();
        target.items.add(Math.min(index, target.items.size()), item);
        target.selected = Math.min(index, target.items.size() - 1);
        target.clampScroll();
        clampScroll();
        onChange.run();
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!contains(mouseX, mouseY) || maxScroll() <= 0) {
            return false;
        }
        scroll -= (int) Math.signum(amount) * SCROLL_STEP;
        clampScroll();
        return true;
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * The keyboard path, and the reason this component can be used at all by anyone who cannot drag
     * comfortably: {@code ↑}/{@code ↓} to choose, {@code Shift} with them to move, {@code Enter} or
     * {@code Delete} to send the entry to the paired list.
     */
    public boolean keyPressed(int key, int modifiers) {
        if (!focused || items.isEmpty()) {
            return false;
        }
        boolean shift = (modifiers & 0x0001) != 0; // GLFW_MOD_SHIFT
        switch (key) {
            case 265 -> { // GLFW_KEY_UP
                if (shift && orderable) {
                    move(-1);
                } else {
                    select(selected - 1);
                }
                return true;
            }
            case 264 -> { // GLFW_KEY_DOWN
                if (shift && orderable) {
                    move(1);
                } else {
                    select(selected + 1);
                }
                return true;
            }
            case 268 -> { // GLFW_KEY_HOME
                select(0);
                return true;
            }
            case 269 -> { // GLFW_KEY_END
                select(items.size() - 1);
                return true;
            }
            case 257, 335, 259, 261 -> { // ENTER, KP_ENTER, BACKSPACE, DELETE
                sendToPeer();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void select(int index) {
        if (items.isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(items.size() - 1, index));
        scrollTo(selected);
    }

    /**
     * Moves the selection one place, as {@code Shift+↑} does.
     *
     * <p>Public so a screen can put a button on it. Dragging and the keyboard are two paths, and a
     * third is not redundant: someone who uses a mouse but cannot hold a button down while moving it
     * has neither of the other two.
     */
    public void moveSelection(int delta) {
        if (orderable) {
            move(delta);
        }
    }

    /** Sends the selection to the paired list, as {@code Enter} does. */
    public void transferSelection() {
        sendToPeer();
    }

    private void move(int delta) {
        int target = selected + delta;
        if (selected < 0 || target < 0 || target >= items.size()) {
            return;
        }
        items.add(target, items.remove(selected));
        selected = target;
        scrollTo(selected);
        onChange.run();
    }

    /** Sends the selection across - which is "place it" from a palette and "remove it" from a layout. */
    private void sendToPeer() {
        T item = selection();
        if (item == null || peer == null) {
            return;
        }
        if (!(copySource && adapter.repeatable(item))) {
            items.remove(selected);
        }
        peer.items.add(item);
        peer.selected = peer.items.size() - 1;
        peer.clampScroll();
        if (selected >= items.size()) {
            selected = items.size() - 1;
        }
        clampScroll();
        onChange.run();
    }

    // ------------------------------------------------------------------ geometry

    /** The row index under {@code mouseY}, or {@code -1} past the end of the entries. */
    private int rowAt(double mouseY) {
        int relative = (int) (mouseY - y - ROW_GAP + scroll);
        if (relative < 0) {
            return -1;
        }
        int index = relative / (ROW_HEIGHT + ROW_GAP);
        return index < items.size() ? index : -1;
    }

    /** Where a drop at {@code mouseY} would insert - the boundary nearest the cursor, not a row. */
    private int insertionRow(double mouseY) {
        int relative = (int) (mouseY - y - ROW_GAP + scroll);
        int index = Math.round(relative / (float) (ROW_HEIGHT + ROW_GAP));
        return Math.max(0, Math.min(items.size(), index));
    }

    /** The insertion point to draw right now, or {@code -1} when no drag is over this list. */
    private int insertionIndex(int mouseX, int mouseY) {
        if (active == null || !orderable || !contains(mouseX, mouseY)) {
            return -1;
        }
        return active.source() == this || active.source().peer == this ? insertionRow(mouseY) : -1;
    }

    private int rowWidth() {
        return width - (maxScroll() > 0 ? SCROLLBAR_WIDTH + 4 : 0);
    }

    private int contentHeight() {
        return items.isEmpty() ? 0 : items.size() * (ROW_HEIGHT + ROW_GAP) + ROW_GAP;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - height);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private void scrollTo(int index) {
        int top = index * (ROW_HEIGHT + ROW_GAP);
        int bottom = top + ROW_HEIGHT + ROW_GAP;
        if (top < scroll) {
            scroll = top;
        } else if (bottom > scroll + height) {
            scroll = bottom - height;
        }
        clampScroll();
    }

    /** {@code text} shortened with an ellipsis so it fits {@code room} pixels. */
    private static String ellipsise(Font font, String text, int room) {
        if (room <= 0 || font.width(text) <= room) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "…") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
