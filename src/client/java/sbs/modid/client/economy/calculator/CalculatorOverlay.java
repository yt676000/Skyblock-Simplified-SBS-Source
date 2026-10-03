/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.calculator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.window.EdgeSnap;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.ui.window.WindowResizer;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.ui.RecipeOverlay;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;
import sbs.modid.client.core.util.MathEval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game calculator: a floating window over container screens, in the same mould as the
 * windowed Recipe Viewer - movable, Ctrl+scroll-resizable, part of the {@code FloatingWindows}
 * z-order, and never closed but {@linkplain #minimized minimized} to a small button beside the
 * search bar.
 *
 * <p>Three stacked parts: the <b>history</b> of past calculations (scrollable, and each line clicks
 * its result back into the input), the <b>display</b> with the typed expression and its live value,
 * and the <b>keypad</b>. Arithmetic is {@link MathEval}, the same evaluator the search bars use, so
 * SkyBlock's {@code k}/{@code m}/{@code b}/{@code t} suffixes work here too ({@code 64*1.2m}).
 *
 * <p>Typing goes through the window only while it holds focus ({@link #updateFocus}), and every key
 * is then swallowed - otherwise 'e' would close the inventory mid-expression.
 */
public final class CalculatorOverlay {

    private static final CalculatorOverlay INSTANCE = new CalculatorOverlay();

    // GLFW key codes (same set the container search bar uses).
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    /** Everything the expression grammar can consume - anything else is ignored while typing. */
    private static final String TYPEABLE = "0123456789+-*/%^().,kmbt";

    private static final int WIN_HEADER = 16;
    private static final int WIN_PAD = 6;
    private static final int WIN_MARGIN = 2;
    private static final int WIN_MIN_W = 118;
    private static final int WIN_MAX_W = 420;
    private static final int WIN_MIN_H = 150;
    private static final int WIN_MAX_H = 460;

    private static final int KEY_H = 14;
    private static final int KEY_GAP = 2;
    private static final int BTN = 14;

    /** The keypad, row by row. {@code <} is backspace, {@code C} clears, {@code =} evaluates. */
    private static final String[][] KEYS = {
            {"C", "(", ")", "<"},
            {"7", "8", "9", "/"},
            {"4", "5", "6", "*"},
            {"1", "2", "3", "-"},
            {"0", ".", "=", "+"},
            {"k", "m", "b", "^"},
    };

    /** One finished calculation, kept verbatim so the history reads like what was typed. */
    private record Entry(String expression, double value) {
    }

    private final List<Entry> history = new ArrayList<>();
    private String input = "";

    /** Rows the history is scrolled up from its newest end (0 = the latest result is visible). */
    private int historyScroll;

    private int winX = Integer.MIN_VALUE;
    private int winY = Integer.MIN_VALUE;
    private int winW = 158;
    private int winH = 216;
    private boolean minimized;
    private boolean focused;
    private boolean draggingWindow;
    private double grabDX;
    private double grabDY;

    private final WindowResizer windowResizer = new WindowResizer();

    /** Where the player left the window: position, size and whether it was collapsed. */
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.CALCULATOR);

    private CalculatorOverlay() {
    }

    public static CalculatorOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.CalculatorSettings cfg() {
        return ConfigManager.getInstance().get().calculator;
    }

    public boolean active() {
        return cfg().enabled;
    }

    /** Clears the history - the module's "Clear History" button. */
    public void clearHistory() {
        history.clear();
        historyScroll = 0;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private int[] windowRect(Screen screen) {
        // Every path into the window's geometry comes through here, so this is where the remembered
        // spot is adopted - before the "never placed" default below can claim it.
        restoreRemembered();
        winW = clamp(winW, WIN_MIN_W, Math.max(WIN_MIN_W, screen.width - WIN_MARGIN * 2));
        winH = clamp(winH, WIN_MIN_H, Math.max(WIN_MIN_H, screen.height - WIN_MARGIN * 2));
        if (winX == Integer.MIN_VALUE) {
            // Default spot: left edge, clear of the Recipe Viewer's default place on the right.
            winX = 8;
            winY = 18;
        }
        winX = clamp(winX, WIN_MARGIN, screen.width - winW - WIN_MARGIN);
        winY = clamp(winY, WIN_MARGIN, screen.height - winH - WIN_MARGIN);
        return new int[]{winX, winY, winW, winH};
    }

    private boolean inWindow(Screen screen, double mx, double my) {
        int[] r = windowRect(screen);
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private int minimizeX(Screen screen) {
        int[] r = windowRect(screen);
        return r[0] + r[2] - 14;
    }

    private boolean inMinimizeBox(Screen screen, double mx, double my) {
        int[] r = windowRect(screen);
        return mx >= minimizeX(screen) && mx < minimizeX(screen) + 12
                && my >= r[1] + 2 && my < r[1] + WIN_HEADER;
    }

    /**
     * The reopen button sits <b>left</b> of the search bar - the Recipe Viewer's own reopen button
     * takes the right side, and two minimized windows must not stack on the same spot.
     */
    private int[] reopenRect(Screen screen) {
        RecipeOverlay recipe = RecipeOverlay.getInstance();
        return new int[]{recipe.barX(screen) - BTN - 4, recipe.barY(screen), BTN, BTN};
    }

    private boolean inReopenButton(Screen screen, double mx, double my) {
        int[] b = reopenRect(screen);
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    /**
     * The content layout inside the window, as
     * {@code {contentX, contentW, historyTop, historyBottom, displayTop, displayH, keypadTop, keyW}}.
     * Everything is derived from the live window rect, so a resize needs no extra bookkeeping.
     */
    private int[] layout(Screen screen, Font font) {
        int[] r = windowRect(screen);
        int cx = r[0] + WIN_PAD;
        int cw = r[2] - WIN_PAD * 2;
        int top = r[1] + WIN_HEADER + 3;
        int bottom = r[1] + r[3] - WIN_PAD;
        int keyW = (cw - KEY_GAP * 3) / 4;
        int keypadH = cfg().keypad ? KEYS.length * (KEY_H + KEY_GAP) - KEY_GAP : 0;
        int keypadTop = bottom - keypadH;
        int displayH = font.lineHeight * 2 + 7;
        int displayTop = keypadTop - (cfg().keypad ? 4 : 0) - displayH;
        return new int[]{cx, cw, top, Math.max(top, displayTop - 3), displayTop, displayH, keypadTop, keyW};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                        com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    }

    /** Whether the window covers this point - the tooltip suppressor asks before drawing. */
    public boolean coversForTooltip(AbstractContainerScreen<?> screen, double mx, double my) {
        if (!active()) {
            return false;
        }
        return minimized ? inReopenButton(screen, mx, my) : inWindow(screen, mx, my);
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    private void append(String text) {
        if (input.length() < 64) {
            input += text;
        }
    }

    private void backspace() {
        if (!input.isEmpty()) {
            input = input.substring(0, input.length() - 1);
        }
    }

    /**
     * Finishes the current expression: it lands in the history and its value becomes the new input,
     * so the next operator just carries on from the result.
     */
    private void evaluate() {
        Double value = MathEval.eval(input);
        if (value == null) {
            return;   // half-typed expression - nothing to record, nothing to complain about
        }
        history.add(new Entry(input.trim(), value));
        int limit = Math.max(1, cfg().historySize);
        while (history.size() > limit) {
            history.remove(0);
        }
        historyScroll = 0;
        input = MathEval.toInput(value);
    }

    private void press(String key) {
        switch (key) {
            case "C" -> input = "";
            case "<" -> backspace();
            case "=" -> evaluate();
            default -> append(key);
        }
    }

    // ------------------------------------------------------------------
    // Rendering (FloatingWindows z-order pass)
    // ------------------------------------------------------------------

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!active()) {
            return;
        }
        // Before the collapsed branch: a window remembered as collapsed must not flash open for a
        // frame, and while collapsed nothing else here reaches the geometry that would restore it.
        restoreRemembered();
        if (minimized) {
            drawReopenButton(screen, g, mouseX, mouseY);
            return;
        }
        Font font = Minecraft.getInstance().font;
        drawChrome(screen, g, font, mouseX, mouseY);
        int[] l = layout(screen, font);
        drawHistory(g, font, l, mouseX, mouseY);
        drawDisplay(g, font, l);
        if (cfg().keypad) {
            drawKeypad(g, font, l, mouseX, mouseY);
        }
    }

    private void drawChrome(Screen screen, GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int[] r = windowRect(screen);
        windowResizer.renderGrips(g, r[0], r[1], r[2], r[3], mouseX, mouseY);
        SciFiRender.glow(g, r[0], r[1], r[2], r[3], SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, r[0], r[1], r[2], r[3], SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, r[0] + 1, r[1] + 1, r[2] - 2, r[3] - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = r[1] + (WIN_HEADER - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("Calculator"), r[0] + WIN_PAD, textY,
                focused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
        boolean minimizeHover = inMinimizeBox(screen, mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeX(screen) + 4, textY,
                minimizeHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(r[0] + WIN_PAD, r[1] + WIN_HEADER, r[0] + r[2] - WIN_PAD, r[1] + WIN_HEADER + 1,
                SBSTheme.ACCENT_SOFT);
    }

    /** Past calculations, oldest at the top so the newest sits right above the display. */
    private void drawHistory(GuiGraphicsExtractor g, Font font, int[] l, int mouseX, int mouseY) {
        int rowH = font.lineHeight + 2;
        int visible = Math.max(0, (l[3] - l[2]) / rowH);
        if (visible == 0) {
            return;
        }
        if (history.isEmpty()) {
            g.text(font, Component.literal("No calculations yet."), l[0], l[2], SBSTheme.TEXT_MUTED);
            return;
        }
        historyScroll = clamp(historyScroll, 0, Math.max(0, history.size() - visible));
        int start = Math.max(0, history.size() - visible - historyScroll);
        int end = Math.min(history.size(), start + visible);
        int y = l[3] - (end - start) * rowH;
        for (int i = start; i < end; i++) {
            Entry entry = history.get(i);
            boolean hover = mouseX >= l[0] && mouseX < l[0] + l[1] && mouseY >= y && mouseY < y + rowH;
            if (hover) {
                SciFiRender.roundedRect(g, l[0] - 2, y - 1, l[1] + 4, rowH,
                        SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG_HOVER);
            }
            String result = MathEval.format(entry.value());
            // The expression gives way first: the result is what a click on this row inserts.
            String expression = font.plainSubstrByWidth(
                    entry.expression(), Math.max(0, l[1] - font.width(result) - 8), false);
            g.text(font, Component.literal(expression), l[0], y, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(result), l[0] + l[1] - font.width(result), y,
                    hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            y += rowH;
        }
    }

    /** The typed expression with its live value underneath. */
    private void drawDisplay(GuiGraphicsExtractor g, Font font, int[] l) {
        SciFiRender.roundedRectWithBorder(g, l[0], l[4], l[1], l[5], SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, focused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        int textY = l[4] + 3;
        String shown = input.isEmpty() ? "0" : font.plainSubstrByWidth(input, l[1] - 8, true);
        int color = input.isEmpty() ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT;
        g.text(font, Component.literal(shown), l[0] + l[1] - 4 - font.width(shown), textY, color);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = l[0] + l[1] - 3;
            g.fill(caretX, textY - 1, caretX + 1, textY + font.lineHeight, SBSTheme.ACCENT_BRIGHT);
        }

        Double value = MathEval.eval(input);
        String result = value == null ? "" : "= " + MathEval.format(value);
        g.text(font, Component.literal(result), l[0] + l[1] - 4 - font.width(result),
                textY + font.lineHeight + 1, SBSTheme.ACCENT_BRIGHT);
    }

    private void drawKeypad(GuiGraphicsExtractor g, Font font, int[] l, int mouseX, int mouseY) {
        for (int row = 0; row < KEYS.length; row++) {
            for (int col = 0; col < KEYS[row].length; col++) {
                int x = l[0] + col * (l[7] + KEY_GAP);
                int y = l[6] + row * (KEY_H + KEY_GAP);
                String key = KEYS[row][col];
                boolean hover = mouseX >= x && mouseX < x + l[7] && mouseY >= y && mouseY < y + KEY_H;
                boolean accent = key.equals("=");
                SciFiRender.roundedRectWithBorder(g, x, y, l[7], KEY_H, SBSTheme.CORNER_RADIUS,
                        hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        hover || accent ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                String label = key.equals("<") ? "←" : key;
                g.centeredText(font, Component.literal(label), x + l[7] / 2,
                        y + (KEY_H - font.lineHeight) / 2 + 1,
                        accent ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            }
        }
    }

    /** Small keypad-glyph button beside the search bar that restores the minimized window. */
    private void drawReopenButton(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] b = reopenRect(screen);
        boolean hover = inReopenButton(screen, mouseX, mouseY);
        SciFiRender.roundedRectWithBorder(g, b[0], b[1], b[2], b[3], SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        // Calculator glyph: a display bar over a 2x2 keypad.
        int color = hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT;
        int gx = b[0] + 4;
        int gy = b[1] + 4;
        g.fill(gx, gy, gx + 6, gy + 2, color);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                g.fill(gx + col * 4, gy + 4 + row * 3, gx + col * 4 + 2, gy + 6 + row * 3, color);
            }
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Re-evaluates keyboard focus on every click anywhere on the screen: the window has it only
     * while it is actually being clicked into. Taking focus releases the container search bar's, so
     * the two text inputs can never both swallow the same keystroke.
     */
    public void updateFocus(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        // The storage workspace is exclusive - while it covers the screen this window is not even
        // drawn, so it must not claim a click landing on where it would have been.
        if (!active() || sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance()
                .isWorkspaceActive(screen)) {
            focused = false;
            return;
        }
        focused = !minimized && inWindow(screen, event.x(), event.y());
        if (focused) {
            SearchHighlightState.getInstance().setSearchFocused(false);
        }
    }

    /** Window input, dispatched through the {@code FloatingWindows} z-order. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active()) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (inReopenButton(screen, mx, my)) {
                minimized = false;
                rememberWindow();
                return true;
            }
            return false;
        }
        if (!inWindow(screen, mx, my)) {
            return false;   // outside: the container stays fully usable
        }
        if (event.button() != 0) {
            return true;    // right-clicks inside the window are swallowed, never passed to a slot
        }
        if (inMinimizeBox(screen, mx, my)) {
            minimized = true;
            rememberWindow();
            return true;
        }
        int[] r = windowRect(screen);
        if (windowResizer.begin(mx, my, r[0], r[1], r[2], r[3])) {
            return true;
        }
        if (my < r[1] + WIN_HEADER) {
            draggingWindow = true;
            grabDX = mx - r[0];
            grabDY = my - r[1];
            return true;
        }
        contentClick(screen, mx, my);
        return true;   // clicks inside the window never reach the container underneath
    }

    /** A click on the keypad or on a history row. */
    private void contentClick(Screen screen, double mx, double my) {
        Font font = Minecraft.getInstance().font;
        int[] l = layout(screen, font);
        if (cfg().keypad && my >= l[6]) {
            int col = (int) ((mx - l[0]) / (l[7] + KEY_GAP));
            int row = (int) ((my - l[6]) / (KEY_H + KEY_GAP));
            if (col >= 0 && col < 4 && row >= 0 && row < KEYS.length) {
                press(KEYS[row][col]);
            }
            return;
        }
        // History row: its result is appended, so a half-typed "5*" can be finished by clicking one.
        if (my >= l[2] && my < l[3] && mx >= l[0] && mx < l[0] + l[1]) {
            int rowH = font.lineHeight + 2;
            int visible = Math.max(0, (l[3] - l[2]) / rowH);
            int start = Math.max(0, history.size() - visible - historyScroll);
            int shown = Math.min(history.size(), start + visible) - start;
            int index = start + (int) ((my - (l[3] - shown * rowH)) / rowH);
            if (index >= start && index < start + shown) {
                append(MathEval.toInput(history.get(index).value()));
            }
        }
    }

    /** Ctrl+scroll resizes the window (window convention); anything else scrolls the history. */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY,
                                double scrollY) {
        if (!active() || scrollY == 0 || minimized || !inWindow(screen, mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            winW = clamp(winW + step * 20, WIN_MIN_W, WIN_MAX_W);
            winH = clamp(winH + step * 20, WIN_MIN_H, WIN_MAX_H);
            rememberWindow();
            return true;
        }
        historyScroll = Math.max(0, historyScroll + (int) Math.signum(scrollY));
        return true;
    }

    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active()) {
            return false;
        }
        if (windowResizer.isActive()) {
            int[] rect = windowResizer.drag(event.x(), event.y(), WIN_MIN_W, WIN_MAX_W, WIN_MIN_H, WIN_MAX_H);
            winX = rect[0];
            winY = rect[1];
            winW = rect[2];
            winH = rect[3];
            windowRect(screen);
            return true;
        }
        if (!draggingWindow) {
            return false;
        }
        winX = (int) (event.x() - grabDX);
        winY = (int) (event.y() - grabDY);
        int[] snapped = EdgeSnap.toBorders(winX, winY, winW, winH, screen.width, screen.height, WIN_MARGIN);
        winX = snapped[0];
        winY = snapped[1];
        windowRect(screen);
        return true;
    }

    public boolean handleRelease() {
        boolean wasResizing = windowResizer.end();
        if (!draggingWindow && !wasResizing) {
            return false;
        }
        draggingWindow = false;
        rememberWindow();
        return true;
    }

    /** Takes the window back to where the player last left it (first call only). */
    private void restoreRemembered() {
        memory.restore(state -> {
            winX = state.x;
            winY = state.y;
            winW = state.width(winW);
            winH = state.height(winH);
            minimized = state.minimized;
        });
    }

    /** Persists position, size and collapsed state so the window comes back where it was left. */
    private void rememberWindow() {
        memory.remember(winX, winY, winW, winH, minimized);
    }

    private boolean typing() {
        return active() && focused && !minimized;
    }

    /**
     * Keys while the window is focused. Everything is consumed, including plain letters - an 'e'
     * that reaches the container closes the inventory in the middle of an expression.
     */
    public boolean handleKey(KeyEvent event) {
        if (!typing()) {
            return false;
        }
        int key = event.key();
        if (key == KEY_ESCAPE) {
            focused = false;
        } else if (key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
            evaluate();
        } else if (key == KEY_BACKSPACE) {
            if (event.hasControlDown()) {
                input = "";
            } else {
                backspace();
            }
        }
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!typing()) {
            return false;
        }
        String typed = event.codepointAsString().toLowerCase(Locale.ROOT);
        if (typed.length() == 1 && TYPEABLE.indexOf(typed.charAt(0)) >= 0) {
            append(typed);
        }
        return true;
    }
}
