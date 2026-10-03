/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.ui.component.KeyCaptureWidget;
import sbs.modid.client.ui.component.SciFiDropdown;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.font.SbsFonts;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrolling column of {@link SettingRow}s, with the parts that are easy to get wrong already done.
 *
 * <p><b>Why this is shared rather than copied.</b> Rendering a list of rows looks like the twenty
 * lines in {@code buildSettingsWidgets} and is not: an open {@link SciFiDropdown} draws its option
 * list outside its own rectangle, so it has to be drawn after every other widget, offered the click
 * before the widgets underneath it, and offered the wheel before the rows behind it - three separate
 * places, each of which has been shipped broken at least once in this codebase. A second screen that
 * re-implemented the loop would re-implement the loop and not the three fixes, and the failure is
 * silent: a pick lands on the row painted underneath and changes a setting the player cannot see.
 * See the "controls that draw outside their own box" checklist in {@code ui/AGENTS.md}.
 *
 * <p><b>What the host still owns.</b> Anything that is not a row: the config screen's favourite stars
 * and jump highlight, a wizard page's heading and prose. The host also decides <i>which</i> rows to
 * show and whether tooltips are wanted - {@link #renderTooltip} draws one when asked and does not
 * consult any setting itself, because "does this screen show hover help" is a question about the
 * screen and not about the list.
 *
 * <p><b>Widget ownership.</b> Widgets are created and destroyed through the {@link WidgetSink} the
 * host supplies, because {@code Screen.addRenderableWidget} is protected and a component outside the
 * screen cannot call it. The host passes its own method references; this class never keeps a
 * reference to the screen.
 */
public final class SettingRowList {

    /** How the host registers and unregisters the widgets this list creates. */
    public interface WidgetSink {
        void add(AbstractWidget widget);

        void remove(AbstractWidget widget);
    }

    /** Max width of a row tooltip before its description wraps. */
    private static final int TOOLTIP_WIDTH = 210;

    /** Strip reserved on the right of the rows for the scrollbar, when one is needed. */
    private static final int BAR_GUTTER = SciFiScrollbar.WIDTH + 3;

    private final WidgetSink sink;
    private final List<AbstractWidget> widgets = new ArrayList<>();

    /** The list's scrollbar - the mod's shared one, so it drags exactly like the module sidebar. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    private List<SettingRow> rows = List.of();
    private int x;
    private int top;
    private int width;
    private int bottom;
    /** Width kept clear on the right of every interactive row - the config screen's star strip. */
    private int rightGutter;
    private int scroll;

    public SettingRowList(WidgetSink sink) {
        this.sink = sink;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * Sets the content box. Call from the host's {@code init} <b>before</b> {@link #rebuild}, and
     * re-call on any resize - the visible row count is derived from this box, never hardcoded.
     */
    public void setBounds(int x, int top, int width, int bottom) {
        this.x = x;
        this.top = top;
        this.width = width;
        this.bottom = bottom;
    }

    /**
     * Reserves a strip on the right of interactive rows for something the host draws there. Label
     * rows keep the full width - there is nothing to pin beside an explanation.
     */
    public void setRightGutter(int rightGutter) {
        this.rightGutter = Math.max(0, rightGutter);
    }

    public int rowStep() {
        return SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
    }

    /** How many rows fit in the current box. At least one, so a tiny viewport still shows something. */
    public int maxVisible() {
        return Math.max(1, (bottom - top + SBSTheme.ENTRY_SPACING) / rowStep());
    }

    /** The rows currently backing this list - the whole list, not only the visible window. */
    public List<SettingRow> rows() {
        return rows;
    }

    public int scroll() {
        return scroll;
    }

    /** Scrolls to an absolute offset. Clamped on the next {@link #rebuild}. */
    public void setScroll(int scroll) {
        this.scroll = Math.max(0, scroll);
    }

    /** The y of a row by index, or {@code -1} when it is scrolled out of view. */
    public int yOf(int index) {
        int offset = index - scroll;
        return offset < 0 || offset >= maxVisible() ? -1 : top + offset * rowStep();
    }

    /** Whether a point is inside the content box at all. */
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= top && mouseY <= bottom;
    }

    /**
     * The row index under a point, or {@code -1} for the gaps between rows and anything outside the
     * box. The gap check is what stops a tooltip appearing while the cursor sits between two rows.
     */
    public int indexAt(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return -1;
        }
        if ((mouseY - top) % rowStep() > SBSTheme.ENTRY_HEIGHT) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - top) / rowStep());
        return index < 0 || index >= rows.size() ? -1 : index;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * Replaces the rows and rebuilds the visible widgets. Safe to call on every scroll, filter change
     * or selection change - it removes what it made last time before making anything new.
     *
     * <p>Rebuilding on a keystroke is only safe because no row this creates holds keyboard focus
     * across the call. A text field's responder must never reach this: it would destroy the field
     * being typed in, mid-word, taking the focus with it. See {@code ui/AGENTS.md}.
     */
    public void rebuild(List<SettingRow> rows) {
        this.rows = rows == null ? List.of() : rows;
        rebuild();
    }

    /** Rebuilds the visible widgets from the rows already set. */
    public void rebuild() {
        for (AbstractWidget widget : widgets) {
            sink.remove(widget);
        }
        widgets.clear();

        int visible = maxVisible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));

        int y = top;
        for (int i = scroll; i < rows.size() && i < scroll + visible; i++) {
            SettingRow row = rows.get(i);
            int rowWidth = rowWidth(row);
            AbstractWidget widget = row.create(x, y, rowWidth, SBSTheme.ENTRY_HEIGHT);
            if (widget instanceof SciFiDropdown dropdown) {
                // Only one option list may be open at a time: two overlapping popups are unreadable,
                // and the click routing below could not tell which list an option belonged to.
                dropdown.setOnOpen(() -> closeOtherDropdowns(dropdown));
            }
            sink.add(widget);
            widgets.add(widget);
            y += rowStep();
        }
    }

    /**
     * How wide one row is drawn.
     *
     * <p>A label keeps the full width - there is nothing pinned beside an explanation - and everything
     * else gives up the host's gutter plus, when the list actually scrolls, the strip the bar occupies.
     * Reserving it rather than drawing over the row matters at the right-hand edge, where the value box
     * and the {@code ON}/{@code OFF} pill live: a bar painted on top of those covers the one thing the
     * row exists to say.
     *
     * <p>Whether the bar is needed depends on the row <i>count</i>, never on the width, so this cannot
     * feed back on itself - no width at which reserving the strip removes the reason for it.
     */
    private int rowWidth(SettingRow row) {
        if (row.isLabel()) {
            return width;
        }
        return width - rightGutter - (barNeeded() ? BAR_GUTTER : 0);
    }

    /** Whether there are more rows than fit. */
    private boolean barNeeded() {
        return rows.size() > maxVisible();
    }

    /** Feeds the shared scrollbar the current track box; call before rendering or hit-testing it. */
    private void syncBar() {
        bar.set(x + width - rightGutter - SciFiScrollbar.WIDTH, top, bottom - top,
                rows.size(), maxVisible());
    }

    /** Drops every widget without building replacements - for a host tearing its screen down. */
    public void clear() {
        for (AbstractWidget widget : widgets) {
            sink.remove(widget);
        }
        widgets.clear();
    }

    // ------------------------------------------------------------------
    // Dropdowns: the three places an out-of-bounds popup has to be handled
    // ------------------------------------------------------------------

    /** True while any row's option list is open. */
    public boolean dropdownOpen() {
        for (AbstractWidget widget : widgets) {
            if (widget instanceof SciFiDropdown dropdown && dropdown.isOpen()) {
                return true;
            }
        }
        return false;
    }

    private void closeOtherDropdowns(SciFiDropdown keep) {
        for (AbstractWidget widget : widgets) {
            if (widget instanceof SciFiDropdown dropdown && dropdown != keep) {
                dropdown.close();
            }
        }
    }

    /**
     * Paints the licence mark over every visible row that needs a token: the translucent red fill
     * and frame an undercut Bazaar order wears, so the two mean the same thing wherever you meet
     * them - "this one is not working for you right now".
     *
     * <p>Drawn over the finished row rather than by the widgets themselves, so it covers every row
     * type at once and a new widget cannot forget to wear it. The fill is the Bazaar's own 0x55
     * alpha, which tints the card without hiding the label or the control underneath.
     *
     * <p>Like the dropdown overlay, this must be called from the host's own render pass rather than
     * registered once at init - a rebuild appends new widgets after anything init registered.
     */
    public void renderLicenceMarks(GuiGraphicsExtractor g) {
        if (!sbs.modid.client.core.licence.LicenceMarks.marking()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            SettingRow row = rows.get(i);
            if (!row.isLicenced()) {
                continue;
            }
            int y = yOf(i);
            if (y < 0) {
                continue;   // scrolled out of view
            }
            SciFiRender.roundedRectWithBorder(g, x, y, rowWidth(row), SBSTheme.ENTRY_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.BAZAAR_OUTDATED_FILL,
                    SBSTheme.BAZAAR_OUTDATED_FRAME);
        }
    }

    /**
     * Draws the scrollbar, when the list has more rows than fit.
     *
     * <p>Called by the host from its own render pass for the same reason the licence marks and the
     * dropdown overlay are: a pass registered once at init is last only until the first rebuild.
     */
    public void renderScrollbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        syncBar();
        bar.render(g, scroll, mouseX, mouseY);
    }

    /**
     * Draws every open option list. The host must call this <b>after</b> {@code super}, from its own
     * {@code extractRenderState} override - not from a pass registered in {@code init}. Renderables
     * draw in insertion order and a rebuild appends the new rows after whatever {@code init}
     * registered, so a pass added once at init is last only until the first rebuild.
     */
    public void renderDropdownOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        for (AbstractWidget widget : widgets) {
            if (widget instanceof SciFiDropdown dropdown) {
                dropdown.renderOverlay(g, mouseX, mouseY);
            }
        }
    }

    /**
     * Offers a click to an open option list first. The host calls this before {@code super}, or the
     * click lands on the row painted underneath the list and changes a setting nobody can see.
     *
     * @return true when the click was consumed and the host should stop
     */
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        KeyCaptureWidget.cancelOnOutsideClick(widgets, event.x(), event.y());
        for (AbstractWidget widget : List.copyOf(widgets)) {
            if (widget instanceof SciFiDropdown dropdown && dropdown.isOpen()
                    && dropdown.mouseClicked(event, doubled)) {
                return true;
            }
        }
        // After the option lists, which are drawn over the bar, and before the host's own rows.
        syncBar();
        return bar.handleClick(event.x(), event.y(), scroll, this::scrollTo);
    }

    /**
     * Offers a drag to the scrollbar.
     *
     * @return true when the thumb is being dragged and the host should stop
     */
    public boolean mouseDragged(double mouseY) {
        syncBar();
        return bar.handleDrag(mouseY, this::scrollTo);
    }

    /**
     * Ends a scrollbar drag.
     *
     * @return true when one was in progress and the host should stop
     */
    public boolean mouseReleased() {
        return bar.release();
    }

    /** Scrolls to an offset and moves the widgets to match - what the bar drives. */
    private void scrollTo(int offset) {
        if (offset != scroll) {
            scroll = offset;
            rebuild();
        }
    }

    /**
     * Routes the wheel: to a listening keybind row first, then to an open option list, else to this
     * list's own scrolling when the cursor is over it. Scrolling the rows while a list is open would
     * move the list itself out from under the cursor.
     *
     * @return true when the wheel was consumed and the host should stop
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        // A keybind row waiting for its input owns the wheel wherever the cursor is - the player is
        // binding Wheel Up, not scrolling the settings. Asked before anything else for the same
        // reason an open dropdown is: it is the innermost thing the player is interacting with.
        if (KeyCaptureWidget.claimScroll(widgets, scrollY)) {
            return true;
        }
        for (AbstractWidget widget : widgets) {
            if (widget instanceof SciFiDropdown dropdown && dropdown.isOpen()) {
                dropdown.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
                return true;
            }
        }
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        rebuild();
        return true;
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    /**
     * Draws the hovered row's description, when it has one. The host decides whether to call this at
     * all; this class holds no opinion about whether hover help is wanted on a given screen.
     *
     * <p>Suppressed while an option list is open: a tooltip is drawn in a stratum above even the
     * overlay pass, so the tooltip of the row underneath the list would cover the very options being
     * read. The list is what the player is looking at, so the list wins.
     */
    public void renderTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (dropdownOpen()) {
            return;
        }
        int index = indexAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        SettingRow row = rows.get(index);
        if (row.isLabel()) {
            return;   // a plain explanation row is not itself a setting
        }
        // Only the row's own explicit description. Deriving one from the label rows underneath was
        // tried and dropped: a label often introduces the NEXT setting, so the borrowed text
        // described the wrong thing - worse than no tooltip at all.
        String description = row.description();
        boolean licenceMark = sbs.modid.client.core.licence.LicenceMarks.marked(row);
        boolean devMark = row.isInDevelopment();
        if ((description == null || description.isBlank()) && !licenceMark && !devMark) {
            return;
        }
        var font = SbsFonts.ui();
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.literal(row.label())
                .withColor(SBSTheme.ACCENT_BRIGHT & 0xFFFFFF).getVisualOrderText());
        if (description != null && !description.isBlank()) {
            lines.addAll(font.split(
                    Component.literal(description).withColor(SBSTheme.TEXT_MUTED & 0xFFFFFF), TOOLTIP_WIDTH));
        }
        // What the red actually means, on the row wearing it. A colour nobody can look up is a
        // colour that gets ignored, and this one is claiming the feature does not work for you.
        if (licenceMark) {
            String note = row.licenceNote();
            String text = note.isBlank()
                    ? "Needs a licence token. Without one this does nothing."
                    : "Needs a licence token. Without one: " + note;
            lines.addAll(font.split(Component.literal(text)
                    .withColor(SBSTheme.BAZAAR_OUTDATED_FRAME & 0xFFFFFF), TOOLTIP_WIDTH));
        }
        // Last, and unconditional: this one does not depend on a token, a setting or the licence
        // marks being switched on. The row is where the feature is decided on, so the warning has to
        // be readable here and not only on the screen it opens.
        if (devMark) {
            lines.addAll(font.split(
                    Component.literal(sbs.modid.client.ui.render.DevNotice.settingsNote())
                            .withColor(SBSTheme.WARN & 0xFFFFFF), TOOLTIP_WIDTH));
        }
        g.setTooltipForNextFrame(lines, mouseX, mouseY);
    }
}
