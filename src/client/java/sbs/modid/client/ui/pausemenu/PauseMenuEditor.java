/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.pausemenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.EdgeSnap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The pause-menu editor: the same editing language as the GUI editor, applied to every button in the
 * pause menu – the vanilla ones and the SBS button alike – drag to move, drag the corner grip to
 * resize, scroll to round the corners.
 *
 * <p>The SBS button is in here rather than off in its own settings because it is one of the buttons
 * you are arranging; it keeps its own drag and scroll in the normal pause menu, and both write the
 * same {@link PauseButtonStyle}, so the two ways in can never disagree.
 *
 * <p><b>Why it edits the real menu instead of a mock.</b> The vanilla menu is a {@code GridLayout}
 * rebuilt for every screen size, so a separate editor screen would have to either duplicate that
 * layout or edit a snapshot that stops matching the moment the window is resized. Instead the editor
 * is a mode <i>on</i> the pause screen ({@code PauseScreenMixin}): the widgets under the boxes are the
 * actual buttons, at their actual size, and every change is visible on the thing itself. The mode only
 * intercepts the mouse, so no vanilla button can fire while you are moving it.
 *
 * <p><b>Align</b> shares the GUI editor's toggle ({@code gui.editorSnap}) and its
 * {@link EdgeSnap} implementation – one concept, one setting, one behaviour in both editors.
 */
public final class PauseMenuEditor {

    /** How close (GUI px) an edge must come to a neighbour's or a border before the drag snaps onto it. */
    private static final int SNAP_RANGE = 7;

    /** The safe inset from each screen border that snapping offers alongside the border itself. */
    private static final int SCREEN_MARGIN = 4;

    /** Side of the square resize grip drawn on each box's bottom-right corner. */
    private static final int GRIP = 6;

    private static final int BOX_FILL = 0x303FB4FF;

    private static final int LEFT_BUTTON = 0;

    /**
     * Set by {@link #open()} and consumed by the pause screen it opens. A flag rather than a
     * constructor argument because the screen is vanilla's – SBS only decorates it.
     */
    private static boolean armed;

    private final PauseMenuLayout layout;

    /** Screen size the chrome was laid out for – what Reset re-applies the layout against. */
    private int screenWidth;
    private int screenHeight;

    private SciFiButton alignButton;

    private PauseMenuLayout.Anchored active;
    private boolean resizing;

    /** Grab point inside the widget, so it does not jump to the cursor on the first drag tick. */
    private double grabOffsetX;
    private double grabOffsetY;

    /** Live snap guide lines: {x, yFrom, yTo} / {y, xFrom, xTo}, or null while nothing snaps. */
    private float[] guideVertical;
    private float[] guideHorizontal;

    public PauseMenuEditor(PauseMenuLayout layout) {
        this.layout = layout;
    }

    // ------------------------------------------------------------------
    // Entering / leaving
    // ------------------------------------------------------------------

    /**
     * Opens the pause menu in edit mode – how the "Edit Pause Menu" settings row gets here. Needs a
     * world: the pause menu builds itself from the running game, so there is nothing to edit on the
     * title screen.
     */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        armed = true;
        minecraft.setScreenAndShow(new PauseScreen(true));
    }

    /** True once, for the pause screen that {@link #open()} asked for. */
    public static boolean consumeArmed() {
        boolean was = armed;
        armed = false;
        return was;
    }

    /** Saves and returns straight to the game, like the GUI editor's Save &amp; Exit. */
    public static void saveAndExit() {
        PauseMenuStyles.save();
        Minecraft.getInstance().setScreenAndShow(null);
    }

    /** Whether drag snapping is on – the GUI editor's setting, shared on purpose. */
    public static boolean alignEnabled() {
        return ConfigManager.getInstance().get().gui.editorSnap;
    }

    /** Clears the snap guides – call after Align was switched off under a live drag. */
    public void clearGuides() {
        guideVertical = null;
        guideHorizontal = null;
    }

    // ------------------------------------------------------------------
    // The editor's own buttons, top-centre – same three as the GUI editor
    // ------------------------------------------------------------------

    /**
     * Builds Reset / Align / Save &amp; Exit into the pause screen. Handed an {@code add} callback
     * rather than the screen itself because {@code addRenderableWidget} is the screen's own protected
     * method – the mixin passes it through and nothing here needs to know about screens.
     */
    public void createChrome(int screenWidth, int screenHeight, Consumer<AbstractWidget> add) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        int buttonW = 110;
        int buttonH = SBSTheme.SEARCH_HEIGHT;
        int gap = 8;
        int totalW = buttonW * 3 + gap * 2;
        int startX = (screenWidth - totalW) / 2;
        int y = 8 + Minecraft.getInstance().font.lineHeight + 6;

        add.accept(new SciFiButton(startX, y, buttonW, buttonH,
                Component.literal("Reset"), this::onReset));
        alignButton = new SciFiButton(startX + buttonW + gap, y, buttonW, buttonH,
                alignLabel(), this::onToggleAlign);
        add.accept(alignButton);
        add.accept(new SciFiButton(startX + (buttonW + gap) * 2, y, buttonW, buttonH,
                Component.literal("Save & Exit"), PauseMenuEditor::saveAndExit));
    }

    private static Component alignLabel() {
        return Component.literal(alignEnabled() ? "Align: On" : "Align: Off");
    }

    private void onToggleAlign() {
        var manager = ConfigManager.getInstance();
        manager.get().gui.editorSnap = !manager.get().gui.editorSnap;
        manager.save();
        alignButton.setMessage(alignLabel());
        clearGuides();
    }

    /** Puts every button back where, how big and how round vanilla built it – the SBS button too. */
    private void onReset() {
        PauseMenuStyles.resetAll();
        PauseMenuStyles.save();
        layout.apply(screenWidth, screenHeight);
        clearGuides();
    }

    // ------------------------------------------------------------------
    // Hit testing
    // ------------------------------------------------------------------

    /** The topmost captured widget under the cursor, or null. */
    private PauseMenuLayout.Anchored at(double mx, double my) {
        List<PauseMenuLayout.Anchored> entries = layout.captured();
        // Reverse order so the visually top-most (last drawn) widget wins where boxes overlap.
        for (int i = entries.size() - 1; i >= 0; i--) {
            AbstractWidget w = entries.get(i).widget();
            if (mx >= w.getX() && mx <= w.getX() + w.getWidth()
                    && my >= w.getY() && my <= w.getY() + w.getHeight()) {
                return entries.get(i);
            }
        }
        return null;
    }

    private static boolean overGrip(AbstractWidget w, double mx, double my) {
        int gx = w.getX() + w.getWidth() - GRIP;
        int gy = w.getY() + w.getHeight() - GRIP;
        return mx >= gx && mx <= gx + GRIP && my >= gy && my <= gy + GRIP;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Claims a click that lands on a menu widget. Clicks anywhere else are left alone so the editor's
     * own Reset / Align / Save &amp; Exit buttons keep working through the screen's normal dispatch.
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != LEFT_BUTTON) {
            return false;
        }
        PauseMenuLayout.Anchored hit = at(mx, my);
        if (hit == null) {
            return false;
        }
        active = hit;
        resizing = overGrip(hit.widget(), mx, my);
        grabOffsetX = mx - hit.widget().getX();
        grabOffsetY = my - hit.widget().getY();
        return true;
    }

    public boolean mouseDragged(double mx, double my, int screenWidth, int screenHeight) {
        if (active == null) {
            return false;
        }
        PauseButtonStyle style = PauseMenuStyles.getOrCreate(active.id());
        AbstractWidget widget = active.widget();
        if (resizing) {
            style.resize((int) Math.round(mx - widget.getX()), (int) Math.round(my - widget.getY()));
        } else {
            // Recomputed from the cursor every tick against the widget's un-offset position, rather
            // than nudged: a snapped box is then never "sticky" (pulling away from a line does not
            // have to fight an accumulated correction), and rounding cannot drift over a long drag.
            int laidOutX = widget.getX() - style.dx;
            int laidOutY = widget.getY() - style.dy;
            int targetX = Math.clamp((int) Math.round(mx - grabOffsetX), 0,
                    Math.max(0, screenWidth - widget.getWidth()));
            int targetY = Math.clamp((int) Math.round(my - grabOffsetY), 0,
                    Math.max(0, screenHeight - widget.getHeight()));
            style.dx = targetX - laidOutX;
            style.dy = targetY - laidOutY;
            clearGuides();
        }
        layout.apply(screenWidth, screenHeight);
        if (!resizing && alignEnabled()) {
            applySnap(style, screenWidth, screenHeight);
        }
        return true;
    }

    public boolean mouseReleased() {
        if (active == null) {
            return false;
        }
        active = null;
        resizing = false;
        clearGuides();
        PauseMenuStyles.save();
        return true;
    }

    /** Scrolling over a button rounds or squares its corners. */
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        PauseMenuLayout.Anchored hit = at(mx, my);
        if (hit == null) {
            return false;
        }
        int height = hit.widget().getHeight();
        PauseButtonStyle style = PauseMenuStyles.getOrCreate(hit.id());
        int current = style.corner < 0 ? SBSTheme.CORNER_RADIUS : style.corner;
        style.setCorner(current + (int) Math.signum(scrollY), height);
        PauseMenuStyles.save();
        return true;
    }

    /** Snaps the dragged widget onto its neighbours and the screen borders, then re-applies so it follows. */
    private void applySnap(PauseButtonStyle style, int screenWidth, int screenHeight) {
        AbstractWidget moving = active.widget();
        List<EdgeSnap.Rect> others = new ArrayList<>();
        for (PauseMenuLayout.Anchored entry : layout.captured()) {
            if (entry == active) {
                continue;
            }
            AbstractWidget w = entry.widget();
            others.add(new EdgeSnap.Rect(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
        }
        EdgeSnap.Result snap = EdgeSnap.solve(
                new EdgeSnap.Rect(moving.getX(), moving.getY(), moving.getWidth(), moving.getHeight()),
                others, SNAP_RANGE, new EdgeSnap.Rect(0, 0, screenWidth, screenHeight), SCREEN_MARGIN);
        if (snap == null) {
            return;
        }
        style.dx += (int) Math.round(snap.dx());
        style.dy += (int) Math.round(snap.dy());
        guideVertical = snap.guideVertical();
        guideHorizontal = snap.guideHorizontal();
        layout.apply(screenWidth, screenHeight);
    }

    // ------------------------------------------------------------------
    // Rendering – boxes over the real buttons, drawn after them
    // ------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        PauseMenuLayout.Anchored hovered = at(mouseX, mouseY);

        for (PauseMenuLayout.Anchored entry : layout.captured()) {
            AbstractWidget w = entry.widget();
            boolean isActive = entry == hovered || entry == active;
            g.fill(w.getX(), w.getY(), w.getX() + w.getWidth(), w.getY() + w.getHeight(), BOX_FILL);
            g.outline(w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                    isActive ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);

            // Resize grip, bottom-right – the only part of the box that resizes instead of moves.
            int gx = w.getX() + w.getWidth() - GRIP;
            int gy = w.getY() + w.getHeight() - GRIP;
            boolean gripHot = (entry == active && resizing) || overGrip(w, mouseX, mouseY);
            SciFiRender.roundedRect(g, gx, gy, GRIP, GRIP, 1,
                    gripHot ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);

            if (isActive) {
                // Live geometry on the hovered box: what you are actually setting, while you set it.
                PauseButtonStyle style = PauseMenuStyles.get(entry.id());
                int corner = style.corner < 0 ? SBSTheme.CORNER_RADIUS : style.corner;
                String info = w.getWidth() + "x" + w.getHeight() + "  r" + corner;
                int infoY = w.getY() - font.lineHeight - 1;
                g.centeredText(font, Component.literal(info), w.getX() + w.getWidth() / 2,
                        infoY < 0 ? w.getY() + 1 : infoY, SBSTheme.ACCENT_BRIGHT);
            }
        }

        if (guideVertical != null) {
            int gx = Math.round(guideVertical[0]);
            g.fill(gx, Math.round(guideVertical[1]), gx + 1, Math.round(guideVertical[2]),
                    SBSTheme.ACCENT_BRIGHT);
        }
        if (guideHorizontal != null) {
            int gy = Math.round(guideHorizontal[0]);
            g.fill(Math.round(guideHorizontal[1]), gy, Math.round(guideHorizontal[2]), gy + 1,
                    SBSTheme.ACCENT_BRIGHT);
        }

        g.centeredText(font, Component.literal("Edit Pause Menu"), screenWidth / 2, 8,
                SBSTheme.ACCENT_BRIGHT);
        g.centeredText(font, Component.literal(alignEnabled()
                        ? "Drag to move  •  Drag the corner to resize  •  Scroll to round  •  Align snaps to neighbours"
                        : "Drag to move  •  Drag the corner to resize  •  Scroll to round the corners"),
                screenWidth / 2, screenHeight - font.lineHeight - 6, SBSTheme.TEXT_MUTED);
    }
}
