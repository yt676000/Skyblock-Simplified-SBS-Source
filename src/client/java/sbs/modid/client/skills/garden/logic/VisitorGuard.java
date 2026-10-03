/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.keybind.Keys;

import java.util.List;
import java.util.Locale;

/**
 * The Garden visitor refuse-guard: when a visitor's offer contains a <b>valuable reward</b>
 * (Overgrown Grass, Visitor's Gratitude, ...), the "Refuse Offer" button only works while
 * <b>Ctrl+Shift</b> is held – so a rare visitor can never be declined by a reflex click.
 *
 * <p>Enforced at the {@code handleContainerInput} choke point like the slot locks: the refused
 * click never becomes a packet. Detection is name-based on the open menu – the clicked slot must
 * read like a refuse/decline button, and any container slot's name or lore must name a valuable
 * item. Both are best-guess against Hypixel's wording; the {@code [SBS][Visitor]} log line shows
 * what the guard saw when it blocks, so the lists can be tuned live. The button and slot reading is
 * {@link VisitorMenu}'s, shared with the visitor Bazaar buttons so there is one detector.
 */
public final class VisitorGuard {

    /**
     * Lower-cased fragments that mark a visitor reward as too valuable to refuse casually. The two
     * the user named plus room to grow – one line per item as they come up.
     */
    private static final String[] VALUABLE = {
            "overgrown grass",
            "visitor's gratitude", "visitors gratitude",
    };

    /** GLFW key codes for the Ctrl+Shift combo (left and right variants both count). */
    private static final int KEY_LCTRL = 341;
    private static final int KEY_RCTRL = 345;
    private static final int KEY_LSHIFT = 340;
    private static final int KEY_RSHIFT = 344;

    private static long lastCueAt;

    private VisitorGuard() {
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().gardenHelpers.visitorGuard;
    }

    /**
     * Whether this container click must be refused: a refuse/decline button inside a menu that
     * offers a valuable reward, clicked without the Ctrl+Shift combo.
     */
    public static boolean blocksClick(Player player, int slotId, ContainerInput input) {
        if (!enabled() || player == null || player.containerMenu == null
                || (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE)) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (slotId < 0 || slotId >= menu.slots.size()) {
            return false;
        }
        ItemStack clicked = menu.getSlot(slotId).getItem();
        if (clicked == null || clicked.isEmpty() || !VisitorMenu.isRefuseButton(clicked)) {
            return false;
        }
        String valuable = valuableRewardIn(menu);
        if (valuable == null) {
            return false;   // nothing precious on offer - refuse away
        }
        if (comboHeld()) {
            return false;   // deliberate refusal - allowed
        }
        cue(valuable);
        return true;
    }

    private static boolean comboHeld() {
        return (Keys.isDown(KEY_LCTRL) || Keys.isDown(KEY_RCTRL))
                && (Keys.isDown(KEY_LSHIFT) || Keys.isDown(KEY_RSHIFT));
    }

    /** The first valuable reward named anywhere in the menu (names + lore), or null. */
    private static String valuableRewardIn(AbstractContainerMenu menu) {
        for (ItemStack stack : VisitorMenu.containerStacks(menu)) {
            String hit = valuableIn(VisitorMenu.strip(stack.getHoverName().getString()));
            if (hit != null) {
                return hit;
            }
            for (String line : VisitorMenu.lore(stack)) {
                hit = valuableIn(line);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** The first valuable reward named in these (stripped) lines, or null. The same list the guard uses. */
    public static String valuableRewardIn(List<String> lines) {
        for (String line : lines) {
            String hit = valuableIn(line);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String valuableIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : VALUABLE) {
            if (lower.contains(keyword)) {
                return text.trim();
            }
        }
        return null;
    }

    private static void cue(String valuable) {
        long now = System.currentTimeMillis();
        if (now - lastCueAt < 500L) {
            return;
        }
        lastCueAt = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(
                    "§cRare reward! §7Hold §bCtrl+Shift§7 and click to refuse §e" + valuable));
            mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f, 0.6f);
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] refuse blocked - offer has {}", valuable);
    }

    /** Compile-time use of the list so a future empty list is caught. */
    static List<String> valuableKeywords() {
        return List.of(VALUABLE);
    }
}
