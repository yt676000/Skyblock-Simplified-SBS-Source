/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File names and catalogued menu names for the Server Scanner. Pure, unit-tested.
 */
public final class ScanNames {

    /** A trailing page counter: {@code "Auction Browser (2/5)"}. */
    private static final Pattern PAGE_COUNTER = Pattern.compile("\\s*\\(\\d+\\s*/\\s*\\d+\\)\\s*$");

    private static final int SLUG_MAX = 40;

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT);

    private static final String TABLE = "Experimentation Table";

    /**
     * Menus whose meaning is catalogued in {@code docs/skyblock-ui/menus.md}, as pattern -> name.
     * The docs are not bundled with the mod, so the table lives here; a feature that is scanned for
     * the first time adds its menus.
     */
    private static final List<Entry> CATALOG = List.of(
            new Entry(Pattern.compile("^Experimentation Table$"), TABLE),
            new Entry(Pattern.compile("^(Chronomatron|Ultrasequencer|Superpairs) \\(.+\\)$"), TABLE + " / $1"),
            new Entry(Pattern.compile("^(Chronomatron|Ultrasequencer|Superpairs) ➜ Stakes$"), TABLE + " / $1 Stakes"),
            new Entry(Pattern.compile("^Superpairs Rewards$"), TABLE + " / Superpairs Rewards"),
            new Entry(Pattern.compile("^Experiment Over$"), TABLE + " / Experiment Over"));

    // ------------------------------------------------------------------ noise

    /*
     * What a full capture leaves out, by rule. Every suppressed entity or sound is counted under its
     * rule and the counts are written to the manifest and summary.txt - "N suppressed", never a silent
     * drop. Keep this list small: a rule that is too wide hides the thing a capture was taken for.
     */

    /** The "CLICK" / "RIGHT CLICK" label under an NPC's name: on every NPC, never changes. */
    public static final String NOISE_CLICK_LABEL = "npc click label";

    /**
     * A named hologram stand (invisible, not tied to a mob, not a health tag) seen again at the same
     * spot with the same name: the Hub's NPC titles and signs re-load every time their chunk does.
     * The first sight is recorded; only the re-sightings are suppressed.
     */
    public static final String NOISE_STATIC_HOLOGRAM = "hologram seen before";

    /** Another player's pet nametag ("[Lv100] Someone's Golden Dragon"). */
    public static final String NOISE_OTHER_PET = "other player's pet";

    /** A stand riding another player: hats, wings and other cosmetics. */
    public static final String NOISE_PLAYER_COSMETIC = "other player's cosmetic";

    /** Another real player, outside presets that keep them (dungeon, kuudra: the party matters). */
    public static final String NOISE_OTHER_PLAYER = "other player";

    /** An invisible stand with no name and nothing worn: a seat or spacer, it carries nothing. */
    public static final String NOISE_BARE_STAND = "bare invisible stand";

    /** A damage splash while the damage channel is off: it would be one entity per hit. */
    public static final String NOISE_DAMAGE_SPLASH = "damage splash (damage off)";

    /** Footsteps, ambience, music and weather - client-side noise, not server cues. */
    public static final String NOISE_SOUND = "step/ambient/music sound";

    /** Every rule above, for the manifest. */
    public static final List<String> NOISE_RULES = List.of(NOISE_CLICK_LABEL, NOISE_STATIC_HOLOGRAM,
            NOISE_OTHER_PET, NOISE_PLAYER_COSMETIC, NOISE_OTHER_PLAYER, NOISE_BARE_STAND,
            NOISE_DAMAGE_SPLASH, NOISE_SOUND);

    private static final Pattern CLICK_LABEL =
            Pattern.compile("^(?:RIGHT |LEFT )?CLICK(?: TO .*)?!?$", Pattern.CASE_INSENSITIVE);

    private ScanNames() {
    }

    /** The noise rule a plain entity name falls under, or {@code null}. */
    public static String noiseName(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return CLICK_LABEL.matcher(plain.trim()).matches() ? NOISE_CLICK_LABEL : null;
    }

    /** Whether a sound id path ({@code entity.player.step}) is client-side noise. */
    public static boolean noiseSound(String path) {
        return path.endsWith(".step") || path.startsWith("ambient.") || path.startsWith("music.")
                || path.startsWith("weather.") || path.startsWith("music_disc.");
    }

    /**
     * The title as a file-name part: page counter stripped, lower case, letters and digits joined by
     * single dashes, at most {@value #SLUG_MAX} characters, never empty.
     */
    public static String slug(String title) {
        String text = title == null ? "" : PAGE_COUNTER.matcher(title).replaceAll("");
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > SLUG_MAX) {
            slug = slug.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "menu" : slug;
    }

    /** {@code <menu-slug>_<HHmmss>_<containerId>.jsonl}. */
    public static String menuFile(String title, LocalTime opened, int containerId) {
        return slug(title) + "_" + opened.format(FILE_TIME) + "_" + containerId + ".jsonl";
    }

    /**
     * The catalogued name of a menu title ({@code "Chronomatron (Metaphysical)"} ->
     * {@code "Experimentation Table / Chronomatron"}), or {@code null} when it is not catalogued.
     * Matched on the plain title with the page counter removed.
     */
    public static String catalog(String title) {
        if (title == null) {
            return null;
        }
        String text = PAGE_COUNTER.matcher(title.trim()).replaceAll("");
        for (Entry entry : CATALOG) {
            Matcher matcher = entry.pattern().matcher(text);
            if (matcher.matches()) {
                return matcher.replaceFirst(entry.name());
            }
        }
        return null;
    }

    private record Entry(Pattern pattern, String name) {
    }
}
