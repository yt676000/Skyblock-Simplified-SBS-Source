/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

/**
 * The one hologram in the world, and the transient edit preview that may be drawn over it.
 *
 * <p>One at a time, on purpose: copying with the Garden key, {@code /.. paste} and a Quick Paste
 * click all replace it. Two free-floating holograms from two features is a state nobody could reason
 * about, and each feature already has a way to bring its own back.
 *
 * <p>The preview wins while it exists: an edit awaiting Enter/Esc is what the player is looking at,
 * and drawing the paste hologram through it would hide which blocks the edit changes.
 *
 * <p>Held in memory only. A hologram belongs to a session; a build worth keeping is saved to the
 * library.
 */
public final class HologramManager {

    private static final HologramManager INSTANCE = new HologramManager();

    private volatile Hologram hologram;
    private volatile Hologram preview;
    private volatile boolean shown;

    private HologramManager() {
    }

    public static HologramManager getInstance() {
        return INSTANCE;
    }

    /** The placed hologram, drawn or not. */
    public Hologram hologram() {
        return hologram;
    }

    /** Whether the placed hologram is switched on. */
    public boolean shown() {
        return shown && hologram != null;
    }

    /** The hologram the renderers draw this frame: the preview first, else the shown hologram. */
    public Hologram active() {
        Hologram pending = preview;
        if (pending != null) {
            return pending;
        }
        return shown ? hologram : null;
    }

    /** Replaces the hologram and shows it. */
    public void set(Hologram value) {
        hologram = value;
        shown = value != null;
    }

    /** Swaps in a changed copy of the current hologram (nudge, turn, pin), keeping its visibility. */
    public void update(Hologram value) {
        hologram = value;
    }

    public void setShown(boolean value) {
        shown = value;
    }

    /** Removes the hologram; the preview, if any, is left alone. */
    public void clear() {
        hologram = null;
        shown = false;
    }

    /** Removes the hologram only if {@code owner} placed it, so one feature cannot clear another's. */
    public void clearIfOwnedBy(HologramOwner owner) {
        Hologram current = hologram;
        if (current != null && current.owner() == owner) {
            clear();
        }
    }

    public Hologram preview() {
        return preview;
    }

    public void setPreview(Hologram value) {
        preview = value;
    }

    /** Everything off - world change, disconnect. */
    public void reset() {
        hologram = null;
        preview = null;
        shown = false;
    }
}
