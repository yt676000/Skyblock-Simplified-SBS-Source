/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which Minecraft blocks a SkyBlock mining material is made of - {@code config/sbs/commission-blocks.json}.
 *
 * <p><b>Why this file is empty and why it stays that way until somebody fills it in game.</b> A
 * commission that says "Mithril" names something Minecraft has never heard of: Hypixel builds it out
 * of ordinary blocks, and which ones is not published anywhere the client can read. That mapping is
 * therefore <i>observed data</i>, and observed data has to be gathered by looking - not written from
 * memory, and not lifted from somebody else's table. Shipping a guessed list would be worse than
 * shipping nothing: the scan would confidently route to gray wool that happens to be a wall.
 *
 * <p>So the scan machinery is complete and this is the only thing missing. Until an entry exists for
 * a material, commissions naming it resolve to
 * {@link sbs.modid.client.skills.mining.model.CommissionTarget.Kind#UNRESOLVED} and say so. Adding one
 * is a line in this file, and {@code /sbs commission blocks} prints the block ids actually around you
 * so the line can be written from what the Dwarven Mines really contain.
 *
 * <p>Format is deliberately the simplest thing that works - a material word mapped to the block-id
 * paths that count as it:
 * <pre>{@code { "mithril": ["prismarine", "light_blue_wool"], "titanium": ["..."] }}</pre>
 * Matching is by <b>substring on the block id path</b>, so one entry covers a family
 * ({@code prismarine} catching {@code prismarine_bricks} and {@code dark_prismarine}) without the
 * file having to list every variant.
 */
public final class CommissionBlocks {

    private static final Path FILE = SBSFiles.root().resolve("commission-blocks.json");

    /** Material word (lower case) -> block-id fragments that count as it. Empty until filled. */
    private static Map<String, List<String>> byMaterial = Map.of();

    private static boolean loaded;

    private CommissionBlocks() {
    }

    /** Reads the file if it exists. Absent or unreadable means "no materials known", never an error. */
    public static synchronized void load() {
        loaded = true;
        byMaterial = Map.of();
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            Map<String, List<String>> parsed = SBSFiles.GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, List<String>>>() { }.getType());
            if (parsed == null) {
                return;
            }
            Map<String, List<String>> normalised = new LinkedHashMap<>(parsed.size());
            for (Map.Entry<String, List<String>> entry : parsed.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                List<String> fragments = new ArrayList<>(entry.getValue().size());
                for (String fragment : entry.getValue()) {
                    if (fragment != null && !fragment.isBlank()) {
                        fragments.add(fragment.toLowerCase(Locale.ROOT).trim());
                    }
                }
                if (!fragments.isEmpty()) {
                    normalised.put(entry.getKey().toLowerCase(Locale.ROOT).trim(), List.copyOf(fragments));
                }
            }
            byMaterial = Map.copyOf(normalised);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Commission] loaded {} material(s) from {}",
                    byMaterial.size(), FILE);
        } catch (IOException | JsonSyntaxException bad) {
            // A hand-edited file with a typo in it is expected, and it is not worth a crash: the
            // feature degrades to "no materials known", which it already handles honestly.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Commission] {} could not be read - "
                    + "material commissions stay unresolved until it parses", FILE, bad);
        }
    }

    /**
     * The block-id fragments for a material word, or an empty list when nothing is known about it.
     * An empty answer is the normal case and the reason a material commission reports unresolved.
     */
    public static synchronized List<String> fragmentsFor(String material) {
        if (!loaded) {
            load();
        }
        if (material == null || material.isBlank()) {
            return List.of();
        }
        return byMaterial.getOrDefault(material.toLowerCase(Locale.ROOT).trim(), List.of());
    }

    /** Whether anything at all is known - what the settings row and the command report. */
    public static synchronized boolean isEmpty() {
        if (!loaded) {
            load();
        }
        return byMaterial.isEmpty();
    }

    /** Where the file lives, for the command to point at. */
    public static Path file() {
        return FILE;
    }
}
