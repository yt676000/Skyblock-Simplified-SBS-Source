/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import java.util.Set;

/**
 * Freecam's packet allow-list, as a pure function of the packet's type name: while either freecam is
 * on, the client sends only what a player standing still would send anyway. Kept free of game types
 * so each decision is unit-tested ({@code FreecamPacketRulesTest}).
 *
 * <p>The name is the class name without its package, nested classes with {@code $}
 * ({@code ServerboundMovePlayerPacket$Pos}). 26.2 ships unobfuscated, so these are the runtime names.
 */
public final class FreecamPacketRules {

    private FreecamPacketRules() {
    }

    /** What the guard does with one outgoing packet. */
    public enum Verdict {
        /** Sent unchanged. */
        ALLOW,
        /** A movement packet: sent, but rebuilt from the player entity so no camera value can leak. */
        REBUILD,
        /** Dropped, and logged once per type. */
        BLOCK
    }

    /**
     * Sent by a player standing still, whatever the camera does: the connection's heartbeat, the
     * tick marker, acknowledging chunks and teleports the <em>server</em> started (dropping those
     * stalls the connection), and what the player types in chat.
     */
    private static final Set<String> ALWAYS = Set.of(
            "ServerboundKeepAlivePacket",
            "ServerboundPongPacket",
            "ServerboundClientTickEndPacket",
            "ServerboundChunkBatchReceivedPacket",
            "ServerboundAcceptTeleportationPacket",
            "ServerboundChatPacket",
            "ServerboundChatCommandPacket",
            "ServerboundChatCommandSignedPacket",
            "ServerboundChatAckPacket",
            "ServerboundChatSessionUpdatePacket",
            "ServerboundCommandSuggestionPacket",
            // Closing a menu is always allowed: by the time it is sent the screen may already be gone.
            "ServerboundContainerClosePacket");

    private static final Set<String> MOVEMENT = Set.of(
            "ServerboundMovePlayerPacket$Pos",
            "ServerboundMovePlayerPacket$PosRot",
            "ServerboundMovePlayerPacket$Rot",
            "ServerboundMovePlayerPacket$StatusOnly");

    /** Menu packets: allowed only while a screen is open, since only a screen makes them. */
    private static final Set<String> WITH_SCREEN = Set.of(
            "ServerboundContainerClickPacket",
            "ServerboundContainerButtonClickPacket",
            "ServerboundContainerSlotStateChangedPacket",
            "ServerboundSetCreativeModeSlotPacket",
            "ServerboundRenameItemPacket",
            "ServerboundSelectTradePacket",
            "ServerboundPlaceRecipePacket",
            "ServerboundRecipeBookSeenRecipePacket",
            "ServerboundRecipeBookChangeSettingsPacket",
            "ServerboundSelectBundleItemPacket");

    /**
     * The verdict for a packet named {@code name} while freecam is on. Everything not listed is
     * blocked: use, attack, interact, dig, place, swing, held-slot, sneak/sprint and input changes,
     * vehicle moves, and client information (it carries the view distance).
     */
    public static Verdict verdict(String name, boolean screenOpen) {
        if (MOVEMENT.contains(name)) {
            return Verdict.REBUILD;
        }
        if (ALWAYS.contains(name) || (screenOpen && WITH_SCREEN.contains(name))) {
            return Verdict.ALLOW;
        }
        return Verdict.BLOCK;
    }

    /** {@code net.minecraft.x.Foo$Bar} to {@code Foo$Bar}. */
    public static String name(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
