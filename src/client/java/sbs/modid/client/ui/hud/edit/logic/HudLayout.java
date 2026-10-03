/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.logic;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.Map;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.model.HudTransform;

/**
 * Central access point for HUD element transforms – the bridge between the persisted
 * {@link HudTransform} map (in the SBS config) and the live render pipeline.
 *
 * <p>Rendering wraps each element's draw call in {@link #begin}/{@link #end}: these push a matrix on
 * the shared {@link GuiGraphicsExtractor#pose()} stack, translate by the saved offset and scale about
 * the element's default anchor, so the element's own drawing code stays completely unchanged (no
 * duplicate render logic). The editor uses {@link #displayBounds} to draw and hit-test the boxes –
 * both sides derive from {@link HudElement#defaultBounds}, so what you drag is exactly what renders.
 *
 * <p>No Fabric API is involved – only {@code net.minecraft.*} and JOML (the matrix stack Minecraft's
 * own GUI uses).
 */
public final class HudLayout {

    /** Shared read-only identity used when an element has never been moved (avoids map churn while rendering). */
    private static final HudTransform IDENTITY = new HudTransform();

    /**
     * How far outside its measured rectangle an SBS panel paints, in the element's own coordinates.
     *
     * <p>Every card reports the box it fills to {@link #measure} and then draws its ring around that
     * box – {@code SciFiRender.glow} two pixels out, the plain border ring one. So the rectangle the
     * player sees is bigger than the one the element measured, and snapping the measured one flush
     * against a screen border puts the ring past the edge, where it is cut off. Anything asking "what
     * does this element occupy on screen" wants {@link #visualBounds}; anything asking "where does it
     * draw" wants {@link #displayBounds}.
     */
    public static final int VISUAL_BLEED = 2;

    /**
     * The actual local rectangle each element last drew ({@code [x, y, w, h]} in the same space as
     * {@link HudElement#defaultBounds}). Overlays whose real size is content-derived report it via
     * {@link #measure}, so the editor box matches what renders instead of a static default guess.
     * Runtime only; an element that has not rendered simply falls back to its default bounds.
     */
    private static final Map<String, float[]> measured = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The HUD frame each element last drew in, for {@link #isVisible}. A frame counter rather than a
     * timestamp because the HUD stops advancing whenever a screen is open: a time window would decide
     * "nothing is visible" a second after opening any menu, while the counter simply freezes and keeps
     * answering with what the last frame of actual play looked like - which is the honest answer.
     */
    private static final Map<String, long[]> drawnFrame = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile long hudFrame;

    /** Whether the frame being drawn counts towards visibility (false while any screen is open). */
    private static volatile boolean countingFrame;

    /**
     * Matrices {@link #begin} has pushed that {@link #end} has not popped yet, this frame.
     *
     * <p>The pose stack is shared with vanilla and every other mod, so an unbalanced pair here does
     * not stay this class's problem: one pop too many takes a matrix somebody else pushed. Deep in a
     * normal HUD frame that silently misplaces everything drawn afterwards; on a join screen, where
     * nothing has nested yet, the stack is at the bottom and JOML throws instead. Counting our own
     * pushes is what lets {@link #end} tell those apart from a legitimate pop.
     */
    private static int depth;

    /** So an unbalanced pair is reported once and not once per frame forever. */
    private static boolean warnedUnbalanced;

    private HudLayout() {
    }

    /**
     * Opens a HUD frame; called once per HUD render pass before any element draws.
     *
     * <p>Frames drawn behind an open screen are deliberately not counted. The HUD keeps rendering
     * there, but several elements suppress themselves while a menu is up, so counting those frames
     * would make "what is visible" mean "what survives with the menu open" - and the editor is
     * reached from a menu, so that is exactly the wrong moment to sample.
     */
    public static void beginFrame() {
        countingFrame = sbs.modid.client.core.api.ScreenAccess.current() == null;
        if (countingFrame) {
            hudFrame++;
        }
        // The pose stack itself is rebuilt per frame, so anything left pushed by a draw that threw
        // is already gone - only our count of it would survive into a frame it does not describe.
        depth = 0;
    }

    /**
     * Whether the element was actually on screen in the last counted frame - not merely enabled.
     * Most SBS cards are self-hiding (no data, wrong island, no held tool), so "not hidden" says very
     * little about whether you can currently see the thing.
     */
    public static boolean isVisible(HudElement element) {
        if (isHidden(element)) {
            return false;
        }
        long[] stamp = drawnFrame.get(element.id());
        return stamp != null && stamp[0] == hudFrame;
    }

    /**
     * An overlay reports the actual rectangle it just drew, in default-bounds (pre-transform) space,
     * so {@link #displayBounds} – and therefore the editor's draggable box – tracks the real element
     * size. Cheap and safe to call every frame from inside the element's own render.
     */
    public static void measure(HudElement element, float x, float y, float w, float h) {
        measured.put(element.id(), new float[] {x, y, w, h});
    }

    private static Map<String, HudTransform> map() {
        return HudLayoutStore.getInstance().map();
    }

    /** Read-only transform for an element (identity if unset) – safe to call every frame. */
    public static HudTransform get(HudElement element) {
        HudTransform t = map().get(element.id());
        return t != null ? t : IDENTITY;
    }

    /** Mutable transform for an element, inserting a fresh identity entry if needed (editor only). */
    public static HudTransform getOrCreate(HudElement element) {
        return map().computeIfAbsent(element.id(), k -> new HudTransform());
    }

    /** When true, every card is hidden at once (cinematic freecam's clean screen); set by its owner. */
    private static volatile java.util.function.BooleanSupplier hideAll = () -> false;

    public static void setHideAll(java.util.function.BooleanSupplier supplier) {
        hideAll = supplier;
    }

    /**
     * Whether an element is hidden - via its editor minus button, or all of them for a clean screen -
     * safe to call every frame. The editor's own button state is {@code get(element).hidden}.
     */
    public static boolean isHidden(HudElement element) {
        return get(element).hidden || hideAll.getAsBoolean();
    }

    /** Toggles an element's hidden state (editor only). */
    public static void toggleHidden(HudElement element) {
        HudTransform t = getOrCreate(element);
        t.hidden = !t.hidden;
    }

    /** Restores every element to its default position and scale. */
    public static void resetAll() {
        map().clear();
    }

    /**
     * The element's own (pre-transform) rectangle: what it last reported to {@link #measure}, or its
     * static default when it has never measured itself. The space {@link HudElement#defaultBounds} is
     * in – {@link #displayBounds} is what turns it into screen coordinates.
     */
    static HudElement.Bounds localBounds(HudElement element, int guiWidth, int guiHeight) {
        HudElement.Bounds def = element.defaultBounds(guiWidth, guiHeight);
        float[] m = measured.get(element.id());
        return m == null ? def : new HudElement.Bounds(m[0], m[1], m[2], m[3]);
    }

    /**
     * Maps a screen x back into the element's own coordinate space – the inverse of what {@link #begin}
     * applies. What an element needs to hit-test its own clickable parts, which it draws in local
     * coordinates.
     */
    public static double localX(HudElement element, double screenX, int guiWidth, int guiHeight) {
        HudTransform t = get(element);
        HudElement.Bounds b = element.defaultBounds(guiWidth, guiHeight);
        return (screenX - t.x - b.x()) / t.scale + b.x() - HudGrowth.offset(element, guiWidth, guiHeight)[0];
    }

    /** Maps a screen y back into the element's own coordinate space – see {@link #localX}. */
    public static double localY(HudElement element, double screenY, int guiWidth, int guiHeight) {
        HudTransform t = get(element);
        HudElement.Bounds b = element.defaultBounds(guiWidth, guiHeight);
        return (screenY - t.y - b.y()) / t.scale + b.y() - HudGrowth.offset(element, guiWidth, guiHeight)[1];
    }

    /** Persists the current layout to its own {@code gui/hud_layout.json} file. */
    public static void save() {
        HudLayoutStore.getInstance().save();
    }

    /**
     * Pushes the element's transform onto the pose stack, and its three opacities onto {@link HudOpacity}.
     * Must be paired with {@link #end}. Scaling is done about the element's default top-left anchor so
     * scale and offset compose predictably.
     *
     * <p>The {@link HudGrowth} correction rides inside the scale, so it is expressed in the element's
     * own coordinates: an element that outgrew its box is translated to wherever the extra size can
     * actually be read, without the element's drawing code knowing anything about it.
     */
    public static void begin(GuiGraphicsExtractor g, HudElement element) {
        if (countingFrame) {
            // Reusing the array instead of boxing a fresh Long keeps this allocation-free on a path
            // that runs for every element, every frame.
            drawnFrame.computeIfAbsent(element.id(), k -> new long[1])[0] = hudFrame;
        }
        HudTransform t = get(element);
        HudElement.Bounds b = element.defaultBounds(g.guiWidth(), g.guiHeight());
        float[] grow = HudGrowth.offset(element, g.guiWidth(), g.guiHeight());
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) (b.x() + t.x), (float) (b.y() + t.y));
        pose.scale((float) t.scale);
        pose.translate(-b.x() + grow[0], -b.y() + grow[1]);
        HudOpacity.push((float) t.backgroundOpacity(), (float) t.outlineOpacity(), (float) t.textOpacity());
        depth++;
    }

    /**
     * Pops the matrix and the opacity pushed by {@link #begin}.
     *
     * <p>An {@code end} with no {@code begin} behind it does nothing at all - see {@link #depth} for
     * why popping anyway is the worse of the two failures. {@link HudOpacity#pop} already refuses
     * the same way; this is the matching half for the matrix.
     */
    public static void end(GuiGraphicsExtractor g) {
        if (depth <= 0) {
            if (!warnedUnbalanced) {
                warnedUnbalanced = true;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Hud] end() without a matching begin() - the pop was refused so the "
                                + "shared pose stack keeps its shape, but some element's begin/end "
                                + "pair is wrong", new Throwable("HUD begin/end trace"));
            }
            return;
        }
        depth--;
        g.pose().popMatrix();
        HudOpacity.pop();
    }

    /**
     * The element's on-screen rectangle after its transform – what the editor draws and hit-tests.
     * Uses the element's measured content rectangle ({@link #measure}) when it has reported one this
     * session, so the box matches the real overlay size; otherwise the static {@link
     * HudElement#defaultBounds}. Scale, offset and the {@link HudGrowth} correction are applied
     * exactly like {@link #begin}: scaling is about the default anchor, so a measured rectangle drawn
     * off-anchor still transforms correctly.
     */
    public static HudElement.Bounds displayBounds(HudElement element, int guiWidth, int guiHeight) {
        HudTransform t = get(element);
        HudElement.Bounds def = element.defaultBounds(guiWidth, guiHeight);
        HudElement.Bounds local = localBounds(element, guiWidth, guiHeight);
        float[] grow = HudGrowth.offset(element, guiWidth, guiHeight);
        float ax = def.x();
        float ay = def.y();
        return new HudElement.Bounds(
                (float) (ax + t.x + (local.x() + grow[0] - ax) * t.scale),
                (float) (ay + t.y + (local.y() + grow[1] - ay) * t.scale),
                (float) (local.w() * t.scale),
                (float) (local.h() * t.scale));
    }

    /**
     * {@link #displayBounds} grown by {@link #VISUAL_BLEED} – the rectangle the player actually sees,
     * ring and all. What the editor draws, hit-tests and snaps, and what the on-screen clamp keeps
     * inside the screen; the element's <i>drawing</i> still starts at {@link #displayBounds}.
     */
    public static HudElement.Bounds visualBounds(HudElement element, int guiWidth, int guiHeight) {
        HudElement.Bounds b = displayBounds(element, guiWidth, guiHeight);
        float bleed = (float) (VISUAL_BLEED * get(element).scale);
        return new HudElement.Bounds(b.x() - bleed, b.y() - bleed, b.w() + bleed * 2, b.h() + bleed * 2);
    }
}
