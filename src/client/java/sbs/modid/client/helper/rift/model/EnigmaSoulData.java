/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.model;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The Enigma Soul data file: every catalogued soul in the Rift.
 *
 * <p><b>Flat, not grouped by island</b> - unlike the Fairy Soul file, every soul here is on the one
 * island, so a grouping level would carry a single constant value on every entry. Grouping for
 * display is by {@link EnigmaSoul#area}, which the renderer and the settings page do themselves.
 *
 * <p><b>{@link #totalSouls} is the generator's own count of the souls that exist</b>, which is not
 * the same as {@code souls.size()} while coverage is partial - and it is the number the chat line's
 * denominator is checked against. Getting that wrong makes the reconciliation lie in the one
 * direction that matters, so the field is separate rather than derived.
 *
 * <p>Partial coverage is a normal state: a file with thirty of the fifty-two souls draws thirty
 * markers and says so. Only a file with no souls at all counts as unusable.
 */
public final class EnigmaSoulData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    /**
     * How many Enigma Souls exist in the game, per the generator. Fifty-two as of the Mountain Top
     * update, and expected to grow - which is exactly why it is data and not a constant.
     */
    public int totalSouls;

    public List<EnigmaSoul> souls = new ArrayList<>();

    public EnigmaSoulData() {
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
        return souls != null && !souls.isEmpty();
    }

    /** Drops unusable entries and fills in defaults Gson leaves null. Called once per document. */
    public void link() {
        if (souls == null) {
            souls = new ArrayList<>();
            return;
        }
        souls.removeIf(soul -> soul == null || !soul.valid());
        for (EnigmaSoul soul : souls) {
            // Gson writes null for an enum the file omits, and every consumer would then have to
            // null-check a field the schema says always has a value.
            if (soul.requirement == null) {
                soul.requirement = SoulRequirement.PICKUP;
            }
            if (soul.certainty == null) {
                soul.certainty = Certainty.WIKI;
            }
            if (soul.minPlayers < 1) {
                soul.minPlayers = 1;
            }
            // A soul tagged as needing cooperation but left at one player is a data slip that would
            // otherwise defeat the "hide what I cannot do alone" filter silently.
            if (soul.requirement == SoulRequirement.COOPERATION && soul.minPlayers < 2) {
                soul.minPlayers = 2;
            }
        }
    }
}
