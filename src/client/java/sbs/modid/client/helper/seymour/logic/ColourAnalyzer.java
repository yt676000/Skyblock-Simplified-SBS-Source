/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.seymour.model.ColourTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SeymourColour#analyse} with the player's thresholds and words and the current targets - the
 * one place the tooltip and the collection screen get an analysis from, so both always agree.
 */
public final class ColourAnalyzer {

    /** Nearest targets kept per analysis: the best for the tooltip, three for the details. */
    public static final int MATCHES = 3;

    private static String parsedWordsFrom;
    private static List<String> parsedWords = List.of();

    private ColourAnalyzer() {
    }

    public static SBSConfig.SeymourColourSettings cfg() {
        return ConfigManager.getInstance().get().seymourColours;
    }

    public static SeymourColour.Thresholds thresholds() {
        SBSConfig.SeymourColourSettings cfg = cfg();
        return SeymourColour.Thresholds.of(cfg.exactUpTo, cfg.nearUpTo, cfg.closeUpTo);
    }

    /** The configured words, re-parsed only when the setting text changed. */
    public static synchronized List<String> words() {
        String raw = cfg().words;
        if (!java.util.Objects.equals(raw, parsedWordsFrom)) {
            parsedWords = SeymourColour.parseWords(raw);
            parsedWordsFrom = raw;
        }
        return parsedWords;
    }

    /**
     * Analyses {@code rgb}. {@code ownId} is the analysed item's own SkyBlock id: a dyed armor piece
     * with a catalogue colour would otherwise always be an exact match for itself.
     */
    public static SeymourColour.Analysis analyse(int rgb, String ownId) {
        List<ColourTarget> targets = ColourTargets.current();
        if (ownId != null) {
            List<ColourTarget> others = new ArrayList<>(targets.size());
            for (ColourTarget target : targets) {
                if (!target.itemId().equalsIgnoreCase(ownId)) {
                    others.add(target);
                }
            }
            targets = others;
        }
        return SeymourColour.analyse(rgb, targets, thresholds(), words(), MATCHES);
    }
}
