/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import net.minecraft.network.chat.Component;

/**
 * One line of the sidebar scoreboard, in three forms the Custom Scoreboard needs at once:
 * <ul>
 *   <li>{@link #display()} – the original, fully coloured component (what "keep original colours"
 *       draws),</li>
 *   <li>{@link #stripped()} – the same text with the {@code §} colour codes removed (what the custom
 *       single-colour mode draws, and what the editor lists), and</li>
 *   <li>{@link #signature()} – a value-independent key (digits collapsed to {@code #}) used to hide
 *       or reorder a line without it breaking when its live number ticks.</li>
 * </ul>
 */
public record ScoreboardLine(Component display, String stripped, String signature) {

    /** True for a line that is only a spacer (blank once colour codes are stripped). */
    public boolean isBlank() {
        return stripped.isBlank();
    }
}
