/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

/**
 * Counts successful catches - every reel that brought something up, not every cast.
 *
 * <p>Most catches announce themselves in chat (a sea creature, a treasure, a trophy fish) and are
 * counted from that line. The ordinary ones - raw fish, a log, junk - have no line at all, so they are
 * counted from what the client sees instead: the bobber leaves the water or lava, and an XP orb pickup
 * sound plays right at the player within a moment of it. Reeling in empty plays no such sound, and a
 * bobber that merely despawned was never reeled.
 *
 * <p>Chat lines arrive before the bobber goes away, so a reel shortly after a chat catch is that
 * catch and not a second one. The sound can land a tick before or after the reel edge, so the pairing
 * runs both ways inside {@link #PAIR_MS}.
 *
 * <p>Pure state, no game access: {@link FishingTracker} feeds it times, which keeps it testable.
 * Unverified in game: the sound volumes (0.1 / 0.5) and the pairing window.
 */
public final class CatchCounter {

    /** How far apart the reel edge and the XP orb sound may be and still be one catch. */
    static final long PAIR_MS = 400L;

    /** How long after a chat catch the bobber's disappearance still belongs to that catch. */
    static final long CHAT_CLAIM_MS = 2_000L;

    /** How recently the bobber must have been in water or lava for a reel to count as a catch. */
    static final long IN_FLUID_MS = 500L;

    private int catches;
    private long lastChatCatchAt;
    private long pendingReelAt;
    private long lastSoundAt;
    private long lastInFluidAt;

    /** A catch announced in chat (sea creature, treasure, trophy fish). */
    public void onChatCatch(long now) {
        catches++;
        lastChatCatchAt = now;
    }

    /** The bobber is in water or lava this tick. */
    public void onBobberInFluid(long now) {
        lastInFluidAt = now;
    }

    /**
     * The bobber went away. Returns true when that completed a catch (the sound already played);
     * otherwise the reel waits for a sound for {@link #PAIR_MS}.
     */
    public boolean onReel(long now) {
        boolean claimedByChat = lastChatCatchAt > 0 && now - lastChatCatchAt < CHAT_CLAIM_MS;
        if (now - lastInFluidAt > IN_FLUID_MS || claimedByChat) {
            pendingReelAt = 0;
            return false;
        }
        if (lastSoundAt > 0 && now - lastSoundAt <= PAIR_MS) {
            return book();
        }
        pendingReelAt = now;
        return false;
    }

    /** An XP orb pickup sound at the player. Returns true when it completed a pending reel. */
    public boolean onCatchSound(long now) {
        lastSoundAt = now;
        if (pendingReelAt > 0 && now - pendingReelAt <= PAIR_MS) {
            return book();
        }
        return false;
    }

    private boolean book() {
        catches++;
        pendingReelAt = 0;
        lastSoundAt = 0;
        return true;
    }

    public int catches() {
        return catches;
    }

    public void reset() {
        catches = 0;
        lastChatCatchAt = 0;
        pendingReelAt = 0;
        lastSoundAt = 0;
        lastInFluidAt = 0;
    }
}
