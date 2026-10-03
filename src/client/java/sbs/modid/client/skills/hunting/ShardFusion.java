/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.hunting.model.ShardContext;

import java.util.Locale;

/**
 * The Hunting module's in-menu fusion keys, built around how Hypixel's Fusion Machine actually
 * works:
 * <ul>
 *   <li>The machine menu ("Fusion Box" / "Shard Fusion") carries Hypixel's <b>own</b>
 *       repeat-last-fusion button at slot {@value #REPEAT_SLOT} - the <b>repeat</b> key clicks it,
 *       and Hypixel itself re-selects the previous shards. No client-side shard memory needed (the
 *       old implementation re-clicked remembered shard items, a model the real menu does not
 *       follow - which is why it never worked).</li>
 *   <li>Fusing opens a separate <b>"Confirm Fusion"</b> dialog whose confirm button sits at slot
 *       {@value #CONFIRM_SLOT} - the <b>accept</b> key clicks that. In the machine menu the accept
 *       key instead looks for the fuse button by name and clicks it, so the whole
 *       repeat → fuse → confirm loop runs off the keyboard.</li>
 * </ul>
 *
 * <p>Clicks are sent as a plain button press (button 2, {@link ContainerInput#CLONE}) -
 * Hypixel's menus accept that as a plain button press, and it can never pick an item up onto the
 * cursor. Every click validates the target slot first and reports in chat when the expected button
 * is not there, so a reshuffled menu says what is wrong instead of silently doing nothing.
 *
 * <p>Dispatched from {@code ContainerSearchBarMixin.keyPressed}, i.e. only while a container screen
 * is open - the exact opposite gate of {@link HuntingKeybinds}.
 */
public final class ShardFusion {

    /** Hypixel's own "repeat last fusion" button inside the Fusion Box menu (row 6, slot 3). */
    private static final int REPEAT_SLOT = 47;

    /** The confirm button inside the separate "Confirm Fusion" dialog (row 4, slot 7). */
    private static final int CONFIRM_SLOT = 33;

    private ShardFusion() {
    }

    private static SBSConfig.HuntingSettings cfg() {
        return ConfigManager.getInstance().get().hunting;
    }

    /** Called for key presses while a container screen is open; true when the key was ours. */
    public static boolean handleKey(AbstractContainerScreen<?> screen, KeyEvent event) {
        SBSConfig.HuntingSettings cfg = cfg();
        int key = event.key();
        if (key == 0 || (key != cfg.acceptFusionKey && key != cfg.repeatFusionKey)) {
            return false;
        }
        // Through ShardContext, so a paged fusion menu - "(2/3) Fusion Box" - is still recognised.
        // A bare contains("fusion") survived the page marker too, but it also claimed anything else
        // Hypixel ever names with the word, and it could not tell the three fusion screens apart
        // past the one "confirm" check.
        ShardContext context = ShardContext.fromTitle(
                screen.getTitle() == null ? "" : screen.getTitle().getString());
        if (context != ShardContext.FUSION_BOX && context != ShardContext.SHARD_FUSION
                && context != ShardContext.CONFIRM_FUSION) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        boolean confirmDialog = context == ShardContext.CONFIRM_FUSION;
        if (key == cfg.acceptFusionKey) {
            if (confirmDialog) {
                clickButton(menu, CONFIRM_SLOT, "confirm");
            } else {
                fuse(menu);
            }
        } else {
            if (confirmDialog) {
                SBSChat.send(warn(" Fusion: this is the confirm dialog - press the accept key"));
            } else {
                clickButton(menu, REPEAT_SLOT, "repeat-last-fusion");
            }
        }
        return true;
    }

    /**
     * Clicks the fuse button of the machine menu. Its slot is not fixed across menu revisions, but
     * its item is the one named after fusing - found by name, clicked like everything else.
     */
    private static void fuse(AbstractContainerMenu menu) {
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = strip(stack.getHoverName().getString()).trim().toLowerCase(Locale.ROOT);
            if (name.contains("fuse") || name.contains("confirm") || name.contains("accept")) {
                click(menu, i);
                return;
            }
        }
        SBSChat.send(warn(" Fusion: no fuse button found - are shards selected?"));
    }

    /** Clicks a fixed-position Hypixel button, verifying the slot actually holds an item first. */
    private static void clickButton(AbstractContainerMenu menu, int slot, String what) {
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        if (slot >= containerSlots || menu.getSlot(slot).getItem().isEmpty()) {
            SBSChat.send(warn(" Fusion: " + what + " button not found (slot " + slot + ")"));
            return;
        }
        click(menu, slot);
    }

    /** Sends the click as button 2, CLONE - a pure button press to Hypixel. */
    private static void click(AbstractContainerMenu menu, int slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, 2,
                ContainerInput.CLONE, minecraft.player);
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(String.valueOf((char) 0x00A7) + ".", "");
    }

    private static Component warn(String text) {
        return Component.literal(text).withColor(0xE0A14D);
    }
}
