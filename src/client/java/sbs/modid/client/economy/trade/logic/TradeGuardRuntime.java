/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.audio.SbsSounds;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.trade.model.TradeSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Minecraft side of Trade Guard: reads the open trade menu when it changes, hands the plain
 * snapshot to {@link TradeGuard}, and turns its answers into chat lines, a sound, tooltip lines and
 * the accept-click veto. Client thread only.
 *
 * <p>It never clicks and never sends a packet. The only thing it does to a trade is refuse my own
 * click on the accept button while a lock runs, and Shift (or a shift-click) always gets through.
 */
public final class TradeGuardRuntime {

    private static final TradeGuardRuntime INSTANCE = new TradeGuardRuntime();
    private static final int KEY_LSHIFT = 340;
    private static final int KEY_RSHIFT = 344;
    private static final int MENU_SLOTS = 45;

    /** Sender of a player chat line: "[285] [MVP+] Name: ", "Party > Name: ", "From [VIP] Name: ". */
    private static final Pattern SENDER = Pattern.compile(
            "^(?:(?:Party|Guild|Co-op|Officer|From|To)\\s*>?\\s*)?(?:\\[[^\\]]+]\\s*)*([A-Za-z0-9_]{3,16})(?:\\s*\\[[^\\]]+])?\\s*:");

    private final TradeGuard guard = new TradeGuard(new PartnerMemory());
    private AbstractContainerScreen<?> screen;
    private int lastState = -1;
    private long lastCueAt;

    private TradeGuardRuntime() {
    }

    public static TradeGuardRuntime getInstance() {
        return INSTANCE;
    }

    public TradeGuard guard() {
        return guard;
    }

    private static SBSConfig.TradeGuardSettings cfg() {
        return ConfigManager.getInstance().get().tradeGuard;
    }

    public static TradeGuard.Settings settings() {
        SBSConfig.TradeGuardSettings c = cfg();
        return new TradeGuard.Settings(c.thresholdPct, c.acceptLock, Math.max(0, c.lockSeconds) * 1000L,
                c.warnAnyChangeAfterConfirm, Math.max(1, c.memoryMinutes) * 60_000L);
    }

    /** Whether a trade menu is open and being watched. */
    public boolean active(AbstractContainerScreen<?> s) {
        return s != null && s == screen && guard.partner() != null;
    }

    // ------------------------------------------------------------------ tick

    /** Client tick, container screens only. Re-reads the menu only when its state id moved. */
    public void onClientTick() {
        if (!cfg().enabled || !(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> s)) {
            reset();
            return;
        }
        int state = s.getMenu().getStateId();
        if (s == screen && state == lastState) {
            return;
        }
        String title = PlainText.strip(s.getTitle().getString());
        if (TradeLayout.partner(title) == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        List<TradeSlot> slots = new ArrayList<>();
        for (Slot slot : s.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.index >= MENU_SLOTS) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            slots.add(TradeReader.read(slot.index, stack, offerSlot(slot.index), now));
        }
        TradeLayout.Menu menu = TradeLayout.parse(title, slots);
        if (menu == null) {
            return;   // items not in yet - try again on the next state change
        }
        if (s != screen) {
            guard.close();
        }
        screen = s;
        lastState = state;
        handle(guard.update(menu, settings(), now));
    }

    private void reset() {
        if (screen != null) {
            screen = null;
            lastState = -1;
            guard.close();
        }
    }

    private static boolean offerSlot(int index) {
        int col = index % 9;
        return index < 36 && col != 4;
    }

    private void handle(List<TradeGuard.Alert> alerts) {
        boolean cue = false;
        Player player = Minecraft.getInstance().player;
        for (TradeGuard.Alert a : alerts) {
            if (a.kind() == TradeGuard.Kind.UNKNOWN_STATE) {
                // Unscanned states: one line each, so the next layout scan knows what to capture.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trade] unscanned trade-menu state - {}", a.message());
                continue;
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trade] {}: {}", a.kind(), PlainText.strip(a.message()));
            if (player != null) {
                player.sendSystemMessage(Component.literal("§8[§bSBS§8] §6Trade Guard§r " + a.message()));
            }
            cue = true;
        }
        if (cue && cfg().sound) {
            SbsSounds.play(SoundEvents.NOTE_BLOCK_BASS.value(), 1.0f, 0.5f);
        }
    }

    // ------------------------------------------------------------------ click veto

    /** Whether this click is my own accept click during a lock. Shift-click overrides. */
    public static boolean blocksClick(Player player, int slotId, ContainerInput input) {
        TradeGuardRuntime rt = INSTANCE;
        if (!cfg().enabled || rt.screen == null || slotId != TradeLayout.ACCEPT
                || sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen() != rt.screen) {
            return false;
        }
        boolean shift = input == ContainerInput.QUICK_MOVE || Keys.isDown(KEY_LSHIFT) || Keys.isDown(KEY_RSHIFT);
        long now = System.currentTimeMillis();
        if (!rt.guard.blocksAccept(slotId, shift, settings(), now)) {
            return false;
        }
        if (now - rt.lastCueAt > 400L && player != null) {
            rt.lastCueAt = now;
            player.sendOverlayMessage(Component.literal(String.format(java.util.Locale.ROOT,
                    "§cTrade Guard: accept locked %.1fs §7- check the trade, Shift-click to accept anyway",
                    rt.guard.lockRemaining(now) / 1000.0)));
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Trade] accept click blocked ({} ms left)", rt.guard.lockRemaining(now));
        return true;
    }

    // ------------------------------------------------------------------ tooltip

    /** Adds the swap / bait lines to a flagged stack's tooltip. Same list back when nothing applies. */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        TradeGuardRuntime rt = INSTANCE;
        if (rt.screen == null || rt.guard.flags().isEmpty() || sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen() != rt.screen) {
            return lines;
        }
        for (TradeGuard.Flag f : rt.guard.flags().values()) {
            List<Slot> slots = rt.screen.getMenu().slots;
            if (f.slot() >= slots.size() || slots.get(f.slot()).getItem() != stack) {
                continue;
            }
            List<Component> out = new ArrayList<>(lines);
            out.add(Component.empty());
            out.add(Component.literal("§c§l" + f.headline()));
            if (f.names() != null && !f.names().beforePart().equals(f.names().afterPart())) {
                out.add(Component.literal("§7Was: " + f.names().before()));
                out.add(Component.literal("§7Now: " + f.names().after()));
            }
            for (String d : f.diff()) {
                out.add(Component.literal("§e • " + d));
            }
            if (f.valueUnknown()) {
                out.add(Component.literal("§6⚠ Value unknown"));
            }
            return out;
        }
        return lines;
    }

    // ------------------------------------------------------------------ chat

    /** Chat line with an item hover: remember what that player showed, for bait &amp; switch. */
    public void onChat(String plain, Component message) {
        if (!cfg().enabled || !cfg().learnFromChat || message == null) {
            return;
        }
        Matcher m = SENDER.matcher(plain);
        if (!m.find()) {
            return;
        }
        String who = m.group(1);
        long now = System.currentTimeMillis();
        for (Component part : message.toFlatList()) {
            if (part.getStyle().getHoverEvent() instanceof HoverEvent.ShowItem show) {
                ItemStack stack = show.item().create();
                if (!stack.isEmpty()) {
                    var print = TradeReader.print(stack);
                    guard.observeShown(who, print, TradeReader.value(print, stack, now), now);
                }
            }
        }
    }
}
