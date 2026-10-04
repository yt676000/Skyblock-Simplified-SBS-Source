/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import sbs.modid.client.economy.recipe.model.ItemRef;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The Hypixel SkyBlock item catalogue, fetched from the <b>official</b> resources endpoint
 * ({@code https://api.hypixel.net/v2/resources/skyblock/items}) – the officially exposed source of
 * every custom item's SkyBlock id, display name, vanilla material <b>and look</b> (skull skin,
 * custom item model, leather tint, glint), live and keyless.
 *
 * <p>Backs the Recipe Viewer search (names, ids, partial, case-insensitive) and icon rendering
 * ({@link SkyBlockItemIcons}). The response is large, so it is cached to disk
 * ({@code config/<modid>-items.json}) and only re-fetched when the cache is older than
 * {@link #MAX_AGE_MS}. Fetching runs on a one-shot daemon thread (mirroring the other SBS API
 * clients); readers see a volatile immutable snapshot.
 */
public final class SkyBlockItemCatalog {

    private static final SkyBlockItemCatalog INSTANCE = new SkyBlockItemCatalog();

    /**
     * The official resources endpoint, fetched <b>directly</b>: it is one of the few Hypixel
     * endpoints that needs no API key at all, so every client (licence or not) gets the live item
     * data - including the texture fields ({@code skin}, {@code item_model}, {@code color},
     * {@code glowing}) that drive icon rendering.
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.hypixel.net/v2/resources/skyblock/items
// METHOD: GET
// PURPOSE: Hypixel's own catalogue of every SkyBlock item - names, rarities, museum data and
//   the texture fields the mod's icons are drawn from.
// DATA SENT: Nothing in the URL and no body. Headers only, via HypixelApi.withHeaders.
// DATA RECEIVED: Read-only public JSON, parsed with Gson into a fixed Response record and
//   cached on disk.
// SAFETY DECLARATION: A keyless public endpoint - no user credentials, session tokens, Mojang
//   uuids or OS telemetry are collected or transmitted. This class also has a licence-gated
//   path through SbsApi for the same data by way of our backend; that one sends the licence
//   token and nothing else, and is declared at its own call site.
// ============================================================================
    private static final String HYPIXEL_URL = "https://api.hypixel.net/v2/resources/skyblock/items";

    // Fallback only: the SBS cloud proxy's cached passthrough of the same catalogue (same
    // {success, items:[...]} shape, licence-token auth), used when Hypixel itself is unreachable.
    private static final String PATH = "/api/hypixel/resources/items";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Hypixel adds / retextures items constantly, so the disk cache is only a day-long buffer. */
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000; // 1 day

    private static final Gson GSON = new Gson();

    /** id (upper-case) -> catalogue entry. Immutable snapshot semantics via volatile publish. */
    private volatile Map<String, Entry> byId = Map.of();

    /**
     * Supplemental entries from the item-data repo ({@code SkyBlockRepoRecipeProvider}) – items the
     * official API misses entirely (e.g. ABIPHONE_XIII_JADE). Official entries always win; these
     * only fill the gaps in search and icon resolution.
     */
    private volatile Map<String, Entry> repoExtra = Map.of();

    /** Lazily merged view over {@link #byId} + {@link #repoExtra} (official entries win). */
    private volatile Map<String, Entry> mergedCache;

    private SkyBlockItemCatalog() {
    }

    public static SkyBlockItemCatalog getInstance() {
        return INSTANCE;
    }

    /**
     * Registers supplemental catalogue entries (repo items, enchantment products, ...) as
     * fallbacks. MERGES with previously registered extras so multiple sources coexist; repeated
     * calls from the same source (periodic refresh) are idempotent by id. Official API entries
     * still win in {@link #merged()}.
     */
    public synchronized void addRepoEntries(Map<String, Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Map<String, Entry> combined = new java.util.HashMap<>(this.repoExtra);
        combined.putAll(entries);
        this.repoExtra = Map.copyOf(combined);
        invalidateViews();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Items] +{} supplemental catalogue entries ({} total).",
                entries.size(), combined.size());
    }

    private Map<String, Entry> merged() {
        Map<String, Entry> merged = mergedCache;
        if (merged == null) {
            merged = new java.util.HashMap<>();
            // Repo fallbacks fill gaps only: entries that duplicate an official item under a
            // different id convention (repo "WOOD_STEP-4" vs official "WOOD_STEP:4") or under the
            // exact same display name would show every such item twice in the viewer, so they are
            // dropped here; {@link #byId(String)} still resolves their ids via the canonical index.
            java.util.Set<String> officialIds = new java.util.HashSet<>();
            java.util.Set<String> officialNames = new java.util.HashSet<>();
            for (Entry entry : byId.values()) {
                officialIds.add(canonicalId(entry.id));
                officialNames.add(entry.nameLower);
            }
            for (Map.Entry<String, Entry> repoEntry : repoExtra.entrySet()) {
                Entry entry = repoEntry.getValue();
                if (officialIds.contains(canonicalId(entry.id)) || officialNames.contains(entry.nameLower)) {
                    continue;
                }
                merged.put(repoEntry.getKey(), entry);
            }
            merged.putAll(byId); // official API entries override repo fallbacks
            mergedCache = merged;
        }
        return merged;
    }

    /**
     * Unifies the two SkyBlock id conventions for data-variant items – official
     * {@code WOOD_STEP:4} and repo {@code WOOD_STEP-4} – into one canonical (dash) form, so
     * recipes, appearances and catalogue entries meet regardless of which side named the item.
     */
    public static String canonicalId(String id) {
        if (id == null) {
            return "";
        }
        String upper = id.toUpperCase(Locale.ROOT);
        int colon = upper.lastIndexOf(':');
        if (colon > 0 && colon < upper.length() - 1
                && upper.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            return upper.substring(0, colon) + "-" + upper.substring(colon + 1);
        }
        return upper;
    }

    /** Lazily built canonical-id index over {@link #merged()} (official entries win). */
    private Map<String, Entry> canonicalIndex() {
        Map<String, Entry> index = canonicalIndexCache;
        if (index == null) {
            index = new java.util.HashMap<>();
            for (Entry entry : byId.values()) {
                index.putIfAbsent(canonicalId(entry.id), entry);
            }
            for (Entry entry : repoExtra.values()) {
                index.putIfAbsent(canonicalId(entry.id), entry);
            }
            canonicalIndexCache = index;
        }
        return index;
    }

    private volatile Map<String, Entry> canonicalIndexCache;

    private void invalidateViews() {
        this.mergedCache = null;
        this.canonicalIndexCache = null;
        this.sortedCache = null;
        this.nameIndex = null;
        this.normalizedIndex = null;
    }

    /** Loads the disk cache (if fresh) and refreshes from the API in the background when stale. */
    public synchronized void start() {
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SBS-ItemCatalog");
            thread.setDaemon(true);
            return thread;
        }).execute(this::loadOrRefresh);
    }

    public Entry byId(String skyblockId) {
        if (skyblockId == null) {
            return null;
        }
        Entry entry = merged().get(skyblockId.toUpperCase(Locale.ROOT));
        return entry != null ? entry : canonicalIndex().get(canonicalId(skyblockId));
    }

    /** Every catalogue entry (unsorted snapshot view, incl. the repo fallbacks). */
    public java.util.Collection<Entry> all() {
        return merged().values();
    }

    /** All entries sorted by display name – the full item list (cached until reload). */
    public List<Entry> allSorted() {
        List<Entry> sorted = sortedCache;
        if (sorted == null || sorted.size() != merged().size()) {
            sorted = new ArrayList<>(merged().values());
            sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            sortedCache = sorted;
        }
        return sorted;
    }

    private volatile List<Entry> sortedCache;

    /** Exact (case-insensitive) display-name lookup, e.g. to resolve icons for name-only data. */
    public Entry byName(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return null;
        }
        Map<String, Entry> index = nameIndex;
        if (index == null || index.isEmpty()) {
            Map<String, Entry> built = new ConcurrentHashMap<>();
            for (Entry entry : merged().values()) {
                built.putIfAbsent(entry.nameLower, entry);
            }
            nameIndex = built;
            index = built;
        }
        return index.get(displayName.trim().toLowerCase(Locale.ROOT));
    }

    private volatile Map<String, Entry> nameIndex;

    /**
     * Fuzzy display-name lookup via {@code SkyblockItem.normalizeName} (strips reforges, pet
     * levels, stars) – resolves icons for decorated names ("Sharp Aspect of the End ✪✪",
     * "[Lvl 80] Bat") that miss the exact-name index.
     */
    public Entry byNormalizedName(String displayName) {
        String key = sbs.modid.client.core.item.SkyblockItem.normalizeName(displayName);
        if (key.isEmpty()) {
            return null;
        }
        Map<String, Entry> index = normalizedIndex;
        if (index == null || index.isEmpty()) {
            Map<String, Entry> built = new ConcurrentHashMap<>();
            for (Entry entry : merged().values()) {
                String normalized = sbs.modid.client.core.item.SkyblockItem.normalizeName(entry.name);
                if (!normalized.isEmpty()) {
                    built.putIfAbsent(normalized, entry);
                }
            }
            normalizedIndex = built;
            index = built;
        }
        return index.get(key);
    }

    private volatile Map<String, Entry> normalizedIndex;

    /**
     * Case-insensitive partial search over names and ids, capped at {@code limit} results. Supports
     * the SkyBlock filter token {@code rarity:<tier>} (e.g. "rarity:legendary sword") anywhere in
     * the query – the token filters by item tier, the remaining text matches names / ids as usual.
     */
    public List<Entry> search(String query, int limit) {
        List<Entry> result = new ArrayList<>();
        Query parsed = Query.parse(query);
        if (parsed.text.isEmpty() && parsed.tier == null) {
            return result;
        }
        for (Entry entry : merged().values()) {
            if (parsed.tier != null && (entry.tierLower == null || !entry.tierLower.contains(parsed.tier))) {
                continue;
            }
            if (!parsed.text.isEmpty()
                    && !entry.nameLower.contains(parsed.text) && !entry.idLower.contains(parsed.text)) {
                continue;
            }
            result.add(entry);
            if (result.size() >= limit) {
                break;
            }
        }
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    /** A parsed search query: free text plus an optional {@code rarity:<tier>} filter token. */
    public static final class Query {
        public final String text;
        public final String tier;

        private Query(String text, String tier) {
            this.text = text;
            this.tier = tier;
        }

        public static Query parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return new Query("", null);
            }
            String tier = null;
            StringBuilder text = new StringBuilder();
            for (String token : raw.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.startsWith("rarity:") && token.length() > 7) {
                    tier = token.substring(7);
                } else {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(token);
                }
            }
            return new Query(text.toString(), tier);
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void loadOrRefresh() {
        try {
            Path path = cachePath();
            if (Files.exists(path)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() < MAX_AGE_MS) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    if (publish(parse(GSON.fromJson(reader, Response.class))) && hasTextureData()) {
                        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Items] Loaded {} item(s) from disk cache.", byId.size());
                        return;
                    }
                }
                // A cache written before the texture fields were read (or by a source that strips
                // them) leaves every modern item without its model - refresh once instead of
                // rendering plain paper for a day.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Items] Cache carries no item models - refreshing once.");
            }
            refreshFromApi();
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Items] Catalogue load failed", e);
        }
    }

    private void refreshFromApi() {
        // Hypixel first (keyless, always the freshest data), proxy only as the fallback.
        String body = fetchDirect();
        String source = "Hypixel";
        if (body == null) {
            body = fetchViaProxy();
            source = "SBS proxy";
        }
        if (body == null) {
            return;
        }
        try {
            Parsed parsed = parse(body);
            if (publish(parsed)) {
                Path path = cachePath();
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    writer.write(body);
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Items] Fetched {} item(s) from the {} item catalogue.",
                        byId.size(), source);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Items] Catalogue parse failed: {}", e.toString());
        }
    }

    /** True once the published catalogue carries the API's look data (custom item models). */
    private boolean hasTextureData() {
        for (Entry entry : byId.values()) {
            if (entry.itemModel != null && !entry.itemModel.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The official keyless endpoint; {@code null} when it is unreachable or answers non-200. */
    private String fetchDirect() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(HYPIXEL_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "SkyblockSimplifiedSBS")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Items] Hypixel items endpoint returned status {}.",
                        response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Items] Hypixel items request failed: {}", e.toString());
            return null;
        }
    }

    /** The proxy's cached copy of the same catalogue; {@code null} without a licence or on error. */
    private String fetchViaProxy() {
        if (!sbs.modid.client.core.api.SbsApi.hasLicence()) {
            return null;
        }
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpRequest request = sbs.modid.client.core.api.SbsApi.withLicence(
                            HttpRequest.newBuilder(URI.create(sbs.modid.client.core.api.SbsApi.base() + PATH))
                                    .timeout(TIMEOUT))
                    .GET()
                    .build();
            HttpResponse<String> response = sbs.modid.client.core.api.SbsApi.send(
                    ConsentScope.LICENCE_VALIDATION, http, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Items] Items proxy returned status {}.", response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Items] Items proxy request failed: {}", e.toString());
            return null;
        }
    }

    /** The catalogue as plain data: entries by upper-case id, plus the Museum's inputs. */
    record Parsed(Map<String, Entry> byId,
                  List<sbs.modid.client.helper.museum.model.MuseumCatalog.Input> museum) {
    }

    /** {@link #parse(Response)} over a raw response body. */
    static Parsed parse(String body) {
        return parse(GSON.fromJson(body, Response.class));
    }

    /**
     * Turns the API response into plain data and nothing else. It must never build an
     * {@code ItemStack}, or touch anything that does: this runs on the catalogue thread during
     * startup, before item components are bound, and one icon built from here threw
     * {@code Components not bound yet} straight through the parse and cost the session its whole
     * catalogue. Icons are built later, by {@link SkyBlockItemIcons#requestPrewarm}.
     *
     * @return {@code null} when the response is unusable
     */
    static Parsed parse(Response response) {
        if (response == null || !response.success || response.items == null) {
            return null;
        }
        Map<String, Entry> map = new ConcurrentHashMap<>();
        List<sbs.modid.client.helper.museum.model.MuseumCatalog.Input> museum = new ArrayList<>();
        for (ApiItem item : response.items) {
            if (item == null || item.id == null || item.name == null) {
                continue;
            }
            if (item.museum_data != null || Boolean.TRUE.equals(item.museum)) {
                museum.add(new sbs.modid.client.helper.museum.model.MuseumCatalog.Input(
                        item.id, item.name, Boolean.TRUE.equals(item.museum), item.museum_data));
            }
            // Legacy data-variant items carry the variant in "durability" ("WOOD_STEP" + 4 =
            // acacia slab); fold it into the material so the icon resolver picks the right
            // modern variant instead of the damage-0 default.
            String material = item.material;
            if (material != null && item.durability != null && item.durability > 0
                    && material.indexOf(':') < 0 && material.indexOf('-') < 0) {
                material = material + ":" + item.durability;
            }
            // Some API names carry raw color tokens ("%%light_purple%%Rift Necklace") - strip
            // them, or the display is ugly AND every name lookup / search for the item misses.
            String name = item.name.replaceAll("%%[a-z_]+%%", "");
            map.put(item.id.toUpperCase(Locale.ROOT),
                    new Entry(item.id, name, material, skinValue(item.skin), item.tier, item.category,
                            item.item_model, parseColor(item.color), Boolean.TRUE.equals(item.glowing),
                            item.npc_sell_price == null ? 0 : item.npc_sell_price,
                            sbs.modid.client.economy.itemvalue.GemSlots.parseDefinitions(item.gemstone_slots)));
        }
        return new Parsed(map, museum);
    }

    private boolean publish(Parsed parsed) {
        if (parsed == null) {
            return false;
        }
        this.byId = parsed.byId();
        invalidateViews();
        if (!parsed.museum().isEmpty()) {
            sbs.modid.client.helper.museum.model.MuseumCatalog.publish(
                    sbs.modid.client.helper.museum.model.MuseumCatalog.build(parsed.museum(), 0L));
        }
        if (!parsed.byId().isEmpty()) {
            // Icons built from an earlier (e.g. disk-cached) publish carry that snapshot's look
            // data, so they go. The new ones are built once a world is loaded - never from here.
            SkyBlockItemIcons.getInstance().invalidate();
            SkyBlockItemIcons.getInstance().requestPrewarm();
            return true;
        }
        return false;
    }

    /**
     * The API's leather-armor tint, given as {@code "r,g,b"} ("139,0,0"), packed into one RGB int;
     * {@code -1} when absent or malformed (the icon then keeps the item's default color).
     */
    public static int parseColor(String color) {
        if (color == null || color.isEmpty()) {
            return -1;
        }
        try {
            String[] parts = color.split(",");
            if (parts.length != 3) {
                return -1;
            }
            int r = Integer.parseInt(parts[0].trim()) & 0xFF;
            int g = Integer.parseInt(parts[1].trim()) & 0xFF;
            int b = Integer.parseInt(parts[2].trim()) & 0xFF;
            return (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The API's skin field is either a plain base64 string or an object {@code {"value": ...}}. */
    private static String skinValue(com.google.gson.JsonElement skin) {
        if (skin == null || skin.isJsonNull()) {
            return null;
        }
        if (skin.isJsonPrimitive()) {
            return skin.getAsString();
        }
        if (skin.isJsonObject() && skin.getAsJsonObject().has("value")) {
            return skin.getAsJsonObject().get("value").getAsString();
        }
        return null;
    }

    private static Path cachePath() {
        return SBSFiles.itemsFile();
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    /** One catalogue entry with pre-lowered search keys. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String material;
        /** Base64 skull skin for player-head items, or {@code null}. */
        public final String skinValue;
        /** SkyBlock tier from the official API (e.g. "LEGENDARY"), or {@code null}. */
        public final String tier;
        /**
         * SkyBlock item category from the official API ("SWORD", "HELMET", "ACCESSORY", ...), or
         * {@code null} for plain materials and for supplemental entries that carry no category.
         * Drives the Recipe Viewer's {@link ItemFilter} buttons.
         */
        public final String category;
        /**
         * The item's custom model id from the API ({@code hypixel_skyblock:item/...}, sometimes a
         * plain vanilla {@code minecraft:...}), or {@code null}. This is how every modern Hypixel
         * item carries its real texture - the successor to the old skull skins.
         */
        public final String itemModel;
        /** Packed RGB leather-armor tint from the API, or {@code -1} when the item has none. */
        public final int color;
        /** True when the API marks the item as permanently enchant-glinting. */
        public final boolean glowing;
        /**
         * What an NPC merchant pays per unit, from the official API; {@code 0} when the item has no
         * NPC sell value (or the entry is supplemental). This is the base a minion hopper's cut
         * applies to (Budget 50%, Enchanted 70%).
         */
        public final double npcSellPrice;
        /**
         * Every gemstone slot the item has, in lore order, from the API's {@code gemstone_slots};
         * empty for items without any (and for supplemental entries). Read by the Gemstone slot
         * summary, which needs the slots an item <i>has</i> - its NBT only knows the filled ones.
         */
        public final List<sbs.modid.client.economy.itemvalue.GemSlots.SlotDef> gemSlots;
        final String nameLower;
        final String idLower;
        final String tierLower;

        Entry(String id, String name, String material, String skinValue, String tier) {
            this(id, name, material, skinValue, tier, null);
        }

        Entry(String id, String name, String material, String skinValue, String tier, String category) {
            this(id, name, material, skinValue, tier, category, null, -1, false, 0);
        }

        Entry(String id, String name, String material, String skinValue, String tier, String category,
              String itemModel, int color, boolean glowing, double npcSellPrice) {
            this(id, name, material, skinValue, tier, category, itemModel, color, glowing, npcSellPrice, List.of());
        }

        Entry(String id, String name, String material, String skinValue, String tier, String category,
              String itemModel, int color, boolean glowing, double npcSellPrice,
              List<sbs.modid.client.economy.itemvalue.GemSlots.SlotDef> gemSlots) {
            this.gemSlots = gemSlots == null ? List.of() : gemSlots;
            this.npcSellPrice = npcSellPrice;
            this.category = category;
            this.itemModel = itemModel;
            this.color = color;
            this.glowing = glowing;
            this.id = id;
            this.name = name;
            this.material = material;
            this.skinValue = skinValue;
            this.tier = tier;
            // Defensive: search keys must never carry formatting - neither leftover API color
            // tokens ("%%light_purple%%") nor section-sign codes from repo display names.
            this.nameLower = name.replaceAll("%%[a-z_]+%%", "")
                    .replaceAll(String.valueOf((char) 0x00A7) + ".", "")
                    .trim().toLowerCase(Locale.ROOT);
            this.idLower = id.toLowerCase(Locale.ROOT);
            this.tierLower = tier == null ? null : tier.toLowerCase(Locale.ROOT);
        }

        public ItemRef toRef() {
            return new ItemRef(id, material, name, 1);
        }
    }

    /** Gson mirror of the API response (only the fields we use). */
    static final class Response {
        boolean success;
        List<ApiItem> items;
    }

    private static final class ApiItem {
        String id;
        String name;
        String material;
        /** Legacy 1.8 data value for variant items (acacia slab = WOOD_STEP + 4), or {@code null}. */
        Integer durability;
        String tier;
        /** "SWORD", "HELMET", "ACCESSORY", ... – absent for plain materials. */
        String category;
        com.google.gson.JsonElement skin;
        /** Custom model id of modern items ({@code hypixel_skyblock:item/...}), or {@code null}. */
        String item_model;
        /** Leather-armor tint as {@code "r,g,b"}, or {@code null}. */
        String color;
        /** Permanent enchantment glint flag. */
        Boolean glowing;
        /** NPC merchant sell value per unit, or {@code null} when the item has none. */
        Double npc_sell_price;
        /** {@code true} on the Museum's Special items, which carry no {@code museum_data}. */
        Boolean museum;
        /** Category, donation XP, set XP, tier and variant links - read by the Museum helper. */
        sbs.modid.client.helper.museum.model.MuseumCatalog.ApiMuseumData museum_data;
        /** Slot types, unlock costs and level requirements - see {@code GemSlots.parseDefinitions}. */
        com.google.gson.JsonElement gemstone_slots;
    }
}
