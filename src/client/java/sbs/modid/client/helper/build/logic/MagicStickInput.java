/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.helper.build.command.BuildCommands;

/**
 * What a selection click does - left-click a block sets corner 1, right-click a block sets corner 2,
 * sneak + right-click air clears the selection - for both ways of clicking it: the Magic Stick Thingy
 * in hand ({@code BuildWandMixin}) and build freecam ({@link Freecam#click}). One copy of the click
 * handling; {@link BuildInput} decides whether it applies.
 *
 * <p><b>The stick is singleplayer only, checked on every click.</b> It cannot exist on a server
 * anyway - it is only ever given by the integrated server - but its hooks cancel vanilla behaviour,
 * and cancelling a click on a server is not something the stick does. Build freecam clicks never
 * reach vanilla at all (they are cancelled at the source, on any server), so they need no such check.
 */
public final class MagicStickInput {

    /** Set by a corner-1 click, cleared by the corner-2 click: the live preview follows the crosshair meanwhile. */
    private static volatile boolean awaitingCorner2;

    static {
        BuildSession.onLeave(() -> awaitingCorner2 = false);
    }

    private MagicStickInput() {
    }

    /** True while the stick is in hand (singleplayer, module on). */
    public static boolean holding() {
        return holdingStick();
    }

    /** Stops the live box following the crosshair (the selection was cleared). */
    public static void endLivePreview() {
        awaitingCorner2 = false;
    }

    /** True while the stick is in the main hand (singleplayer, module on). Only {@link BuildInput} asks. */
    static boolean holdingStick() {
        if (!BuildToolsOwner.cfg().enabled || !BuildGate.singleplayer()) {
            return false;
        }
        Player player = Minecraft.getInstance().player;
        return player != null && MagicStick.is(player.getMainHandItem());
    }

    /** True between a corner-1 click and its corner-2 click. */
    public static boolean awaitingCorner2() {
        return awaitingCorner2;
    }

    /**
     * The one click handler. {@code pos} is the block the click is at ({@code null} = air);
     * {@code sneaking} matters only for a right-click in the air, which clears the selection.
     * Returns whether the click was a selection click (the caller then cancels vanilla's).
     */
    public static boolean click(boolean primary, BlockPos pos, boolean sneaking) {
        SelectionManager selection = SelectionManager.getInstance();
        if (pos == null) {
            if (!primary && sbs.modid.client.helper.build.model.SelectionUx.gestureClears(sneaking, false)
                    && (selection.corner1() != null || selection.corner2() != null)) {
                SelectionActions.clear();
                return true;
            }
            return false;
        }
        // Holding the button repeats the call; only a new block is worth a chat line.
        if (primary) {
            if (!pos.equals(selection.corner1()) || selection.corner2() != null) {
                // Corner 1 on a finished selection starts a new one rather than editing the old box.
                if (selection.corner2() != null && !awaitingCorner2) {
                    selection.setCorner2(null);
                }
                selection.setCorner1(pos);
                BuildCommands.reportCorner(1, pos);
            }
            awaitingCorner2 = true;
        } else {
            if (!pos.equals(selection.corner2())) {
                selection.setCorner2(pos);
                BuildCommands.reportCorner(2, pos);
            }
            awaitingCorner2 = false;
        }
        SelectionActions.touch();
        return true;
    }

    /** Stick: left-click on a block. Returns whether the break must be cancelled. */
    public static boolean onStartDestroy(BlockPos pos) {
        return holdingStick() && click(true, pos, false);
    }

    /** Stick: left button held on a block - keep the break from progressing. */
    public static boolean onContinueDestroy() {
        return holdingStick();
    }

    /** Stick: right-click on a block. Returns whether the use must be cancelled. */
    public static boolean onUseOn(InteractionHand hand, BlockPos pos) {
        return hand == InteractionHand.MAIN_HAND && holdingStick() && click(false, pos, false);
    }

    /**
     * Stick: right-click in the air. Sneaking clears the selection; the click is swallowed either way
     * in singleplayer, so a use-item packet never leaves for the stick. Returns whether to cancel.
     */
    public static boolean onUseAir(InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !holdingStick()) {
            return false;
        }
        Player player = Minecraft.getInstance().player;
        click(false, null, player != null && player.isShiftKeyDown());
        return true;
    }
}
