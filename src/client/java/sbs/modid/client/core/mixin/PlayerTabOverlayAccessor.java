/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Comparator;
import java.util.List;

/**
 * Exposes the tab list's header and footer text. Vanilla keeps both private with only setters
 * ({@code setHeader}/{@code setFooter}), yet Hypixel puts real state down there - the active God
 * Potion and Booster Cookie live in the footer, below the player columns, not in the fake player
 * entries every other tab reader uses.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {

    @Accessor("footer")
    Component skyblockSimplified$footer();

    @Accessor("header")
    Component skyblockSimplified$header();

    /**
     * The entries exactly as vanilla would draw them: listed players only, sorted by the vanilla
     * comparator, capped at 80. The SBS Tab-List renders this list rather than re-deriving it, so
     * its rows can never disagree with what the vanilla overlay would have shown.
     */
    @Invoker("getPlayerInfos")
    List<PlayerInfo> skyblockSimplified$playerInfos();

    /**
     * Vanilla's tab-list sort order. Exposed so a reader can put the <i>whole</i> entry list into
     * display order itself: {@link #skyblockSimplified$playerInfos()} is already sorted but also
     * capped at the 80 rows vanilla can draw, and a reader that only wants the order (not the
     * drawing) must not lose entries past that cap.
     */
    @Accessor("PLAYER_COMPARATOR")
    static Comparator<PlayerInfo> skyblockSimplified$playerComparator() {
        throw new AssertionError("mixin accessor");
    }
}
