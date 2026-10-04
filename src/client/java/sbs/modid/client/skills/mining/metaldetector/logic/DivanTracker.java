/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MetalDetectorSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker;
import sbs.modid.client.skills.mining.nucleus.model.Crystal;

/**
 * The Mines of Divan chat, read once and handed out: the detector's hunt ends on every find, and the
 * Divan Tools checklist moves its rows. Also where the checklist is keyed to the lobby and where it
 * asks the Nucleus tracker whether the Jade Crystal has been collected.
 *
 * <p>Read-only: chat in, a card and an alert out. It never talks to a Keeper or digs anything.
 */
public final class DivanTracker {

    private static final DivanTracker INSTANCE = new DivanTracker();

    /** The lobby and the Nucleus tracker are looked at this often - both walk lists. */
    private static final int CHECK_EVERY_TICKS = 10;

    private final DivanChecklist checklist = new DivanChecklist();
    private int ticks;

    private DivanTracker() {
    }

    public static DivanTracker getInstance() {
        return INSTANCE;
    }

    private static MetalDetectorSettings cfg() {
        return ConfigManager.getInstance().get().metalDetector;
    }

    private static boolean onHollows() {
        return SkyBlockLocation.onIsland(HollowsTracker.ISLAND);
    }

    /** The checklist, for the card. */
    public DivanChecklist checklist() {
        return checklist;
    }

    /** From {@code ChatPriceListenerMixin}: every displayed chat line, colour codes included. */
    public void onChat(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Cheap pre-filter: every line this reads contains one of these two, and most chat does not.
        if (!text.contains("Metal Detector") && !text.contains("Keeper of")) {
            return;
        }
        DivanChat.Event event = DivanChat.parse(text);
        if (event == null) {
            return;
        }
        String line = DivanChat.plain(text);
        if (event instanceof DivanChat.ToolFound || event instanceof DivanChat.ChestLoot) {
            MetalDetectorTracker.getInstance().onFound(event, line);
        }
        if (!cfg().checklist) {
            return;
        }
        checklist.syncLobby(ServerWorldTime.serverName());
        if (checklist.apply(event)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Divan] \"{}\" -> {} tool(s) obtained, jade {}",
                    line, checklist.obtained(), checklist.jade());
        }
        if (event instanceof DivanChat.ToolFound found && found.tool() != null) {
            Alerts.send(Alerts.Alert.of("Found " + found.tool().itemName(),
                    DivanChecklist.returnHint(found.tool(), checklist.obtained())), cfg().toolAlertChannels);
        }
    }

    /** Every client tick: the lobby key, and the Jade Crystal from the Nucleus tracker. */
    public void onClientTick() {
        if (!cfg().checklist || ++ticks % CHECK_EVERY_TICKS != 0 || !onHollows()) {
            return;
        }
        if (checklist.syncLobby(ServerWorldTime.serverName())) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Divan] lobby changed - checklist cleared");
        }
        if (checklist.jade() == DivanChecklist.Jade.READY
                && checklist.observeJade(nucleusHasJade())) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Divan] Jade Crystal collected (Nucleus tracker)");
        }
    }

    /**
     * Whether the Nucleus tracker's open run has the Jade crystal found or placed. That tracker reads
     * the {@code CRYSTAL FOUND} block already; asking it keeps the line parsed in one place.
     */
    private static boolean nucleusHasJade() {
        return NucleusRunTracker.getInstance().ledger().crystalState(Crystal.JADE) != Crystal.State.NONE;
    }
}
