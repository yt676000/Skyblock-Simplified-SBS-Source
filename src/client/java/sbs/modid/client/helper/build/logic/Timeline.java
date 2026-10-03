/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import sbs.modid.client.helper.build.model.EditHistory;
import sbs.modid.client.helper.build.model.EditRecord;

/**
 * The undo timeline of the singleplayer world that is open now.
 *
 * <p>Memory only, and per world: it is cleared the moment the world closes ({@link BuildSession}),
 * because an entry's positions mean nothing in another world and replaying one there would edit the
 * wrong blocks. Capped at {@link #MAX_CELLS} cells in total; the oldest entries fall off first.
 */
public final class Timeline {

    /** About 16M positions: roughly 250 MB of undo data at the worst, usually a small fraction. */
    public static final long MAX_CELLS = 16_000_000L;

    private static final EditHistory<EditRecord> HISTORY = new EditHistory<>(MAX_CELLS);

    static {
        BuildSession.onLeave(Timeline::clear);
    }

    private Timeline() {
    }

    public static EditHistory<EditRecord> history() {
        return HISTORY;
    }

    public static void clear() {
        HISTORY.clear();
    }
}
