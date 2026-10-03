/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import net.minecraft.world.phys.Vec3;

/**
 * Where each Hub warp puts you down.
 *
 * <h2>Coordinates, not commands</h2>
 *
 * <p>These are arrival points - facts about the world, of the same kind as a fairy soul's position.
 * The commands that reach them are not here and are not built from here: the mod already carries the
 * warp list in {@code helper/warp/WarpCatalog}, and this exists only so that "which warp is nearest
 * to that burrow" can be answered. What the answer is <i>used</i> for is a display: the suggestion
 * is shown, and the player's own keybind sends the warp.
 *
 * <p>An enum rather than a data file because it is seven rows that change when Hypixel rebuilds the
 * Hub, which is roughly never and is a code change either way - the same call
 * {@code GemzieSpot} makes for the same reason.
 *
 * <h2>Two of them are worth leaving from and two are not</h2>
 *
 * <p>{@link #CASTLE} and {@link #CRYPT} land somewhere that takes a while to get out of, which is why
 * {@link #awkwardExit} exists: a warp that is nominally nearest but drops you inside a building can
 * be slower than one a little further out in the open. That is a judgement about the geometry of the
 * Hub, and it is the reason "nearest" alone is the wrong answer.
 *
 * <p>Every coordinate here is <b>unverified</b> against this client. A wrong one costs a suggestion
 * that sends the player to the second-best warp, which is a mild failure - but it is still a failure,
 * and it is why the suggestion says which warp rather than silently routing to it.
 */
public enum HubWarp {

    /** The Hub's own spawn. Always available, to everyone. */
    SPAWN("Hub", "hub", 0.5, 77.0, -0.5, false),

    /** High up and away to the west. Y is ignored when comparing it - see the class note. */
    CASTLE("Castle", "castle", -250.0, 130.0, 45.0, true),

    /** Also high up, and also compared without its Y. */
    WIZARD("Wizard Tower", "wizard", 44.5, 119.0, 93.5, false),

    /** Underground and slow to leave. */
    CRYPT("Crypts", "crypt", -160.5, 62.0, -106.5, true),

    /** The bazaar end of the plaza. */
    STONKS("Bazaar", "stonks", -36.5, 70.0, -81.5, false),

    /** Out on the eastern edge. */
    DARK_AUCTION("Dark Auction", "da", 91.5, 75.0, 173.5, false),

    /** Central, and the usual best answer for anything in the middle of the Hub. */
    MUSEUM("Museum", "museum", 29.5, 72.0, 1.5, false);

    private final String displayName;
    private final String command;
    private final Vec3 pos;
    private final boolean awkwardExit;

    HubWarp(String displayName, String command, double x, double y, double z, boolean awkwardExit) {
        this.displayName = displayName;
        this.command = command;
        this.pos = new Vec3(x, y, z);
        this.awkwardExit = awkwardExit;
    }

    public String displayName() {
        return displayName;
    }

    /** The bare warp name - what the player would type after {@code /warp}. Never sent from here. */
    public String command() {
        return command;
    }

    public Vec3 pos() {
        return pos;
    }

    /** Whether leaving this warp costs enough time to prefer a slightly further one. */
    public boolean awkwardExit() {
        return awkwardExit;
    }

    /**
     * Whether this warp is so far above the ground that comparing it by straight-line distance
     * flatters it.
     *
     * <p>The two high warps are fifty blocks up. Included in a plain distance comparison they look
     * further from every burrow than they are in practice, because the drop is free.
     */
    public boolean elevated() {
        return this == CASTLE || this == WIZARD;
    }
}
