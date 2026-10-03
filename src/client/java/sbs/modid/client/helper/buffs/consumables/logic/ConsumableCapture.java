/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The evidence half of Consumable Timers: logs, under {@code [SBS][Consumables]}, each distinct
 * value once, from the three places that could still change what the timers know -
 * <ol>
 *   <li>{@code tab footer:} the full footer text, {@code §} codes kept, whenever it changes;</li>
 *   <li>{@code menu:} every slot (name and lore) of the Active Effects menu while the player has it
 *       open - the menu has never been captured, see docs/skyblock-ui/menus.md;</li>
 *   <li>{@code use:} the SkyBlock id, name and time-bearing lore lines of a right-clicked item - how
 *       a consumable nobody has listed yet shows up.</li>
 * </ol>
 * It reads only what is already on screen or in hand. It never opens the menu, sends a command or
 * clicks; the menu is read only when the player opened it.
 */
public final class ConsumableCapture {

    private static final ConsumableCapture INSTANCE = new ConsumableCapture();

    /** How many distinct values are remembered before the oldest are forgotten. */
    private static final int MAX_SEEN = 512;
    private static final long FOOTER_INTERVAL_MS = 2_000L;
    /** Ticks the menu's state must hold still before it is read (slots arrive in several packets). */
    private static final int SETTLE_TICKS = 5;

    /** One used item, as far as the timers care. */
    public record Use(String id, String name, List<String> timeLines) {
    }

    private final Set<String> seen = new LinkedHashSet<>();
    private long footerReadAt;
    private String lastFooter = "";
    private AbstractContainerScreen<?> lastMenu;
    private int lastMenuState = -1;
    private int stableTicks;
    private boolean menuRead;

    private ConsumableCapture() {
    }

    public static ConsumableCapture getInstance() {
        return INSTANCE;
    }

    /** Called every client tick from {@code BuffTracker}; throttles itself. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - footerReadAt >= FOOTER_INTERVAL_MS) {
            footerReadAt = now;
            String footer = TabWidgets.footerRaw();
            if (!footer.equals(lastFooter)) {
                lastFooter = footer;
                if (!footer.isBlank()) {
                    logOnce("tab footer", footer.replace("\n", " | "));
                }
            }
        }
        readMenu();
    }

    private void readMenu() {
        if (!(GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen)) {
            lastMenu = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        if (screen != lastMenu || state != lastMenuState) {
            lastMenu = screen;
            lastMenuState = state;
            stableTicks = 0;
            menuRead = false;
            return;
        }
        if (menuRead || ++stableTicks < SETTLE_TICKS) {
            return;
        }
        menuRead = true;
        String title = MenuFrame.of(screen).normalised();
        if (!EffectsMenu.isActiveEffectsMenu(title)) {
            return;
        }
        List<EffectsMenu.Slot> slots = new ArrayList<>();
        List<Slot> menuSlots = screen.getMenu().slots;
        for (int i = 0; i < menuSlots.size(); i++) {
            Slot slot = menuSlots.get(i);
            ItemStack stack = slot.getItem();
            if (slot.container instanceof Inventory || stack.isEmpty()) {
                continue;
            }
            String name = PlainText.strip(stack.getHoverName().getString()).trim();
            if (name.isEmpty()) {
                continue;
            }
            List<String> lore = lore(stack);
            slots.add(new EffectsMenu.Slot(name, lore));
            logOnce("menu", "'" + title + "' slot " + i + " '" + name + "' lore=" + lore);
        }
        ConsumableStore.getInstance().onMenu(EffectsMenu.parse(title, slots));
    }

    /**
     * Reads a right-clicked item and logs it when its lore mentions a time. Runs on the client
     * thread from the item-use hook, which is where the stack may be read.
     */
    public Use onItemUse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        List<String> timeLines = new ArrayList<>();
        for (String line : lore(stack)) {
            if (ConsumableLines.mentionsTime(line)) {
                timeLines.add(line);
            }
        }
        if (timeLines.isEmpty()) {
            return null;
        }
        String id = SkyblockItem.id(stack);
        String name = PlainText.strip(stack.getHoverName().getString()).trim();
        logOnce("use", (id == null ? "?" : id) + " '" + name + "' " + timeLines);
        return new Use(id, name, timeLines);
    }

    private static List<String> lore(ItemStack stack) {
        List<String> lore = new ArrayList<>();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) {
                lore.add(PlainText.strip(line.getString()));
            }
        }
        return lore;
    }

    /** Logs {@code value} the first time it is seen; also used by the store for unparsed lines. */
    synchronized void logOnce(String source, String value) {
        String key = source + "|" + value;
        if (!seen.add(key)) {
            return;
        }
        if (seen.size() > MAX_SEEN) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Consumables] {}: {}", source, value);
    }
}
