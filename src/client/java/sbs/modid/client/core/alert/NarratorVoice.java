/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.util.SpokenNumbers;

/**
 * Speaks alert text through the operating system's text-to-speech voice.
 *
 * <p>This is the channel that survives a fullscreen, fully muted game: the speech is synthesized by
 * the OS and played on its own audio path, so neither Minecraft's volume sliders nor its window
 * state affect it. No voice assets are shipped - whatever voice the player has configured in
 * Windows / macOS is the voice they hear, and the text can be anything.
 *
 * <h2>The player's narrator setting is never touched</h2>
 * Minecraft's own wrapper ({@code GameNarrator.saySystemNow}) refuses to speak unless the player's
 * Narrator option is set to System or All, so going through it would mean either staying silent or
 * changing an accessibility setting behind the player's back. This talks to the underlying
 * {@code com.mojang.text2speech.Narrator} directly instead: it speaks on our own per-alert opt-in
 * without reading, writing or caring about {@code NarratorStatus}. A player who runs the narrator as
 * a screen reader keeps exactly the configuration they had.
 *
 * <p>For the same reason nothing here ever interrupts: {@code say(text, false, volume)} queues
 * behind whatever the narrator is currently reading rather than cutting it off. Interrupting would
 * talk over a screen-reader user mid-sentence.
 *
 * <h2>Staleness is handled here, not by the OS</h2>
 * An alert that has been waiting longer than {@link #STALE_MS} is dropped rather than spoken: after
 * a burst of pests, hearing about the first one a minute later is worse than not hearing about it.
 * A minimum gap between utterances keeps a rapid sequence from becoming a backlog in the first
 * place. Both are enforced before handing anything to the OS, because once speech is queued there
 * it cannot be recalled.
 */
public final class NarratorVoice {

    /** Shortest gap between two spoken alerts. Below this the newer one replaces the older. */
    private static final long MIN_GAP_MS = 2_500L;

    /** An utterance older than this is not worth speaking any more. */
    private static final long STALE_MS = 5_000L;

    /** Longest text handed to the synthesizer - an alert is a sentence, not a paragraph. */
    private static final int MAX_CHARS = 120;

    /** When the last utterance was handed over. */
    private static volatile long lastSpokeAt;

    /** The most recent text waiting for the gap to pass, or {@code null} when nothing is pending. */
    private static volatile String pending;
    private static volatile long pendingSince;

    /** Cached availability probe; the library is loaded once and its verdict does not change. */
    private static volatile Boolean available;

    private NarratorVoice() {
    }

    /**
     * Whether text-to-speech actually works on this machine.
     *
     * <p>The library is present on every platform but only functional where the OS provides a
     * speech backend - Windows (SAPI) and macOS do, most Linux setups do not. The settings page asks
     * this before offering the channel, so it is never presented as working where it cannot.
     */
    public static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean probe;
        try {
            probe = com.mojang.text2speech.Narrator.getNarrator().active();
        } catch (Throwable t) {
            // A missing or broken speech backend must not take a feature down with it.
            probe = false;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Narrator] text-to-speech is unavailable here.", t);
        }
        available = probe;
        return probe;
    }

    /**
     * Speaks {@code text}, or defers it when one was just spoken.
     *
     * @return whether the text was spoken or queued; {@code false} means the channel could not take
     *         it and the caller's other channels are all the player will get
     */
    public static boolean speak(String text, int volumePercent, NarratorLanguage language) {
        if (text == null || text.isBlank() || !available()) {
            return false;
        }
        String trimmed = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        long now = System.currentTimeMillis();
        if (now - lastSpokeAt < MIN_GAP_MS) {
            // Too soon: hold the NEWEST text only. A queue here would read out a situation that has
            // already moved on - the last alert is the one still worth hearing.
            pending = trimmed;
            pendingSince = now;
            return true;
        }
        return say(trimmed, volumePercent, language, now);
    }

    /**
     * Releases a deferred utterance once the gap has passed, or drops it once it is stale. Called
     * from the client tick.
     */
    public static void tick(int volumePercent, NarratorLanguage language) {
        String waiting = pending;
        if (waiting == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - pendingSince > STALE_MS) {
            pending = null;   // the moment has passed; saying it now would be noise
            return;
        }
        if (now - lastSpokeAt >= MIN_GAP_MS) {
            pending = null;
            say(waiting, volumePercent, language, now);
        }
    }

    /** Hands one utterance to the OS. Never interrupts what is already being read. */
    private static boolean say(String text, int volumePercent, NarratorLanguage language, long now) {
        try {
            float volume = Math.max(1, Math.min(100, volumePercent)) / 100.0f;
            com.mojang.text2speech.Narrator.getNarrator().say(spoken(text, language), false, volume);
            lastSpokeAt = now;
            return true;
        } catch (Throwable t) {
            available = false;
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Narrator] speaking failed - the narrator channel is off for this session.", t);
            return false;
        }
    }

    /**
     * The alert as the synthesizer should read it, which is not quite the alert as it is written.
     *
     * <p>Every number is spelled out here and only here - the last thing that happens to the text
     * before it leaves for the OS. That placement is the point: the voice is the only consumer that
     * gets words instead of digits, so chat, the HUD, the titles and the log keep "Plot 4" while the
     * speech gets "Plot four". {@link SpokenNumbers} carries the reason a voice cannot simply be
     * told to read the digits in the right language.
     *
     * <p>It also sits below the staleness and gap logic rather than above it, so {@link #pending}
     * still holds what the feature wrote. Nothing about <i>when</i> or <i>whether</i> something is
     * spoken is decided here - only how it reads.
     *
     * <p>The length cap is applied before this, to the text as written, so the same alert is cut at
     * the same word whatever the language setting says. Spelled-out numbers are longer than digits,
     * and letting them push a sentence over the cap would mean the setting quietly changed how much
     * of an alert the player hears.
     */
    private static String spoken(String text, NarratorLanguage language) {
        return SpokenNumbers.rewrite(text, NarratorLanguage.orAuto(language).resolved());
    }

    /** Forgets any deferred speech - used on world change so nothing carries across a warp. */
    public static void clearPending() {
        pending = null;
    }
}
