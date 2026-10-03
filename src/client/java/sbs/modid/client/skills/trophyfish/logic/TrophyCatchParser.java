/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.logic;

import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.FishingData.TrophyFish;
import sbs.modid.client.skills.trophyfish.model.TrophyTier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the trophy catch line out of chat.
 *
 * <p><b>ASSUMED, not verified.</b> The shape {@code TROPHY FISH! You caught a Blobfish BRONZE.} is
 * the one the wiki and the feature request describe; none of the play instance's ~270 logs contains
 * a single "TROPHY FISH" line. That is why chat counting ships switched off. Every line that starts
 * with "TROPHY FISH!" and does <i>not</i> match is logged once by the tracker, so the first real
 * catch settles it.
 */
public final class TrophyCatchParser {

    /** One parsed catch. */
    public record Catch(TrophyFish fish, TrophyTier tier) {
    }

    static final String PREFIX = "TROPHY FISH!";

    private static final Pattern LINE = Pattern.compile(
            "^TROPHY FISH! You caught an? (.+?) (BRONZE|SILVER|GOLD|DIAMOND)\\.?$");

    private TrophyCatchParser() {
    }

    /** Whether a plain chat line is trophy-shaped at all, matched or not. */
    public static boolean looksLikeTrophyLine(String plain) {
        return plain != null && plain.trim().startsWith(PREFIX);
    }

    /** The catch on a plain (colour-stripped) chat line, or {@code null}. */
    public static Catch parse(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher m = LINE.matcher(plain.trim());
        if (!m.matches()) {
            return null;
        }
        TrophyFish fish = FishingData.trophyByName(m.group(1));
        TrophyTier tier = TrophyTier.byWord(m.group(2));
        return fish == null || tier == null ? null : new Catch(fish, tier);
    }
}
