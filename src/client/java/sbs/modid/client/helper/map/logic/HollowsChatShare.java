/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sbs.modid.client.core.location.hollows.HollowsGeometry;
import sbs.modid.client.core.location.hollows.HollowsStructure;
import sbs.modid.client.helper.map.model.KnownStructure;

/**
 * Crystal Hollows structures through plain chat, for everyone in the lobby whether or not they run
 * SBS: {@code /sbs chmap share} writes the found structures as one line, and a line like that from
 * anyone else is read back into "structure at x y z" entries the player can add with one click.
 *
 * <p>Pure strings, no game state, so the round trip is testable. The line format is
 * {@code CH: Jungle Temple 512 100 400 | Mines of Divan 600 90 600}: names as the game writes them,
 * bare triples (which other coordinate readers also understand), {@code |} between entries.
 */
public final class HollowsChatShare {

    /** Hypixel cuts chat at 256 characters; the channel command ("pc ") comes on top. */
    static final int MAX_LINE = 240;

    static final String PREFIX = "CH: ";

    private static final Pattern TRIPLE = Pattern.compile(
            "(?<![\\d-])(-?\\d{1,4})[ ,]+(-?\\d{1,3})[ ,]+(-?\\d{1,4})(?![\\d])");

    private static final Pattern FORMATTING = Pattern.compile("§.");

    /** Short words players actually type for each structure, checked after the full name. */
    private static final String[][] ALIASES = {
            {"JUNGLE_TEMPLE", "temple"},
            {"MINES_OF_DIVAN", "divan"},
            {"GOBLIN_QUEENS_DEN", "queen"},
            {"LOST_PRECURSOR_CITY", "precursor", "city"},
            {"KHAZAD_DUM", "khazad", "bal"},
            {"FAIRY_GROTTO", "grotto", "fairy"},
            {"DRAGONS_LAIR", "lair", "dragon"},
            {"CRYSTAL_NUCLEUS", "nucleus"},
    };

    /** One structure (or an unnamed spot, {@code structure == null}) read from a chat line. */
    public record Entry(HollowsStructure structure, int x, int y, int z) {
    }

    private HollowsChatShare() {
    }

    /** The line to send for these structures, or {@code ""} when there is nothing to share. */
    public static String format(List<KnownStructure> structures) {
        StringBuilder out = new StringBuilder(PREFIX);
        int count = 0;
        for (KnownStructure s : structures) {
            String part = s.structure().displayName() + " " + s.x() + " " + s.y() + " " + s.z();
            int extra = (count == 0 ? 0 : 3) + part.length();
            if (out.length() + extra > MAX_LINE) {
                break;
            }
            if (count > 0) {
                out.append(" | ");
            }
            out.append(part);
            count++;
        }
        return count == 0 ? "" : out.toString();
    }

    /**
     * The structure a typed argument names ("temple", "Mines of Divan", "khazad_dum"), or
     * {@code null}. A near-miss stays {@code null}: a wrong name on the map is worse than none.
     */
    public static HollowsStructure structureNamed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String wanted = text.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        for (HollowsStructure s : HollowsStructure.values()) {
            if (s.displayName().toLowerCase(Locale.ROOT).equals(wanted)
                    || s.name().toLowerCase(Locale.ROOT).replace('_', ' ').equals(wanted)) {
                return s;
            }
        }
        for (String[] row : ALIASES) {
            for (int i = 1; i < row.length; i++) {
                if (row[i].equals(wanted)) {
                    return HollowsStructure.fromId(row[0]);
                }
            }
        }
        return null;
    }

    /**
     * Every Hollows position in a chat message body, each with the structure named next to it when
     * there is one. Positions outside the Hollows are dropped - a hub coordinate is not ours to add.
     */
    public static List<Entry> parse(String body) {
        List<Entry> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        String clean = FORMATTING.matcher(body).replaceAll("");
        for (String segment : clean.split("\\|")) {
            Matcher m = TRIPLE.matcher(segment);
            if (!m.find()) {
                continue;
            }
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(m.group(1));
                y = Integer.parseInt(m.group(2));
                z = Integer.parseInt(m.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!HollowsGeometry.inBounds(x, y, z)) {
                continue;
            }
            HollowsStructure named = structureIn(segment);
            if (named != null && !named.plausibleAt(x, y, z)) {
                // Named, but somewhere that structure cannot be: keep the spot, drop the claim.
                named = null;
            }
            out.add(new Entry(named, x, y, z));
            if (out.size() >= HollowsStructure.values().length) {
                break;
            }
        }
        return out;
    }

    /** The structure whose full name or alias appears as a word in the text, or {@code null}. */
    static HollowsStructure structureIn(String text) {
        String lower = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9ûü']+", " ") + " ";
        for (HollowsStructure s : HollowsStructure.values()) {
            String name = s.displayName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9ûü']+", " ");
            if (lower.contains(" " + name.strip() + " ")) {
                return s;
            }
        }
        for (String[] row : ALIASES) {
            for (int i = 1; i < row.length; i++) {
                if (lower.contains(" " + row[i] + " ")) {
                    return HollowsStructure.fromId(row[0]);
                }
            }
        }
        return null;
    }
}
