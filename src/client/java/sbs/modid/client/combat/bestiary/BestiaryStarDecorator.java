/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The ★ on every mob of a Bestiary island page ("Bestiary ➜ Hub"): filled gold when the mob is
 * pinned to the HUD, a dim outline star when not. A click on the star toggles the pin and is
 * consumed, so Hypixel never sees it; the rest of the slot still opens the mob's page as usual.
 * Shift+Right-click anywhere on the mob item does the same, and is consumed too.
 *
 * <p>Which slots are mobs comes from {@link BestiaryTracker}'s menu scan, so the draw path never
 * parses lore - it reads a map the tick already filled.
 */
public final class BestiaryStarDecorator implements SlotDecorator {

    /** The star's hit box, slot-relative: the top-right corner of the 16x16 item. */
    private static final int STAR_X = 9;
    private static final int STAR_Y = -1;
    private static final int STAR_SIZE = 8;

    private static final int PINNED = 0xFFFFD24A;
    private static final int UNPINNED = 0xB0A0A0A0;

    /** GLFW's shift modifier bit. */
    private static final int MOD_SHIFT = 0x0001;

    /** ServiceLoader needs a public no-arg constructor. */
    public BestiaryStarDecorator() {
    }

    @Override
    public String id() {
        return "bestiary_star";
    }

    @Override
    public int order() {
        return 430; // an icon over the item's corner; nothing else draws on Bestiary pages
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(frame != null && isIslandPage(frame.title()));
    }

    /**
     * An island page lists mobs: "Bestiary ➜ Hub". Not the root "Bestiary" (islands, not mobs), not
     * "Bestiary Milestones", and not a mob's own page ("Hub ➜ Zombie", no "Bestiary" in it).
     */
    static boolean isIslandPage(String title) {
        String lower = BestiaryTracker.strip(title).trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("bestiary") && !lower.equals("bestiary") && !lower.contains("milestone");
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().bestiary.enabled;
    }

    private static Map<Integer, String> stars(AbstractContainerScreen<?> screen) {
        if (!enabled()) {
            return Map.of();
        }
        return BestiaryTracker.getInstance().starSlots(screen.getMenu().containerId);
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Map<Integer, String> stars = stars(screen);
        if (stars.isEmpty()) {
            return;
        }
        var font = Minecraft.getInstance().font;
        AbstractContainerScreenAccessor access = (AbstractContainerScreenAccessor) screen;
        double localX = mouseX - access.skyblockSimplified$leftPos();
        double localY = mouseY - access.skyblockSimplified$topPos();
        BestiaryTracker tracker = BestiaryTracker.getInstance();
        for (Map.Entry<Integer, String> e : stars.entrySet()) {
            if (e.getKey() >= screen.getMenu().slots.size()) {
                continue;
            }
            Slot slot = screen.getMenu().getSlot(e.getKey());
            boolean pinned = tracker.isPinned(e.getValue());
            g.text(font, pinned ? STAR_FILLED : STAR_EMPTY, slot.x + STAR_X, slot.y + STAR_Y,
                    pinned ? PINNED : UNPINNED, true);
            if (onStar(slot, localX, localY)) {
                // Set before vanilla queues the item's own tooltip, which does not replace an existing one.
                g.setTooltipForNextFrame(font, tooltip(e.getValue(), pinned), Optional.empty(),
                        mouseX, mouseY, SBSTheme.tooltipStyle());
            }
        }
    }

    private static final Component STAR_FILLED = Component.literal("★");
    private static final Component STAR_EMPTY = Component.literal("☆");

    /** The hovered star's tooltip, rebuilt only when the hovered mob or its pin changes (render thread only). */
    private static String tipName;
    private static boolean tipPinned;
    private static List<Component> tip = List.of();

    private static List<Component> tooltip(String name, boolean pinned) {
        if (!name.equals(tipName) || pinned != tipPinned) {
            tipName = name;
            tipPinned = pinned;
            tip = List.of(Component.literal(pinned ? "Unpin " + name + " from the HUD" : "Pin " + name + " to the HUD"),
                    Component.literal("Shift+Right-click the mob does the same"));
        }
        return tip;
    }

    private static boolean onStar(Slot slot, double x, double y) {
        return x >= slot.x + STAR_X && x < slot.x + STAR_X + STAR_SIZE
                && y >= slot.y + STAR_Y && y < slot.y + STAR_Y + STAR_SIZE;
    }

    /**
     * Toggles the pin when the star (any button) or the mob (Shift+Right-click) was clicked.
     *
     * @return whether the click was ours - the caller then cancels it so it never reaches the slot
     */
    public static boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        Map<Integer, String> stars = stars(screen);
        if (stars.isEmpty()) {
            return false;
        }
        AbstractContainerScreenAccessor access = (AbstractContainerScreenAccessor) screen;
        double x = event.x() - access.skyblockSimplified$leftPos();
        double y = event.y() - access.skyblockSimplified$topPos();
        boolean shiftRight = event.button() == 1 && (event.modifiers() & MOD_SHIFT) != 0;
        for (Map.Entry<Integer, String> e : stars.entrySet()) {
            if (e.getKey() >= screen.getMenu().slots.size()) {
                continue;
            }
            Slot slot = screen.getMenu().getSlot(e.getKey());
            boolean onItem = x >= slot.x - 1 && x < slot.x + 17 && y >= slot.y - 1 && y < slot.y + 17;
            if (onStar(slot, x, y) || (shiftRight && onItem)) {
                if (event.button() == 0 || shiftRight) {
                    BestiaryTracker.getInstance().togglePin(e.getValue());
                }
                return true;
            }
        }
        return false;
    }
}
