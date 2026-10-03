/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.ChatTag;

/**
 * The single door every outbound party message in this module goes through.
 *
 * <p><b>There is exactly one of these because sending chat on someone's behalf is the one thing in
 * the module that can get them into trouble.</b> Hypixel disconnects a client that sends chat too
 * fast or sends a line that is too long, and a bug in any one alert - a phase that flickers, a crate
 * that is seen and unseen - would otherwise turn straight into a self-inflicted kick mid-run. So the
 * rate limit and the length cap live here rather than in each caller, and no caller can skip them.
 *
 * <p><b>Nothing another player typed ever reaches this method.</b> Every call site builds its message
 * out of the module's own constants and enum names; incoming party text is only ever matched against
 * a fixed list and then discarded. Even so the text is scrubbed of anything that could change what
 * the line means - a leading slash would make it a command, a section sign would smuggle formatting,
 * a newline would make it two messages.
 */
public final class KuudraSay {

    /** Minimum gap between two sends. Comfortably inside anything Hypixel objects to. */
    private static final long MIN_GAP_MS = 1_500L;

    /** Hard length cap. Well under Hypixel's limit, which disconnects rather than truncates. */
    private static final int MAX_LENGTH = 96;

    private static volatile long lastSentAt;

    private KuudraSay() {
    }

    /**
     * Sends {@code message} to party chat, unless it is too soon after the last one.
     *
     * <p>Returns quietly rather than queueing: these are call-outs about a moment, and a "no tri"
     * that arrives four seconds late is worse than one that never arrives.
     */
    public static void party(String message) {
        if (!ConfigManager.getInstance().get().kuudra.enabled) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !KuudraTracker.getInstance().running()) {
            return;
        }
        String clean = sanitize(message);
        if (clean.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < MIN_GAP_MS) {
            KuudraTracker.getInstance().log("held back a party message (too soon): {}", clean);
            return;
        }
        lastSentAt = now;
        // Tagged after the length cap, not before: the tag is the mod's, and letting it eat into a
        // call-out's 96 characters would silently shorten the message the party actually needs.
        player.connection.sendCommand("pc " + ChatTag.tag(clean));
    }

    /** Strips everything that could turn a message into something other than a message. */
    private static String sanitize(String message) {
        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < message.length() && out.length() < MAX_LENGTH; i++) {
            char c = message.charAt(i);
            if (c == '\n' || c == '\r' || c == '§' || c < ' ') {
                continue;
            }
            // A leading slash would be parsed as a command by the server, not as chat.
            if (c == '/' && out.length() == 0) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
