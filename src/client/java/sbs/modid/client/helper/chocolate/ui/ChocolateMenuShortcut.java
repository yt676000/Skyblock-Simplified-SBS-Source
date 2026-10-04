/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.chocolate.logic.ChocolateStore;
import sbs.modid.client.helper.chocolate.logic.MenuShortcut;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Chocolate Factory button on a free slot of Hypixel's {@code SkyBlock Menu}: one click sends the
 * command that opens the factory, and its tooltip shows the numbers from the last factory reading.
 *
 * <p><b>Why a click that sends a command is allowed here.</b> The player presses the button; the mod
 * sends exactly one command in answer, at most once a second, and nothing else. It is the same
 * thing as typing the command, it touches nothing inside the factory, and no click is ever made for
 * the player - the factory itself stays strictly read-only, as {@code ChocolateFactory} describes.
 * The Equipment column's {@code /equipment} click is the precedent.
 *
 * <p><b>Drawn only over a filler pane.</b> The target slot is checked every frame: if Hypixel ever
 * puts a real item there, the button disappears, clicks reach that item as normal, and the change
 * is logged once. Hiding a server button under ours would be the worse failure.
 *
 * <p>Gated on the exact title first, so every other container pays one cached string compare.
 */
public final class ChocolateMenuShortcut implements SlotDecorator {

    /** How long after a click the next menu is still attributed to it, for the log line. */
    private static final long OPENED_LOG_WINDOW_MS = 5_000L;

    private static final MenuShortcut.Cooldown COOLDOWN = new MenuShortcut.Cooldown();

    /** Built lazily: no {@code ItemStack} before a level exists. */
    private static ItemStack icon;

    /** The SkyBlock Menu that was clicked, until the log line about what opened next is written. */
    private static AbstractContainerScreen<?> clickedOn;
    private static long clickedAt;

    /** Whether the "a real item took our slot" line has been written this session. */
    private static boolean occupiedLogged;

    /** ServiceLoader needs a public no-arg constructor. */
    public ChocolateMenuShortcut() {
    }

    private static SBSConfig.ChocolateFactorySettings cfg() {
        return ConfigManager.getInstance().get().chocolateFactory;
    }

    @Override
    public String id() {
        return "chocolate_menu_shortcut";
    }

    @Override
    public int order() {
        return 420; // an icon on its own slot of its own menu; nothing else draws there
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(isSkyBlockMenu(frame));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        if (clickedOn != null) {
            reportWhatOpened(screen);
        }
        Slot slot = targetSlot(screen);
        if (slot == null) {
            return;
        }
        SlotDecorations.box(g, slot, 0xFF8B8B8B, 0xC0A9714B); // covers the pane; the module's accent
        g.item(icon(), slot.x, slot.y);

        AbstractContainerScreenAccessor access = (AbstractContainerScreenAccessor) screen;
        if (access.skyblockSimplified$hoveredSlot() == slot) {
            var font = Minecraft.getInstance().font;
            List<String> lines = MenuShortcut.tooltip(ChocolateStore.getInstance().snapshot(),
                    cfg().enabled, System.currentTimeMillis(), NumberDisplay::format);
            List<Component> tip = new ArrayList<>(lines.size());
            for (String line : lines) {
                tip.add(Component.literal(line));
            }
            // Set before vanilla queues the pane's own tooltip, which does not replace an existing one.
            g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    /**
     * Sends the factory command when the button was clicked. Every mouse button on the button is
     * consumed, so no click ever reaches the pane underneath; only the left button sends.
     *
     * @return whether the click was the button's
     */
    public static boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        Slot slot = targetSlot(screen);
        if (slot == null) {
            return false;
        }
        AbstractContainerScreenAccessor access = (AbstractContainerScreenAccessor) screen;
        double x = event.x() - access.skyblockSimplified$leftPos();
        double y = event.y() - access.skyblockSimplified$topPos();
        if (x < slot.x - 1 || x >= slot.x + 17 || y < slot.y - 1 || y >= slot.y + 17) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        if (event.button() == 0 && player != null && COOLDOWN.tryFire(System.currentTimeMillis())) {
            player.connection.sendCommand(MenuShortcut.COMMAND);
            clickedOn = screen;
            clickedAt = System.currentTimeMillis();
        }
        return true;
    }

    /** The slot the button sits on, or {@code null} when it is not shown on this screen. */
    private static Slot targetSlot(AbstractContainerScreen<?> screen) {
        if (!cfg().menuShortcut || !isSkyBlockMenu(MenuFrame.of(screen))) {
            return null;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int index = MenuShortcut.resolveSlot(cfg().menuShortcutSlot);
        if (index >= menu.slots.size()) {
            return null;
        }
        Slot slot = menu.getSlot(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return null; // the menu's contents have not arrived yet
        }
        if (!isFiller(stack)) {
            if (!occupiedLogged) {
                occupiedLogged = true;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chocolate] SkyBlock Menu slot {} holds '{}', "
                        + "not a filler pane - shortcut hidden there", index,
                        PlainText.strip(stack.getHoverName().getString()));
            }
            return null;
        }
        return slot;
    }

    private static boolean isFiller(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return MenuShortcut.isFiller(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                PlainText.strip(stack.getHoverName().getString()),
                lore == null ? 0 : lore.lines().size());
    }

    /** The one screen test, asked by the tier, the draw and the click so they cannot disagree. */
    private static boolean isSkyBlockMenu(MenuFrame frame) {
        return frame != null && MenuShortcut.TITLE.equals(frame.normalised());
    }

    /**
     * Logs the first menu that opens after a click, once. The command is not yet confirmed in game,
     * and this line is what confirms it - or shows the menu never changed.
     */
    private static void reportWhatOpened(AbstractContainerScreen<?> screen) {
        if (screen != clickedOn || screen.getMenu().containerId != clickedOn.getMenu().containerId) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chocolate] shortcut /{} -> {}", MenuShortcut.COMMAND,
                    PlainText.strip(MenuFrame.of(screen).title()));
            clickedOn = null;
        } else if (System.currentTimeMillis() - clickedAt > OPENED_LOG_WINDOW_MS) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chocolate] shortcut /{} -> no new menu within {} s",
                    MenuShortcut.COMMAND, OPENED_LOG_WINDOW_MS / 1000);
            clickedOn = null;
        }
    }

    private static ItemStack icon() {
        if (icon == null) {
            icon = new ItemStack(Items.COCOA_BEANS);
        }
        return icon;
    }
}
