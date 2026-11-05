/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.bossbar.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * Maximum health per Catacombs boss and floor ({@code assets/.../dungeons/boss_hp.json}), used to
 * turn the boss bar's progress into an estimated number when no nametag can be read.
 *
 * <p>Every entry carries a {@link Certainty} and a {@code source} line naming where the figure came
 * from. A boss with no entry is shown as a percentage only - the table is allowed to be incomplete,
 * and an empty table is a valid document, never a made-up number.
 */
public final class BossHpTable implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public List<Entry> bosses = new ArrayList<>();

    /** One boss on one floor type. */
    public static final class Entry {
        /** {@code BossBarText.Boss} name, e.g. {@code "MAXOR"}. */
        public String boss = "";
        /** Master Mode floor rather than the normal one. */
        public boolean master;
        public double maxHp;
        public Certainty certainty = Certainty.UNKNOWN;
        /** Where the number came from (page and date). */
        public String source = "";
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public boolean valid() {
        return bosses != null;
    }

    /** The usable entry for a boss, or {@code null} when it is missing or not a positive number. */
    public Entry find(String boss, boolean master) {
        if (bosses == null || boss == null) {
            return null;
        }
        for (Entry entry : bosses) {
            if (entry != null && boss.equals(entry.boss) && entry.master == master && entry.maxHp > 0
                    && entry.certainty != Certainty.UNKNOWN) {
                return entry;
            }
        }
        return null;
    }
}
