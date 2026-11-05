/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validation for player names and uuids, and the fixed command templates that may carry them.
 *
 * <p>A command that contains a player name is only ever built here, from a name that passed
 * {@link #isValidName}. The template is a constant; the name cannot contain a space, a slash or any
 * other character that would change which command is sent. Building a command does not send it:
 * the caller sends it only in response to the player's own click or keypress.
 */
public final class Identity {

    /** Minecraft account names: 3 to 16 of letters, digits and underscore. */
    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static final Pattern UUID_PLAIN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern UUID_DASHED =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private Identity() {
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The name if valid. */
    public static Optional<String> name(String name) {
        return isValidName(name) ? Optional.of(name) : Optional.empty();
    }

    /** 32 lowercase hex characters, from a plain or dashed uuid; empty for anything else. */
    public static Optional<String> normalizeUuid(String uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        if (UUID_PLAIN.matcher(uuid).matches()) {
            return Optional.of(uuid.toLowerCase(Locale.ROOT));
        }
        if (UUID_DASHED.matcher(uuid).matches()) {
            return Optional.of(uuid.replace("-", "").toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    /** The dashed lowercase form, from a plain or dashed uuid. */
    public static Optional<String> dashedUuid(String uuid) {
        return normalizeUuid(uuid).map(u -> u.substring(0, 8) + "-" + u.substring(8, 12) + "-"
                + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20));
    }

    public static boolean isValidUuid(String uuid) {
        return normalizeUuid(uuid).isPresent();
    }

    // ------------------------------------------------------------- templates
    // Each returns the command without the leading slash, as sendCommand expects.

    public static Optional<String> partyInvite(String name) {
        return name(name).map(n -> "party invite " + n);
    }

    public static Optional<String> partyKick(String name) {
        return name(name).map(n -> "party kick " + n);
    }

    public static Optional<String> partyTransfer(String name) {
        return name(name).map(n -> "party transfer " + n);
    }

    public static Optional<String> partyJoin(String name) {
        return name(name).map(n -> "party join " + n);
    }

    public static Optional<String> viewProfile(String name) {
        return name(name).map(n -> "pv " + n);
    }

    public static Optional<String> visit(String name) {
        return name(name).map(n -> "visit " + n);
    }

    public static Optional<String> message(String name) {
        return name(name).map(n -> "msg " + n);
    }
}
