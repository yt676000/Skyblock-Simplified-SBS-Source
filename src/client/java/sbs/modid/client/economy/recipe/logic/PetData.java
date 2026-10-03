/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Recipe Viewer's pet tooltips: the repo's pet data (see {@link PetLore}), loaded lazily from the
 * recipe cache, plus the rarity and level the player picked per pet in the detail view.
 *
 * <p>A pet is a catalogue key {@code PET_<TYPE>} ({@link SkyBlockRepoRecipeProvider#isPetKey}); its
 * rarities are the {@code <TYPE>;<index>} templates the repo has. The <b>default</b> is the pet's
 * highest rarity at its max level - what the hover tooltip always shows; the detail view's choice only
 * changes the detail view.
 */
public final class PetData {

    /** The detail view's choice for one pet. */
    public record Selection(int rarity, int level) {
    }

    private static volatile JsonObject nums;
    private static volatile JsonObject constants;
    private static final Map<String, Selection> CHOSEN = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PetData() {
    }

    /** Drops the parsed files (a fresh repo conversion wrote new ones). */
    static void invalidate() {
        nums = null;
        constants = null;
    }

    private static JsonObject nums() {
        JsonObject value = nums;
        if (value == null) {
            value = read(SkyBlockRepoRecipeProvider.petNumsPath());
            nums = value;
        }
        return value;
    }

    private static JsonObject constants() {
        JsonObject value = constants;
        if (value == null) {
            value = read(SkyBlockRepoRecipeProvider.petConstantsPath());
            constants = value;
        }
        return value;
    }

    private static JsonObject read(Path path) {
        try {
            if (Files.exists(path)) {
                JsonObject parsed = sbs.modid.client.core.config.SBSFiles.GSON.fromJson(
                        Files.readString(path), JsonObject.class);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][RecipeRepo] could not read {}: {}", path.getFileName(), e.toString());
        }
        return new JsonObject();
    }

    /** {@code PET_GOLDEN_DRAGON} -> {@code GOLDEN_DRAGON}. */
    public static String typeOf(String petKey) {
        return petKey.startsWith("PET_") ? petKey.substring(4) : petKey;
    }

    /** The rarity indices this pet exists in, ascending (from the repo's templates). */
    public static List<Integer> raritiesOf(String petKey) {
        String type = typeOf(petKey).toUpperCase(Locale.ROOT);
        Map<String, SkyBlockRepoRecipeProvider.PetTemplate> templates =
                SkyBlockRepoRecipeProvider.getInstance().petTemplates();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < PetLore.RARITIES.length; i++) {
            if (templates.containsKey(type + ";" + i)) {
                out.add(i);
            }
        }
        return out;
    }

    public static int maxLevel(String petKey) {
        return PetLore.maxLevel(constants(), typeOf(petKey));
    }

    /** Highest rarity, max level. */
    public static Selection defaults(String petKey) {
        List<Integer> rarities = raritiesOf(petKey);
        return new Selection(rarities.isEmpty() ? 0 : rarities.getLast(), maxLevel(petKey));
    }

    /** The detail view's current choice (the defaults until the player changes it). */
    public static Selection chosen(String petKey) {
        return CHOSEN.getOrDefault(petKey, defaults(petKey));
    }

    public static void choose(String petKey, int rarity, int level) {
        CHOSEN.put(petKey, new Selection(rarity, Math.max(1, Math.min(maxLevel(petKey), level))));
    }

    /**
     * The tooltip lines for {@code petKey} at {@code selection}, or an empty list when the repo has no
     * template for it (the caller then falls back to the plain item tooltip).
     */
    public static List<String> tooltip(String petKey, Selection selection) {
        String type = typeOf(petKey).toUpperCase(Locale.ROOT);
        SkyBlockRepoRecipeProvider.PetTemplate template =
                SkyBlockRepoRecipeProvider.getInstance().petTemplates().get(type + ";" + selection.rarity());
        if (template == null || template.lore == null) {
            return List.of();
        }
        JsonObject perPet = nums().has(type) ? nums().getAsJsonObject(type) : null;
        String rarity = PetLore.RARITIES[selection.rarity()];
        JsonObject rarityNums = perPet != null && perPet.has(rarity) ? perPet.getAsJsonObject(rarity) : null;
        Set<String> missing = new HashSet<>();
        List<String> lines = PetLore.build(template.displayname, template.lore, rarityNums, selection.level(), missing);
        if (!missing.isEmpty() && LOGGED.add(type + ";" + rarity)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][RecipeRepo] pet {} {} has no stat data for {} - shown as ?",
                    type, rarity, missing);
        }
        return lines;
    }
}
