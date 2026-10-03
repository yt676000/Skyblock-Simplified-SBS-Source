/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.economy.forge.model.ForgeSlotTimer;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Forge timers: reads the Dwarven Forge menu when the player opens it, and announces a slot that
 * finishes - including one that finished while they were offline.
 *
 * <p><b>Reads only.</b> Nothing is clicked or claimed; opening the forge is the player's action.
 *
 * <p><b>The layout is UNVERIFIED</b> ({@code docs/skyblock-ui/menus.md}, The Forge). So the menu
 * is matched on a configurable title prefix rather than a literal, every slot of the first reading
 * of each opened menu is written to the log under {@code [SBS][ForgeTimer]}, and a slot whose lore
 * matches neither a countdown nor a ready word is simply not tracked. The exact title matters:
 * the recipe picker behind an empty slot lists forge durations too, and reading that as slots in
 * progress would replace the real ones - which is why the match is a prefix of the main menu's
 * title and not a {@code contains("forge")}.
 *
 * <p><b>Survives world changes by design</b> - the forge keeps running while the player is
 * elsewhere, which is the exception to "reset on world change" and is why it is spelt out here.
 */
public final class ForgeTimers {

    private static final ForgeTimers INSTANCE = new ForgeTimers();

    private static final long CHECK_INTERVAL_MS = 1_000L;

    /** Silence after joining, so the login chat flood does not swallow the alert. */
    private static final long JOIN_GRACE_MS = 15_000L;

    private Object lastScreen;
    private int lastStateId = Integer.MIN_VALUE;
    private boolean dumpedThisScreen;

    private long lastCheckAt;
    private long inWorldSince;

    /** What the last menu read found, for the settings page. */
    private volatile String lastReadSummary = "the forge menu has not been read yet";

    private ForgeTimers() {
    }

    public static ForgeTimers getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ForgeSettings cfg() {
        return ConfigManager.getInstance().get().forge;
    }

    private static ForgeTimerStore store() {
        return ForgeTimerStore.getInstance();
    }

    /** Every client tick: read the menu if it is open and changed, then check for finished slots. */
    public void onClientTick() {
        SBSConfig.ForgeSettings cfg = cfg();
        if (!cfg.timersEnabled) {
            return;
        }
        try {
            readMenuIfOpen(cfg);
        } catch (RuntimeException e) {
            // One lost reading, worth seeing; the next change re-reads.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][ForgeTimer] menu read failed", e);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            inWorldSince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (inWorldSince == 0L) {
            inWorldSince = now;
        }
        if (now - lastCheckAt < CHECK_INTERVAL_MS || !store().ready()) {
            return;
        }
        lastCheckAt = now;
        if (now - inWorldSince >= JOIN_GRACE_MS) {
            notifyFinished(cfg, now);
        }
    }

    public void onWorldChange() {
        inWorldSince = 0L;
        lastScreen = null;
        store().flushProfile();
    }

    // ------------------------------------------------------------------ menu

    /** Whether this title is the forge's main menu, by the configured prefix. Public for reuse. */
    public static boolean isForgeMenu(String strippedTitle) {
        String prefix = cfg().timerMenuTitle;
        if (prefix == null || prefix.isBlank()) {
            prefix = SBSConfig.ForgeSettings.DEFAULT_TIMER_MENU_TITLE;
        }
        return strippedTitle != null && strippedTitle.trim().toLowerCase(Locale.ROOT)
                .startsWith(prefix.trim().toLowerCase(Locale.ROOT));
    }

    private void readMenuIfOpen(SBSConfig.ForgeSettings cfg) {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            lastScreen = null;
            return;
        }
        Component title = container.getTitle();
        String plain = title == null ? "" : StyledText.strip(title.getString());
        if (!isForgeMenu(plain)) {
            return;
        }
        int stateId = container.getMenu().getStateId();
        if (lastScreen == screen && lastStateId == stateId) {
            return;
        }
        if (lastScreen != screen) {
            dumpedThisScreen = false;
        }
        lastScreen = screen;
        lastStateId = stateId;
        if (!store().ready()) {
            return;
        }
        read(container, plain);
    }

    private void read(AbstractContainerScreen<?> container, String title) {
        long now = System.currentTimeMillis();
        List<ForgeSlotTimer> read = new ArrayList<>();
        int items = 0;
        int unknown = 0;
        boolean dump = !dumpedThisScreen;
        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            items++;
            String name = StyledText.strip(stack.getHoverName().getString()).trim();
            List<String> lore = ItemPriceKey.lore(stack);
            if (dump) {
                // The probe: until docs/skyblock-ui/menus.md has the real layout, this is it.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][ForgeTimer] '{}' slot {} name=\"{}\" x{} lore={}",
                        title, slot.index, name, stack.getCount(), lore);
            }
            ForgeSlotParser.Reading reading = ForgeSlotParser.read(lore);
            if (reading.state() == ForgeSlotParser.State.UNKNOWN) {
                unknown++;
                continue;
            }
            read.add(ForgeSlotParser.merge(store().get(slot.index), slot.index, name,
                    stack.getCount(), reading, now));
        }
        if (items == 0) {
            // The contents packet has not arrived yet. Replacing now would wipe the stored slots
            // and their alert flags for one frame.
            return;
        }
        dumpedThisScreen = true;
        store().replaceAll(read);
        lastReadSummary = read.size() + " slot(s) tracked, " + unknown + " other item(s) ignored";
        if (dump) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ForgeTimer] read '{}': {}", title, lastReadSummary);
        }
    }

    // ------------------------------------------------------------------ alerts

    /**
     * One announcement for every slot that has finished and not been announced - coalesced, so
     * three slots finishing together (or while offline) are one line, not three.
     */
    private void notifyFinished(SBSConfig.ForgeSettings cfg, long now) {
        List<String> done = new ArrayList<>(2);
        for (ForgeSlotTimer timer : store().all()) {
            if (timer.ready(now) && !timer.notified) {
                timer.notified = true;
                done.add(timer.label());
            }
        }
        if (done.isEmpty()) {
            return;
        }
        store().touch();
        if (!cfg.timerAlert || !AlertChannels.any(cfg.timerChannels)) {
            return;
        }
        String headline = done.size() == 1 ? "Forge slot ready" : done.size() + " forge slots ready";
        String list = String.join(", ", done);
        if (AlertChannels.has(cfg.timerChannels, AlertChannel.CHAT)) {
            SBSChat.send(Component.literal(" " + headline + ": ").withColor(SBSChat.WHITE)
                    .append(Component.literal(list).withColor(0x57D977)));
        }
        int mask = cfg.timerChannels & ~AlertChannel.CHAT.bit();
        if (AlertChannels.any(mask)) {
            Alerts.send(new Alerts.Alert(headline, list, SbsAudio.Tone.CHIME, null), mask);
        }
    }

    // ------------------------------------------------------------------ queries

    /** Every tracked slot, soonest first (ready ones on top). */
    public List<ForgeSlotTimer> sorted() {
        if (!store().ready()) {
            return List.of();
        }
        List<ForgeSlotTimer> out = store().all();
        out.sort(Comparator.comparingLong(t -> t.finishAt));
        return out;
    }

    public String status() {
        if (!cfg().timersEnabled) {
            return "off";
        }
        if (!store().ready()) {
            return "waiting for the SkyBlock profile to be known";
        }
        return lastReadSummary;
    }
}
