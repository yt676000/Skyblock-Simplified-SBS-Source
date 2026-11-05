/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.net.Identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static sbs.modid.client.social.playerviewer.logic.PvValidator.ID_LEN;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.NAME_LEN;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.arr;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.bool;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.count;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.num;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.numMap;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.obj;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.objIfObject;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.str;

/**
 * Relayed Hypixel JSON -> {@link PvModel} records. Reads through {@link PvJson} (size, depth and
 * shape limits) and {@link PvValidator} (types, bounds, lengths), copying only the fields the
 * viewer uses. The response root (body limits, {@code profile}, the member) is strict: a violation
 * there rejects the response. Below that, each section is validated on its own: a violation marks
 * only that section unavailable.
 */
public final class PvParser {

    static final List<String> SKILLS = List.of("farming", "mining", "combat", "foraging", "fishing",
            "enchanting", "alchemy", "taming");
    static final List<String> SLAYERS = List.of("zombie", "spider", "wolf", "enderman", "blaze", "vampire");
    static final List<String> DUNGEON_CLASSES = List.of("healer", "mage", "berserk", "archer", "tank");
    /** Containers on the inventory root the viewer decodes (grid pages + ownership check). */
    static final List<String> CONTAINERS = List.of("inv_contents", "ender_chest_contents", "inv_armor",
            "equipment_contents", "wardrobe_contents", "personal_vault_contents");
    static final List<String> BAGS = List.of("talisman_bag", "potion_bag", "fishing_bag", "quiver");
    private static final List<String> BURROWS = List.of("burrows_dug_next", "burrows_dug_combat",
            "burrows_dug_treasure", "burrows_chains_complete");
    private static final List<String> AUCTION_KEYS = List.of("bids", "highest_bid", "won", "created",
            "gold_spent", "gold_earned");

    private PvParser() {
    }

    // ------------------------------------------------------------------
    // Profile list (/skyblock/profiles relay)
    // ------------------------------------------------------------------

    /** The profiles {@code uuid} is a member of, in document order. Empty when there are none. */
    public static List<PvModel.ProfileRef> parseProfileList(String body, String uuid) throws PvInvalidDataException {
        JsonObject root = root(body);
        JsonArray profiles = arr(root, "profiles");
        List<PvModel.ProfileRef> out = new ArrayList<>();
        if (profiles == null) {
            return out;
        }
        for (JsonElement el : profiles) {
            if (!el.isJsonObject()) {
                throw new PvInvalidDataException("type:profile");
            }
            JsonObject prof = el.getAsJsonObject();
            JsonObject members = obj(prof, "members");
            if (members == null || !members.has(uuid)) {
                continue;
            }
            String id = Identity.normalizeUuid(str(prof, "profile_id", 64)).orElse(null);
            if (id == null) {
                throw new PvInvalidDataException("format:profile_id");
            }
            String cute = str(prof, "cute_name", 32);
            String mode = str(prof, "game_mode", 32);
            Boolean selected = bool(prof, "selected");
            out.add(new PvModel.ProfileRef(id, cute, selected != null && selected, mode));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // One profile (/v1/playerviewer relay)
    // ------------------------------------------------------------------

    public static PvModel.Profile parseProfile(String body, String uuid) throws PvInvalidDataException {
        JsonObject root = root(body);
        JsonObject prof = obj(root, "profile");
        if (prof == null) {
            throw new PvInvalidDataException("absent:profile");
        }
        JsonObject members = obj(prof, "members");
        JsonObject member = members == null ? null : obj(members, uuid);
        if (member == null) {
            throw new PvInvalidDataException("absent:member");
        }
        String id = Identity.normalizeUuid(str(prof, "profile_id", 64)).orElse(null);
        List<String> invalid = new ArrayList<>();
        Double bank = null;
        try {
            bank = num(obj(prof, "banking"), "balance");
        } catch (PvInvalidDataException e) {
            invalid.add(PvSections.STATS);
            logOnce(PvSections.STATS, "banking." + e.getMessage());
        }
        return new PvModel.Profile(id, bank, member(member, invalid));
    }

    private static JsonObject root(String body) throws PvInvalidDataException {
        JsonElement el = PvJson.parse(body);
        if (!el.isJsonObject()) {
            throw new PvInvalidDataException("type:root");
        }
        JsonObject root = el.getAsJsonObject();
        Boolean success = bool(root, "success");
        if (success != null && !success) {
            throw new PvInvalidDataException("upstream:unsuccessful");
        }
        return root;
    }

    /** A section parser; any {@link PvInvalidDataException} it throws affects that section only. */
    @FunctionalInterface
    private interface SectionReader<T> {
        T read() throws PvInvalidDataException;
    }

    /** Violations already logged this session ("section|message"), so each is logged once. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** Collects which sections failed while a member is read. */
    private static final class Sections {
        private final List<String> invalid = new ArrayList<>();

        <T> T read(String section, T fallback, SectionReader<T> reader) {
            try {
                return reader.read();
            } catch (PvInvalidDataException e) {
                if (!invalid.contains(section)) {
                    invalid.add(section);
                }
                logOnce(section, e.getMessage());
                return fallback;
            }
        }
    }

    /**
     * Logs a rejected section once per session: the section id, the field path and the expected
     * vs actual JSON type. Path segments that come from dynamic map keys are reduced to a safe
     * charset so no player data can reach the log.
     */
    public static void logOnce(String section, String message) {
        String safe = message == null ? "" : message.replaceAll("[^A-Za-z0-9_.:, -]", "?");
        if (safe.length() > 160) {
            safe = safe.substring(0, 160);
        }
        if (LOGGED.add(section + "|" + safe)) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Section {} unavailable: {}", section, safe);
        }
    }

    /**
     * Reads the viewed member section by section. A wrong type in one section marks only that
     * section unavailable (listed in {@link PvModel.Member#invalid()}); the rest is still read.
     */
    static PvModel.Member member(JsonObject m, List<String> alreadyInvalid) {
        Sections s = new Sections();
        s.invalid.addAll(alreadyInvalid);
        PvModel.PlayerStats diana = s.read(PvSections.DIANA, null, () -> dianaStats(objIfObject(m, "player_stats")));
        PvModel.PlayerStats rest = s.read(PvSections.STATS, null, () -> playerStats(obj(m, "player_stats")));
        PvModel.PlayerStats stats = merge(rest, diana);
        return new PvModel.Member(
                s.read(PvSections.LEVELING, null, () -> num(obj(m, "leveling"), "experience")),
                s.read(PvSections.ACCESSORIES, null, () -> accessoryStorage(obj(m, "accessory_bag_storage"))),
                s.read(PvSections.SKILLS, Map.of(), () -> skills(m)),
                s.read(PvSections.DUNGEONS, null, () -> dungeons(obj(m, "dungeons"))),
                s.read(PvSections.SLAYER, Map.of(), () -> slayers(m)),
                s.read(PvSections.MINING, null, () -> miningCore(obj(m, "mining_core"))),
                stats,
                s.read(PvSections.DIANA, null, () -> num(obj(m, "stats"), "mythos_kills")),
                s.read(PvSections.BESTIARY, null, () -> bestiary(obj(m, "bestiary"))),
                s.read(PvSections.CRIMSON, null, () -> nether(obj(m, "nether_island_player_data"))),
                s.read(PvSections.PETS, List.of(), () -> pets(m)),
                s.read(PvSections.INVENTORY, new PvModel.Inventory(Map.of(), Map.of(), Map.of()), () -> inventory(m)),
                s.read(PvSections.COLLECTIONS, null, () -> numMap(m, "collection")),
                s.read(PvSections.MINING, null, () -> glacite(obj(m, "glacite_player_data"))),
                s.read(PvSections.FORAGING, null, () -> foragingCore(obj(m, "foraging_core"))),
                s.read(PvSections.FORAGING, null, () -> foraging(obj(m, "foraging"))),
                s.read(PvSections.FISHING, null, () -> trophyFish(obj(m, "trophy_fish"))),
                s.read(PvSections.FARMING, null, () -> jacob(obj(m, "jacobs_contest"))),
                s.read(PvSections.FARMING, null, () -> garden(obj(m, "garden_player_data"))),
                s.read(PvSections.HOPPITY, null, () -> easter(obj(obj(m, "events"), "easter"))),
                s.read(PvSections.RIFT, null, () -> rift(obj(m, "rift"))),
                s.read(PvSections.STATS, null, () -> currencies(obj(m, "currencies"))),
                s.read(PvSections.STATS, null, () -> num(m, "coin_purse")),
                s.read(PvSections.STATS, null, () -> firstJoin(m)),
                s.read(PvSections.STATS, null, () -> fairySouls(m)),
                s.read(PvSections.LOADOUTS, null, () -> loadout(obj(m, "loadout"))),
                s.read(PvSections.SKILL_TREE, null, () -> skillTree(obj(m, "skill_tree"))),
                List.copyOf(s.invalid));
    }

    /** Combines the general player stats with the separately validated Diana part. */
    private static PvModel.PlayerStats merge(PvModel.PlayerStats rest, PvModel.PlayerStats diana) {
        if (rest == null && diana == null) {
            return null;
        }
        PvModel.PlayerStats r = rest != null ? rest : new PvModel.PlayerStats(null, null, Map.of(), null,
                null, null, null, null, null, null, null);
        return new PvModel.PlayerStats(r.summoningEyes(), diana == null ? null : diana.mythosKills(),
                diana == null ? Map.of() : diana.burrows(), r.auctions(), r.minedOres(), r.blocksMined(),
                r.seaCreatureKills(), r.itemsFished(), r.kills(), r.deaths(), r.rift());
    }

    /** The Diana part of {@code player_stats.mythos}: kills and burrow counters. */
    private static PvModel.PlayerStats dianaStats(JsonObject ps) throws PvInvalidDataException {
        if (ps == null) {
            return null;
        }
        JsonObject mythos = obj(ps, "mythos");
        Map<String, Double> burrows = new LinkedHashMap<>();
        for (String key : BURROWS) {
            JsonElement raw = mythos == null ? null : mythos.get(key);
            Double v;
            try {
                v = raw != null && raw.isJsonObject() ? num(raw.getAsJsonObject(), "total") : num(raw);
            } catch (PvInvalidDataException e) {
                throw new PvInvalidDataException("player_stats.mythos." + key + ": " + e.getMessage());
            }
            burrows.put(key, v == null ? 0.0 : v);
        }
        return new PvModel.PlayerStats(null, num(mythos, "kills"), burrows, null, null, null, null, null,
                null, null, null);
    }

    private static PvModel.AccessoryStorage accessoryStorage(JsonObject s) throws PvInvalidDataException {
        if (s == null) {
            return null;
        }
        JsonObject tuning = objIfObject(obj(s, "tuning"), "slot_0");
        return new PvModel.AccessoryStorage(num(s, "highest_magical_power"),
                str(s, "selected_power", 64), num(s, "bag_upgrades_purchased"), numMap(tuning));
    }

    private static Map<String, Double> skills(JsonObject m) throws PvInvalidDataException {
        JsonObject pdata = obj(obj(m, "player_data"), "experience");
        Map<String, Double> out = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(SKILLS);
        all.add("carpentry");
        for (String s : all) {
            Double xp = num(pdata, "SKILL_" + s.toUpperCase(Locale.ROOT));
            if (xp == null) {
                xp = num(m, "experience_skill_" + s);
            }
            if (xp != null) {
                out.put(s, xp);
            }
        }
        return out;
    }

    private static PvModel.DungeonType dungeonType(JsonObject d) throws PvInvalidDataException {
        if (d == null) {
            return null;
        }
        return new PvModel.DungeonType(numMap(d, "tier_completions"), numMap(d, "fastest_time"),
                numMap(d, "fastest_time_s"), numMap(d, "fastest_time_s_plus"), numMap(d, "best_score"));
    }

    private static PvModel.Dungeons dungeons(JsonObject d) throws PvInvalidDataException {
        if (d == null) {
            return null;
        }
        JsonObject types = obj(d, "dungeon_types");
        JsonObject cata = obj(types, "catacombs");
        Map<String, Double> classXp = new LinkedHashMap<>();
        JsonObject classes = obj(d, "player_classes");
        for (String c : DUNGEON_CLASSES) {
            Double xp = num(obj(classes, c), "experience");
            if (xp != null) {
                classXp.put(c, xp);
            }
        }
        return new PvModel.Dungeons(num(cata, "experience"), dungeonType(cata),
                dungeonType(obj(types, "master_catacombs")), num(d, "secrets"), classXp);
    }

    private static Map<String, PvModel.SlayerBoss> slayers(JsonObject m) throws PvInvalidDataException {
        JsonObject bosses = objIfObject(objIfObject(m, "slayer"), "slayer_bosses");
        if (bosses == null) {
            bosses = obj(m, "slayer_bosses");
        }
        Map<String, PvModel.SlayerBoss> out = new LinkedHashMap<>();
        if (bosses == null) {
            return out;
        }
        for (String sl : SLAYERS) {
            JsonObject b = obj(bosses, sl);
            if (b != null) {
                out.put(sl, new PvModel.SlayerBoss(num(b, "xp"), numMap(b)));
            }
        }
        return out;
    }

    private static PvModel.MiningCore miningCore(JsonObject c) throws PvInvalidDataException {
        if (c == null || c.isEmpty()) {
            return null;
        }
        Map<String, PvModel.Crystal> crystals = new LinkedHashMap<>();
        JsonObject raw = obj(c, "crystals");
        if (raw != null) {
            for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    JsonObject b = e.getValue().getAsJsonObject();
                    crystals.put(e.getKey(), new PvModel.Crystal(num(b, "total_found"),
                            num(b, "total_placed"), str(b, "state", 64)));
                }
            }
        }
        Map<String, Double> biomes = null;
        JsonObject rawBiomes = obj(c, "biomes");
        if (rawBiomes != null) {
            biomes = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : rawBiomes.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    for (Map.Entry<String, Double> v : numMap(e.getValue().getAsJsonObject()).entrySet()) {
                        biomes.put(e.getKey() + "_" + v.getKey(), v.getValue());
                    }
                }
            }
        }
        return new PvModel.MiningCore(num(c, "experience"), numMap(c), crystals, biomes);
    }

    private static PvModel.PlayerStats playerStats(JsonObject ps) throws PvInvalidDataException {
        if (ps == null) {
            return null;
        }
        Map<String, Double> auctions = null;
        JsonObject auc = obj(ps, "auctions");
        if (auc != null && !auc.isEmpty()) {
            auctions = new LinkedHashMap<>();
            for (String k : AUCTION_KEYS) {
                Double v = num(auc, k);
                if (v != null) {
                    auctions.put(k, v);
                }
            }
        }
        JsonObject dragon = obj(obj(ps, "end_island"), "dragon_fight");
        return new PvModel.PlayerStats(PvValidator.numIfNumber(dragon, "summoning_eyes_contributed"), null,
                Map.of(), auctions, numMap(objIfObject(ps, "mined_ores")), num(ps, "blocks_mined"),
                num(ps, "sea_creature_kills"), numMap(objIfObject(ps, "items_fished")),
                numMap(objIfObject(ps, "kills")), numMap(objIfObject(ps, "deaths")),
                numMap(objIfObject(ps, "rift")));
    }

    private static PvModel.Bestiary bestiary(JsonObject b) throws PvInvalidDataException {
        if (b == null) {
            return null;
        }
        return new PvModel.Bestiary(num(obj(b, "milestone"), "last_claimed_milestone"),
                numMap(objIfObject(b, "kills")));
    }

    private static PvModel.Nether nether(JsonObject n) throws PvInvalidDataException {
        if (n == null || n.isEmpty()) {
            return null;
        }
        String faction = str(n, "selected_faction", 64);
        Double rep = faction == null || faction.isEmpty() ? null : num(n, faction + "_reputation");
        JsonObject abiphone = objIfObject(n, "abiphone");
        return new PvModel.Nether(numMap(n, "kuudra_completed_tiers"), faction, rep, numMap(n, "dojo"),
                abiphone == null ? null : count(abiphone, "active_contacts"));
    }

    private static List<PvModel.Pet> pets(JsonObject m) throws PvInvalidDataException {
        JsonObject petsData = obj(m, "pets_data");
        JsonElement raw = petsData == null ? null : petsData.get("pets");
        if (raw == null || !raw.isJsonArray()) {
            raw = m.get("pets");
        }
        List<PvModel.Pet> out = new ArrayList<>();
        if (raw == null || !raw.isJsonArray()) {
            return out;
        }
        for (JsonElement el : raw.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject p = el.getAsJsonObject();
            Boolean active = bool(p, "active");
            out.add(new PvModel.Pet(str(p, "uuid", ID_LEN), str(p, "uniqueId", ID_LEN), str(p, "type", ID_LEN), str(p, "tier", 32), num(p, "exp"),
                    str(p, "heldItem", ID_LEN), active != null && active,
                    str(p, "skin", ID_LEN)));
        }
        return out;
    }

    /** The {@code data} blob of {@code root[key]}, or {@code null} when absent or not an object. */
    private static String blob(JsonObject root, String key) throws PvInvalidDataException {
        JsonObject entry = objIfObject(root, key);
        String data = str(entry, "data", Integer.MAX_VALUE);
        return data == null || data.isEmpty() ? null : data;
    }

    private static PvModel.Inventory inventory(JsonObject m) throws PvInvalidDataException {
        JsonObject root = objIfObject(m, "inventory");
        if (root == null) {
            root = m;
        }
        Map<String, String> containers = new LinkedHashMap<>();
        for (String key : CONTAINERS) {
            String b = blob(root, key);
            if (b != null) {
                containers.put(key, b);
            }
        }
        Map<String, String> bags = new LinkedHashMap<>();
        JsonObject bagRoot = objIfObject(root, "bag_contents");
        for (String key : BAGS) {
            String b = blob(bagRoot, key);
            if (b != null) {
                bags.put(key, b);
            }
        }
        Map<String, String> backpacks = new LinkedHashMap<>();
        JsonObject packs = objIfObject(root, "backpack_contents");
        if (packs != null) {
            for (String key : packs.keySet()) {
                String b = blob(packs, key);
                if (b != null) {
                    backpacks.put(key, b);
                }
            }
        }
        return new PvModel.Inventory(containers, bags, backpacks);
    }

    private static PvModel.Glacite glacite(JsonObject g) throws PvInvalidDataException {
        if (g == null || g.isEmpty()) {
            return null;
        }
        return new PvModel.Glacite(num(g, "mineshafts_entered"), num(g, "fossil_dust"),
                count(g, "fossils_donated"), numMap(objIfObject(g, "corpses_looted")));
    }

    private static PvModel.ForagingCore foragingCore(JsonObject c) throws PvInvalidDataException {
        if (c == null || c.isEmpty()) {
            return null;
        }
        return new PvModel.ForagingCore(num(c, "forests_whispers"), num(c, "forests_whispers_spent"),
                str(c, "current_daily_effect", ID_LEN));
    }

    private static PvModel.Foraging foraging(JsonObject f) throws PvInvalidDataException {
        if (f == null || f.isEmpty()) {
            return null;
        }
        JsonObject tasks = obj(obj(f, "hina"), "tasks");
        PvModel.Hina hina = tasks == null ? null
                : new PvModel.Hina(count(tasks, "completed_tasks"), num(tasks, "tier_claimed"));
        return new PvModel.Foraging(numMap(objIfObject(f, "tree_gifts")), hina,
                numMap(obj(obj(f, "songs"), "harp")), numMap(obj(obj(f, "starlyn"), "personal_bests")));
    }

    private static PvModel.TrophyFish trophyFish(JsonObject t) throws PvInvalidDataException {
        if (t == null || t.isEmpty()) {
            return null;
        }
        return new PvModel.TrophyFish(numMap(t), count(t, "rewards"));
    }

    private static PvModel.Jacob jacob(JsonObject j) throws PvInvalidDataException {
        if (j == null) {
            return null;
        }
        Integer contests = null;
        List<Double> claimed = new ArrayList<>();
        JsonObject raw = objIfObject(j, "contests");
        if (raw != null) {
            contests = raw.size();
            for (JsonElement c : raw.asMap().values()) {
                if (c.isJsonObject()) {
                    JsonElement pos = c.getAsJsonObject().get("claimed_position");
                    if (pos != null && !pos.isJsonNull()) {
                        claimed.add(num(pos));
                    }
                }
            }
        }
        Map<String, Integer> brackets = null;
        JsonObject rawBrackets = objIfObject(j, "unique_brackets");
        if (rawBrackets != null) {
            brackets = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : rawBrackets.entrySet()) {
                if (e.getValue().isJsonArray() && !e.getValue().getAsJsonArray().isEmpty()) {
                    brackets.put(e.getKey(), e.getValue().getAsJsonArray().size());
                }
            }
        }
        return new PvModel.Jacob(numMap(objIfObject(j, "medals_inv")), numMap(objIfObject(j, "perks")),
                contests, claimed, numMap(objIfObject(j, "personal_bests")), brackets);
    }

    private static PvModel.Garden garden(JsonObject g) throws PvInvalidDataException {
        if (g == null) {
            return null;
        }
        return new PvModel.Garden(num(g, "copper"), num(g, "larva_consumed"),
                count(g, "discovered_greenhouse_crops"));
    }

    private static PvModel.Easter easter(JsonObject e) throws PvInvalidDataException {
        if (e == null || e.isEmpty()) {
            return null;
        }
        Map<String, Double> tower = null;
        JsonObject rawTower = objIfObject(e, "time_tower");
        if (rawTower != null) {
            tower = new LinkedHashMap<>();
            for (String k : List.of("charges", "level")) {
                Double v = num(rawTower, k);
                if (v != null) {
                    tower.put(k, v);
                }
            }
        }
        JsonObject rabbits = objIfObject(e, "rabbits");
        return new PvModel.Easter(num(e, "chocolate"), num(e, "total_chocolate"),
                num(e, "chocolate_since_prestige"), num(e, "rabbit_barn_capacity_level"),
                numMap(objIfObject(e, "employees")), tower, numMap(rabbits),
                numMap(objIfObject(rabbits, "collected_eggs")));
    }

    private static PvModel.Rift rift(JsonObject r) throws PvInvalidDataException {
        if (r == null || r.isEmpty()) {
            return null;
        }
        JsonObject enigma = obj(r, "enigma");
        List<String> souls = null;
        JsonArray rawSouls = arr(enigma, "found_souls");
        if (rawSouls != null) {
            souls = new ArrayList<>();
            for (JsonElement s : rawSouls) {
                if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isString()
                        || s.getAsString().length() > ID_LEN) {
                    throw new PvInvalidDataException("type:soul");
                }
                souls.add(s.getAsString());
            }
        }
        JsonObject cats = obj(r, "dead_cats");
        JsonObject gallery = obj(r, "gallery");
        return new PvModel.Rift(souls, bool(enigma, "bought_cloak"), count(obj(r, "wither_cage"), "killed_eyes"),
                count(cats, "found_cats"), objIfObject(cats, "montezuma") != null,
                count(gallery, "secured_trophies"), count(r, "lifetime_purchased_boundaries"),
                num(obj(r, "wizard_tower"), "wizard_quest_step"), num(gallery, "elise_step"));
    }

    private static PvModel.Currencies currencies(JsonObject c) throws PvInvalidDataException {
        if (c == null) {
            return null;
        }
        Map<String, Double> essence = new LinkedHashMap<>();
        JsonObject raw = obj(c, "essence");
        if (raw != null) {
            for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
                JsonElement v = e.getValue();
                Double amount = v.isJsonObject() ? num(v.getAsJsonObject(), "current")
                        : PvValidator.isNumber(v) ? num(v) : null;
                if (amount != null) {
                    essence.put(e.getKey(), amount);
                }
            }
        }
        return new PvModel.Currencies(num(c, "motes_purse"), num(c, "coin_purse"), essence);
    }

    private static Double firstJoin(JsonObject m) throws PvInvalidDataException {
        Double v = num(obj(m, "profile"), "first_join");
        if (v == null || v == 0) {
            Double legacy = num(m, "first_join");
            return legacy != null ? legacy : v;
        }
        return v;
    }

    private static Double fairySouls(JsonObject m) throws PvInvalidDataException {
        Double v = num(obj(m, "fairy_soul"), "total_collected");
        return v != null ? v : num(m, "fairy_souls_collected");
    }

    private static Map<String, Map<String, String>> sets(JsonObject source, List<String> pieces)
            throws PvInvalidDataException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : source.entrySet()) {
            if (!isDigits(e.getKey()) || !e.getValue().isJsonObject()) {
                continue;
            }
            Map<String, String> blobs = new LinkedHashMap<>();
            for (String piece : pieces) {
                String b = blob(e.getValue().getAsJsonObject(), piece);
                if (b != null) {
                    blobs.put(piece, b);
                }
            }
            out.put(e.getKey(), blobs);
        }
        return out;
    }

    private static PvModel.Loadout loadout(JsonObject lo) throws PvInvalidDataException {
        if (lo == null) {
            return null;
        }
        JsonObject armor = objIfObject(lo, "armor");
        JsonObject equipment = objIfObject(lo, "equipment");
        JsonObject rawEntries = objIfObject(lo, "loadouts");
        List<PvModel.LoadoutEntry> entries = new ArrayList<>();
        if (rawEntries != null) {
            for (Map.Entry<String, JsonElement> e : rawEntries.entrySet()) {
                if (!isDigits(e.getKey()) || !e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject l = e.getValue().getAsJsonObject();
                entries.add(new PvModel.LoadoutEntry(e.getKey(), str(l, "name", NAME_LEN),
                        setId(num(l, "armor_set_id")), setId(num(l, "equipment_set_id")),
                        str(l, "power_stone", ID_LEN), str(l, "pet", ID_LEN),
                        num(l, "tuning_points_slot"), num(l, "mining_core_selected_slot"),
                        num(l, "foraging_core_selected_slot")));
            }
        }
        return new PvModel.Loadout(sets(armor, PvCompute.LOADOUT_PIECES), num(armor, "equipped_set"),
                sets(equipment, PvCompute.EQUIPMENT_SLOTS), num(equipment, "equipped_set"), entries);
    }

    /** A set reference as the digit key it points at, or {@code null} when unset. */
    private static String setId(Double id) {
        return id == null || id < 0 || id != Math.floor(id) ? null : String.valueOf(id.longValue());
    }

    /**
     * {@code skill_tree}: for every tree object under {@code nodes}, the numeric entries are perk
     * levels and the boolean {@code toggle_<perk>} entries are the enabled flags. A toggle without
     * a level is kept as well: Hypixel writes {@code toggle_pickaxe_toss: false} for a perk whose
     * level key is absent.
     */
    private static PvModel.SkillTree skillTree(JsonObject t) throws PvInvalidDataException {
        if (t == null || t.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Double>> levels = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> toggles = new LinkedHashMap<>();
        JsonObject nodes = obj(t, "nodes");
        if (nodes != null) {
            for (Map.Entry<String, JsonElement> tree : nodes.entrySet()) {
                if (!tree.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject perks = tree.getValue().getAsJsonObject();
                // numMap also reads booleans as 0/1, so the toggles are taken out of the levels here.
                Map<String, Double> perkLevels = numMap(perks);
                perkLevels.keySet().removeIf(k -> k.startsWith("toggle_"));
                levels.put(tree.getKey(), perkLevels);
                Map<String, Boolean> on = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> perk : perks.entrySet()) {
                    if (perk.getKey().startsWith("toggle_")) {
                        Boolean b = bool(perks, perk.getKey());
                        if (b != null) {
                            on.put(perk.getKey().substring("toggle_".length()), b);
                        }
                    }
                }
                toggles.put(tree.getKey(), on);
            }
        }
        Map<String, String> ability = new LinkedHashMap<>();
        JsonObject rawAbility = obj(t, "selected_ability");
        if (rawAbility != null) {
            for (String key : rawAbility.keySet()) {
                String v = str(rawAbility, key, ID_LEN);
                if (v != null) {
                    ability.put(key, v);
                }
            }
        }
        Map<String, Double> tokens = numMap(t, "tokens_spent");
        Map<String, Double> xp = numMap(t, "experience");
        Map<String, Double> slot = numMap(t, "selected_skill_tree_slot");
        return new PvModel.SkillTree(levels, toggles, ability, tokens == null ? Map.of() : tokens,
                xp == null ? Map.of() : xp, slot == null ? Map.of() : slot);
    }

    static boolean isDigits(String s) {
        if (s.isEmpty() || s.length() > 9) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
