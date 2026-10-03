/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The one place anything is drawn over a container screen's slots.
 *
 * <p><b>Why this exists.</b> Five separate mixins used to inject at the tail of
 * {@code extractSlots} - seven injections into one method - each re-deriving slot geometry and each
 * carrying its own copy of the same fill-and-outline. That is one mechanism implemented five times,
 * and it cost what duplication always costs: the copies disagreed. One of them added
 * {@code leftPos}/{@code topPos} to coordinates that were already GUI-relative, drawing a whole GUI
 * off to the side, and its comment asserted the opposite of what vanilla does. Nothing caught it,
 * because there was no single definition of "where a slot decoration goes" to be wrong about.
 *
 * <p><b>The coordinate space, stated once.</b> {@code AbstractContainerScreen.extractContents}
 * pushes the pose and translates it to {@code leftPos}/{@code topPos} <i>before</i> calling
 * {@code extractSlots}; vanilla's own {@code extractSlot} then reads {@code slot.x}/{@code slot.y}
 * with no origin added. Slot-relative is therefore not a convention this class chose - it is the
 * space the method runs in. {@link #box} is the only geometry any decorator should need.
 *
 * <p><b>One decorator cannot take the others down.</b> This runs every frame on every container, so
 * a decorator that throws would otherwise throw thousands of times and take the whole slot layer
 * with it each time. A throwing decorator is logged once, by id, and then dropped for the rest of
 * the session: the failure is visible and bounded, and every other feature keeps drawing.
 */
public final class SlotDecorations {

    /**
     * The plain "this slot is the one you asked about" wash, shared by the decorations that have no
     * meaning to encode in colour - the API's own highlights and the recipe search. Features whose
     * colour <i>carries</i> information (Bazaar order status, Bits Shop ranking) bring their own from
     * the theme instead; these two only need to be visible.
     */
    public static final int HIGHLIGHT_FILL = 0x553FB4FF;
    public static final int HIGHLIGHT_FRAME = 0xFFFFFFFF;

    /** Loaded once per session from the service list; {@code null} until the first draw. */
    private static List<SlotDecorator> decorators;

    /** Decorators that threw and have been dropped for the session. */
    private static final Set<String> disabled = new HashSet<>();

    private SlotDecorations() {
    }

    /**
     * Draws every registered decoration, in {@link SlotDecorator#order()} order.
     *
     * <p>Called from exactly one mixin. Decorators still do their own gating; what this adds is the
     * chance to skip that gate altogether. Each pass goes through {@link MenuRenderPriority} with
     * the tier the decorator declares for the open menu, so on a frame the mod is over budget the
     * decorations belonging to some other screen cost nothing at all rather than costing a title
     * read and a slot walk each. Nothing is skipped while the frame is within budget.
     */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (screen == null || g == null) {
            return;
        }
        MenuFrame frame = MenuFrame.of(screen);
        for (SlotDecorator decorator : all()) {
            if (disabled.contains(decorator.id())) {
                continue;
            }
            try {
                MenuRenderPriority.run(decorator.id(), decorator.tier(frame),
                        () -> decorator.decorate(screen, g, mouseX, mouseY));
            } catch (Throwable t) {
                disabled.add(decorator.id());
                SkyblockSimplifiedSBS.LOGGER.error(
                        "[SBS][Slots] decorator '{}' threw and is disabled for this session - "
                                + "its decoration will not draw again until the game is restarted",
                        decorator.id(), t);
            }
        }
    }

    /**
     * Fills a slot and outlines it, in the slot-relative space {@code extractSlots} runs in.
     *
     * <p>The 16x16 fill covers the item; the outline sits one pixel outside it, which is what makes
     * a decorated slot read as decorated rather than as an item with an odd background. Pass
     * {@code 0} for either colour to skip that half.
     */
    public static void box(GuiGraphicsExtractor g, Slot slot, int fill, int frame) {
        if (slot == null) {
            return;
        }
        box(g, slot.x, slot.y, fill, frame);
    }

    /** {@link #box(GuiGraphicsExtractor, Slot, int, int)} for a slot position already in hand. */
    public static void box(GuiGraphicsExtractor g, int x, int y, int fill, int frame) {
        if (fill != 0) {
            g.fill(x, y, x + 16, y + 16, fill);
        }
        if (frame != 0) {
            g.outline(x - 1, y - 1, 18, 18, frame);
        }
    }

    private static synchronized List<SlotDecorator> all() {
        if (decorators != null) {
            return decorators;
        }
        List<SlotDecorator> loaded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (SlotDecorator decorator : ServiceLoader.load(SlotDecorator.class,
                SlotDecorations.class.getClassLoader())) {
            String id = decorator.id();
            if (id == null || id.isBlank()) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Slots] {} declares no id - dropped.",
                        decorator.getClass().getName());
                continue;
            }
            if (!ids.add(id)) {
                // First wins, deterministically, and the collision is named - two decorators sharing
                // an id would share a disable entry, so one throwing would silence the other.
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Slots] duplicate decorator id '{}' from {} - dropped, the first keeps it.",
                        id, decorator.getClass().getName());
                continue;
            }
            loaded.add(decorator);
        }
        loaded.sort(Comparator.comparingInt(SlotDecorator::order).thenComparing(SlotDecorator::id));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Slots] {} slot decorator(s): {}",
                loaded.size(), loaded.stream().map(SlotDecorator::id).toList());
        decorators = loaded;
        return decorators;
    }
}
