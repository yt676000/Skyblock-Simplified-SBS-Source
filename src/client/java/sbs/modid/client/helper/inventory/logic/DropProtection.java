/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.Rarity;

import java.util.Locale;

/**
 * Rarity drop protection (part of the Slot Lock module): items at or above a configured rarity
 * cannot be dropped even when their slot is not locked – either refused outright, or only let
 * through after pressing drop three times in quick succession (so a genuine "yes, throw my
 * Terminator away" still works without opening any settings).
 *
 * <p>Enforced at the same two choke points as the slot locks – {@code LocalPlayer.drop} for the
 * in-world Q and {@code MultiPlayerGameMode.handleContainerInput} for container drops (Q over a
 * slot, Ctrl+Q, clicking outside the window with the item on the cursor) – so no refused drop ever
 * becomes a packet. Selling to NPCs / trading is a container PICKUP into another menu, not a drop,
 * and stays untouched.
 */
public final class DropProtection {

    /** The presses required in triple-press mode, and how close together they must be. */
    private static final int PRESSES_REQUIRED = 3;
    private static final long PRESS_WINDOW_MS = 3_000L;

    private static int presses;
    private static long lastPressAt;

    private DropProtection() {
    }

    private static SBSConfig.SlotLockSettings cfg() {
        return ConfigManager.getInstance().get().slotLock;
    }

    /** The configured threshold, defensively parsed (a broken config falls back to EPIC). */
    public static Rarity threshold() {
        try {
            return Rarity.valueOf(cfg().rarityProtectMin.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Rarity.EPIC;
        }
    }

    /** Whether this stack is protected: feature on, rarity detected, and at/above the threshold. */
    public static boolean protects(ItemStack stack) {
        if (!cfg().rarityProtect || stack == null || stack.isEmpty()) {
            return false;
        }
        Rarity rarity = Rarity.detect(stack);
        // The enum is ordered strongest-first (DIVINE=0 ... COMMON=7), so "at or above the
        // threshold" is a SMALLER-or-equal ordinal.
        return rarity != null && rarity.ordinal() <= threshold().ordinal();
    }

    /**
     * The one gate every drop path asks: {@code true} lets the drop through. In triple-press mode
     * the first two presses are refused with a countdown cue; the third within the window drops for
     * real. In block mode every attempt is refused (unlock by lowering the threshold / toggling).
     */
    public static boolean allowDrop(ItemStack stack) {
        if (!protects(stack)) {
            return true;
        }
        String name = stack.getHoverName().getString();
        if (!cfg().rarityTriplePress) {
            cue("§c" + name + " §7is drop-protected (rarity)", 0.6f);
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPressAt > PRESS_WINDOW_MS) {
            presses = 0;   // too slow - the sequence starts over
        }
        lastPressAt = now;
        presses++;
        if (presses >= PRESSES_REQUIRED) {
            presses = 0;
            return true;
        }
        cue("§ePress drop §b" + (PRESSES_REQUIRED - presses) + "§e more time"
                + (PRESSES_REQUIRED - presses == 1 ? "" : "s") + " to drop §c" + name, 1.0f);
        return false;
    }

    /** Action-bar style cue + click, so a refused drop is never silent. */
    private static void cue(String message, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(message));
            mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, pitch);
        }
    }

    /** Cycles the threshold one rarity step (settings row), COMMON wrapping back to DIVINE. */
    public static void cycleThreshold() {
        Rarity[] values = Rarity.values();
        cfg().rarityProtectMin = values[(threshold().ordinal() + 1) % values.length].name();
        ConfigManager.getInstance().save();
    }

    /** "Epic and above" - the settings-row display of the current threshold. */
    public static String thresholdLabel() {
        return labelOf(threshold());
    }

    /** The same label for any rarity, so the picker's options and its value read identically. */
    public static String labelOf(Rarity rarity) {
        String name = rarity.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " +";
    }

    /** Every threshold the picker offers, in rarity order. */
    public static java.util.List<String> thresholdOptions() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Rarity rarity : Rarity.values()) {
            out.add(labelOf(rarity));
        }
        return out;
    }

    /** Sets the threshold from a {@link #labelOf} label; an unknown label is ignored. */
    public static void setThresholdByLabel(String label) {
        for (Rarity rarity : Rarity.values()) {
            if (labelOf(rarity).equals(label)) {
                cfg().rarityProtectMin = rarity.name();
                ConfigManager.getInstance().save();
                return;
            }
        }
    }
}
