/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

/**
 * Which hand {@code hand} reads, and what is said when it holds no block. Pure, so the rules are
 * tested: {@code hand} means the main hand - unless that is the Magic Stick Thingy, which the player
 * holds while selecting, in which case the off hand is meant.
 */
public final class HeldBlockRule {

    public static final String NO_BLOCK = "Hold a block, or type one: //set stone";
    public static final String STICK_NO_OFFHAND = "You are holding the Magic Stick Thingy - put the block in your "
            + "off hand, or type one: //set stone";

    private HeldBlockRule() {
    }

    /** What //set places when no block is typed: the held block. */
    public static String setArgument(String typed) {
        return typed == null || typed.isBlank() ? BlockPattern.HAND : typed;
    }

    /** Whether the off hand is read for the token. */
    public static boolean readsOffhand(boolean tokenIsOffhand, boolean mainHandIsStick) {
        return tokenIsOffhand || mainHandIsStick;
    }

    /**
     * The refusal when the read hand holds no placeable block, or {@code null} when it does.
     *
     * @param empty      the hand is empty
     * @param blockItem  the item places a block
     * @param viaStick   the off hand was read because the main hand holds the stick
     */
    public static String refusal(boolean empty, boolean blockItem, boolean viaStick) {
        if (empty || !blockItem) {
            return viaStick ? STICK_NO_OFFHAND : NO_BLOCK;
        }
        return null;
    }
}
