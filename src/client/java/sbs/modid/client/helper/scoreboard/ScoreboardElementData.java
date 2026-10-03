/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The scoreboard element catalogue: what each sidebar row <i>is</i>, as a pattern over the
 * colour-stripped text.
 *
 * <p>Data rather than code on purpose. Hypixel rewords its sidebar without warning, and every
 * reword that this file cannot describe costs a mod release; behind {@link
 * sbs.modid.client.core.data.VersionedDataStore} the same change ships as a document bump. The
 * bundled copy is always live before any fetch is made, so a missing licence or a dead backend is a
 * non-event.
 *
 * <p><b>List order is match order.</b> First pattern to hit wins, exactly as the hand-written enum
 * this replaced worked - which is what keeps the date line from being claimed by the looser
 * patterns further down.
 */
public final class ScoreboardElementData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Entry> elements = new ArrayList<>();

    /** One element: a stable id, what to call it in the editor, and how to recognise it. */
    public static final class Entry {

        /**
         * The stable identity the user's layout is stored against. Assigned once and never
         * renumbered - renaming {@link #name} in a later document must not move anything, which is
         * the whole reason the id is not derived from the name.
         */
        public String id = "";

        /** What the editor calls it. Free to change between documents; nothing is keyed on it. */
        public String name = "";

        /** Java regex, matched with {@code find()} against the colour-stripped line. */
        public String pattern = "";

        /** A representative line, drawn in the editor's preview while the real row is absent. */
        public String example = "";

        /** Why this row is sometimes missing ("The Garden only"), for the editor. Optional. */
        public String note = "";

        /**
         * Whether the lines <i>under</i> this one belong to it until the next blank row.
         *
         * <p>Only the header of a block like {@code Objective} is recognisable on its own - the
         * lines beneath it are free text that changes with the quest. Without this they classify as
         * unrecognised and scatter away from the header they explain.
         */
        public boolean body;

        /**
         * Whether this pattern recognises a line by its <i>shape</i> rather than by its words.
         *
         * <p>A shape pattern has to be loose to be useful - the location row is "a marker glyph then
         * a place name", and the marker is the part most likely to be restyled - and a loose pattern
         * is exactly the kind that will also claim a quest line inside an {@code Objective} block.
         * A weak element never breaks a block open; inside one it is treated as body text.
         */
        public boolean weak;

        public Entry() {
        }
    }

    public ScoreboardElementData() {
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
        return elements != null && !elements.isEmpty();
    }
}
