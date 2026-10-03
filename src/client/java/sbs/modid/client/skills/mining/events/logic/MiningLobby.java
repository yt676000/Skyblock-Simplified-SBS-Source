/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.logic;

import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.timers.ServerWorldTime;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which server instance the player is on, as one id for both places the game prints it.
 *
 * <p>The tab's {@code Server: mini24CD} row ({@link ServerWorldTime#serverName()}) first; the
 * sidebar's {@code MM/DD/YY m24CD} line (CONFIRMED, {@code docs/skyblock-ui/hud-sources.md}) when the
 * tab has not been served yet. Both are normalised to the short sidebar form so one lobby is one id.
 * Feature-local until a shared lobby reader exists in {@code core}; see the spec.
 */
public final class MiningLobby {

    /** The SkyBlock sidebar's date line: one space, then {@code m<id>}. */
    private static final Pattern SIDEBAR_ID = Pattern.compile("^\\d{2}/\\d{2}/\\d{2}\\s+(m[0-9A-Za-z]+)\\b");

    private MiningLobby() {
    }

    /** The current lobby id, or {@code ""} when neither source has it. */
    public static String current() {
        String tab = normalise(ServerWorldTime.serverName());
        return tab.isEmpty() ? fromSidebar(SkyBlockLocation.sidebarLines()) : tab;
    }

    /** {@code mini24CD} -> {@code m24CD}; anything else trimmed as it is. Never {@code null}. */
    public static String normalise(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("mini") && trimmed.length() > 4) {
            return "m" + trimmed.substring(4);
        }
        return trimmed;
    }

    /** The id on the sidebar's date line, or {@code ""}. */
    public static String fromSidebar(List<String> lines) {
        if (lines == null) {
            return "";
        }
        for (String line : lines) {
            Matcher matcher = SIDEBAR_ID.matcher(line == null ? "" : line.trim());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }
}
