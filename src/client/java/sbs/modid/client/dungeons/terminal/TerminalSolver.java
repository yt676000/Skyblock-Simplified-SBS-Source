/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.dungeons.terminal.TerminalSolutions.Hint;

import java.util.Map;

/**
 * Highlights the solution of the F7/M7 phase-3 terminals - <b>display only, it never clicks</b>. Reads
 * the open terminal menu each client tick (read-only, never in render), works out which slots are the
 * next correct action, and marks them; the player still clicks themselves.
 *
 * <p>All six terminals of the Goldor gates are covered - see {@link TerminalType} for the list and
 * {@link TerminalSolutions} for how each is solved. The marks are:
 *
 * <ul>
 *   <li><b>green outline</b> - click this slot,</li>
 *   <li><b>thin dark-green outline</b> - the click after this one (ordered terminal), there to aim at
 *       and never clickable,</li>
 *   <li><b>orange outline with a negative number</b> - right-click it that many times (rubix, where
 *       the cycle is shorter backwards),</li>
 *   <li><b>blue outline</b> - melody's button for the row, waiting for its note; it turns green with
 *       a "!" for the moment the note stands on the marked column.</li>
 * </ul>
 *
 * <p>With <i>Block Wrong Clicks</i> on, a click on a slot that is not part of the solution is vetoed
 * before it becomes a packet ({@link #handleClick}) - the guard only ever <i>refuses</i> an action,
 * it never performs one, and it stands down for melody and for any title we are only guessing at.
 *
 * <p>Opening a terminal logs its title and every slot once ({@code [SBS][Terminal] ...}), so a board
 * that is read wrong can be diagnosed from the instance log rather than from memory.
 */
public final class TerminalSolver {

    private static final TerminalSolver INSTANCE = new TerminalSolver();

    private volatile TerminalType type = TerminalType.NONE;
    private volatile Map<Integer, Hint> hints = Map.of();
    private Object lastScreen;

    private TerminalSolver() {
    }

    public static TerminalSolver getInstance() {
        return INSTANCE;
    }

    /** The terminal currently open, or {@link TerminalType#NONE}. */
    public TerminalType type() {
        return type;
    }

    /** The slots to act on, as of the last tick. Never null, possibly empty. */
    public Map<Integer, Hint> hints() {
        return hints;
    }

    /**
     * Whether this screen is the one the last tick scan read. The board overlay asks before it draws
     * or forwards a click: the hints belong to a screen, and a screen swapped between tick and frame
     * would otherwise be painted with the previous menu's solution.
     */
    public boolean isTracking(Screen screen) {
        return screen != null && screen == lastScreen;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ------------------------------------------------------------------
    // Tick scan
    // ------------------------------------------------------------------

    /**
     * The scan runs for the timer too, not only for the marks: "how long did that terminal take" is
     * measured from the moment it opens, and that moment is only known here. Switching Terminal Times
     * on without the solver would otherwise silently measure nothing.
     */
    public void tick(Minecraft minecraft) {
        if (minecraft == null || (!cfg().terminalSolver && !cfg().terminalTimes)) {
            clear();
            return;
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (screen != lastScreen) {
            lastScreen = screen;
            hints = Map.of();
            type = TerminalType.NONE;
            dumpOnOpen(screen);
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !DungeonStateManager.getInstance().inDungeon()) {
            type = TerminalType.NONE;
            hints = Map.of();
            return;
        }
        String title = titleOf(screen);
        TerminalType found = TerminalType.classify(title);
        type = found;
        if (found == TerminalType.NONE) {
            hints = Map.of();
            return;
        }
        // The clock for "how long did that take" starts here rather than on the chat line that ends
        // it: opening the terminal is the moment the player starts solving it.
        TerminalCompletion.getInstance().onTerminalVisible(screen, found);
        if (!cfg().terminalSolver) {
            hints = Map.of(); // timing only: nothing is marked and nothing is guarded
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        hints = TerminalSolutions.solve(found, title, menu, upperSlots(menu));
    }

    private void clear() {
        type = TerminalType.NONE;
        hints = Map.of();
        lastScreen = null;
    }

    /**
     * One-shot log of the whole terminal on open, so a mis-read board can be diagnosed after a run.
     * A title that <i>looks</i> like a terminal prompt but does not classify is logged too - that is
     * the case worth having in the log, since it is the one where nothing gets highlighted.
     */
    private void dumpOnOpen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = titleOf(screen);
        String norm = title.toLowerCase(java.util.Locale.ROOT);
        boolean looksLikeTerminal = norm.contains("click") || norm.contains("select all")
                || norm.contains("starts with") || norm.contains("change all") || norm.contains("correct all");
        if (TerminalType.classify(title) == TerminalType.NONE && !looksLikeTerminal) {
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        StringBuilder slots = new StringBuilder();
        int upper = upperSlots(menu);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = TerminalSolutions.item(menu, i);
            if (stack.isEmpty()) {
                continue;
            }
            slots.append(i).append(':').append(TerminalSolutions.idPath(stack)).append('x')
                    .append(stack.getCount()).append(stack.hasFoil() ? "*" : "").append(' ');
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Terminal] open title=\"{}\" -> {} | {}",
                title, TerminalType.classify(title), slots.toString().trim());
    }

    // ------------------------------------------------------------------
    // Misclick guard
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} to CANCEL a click that the solution does not contain, so it never becomes
     * a packet. Only the terminals where a wrong click is unambiguously a mistake are guarded
     * ({@link TerminalType#guardsMisclicks()}), and only while the board actually solved into
     * something; the player's own inventory is never touched.
     *
     * <p>A {@link Hint#preview()} mark counts as "not in the solution": the ordered terminal points at
     * the number after the current one so the mouse can set off early, and clicking that one early is
     * the exact mistake being guarded against.
     */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        // The custom board covers the screen and forwards its own cells to the real slots, so the
        // vanilla hovered slot under the cursor means nothing here - the board applies the guard.
        if (screen != lastScreen || TerminalBoardOverlay.getInstance().isActive(screen)) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Slot slot = ((AbstractContainerScreenAccessor) screen).skyblockSimplified$hoveredSlot();
        if (slot == null) {
            return false;
        }
        int index = menu.slots.indexOf(slot);
        if (index < 0 || index >= upperSlots(menu)) {
            return false; // player inventory / outside the board: always free
        }
        return blocks(index);
    }

    /**
     * Whether the misclick guard refuses a click on this board slot right now - the single rule both
     * the vanilla-slot guard above and the custom board's own click forwarding ask, so the two can
     * never drift into disagreeing about what is allowed.
     */
    public boolean blocks(int slotIndex) {
        if (!cfg().terminalSolver || !cfg().terminalBlockWrongClicks) {
            return false;
        }
        Map<Integer, Hint> solution = hints;
        if (!type.guardsMisclicks() || solution.isEmpty()) {
            return false;
        }
        Hint hint = solution.get(slotIndex);
        return hint == null || hint.preview();
    }

    // ------------------------------------------------------------------
    // Render (from OverlayRenderMixin, slot-relative like ExperimentationTable)
    // ------------------------------------------------------------------

    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Map<Integer, Hint> solution = hints;
        if (!cfg().terminalSolver || type == TerminalType.NONE || solution.isEmpty()
                || TerminalBoardOverlay.getInstance().isActive(screen)) {
            return; // the board draws its own marks, at its own size
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        Font font = Minecraft.getInstance().font;
        for (Map.Entry<Integer, Hint> entry : solution.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= slotCount) {
                continue;
            }
            Slot slot = menu.getSlot(index);
            int x = left + slot.x;
            int y = top + slot.y;
            Hint hint = entry.getValue();
            // A look-ahead gets the thin ring: it has to be findable at a glance without ever being
            // mistaken for the slot that is actually due.
            if (hint.preview()) {
                thinOutline(g, x, y, hint.color());
            } else {
                outline(g, x, y, hint.color());
            }
            String label = hint.label();
            if (label != null && !label.isEmpty()) {
                g.text(font, Component.literal(label), x + 17 - font.width(label), y + 9,
                        hint.color());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Slots belonging to the terminal itself: everything above the 36 player-inventory slots. */
    private static int upperSlots(AbstractContainerMenu menu) {
        return Math.max(0, menu.getItems().size() - 36);
    }

    private static String titleOf(Screen screen) {
        return screen == null || screen.getTitle() == null ? ""
                : screen.getTitle().getString().replaceAll("§.", "").trim();
    }

    /** 2px accent outline hugging a 16px slot (same as ExperimentationTable's). */
    static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 17, y + 1, color);
        g.fill(x - 1, y + 15, x + 17, y + 17, color);
        g.fill(x - 1, y - 1, x + 1, y + 17, color);
        g.fill(x + 15, y - 1, x + 17, y + 17, color);
    }

    /** The same ring at 1px, for a mark that must be seen but not acted on. */
    static void thinOutline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y, x + 16, y + 1, color);
        g.fill(x, y + 15, x + 16, y + 16, color);
        g.fill(x, y, x + 1, y + 16, color);
        g.fill(x + 15, y, x + 16, y + 16, color);
    }
}
