/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer.model;

/**
 * One "show this player as that instead" entry: wherever {@code name} appears, {@code alias} is
 * drawn in its place. Plain Gson POJO for the config.
 *
 * <p>Keyed on the in-game name rather than the uuid, on purpose. The name is what the player types
 * into the editor and what they can check afterwards; a uuid would survive a rename but could not be
 * entered by hand or read back, and an alias for somebody who is not online right now has no uuid to
 * look up in the first place.
 */
public final class PlayerAlias {

    /** The in-game name to replace. Matched whole-word and case-insensitively. */
    public String name = "";

    /** What to show instead. Empty blanks the name out entirely. */
    public String alias = "";

    /** Off leaves this one entry alone without deleting it. */
    public boolean enabled = true;

    public PlayerAlias() {
    }

    public PlayerAlias(String name, String alias) {
        this.name = name;
        this.alias = alias;
    }

    /** Whether this entry can match anything - an entry with no name never applies. */
    public boolean usable() {
        return enabled && name != null && !name.isBlank();
    }
}
