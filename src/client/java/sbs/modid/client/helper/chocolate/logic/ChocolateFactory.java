/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.helper.chocolate.model.ChocolateSnapshot;
import sbs.modid.client.helper.chocolate.model.FactoryUpgrade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the open Chocolate Factory menu: what it costs, what it earns, and what has stopped.
 *
 * <p><b>Read-only, and this one is not a per-feature judgement call.</b> Chocolate Factory
 * auto-clicking is what Hypixel bans for. Nothing here clicks, and the module deliberately has no
 * keybind at all - not even a harmless one - so that anybody reading a screenshot of the settings
 * page can see there is no key because nothing here presses anything.
 *
 * <p><b>Nothing it looks for is {@code CONFIRMED}.</b> No session has opened the factory with
 * {@code /sbs probe} armed. Every keyword is config and {@code ESTIMATED}, the whole menu is logged
 * under {@code [SBS][Chocolate]} while it is open, and the feature ships off. What makes that
 * honest rather than reckless is the failure shape: a slot that does not parse is dropped from the
 * ranking, so a bad guess costs entries and can never produce a wrong recommendation.
 *
 * <p>Harvest is gated on the menu's own state id (the BitsShop pattern) and throttled to 150 ms, so
 * a redraw costs a comparison rather than a re-read of every slot.
 */
public final class ChocolateFactory {

    /**
     * What the menu is called. ESTIMATED - matched as a lowercase substring of the cached title,
     * never derived in a draw path. The command that opens it is deliberately not relied on
     * anywhere: a title is what the client can actually see.
     */
    private static final String MENU_TITLE = "chocolate factory";

    private static final long SCAN_INTERVAL_MS = 150L;
    private static final long LOG_INTERVAL_MS = 20_000L;

    private static final ChocolateFactory INSTANCE = new ChocolateFactory();

    /** Slot index -> what that slot is, for the currently open menu only. */
    private final Map<Integer, FactoryUpgrade> live = new HashMap<>();
    /** Slots holding a stray rabbit right now. */
    private final Set<Integer> strays = new HashSet<>();
    /** Strays already announced this menu session, keyed slot + name so a redraw is not a new one. */
    private final Set<String> announced = new HashSet<>();

    private FactoryUpgrade best;
    private long balance = ChocolateSnapshot.UNKNOWN;

    private Screen harvestedScreen;
    private int harvestedState = -1;
    private long lastScanAt;
    private long lastLogAt;
    private boolean barnWarned;
    private boolean towerWarned;

    private ChocolateFactory() {
    }

    public static ChocolateFactory getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ChocolateFactorySettings cfg() {
        return ConfigManager.getInstance().get().chocolateFactory;
    }

    /**
     * Whether {@code normalisedTitle} is the factory.
     *
     * <p>Public because {@code ScreenForeground} derives this pass's render tier from it. Root
     * {@code AGENTS.md}: a tier comes from the feature's own screen test, never a second copy of
     * it, or the two drift and the overlay silently stops drawing.
     */
    public static boolean isFactoryMenu(String normalisedTitle) {
        return normalisedTitle != null
                && normalisedTitle.toLowerCase(Locale.ROOT).contains(MENU_TITLE);
    }

    /** Whether the overlay has anything to draw on this screen. */
    public boolean isActive(AbstractContainerScreen<?> screen) {
        return cfg().enabled && screen != null
                && isFactoryMenu(sbs.modid.client.ui.render.MenuFrame.of(screen).normalised());
    }

    /** What the last harvest made of a slot, or {@code null}. */
    public FactoryUpgrade upgradeAt(int slot) {
        return live.get(slot);
    }

    /** The shortest-payback upgrade in the open menu, or {@code null}. */
    public FactoryUpgrade best() {
        return best;
    }

    /** Whether the slot holds a stray rabbit. */
    public boolean isStray(int slot) {
        return strays.contains(slot);
    }

    /** Chocolate in hand as of the last harvest, or {@link ChocolateSnapshot#UNKNOWN}. */
    public long balance() {
        return balance;
    }

    // ------------------------------------------------------------------ harvest

    /** Called once per client tick from the shared GUI tracking hook. */
    public void tick(Minecraft minecraft) {
        SBSConfig.ChocolateFactorySettings cfg = cfg();
        if (!cfg.enabled) {
            forget();
            return;
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !isFactoryMenu(sbs.modid.client.ui.render.MenuFrame.of(container).normalised())) {
            forget();
            return;
        }
        int stateId = container.getMenu().getStateId();
        long now = System.currentTimeMillis();
        if (harvestedScreen == screen && harvestedState == stateId
                && now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        harvestedScreen = screen;
        harvestedState = stateId;
        lastScanAt = now;
        harvest(container, cfg);
    }

    private void harvest(AbstractContainerScreen<?> container, SBSConfig.ChocolateFactorySettings cfg) {
        ChocolateSnapshot snapshot = new ChocolateSnapshot();
        List<FactoryUpgrade> upgrades = new ArrayList<>();
        Map<Integer, FactoryUpgrade> freshLive = new HashMap<>();
        Set<Integer> freshStrays = new HashSet<>();
        StringBuilder sample = System.currentTimeMillis() - lastLogAt > LOG_INTERVAL_MS
                ? new StringBuilder() : null;

        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue; // the player's own half is not the factory
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = plain(stack.getHoverName().getString()).trim();
            List<String> lore = ItemPriceKey.lore(stack);
            if (sample != null) {
                sample.append('[').append(slot.index).append(' ').append(name).append(" | ")
                        .append(String.join(" / ", lore)).append("] ");
            }

            if (containsAny(name, cfg.strayWords)) {
                freshStrays.add(slot.index);
                continue; // a stray is not an upgrade, whatever else its lore says
            }
            readFixtures(name, lore, cfg, snapshot);

            double cost = ChocolateLore.cost(lore, cfg.costWords);
            if (cost == ChocolateLore.UNKNOWN) {
                continue; // no price line: not something the player buys
            }
            double gain = ChocolateLore.addedPerSecond(lore, cfg.rateWords);
            FactoryUpgrade upgrade = new FactoryUpgrade(slot.index, name, (long) cost,
                    gain == ChocolateLore.UNKNOWN ? FactoryUpgrade.UNKNOWN : gain);
            upgrades.add(upgrade);
            freshLive.put(slot.index, upgrade);
        }

        if (sample != null && sample.length() > 0) {
            lastLogAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chocolate] menu contents: {}",
                    sample.toString().trim());
        }

        snapshot.upgrades = upgrades;
        live.clear();
        live.putAll(freshLive);
        strays.clear();
        strays.addAll(freshStrays);
        best = UpgradeRanking.best(upgrades);
        balance = snapshot.balance;

        ChocolateStore.getInstance().record(snapshot);
        announceStrays(container, cfg);
        announceStatus(snapshot, cfg);
    }

    /**
     * Pulls the non-upgrade readouts - the balance, the rate, the Time Tower and the barn - out of
     * whichever slot happens to carry them.
     *
     * <p>By name keyword rather than by slot index: a slot number is the kind of fact that is right
     * until Hypixel rearranges the menu, and there is no probe here to have got it right in the
     * first place.
     */
    private static void readFixtures(String name, List<String> lore,
                                     SBSConfig.ChocolateFactorySettings cfg,
                                     ChocolateSnapshot snapshot) {
        if (snapshot.perSecond == ChocolateSnapshot.UNKNOWN) {
            double rate = ChocolateLore.perSecond(lore, cfg.rateWords);
            if (rate != ChocolateLore.UNKNOWN && ChocolateLore.addedPerSecond(lore, cfg.rateWords)
                    == ChocolateLore.UNKNOWN) {
                // A "+N per second" line describes an upgrade; a bare one describes the factory.
                snapshot.perSecond = rate;
            }
        }
        if (containsAny(name, cfg.towerWords)) {
            long[] charges = ChocolateLore.capacity(lore);
            if (charges != null) {
                snapshot.towerCharges = charges[0];
                snapshot.towerMaxCharges = charges[1];
            }
        }
        if (containsAny(name, cfg.barnWords)) {
            long[] barn = ChocolateLore.capacity(lore);
            if (barn != null) {
                snapshot.barnRabbits = barn[0];
                snapshot.barnCapacity = barn[1];
            }
        }
    }

    // ------------------------------------------------------------------ alerts

    /**
     * One alert per stray, not one per frame.
     *
     * <p>Keyed on slot plus name because {@link ItemStack} has no {@code equals} and Hypixel
     * rewrites menu slots continuously - "is a stray present" is true on every frame it is there,
     * which is a stream of alerts rather than an event.
     */
    private void announceStrays(AbstractContainerScreen<?> container,
                                SBSConfig.ChocolateFactorySettings cfg) {
        if (!cfg.strayAlert) {
            return;
        }
        for (int slot : strays) {
            ItemStack stack = slotItem(container, slot);
            String name = stack == null ? "Stray Rabbit" : plain(stack.getHoverName().getString()).trim();
            if (!announced.add(slot + "|" + name)) {
                continue;
            }
            Alerts.send(new Alerts.Alert("Stray rabbit", name + " - click it yourself",
                    SbsAudio.Tone.ALARM, null), cfg.strayChannels);
        }
    }

    /** Barn full and Time Tower idle, each announced once per visit rather than once per scan. */
    private void announceStatus(ChocolateSnapshot snapshot, SBSConfig.ChocolateFactorySettings cfg) {
        if (cfg.barnWarning && snapshot.barnFull(cfg.barnWarnPercent)) {
            if (!barnWarned) {
                barnWarned = true;
                Alerts.send(new Alerts.Alert("Rabbit barn nearly full",
                        snapshot.barnRabbits + "/" + snapshot.barnCapacity
                                + " - a new rabbit would be lost",
                        SbsAudio.Tone.CHIME, null), cfg.statusChannels);
            }
        } else {
            barnWarned = false;
        }

        if (cfg.towerAlert && snapshot.towerIdleWithCharge()) {
            if (!towerWarned) {
                towerWarned = true;
                Alerts.send(new Alerts.Alert("Time Tower charge ready",
                        snapshot.towerCharges + " charge(s) waiting and the tower is idle",
                        SbsAudio.Tone.CHIME, null), cfg.statusChannels);
            }
        } else {
            towerWarned = false;
        }
    }

    // ------------------------------------------------------------------ housekeeping

    private static ItemStack slotItem(AbstractContainerScreen<?> container, int index) {
        for (Slot slot : container.getMenu().slots) {
            if (slot.index == index) {
                return slot.getItem();
            }
        }
        return null;
    }

    /**
     * Drops everything about the open menu. The stray record goes with it, so walking back in
     * announces what is there now rather than staying quiet about it.
     */
    private void forget() {
        live.clear();
        strays.clear();
        announced.clear();
        best = null;
        balance = ChocolateSnapshot.UNKNOWN;
        harvestedScreen = null;
        harvestedState = -1;
        barnWarned = false;
        towerWarned = false;
    }

    private static boolean containsAny(String text, String words) {
        if (text == null || words == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String raw : words.split(",")) {
            String word = raw.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String plain(String text) {
        return text == null ? "" : text.replaceAll(String.valueOf((char) 0x00A7) + ".", "");
    }
}
