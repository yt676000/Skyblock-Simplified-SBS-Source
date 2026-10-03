/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.window.EdgeSnap;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudTransform;

import java.util.ArrayList;
import java.util.List;

/**
 * The GUI editor: a full-screen overlay (SBS design language) for moving and scaling HUD elements.
 *
 * <p>Each {@link HudElement} is shown as a draggable bounding box at its live (transformed) position.
 * Dragging a box moves the element; scrolling the mouse wheel over it changes its scale. Both update
 * live because the boxes read {@link HudLayout#visualBounds} every frame, and the same transforms are
 * applied to the real HUD during gameplay – so the editor never duplicates render logic. It is the
 * <i>visual</i> bounds throughout – ring included, see {@link HudLayout#VISUAL_BLEED} – so the box you
 * drag against a border is the outline you can actually see, and snapping flush to one leaves nothing
 * hanging over the edge.
 *
 * <p>Three buttons sit top-centre: <b>Reset</b> (restore every element to its default position and
 * scale), <b>Align</b> (toggle drag snapping) and <b>Save &amp; Exit</b> (persist through the
 * existing config system and close the whole SBS overlay straight back to the game).
 *
 * <p><b>Align (snapping):</b> while it is on, a dragged box snaps to its neighbours – flush against
 * their edges (directly under / next to them) and onto their alignment lines (left/right/top/bottom
 * edges and centres) – and to the screen itself: every border, a safe {@link #SCREEN_MARGIN} inset
 * from it, and the two centre lines. Corners need no special case, because the axes snap
 * independently: a box dragged into one is caught by a border on each axis and lands in it. The
 * matched line is drawn as a bright guide while the snap holds, so "these two are attached" is
 * visible rather than guessed. Toggleable (and persisted) because pixel-precise free placement needs
 * it off.
 *
 * <p>The other two mouse buttons act on the element under the cursor as well, so everything about an
 * element is reachable from the element itself rather than from a menu somewhere else:
 * <ul>
 *   <li><b>Right-click</b> opens its opacity sliders – one each for the panel background, the frame
 *       and the text/icons, because a see-through card with a solid readout is the usual reason to
 *       touch these at all. The real HUD renders live behind this screen, so the element fades under
 *       the cursor as a slider moves.</li>
 *   <li><b>Middle-click</b> (mouse wheel) leaves the editor for that element's own settings page in
 *       the config screen – see {@link HudElement#settingsModuleId()}.</li>
 * </ul>
 */
public final class HudEditorScreen extends Screen implements sbs.modid.client.ui.theme.KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "hud_editor";
    }


    private static final int BOX_FILL = 0x303FB4FF;
    private static final int BOX_FILL_HIDDEN = 0x30808080;
    private static final double SCALE_STEP = 0.1;

    /** How close (GUI px) an edge must come to a neighbour's or a border before the drag snaps onto it. */
    private static final int SNAP_RANGE = 7;

    /** The safe inset from each screen border that snapping offers alongside the border itself. */
    private static final int SCREEN_MARGIN = 4;

    /** GLFW mouse buttons, spelled out because all three do something different here. */
    private static final int LEFT_BUTTON = 0;
    private static final int RIGHT_BUTTON = 1;
    private static final int MIDDLE_BUTTON = 2;

    /** Diameter of the red "minus" delete button drawn at each box's top-left corner. */
    private static final int MINUS_SIZE = 9;
    private static final int MINUS_RED = 0xFFE0433C;
    private static final int MINUS_WHITE = 0xFFFFFFFF;

    /** Right-click opacity popup: outer size, and the geometry of the slider tracks inside it. */
    private static final int POPUP_W = 158;
    private static final int POPUP_PAD = 8;
    private static final int TRACK_H = 8;
    private static final int KNOB_W = 6;

    /** Gap between a row's label line and its track, and between one row and the next. */
    private static final int LABEL_GAP = 2;
    private static final int ROW_GAP = 6;

    /** How much one mouse-wheel notch over a slider changes that opacity. */
    private static final double OPACITY_STEP = 0.05;

    /**
     * The three parts of an element that fade independently – one slider each in the popup.
     *
     * <p>A single slider plus a "what does this apply to" cycler used to do this job, which meant the
     * three combinations were reachable only one click at a time and, worse, that the parts could not
     * be set to <em>different</em> values: the whole point of separating text from its plate is a
     * see-through card with a solid readout, and that is two numbers, not one number and a mode.
     */
    private enum Dial {

        BACKGROUND("Background") {
            @Override double get(HudTransform t) { return t.backgroundOpacity(); }
            @Override void set(HudTransform t, double v) { t.setBackgroundOpacity(v); }
        },
        OUTLINE("Frame") {
            @Override double get(HudTransform t) { return t.outlineOpacity(); }
            @Override void set(HudTransform t, double v) { t.setOutlineOpacity(v); }
        },
        TEXT("Text & Icons") {
            @Override double get(HudTransform t) { return t.textOpacity(); }
            @Override void set(HudTransform t, double v) { t.setTextOpacity(v); }
        };

        private final String label;

        Dial(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        abstract double get(HudTransform t);

        abstract void set(HudTransform t, double v);
    }

    private static final Dial[] DIALS = Dial.values();

    private HudElement dragging;

    /** The element whose opacity popup is open, or null while no popup is showing. */
    private HudElement opacityElement;
    private int popupX;
    private int popupY;

    /** The slider being dragged in the open popup, or null while none is. */
    private Dial draggingDial;

    /**
     * The drag position as the mouse actually moved it, BEFORE any snap correction. Kept separately
     * so the snap is recomputed from scratch each drag tick: correcting the stored transform instead
     * would make a snapped box "sticky" – the mouse would have to fight the accumulated correction
     * to pull it off a line again.
     */
    private double dragRawX;
    private double dragRawY;

    /** Live snap guide lines: {x, yFrom, yTo} / {y, xFrom, xTo}, or null while nothing snaps. */
    private float[] guideVertical;
    private float[] guideHorizontal;

    private SciFiButton alignButton;

    /** The elements this editor lets you move (all of them by default; a subset for focused editors). */
    private final HudElement[] elements;

    public HudEditorScreen() {
        this(HudElement.values(), "Edit GUI");
    }

    /**
     * Editor over only the elements that were <b>actually on screen</b> in the last frame of play –
     * see {@link HudLayout#isVisible}. Not-hidden is far too weak a test on its own: most SBS cards
     * are self-hiding (the Pest card away from the Garden, the damage cards without a recent hit, the
     * buff cards without an active buff), so an editor filtered on the hidden flag alone still showed
     * a screenful of boxes for things you cannot see – exactly the clutter this variant exists to
     * remove.
     *
     * <p>The flip side is that an element you cannot currently see is not editable here, on purpose;
     * the plain {@link #HudEditorScreen()} editor still reaches everything.
     *
     * @return an editor over the visible elements, or over all of them when none is visible (an
     *         empty editor would look broken and give no way back to the rest)
     */
    public static HudEditorScreen activeOnly() {
        HudElement[] visible = java.util.Arrays.stream(HudElement.values())
                .filter(HudLayout::isVisible)
                .toArray(HudElement[]::new);
        return visible.length == 0
                ? new HudEditorScreen()
                : new HudEditorScreen(visible, "Edit GUI (visible only)");
    }

    /** Focused editor variant: only the given elements are shown/draggable (e.g. just the Dungeon Map). */
    public HudEditorScreen(HudElement[] elements, String title) {
        super(Component.literal(title));
        this.elements = elements;
    }

    @Override
    protected void init() {
        int buttonW = 110;
        int buttonH = SBSTheme.SEARCH_HEIGHT;
        int gap = 8;
        int totalW = buttonW * 3 + gap * 2;
        int startX = (this.width - totalW) / 2;
        int buttonsY = 8 + this.font.lineHeight + 6;

        addRenderableOnly(new BoxesRenderable());

        addRenderableWidget(new SciFiButton(startX, buttonsY, buttonW, buttonH,
                Component.literal("Reset"), this::onReset));
        alignButton = new SciFiButton(startX + buttonW + gap, buttonsY, buttonW, buttonH,
                alignLabel(), this::onToggleAlign);
        addRenderableWidget(alignButton);
        addRenderableWidget(new SciFiButton(startX + (buttonW + gap) * 2, buttonsY, buttonW, buttonH,
                Component.literal("Save & Exit"), this::onSaveAndExit));
    }

    private void onReset() {
        HudLayout.resetAll();
        opacityElement = null;
        draggingDial = null;
    }

    /** Whether drag snapping ("Align") is on – persisted in the config, so it survives sessions. */
    private static boolean alignEnabled() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().gui.editorSnap;
    }

    private Component alignLabel() {
        return Component.literal(alignEnabled() ? "Align: On" : "Align: Off");
    }

    private void onToggleAlign() {
        var manager = sbs.modid.client.core.config.ConfigManager.getInstance();
        manager.get().gui.editorSnap = !manager.get().gui.editorSnap;
        manager.save();
        alignButton.setMessage(alignLabel());
        guideVertical = null;
        guideHorizontal = null;
    }

    private void onSaveAndExit() {
        HudLayout.save();
        // Close the entire SBS overlay and return directly to the game.
        Minecraft.getInstance().setScreenAndShow(null);
    }

    /**
     * Persists the layout however the editor is left – Escape, switching screens, or Save &amp; Exit.
     *
     * <p>Previously only the "Save &amp; Exit" button saved, so closing with Escape kept the moved
     * position in memory for the session but never wrote it to disk – and it was gone on the next
     * launch, which read as "the elements never save their position". Saving on {@code removed}
     * makes any way of closing the editor persist. The write is idempotent, so Save &amp; Exit
     * saving twice is harmless.
     */
    @Override
    public void removed() {
        HudLayout.save();
        super.removed();
    }

    // ------------------------------------------------------------------
    // Dragging (move) and scrolling (scale)
    // ------------------------------------------------------------------

    private HudElement elementAt(double mouseX, double mouseY) {
        // Iterate in reverse so the visually top-most (last drawn) box wins when boxes overlap.
        for (int i = elements.length - 1; i >= 0; i--) {
            if (HudLayout.visualBounds(elements[i], this.width, this.height).contains(mouseX, mouseY)) {
                return elements[i];
            }
        }
        return null;
    }

    /** Whether {@code (mx, my)} is over the minus (delete) button of the given element's box. */
    private boolean overMinus(HudElement element, double mx, double my) {
        HudElement.Bounds b = HudLayout.visualBounds(element, this.width, this.height);
        int cx = Math.round(b.x());
        int cy = Math.round(b.y());
        int half = MINUS_SIZE / 2;
        return mx >= cx - half && mx <= cx + half && my >= cy - half && my <= cy + half;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // The open popup is modal over the area it covers: it sits on top of the boxes, so a click
        // inside it must never reach the element behind it.
        if (opacityElement != null) {
            if (inPopup(event.x(), event.y())) {
                Dial hit = event.button() == LEFT_BUTTON ? dialAt(event.x(), event.y()) : null;
                if (hit != null) {
                    draggingDial = hit;
                    setOpacityFromMouse(hit, event.x());
                }
                return true;
            }
            opacityElement = null; // clicking anywhere else dismisses it, and still does its own job
        }
        if (event.button() == RIGHT_BUTTON) {
            HudElement hit = elementAt(event.x(), event.y());
            if (hit != null) {
                openOpacityPopup(hit, event.x(), event.y());
                return true;
            }
            return false;
        }
        if (event.button() == MIDDLE_BUTTON) {
            HudElement hit = elementAt(event.x(), event.y());
            if (hit != null) {
                openSettingsFor(hit);
                return true;
            }
            return false;
        }
        if (super.mouseClicked(event, doubled)) {
            return true; // a top-centre button (Reset / Save & Exit) was clicked
        }
        // Minus buttons take priority over dragging (they sit on the box corner).
        for (HudElement element : elements) {
            if (overMinus(element, event.x(), event.y())) {
                HudLayout.toggleHidden(element);
                return true;
            }
        }
        HudElement hit = elementAt(event.x(), event.y());
        if (hit != null) {
            dragging = hit;
            HudTransform t = HudLayout.getOrCreate(hit);
            dragRawX = t.x;
            dragRawY = t.y;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingDial != null) {
            setOpacityFromMouse(draggingDial, event.x());
            return true;
        }
        if (dragging != null) {
            dragRawX += dragX;
            dragRawY += dragY;
            HudTransform t = HudLayout.getOrCreate(dragging);
            // The raw position is authoritative; the snap is a fresh correction on top each tick.
            t.x = dragRawX;
            t.y = dragRawY;
            guideVertical = null;
            guideHorizontal = null;
            if (alignEnabled()) {
                applySnap(t);
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        draggingDial = null;
        guideVertical = null;
        guideHorizontal = null;
        return super.mouseReleased(event);
    }

    // ------------------------------------------------------------------
    // Right-click: per-element opacity
    // ------------------------------------------------------------------

    /** Opens the opacity popup for {@code element}, kept fully on screen next to the click. */
    private void openOpacityPopup(HudElement element, double mouseX, double mouseY) {
        opacityElement = element;
        draggingDial = null;
        popupX = (int) Math.max(2, Math.min(this.width - POPUP_W - 2, mouseX - POPUP_W / 2.0));
        // Above the cursor by preference, below it when there is no room up there.
        int above = (int) mouseY - popupH() - 6;
        popupY = above >= 2 ? above : (int) Math.min(this.height - popupH() - 2, mouseY + 6);
    }

    /** Header (name) plus one labelled slider per dial. */
    private int popupH() {
        return 6 + this.font.lineHeight + 5 + DIALS.length * rowH();
    }

    /** One dial's row: its label line, the track, and the gap to the next row. */
    private int rowH() {
        return this.font.lineHeight + LABEL_GAP + TRACK_H + ROW_GAP;
    }

    private int trackX() {
        return popupX + POPUP_PAD;
    }

    private int trackW() {
        return POPUP_W - POPUP_PAD * 2;
    }

    /** Top of the label line of {@code dial}'s row. */
    private int rowY(Dial dial) {
        return popupY + 6 + this.font.lineHeight + 5 + dial.ordinal() * rowH();
    }

    private int trackY(Dial dial) {
        return rowY(dial) + this.font.lineHeight + LABEL_GAP;
    }

    private boolean inPopup(double mx, double my) {
        return opacityElement != null
                && mx >= popupX && mx <= popupX + POPUP_W
                && my >= popupY && my <= popupY + popupH();
    }

    /**
     * The dial whose slider is under {@code (mx, my)}, or null. The rows are deliberately hit-tested
     * over their whole height, label included: hitting an 8px bar exactly is not what a click on a
     * row should require, and the rows tile the popup so there is no ambiguity about which one owns
     * a given y.
     */
    private Dial dialAt(double mx, double my) {
        if (mx < trackX() - 2 || mx > trackX() + trackW() + 2) {
            return null;
        }
        for (Dial dial : DIALS) {
            int top = rowY(dial);
            if (my >= top && my < top + rowH()) {
                return dial;
            }
        }
        return null;
    }

    private void setOpacityFromMouse(Dial dial, double mouseX) {
        if (opacityElement == null) {
            return;
        }
        int usable = trackW() - KNOB_W;
        if (usable <= 0) {
            return;
        }
        double fraction = (mouseX - (trackX() + KNOB_W / 2.0)) / usable;
        dial.set(HudLayout.getOrCreate(opacityElement),
                HudTransform.MIN_OPACITY + fraction * (HudTransform.MAX_OPACITY - HudTransform.MIN_OPACITY));
    }

    /** Where a dial's knob sits for its current value, as an offset into the track. */
    private int knobOffset(Dial dial) {
        double fraction = (dial.get(HudLayout.get(opacityElement)) - HudTransform.MIN_OPACITY)
                / (HudTransform.MAX_OPACITY - HudTransform.MIN_OPACITY);
        return (int) Math.round(Math.max(0, Math.min(1, fraction)) * (trackW() - KNOB_W));
    }

    // ------------------------------------------------------------------
    // Middle-click: jump to this element's own settings
    // ------------------------------------------------------------------

    /**
     * Leaves the editor for the config page that owns {@code element}. The layout is saved on the way
     * out by {@link #removed()}, so the move / scale / opacity being fine-tuned is never lost by
     * jumping to the settings mid-edit.
     */
    private void openSettingsFor(HudElement element) {
        sbs.modid.client.ui.settings.ConfigNavigator.jumpToModule(element.settingsModuleId());
    }

    // ------------------------------------------------------------------
    // Align (snapping)
    // ------------------------------------------------------------------

    /**
     * Snaps the dragged element onto its nearest neighbour lines and onto the screen's own borders and
     * centre lines – the algorithm itself lives in {@link EdgeSnap}, shared with the pause-menu editor,
     * because "attach this box to that one" is the same problem in both.
     */
    private void applySnap(HudTransform t) {
        HudElement.Bounds b = HudLayout.visualBounds(dragging, this.width, this.height);
        List<EdgeSnap.Rect> others = new ArrayList<>();
        for (HudElement other : elements) {
            if (other == dragging) {
                continue;
            }
            HudElement.Bounds o = HudLayout.visualBounds(other, this.width, this.height);
            others.add(new EdgeSnap.Rect(o.x(), o.y(), o.w(), o.h()));
        }
        EdgeSnap.Result snap = EdgeSnap.solve(new EdgeSnap.Rect(b.x(), b.y(), b.w(), b.h()),
                others, SNAP_RANGE, new EdgeSnap.Rect(0, 0, this.width, this.height), SCREEN_MARGIN);
        if (snap == null) {
            return;
        }
        t.x += snap.dx();
        t.y += snap.dy();
        guideVertical = snap.guideVertical();
        guideHorizontal = snap.guideHorizontal();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // Over the popup the wheel nudges the slider it is pointing at – the same gesture as resizing,
        // on the control under the cursor. Pointing at no slider still swallows the scroll, so the
        // element behind the popup is never resized by a wheel meant for it.
        if (inPopup(mouseX, mouseY)) {
            Dial dial = dialAt(mouseX, mouseY);
            if (dial != null) {
                HudTransform t = HudLayout.getOrCreate(opacityElement);
                dial.set(t, dial.get(t) + scrollY * OPACITY_STEP);
            }
            return true;
        }
        HudElement hit = elementAt(mouseX, mouseY);
        if (hit != null) {
            HudLayout.getOrCreate(hit).addScale(scrollY * SCALE_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * What follows an element's name on its editor box: its hidden state, or whichever of its three
     * opacities are not full. Named per dial once they differ – "40%" alone would be a lie about the
     * other two, and the whole reason they are separate is that they can disagree.
     */
    private static String labelSuffix(HudElement element, boolean hidden) {
        if (hidden) {
            return " (hidden)";
        }
        HudTransform t = HudLayout.get(element);
        double background = t.backgroundOpacity();
        double outline = t.outlineOpacity();
        double text = t.textOpacity();
        if (background >= 1.0 && outline >= 1.0 && text >= 1.0) {
            return "";
        }
        if (background == outline && outline == text) {
            return " (" + percent(background) + ")";
        }
        StringBuilder parts = new StringBuilder();
        for (Dial dial : DIALS) {
            double value = dial.get(t);
            if (value < 1.0) {
                parts.append(parts.isEmpty() ? "" : " ").append(dial.label().charAt(0)).append(' ')
                        .append(percent(value));
            }
        }
        return " (" + parts + ")";
    }

    private static String percent(double opacity) {
        return Math.round(opacity * 100) + "%";
    }

    /** Draws a small red circle with a white minus sign, centred on {@code (cx, cy)} (a box corner). */
    private static void drawMinusButton(GuiGraphicsExtractor g, int cx, int cy) {
        int half = MINUS_SIZE / 2;
        int x = cx - half;
        int y = cy - half;
        SciFiRender.roundedRect(g, x, y, MINUS_SIZE, MINUS_SIZE, half, MINUS_RED);
        g.fill(x + 2, cy, x + MINUS_SIZE - 2, cy + 1, MINUS_WHITE);
    }

    // ------------------------------------------------------------------
    // Rendering (boxes + header), drawn behind the buttons
    // ------------------------------------------------------------------

    private final class BoxesRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = HudEditorScreen.this.font;
            int w = HudEditorScreen.this.width;
            int h = HudEditorScreen.this.height;

            g.fill(0, 0, w, h, SBSTheme.BG_TINT);

            HudElement hovered = elementAt(mouseX, mouseY);
            for (HudElement element : HudEditorScreen.this.elements) {
                HudElement.Bounds b = HudLayout.visualBounds(element, w, h);
                int x = Math.round(b.x());
                int y = Math.round(b.y());
                int bw = Math.max(2, Math.round(b.w()));
                int bh = Math.max(2, Math.round(b.h()));

                boolean hidden = HudLayout.get(element).hidden;
                boolean active = element == hovered || element == dragging || element == opacityElement;
                int border = active ? SBSTheme.ACCENT_BRIGHT : (hidden ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT);
                g.fill(x, y, x + bw, y + bh, hidden ? BOX_FILL_HIDDEN : BOX_FILL);
                g.outline(x, y, bw, bh, border);

                // Label centred above the box (or inside if it would clip off the top edge). A faded
                // element carries its percentage so the setting is visible without reopening its popup.
                Component name = Component.literal(element.displayName() + labelSuffix(element, hidden));
                int labelY = y - font.lineHeight - 1;
                if (labelY < 0) {
                    labelY = y + 1;
                }
                int labelColor = active ? SBSTheme.ACCENT_BRIGHT : (hidden ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT);
                g.centeredText(font, name, x + bw / 2, labelY, labelColor);

                drawMinusButton(g, x, y);
            }

            // Snap guide lines: bright, drawn over the boxes so the matched edge is unmistakable.
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

            drawOpacityPopup(g, mouseX, mouseY);

            // Header + hints, top-centre (above the Reset / Align / Save & Exit buttons) and bottom.
            g.centeredText(font, HudEditorScreen.this.getTitle(), w / 2, 8, SBSTheme.ACCENT_BRIGHT);
            Component hint = Component.literal(alignEnabled()
                    ? "Drag to move  •  Scroll to resize  •  Align snaps to neighbours and screen edges"
                    : "Drag to move  •  Scroll to resize");
            g.centeredText(font, hint, w / 2, h - font.lineHeight * 2 - 8, SBSTheme.TEXT_MUTED);
            g.centeredText(font, Component.literal(
                            "Right-click for opacity  •  Middle-click to open this element's settings"),
                    w / 2, h - font.lineHeight - 6, SBSTheme.TEXT_MUTED);
        }

        /** The right-click opacity popup: the element's name, then one labelled slider per dial. */
        private void drawOpacityPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            HudElement element = opacityElement;
            if (element == null) {
                return;
            }
            var font = HudEditorScreen.this.font;
            int h = popupH();
            SciFiRender.glow(g, popupX, popupY, POPUP_W, h, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRectWithBorder(g, popupX, popupY, POPUP_W, h, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_FILL_TOP, SBSTheme.ACCENT);

            g.text(font, Component.literal(element.displayName()), popupX + POPUP_PAD, popupY + 6,
                    SBSTheme.TEXT);

            HudTransform transform = HudLayout.get(element);
            Dial hovered = dialAt(mouseX, mouseY);
            for (Dial dial : DIALS) {
                drawDial(g, dial, transform, dial == draggingDial || dial == hovered);
            }
        }

        /** One dial: its name on the left, its percentage on the right, and the slider under both. */
        private void drawDial(GuiGraphicsExtractor g, Dial dial, HudTransform transform, boolean hot) {
            var font = HudEditorScreen.this.font;
            int tx = trackX();
            int tw = trackW();

            int ly = rowY(dial);
            g.text(font, Component.literal(dial.label()), tx, ly, SBSTheme.TEXT_MUTED);
            String value = percent(dial.get(transform));
            g.text(font, Component.literal(value), tx + tw - font.width(value), ly,
                    hot ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);

            int ty = trackY(dial);
            SciFiRender.roundedRectWithBorder(g, tx, ty, tw, TRACK_H, 3, SBSTheme.CARD_BG_DISABLED,
                    hot ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT_SOFT);
            int knobX = tx + knobOffset(dial);
            if (knobX > tx + 1) {
                SciFiRender.roundedRect(g, tx + 1, ty + 1, knobX - tx - 1, TRACK_H - 2, 2, SBSTheme.ACCENT_SOFT);
            }
            SciFiRender.roundedRectWithBorder(g, knobX, ty, KNOB_W, TRACK_H, 2,
                    SBSTheme.ACCENT, SBSTheme.ACCENT_BRIGHT);
        }
    }
}
