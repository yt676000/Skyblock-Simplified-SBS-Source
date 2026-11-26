/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line in a slayer boss's nametag stack that names whose quest spawned it – the one piece of
 * hard evidence for "whose boss is this" in a lobby where every boss looks the same.
 *
 * <p>Hypixel has printed it two ways. Up to October 2026 it read {@code "Spawned by: Steve"}. Since
 * then (first seen in the play instance's logs on 2026-11-05, captured whole in the Server Scanner
 * session of 2026-11-22 in the Smoldering Tomb) it reads {@code "Steve's SLAYER BOSS"}, or
 * {@code "Ross' SLAYER BOSS"} for a name ending in s. The Inferno Demonlord's split demons carry the
 * same line, so it also says whose Quazii and Typhoeus they are. Both shapes are accepted: mining
 * event mobs still print "Spawned by:", and an old shape that comes back costs nothing to read.
 */
public final class SlayerOwnerLine {

    /** The old shape, {@code "Spawned by: Steve"}. */
    private static final Pattern SPAWNED_BY =
            Pattern.compile("Spawned by:\\s+([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);

    /**
     * The current shape, {@code "Steve's SLAYER BOSS"} / {@code "Ross' SLAYER BOSS"}. Upper case as
     * printed, so neither "SLAYER MINIBOSS" nor a chat-style "slayer boss" sentence fits; the
     * look-behind stops a name from being read out of the middle of a longer word.
     */
    private static final Pattern POSSESSIVE =
            Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z0-9_]{1,16})['’]s?\\s+SLAYER BOSS\\b");

    private SlayerOwnerLine() {
    }

    /** The player a colour-stripped nametag names as the boss's owner, or {@code null} when it is no owner line. */
    public static String ownerIn(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        // Cheap prefilter before either regex: an owner line always carries one of these words.
        if (plain.indexOf("SLAYER BOSS") >= 0) {
            Matcher matcher = POSSESSIVE.matcher(plain);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        if (plain.contains("pawned by")) {
            Matcher matcher = SPAWNED_BY.matcher(plain);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }
}
