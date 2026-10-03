/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.Locale;

/**
 * Leap Menu (Spirit Leap): colours each teammate's slot by their dungeon class and adds a <b>per-class
 * slot keybind</b> - while the Spirit Leap menu is open, pressing the class key clicks that class's
 * player. This is a slot hotkey, not automation: the menu must already be open and you press the key,
 * exactly like the existing Slot Hotkeys module. It never opens the menu or leaps on its own.
 *
 * <p>Player → class comes from {@link DungeonTeamClasses}; the slot's player name is read from the head
 * item's display name. Fed from the shared container key/render hooks.
 */
public final class LeapMenu {

    private static final LeapMenu INSTANCE = new LeapMenu();

    private static final String SECTION = String.valueOf((char) 0x00A7);

    private LeapMenu() {
    }

    public static LeapMenu getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** True while a Spirit Leap menu is the open screen. */
    private static boolean isLeapMenu(AbstractContainerScreen<?> screen) {
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString().replaceAll(SECTION + ".", "");
        return title.toLowerCase(Locale.ROOT).contains("spirit leap");
    }

    /** The class letter a pressed key is bound to, or 0. */
    private static char classForKey(int key) {
        SBSConfig.DungeonsSettings cfg = cfg();
        if (key == 0) {
            return 0;
        }
        if (key == cfg.leapMageKey) {
            return 'M';
        }
        if (key == cfg.leapArcherKey) {
            return 'A';
        }
        if (key == cfg.leapBerserkKey) {
            return 'B';
        }
        if (key == cfg.leapTankKey) {
            return 'T';
        }
        if (key == cfg.leapHealerKey) {
            return 'H';
        }
        return 0;
    }

    /**
     * Handles a key press while a container is open (from {@code SlotHotkeyMixin}). When the Spirit
     * Leap menu is open and the key is a bound class key, clicks the slot of that class's player.
     * Returns {@code true} when it consumed the key.
     */
    public boolean handleKey(AbstractContainerScreen<?> screen, int key) {
        if (!cfg().leapMenuEnabled || !isLeapMenu(screen)) {
            return false;
        }
        char want = classForKey(key);
        if (want == 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            String player = playerOf(menu.getSlot(i).getItem());
            if (player != null && DungeonTeamClasses.classOf(player) == want) {
                // A plain left-click = clicking the head, i.e. the leap. Same call the Slot Hotkeys
                // module uses; the server turns it into the leap action.
                mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.PICKUP, mc.player);
                return true;
            }
        }
        return false; // no teammate of that class in the menu - leave the key for vanilla
    }

    /** Draws a class-colour outline + class letter on each teammate's slot in the Spirit Leap menu. */
    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!cfg().leapMenuEnabled || !isLeapMenu(screen)) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        Font font = Minecraft.getInstance().font;
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            Slot slot = menu.getSlot(i);
            String player = playerOf(slot.getItem());
            if (player == null) {
                continue;
            }
            char cls = DungeonTeamClasses.classOf(player);
            if (cls == 0) {
                continue;
            }
            int color = DungeonTeamClasses.colorOf(player, 0xFFFFFFFF);
            int x = left + slot.x;
            int y = top + slot.y;
            outline(g, x, y, color);
            g.text(font, Component.literal(String.valueOf(cls)), x + 1, y + 1, color);
        }
    }

    /** The player name a leap-menu head belongs to (its display name), or null when not a head. */
    private static String playerOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("player_head")) {
            return null;
        }
        String name = stack.getHoverName().getString().replaceAll(SECTION + ".", "").trim();
        return name.isEmpty() ? null : name;
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 17, y + 1, color);
        g.fill(x - 1, y + 15, x + 17, y + 17, color);
        g.fill(x - 1, y - 1, x + 1, y + 17, color);
        g.fill(x + 15, y - 1, x + 17, y + 17, color);
    }
}
