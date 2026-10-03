/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;

/**
 * Makes the renderer's section grid actually big enough for the terrain being served.
 *
 * <p><b>The ceiling nothing else could lift.</b> {@code ViewArea} allocates its whole section grid
 * <i>in its constructor</i>, sized to the render distance in force at that moment, and holds it in a
 * fixed storage ring. Reporting a larger distance from {@code getEffectiveRenderDistance} therefore
 * changes what the renderer is <i>willing</i> to draw but not what it is <i>able</i> to: sections
 * past the grid it was built with have nowhere to live, so they are silently dropped. That is why
 * Uncapped kept showing only a render distance worth of terrain no matter how much of the map had
 * been loaded - and why walking toward something "fixed" it, since approaching brings it inside the
 * grid that already exists.
 *
 * <p>The grid is only rebuilt by {@code invalidateCompiledGeometry}, which frees every buffer and
 * remeshes the world. That is far too expensive to do speculatively, so it is done exactly when the
 * needed distance no longer fits, and never more than once per {@value #COOLDOWN_MS} ms.
 *
 * <p><b>And exactly once when it cannot fit at all.</b> A rendering mod that owns the section grid
 * leaves {@code ViewArea} behind as a stub whose distance stays at whatever it was - so the
 * shortfall this class exists to close survives the rebuild that was supposed to close it. The
 * cooldown alone does not save us there: it turns "rebuild when needed" into "remesh the world every
 * ten seconds, forever". So the rebuild is checked, and a rebuild that changed nothing is taken as
 * proof that this renderer is not ours to resize.
 */
public final class FarTerrainViewArea {

    /** A rebuild is a full remesh - never do two in quick succession, whatever the sliders say. */
    private static final long COOLDOWN_MS = 10_000L;

    /**
     * Only rebuild when the shortfall is worth it. Rebuilding for one chunk would trade a whole
     * remesh for a sliver of view, and the grid is re-checked constantly.
     */
    private static final int GROW_THRESHOLD = 2;

    private static long lastRebuild;

    /**
     * Set once a rebuild has been shown not to grow the grid, and never cleared: which renderer is
     * drawing the world is decided by what is installed, not by which world it is. A per-level flag
     * would look almost right and still cost a full remesh on every server hop.
     */
    private static boolean cannotGrow;

    private FarTerrainViewArea() {
    }

    /**
     * Grows the section grid if the distance the module wants to draw does not fit in it.
     *
     * <p>Called once a second from the manager's tick. Does nothing at all in the normal case - the
     * grid already matches the slider, and only Uncapped (or a fresh, larger map extent) can make
     * the wanted distance exceed it.
     */
    public static void ensureFits(Minecraft minecraft) {
        if (cannotGrow) {
            return;
        }
        Integer wanted = FarTerrainManager.ownRenderDistance();
        if (wanted == null || minecraft.level == null) {
            return;
        }
        var levelRenderer = minecraft.levelRenderer;
        var viewArea = levelRenderer.viewArea();
        if (viewArea == null) {
            return;
        }
        int have = viewArea.getViewDistance();
        if (have >= wanted - GROW_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRebuild < COOLDOWN_MS) {
            return;
        }
        lastRebuild = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Terrain] section grid is {} chunks but {} are being served - rebuilding it "
                        + "(one-off remesh; this is what Uncapped costs)", have, wanted);
        try {
            levelRenderer.invalidateCompiledGeometry(minecraft.level, minecraft.options,
                    minecraft.gameRenderer.mainCamera(), minecraft.getBlockColors());
        } catch (Throwable t) {
            cannotGrow = true;
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not rebuild the section grid ({}) - far terrain stays "
                            + "limited to {} chunks", t.toString(), have);
            return;
        }
        // The rebuild replaces the grid, so this has to be the NEW one, not the local above. A
        // distance that did not move means the rebuild reached something that is not the renderer
        // actually drawing the world - and repeating it would only buy another remesh.
        var rebuilt = levelRenderer.viewArea();
        if (rebuilt == null || rebuilt.getViewDistance() <= have) {
            cannotGrow = true;
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] the section grid did not grow past {} chunks - another mod owns "
                            + "the chunk renderer, so far terrain stays limited to what it draws "
                            + "(not trying again)", have);
        }
    }

    /**
     * Forgets the cooldown, so a fresh world can size its grid immediately. Deliberately leaves the
     * give-up alone - see {@link #cannotGrow}.
     */
    public static void reset() {
        lastRebuild = 0L;
    }
}
