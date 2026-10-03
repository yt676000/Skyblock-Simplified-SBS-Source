/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a schematic says about itself, apart from its blocks: name, when it was made, where from,
 * tags, and the small numbers a source needs to place it again.
 *
 * <p>Written first in the file ({@code io/SchematicCodec}), so the library and the Quick Paste grid
 * list a folder by reading only headers.
 *
 * @param name        display name; the file name is derived from it but may differ (sanitised)
 * @param createdAt   epoch millis
 * @param tags        free-form labels, lower-case; the Quick Paste search and filter use them
 * @param folder      optional folder label ({@code ""} = top level)
 * @param favourite   sorted first in Quick Paste
 * @param source      where the blocks came from
 * @param origin      world position of the min corner at capture time, or {@code null}
 * @param extras      named integers a source needs to re-place the capture (Garden's floor and
 *                    bedrock offsets); unknown keys are kept on re-save
 */
public record SchematicHeader(String name, long createdAt, List<String> tags, String folder,
                              boolean favourite, Source source, int[] origin,
                              Map<String, Integer> extras) {

    /** Where a schematic's blocks were read from. */
    public enum Source {
        /** Copied in a singleplayer world. */
        SINGLEPLAYER,
        /** Copied from the client's loaded blocks on a server. */
        MULTIPLAYER,
        /** A Garden plot copy (Garden Blueprint's plot source). */
        PLOT,
        /** Imported from a share code or a vanilla structure file. */
        IMPORT;

        /** Tolerant parse; an unknown value from a newer build reads as {@link #IMPORT}. */
        public static Source parse(String value) {
            if (value == null) {
                return IMPORT;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return IMPORT;
            }
        }

        /** How the source reads in the library: "singleplayer", "plot copy", ... */
        public String label() {
            return switch (this) {
                case SINGLEPLAYER -> "singleplayer";
                case MULTIPLAYER -> "server copy";
                case PLOT -> "plot copy";
                case IMPORT -> "imported";
            };
        }
    }

    public SchematicHeader {
        name = name == null ? "" : name;
        tags = tags == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(tags));
        folder = folder == null ? "" : folder;
        source = source == null ? Source.IMPORT : source;
        origin = origin == null || origin.length != 3 ? null : origin.clone();
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** A blank header, for a schematic nobody has named yet. */
    public static SchematicHeader untitled() {
        return new SchematicHeader("", 0L, List.of(), "", false, Source.IMPORT, null, Map.of());
    }

    public SchematicHeader withName(String value) {
        return new SchematicHeader(value, createdAt, tags, folder, favourite, source, origin, extras);
    }

    public SchematicHeader withCreatedAt(long value) {
        return new SchematicHeader(name, value, tags, folder, favourite, source, origin, extras);
    }

    public SchematicHeader withTags(List<String> value) {
        return new SchematicHeader(name, createdAt, value, folder, favourite, source, origin, extras);
    }

    public SchematicHeader withFolder(String value) {
        return new SchematicHeader(name, createdAt, tags, value, favourite, source, origin, extras);
    }

    public SchematicHeader withFavourite(boolean value) {
        return new SchematicHeader(name, createdAt, tags, folder, value, source, origin, extras);
    }

    public SchematicHeader withSource(Source value) {
        return new SchematicHeader(name, createdAt, tags, folder, favourite, value, origin, extras);
    }

    public SchematicHeader withOrigin(int[] value) {
        return new SchematicHeader(name, createdAt, tags, folder, favourite, source, value, extras);
    }

    public SchematicHeader withExtra(String key, int value) {
        Map<String, Integer> copy = new LinkedHashMap<>(extras);
        copy.put(key, value);
        return new SchematicHeader(name, createdAt, tags, folder, favourite, source, origin, copy);
    }

    /** A named integer, or {@code fallback} when the source never wrote it. */
    public int extra(String key, int fallback) {
        Integer value = extras.get(key);
        return value == null ? fallback : value;
    }

    public boolean hasExtra(String key) {
        return extras.containsKey(key);
    }

    /** Origin X/Y/Z, or {@code fallback} when there is no origin. */
    public int originX(int fallback) {
        return origin == null ? fallback : origin[0];
    }

    public int originY(int fallback) {
        return origin == null ? fallback : origin[1];
    }

    public int originZ(int fallback) {
        return origin == null ? fallback : origin[2];
    }

    // Records compare arrays by identity; the origin is a value.
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SchematicHeader that)) {
            return false;
        }
        return createdAt == that.createdAt && favourite == that.favourite && name.equals(that.name)
                && tags.equals(that.tags) && folder.equals(that.folder) && source == that.source
                && java.util.Arrays.equals(origin, that.origin) && extras.equals(that.extras);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, createdAt, tags, folder, favourite, source,
                java.util.Arrays.hashCode(origin), extras);
    }

    @Override
    public int[] origin() {
        return origin == null ? null : origin.clone();
    }
}
