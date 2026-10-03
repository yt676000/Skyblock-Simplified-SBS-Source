/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.util.StyledText;

/**
 * The HotM Upgrade Reminder's watch key: pressed over a perk in the Heart of the Mountain menu, it
 * adds the perk to the watch list or takes it off.
 *
 * <p><b>Never a click.</b> The HotM menu is server-drawn, and a click in it spends powder. This key
 * changes a local list and nothing else; when it acts it is consumed before the container screen
 * sees it, so the screen cannot turn it into a slot interaction either. That is also why a binding
 * that coincides with a key the container screen <i>does</i> send to the server - a hotbar number,
 * drop, swap-offhand, pick-block, the inventory key - is refused rather than consumed: taking those
 * keys away would break the menu, and sharing them would make one press do two things.
 * {@code HotmWatchKeyTest} holds both halves.
 */
public final class HotmWatchKey {

    /** GLFW's Escape. Closing a screen is never ours to take. */
    static final int ESCAPE = 256;

    /** What a key press means here. */
    public enum Action {
        /** Not ours: the screen handles it exactly as if SBS were not installed. */
        PASS,
        /** Ours, over something that is not a perk: swallowed, nothing changes. */
        CONSUME,
        /** Ours, over a perk: toggle {@link Decision#perkId()}. */
        TOGGLE
    }

    public record Decision(Action action, String perkId) {

        static final Decision PASS = new Decision(Action.PASS, null);
        static final Decision CONSUME = new Decision(Action.CONSUME, null);
    }

    private static boolean loggedClash;

    private HotmWatchKey() {
    }

    /**
     * Pure decision.
     *
     * @param pressed     the key code pressed
     * @param bound       the configured key, {@code 0} when unbound
     * @param gameKey     whether the press is also bound to a key the container screen acts on
     * @param hotmMenu    whether the open screen is the Heart of the Mountain menu
     * @param typing      whether a text field has focus
     * @param hoveredPerk the perk id under the cursor, or {@code null}
     */
    public static Decision decide(int pressed, int bound, boolean gameKey, boolean hotmMenu,
                                  boolean typing, String hoveredPerk) {
        if (bound <= 0 || pressed != bound || bound == ESCAPE || gameKey || !hotmMenu || typing) {
            return Decision.PASS;
        }
        return hoveredPerk == null ? Decision.CONSUME : new Decision(Action.TOGGLE, hoveredPerk);
    }

    /**
     * The container screen's key press. Returns {@code true} when the key was SBS's and must not
     * reach the screen.
     */
    public static boolean handle(AbstractContainerScreen<?> screen, KeyEvent event, Slot hovered) {
        MiningHelpersSettings settings = ConfigManager.getInstance().get().miningHelpers;
        if (settings.hotmWatchKey <= 0 || event.key() != settings.hotmWatchKey) {
            return false;   // the common case, before any screen or slot is looked at
        }
        Minecraft mc = Minecraft.getInstance();
        boolean gameKey = gameKey(mc.options, event);
        if (gameKey && !loggedClash) {
            loggedClash = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] watch key {} is also a container key; "
                    + "left to the game", settings.hotmWatchKey);
        }
        boolean hotm = screen.getTitle() != null && HotmTreeReader.isHotmTitle(screen.getTitle().getString());
        Decision decision = decide(event.key(), settings.hotmWatchKey, gameKey, hotm,
                screen.getFocused() instanceof EditBox, hotm ? perkUnder(hovered) : null);
        if (decision.action() == Action.TOGGLE) {
            boolean watched = HotmTreeStore.getInstance().toggleWatched(decision.perkId());
            String name = HotmReminder.displayName(decision.perkId());
            if (mc.player != null) {
                mc.player.sendOverlayMessage(Component.literal(watched
                        ? "§aWatching §f" + name + "§a for HotM reminders"
                        : "§7Stopped watching §f" + name));
            }
        }
        return decision.action() != Action.PASS;
    }

    /** Every key the container screen turns into a slot action or a close. */
    private static boolean gameKey(Options options, KeyEvent event) {
        if (options.keyInventory.matches(event) || options.keyPickItem.matches(event)
                || options.keyDrop.matches(event) || options.keySwapOffhand.matches(event)) {
            return true;
        }
        for (var hotbar : options.keyHotbarSlots) {
            if (hotbar.matches(event)) {
                return true;
            }
        }
        return false;
    }

    /** The perk id of the menu slot under the cursor, or {@code null} for anything else. */
    private static String perkUnder(Slot slot) {
        if (slot == null || slot.container instanceof Inventory || !slot.hasItem()) {
            return null;
        }
        String name = StyledText.strip(slot.getItem().getHoverName().getString()).trim();
        String id = HotmTreeReader.perkIdFor(name);
        return HotmTreeStore.getInstance().nodes().containsKey(id) ? id : null;
    }
}
