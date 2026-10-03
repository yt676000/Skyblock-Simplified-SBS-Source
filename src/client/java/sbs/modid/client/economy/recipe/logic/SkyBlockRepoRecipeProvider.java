/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.recipe.model.ForgeRecipe;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pre-populated SkyBlock recipe database – the provider that lets the Recipe Viewer know recipes
 * <b>before</b> the player ever opens them in-game.
 *
 * <p>Hypixel has no official recipe endpoint, so this provider pulls a community-maintained,
 * publicly available SkyBlock item-data repository (a JSON data archive, not mod code):
 * one background download of the repo zip, converted once into SBS's own {@link SbsRecipe} model and
 * cached to {@code config/<modid>-repo-recipes.json}. Every later start loads only the local cache –
 * no network, instant availability, persistent across restarts. The in-game menu scraper
 * ({@link ScrapedRecipeStore}) still runs and supplements / corrects this data.
 */
public final class SkyBlockRepoRecipeProvider implements RecipeProvider {

    /**
     * The catalogue keys this provider created for pets: {@code PET_<TYPE>}, made from a repo id of
     * the form {@code <TYPE>;<digits>} (a pet at a rarity). This is the only reliable pet test in the
     * catalogue - {@code PET_} alone is not one, because {@code PET_SKIN_*}, {@code PET_ITEM_*} and
     * {@code PET_ATTRIBUTE_SHARD_*} are ordinary items with plain repo ids (checked against the cached
     * repo, 2026-09-26: 870 {@code PET_} keys, most of them skins and shards). Read by the Recipe
     * Viewer's Pets filter ({@code RecipeEntryKind}).
     */
    private static final java.util.Set<String> PET_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether {@code id} is a pet this provider catalogued (see {@link #PET_KEYS}). */
    public static boolean isPetKey(String id) {
        return id != null && PET_KEYS.contains(id);
    }

    private static final SkyBlockRepoRecipeProvider INSTANCE = new SkyBlockRepoRecipeProvider();

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/refs/heads/master
// METHOD: GET (returns a zip archive)
// PURPOSE: The crafting-recipe database behind the Recipe Viewer, fetched once and cached on
//   disk rather than per lookup.
// DATA SENT: Nothing in the URL and no body.
// DATA RECEIVED: A zip archive of public JSON. Entries are read through ZipInputStream and
//   parsed with Gson into fixed types; only paths under the items directory are read, nothing
//   is executed, and nothing is written outside the mod's own cache directory.
// SAFETY DECLARATION: A public read-only data repository over HTTPS, needing no
//   authentication. Nothing identifying is sent - no licence token, no uuid, no account
//   data, no OS telemetry - and the request is not gated on any consent switch because
//   it carries nothing about the user. Provenance and licensing: THIRD-PARTY.md,
//   "Game data sources". What leaves the machine at all: PRIVACY.md.
// ============================================================================
    private static final String REPO_ZIP_URL =
            "https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/refs/heads/master";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, SbsRecipe>>() {
    }.getType();
    private static final Type APPEARANCE_TYPE = new TypeToken<Map<String, Appearance>>() {
    }.getType();

    /** Human-readable source note stamped on every recipe from this provider. */
    public static final String SOURCE_NAME = "SkyBlock recipe database";

    /** Base64 skull texture inside a repo item's {@code nbttag} SkullOwner. */
    private static final java.util.regex.Pattern SKULL_VALUE =
            java.util.regex.Pattern.compile("Value:\"([A-Za-z0-9+/=]+)\"");

    /**
     * Modern custom-model reference inside a repo item's {@code nbttag}
     * ({@code ItemModel:"hypixel_skyblock:item/..."}) – how current Hypixel items carry their
     * custom texture (server resource pack) instead of a skull skin.
     */
    private static final java.util.regex.Pattern ITEM_MODEL =
            java.util.regex.Pattern.compile("ItemModel:\"([a-z0-9_.:/-]+)\"");

    /** Repo grid keys, row-major (A = top row, 1 = left column). */
    private static final String[] GRID_KEYS = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private final Map<String, SbsRecipe> recipes = new ConcurrentHashMap<>();

    /**
     * How every repo item actually looks in-game: legacy item id + damage and (for skull items) the
     * base64 skin. This is what fixes the icons whose official-API data is wrong or missing – the
     * Abiphones are {@code material: PAPER} without a skin there, while in the game (and here) they
     * are textured player heads; items like {@code ABIPHONE_XIII_JADE} are missing entirely.
     */
    private final Map<String, Appearance> appearance = new ConcurrentHashMap<>();

    /**
     * Item id → its plain lore text (colour-stripped, lower-cased, lines joined) – the index behind
     * "search also matches item descriptions". Built during the one-time zip conversion (the repo
     * item files carry the full lore) and cached to disk beside the appearance index; ~1–2 MB of
     * strings, read-only after load.
     */
    private final Map<String, String> loreIndex = new ConcurrentHashMap<>();
    private static final Type LORE_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    /**
     * Output id → its Forge recipe, the source the local forge-flip ranking prices.
     *
     * <p>Collected during the same zip pass as everything else: the item files were already being
     * parsed for their crafting grid, and their {@code recipes} array — which carries the forge
     * variants <b>and their durations</b> — was simply being dropped. Reading it here costs one field
     * on {@link RepoItem} and one cache file; a downloader of its own in the forge package would have
     * pulled the whole archive a second time.
     */
    private final Map<String, ForgeRecipe> forgeRecipes = new ConcurrentHashMap<>();
    private static final Type FORGE_TYPE = new TypeToken<Map<String, ForgeRecipe>>() {
    }.getType();

    private SkyBlockRepoRecipeProvider() {
    }

    /** One repo item's in-game appearance. */
    public static final class Appearance {
        public String itemid;
        public int damage;
        public String skull;
        /** Custom item-model id ({@code hypixel_skyblock:item/...}), or {@code null}. */
        public String itemModel;
        public String name;
        /**
         * {@code true} for a pet ({@code PET_<TYPE>} made from a {@code <TYPE>;<digits>} repo id),
         * absent otherwise. Persisted so a cache-only start can rebuild {@link #PET_KEYS}: without it
         * the set was filled only during a fresh download, and every later start had no pets.
         */
        public Boolean pet;
    }

    /** One pet rarity's repo item: the name and lore templates with {@code {LVL}}, stat and {@code {n}} placeholders. */
    public static final class PetTemplate {
        public String displayname;
        public List<String> lore;
    }

    /** {@code <TYPE>;<rarity index>} -> template. Filled from the zip, persisted, reloaded from the cache. */
    private final Map<String, PetTemplate> petTemplates = new java.util.concurrent.ConcurrentHashMap<>();

    /** The pet templates by {@code <TYPE>;<rarity index>} (read-only view). */
    public Map<String, PetTemplate> petTemplates() {
        return java.util.Collections.unmodifiableMap(petTemplates);
    }

    /** The pet keys an appearance map marks as pets - the cache-load half of {@link #PET_KEYS}. */
    static java.util.Set<String> petKeysOf(Map<String, Appearance> appearances) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        appearances.forEach((key, app) -> {
            if (app != null && Boolean.TRUE.equals(app.pet)) {
                keys.add(key);
            }
        });
        return keys;
    }

    /** Replaces {@link #PET_KEYS} with the pets in {@code appearances} (cache load; also used by the test). */
    static void rememberPetKeys(Map<String, Appearance> appearances) {
        PET_KEYS.clear();
        PET_KEYS.addAll(petKeysOf(appearances));
    }

    private static final Type PET_TEMPLATE_TYPE = new TypeToken<Map<String, PetTemplate>>() {
    }.getType();

    /** The repo appearance of a SkyBlock id, or {@code null} (id-convention tolerant). */
    public Appearance appearanceOf(String id) {
        if (id == null) {
            return null;
        }
        Appearance found = appearance.get(id.toUpperCase(java.util.Locale.ROOT));
        // Official data-variant ids use ':' where the repo uses '-' (WOOD_STEP:4 vs WOOD_STEP-4).
        return found != null ? found : appearance.get(SkyBlockItemCatalog.canonicalId(id));
    }

    public static SkyBlockRepoRecipeProvider getInstance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "SkyBlock (recipe database)";
    }

    @Override
    public List<SbsRecipe> loadRecipes() {
        return new ArrayList<>(recipes.values());
    }

    /** Loads the local cache; downloads and converts the repo once when no cache exists yet. */
    public synchronized void start() {
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SBS-RecipeRepo");
            thread.setDaemon(true);
            return thread;
        }).execute(this::loadOrDownload);
    }

    private void loadOrDownload() {
        try {
            Path cache = cachePath();
            Path appearanceCache = appearancePath();
            if (Files.exists(cache) && Files.exists(appearanceCache)) {
                try (Reader reader = Files.newBufferedReader(cache)) {
                    Map<String, SbsRecipe> stored = GSON.fromJson(reader, MAP_TYPE);
                    if (stored != null && !stored.isEmpty()) {
                        recipes.putAll(stored);
                        try (Reader appearanceReader = Files.newBufferedReader(appearanceCache)) {
                            Map<String, Appearance> storedAppearance =
                                    GSON.fromJson(appearanceReader, APPEARANCE_TYPE);
                            if (storedAppearance != null) {
                                appearance.putAll(storedAppearance);
                            }
                        }
                        // A cache written before item models / essence costs / pet skulls were
                        // indexed lacks them – refresh once so custom items, star pricing and the
                        // profile viewer's pet icons work.
                        boolean hasModels = appearance.values().stream()
                                .anyMatch(app -> app.itemModel != null && !app.itemModel.isEmpty());
                        // Pets by their flag, not by a "PET_" key: a cache from before the flag existed
                        // has pet appearances without it and has to rebuild once to get the Pets
                        // filter and the pet tooltip - the same refresh-once as the other indexes.
                        boolean hasPets = appearance.values().stream()
                                .anyMatch(app -> app != null && Boolean.TRUE.equals(app.pet))
                                && Files.exists(petNumsPath()) && Files.exists(petConstantsPath())
                                && Files.exists(petTemplatesPath());
                        if (hasPets) {
                            rememberPetKeys(appearance);
                            try (Reader templateReader = Files.newBufferedReader(petTemplatesPath())) {
                                Map<String, PetTemplate> storedTemplates = GSON.fromJson(templateReader,
                                        PET_TEMPLATE_TYPE);
                                if (storedTemplates != null) {
                                    petTemplates.putAll(storedTemplates);
                                }
                            }
                        }
                        // Lore index too: a cache from before the lore search shipped lacks it, and
                        // only the zip conversion can build it - refresh once like the other indexes.
                        if (Files.exists(lorePath())) {
                            try (Reader loreReader = Files.newBufferedReader(lorePath())) {
                                Map<String, String> storedLore = GSON.fromJson(loreReader, LORE_TYPE);
                                if (storedLore != null) {
                                    loreIndex.putAll(storedLore);
                                }
                            }
                        }
                        // Forge recipes joined this cache set later than the rest, so an install from
                        // before the forge ranking shipped has every other index and none of these.
                        // Without this condition that install would never re-download and its forge
                        // fallback would be permanently empty with nothing on screen explaining why.
                        if (Files.exists(forgePath())) {
                            try (Reader forgeReader = Files.newBufferedReader(forgePath())) {
                                Map<String, ForgeRecipe> storedForge =
                                        GSON.fromJson(forgeReader, FORGE_TYPE);
                                if (storedForge != null) {
                                    storedForge.forEach((key, value) -> {
                                        if (value != null && value.usable()) {
                                            forgeRecipes.put(key, value);
                                        }
                                    });
                                }
                            }
                        }
                        if (!appearance.isEmpty() && hasModels && hasPets && Files.exists(essencePath())
                                && Files.exists(essenceShopsPath())
                                && !loreIndex.isEmpty() && !forgeRecipes.isEmpty()) {
                            publishAppearance();
                            RecipeRegistry.getInstance().markDirty();
                            SkyblockSimplifiedSBS.LOGGER.info(
                                    "[SBS][RecipeRepo] Loaded {} recipe(s) and {} item appearance(s) from disk cache.",
                                    recipes.size(), appearance.size());
                            return;
                        }
                        SkyblockSimplifiedSBS.LOGGER.info(
                                "[SBS][RecipeRepo] Cache predates the item-model/essence/pet/forge/perk index"
                                        + " - refreshing once.");
                        recipes.clear();
                        appearance.clear();
                        forgeRecipes.clear();
                    }
                }
            }
            // Also re-downloads once for installs whose cache predates the appearance index.
            download();
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Recipe database load failed", e);
        }
    }

    private void download() {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][RecipeRepo] Downloading the SkyBlock recipe database (one-time)...");
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(REPO_ZIP_URL))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "SkyblockSimplifiedSBS")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][RecipeRepo] Download returned status {}.", response.statusCode());
                return;
            }
            int parsed = 0;
            try (ZipInputStream zip = new ZipInputStream(response.body())) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    // Star-upgrade essence costs (constants/essencecosts.json) for the value checker.
                    if (entryName.endsWith("constants/essencecosts.json")) {
                        saveEssence(readEntry(zip));
                        continue;
                    }
                    // Essence shop perk costs (constants/essenceshops.json) for the shop overview:
                    // every level's price, which the shop menu itself never shows.
                    if (entryName.endsWith("constants/essenceshops.json")) {
                        saveEssenceShops(readEntry(zip));
                        continue;
                    }
                    // Pet stat table (constants/petnums.json) and pet levelling constants
                    // (constants/pets.json: max level per pet) - the Recipe Viewer's pet tooltip.
                    if (entryName.endsWith("constants/petnums.json")) {
                        saveText(petNumsPath(), readEntry(zip));
                        continue;
                    }
                    if (entryName.endsWith("constants/pets.json")) {
                        saveText(petConstantsPath(), readEntry(zip));
                        continue;
                    }
                    if (!entryName.contains("/items/") || !entryName.endsWith(".json")) {
                        continue;
                    }
                    RepoItem item = GSON.fromJson(readEntry(zip), RepoItem.class);
                    collectAppearance(item);
                    collectLore(item);
                    collectForge(item);
                    if (convert(item)) {
                        parsed++;
                    }
                }
            }
            save();
            saveAppearance();
            saveText(petTemplatesPath(), GSON.toJson(petTemplates, PET_TEMPLATE_TYPE));
            PetData.invalidate();
            saveLore();
            saveForge();
            publishAppearance();
            RecipeRegistry.getInstance().markDirty();
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][RecipeRepo] Converted {} recipe(s), {} item appearance(s) and {} forge "
                            + "recipe(s) into the local database.",
                    parsed, appearance.size(), forgeRecipes.size());
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][RecipeRepo] Recipe database download failed: {}", e.toString());
        }
    }

    /**
     * Records how a repo item looks in-game. Mob / pet variants (ids like {@code ENDER_DRAGON;4})
     * and template names ("[Lvl {LVL}] ...") are skipped – they are not tradeable item entries.
     */
    private void collectAppearance(RepoItem item) {
        if (item == null || item.internalname == null || item.internalname.isEmpty()) {
            return;
        }
        // Pet variants are keyed "<TYPE>;<RARITY>" (ENDER_DRAGON;4). Their skull texture is the same
        // across rarities, so index them once under PET_<TYPE> for the profile viewer's pet icons.
        String key;
        boolean pet = false;
        if (item.internalname.contains(";")) {
            String[] parts = item.internalname.split(";", 2);
            if (parts.length == 2 && !parts[0].isEmpty()
                    && parts[1].chars().allMatch(Character::isDigit)) {
                key = "PET_" + parts[0].toUpperCase(java.util.Locale.ROOT);
                pet = true;
                PET_KEYS.add(key);
                // Every rarity's template, before any of the texture checks below can return early.
                if (item.lore != null && item.displayname != null) {
                    PetTemplate template = new PetTemplate();
                    template.displayname = item.displayname;
                    template.lore = new ArrayList<>(item.lore);
                    petTemplates.put(item.internalname.toUpperCase(java.util.Locale.ROOT), template);
                }
            } else {
                return; // other ";" ids (mob variants, templates) are not tradeable icons
            }
        } else {
            key = item.internalname.toUpperCase(java.util.Locale.ROOT);
        }
        String name = pet ? prettify(item.internalname.substring(0, item.internalname.indexOf(';')))
                : stripCodes(item.displayname != null ? item.displayname : prettify(item.internalname)).trim();
        if (name.isEmpty() || name.contains("{")) {
            return;
        }
        Appearance app = new Appearance();
        app.itemid = item.itemid;
        app.damage = Math.max(0, item.damage);
        app.name = name;
        app.pet = pet ? Boolean.TRUE : null;
        if (item.nbttag != null) {
            // Pets are always skulls; other items only when their itemid says so.
            if (pet || (item.itemid != null && item.itemid.endsWith("skull"))) {
                java.util.regex.Matcher matcher = SKULL_VALUE.matcher(item.nbttag);
                if (matcher.find()) {
                    app.skull = matcher.group(1);
                    app.itemid = "skull";  // force the player-head material for pet skins
                }
            }
            // Modern custom items (Abiphones, Arachne Fragment, ...) are plain vanilla items
            // (often PAPER) whose real look comes from Hypixel's resource-pack item model.
            java.util.regex.Matcher model = ITEM_MODEL.matcher(item.nbttag);
            if (model.find()) {
                app.itemModel = model.group(1);
            }
        }
        if (pet && (app.skull == null || app.skull.isEmpty())) {
            return; // no usable texture – leave the pet unresolved rather than a wrong head
        }
        // Pets: first rarity seen wins (same texture), don't overwrite with later rarities.
        if (pet && appearance.containsKey(key)) {
            return;
        }
        appearance.put(key, app);
    }

    /**
     * Publishes the appearance data: repo-only items become searchable catalogue entries (the
     * official items API misses several live items) and the icon cache re-resolves so items whose
     * API material is wrong (Abiphones: PAPER instead of a textured skull) get their real look.
     */
    private void publishAppearance() {
        if (appearance.isEmpty()) {
            return;
        }
        Map<String, SkyBlockItemCatalog.Entry> extra = new java.util.HashMap<>();
        for (Map.Entry<String, Appearance> mapEntry : appearance.entrySet()) {
            Appearance app = mapEntry.getValue();
            String material = materialOf(app.itemid);
            if (material != null && app.damage > 0) {
                material = material + "-" + app.damage;
            }
            extra.put(mapEntry.getKey(),
                    new SkyBlockItemCatalog.Entry(mapEntry.getKey(), app.name, material, app.skull, null));
        }
        SkyBlockItemCatalog.getInstance().addRepoEntries(extra);
        SkyBlockItemIcons.getInstance().invalidate();
    }

    /** Converts one repo item's crafting recipe into an {@link SbsRecipe}; returns true if stored. */
    private boolean convert(RepoItem item) {
        if (item == null || item.internalname == null || item.recipe == null || item.recipe.isEmpty()) {
            return false;
        }
        String resultName = stripCodes(item.displayname != null ? item.displayname : item.internalname).trim();
        // Prefer the official catalogue's modern material (the repo's itemid is a legacy 1.8 id);
        // the icon registry re-resolves against the catalogue at render time either way.
        SkyBlockItemCatalog.Entry resultEntry = SkyBlockItemCatalog.getInstance().byId(item.internalname);
        String resultMaterial = resultEntry != null && resultEntry.material != null
                ? resultEntry.material : materialOf(item.itemid);
        ItemRef result = new ItemRef(item.internalname, resultMaterial, resultName, 1);
        SbsRecipe recipe = new SbsRecipe("Crafting", SOURCE_NAME, result);

        ItemRef[] grid = new ItemRef[9];
        List<ItemRef> ingredients = new ArrayList<>();
        for (int i = 0; i < GRID_KEYS.length; i++) {
            String cell = item.recipe.get(GRID_KEYS[i]);
            if (cell == null || cell.isBlank()) {
                continue;
            }
            // Cell format: "ITEM_ID:count" (count optional).
            String id = cell;
            int count = 1;
            int colon = cell.lastIndexOf(':');
            if (colon > 0) {
                try {
                    count = (int) Math.ceil(Double.parseDouble(cell.substring(colon + 1)));
                    id = cell.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // no numeric suffix – the whole cell is the id
                }
            }
            // Name / material resolve lazily against the item catalogue at display time.
            SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
            ItemRef ref = new ItemRef(id,
                    entry != null ? entry.material : null,
                    entry != null ? entry.name : prettify(id),
                    Math.max(1, count));
            grid[i] = ref;
            merge(ingredients, ref);
        }
        if (ingredients.isEmpty()) {
            return false;
        }
        recipe.grid = grid;
        recipe.ingredients = ingredients;
        recipes.put(recipe.key() + "|" + item.internalname, recipe);
        return true;
    }

    private static void merge(List<ItemRef> ingredients, ItemRef ref) {
        for (ItemRef existing : ingredients) {
            if (existing.lookupId().equals(ref.lookupId())) {
                existing.count += ref.count;
                return;
            }
        }
        ingredients.add(new ItemRef(ref.skyblockId, ref.material, ref.name, ref.count));
    }

    /** "minecraft:golden_carrot" → "golden_carrot"; null-safe. */
    private static String materialOf(String itemid) {
        if (itemid == null || itemid.isEmpty()) {
            return null;
        }
        int colon = itemid.indexOf(':');
        return colon >= 0 ? itemid.substring(colon + 1) : itemid;
    }

    /** "ENCHANTED_GOLDEN_CARROT" → "Enchanted Golden Carrot" (fallback when not in the catalogue). */
    private static String prettify(String id) {
        String[] words = id.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String stripCodes(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }

    private static String readEntry(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = zip.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private synchronized void save() {
        Path path = cachePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(recipes, MAP_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save recipe database", e);
        }
    }

    private synchronized void saveAppearance() {
        Path path = appearancePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(appearance, APPEARANCE_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save appearance index", e);
        }
    }

    private static Path cachePath() {
        return SBSFiles.recipesFile();
    }

    private static Path appearancePath() {
        return cachePath().getParent().resolve("Repo_Item_Appearance.json");
    }

    private static Path lorePath() {
        return cachePath().getParent().resolve("Repo_Item_Lore.json");
    }

    /** Indexes an item's plain lore for the lore search; variant ids ({@code X;4}) are skipped. */
    private void collectLore(RepoItem item) {
        if (item == null || item.internalname == null || item.internalname.isEmpty()
                || item.internalname.contains(";") || item.lore == null || item.lore.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (String line : item.lore) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            // One-time conversion path - the regex strip is fine here (never a hot loop).
            String plain = line.replaceAll("§.", "").trim();
            if (!plain.isEmpty()) {
                joined.append(plain.toLowerCase(java.util.Locale.ROOT)).append(' ');
            }
        }
        if (joined.length() > 0) {
            loreIndex.put(item.internalname.toUpperCase(java.util.Locale.ROOT), joined.toString().trim());
        }
    }

    private synchronized void saveLore() {
        Path path = lorePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(loreIndex, LORE_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save lore index", e);
        }
    }

    /** Item id → plain lore text (read-only); empty until the repo (re)conversion has run. */
    public Map<String, String> loreIndex() {
        return java.util.Collections.unmodifiableMap(loreIndex);
    }

    // ------------------------------------------------------------------
    // Forge recipes
    // ------------------------------------------------------------------

    /**
     * Pulls the forge variant out of one item's {@code recipes} array, if it has one.
     *
     * <p>An item may list several recipes (a crafting grid and a forge run for the same result); only
     * the first forge entry is kept, which is what the backend does and is enough — the forge menu
     * offers one way to make a thing.
     *
     * <p>An entry with no duration, no inputs or no resolvable output is dropped rather than stored
     * with a placeholder: profit per forge <i>hour</i> divided by a guessed duration is a confidently
     * wrong number, and the ranking is sorted by it.
     */
    private void collectForge(RepoItem item) {
        if (item == null || item.recipes == null || item.recipes.isEmpty()) {
            return;
        }
        for (RepoRecipeEntry entry : item.recipes) {
            if (entry == null || !"forge".equalsIgnoreCase(entry.type) || entry.inputs == null) {
                continue;
            }
            int duration = (int) number(entry.duration, 0);
            String output = entry.overrideOutputId != null && !entry.overrideOutputId.isBlank()
                    ? entry.overrideOutputId.trim() : item.internalname;
            if (duration <= 0 || output == null || output.isBlank()) {
                return; // first forge recipe wins, even when it is unusable
            }
            ForgeRecipe recipe = new ForgeRecipe();
            recipe.outputId = output.toUpperCase(java.util.Locale.ROOT);
            recipe.displayName = stripCodes(item.displayname != null ? item.displayname
                    : prettify(output)).trim();
            recipe.outputCount = Math.max(1, number(entry.count, 1));
            recipe.durationSeconds = duration;
            recipe.requirement = stripCodes(item.crafttext == null ? "" : item.crafttext).trim();
            for (String raw : entry.inputs) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // Cell format is "ITEM_ID:count", the count optional and sometimes fractional.
                int colon = raw.lastIndexOf(':');
                String id = colon > 0 ? raw.substring(0, colon) : raw;
                double count = colon > 0 ? number(raw.substring(colon + 1), 1) : 1;
                if (!id.isBlank() && count > 0) {
                    recipe.inputs.add(new ForgeRecipe.Ingredient(
                            id.trim().toUpperCase(java.util.Locale.ROOT), count));
                }
            }
            if (recipe.usable()) {
                forgeRecipes.putIfAbsent(recipe.outputId, recipe);
            }
            return;
        }
    }

    /**
     * Tolerant number parse. The archive types these fields loosely — {@code count} and {@code
     * duration} arrive as JSON numbers on some items and as quoted strings on others — so they are
     * declared {@code String} (Gson's string adapter reads a number token, a numeric field does not
     * read a string) and converted here.
     */
    private static double number(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized void saveForge() {
        Path path = forgePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(forgeRecipes, FORGE_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save forge recipes", e);
        }
    }

    private static Path forgePath() {
        return cachePath().getParent().resolve("Repo_Forge_Recipes.json");
    }

    /**
     * Output id → forge recipe (read-only). Empty until the repo conversion has run, which is a real
     * state the forge ranking has to render rather than treat as "nothing is profitable".
     */
    public Map<String, ForgeRecipe> forgeRecipes() {
        return java.util.Collections.unmodifiableMap(forgeRecipes);
    }

    static Path petNumsPath() {
        return cachePath().getParent().resolve("Repo_Pet_Nums.json");
    }

    static Path petConstantsPath() {
        return cachePath().getParent().resolve("Repo_Pet_Constants.json");
    }

    private static Path petTemplatesPath() {
        return cachePath().getParent().resolve("Repo_Pet_Templates.json");
    }

    private static void saveText(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save {}", path.getFileName(), e);
        }
    }

    private static Path essencePath() {
        return cachePath().getParent().resolve("Essence_Costs.json");
    }

    private synchronized void saveEssence(String json) {
        try {
            Files.createDirectories(essencePath().getParent());
            Files.writeString(essencePath(), json);
            essenceCostsParsed = null; // re-parse lazily from the fresh file
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save essence costs", e);
        }
    }

    private volatile com.google.gson.JsonObject essenceCostsParsed;

    /**
     * The star-upgrade essence table ({@code constants/essencecosts.json}): item id →
     * {@code {"type": "Wither", "1": 10, ...}}. {@code null} until the repo download stored it
     * (the value checker then shows stars as unpriced instead of failing).
     */
    public com.google.gson.JsonObject essenceCosts() {
        com.google.gson.JsonObject parsed = essenceCostsParsed;
        if (parsed != null) {
            return parsed;
        }
        try {
            Path path = essencePath();
            if (!Files.exists(path)) {
                return null;
            }
            parsed = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            essenceCostsParsed = parsed;
            return parsed;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][RecipeRepo] Essence cost table unreadable: {}", e.toString());
            return null;
        }
    }

    private static Path essenceShopsPath() {
        return cachePath().getParent().resolve("Essence_Shops.json");
    }

    private synchronized void saveEssenceShops(String json) {
        try {
            Files.createDirectories(essenceShopsPath().getParent());
            Files.writeString(essenceShopsPath(), json);
            essenceShopsParsed = null; // re-parse lazily from the fresh file
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][RecipeRepo] Failed to save essence shop perks", e);
        }
    }

    private volatile com.google.gson.JsonObject essenceShopsParsed;

    /**
     * The essence shop perk table ({@code constants/essenceshops.json}): essence id →
     * perk key → {@code {"name": "Forbidden Strength", "costs": [100, 250, ...]}}, where
     * {@code costs[i]} is the price of the step from level {@code i} to {@code i+1}.
     *
     * <p>This is the whole reason the shop overview can exist: the menu states only what the
     * <i>next</i> level costs, so the cost of maxing a perk is not computable from the screen.
     * {@code null} until the repo download stored it - the overview then says so rather than
     * showing a total it cannot stand behind.
     */
    public com.google.gson.JsonObject essenceShops() {
        com.google.gson.JsonObject parsed = essenceShopsParsed;
        if (parsed != null) {
            return parsed;
        }
        try {
            Path path = essenceShopsPath();
            if (!Files.exists(path)) {
                return null;
            }
            parsed = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            essenceShopsParsed = parsed;
            return parsed;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][RecipeRepo] Essence shop table unreadable: {}", e.toString());
            return null;
        }
    }

    /** Gson mirror of one repo item file (only the fields we use). */
    private static final class RepoItem {
        String internalname;
        String displayname;
        String itemid;
        int damage;
        String nbttag;
        Map<String, String> recipe;
        /** The item's in-game description lines (§-coded) – source of the lore search index. */
        java.util.List<String> lore;
        /** Free-text unlock note, e.g. a Heart of the Mountain tier. */
        String crafttext;
        /**
         * The item's alternative recipes, of which only {@code type: "forge"} is read here. The
         * crafting grid above is the same data in a different shape and stays the Recipe Viewer's
         * source; this array is where the forge <b>durations</b> live and nowhere else does.
         */
        java.util.List<RepoRecipeEntry> recipes;
    }

    /**
     * One entry of a repo item's {@code recipes} array. {@code count} and {@code duration} are typed
     * as strings on purpose – see {@link #number}.
     */
    private static final class RepoRecipeEntry {
        String type;
        java.util.List<String> inputs;
        String count;
        String duration;
        String overrideOutputId;
    }
}
