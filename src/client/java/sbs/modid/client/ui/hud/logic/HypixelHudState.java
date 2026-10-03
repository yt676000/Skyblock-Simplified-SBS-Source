/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.skills.progress.SkillTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for Hypixel SkyBlock HUD values that are not available from vanilla –
 * currently only the player's mana, which the SBS mana bar reads.
 *
 * <p>Hypixel publishes mana through the SkyBlock <b>action bar</b> (the overlay message above the
 * hotbar), e.g. "450/600✎ Mana". This class parses that text (fed in by the HUD mixin's
 * {@code setOverlayMessage} hook) so the mana bar always reads the exact same numbers – nothing is
 * estimated. The health / defense / overflow stats are <b>not</b> re-parsed or re-rendered: the GUI
 * editor transforms Hypixel's real action-bar render directly instead.
 *
 * <p>Values are {@code volatile} because they are written from the network/HUD thread and read while
 * rendering.
 */
public final class HypixelHudState {

    private static final HypixelHudState INSTANCE = new HypixelHudState();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);
    /** The classic mana pencil ✎ – still accepted alongside the current custom-font glyph. */
    private static final String LEGACY_MANA_SYMBOL = String.valueOf((char) 0x270E);

    /**
     * Hypixel's current custom-font stat glyphs (private-use area), verified from a live action bar
     * via the {@code [SBS][Vitality]} dump: health {@code E010}, mana {@code E003}, vitality (the
     * "healing pool") {@code E028}. The parser identifies mana and vitality by these <b>directly</b>,
     * because Hypixel appended the vitality ratio AFTER mana – breaking the old "mana = last ratio"
     * contract (the last ratio is now vitality, which then read as a permanently-full mana bar). The
     * structure heuristics stay as a fallback for any format whose glyphs are not these; the tuning
     * log surfaces a new glyph so a constant can be updated.
     */
    private static final String MANA_GLYPH = String.valueOf((char) 0xE003);
    private static final String VITALITY_GLYPH = String.valueOf((char) 0xE028);
    /**
     * Health, which the mana fallback must never pick up. When Hypixel replaces the mana section
     * with "NOT ENOUGH MANA" the health ratio is the only one left on the bar – and the old
     * "last ratio that is not vitality" fallback then read <b>health</b> as mana, which is why the
     * mana bar jumped to FULL at the exact moment mana ran out.
     */
    private static final String HEALTH_GLYPH = String.valueOf((char) 0xE010);

    /**
     * One stat ratio "<current>/<max><glyph>" where the glyph is ANY single non-ASCII symbol.
     * Hypixel periodically swaps the stat icons (❤/✎/❈ were replaced by new glyphs in a 2026
     * update), so the parser is <b>structure-based</b> instead of icon-based: the action bar is
     * always "health-ratio … [defense] … mana-ratio [overflow]", i.e. the LAST symbol-terminated
     * ratio is the mana. ASCII-labelled ratios ("500/500 Drill Fuel", "6/6 Secrets") never match.
     */
    private static final Pattern STAT_RATIO = Pattern.compile(
            "([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)\\s*([^\\x00-\\x7F\\s])");

    /**
     * An overflow-mana segment: the first standalone symbol-terminated number AFTER the base mana
     * ratio (e.g. "925/961✎ 600ʬ" – any glyph, Hypixel has changed this one too).
     */
    private static final Pattern OVERFLOW = Pattern.compile(
            "([0-9][0-9,]*)\\s*([^\\x00-\\x7F\\s])");

    /** Mana is considered current only if it was seen in the last few seconds. */
    private static final long FRESHNESS_MS = 5_000L;

    private volatile int manaCurrent;
    private volatile int manaMax;
    private volatile long lastManaUpdate;

    private volatile int overflowMana;
    private volatile long lastOverflowUpdate;

    /**
     * Hypixel's "NOT ENOUGH MANA" notice, in every casing it has shipped in. While it is up the
     * mana ratio is <b>gone from the action bar</b>, which is the whole problem: the parser has
     * nothing to read, keeps the last values, and the bar sits there claiming to be full at the one
     * moment mana ran out. The values cannot be corrected (the notice says "not enough for this
     * ability", not how much is left), so the bar flags itself instead – see {@link #notEnoughMana}.
     */
    private static final Pattern NOT_ENOUGH_MANA = Pattern.compile("(?i)not\\s+enough\\s+mana");

    /** Until when the notice counts as on screen (see {@link #noteNotEnoughMana}). */
    private volatile long notEnoughManaUntil;

    /**
     * How long one action-bar sighting keeps the flag up. Hypixel rewrites the action bar every
     * tick while the notice shows, so this only has to outlive a single tick.
     */
    private static final long NOTICE_ACTION_BAR_MS = 600L;

    /** A title is sent once but stays on screen, so its sighting has to cover the whole display. */
    private static final long NOTICE_TITLE_MS = 2_000L;

    /**
     * The last action-bar line, colour-stripped, for readers of stats this class does not model.
     *
     * <p>Kept here rather than re-hooked elsewhere because this is already the single place the
     * overlay message arrives: a second mixin into {@code setOverlayMessage} for the sake of one more
     * consumer would be two hooks racing to parse the same string. The Rift Time reader uses it as
     * one of its three sources.
     */
    private volatile String lastActionBar = "";

    private volatile int vitalityCurrent;
    private volatile int vitalityMax;
    private volatile long lastVitalityUpdate;
    /** Throttle for the [SBS][Vitality] tuning log (only while the vitality bar is enabled). */
    private volatile long lastVitalityLog;

    private HypixelHudState() {
    }

    public static HypixelHudState getInstance() {
        return INSTANCE;
    }

    public int manaCurrent() {
        return manaCurrent;
    }

    public int manaMax() {
        return manaMax;
    }

    /** Whether recent mana data is available (i.e. the player is on SkyBlock). */
    public boolean hasMana() {
        return manaMax > 0 && (System.currentTimeMillis() - lastManaUpdate) < FRESHNESS_MS;
    }

    /**
     * Whether Hypixel's "NOT ENOUGH MANA" notice is on screen right now – i.e. whether the mana
     * values the bar is drawing are the stale ones from before the notice replaced them.
     */
    public boolean notEnoughMana() {
        return System.currentTimeMillis() < notEnoughManaUntil;
    }

    /**
     * Records a sighting of the notice. Called for both places Hypixel has put it: the action bar
     * (which it rewrites every tick) and the big red title.
     */
    public void noteNotEnoughMana(boolean fromTitle) {
        notEnoughManaUntil = System.currentTimeMillis()
                + (fromTitle ? NOTICE_TITLE_MS : NOTICE_ACTION_BAR_MS);
    }

    /** Feeds a title / subtitle line in, so the notice is caught however Hypixel sends it. */
    public void parseTitle(String raw) {
        if (raw != null && NOT_ENOUGH_MANA.matcher(raw).find()) {
            noteNotEnoughMana(true);
        }
    }

    /** Current overflow mana (extra mana beyond max), or 0 if none / stale. */
    public int overflowMana() {
        if (System.currentTimeMillis() - lastOverflowUpdate >= FRESHNESS_MS) {
            return 0;
        }
        return overflowMana;
    }

    /** The last action-bar line seen, colour-stripped; {@code ""} before the first one arrives. */
    public String lastActionBar() {
        return lastActionBar;
    }

    public int vitalityCurrent() {
        return vitalityCurrent;
    }

    public int vitalityMax() {
        return vitalityMax;
    }

    /** Whether recent vitality data is available (i.e. the player is somewhere the stat is shown). */
    public boolean hasVitality() {
        return vitalityMax > 0 && (System.currentTimeMillis() - lastVitalityUpdate) < FRESHNESS_MS;
    }

    /**
     * Parses a Hypixel action-bar line for the mana and overflow-mana segments; missing ones are
     * ignored. Structure-based (see {@link #STAT_RATIO}): with two or more symbol-ratios the last
     * one is the mana (the first is health) and its glyph is remembered; a lone ratio counts only
     * when it carries the remembered / legacy mana glyph, so a mana-less bar ("health + drill
     * fuel") can never poison the mana values.
     */
    public void parseActionBar(String raw) {
        if (raw == null) {
            return;
        }
        String text = raw.replaceAll(SECTION_SIGN + ".", "");
        lastActionBar = text;
        long now = System.currentTimeMillis();

        // Metal Detector: the treasure distance rides the same bar. Here rather than in a second
        // mixin - this is the mod's one action-bar hook and it stays that way.
        sbs.modid.client.skills.mining.metaldetector.logic.MetalDetectorTracker.getInstance()
                .onActionBar(text);

        // The notice replaces the stats, so this has to be checked BEFORE the ratios are read (there
        // are none to read) - it is what tells the mana bar its numbers are stale.
        if (NOT_ENOUGH_MANA.matcher(text).find()) {
            noteNotEnoughMana(false);
        }

        // Every "current/max<glyph>" ratio on the bar, in order (with its span), so mana and vitality
        // can be picked by glyph and the overflow bounded to the gap after mana.
        List<Ratio> ratios = new ArrayList<>();
        Matcher matcher = STAT_RATIO.matcher(text);
        while (matcher.find()) {
            ratios.add(new Ratio(matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.start(), matcher.end()));
        }

        // Mana: the ratio carrying the mana glyph (current custom-font one, or the classic ✎). No
        // glyph match (an unknown format) → the last ratio that is not the known vitality glyph,
        // i.e. the old "mana is the last ratio" contract, but never the vitality ratio.
        Ratio mana = pickByGlyph(ratios, MANA_GLYPH, LEGACY_MANA_SYMBOL);
        if (mana == null && ratios.size() >= 2) {
            // Positional fallback for an unknown mana glyph. It needs at least TWO ratios: the bar
            // always starts with health, so a LONE ratio is health - which is exactly what is left
            // when "NOT ENOUGH MANA" takes the mana section over. Reading it as mana is what made
            // the bar claim to be full while mana was empty. Vitality and health are both excluded
            // by glyph as well, so only a genuinely unknown mana glyph can land here.
            for (int i = ratios.size() - 1; i >= 0; i--) {
                String symbol = ratios.get(i).symbol();
                if (!symbol.equals(VITALITY_GLYPH) && !symbol.equals(HEALTH_GLYPH)) {
                    mana = ratios.get(i);
                    break;
                }
            }
        }
        if (mana != null) {
            try {
                manaCurrent = Integer.parseInt(mana.cur().replace(",", ""));
                manaMax = Integer.parseInt(mana.max().replace(",", ""));
                lastManaUpdate = now;
            } catch (NumberFormatException ignored) {
                // leave the previous values untouched
            }

            // Overflow is the first lone symbol-number right after mana, bounded to BEFORE the next
            // ratio so the vitality ratio's own number can never be mistaken for overflow.
            int limit = text.length();
            for (Ratio r : ratios) {
                if (r.start() >= mana.end()) {
                    limit = r.start();
                    break;
                }
            }
            Matcher overflow = OVERFLOW.matcher(text.substring(mana.end(), limit));
            if (overflow.find()) {
                try {
                    overflowMana = Integer.parseInt(overflow.group(1).replace(",", ""));
                    lastOverflowUpdate = now;
                } catch (NumberFormatException ignored) {
                    // leave the previous value untouched
                }
            }
        }

        parseVitality(raw, ratios, now);

        // Skill XP gains ("+40 Combat (312,540/1,000,000)") feed the live pet-XP counter and the
        // Skill Progress overlay. The progress part is optional - Hypixel omits it once a skill is
        // maxed, and the player can turn it off - so the pet counter never depends on it.
        Matcher skill = SKILL_XP.matcher(text);
        if (skill.find()) {
            try {
                double amount = Double.parseDouble(skill.group(1).replace(",", ""));
                PetTracker.getInstance().onSkillXp(skill.group(2), amount);
                // "which skill is earning right now", recorded from the same branch the pet counter
                // uses - so it keeps answering for a maxed skill, where the progress part is gone.
                SkillTracker.getInstance().onSkillPayout(skill.group(2), amount);
                if (skill.group(3) != null && skill.group(4) != null) {
                    SkillTracker.getInstance().onSkillXp(skill.group(2), amount,
                            Double.parseDouble(skill.group(3).replace(",", "")),
                            Double.parseDouble(skill.group(4).replace(",", "")));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * Vitality is Hypixel's new "healing pool" stat, shown on the action bar as its own
     * {@code current/max} ratio next to health and mana. The mana logic above keeps its
     * health-first / mana-last contract, so vitality is read from a THIRD ratio: with three or more
     * symbol-ratios, health is the first and mana the last, leaving the middle one as vitality.
     *
     * <p>Its exact glyph and slot are still settling on Hypixel's side, so a throttled
     * {@code [SBS][Vitality]} log dumps the raw action bar (every non-ASCII glyph as its codepoint)
     * whenever the vitality bar is switched on – enough to pin the format from a live client and,
     * if the middle-ratio guess is off, move to an exact glyph match.
     */
    private void parseVitality(String raw, List<Ratio> ratios, long now) {
        // Vitality = the ratio carrying the vitality glyph; no glyph match falls back to the old
        // middle-ratio guess (health first, mana last on the pre-vitality layout).
        Ratio vitality = pickByGlyph(ratios, VITALITY_GLYPH);
        if (vitality == null && ratios.size() >= 3) {
            vitality = ratios.get(1);
        }
        if (vitality != null) {
            try {
                int cur = Integer.parseInt(vitality.cur().replace(",", ""));
                int max = Integer.parseInt(vitality.max().replace(",", ""));
                if (max > 0) {
                    vitalityCurrent = cur;
                    vitalityMax = max;
                    lastVitalityUpdate = now;
                }
            } catch (NumberFormatException ignored) {
                // leave the previous values untouched
            }
        }

        boolean enabled = ConfigManager.getInstance().get().hypixelGui.vitalityBar.rendersBar();
        if (enabled && manaMax > 0 && now - lastVitalityLog > 5_000L) {
            lastVitalityLog = now;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Vitality] ratios={} bar='{}'",
                    ratios.size(), dumpCodepoints(raw));
        }
    }

    /** One parsed "current/max<glyph>" segment and the character span it occupied on the bar. */
    private record Ratio(String cur, String max, String symbol, int start, int end) {
    }

    /** The first ratio whose glyph matches any of {@code glyphs}, or {@code null} when none does. */
    private static Ratio pickByGlyph(List<Ratio> ratios, String... glyphs) {
        for (Ratio ratio : ratios) {
            for (String glyph : glyphs) {
                if (ratio.symbol().equals(glyph)) {
                    return ratio;
                }
            }
        }
        return null;
    }

    /** Renders a string with every non-ASCII char shown as {@code [U+XXXX]} for log-safe glyph IDs. */
    private static String dumpCodepoints(String text) {
        StringBuilder sb = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp > 0x7F) {
                sb.append("[U+").append(Integer.toHexString(cp).toUpperCase()).append(']');
            } else {
                sb.append((char) cp);
            }
        });
        return sb.toString();
    }

    /**
     * Action-bar skill XP gain: "+&lt;amount&gt; &lt;Skill&gt;", optionally followed by the level
     * progress "(&lt;current&gt;/&lt;required&gt;)". Groups: 1 = amount, 2 = skill,
     * 3 = current, 4 = required (3 and 4 are {@code null} when Hypixel omits the progress).
     */
    private static final Pattern SKILL_XP = Pattern.compile(
            "\\+([0-9,.]+)\\s+(Combat|Farming|Mining|Foraging|Fishing|Enchanting|Alchemy|Taming|Carpentry|Runecrafting|Social)\\b"
                    + "(?:\\s*\\(([0-9,.]+)\\s*/\\s*([0-9,.]+)\\))?");
}
