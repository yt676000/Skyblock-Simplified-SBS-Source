/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where Build Tools' state is cleared when a world closes, decided in one place.
 *
 * <p><b>What survives a world change</b>: the clipboard, the hologram (pinned where it was) and the
 * selection. They are the player's own working copy - the same Garden plot is the same coordinates on
 * the next instance, and a clipboard is a clipboard. <b>What does not</b>: floating placement, a pending
 * edit preview and a running edit, which all belong to the world they were started in; and, in
 * singleplayer, the undo history of the world that closed.
 */
public final class BuildSession {

    private static final List<Runnable> ON_LEAVE = new CopyOnWriteArrayList<>();

    private BuildSession() {
    }

    /** Adds a clean-up step to run when a world closes. */
    public static void onLeave(Runnable step) {
        ON_LEAVE.add(step);
    }

    /** Called on the tick the local player disappears (world closed, disconnected, server hop). */
    public static void onWorldLeave() {
        Placement.onWorldLeave();
        sbs.modid.client.core.build.logic.HologramManager.getInstance().setPreview(null);
        for (Runnable step : ON_LEAVE) {
            step.run();
        }
    }
}
