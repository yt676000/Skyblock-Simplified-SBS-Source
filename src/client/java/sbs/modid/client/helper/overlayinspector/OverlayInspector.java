/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.overlayinspector;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Overlay Inspector: a free mouse pointer over the running game that names the mod behind whatever
 * HUD element it is over, and opens that mod's settings on click.
 *
 * <p><b>The problem.</b> A loaded-up client draws a dozen overlays from half a dozen mods, and
 * nothing on screen says which is which. Turning a bar or a counter off means guessing at the mod,
 * then hunting through its settings – for an overlay you cannot even name.
 *
 * <p><b>How the answer is obtained.</b> Every rectangle, sprite, item and text the client draws is
 * handed to one pipeline, and each carries the screen box it covers. While the inspector is on, the
 * capture hook records those boxes together with the mod that submitted them
 * ({@link ModAttribution}), so the frame you are looking at doubles as a map of who drew what. The
 * pointer then simply looks up the box under it.
 *
 * <p><b>Why no screen is opened.</b> The obvious way to get a cursor is a transparent screen, but
 * opening one changes the very thing being inspected: overlays that hide themselves in menus
 * disappear, the chat expands, hover states change. So the game stays screenless and only the camera
 * is frozen – the pointer is a virtual one, moved by the same mouse deltas that would have turned
 * the player. What you inspect is exactly what you play with.
 *
 * <p>Everything here is inert while the inspector is off: {@link #isActive()} is a plain field read,
 * and it gates the capture hook, the camera freeze and the click handling alike.
 */
public final class OverlayInspector {

    /** Rectangles kept per frame. Far above a busy client's HUD; a runaway drawer stops here. */
    private static final int MAX_RECORDS = 8192;

    /** Gap in scaled pixels still counted as "part of the same element" when growing the outline. */
    private static final int CLUSTER_GAP = 6;

    /** Passes over the frame while growing an element outline. Converges long before this. */
    private static final int CLUSTER_PASSES = 8;

    /**
     * Rectangles covering more of the screen than this are never merged into an element outline: a
     * full-screen backdrop would otherwise stretch the box around the whole display.
     */
    private static final double BACKDROP_FRACTION = 0.6;

    private static final OverlayInspector INSTANCE = new OverlayInspector();

    /** left, top, right, bottom per record. */
    private final int[] rects = new int[MAX_RECORDS * 4];
    private final String[] owners = new String[MAX_RECORDS];

    /** The frame each answer came from – references only, so recording stays allocation-free. */
    private final Class<?>[] sourceClasses = new Class<?>[MAX_RECORDS];
    private final String[] sourceMethods = new String[MAX_RECORDS];
    private int count;

    private volatile boolean active;

    /** True while the inspector draws its own overlay – its rectangles must not be recorded. */
    private boolean drawingSelf;

    /** Pointer position in scaled GUI coordinates ({@code -1} until first placed). */
    private double cursorX = -1;
    private double cursorY = -1;

    /** What the pointer was over on the last drawn frame – also what a click acts on. */
    private Hovered hovered;

    /** Last answer written to the log, so a held pointer logs once rather than every frame. */
    private String lastLoggedHover;

    private OverlayInspector() {
    }

    public static OverlayInspector getInstance() {
        return INSTANCE;
    }

    /**
     * One resolved element: the mod that drew it, the box it covers, and the code the answer came
     * from.
     *
     * <p>That last part is on the card on purpose. Attribution reads a call stack, and a wrong
     * answer is indistinguishable from a right one unless it can be checked – naming the class and
     * method that decided it turns "it says the wrong mod" into something anyone can see the reason
     * for.
     */
    public record Hovered(String modId, int left, int top, int right, int bottom,
                          Class<?> sourceClass, String sourceMethod) {

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        /** Short {@code Class.method} of the deciding frame, or {@code null}. */
        public String source() {
            if (sourceClass == null) {
                return null;
            }
            String name = sourceClass.getName();
            int dot = name.lastIndexOf('.');
            return (dot < 0 ? name : name.substring(dot + 1))
                    + "." + (sourceMethod == null ? "?" : sourceMethod);
        }
    }

    /** One mod's share of the current frame, for the legend list. */
    public record Contributor(String modId, int rectangles) {
    }

    private static SBSConfig.OverlayInspectorSettings cfg() {
        return ConfigManager.getInstance().get().overlayInspector;
    }

    // ------------------------------------------------------------------ state

    public boolean isActive() {
        return active;
    }

    /** Called for every fresh in-world key press; toggles on the configured key. */
    public void onKeyPressed(int keyCode) {
        SBSConfig.OverlayInspectorSettings cfg = cfg();
        if (!cfg.enabled || cfg.toggleKey == -1 || keyCode != cfg.toggleKey) {
            return;
        }
        toggle();
    }

    public void toggle() {
        if (active) {
            deactivate();
        } else {
            activate();
        }
    }

    public void activate() {
        Minecraft minecraft = Minecraft.getInstance();
        if (active || minecraft.player == null) {
            return;
        }
        // Both before the first captured frame, on purpose: the index would otherwise be built
        // inside a draw call, and a button held at this moment could never report its release
        // (clicks are swallowed while inspecting) and would read as held down for the whole session.
        ModIndex.warmUp();
        KeyMapping.releaseAll();

        active = true;
        count = 0;
        hovered = null;
        cursorX = minecraft.getWindow().getGuiScaledWidth() / 2.0;
        cursorY = minecraft.getWindow().getGuiScaledHeight() / 2.0;
        if (cfg().announce) {
            SBSChat.send(Component.literal("Overlay Inspector ")
                    .withColor(SBSChat.WHITE)
                    .append(Component.literal("ON").withColor(0x57D977))
                    .append(Component.literal(" - point at an overlay, click to open its mod's "
                            + "settings, ESC to leave").withColor(0xFF8194B0)));
        }
    }

    public void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        count = 0;
        hovered = null;
        // A click swallowed while inspecting would otherwise leave the button stuck down.
        KeyMapping.releaseAll();
    }

    // ------------------------------------------------------------------ pointer

    /**
     * Feeds the mouse movement the camera did not get.
     *
     * <p>Deltas arrive in real screen pixels and the pointer lives in scaled GUI pixels, so they are
     * divided by the GUI scale – the pointer then covers the same physical distance as the system
     * cursor would, at any scale.
     */
    public void moveCursor(double rawDeltaX, double rawDeltaY) {
        Minecraft minecraft = Minecraft.getInstance();
        double scale = Math.max(1, minecraft.getWindow().getGuiScale());
        double speed = Math.max(10, cfg().pointerSpeed) / 100.0;
        cursorX += rawDeltaX * speed / scale;
        cursorY += rawDeltaY * speed / scale;
        clampCursor(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
    }

    private void clampCursor(int width, int height) {
        cursorX = Math.max(0, Math.min(width - 1, cursorX));
        cursorY = Math.max(0, Math.min(height - 1, cursorY));
    }

    public double cursorX() {
        return cursorX;
    }

    public double cursorY() {
        return cursorY;
    }

    public Hovered hovered() {
        return hovered;
    }

    // ------------------------------------------------------------------ capture

    /** True while the inspector's own overlay is being drawn, so the hook can skip those pixels. */
    public boolean isDrawingSelf() {
        return drawingSelf;
    }

    void beginSelfDraw() {
        drawingSelf = true;
    }

    void endSelfDraw() {
        drawingSelf = false;
    }

    /**
     * Records one drawn box and who drew it. Called from the pipeline hook for every element
     * submitted while the inspector is on.
     *
     * <p>Fully transparent geometry is dropped. It is common – spacers, zero-alpha fades, alerts
     * that keep drawing at alpha 0 while idle – and it covers real estate without putting a single
     * pixel on screen, so keeping it would let an invisible rectangle answer for everything drawn
     * underneath it.
     */
    public void record(ScreenRectangle bounds, int argb) {
        if (bounds == null || count >= MAX_RECORDS || (argb >>> 24) == 0) {
            return;
        }
        int left = bounds.left();
        int top = bounds.top();
        int right = bounds.right();
        int bottom = bounds.bottom();
        if (right <= left || bottom <= top) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // Off-screen geometry (scissored-away rows, elements parked outside the view) can still be
        // submitted, and would otherwise sit in the way of the hit test.
        if (right <= 0 || bottom <= 0
                || left >= minecraft.getWindow().getGuiScaledWidth()
                || top >= minecraft.getWindow().getGuiScaledHeight()) {
            return;
        }
        String owner = ModAttribution.currentOwner();
        int base = count * 4;
        rects[base] = left;
        rects[base + 1] = top;
        rects[base + 2] = right;
        rects[base + 3] = bottom;
        owners[count] = owner;
        sourceClasses[count] = ModAttribution.lastSourceClass();
        sourceMethods[count] = ModAttribution.lastSourceMethod();
        count++;
    }

    /** Drops the recorded frame. Called once the overlay for that frame has been drawn. */
    void endFrame() {
        count = 0;
    }

    // ------------------------------------------------------------------ hit testing

    /**
     * Resolves what the pointer is over in the frame just recorded, and remembers it for the click
     * handler. Returns {@code null} when the pointer is over bare world.
     *
     * <p>The smallest box containing the pointer wins. Elements are drawn as a stack of rectangles –
     * a plate, a frame, a bar, labels – and the smallest is the most specific thing under the
     * pointer; picking the largest (or the last drawn) would answer with a mod's backdrop whenever
     * one happens to span the screen.
     */
    Hovered resolveHover(int screenWidth, int screenHeight) {
        int pointerX = (int) cursorX;
        int pointerY = (int) cursorY;
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            if (pointerX < rects[base] || pointerX >= rects[base + 2]
                    || pointerY < rects[base + 1] || pointerY >= rects[base + 3]) {
                continue;
            }
            long area = (long) (rects[base + 2] - rects[base]) * (rects[base + 3] - rects[base + 1]);
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        hovered = best < 0 ? null : grow(best, screenWidth, screenHeight);
        logHover();
        return hovered;
    }

    /**
     * Logs each new answer once, with the code it came from.
     *
     * <p>Attribution is a judgement made from a call stack that no longer exists by the time anyone
     * doubts it. One line per distinct element makes a wrong answer reproducible instead of a
     * report that it "says the wrong mod" – which is untraceable on its own.
     */
    private void logHover() {
        if (!cfg().logHovers) {
            return;
        }
        String key = hovered == null ? null : hovered.modId() + "|" + hovered.source();
        if (key == null || key.equals(lastLoggedHover)) {
            return;
        }
        lastLoggedHover = key;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Inspector] {} ({}) via {} - box {}x{} at {},{}",
                ModIndex.displayName(hovered.modId()), hovered.modId(), hovered.source(),
                hovered.width(), hovered.height(), hovered.left(), hovered.top());
    }

    /**
     * Grows the hit rectangle into the whole element: every box from the same mod that touches the
     * growing outline (within {@link #CLUSTER_GAP}) joins it.
     *
     * <p>That gap is what turns a scatter of plate, border, icon and text rectangles into one
     * outline around the card they form, while still stopping at the edge of a neighbouring card the
     * same mod drew somewhere else on screen.
     */
    private Hovered grow(int seed, int screenWidth, int screenHeight) {
        int base = seed * 4;
        int left = rects[base];
        int top = rects[base + 1];
        int right = rects[base + 2];
        int bottom = rects[base + 3];
        String owner = owners[seed];

        long backdropArea = (long) (screenWidth * screenHeight * BACKDROP_FRACTION);
        boolean[] used = new boolean[count];
        used[seed] = true;

        for (int pass = 0; pass < CLUSTER_PASSES; pass++) {
            boolean changed = false;
            for (int i = 0; i < count; i++) {
                if (used[i] || !sameOwner(owners[i], owner)) {
                    continue;
                }
                int b = i * 4;
                long area = (long) (rects[b + 2] - rects[b]) * (rects[b + 3] - rects[b + 1]);
                if (area >= backdropArea) {
                    continue;
                }
                if (rects[b] > right + CLUSTER_GAP || rects[b + 2] < left - CLUSTER_GAP
                        || rects[b + 1] > bottom + CLUSTER_GAP || rects[b + 3] < top - CLUSTER_GAP) {
                    continue;
                }
                left = Math.min(left, rects[b]);
                top = Math.min(top, rects[b + 1]);
                right = Math.max(right, rects[b + 2]);
                bottom = Math.max(bottom, rects[b + 3]);
                used[i] = true;
                changed = true;
            }
            if (!changed) {
                break;
            }
        }
        return new Hovered(owner, left, top, right, bottom,
                sourceClasses[seed], sourceMethods[seed]);
    }

    private static boolean sameOwner(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Every mod that drew something this frame, busiest first – the legend list. */
    List<Contributor> contributors() {
        Map<String, Integer> tally = new HashMap<>();
        for (int i = 0; i < count; i++) {
            tally.merge(owners[i] == null ? ModIndex.UNKNOWN : owners[i], 1, Integer::sum);
        }
        List<Contributor> list = new ArrayList<>(tally.size());
        for (Map.Entry<String, Integer> entry : tally.entrySet()) {
            list.add(new Contributor(entry.getKey(), entry.getValue()));
        }
        list.sort(Comparator.comparingInt(Contributor::rectangles).reversed());
        return list;
    }

    // ------------------------------------------------------------------ clicks

    /**
     * Acts on a click at the pointer. Left opens the hovered mod's settings, right copies its name.
     * Returns {@code true} when the click was consumed.
     */
    public boolean onClick(int button) {
        if (!active) {
            return false;
        }
        // The toggle gets out of its own way. Every click is swallowed while the pointer is up, so a
        // toggle bound to a mouse button would switch the inspector on and then have no way to
        // switch it off - the one bind that has to survive the swallowing.
        int toggle = cfg().toggleKey;
        if (toggle > 0 && toggle == sbs.modid.client.core.keybind.Keys.ofMouseButton(button)) {
            deactivate();
            return true;
        }
        Hovered target = hovered;
        if (target == null) {
            return true; // still swallowed: a click in inspect mode must never swing or place
        }
        if (button == 1) {
            copyToClipboard(target.modId());
            return true;
        }
        if (button != 0) {
            return true;
        }
        if (ModIndex.MINECRAFT.equals(target.modId())) {
            SBSChat.send(Component.literal("That is drawn by Minecraft itself, not by a mod")
                    .withColor(0xFF8194B0));
            return true;
        }
        ModIndex.ModInfo info = ModIndex.info(target.modId());
        if (info == null) {
            SBSChat.send(Component.literal("Could not tell which mod drew that")
                    .withColor(0xFF8194B0));
            return true;
        }
        deactivate();
        ModConfigOpener.open(info);
        return true;
    }

    private static void copyToClipboard(String modId) {
        ModIndex.ModInfo info = ModIndex.info(modId);
        String text = info == null ? modId : info.name() + " (" + info.id() + " " + info.version() + ")";
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        SBSChat.send(Component.literal("Copied ")
                .withColor(0xFF8194B0)
                .append(Component.literal(text).withColor(SBSChat.WHITE)));
    }
}
