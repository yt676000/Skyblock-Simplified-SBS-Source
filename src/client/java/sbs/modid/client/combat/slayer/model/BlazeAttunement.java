/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four attunements the Inferno Demonlord's shield cycles through. Whichever one it is wearing is
 * the one your dagger has to be set to – everything else is cut by 99% – so the whole fight is a
 * "read the word, flip the dagger" loop, and the word is printed right on the boss.
 *
 * <p><b>Two daggers, four modes.</b> One dagger flips between {@link #ASHEN} and {@link #AURIC}, the
 * other between {@link #SPIRIT} and {@link #CRYSTAL}. That split is what {@link #partner()} is for: it
 * is the mode you can reach without changing what you are holding, so knowing it is knowing whether
 * the next swap is a right-click or a hotbar slot.
 *
 * <p><b>Order.</b> While the boss is whole the shield walks the declaration order and hands over
 * after eight matching hits. That order stops holding the moment it splits: each demon owns one
 * dagger's pair and alternates inside it, which is why {@link #next()} is only offered as a hint.
 */
public enum BlazeAttunement {

    ASHEN(0xFF7A6E86),
    SPIRIT(0xFFFFFFFF),
    AURIC(0xFFFFC12E),
    CRYSTAL(0xFF55E5E5);

    /**
     * The four words as they are printed, matched whole. A loose {@code contains} would take the
     * "Crystal" out of any crystal-named item or mob that happens to float past mid-fight.
     */
    private static final Pattern WORD =
            Pattern.compile("\\b(ASHEN|SPIRIT|AURIC|CRYSTAL)\\b", Pattern.CASE_INSENSITIVE);

    private final int fallbackColor;

    BlazeAttunement(int fallbackColor) {
        this.fallbackColor = fallbackColor;
    }

    /** Used only when the nametag the word was read from carried no colour code of its own. */
    public int fallbackColor() {
        return fallbackColor;
    }

    /** The other mode on the SAME dagger: Ashen ↔ Auric, Spirit ↔ Crystal. */
    public BlazeAttunement partner() {
        return values()[(ordinal() + 2) % values().length];
    }

    /** The mode the shield moves to after this one – only while the boss is still in one piece. */
    public BlazeAttunement next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** The attunement named in a colour-stripped nametag, or {@code null} when it names none. */
    public static BlazeAttunement inNametag(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        Matcher matcher = WORD.matcher(plain);
        return matcher.find() ? valueOf(matcher.group(1).toUpperCase(Locale.ROOT)) : null;
    }
}
