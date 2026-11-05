/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.scoreboard.ScoreboardSnapshots;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.core.util.HypixelServerEntry;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;
import sbs.modid.client.helper.floordrop.logic.FloorDropAreas;
import sbs.modid.client.helper.visual.logic.MistVisuals;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.hunting.logic.SafariTracker;

import java.util.List;
import java.util.Objects;

/**
 * Builds the {@link TickContext} once at the top of each tick dispatch, and is the one place that
 * reads the sidebar and tab list on the gates' behalf.
 *
 * <p><b>Cost.</b> The location half ({@link TickLocation}) is only recomputed when the sidebar
 * snapshot's revision, the tab list's revision or the world changed - Hypixel's sidebar ticks about
 * once a second, so that is the rate. The cheap half (player, level, server, screen, rod) is compared
 * field by field every call. When neither changed, the previous instances are returned and nothing is
 * allocated.
 *
 * <p>Client thread only. Built at the HEAD of the tick and again at the TAIL, because the packets the
 * vanilla tick applies in between can move the player somewhere else.
 */
public final class TickContexts {

    /** Written on the client thread only; volatile for the readers on other threads. */
    private static volatile TickContext current = TickContext.NONE;

    private static ClientLevel lastLevel;
    private static boolean lastHasPlayer;
    private static int epoch;

    private static ServerData lastServer;
    private static String lastServerIp;
    private static boolean lastOnHypixel;

    private static int seenScoreboardRevision = Integer.MIN_VALUE;
    private static int seenTabRevision = Integer.MIN_VALUE;
    private static int seenEpoch = Integer.MIN_VALUE;

    private TickContexts() {
    }

    /** The context the last {@link #refresh} built; {@link TickContext#NONE} before the first tick. */
    public static TickContext current() {
        return current;
    }

    /** Rebuilds the context from the game state where something changed, and returns it. */
    public static TickContext refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        boolean hasPlayer = player != null;
        if (level != lastLevel || hasPlayer != lastHasPlayer) {
            lastLevel = level;
            lastHasPlayer = hasPlayer;
            epoch++;
        }
        boolean onHypixel = onHypixel(minecraft.getCurrentServer());
        TickContext.ScreenKind screen = screenKind(GuiStateManager.getInstance().getCurrentScreen());
        boolean fishing = hasPlayer
                && (player.fishing != null || player.getMainHandItem().getItem() == Items.FISHING_ROD);
        TickLocation location = location(current.location());

        TickContext before = current;
        if (before.hasPlayer() != hasPlayer || before.hasLevel() != (level != null)
                || before.worldEpoch() != epoch || before.onHypixel() != onHypixel
                || before.screen() != screen || before.fishing() != fishing || before.location() != location) {
            current = new TickContext(hasPlayer, level != null, epoch, onHypixel, screen, fishing, location);
        }
        return current;
    }

    private static boolean onHypixel(ServerData server) {
        if (server != lastServer || (server != null && !Objects.equals(server.ip, lastServerIp))) {
            lastServer = server;
            lastServerIp = server == null ? null : server.ip;
            lastOnHypixel = server != null && HypixelServerEntry.isHypixel(server.ip);
        }
        return lastOnHypixel;
    }

    private static TickContext.ScreenKind screenKind(Screen screen) {
        if (screen == null) {
            return TickContext.ScreenKind.NONE;
        }
        return screen instanceof AbstractContainerScreen<?>
                ? TickContext.ScreenKind.CONTAINER : TickContext.ScreenKind.OTHER;
    }

    /** The location, recomputed only when one of its inputs moved; {@code previous} otherwise. */
    private static TickLocation location(TickLocation previous) {
        int scoreboardRevision = ScoreboardSnapshots.current().revision();
        int tabRevision = TabWidgets.revision();
        if (scoreboardRevision == seenScoreboardRevision && tabRevision == seenTabRevision
                && epoch == seenEpoch) {
            return previous;
        }
        seenScoreboardRevision = scoreboardRevision;
        seenTabRevision = tabRevision;
        seenEpoch = epoch;
        SkyBlockLocation.rereadNow();
        String island = SkyBlockLocation.island();
        String zone = SkyBlockLocation.zone();
        // The dungeon flags are asked even with no location: DungeonScoreboard's test is the sidebar
        // alone, with no SkyBlock precondition, and the flags have to answer exactly what it answers.
        List<String> lines = SkyBlockLocation.sidebarLines();
        boolean inCatacombs = DungeonScoreboard.isInCatacombs(lines);
        boolean inDungeonFloor = DungeonScoreboard.isInDungeon(lines);
        TickLocation next;
        if (island.isEmpty() && zone.isEmpty() && !inCatacombs) {
            next = TickLocation.NONE;
        } else {
            long islands = 0L;
            for (Island candidate : Island.all()) {
                if (SkyBlockLocation.onIsland(candidate.displayName)) {
                    islands |= candidate.bit;
                }
            }
            next = new TickLocation(island, zone, islands, inCatacombs, inDungeonFloor,
                    SkillIslands.onMiningIsland(), SkillIslands.onFarmingIsland(),
                    SkillIslands.onForagingIsland(), MistVisuals.inTheMist(), SafariTracker.inSafariArea(),
                    FloorDropAreas.live());
        }
        return next.equals(previous) ? previous : next;
    }
}
