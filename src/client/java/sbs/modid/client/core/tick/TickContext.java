/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

/**
 * Everything a tick gate decides on, computed once at the top of the client tick by
 * {@link TickContexts} and immutable afterwards. While nothing changed the previous instance is
 * handed out again, so a steady tick allocates nothing.
 *
 * @param hasPlayer  {@code Minecraft.player != null}
 * @param hasLevel   {@code Minecraft.level != null}
 * @param worldEpoch moves whenever the level or the player's presence changes - the world-change
 *                   transition every tick task gets a run-on window for ({@link TickDispatcher})
 * @param onHypixel  the current server's address is Hypixel's ({@code HypixelServerEntry.isHypixel});
 *                   informational - SkyBlock gates ask {@link #inSkyBlock()} instead, so a player
 *                   joining through a proxy address loses nothing
 * @param screen     what kind of screen {@code GuiStateManager} reports open
 * @param fishing    a bobber is out, or a fishing rod is in the main hand
 * @param location   where the player is
 */
public record TickContext(boolean hasPlayer, boolean hasLevel, int worldEpoch, boolean onHypixel,
                          ScreenKind screen, boolean fishing, TickLocation location) {

    /** What kind of screen is open. */
    public enum ScreenKind {
        /** No screen: the player is in the world. */
        NONE,
        /** A container menu ({@code AbstractContainerScreen}) - the player inventory included. */
        CONTAINER,
        /** Any other screen (chat, settings, the title screen). */
        OTHER
    }

    /** Before the first tick: no world, no screen, nowhere. */
    public static final TickContext NONE =
            new TickContext(false, false, 0, false, ScreenKind.NONE, false, TickLocation.NONE);

    public boolean inSkyBlock() {
        return location.inSkyBlock();
    }

    public boolean on(Island island) {
        return location.on(island);
    }

    /** Whether the island cannot be read (zone-only, or not on SkyBlock at all). */
    public boolean islandUnknown() {
        return location.island().isEmpty();
    }

    public boolean inCatacombs() {
        return location.inCatacombs();
    }

    public boolean container() {
        return screen == ScreenKind.CONTAINER;
    }
}
