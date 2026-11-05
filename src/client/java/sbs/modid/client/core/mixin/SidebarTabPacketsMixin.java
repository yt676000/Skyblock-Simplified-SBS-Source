/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.PacketThreadCheck;
import sbs.modid.client.core.scoreboard.ScoreboardSnapshots;
import sbs.modid.client.core.tab.TabWidgets;

/**
 * Marks the shared sidebar snapshot and the tab-list revision dirty whenever the client applies a
 * packet that can change them.
 *
 * <p>{@code RETURN} rather than {@code HEAD}: every one of these handlers starts with
 * {@code PacketUtils.ensureRunningOnSameThread}, which throws on the netty thread and re-dispatches,
 * so a RETURN injection runs once, on the client thread, after the scoreboard has really changed. It
 * also covers the early {@code return} in the team handler.
 *
 * <p>Team packets mark the tab list as well: vanilla's tab order sorts by team name, so a player
 * moving team can reorder the widget lines without any tab packet at all.
 */
@Mixin(ClientPacketListener.class)
public class SidebarTabPacketsMixin {

    @Inject(method = {"handleAddObjective", "handleSetScore", "handleResetScore", "handleSetDisplayObjective"},
            at = @At("RETURN"))
    private void skyblockSimplified$sidebarChanged(CallbackInfo ci) {
        PacketThreadCheck.check("SidebarTabPacketsMixin.sidebarChanged");
        ScoreboardSnapshots.markDirty();
    }

    @Inject(method = "handleSetPlayerTeamPacket", at = @At("RETURN"))
    private void skyblockSimplified$teamChanged(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
        PacketThreadCheck.check("SidebarTabPacketsMixin.teamChanged");
        // Only teams behind a sidebar row: the Hub's nametag teams update constantly and used to force
        // a full sidebar re-read on the next frame each time.
        ScoreboardSnapshots.markTeamDirty(packet.getName(), packet.getPlayers());
        TabWidgets.markDirty();
    }

    @Inject(method = {"handlePlayerInfoUpdate", "handlePlayerInfoRemove", "handleTabListCustomisation"},
            at = @At("RETURN"))
    private void skyblockSimplified$tabChanged(CallbackInfo ci) {
        PacketThreadCheck.check("SidebarTabPacketsMixin.tabChanged");
        TabWidgets.markDirty();
        // Player entries are not part of the tab revision, but dungeon class tags live on them.
        sbs.modid.client.dungeons.run.model.DungeonTeamClasses.markPlayersChanged();
    }
}
