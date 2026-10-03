/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rejoin;

/**
 * Why the player stopped being on SkyBlock. The whole feature turns on this and nothing else.
 *
 * <p><b>The bug this exists to fix.</b> Every one of these ends in the same observable state - the
 * player is in a Hypixel lobby and no longer on SkyBlock - so a trigger phrased as "left SkyBlock
 * and landed in a lobby" cannot tell them apart, and the timer armed on all of them. The
 * distinguishing information is the <i>reason</i>, and the reason only ever arrives before the move,
 * in chat or as a command the player typed. By the time the sidebar changes it is gone.
 *
 * <p><b>{@link #UNKNOWN} does not prompt, and that is the deliberate default.</b> A missing prompt
 * costs the player one command. A wrong prompt on every scheduled restart teaches them to ignore the
 * banner, which costs the feature the one case it was built for.
 */
public enum RejoinCause {

    /**
     * Kicked or dropped without warning - the case the feature exists for. The player wants back
     * into the same island as soon as Hypixel will take them.
     */
    KICK,

    /**
     * A scheduled restart or evacuation, announced in advance with a countdown. Everyone on the
     * server is moved and the server is then not there, so a countdown to rejoining it is worse than
     * useless: it invites the player to try at the exact moment it cannot work.
     */
    RESTART,

    /**
     * The player left on purpose - {@code /lobby}, {@code /play}, a menu, quitting. Never prompt.
     * They know where they are; they put themselves there.
     */
    VOLUNTARY,

    /**
     * In a lobby, and the client never saw a reason. Everything that is not positively identified
     * lands here, including a kick whose wording this build does not recognise.
     */
    UNKNOWN
}
