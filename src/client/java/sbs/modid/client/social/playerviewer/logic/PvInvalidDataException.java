/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

/**
 * A relayed response broke a limit or carried a wrong type in a field the viewer reads. The whole
 * section (or, for the response root, the response) is rejected. The message names the field path
 * and the expected vs actual JSON type only, never a value, so it is
 * safe to log.
 */
public final class PvInvalidDataException extends Exception {

    public PvInvalidDataException(String kind) {
        super(kind, null, false, false);
    }
}
