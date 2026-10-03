/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.util.SpokenNumbers;

import java.util.Locale;

/**
 * Which language {@link NarratorVoice} spells its numbers out in.
 *
 * <p>This does not translate an alert - the alerts are written in English and stay that way. It
 * settles the one thing the player can actually hear going wrong, which is a sentence whose words
 * are in one language and whose digits are in another: the system voice expands a digit by its own
 * locale, so a German voice says "vier" in the middle of an English line. Spelling the number out
 * before the voice sees it means the whole announcement comes out in whichever language is picked
 * here. See {@link SpokenNumbers} for why the voice cannot simply be told which language to use.
 *
 * <p>Only the two languages SBS itself is written in are offered. A third would need its own
 * hand-written number words to be worth anything, and offering a language whose numbers still come
 * out English would be the same bug wearing a different label.
 */
public enum NarratorLanguage {

    /**
     * Follow Minecraft's own language setting - which is the closest thing to "the language this
     * player reads the game in" that the client actually knows.
     *
     * <p>Note that it deliberately does <b>not</b> follow the system voice. The voice is what caused
     * the mismatch; matching it would keep the numbers German for a player who has the game in
     * English, which is the case the bug was reported from.
     */
    AUTO("Auto"),

    ENGLISH("English"),

    GERMAN("German");

    private final String displayName;

    NarratorLanguage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The value, or {@link #AUTO} when Gson read a name this enum no longer has ({@code null}). */
    public static NarratorLanguage orAuto(NarratorLanguage value) {
        return value == null ? AUTO : value;
    }

    /** The language the words are actually written in. Never returns {@code null}. */
    public SpokenNumbers.Language resolved() {
        return switch (this) {
            case ENGLISH -> SpokenNumbers.Language.ENGLISH;
            case GERMAN -> SpokenNumbers.Language.GERMAN;
            case AUTO -> fromMinecraft();
        };
    }

    /**
     * Minecraft's selected language as one of ours, English for everything we have no words for.
     *
     * <p>Matched on the {@code de} prefix rather than on the full code, so {@code de_de},
     * {@code de_at} and {@code de_ch} all count - they differ in vocabulary SBS does not use and
     * agree on every number word here.
     */
    private static SpokenNumbers.Language fromMinecraft() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getLanguageManager() == null) {
            return SpokenNumbers.Language.ENGLISH;
        }
        String code = minecraft.getLanguageManager().getSelected();
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("de")
                ? SpokenNumbers.Language.GERMAN : SpokenNumbers.Language.ENGLISH;
    }
}
