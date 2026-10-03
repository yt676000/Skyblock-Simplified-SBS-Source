/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.component.DataComponents;
import sbs.modid.client.core.api.events.GuiChangedEvent;
import sbs.modid.client.core.api.events.GuiClosedEvent;
import sbs.modid.client.core.api.events.GuiEvents;
import sbs.modid.client.core.api.events.GuiOpenedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures the <b>real</b> open Minecraft screen / container once per client tick and
 * publishes an immutable {@link GuiState} snapshot.
 *
 * <p>It reads {@code AbstractContainerScreen.getMenu()} – so it works for every
 * container screen (chest, generic container, player inventory, merchant, anvil,
 * crafting, beacon, and any future {@code AbstractContainerScreen} subclass), on any
 * server including Hypixel. It is <i>not</i> tied to the mod's own overlays.
 *
 * <p>Threading: {@link #setCurrentScreen(Screen)} and {@link #tick(Minecraft)} run on
 * the client thread (from {@code GuiTrackingMixin}); the snapshot is published via an
 * {@link AtomicReference} that the HTTP server reads. The HTTP side never touches
 * Minecraft objects, so there is no render-thread load.
 */
public final class GuiStateManager {

    private static final GuiStateManager INSTANCE = new GuiStateManager();

    public static GuiStateManager getInstance() {
        return INSTANCE;
    }

    private volatile Screen currentScreen;
    private final AtomicReference<GuiState> state = new AtomicReference<>(GuiState.empty());

    private String previousScreenClass;
    private String previousScreenTitle;

    private GuiStateManager() {
    }

    public void setCurrentScreen(Screen screen) {
        this.currentScreen = screen;
    }

    /**
     * The screen currently shown – the version-independent replacement for
     * {@code minecraft.gui.screen()} (field on 26.1.2, method on 26.2). Reads the live value via
     * {@link ScreenAccess}; the {@code setScreenAndShow}-tracked field is only the fallback,
     * because 26.1.2 also sets screens through the untracked {@code setScreen}.
     */
    public Screen getCurrentScreen() {
        if (ScreenAccess.available()) {
            return ScreenAccess.current(); // authoritative, including null (= no screen open)
        }
        return currentScreen;
    }

    public GuiState getState() {
        return state.get();
    }

    public void tick(Minecraft minecraft) {
        try {
            GuiState snapshot = capture(minecraft);
            state.set(snapshot);
            detectEvents(snapshot);
        } catch (Throwable t) {
            // Never let API capture break the game tick.
        }
    }

    private GuiState capture(Minecraft minecraft) {
        long timestamp = System.currentTimeMillis() / 1000L;
        String player = minecraft.player != null ? minecraft.player.getName().getString() : null;
        boolean worldLoaded = minecraft.level != null;
        int fps = minecraft.getFps();

        Screen screen = getCurrentScreen();
        if (screen == null) {
            return new GuiState(false, null, null, 0, 0, List.of(), player, worldLoaded, fps, timestamp);
        }

        String screenClass = screen.getClass().getSimpleName();
        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";

        int rows = 0;
        int slotCount = 0;
        List<GuiState.SlotInfo> slots = List.of();

        // Real container data – generic over every AbstractContainerScreen..
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            slotCount = menu.getItems().size();
            // rows only makes sense for chest-type menus.
            if (menu instanceof ChestMenu chestMenu) {
                rows = chestMenu.getRowCount();
            }
            List<GuiState.SlotInfo> collected = new ArrayList<>(slotCount);
            for (int i = 0; i < slotCount; i++) {
                Slot slot = menu.getSlot(i);
                ItemStack stack = slot.getItem();
                boolean empty = stack == null || stack.isEmpty();
                if (empty) {
                    collected.add(new GuiState.SlotInfo(i, slot.x, slot.y, null, 0, null, null, List.of(), true));
                } else {
                    collected.add(new GuiState.SlotInfo(
                            i, slot.x, slot.y,
                            stack.getHoverName().getString(),
                            stack.getCount(),
                            itemId(stack),
                            rarity(stack),
                            extractLore(stack),
                            false));
                }
            }
            slots = List.copyOf(collected);
        }

        return new GuiState(true, screenClass, title, rows, slotCount, slots, player, worldLoaded, fps, timestamp);
    }

    /** Registry id, e.g. {@code minecraft:diamond}. */
    private static String itemId(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Rarity name, e.g. {@code COMMON} / {@code RARE}. */
    private static String rarity(ItemStack stack) {
        try {
            Rarity rarity = stack.getRarity();
            return rarity != null ? rarity.name() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lore lines from the LORE data component (best effort). */
    private static List<String> extractLore(ItemStack stack) {
        try {
            Object component = stack.get(DataComponents.LORE);
            if (component instanceof ItemLore lore) {
                List<String> lines = new ArrayList<>();
                for (Object line : lore.lines()) {
                    if (line instanceof Component text) {
                        lines.add(text.getString());
                    }
                }
                return List.copyOf(lines);
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    private void detectEvents(GuiState snapshot) {
        String currentClass = snapshot.isOpen() ? snapshot.screenClass() : null;
        String currentTitle = snapshot.isOpen() ? snapshot.title() : null;
        boolean wasOpen = previousScreenClass != null;
        boolean isOpen = currentClass != null;

        if (!wasOpen && isOpen) {
            GuiEvents.post(new GuiOpenedEvent(currentClass, currentTitle));
        } else if (wasOpen && !isOpen) {
            GuiEvents.post(new GuiClosedEvent(previousScreenClass, previousScreenTitle));
        } else if (wasOpen && isOpen
                && (!Objects.equals(currentClass, previousScreenClass)
                || !Objects.equals(currentTitle, previousScreenTitle))) {
            GuiEvents.post(new GuiChangedEvent(previousScreenClass, previousScreenTitle, currentClass, currentTitle));
        }

        previousScreenClass = currentClass;
        previousScreenTitle = currentTitle;
    }
}
