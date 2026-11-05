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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import sbs.modid.client.core.net.NbtLimits;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every Player Viewer calculation, as pure functions from {@link PvModel} records to the view model
 * the pages render. This is a line-by-line port of the old server's {@code _profile_view} and its
 * {@code _pv_*} helpers: the output keeps the old key layout so the pages and the golden tests can
 * be compared one to one.
 *
 * <p>Inputs other than the profile are explicit: the Hypixel resources ({@link PvResources}), the
 * inventory decoder (normally {@link #LIMITED}) and the clock. No I/O happens
 * here.
 *
 * <p>Every remote string is passed through {@link PvText} on its way into the view model.
 */
public final class PvCompute {

    static final List<String> LOADOUT_PIECES = List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");
    static final List<String> EQUIPMENT_SLOTS = List.of("EQUIPMENT_SLOT_1", "EQUIPMENT_SLOT_2",
            "EQUIPMENT_SLOT_3", "EQUIPMENT_SLOT_4");

    private static final int ID_LEN = 128;
    private static final int NAME_LEN = 256;
    private static final int LORE_LINE_LEN = 1024;
    private static final int LORE_LINES = 128;
    /**
     * Longest ExtraAttributes SNBT carried for the offline appraisal. A real item's attributes are a
     * few hundred characters; anything past this is dropped (the item is then priced by id alone).
     */
    private static final int EA_LEN = 16_384;
    private static final long DAY = 86_400L;

    /** Inventory blob -> slot compounds; throws when the blob is malformed or over a limit. */
    @FunctionalInterface
    public interface Decoder {
        List<CompoundTag> decode(String base64) throws IOException;
    }

    /**
     * The production decoder: {@link NbtLimits#decodeBase64Gzip} (compressed and decompressed size,
     * depth via {@code NbtAccounter}, tag count, list and string length via {@code checkTree}),
     * then the root {@code i} list read here in slot order, an empty compound for an empty slot.
     */
    public static final Decoder LIMITED = PvCompute::decodeSlots;

    static List<CompoundTag> decodeSlots(String base64) throws IOException {
        CompoundTag root = NbtLimits.decodeBase64Gzip(base64);
        List<CompoundTag> out = new ArrayList<>();
        if (root.get("i") instanceof ListTag list) {
            for (Tag entry : list) {
                out.add(entry instanceof CompoundTag c ? c : new CompoundTag());
            }
        }
        return out;
    }

    private PvCompute() {
    }

    /**
     * Builds the view for one member. {@code nowMillis} only feeds {@code extras.days_joined}.
     * Sections whose inventory blob could not be decoded are listed under {@code unavailable}.
     */
    public static JsonObject view(PvModel.Profile profile, PvResources resources, Decoder decoder, long nowMillis) {
        return new Run(profile, resources == null ? PvResources.EMPTY : resources, decoder).build(nowMillis);
    }

    /** Adds the per-profile header the pages show (name, selected marker, game mode). */
    public static void addProfileMeta(JsonObject view, PvModel.ProfileRef ref) {
        view.addProperty("cute_name", ref == null || ref.cuteName() == null ? "?"
                : PvText.plain(ref.cuteName(), 32));
        view.addProperty("selected", ref != null && ref.selected());
        view.addProperty("game_mode", ref == null || ref.gameMode() == null || ref.gameMode().isEmpty()
                ? "normal" : PvText.plain(ref.gameMode(), 32));
    }

    // ------------------------------------------------------------------
    // Shared helpers (also used by tests)
    // ------------------------------------------------------------------

    static int levelFromXp(long[] table, double xp) {
        int lvl = 0;
        for (int i = 0; i < table.length; i++) {
            if (xp >= table[i]) {
                lvl = i;
            } else {
                break;
            }
        }
        return lvl;
    }

    /** {@code [level, progress 0..1000]}; at or past the cap the bar is full. */
    static JsonArray progress(long[] table, double xp, Integer maxLevel) {
        int level = levelFromXp(table, xp);
        int cap = maxLevel != null ? maxLevel : table.length - 1;
        if (level >= cap) {
            return pair(cap, 1000);
        }
        double lo = table[level];
        double hi = table[level + 1];
        double frac = hi > lo ? (xp - lo) / (hi - lo) : 0.0;
        return pair(level, Math.max(0, Math.min(1000, (long) (frac * 1000))));
    }

    static int petLevel(String tier, double exp, int maxLevel) {
        int level = 1;
        int off = PvTables.PET_XP_OFFSET.getOrDefault(tier == null ? "" : tier.toUpperCase(Locale.ROOT), 0);
        long[] d = PvTables.PET_XP_DELTAS;
        for (int i = off; i < Math.min(off + 99, d.length); i++) {
            if (exp < d[i] || level >= maxLevel) {
                break;
            }
            exp -= d[i];
            level++;
        }
        long last = d[d.length - 1];
        while (level < maxLevel && exp >= last) {
            exp -= last;
            level++;
        }
        return level;
    }

    /** Python's {@code round(x, 1)}: exact binary value, ties to even. */
    static double round1(double x) {
        return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python's {@code str.title()} for ASCII ids: upper after a non-letter, lower otherwise. */
    static String title(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevLetter = false;
        for (char c : s.toCharArray()) {
            sb.append(prevLetter ? Character.toLowerCase(c) : Character.toUpperCase(c));
            prevLetter = Character.isLetter(c);
        }
        return sb.toString();
    }

    static String pretty(String id) {
        return title(id.replace('_', ' '));
    }

    private static JsonArray pair(long a, long b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }

    private static JsonArray pair(String k, long v) {
        JsonArray arr = new JsonArray();
        arr.add(k);
        arr.add(v);
        return arr;
    }

    private static long n(Double v) {
        return v == null ? 0L : (long) (double) v;
    }

    private static double d(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String key(String k) {
        return PvText.plain(k, ID_LEN);
    }

    /** {@code {k: int(v)}} for a numeric map, keys sanitized. */
    private static JsonObject intMap(Map<String, Double> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Double> e : m.entrySet()) {
            o.addProperty(key(e.getKey()), n(e.getValue()));
        }
        return o;
    }

    /** {@code (total, [(name, count), ...])} of a counter map, sorted by count descending. */
    record Counter(long total, List<Map.Entry<String, Double>> entries) {
        JsonArray entriesJson() {
            JsonArray a = new JsonArray();
            for (Map.Entry<String, Double> e : entries) {
                a.add(pair(key(e.getKey()), n(e.getValue())));
            }
            return a;
        }
    }

    static Counter counter(Map<String, Double> raw) {
        if (raw == null) {
            return new Counter(0, List.of());
        }
        double total = d(raw.get("total"));
        List<Map.Entry<String, Double>> entries = new ArrayList<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (!e.getKey().equals("total")) {
                entries.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        entries.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed());
        if (total == 0) {
            for (Map.Entry<String, Double> e : entries) {
                total += e.getValue();
            }
        }
        return new Counter((long) total, entries);
    }

    // ------------------------------------------------------------------
    // One computation
    // ------------------------------------------------------------------

    /** Decoded inventory: the slots, or {@code null} with the section marked unavailable. */
    private static final class Run {
        private final PvModel.Profile profile;
        private final PvModel.Member m;
        private final PvResources res;
        private final Decoder decoder;
        private final Map<String, List<CompoundTag>> decoded = new HashMap<>();
        private final Set<String> failed = new HashSet<>();
        private final Set<String> unavailable = new LinkedHashSet<>();
        /** The old server's private stats ({@code _member_stats}) that the details reuse. */
        private final Map<String, Long> stats = new LinkedHashMap<>();
        private final Map<String, Double> bestiaryKills = new LinkedHashMap<>();
        private boolean ownedSeen;

        Run(PvModel.Profile profile, PvResources res, Decoder decoder) {
            this.profile = profile;
            this.m = profile.member();
            this.res = res;
            this.decoder = decoder;
            unavailable.addAll(m.invalid());
        }

        /** Decodes a blob once per run; {@code null} (and the section noted) on any failure. */
        private List<CompoundTag> decode(String blob, String section) {
            if (blob == null) {
                return null;
            }
            if (decoded.containsKey(blob)) {
                return decoded.get(blob);
            }
            if (failed.contains(blob)) {
                unavailable.add(section);
                return null;
            }
            try {
                List<CompoundTag> items = decoder.decode(blob);
                decoded.put(blob, items);
                return items;
            } catch (IOException | RuntimeException e) {
                failed.add(blob);
                unavailable.add(section);
                return null;
            }
        }

        JsonObject build(long nowMillis) {
            JsonObject view = new JsonObject();
            memberStats(view);
            skills(view);
            sbBar(view);
            slayers(view);
            JsonObject kuudra = new JsonObject();
            for (String t : PvTables.KUUDRA_TIERS) {
                if (stats.containsKey("kuudra_" + t)) {
                    kuudra.addProperty(t, stats.get("kuudra_" + t));
                }
            }
            view.add("kuudra", kuudra);
            put(view, "dungeons", dungeons());
            put(view, "slayer_detail", slayerDetail());
            put(view, "diana", diana());
            put(view, "crimson", crimson());
            put(view, "collections", collections());
            put(view, "mining", mining());
            put(view, "foraging", foraging());
            put(view, "fishing", fishing());
            put(view, "farming", farming());
            put(view, "hoppity", hoppity());
            put(view, "rift", rift());
            put(view, "accessories", accessories());
            put(view, "loadouts", loadouts());
            put(view, "heart_trees", heartTrees());
            classesAndCata(view);
            view.add("pets", pets());
            List<String> armor = names("inv_armor");
            java.util.Collections.reverse(armor);
            view.add("armor", strings(armor));
            view.add("equipment", strings(names("equipment_contents")));
            view.addProperty("inventory_api", ownedSeen);
            view.add("extras", extras(nowMillis));
            view.add("inventories", inventories());
            if (!unavailable.isEmpty()) {
                // An unavailable section shows its message, never a partial or empty-looking page.
                for (Map.Entry<String, String> e : PvSections.VIEW_KEY_SECTION.entrySet()) {
                    if (unavailable.contains(e.getValue())) {
                        view.remove(e.getKey());
                    }
                }
                view.add("unavailable", strings(new ArrayList<>(unavailable)));
            }
            return view;
        }

        private static void put(JsonObject view, String k, JsonObject v) {
            if (v != null && !v.isEmpty()) {
                view.add(k, v);
            }
        }

        private static JsonArray strings(List<String> list) {
            JsonArray a = new JsonArray();
            list.forEach(a::add);
            return a;
        }

        // ---- _member_stats -------------------------------------------------

        private void stat(JsonObject view, String k, long v) {
            stats.put(k, v);
            view.addProperty(k, v);
        }

        private void memberStats(JsonObject view) {
            if (m.levelingXp() != null) {
                stat(view, "sb_level", Math.floorDiv((long) (double) m.levelingXp(), 100L));
            }
            if (m.accessoryStorage() != null && m.accessoryStorage().highestMp() != null) {
                stat(view, "magical_power", n(m.accessoryStorage().highestMp()));
            }
            List<Integer> levels = new ArrayList<>();
            for (String s : PvParser.SKILLS) {
                Double xp = m.skillXp().get(s);
                if (xp != null) {
                    int level = levelFromXp(PvTables.SKILL_XP, xp);
                    stat(view, "skill_" + s, level);
                    levels.add(level);
                }
            }
            if (!levels.isEmpty()) {
                double sum = 0;
                for (int l : levels) {
                    sum += l;
                }
                view.addProperty("skill_average", round1(sum / levels.size()));
            }
            PvModel.Dungeons dg = m.dungeons();
            if (dg != null && dg.cataXp() != null) {
                stat(view, "catacombs", levelFromXp(PvTables.CATA_XP, dg.cataXp()));
            }
            for (String sl : PvParser.SLAYERS) {
                PvModel.SlayerBoss b = m.slayerBosses().get(sl);
                if (b != null && b.xp() != null) {
                    long[] table = sl.equals("vampire") ? PvTables.SLAYER_VAMPIRE_XP : PvTables.SLAYER_XP;
                    stat(view, "slayer_" + sl, levelFromXp(table, b.xp()));
                }
            }
            PvModel.MiningCore core = m.miningCore();
            if (core != null) {
                if (core.experience() != null) {
                    stat(view, "hotm", levelFromXp(PvTables.HOTM_XP, core.experience()) + 1);
                }
                double powder = 0;
                for (String k : List.of("powder_mithril", "powder_spent_mithril", "powder_gemstone",
                        "powder_spent_gemstone", "powder_glacite", "powder_spent_glacite")) {
                    powder += d(core.numbers().get(k));
                }
                if (powder > 0) {
                    stat(view, "powder", (long) powder);
                }
                List<Double> placed = new ArrayList<>();
                boolean any = false;
                for (String c : PvTables.CRYSTALS) {
                    PvModel.Crystal cr = core.crystals().get(c);
                    double p = cr == null ? 0 : d(cr.placed());
                    placed.add(p);
                    any |= p != 0;
                }
                if (any) {
                    stat(view, "nucleus_runs", (long) (double) java.util.Collections.min(placed));
                }
            }
            PvModel.PlayerStats ps = m.stats();
            if (ps != null && ps.summoningEyes() != null) {
                stat(view, "eyes_placed", n(ps.summoningEyes()));
            }
            Double mythos = ps == null ? null : ps.mythosKills();
            if (mythos == null) {
                mythos = m.legacyMythosKills();
            }
            if (mythos != null) {
                stat(view, "mythos_kills", n(mythos));
            }
            if (m.bestiary() != null) {
                if (m.bestiary().milestone() != null) {
                    stat(view, "bestiary_milestone", n(m.bestiary().milestone()));
                }
                if (m.bestiary().kills() != null) {
                    for (Map.Entry<String, Double> e : m.bestiary().kills().entrySet()) {
                        bestiaryKills.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                    }
                }
            }
            PvModel.Nether nether = m.nether();
            if (nether != null && nether.kuudra() != null) {
                for (String t : PvTables.KUUDRA_TIERS) {
                    Double v = nether.kuudra().get(t);
                    if (v != null) {
                        stat(view, "kuudra_" + t, n(v));
                    }
                }
            }
            for (PvModel.Pet pet : m.pets()) {
                String type = pet.type() == null ? "" : pet.type().toLowerCase(Locale.ROOT);
                String tier = pet.tier() == null ? "" : pet.tier().toUpperCase(Locale.ROOT);
                int rank = PvTables.PET_TIERS.indexOf(tier);
                if (!type.isEmpty() && rank >= 0) {
                    String k = "pet_" + key(type);
                    long prev = stats.getOrDefault(k, -1L);
                    stat(view, k, Math.max(prev, rank));
                }
            }
            long looting = owned();
            if (ownedSeen) {
                stat(view, "looting", looting);
            }
        }

        /** The ownership pass ({@code _owned}): only the max looting level reaches the view. */
        private long owned() {
            double looting = 0;
            PvModel.Inventory inv = m.inventory();
            List<String> blobs = new ArrayList<>();
            for (String k : List.of("inv_contents", "ender_chest_contents", "inv_armor",
                    "equipment_contents", "wardrobe_contents")) {
                String b = inv.containers().get(k);
                if (b != null) {
                    blobs.add(b);
                }
            }
            blobs.addAll(inv.backpacks().values());
            for (String b : blobs) {
                ownedSeen = true;
                List<CompoundTag> items = decode(b, "ownership");
                if (items == null) {
                    continue;
                }
                for (CompoundTag item : items) {
                    CompoundTag tag = tagOf(item);
                    if (tag == null) {
                        continue;
                    }
                    CompoundTag ea = tag.getCompoundOrEmpty("ExtraAttributes");
                    looting = Math.max(looting, number(ea.getCompoundOrEmpty("enchantments").get("looting")));
                }
            }
            return (long) looting;
        }

        // ---- skills / bars -------------------------------------------------

        private void skills(JsonObject view) {
            JsonObject skills = new JsonObject();
            for (String s : PvParser.SKILLS) {
                Double xp = m.skillXp().get(s);
                if (xp != null) {
                    skills.add(s, progress(PvTables.SKILL_XP, xp, null));
                }
            }
            Double carp = m.skillXp().get("carpentry");
            if (carp != null) {
                skills.add("carpentry", progress(PvTables.SKILL_XP, carp, 50));
            }
            view.add("skills", skills);
        }

        private void sbBar(JsonObject view) {
            if (m.levelingXp() != null) {
                long xp = (long) (double) m.levelingXp();
                view.add("sb_bar", pair(Math.floorDiv(xp, 100L), Math.floorMod(xp, 100L) * 10));
            }
        }

        private void slayers(JsonObject view) {
            JsonObject out = new JsonObject();
            for (String sl : PvParser.SLAYERS) {
                PvModel.SlayerBoss b = m.slayerBosses().get(sl);
                if (b != null && b.xp() != null) {
                    boolean vamp = sl.equals("vampire");
                    out.add(sl, progress(vamp ? PvTables.SLAYER_VAMPIRE_XP : PvTables.SLAYER_XP, b.xp(),
                            vamp ? 5 : 9));
                }
            }
            view.add("slayers", out);
        }

        private void classesAndCata(JsonObject view) {
            PvModel.Dungeons dg = m.dungeons();
            if (dg != null && dg.cataXp() != null) {
                view.add("catacombs_bar", progress(PvTables.CATA_XP, dg.cataXp(), 50));
            }
            JsonObject classes = new JsonObject();
            if (dg != null) {
                for (String c : PvParser.DUNGEON_CLASSES) {
                    Double xp = dg.classXp().get(c);
                    if (xp != null) {
                        classes.add(c, progress(PvTables.CATA_XP, xp, 50));
                    }
                }
            }
            view.add("classes", classes);
        }

        // ---- combat --------------------------------------------------------

        private static JsonArray floorRows(PvModel.DungeonType t, boolean master) {
            JsonArray rows = new JsonArray();
            if (t == null) {
                return rows;
            }
            Map<String, Double> completions = orEmpty(t.tierCompletions());
            Map<String, Double> best = orEmpty(t.fastest());
            Map<String, Double> bestS = orEmpty(t.fastestS());
            Map<String, Double> bestSPlus = orEmpty(t.fastestSPlus());
            Map<String, Double> score = orEmpty(t.bestScore());
            for (int tier = master ? 1 : 0; tier <= PvTables.DUNGEON_FLOORS; tier++) {
                String k = String.valueOf(tier);
                double runs = d(completions.get(k));
                if (runs == 0 && !best.containsKey(k)) {
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("name", tier == 0 ? "Entrance" : (master ? "M" : "F") + k);
                row.addProperty("tier", tier);
                row.addProperty("runs", (long) runs);
                if (best.containsKey(k)) {
                    row.addProperty("best", n(best.get(k)));
                }
                if (bestS.containsKey(k)) {
                    row.addProperty("s", n(bestS.get(k)));
                }
                if (bestSPlus.containsKey(k)) {
                    row.addProperty("splus", n(bestSPlus.get(k)));
                }
                if (score.containsKey(k)) {
                    row.addProperty("score", n(score.get(k)));
                }
                rows.add(row);
            }
            return rows;
        }

        private static <V> Map<String, V> orEmpty(Map<String, V> map) {
            return map == null ? Map.of() : map;
        }

        private JsonObject dungeons() {
            PvModel.Dungeons dg = m.dungeons();
            if (dg == null) {
                return null;
            }
            JsonArray cata = floorRows(dg.catacombs(), false);
            JsonArray master = floorRows(dg.master(), true);
            if (cata.isEmpty() && master.isEmpty()) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.add("catacombs", cata);
            out.add("master", master);
            long total = 0;
            for (JsonElement r : cata) {
                total += r.getAsJsonObject().get("runs").getAsLong();
            }
            for (JsonElement r : master) {
                total += r.getAsJsonObject().get("runs").getAsLong();
            }
            out.addProperty("runs_total", total);
            if (dg.secrets() != null) {
                out.addProperty("secrets", n(dg.secrets()));
            }
            return out;
        }

        private JsonObject slayerDetail() {
            JsonObject bosses = new JsonObject();
            long xpTotal = 0;
            long killsTotal = 0;
            for (String sl : PvParser.SLAYERS) {
                PvModel.SlayerBoss raw = m.slayerBosses().get(sl);
                if (raw == null) {
                    continue;
                }
                long xp = n(raw.xp());
                int tierCount = sl.equals("vampire") ? 5 : 4;
                JsonArray tiers = new JsonArray();
                long sum = 0;
                for (int t = 0; t < tierCount; t++) {
                    long k = n(raw.tierKills().get("boss_kills_tier_" + t));
                    tiers.add(k);
                    sum += k;
                }
                boolean anyTier = false;
                for (JsonElement t : tiers) {
                    anyTier |= t.getAsLong() != 0;
                }
                if (raw.xp() == null || raw.xp() == 0) {
                    if (!anyTier) {
                        continue;
                    }
                }
                JsonObject b = new JsonObject();
                b.addProperty("xp", xp);
                b.add("tiers", tiers);
                bosses.add(sl, b);
                xpTotal += xp;
                killsTotal += sum;
            }
            if (bosses.isEmpty()) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.add("bosses", bosses);
            out.addProperty("xp_total", xpTotal);
            out.addProperty("bosses_total", killsTotal);
            return out;
        }

        private JsonObject diana() {
            JsonObject out = new JsonObject();
            Map<String, Double> b = m.stats() == null ? Map.of() : m.stats().burrows();
            long next = n(b.get("burrows_dug_next"));
            long combat = n(b.get("burrows_dug_combat"));
            long treasure = n(b.get("burrows_dug_treasure"));
            long dug = next + combat + treasure;
            if (dug != 0) {
                out.addProperty("burrows_dug", dug);
                out.addProperty("burrows_next", next);
                out.addProperty("burrows_combat", combat);
                out.addProperty("burrows_treasure", treasure);
            }
            long chains = n(b.get("burrows_chains_complete"));
            if (chains != 0) {
                out.addProperty("chains", chains);
            }
            for (PvModel.Pet pet : m.pets()) {
                if (pet.type() != null && pet.type().equalsIgnoreCase("GRIFFIN")) {
                    String tier = pet.tier() == null ? "COMMON" : pet.tier().toUpperCase(Locale.ROOT);
                    int level = petLevel(tier, d(pet.exp()), 100);
                    long prev = out.has("griffin_level") ? out.get("griffin_level").getAsLong() : 0;
                    out.addProperty("griffin_level", Math.max(prev, level));
                }
            }
            JsonObject mobKills = new JsonObject();
            for (String mob : PvTables.MYTHOS_MOBS) {
                long total = 0;
                for (Map.Entry<String, Double> e : bestiaryKills.entrySet()) {
                    if (e.getKey().contains(mob)) {
                        total += (long) (double) e.getValue();
                    }
                }
                if (total != 0) {
                    mobKills.addProperty(mob, total);
                }
            }
            if (!mobKills.isEmpty()) {
                out.add("mob_kills", mobKills);
            }
            return out;
        }

        private JsonObject crimson() {
            PvModel.Nether nether = m.nether();
            if (nether == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            if (nether.faction() != null && !nether.faction().isEmpty()) {
                out.addProperty("faction", key(nether.faction()));
                if (nether.reputation() != null) {
                    out.addProperty("reputation", n(nether.reputation()));
                }
            }
            JsonObject dojo = new JsonObject();
            long total = 0;
            Map<String, Double> raw = orEmpty(nether.dojo());
            for (String[] test : PvTables.DOJO_TESTS) {
                Double points = raw.get("dojo_points_" + test[0]);
                if (points != null) {
                    dojo.addProperty(test[1], n(points));
                    total += n(points);
                }
            }
            if (!dojo.isEmpty()) {
                out.add("dojo", dojo);
                out.addProperty("dojo_total", total);
            }
            return out;
        }

        // ---- skills pages --------------------------------------------------

        private JsonObject collections() {
            Map<String, Double> owned = m.collection();
            if (owned == null || res.collections().isEmpty()) {
                return null;
            }
            JsonObject out = new JsonObject();
            for (PvResources.CollectionCategory cat : res.collections()) {
                List<JsonObject> items = new ArrayList<>();
                int unlocked = 0;
                for (PvResources.CollectionItem meta : cat.items()) {
                    long amount = n(owned.get(meta.id()));
                    long tier = 0;
                    Long need = null;
                    for (PvResources.Tier step : meta.tiers()) {
                        if (amount >= step.amountRequired()) {
                            tier = (long) step.tier();
                        } else {
                            need = (long) step.amountRequired();
                            break;
                        }
                    }
                    JsonObject e = new JsonObject();
                    e.addProperty("id", key(meta.id()));
                    e.addProperty("name", meta.name() == null || meta.name().isEmpty() ? key(meta.id())
                            : PvText.plain(meta.name(), NAME_LEN));
                    e.addProperty("amount", amount);
                    e.addProperty("tier", tier);
                    e.addProperty("max", (long) meta.maxTiers());
                    if (need != null) {
                        e.addProperty("next", need);
                    }
                    items.add(e);
                    if (tier > 0) {
                        unlocked++;
                    }
                }
                items.sort(Comparator.comparingLong((JsonObject i) -> i.get("tier").getAsLong())
                        .thenComparingLong(i -> i.get("amount").getAsLong()).reversed());
                JsonArray arr = new JsonArray();
                int maxed = 0;
                for (JsonObject i : items) {
                    arr.add(i);
                    long max = i.get("max").getAsLong();
                    if (max != 0 && i.get("tier").getAsLong() >= max) {
                        maxed++;
                    }
                }
                JsonObject c = new JsonObject();
                c.addProperty("name", cat.name() == null || cat.name().isEmpty() ? title(key(cat.key()))
                        : PvText.plain(cat.name(), NAME_LEN));
                c.add("items", arr);
                c.addProperty("unlocked", unlocked);
                c.addProperty("total", items.size());
                c.addProperty("maxed", maxed);
                out.add(key(cat.key()), c);
            }
            return out.isEmpty() ? null : out;
        }

        private JsonObject mining() {
            PvModel.MiningCore core = m.miningCore();
            if (core == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            if (stats.containsKey("hotm")) {
                out.addProperty("hotm", stats.get("hotm"));
            }
            if (stats.containsKey("nucleus_runs")) {
                out.addProperty("nucleus_runs", stats.get("nucleus_runs"));
            }
            JsonObject powder = new JsonObject();
            for (String kind : PvTables.POWDERS) {
                long current = n(core.numbers().get("powder_" + kind));
                long spent = n(core.numbers().get("powder_spent_" + kind));
                if (current != 0 || spent != 0) {
                    JsonObject p = new JsonObject();
                    p.addProperty("current", current);
                    p.addProperty("spent", spent);
                    p.addProperty("total", current + spent);
                    powder.add(kind, p);
                }
            }
            if (!powder.isEmpty()) {
                out.add("powder", powder);
            }
            JsonObject crystals = new JsonObject();
            for (Map.Entry<String, PvModel.Crystal> e : core.crystals().entrySet()) {
                PvModel.Crystal c = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("found", n(c.found()));
                o.addProperty("placed", n(c.placed()));
                o.addProperty("state", c.state() == null || c.state().isEmpty() ? "NOT_FOUND" : key(c.state()));
                crystals.add(key(e.getKey().replace("_crystal", "")), o);
            }
            if (!crystals.isEmpty()) {
                out.add("crystals", crystals);
            }
            if (core.biomesFlat() != null && !core.biomesFlat().isEmpty()) {
                out.add("biomes", intMap(core.biomesFlat()));
            }
            PvModel.Glacite g = m.glacite();
            if (g != null) {
                JsonObject gl = new JsonObject();
                if (g.mineshafts() != null) {
                    gl.addProperty("mineshafts", n(g.mineshafts()));
                }
                if (g.fossilDust() != null) {
                    gl.addProperty("fossil_dust", n(g.fossilDust()));
                }
                if (g.fossilsDonated() != null) {
                    gl.addProperty("fossils_donated", g.fossilsDonated());
                }
                if (g.corpses() != null) {
                    gl.add("corpses", intMap(g.corpses()));
                }
                if (!gl.isEmpty()) {
                    out.add("glacite", gl);
                }
            }
            if (m.stats() != null && m.stats().minedOres() != null) {
                Counter c = counter(m.stats().minedOres());
                if (c.total() != 0) {
                    out.addProperty("ores_mined", c.total());
                    out.add("ores_top", c.entriesJson());
                }
            }
            return out.isEmpty() ? null : out;
        }

        private JsonObject foraging() {
            PvModel.ForagingCore core = m.foragingCore();
            PvModel.Foraging fora = m.foraging();
            if (core == null && fora == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            if (core != null) {
                long whispers = n(core.whispers());
                long spent = n(core.whispersSpent());
                if (whispers != 0 || spent != 0) {
                    JsonObject w = new JsonObject();
                    w.addProperty("current", whispers);
                    w.addProperty("spent", spent);
                    w.addProperty("total", whispers + spent);
                    out.add("whispers", w);
                }
                if (core.dailyEffect() != null && !core.dailyEffect().isEmpty()) {
                    out.addProperty("daily_effect", key(core.dailyEffect()));
                }
            }
            if (fora != null) {
                if (fora.treeGifts() != null && !fora.treeGifts().isEmpty()) {
                    out.add("tree_gifts", intMap(fora.treeGifts()));
                }
                PvModel.Hina hina = fora.hina();
                if (hina != null) {
                    JsonObject task = new JsonObject();
                    if (hina.completed() != null) {
                        task.addProperty("completed", hina.completed());
                    }
                    if (hina.tierClaimed() != null) {
                        task.addProperty("tier", n(hina.tierClaimed()));
                    }
                    if (!task.isEmpty()) {
                        out.add("hina", task);
                    }
                }
                if (fora.harp() != null) {
                    Map<String, JsonObject> songs = new LinkedHashMap<>();
                    for (Map.Entry<String, Double> e : fora.harp().entrySet()) {
                        String k = e.getKey();
                        if (!k.startsWith("song_")) {
                            continue;
                        }
                        for (String[] sf : new String[][]{{"_perfect_completions", "perfect"},
                                {"_completions", "completions"}, {"_best_completion", "best"}}) {
                            if (k.endsWith(sf[0]) && k.length() >= 5 + sf[0].length()) {
                                String name = key(k.substring(5, k.length() - sf[0].length()));
                                songs.computeIfAbsent(name, x -> new JsonObject()).addProperty(sf[1], n(e.getValue()));
                                break;
                            }
                        }
                    }
                    if (!songs.isEmpty()) {
                        JsonObject harp = new JsonObject();
                        songs.forEach(harp::add);
                        out.add("harp", harp);
                    }
                }
                if (fora.personalBests() != null && !fora.personalBests().isEmpty()) {
                    out.add("personal_bests", intMap(fora.personalBests()));
                }
            }
            return out.isEmpty() ? null : out;
        }

        private JsonObject fishing() {
            JsonObject out = new JsonObject();
            PvModel.TrophyFish tf = m.trophyFish();
            if (tf != null) {
                Map<String, Double> counts = tf.counts();
                TreeSet<String> names = new TreeSet<>();
                for (String k : counts.keySet()) {
                    int cut = k.lastIndexOf('_');
                    String suffix = cut < 0 ? k : k.substring(cut + 1);
                    if (PvTables.TROPHY_TIERS.contains(suffix)) {
                        names.add(cut < 0 ? k : k.substring(0, cut));
                    }
                }
                List<JsonObject> fish = new ArrayList<>();
                for (String name : names) {
                    JsonObject f = new JsonObject();
                    f.addProperty("id", key(name));
                    f.addProperty("total", n(counts.get(name)));
                    JsonArray tiers = new JsonArray();
                    for (String t : PvTables.TROPHY_TIERS) {
                        tiers.add(n(counts.get(name + "_" + t)));
                    }
                    f.add("tiers", tiers);
                    fish.add(f);
                }
                fish.sort(Comparator.comparingLong((JsonObject f) -> f.get("total").getAsLong()).reversed());
                if (!fish.isEmpty()) {
                    JsonArray arr = new JsonArray();
                    fish.forEach(arr::add);
                    out.add("trophy", arr);
                    out.addProperty("trophy_total", n(counts.get("total_caught")));
                    JsonArray tierCounts = new JsonArray();
                    for (int i = 0; i < PvTables.TROPHY_TIERS.size(); i++) {
                        int c = 0;
                        for (JsonObject f : fish) {
                            if (f.getAsJsonArray("tiers").get(i).getAsLong() > 0) {
                                c++;
                            }
                        }
                        tierCounts.add(c);
                    }
                    out.add("trophy_tier_counts", tierCounts);
                }
                if (tf.rewards() != null) {
                    out.addProperty("trophy_rewards", tf.rewards());
                }
            }
            PvModel.PlayerStats ps = m.stats();
            if (ps != null) {
                if (ps.itemsFished() != null) {
                    out.add("items_fished", intMap(ps.itemsFished()));
                }
                if (ps.seaCreatureKills() != null) {
                    out.addProperty("sea_creatures", n(ps.seaCreatureKills()));
                }
            }
            return out.isEmpty() ? null : out;
        }

        private JsonObject farming() {
            JsonObject out = new JsonObject();
            if (stats.containsKey("skill_farming")) {
                out.addProperty("farming_level", stats.get("skill_farming"));
            }
            PvModel.Jacob j = m.jacob();
            if (j != null) {
                if (j.medals() != null && !j.medals().isEmpty()) {
                    out.add("medals", intMap(j.medals()));
                }
                if (j.perks() != null) {
                    out.add("perks", intMap(j.perks()));
                }
                if (j.contests() != null) {
                    out.addProperty("contests", j.contests());
                    out.addProperty("contests_claimed", j.claimedPositions().size());
                    if (!j.claimedPositions().isEmpty()) {
                        long top = 0;
                        for (Double p : j.claimedPositions()) {
                            if (d(p) < 1) {
                                top++;
                            }
                        }
                        out.addProperty("contests_top", top);
                    }
                }
                if (j.personalBests() != null && !j.personalBests().isEmpty()) {
                    out.add("personal_bests", intMap(j.personalBests()));
                }
                if (j.uniqueBrackets() != null && !j.uniqueBrackets().isEmpty()) {
                    JsonObject u = new JsonObject();
                    j.uniqueBrackets().forEach((k, v) -> u.addProperty(key(k), v));
                    out.add("unique_brackets", u);
                }
            }
            PvModel.Garden g = m.garden();
            if (g != null) {
                if (g.copper() != null) {
                    out.addProperty("copper", n(g.copper()));
                }
                if (g.larvaConsumed() != null) {
                    out.addProperty("larva_consumed", n(g.larvaConsumed()));
                }
                if (g.greenhouseCrops() != null) {
                    out.addProperty("greenhouse_crops", g.greenhouseCrops());
                }
            }
            return out.isEmpty() ? null : out;
        }

        private JsonObject hoppity() {
            PvModel.Easter e = m.easter();
            if (e == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            if (e.chocolate() != null) {
                out.addProperty("chocolate", n(e.chocolate()));
            }
            if (e.totalChocolate() != null) {
                out.addProperty("total", n(e.totalChocolate()));
            }
            if (e.sincePrestige() != null) {
                out.addProperty("since_prestige", n(e.sincePrestige()));
            }
            if (e.barnLevel() != null) {
                out.addProperty("barn_level", n(e.barnLevel()));
            }
            if (e.employees() != null && !e.employees().isEmpty()) {
                out.add("employees", intMap(e.employees()));
                long total = 0;
                for (Double v : e.employees().values()) {
                    total += n(v);
                }
                out.addProperty("employees_total", total);
            }
            if (e.timeTower() != null) {
                out.add("time_tower", intMap(e.timeTower()));
            }
            if (e.rabbits() != null) {
                Map<String, Double> found = e.rabbits();
                if (!found.isEmpty()) {
                    long total = 0;
                    long dup = 0;
                    List<Map.Entry<String, Double>> list = new ArrayList<>();
                    for (Map.Entry<String, Double> r : found.entrySet()) {
                        long v = n(r.getValue());
                        total += v;
                        if (r.getValue() > 1) {
                            dup += v - 1;
                        }
                        list.add(Map.entry(r.getKey(), r.getValue()));
                    }
                    out.addProperty("rabbits_unique", found.size());
                    out.addProperty("rabbits_total", total);
                    out.addProperty("rabbits_duplicates", dup);
                    list.sort(Comparator.comparingDouble((Map.Entry<String, Double> r) -> r.getValue()).reversed());
                    JsonArray arr = new JsonArray();
                    for (Map.Entry<String, Double> r : list) {
                        arr.add(pair(key(r.getKey()), n(r.getValue())));
                    }
                    out.add("rabbits_list", arr);
                }
                if (e.eggs() != null) {
                    out.add("eggs", intMap(e.eggs()));
                }
            }
            return out.isEmpty() ? null : out;
        }

        private JsonObject rift() {
            PvModel.Rift r = m.rift();
            if (r == null) {
                return null;
            }
            Map<String, Double> ps = m.stats() == null || m.stats().rift() == null ? Map.of() : m.stats().rift();
            JsonObject out = new JsonObject();
            if (r.souls() != null) {
                out.addProperty("enigma_souls", r.souls().size());
                List<String> sorted = new ArrayList<>(r.souls());
                java.util.Collections.sort(sorted);
                JsonArray arr = new JsonArray();
                sorted.forEach(s -> arr.add(key(s)));
                out.add("enigma_souls_list", arr);
            }
            if (r.cloak() != null) {
                out.addProperty("enigma_cloak", r.cloak());
            }
            for (String[] sf : new String[][]{{"visits", "visits"}, {"pass_consumed", "pass_consumed"},
                    {"lifetime_motes_earned", "motes_lifetime"}}) {
                if (ps.containsKey(sf[0])) {
                    out.addProperty(sf[1], n(ps.get(sf[0])));
                }
            }
            if (m.currencies() != null && m.currencies().motes() != null) {
                out.addProperty("motes", n(m.currencies().motes()));
            }
            JsonObject progress = new JsonObject();
            if (r.witherEyes() != null) {
                progress.add("Wither Eyes", pair(r.witherEyes(), 5));
            }
            if (r.deadCats() != null) {
                progress.add("Dead Cats", pair(r.deadCats(), 9));
            }
            if (r.trophies() != null) {
                progress.add("Gallery Trophies", pair(r.trophies(), 8));
            }
            if (r.boundaries() != null) {
                progress.add("Boundaries", pair(r.boundaries(), 8));
            }
            if (r.wizardStep() != null) {
                progress.add("Wizard Quest", pair(n(r.wizardStep()), 5));
            }
            if (r.eliseStep() != null) {
                progress.add("Elise", pair(n(r.eliseStep()), 6));
            }
            if (!progress.isEmpty()) {
                out.add("progress", progress);
            }
            if (r.montezuma()) {
                out.addProperty("montezuma", true);
            }
            if (!ps.isEmpty()) {
                List<Map.Entry<String, Double>> list = new ArrayList<>(ps.entrySet());
                list.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed());
                JsonArray arr = new JsonArray();
                for (Map.Entry<String, Double> e : list) {
                    arr.add(pair(key(e.getKey()), n(e.getValue())));
                }
                out.add("counters", arr);
            }
            return out.isEmpty() ? null : out;
        }

        // ---- accessories / loadouts -----------------------------------------

        /** Rarity words as the last lore line prints them, longest first ("VERY SPECIAL" before "SPECIAL"). */
        private static final List<String> LORE_RARITIES = List.of("VERY SPECIAL", "LEGENDARY", "UNCOMMON",
                "SUPREME", "SPECIAL", "MYTHIC", "COMMON", "EPIC", "RARE");

        /**
         * The rarity the item's own lore states ("§6§lLEGENDARY ACCESSORY", with obfuscated padding
         * when recombobulated), or {@code null} when no line names one. Read from the bottom up:
         * the rarity line is the last line Hypixel writes.
         */
        static String loreRarity(CompoundTag tag) {
            if (!(tag.getCompoundOrEmpty("display").get("Lore") instanceof ListTag lore)) {
                return null;
            }
            for (int i = lore.size() - 1; i >= Math.max(0, lore.size() - 3); i--) {
                String line = PvText.plain(stringOf(lore.get(i)), LORE_LINE_LEN).toUpperCase(Locale.ROOT);
                for (String word : LORE_RARITIES) {
                    if (line.contains(word + " ACCESSORY") || line.contains(word + " DUNGEON ACCESSORY")) {
                        return word.replace(' ', '_');
                    }
                }
            }
            return null;
        }

        private String rarityOf(String id, long recomb) {
            PvResources.Accessory meta = res.accessories().get(id);
            String base = meta == null || meta.tier() == null ? "" : meta.tier().toUpperCase(Locale.ROOT);
            if (recomb != 0 && PvTables.RARITY_LADDER.contains(base)) {
                int idx = Math.min(PvTables.RARITY_LADDER.indexOf(base) + 1, PvTables.RARITY_LADDER.size() - 1);
                return PvTables.RARITY_LADDER.get(idx);
            }
            return base;
        }

        /**
         * The accessory bag against the catalogue, and its Magical Power.
         *
         * <p>MP rule, matched to the game's own "Accessory Power" on a real profile (2026-10-08,
         * 1859 = 1859): each accessory's rarity is read from its <b>own lore</b> (recombobulated and
         * in-place upgrades such as Pulse Ring or Book of Progression included; the catalogue tier
         * undercounts those), only the <b>highest rung of each upgrade ladder</b> counts, the
         * Hegemony Artifact counts twice, and an Abicase adds half the player's active Abiphone
         * contacts. The catalogue tier plus the recombobulator flag is the fallback for an item
         * without a readable rarity line.
         *
         * <p>Lower rungs consumed by a held upgrade are neither owned nor missing: they are counted in
         * {@code covered_by_upgrade} and listed nowhere, so the grid shows each ladder once.
         */
        private JsonObject accessories() {
            String blob = m.inventory().bags().get("talisman_bag");
            if (blob == null) {
                return null;
            }
            List<CompoundTag> items = decode(blob, "accessories");
            if (items == null) {
                return null;
            }
            Map<String, PvResources.Accessory> catalogue = res.accessories();
            if (catalogue.isEmpty()) {
                return null;
            }
            // id -> rarity as the item itself states it (first copy wins; duplicates never add MP).
            TreeMap<String, String> owned = new TreeMap<>();
            Map<String, Boolean> recombed = new HashMap<>();
            for (CompoundTag item : items) {
                CompoundTag tag = tagOf(item);
                if (tag == null) {
                    continue;
                }
                CompoundTag ea = tag.getCompoundOrEmpty("ExtraAttributes");
                String id = idOf(ea);
                if (id.isEmpty() || owned.containsKey(id)) {
                    continue;
                }
                long recomb = (long) number(ea.get("rarity_upgrades"));
                String rarity = loreRarity(tag);
                owned.put(id, rarity != null ? rarity : rarityOf(id, recomb));
                recombed.put(id, recomb != 0);
            }

            // The rung of each ladder that counts: the highest one held.
            Map<String, Integer> bestStep = new HashMap<>();
            Map<String, String> bestId = new HashMap<>();
            for (String id : owned.keySet()) {
                PvResources.Rung rung = res.ladders().get(id);
                if (rung != null && rung.step() > bestStep.getOrDefault(rung.family(), Integer.MIN_VALUE)) {
                    bestStep.put(rung.family(), rung.step());
                    bestId.put(rung.family(), id);
                }
            }

            List<JsonObject> have = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String, String> e : owned.entrySet()) {
                String id = e.getKey();
                String rarity = e.getValue();
                PvResources.Rung rung = res.ladders().get(id);
                boolean counts = rung == null || id.equals(bestId.get(rung.family()));
                long mp = PvTables.MP_PER_RARITY.getOrDefault(rarity, 0);
                if (id.equals("HEGEMONY_ARTIFACT")) {
                    mp *= 2;
                } else if (id.equals("RIFT_PRISM")) {
                    mp = 11;
                } else if (id.equals("ABICASE") && m.nether() != null && m.nether().abiphoneContacts() != null) {
                    mp += m.nether().abiphoneContacts() / 2;
                }
                if (!counts) {
                    mp = 0;   // a lower rung held alongside a higher one adds nothing
                }
                total += mp;
                JsonObject a = new JsonObject();
                a.addProperty("id", key(id));
                a.addProperty("name", accName(catalogue.get(id), id));
                a.addProperty("rarity", rarity.isEmpty() ? "COMMON" : key(rarity));
                a.addProperty("mp", mp);
                a.addProperty("recomb", recombed.getOrDefault(id, false));
                a.addProperty("owned", true);
                if (!counts) {
                    a.addProperty("superseded_by", key(bestId.get(rung.family())));
                }
                have.add(a);
            }

            List<JsonObject> missing = new ArrayList<>();
            long covered = 0;
            for (Map.Entry<String, PvResources.Accessory> e : catalogue.entrySet()) {
                if (owned.containsKey(e.getKey())) {
                    continue;
                }
                PvResources.Rung rung = res.ladders().get(e.getKey());
                if (rung != null && rung.step() < bestStep.getOrDefault(rung.family(), Integer.MIN_VALUE)) {
                    covered++;   // consumed by an upgrade the player holds
                    continue;
                }
                String rarity = e.getValue().tier() == null ? "" : e.getValue().tier().toUpperCase(Locale.ROOT);
                JsonObject a = new JsonObject();
                a.addProperty("id", key(e.getKey()));
                a.addProperty("name", accName(e.getValue(), e.getKey()));
                a.addProperty("rarity", rarity.isEmpty() ? "COMMON" : key(rarity));
                a.addProperty("mp", PvTables.MP_PER_RARITY.getOrDefault(rarity, 0));
                a.addProperty("owned", false);
                missing.add(a);
            }
            Comparator<JsonObject> order = Comparator
                    .comparingInt((JsonObject a) -> -Math.max(0, PvTables.RARITY_LADDER.indexOf(a.get("rarity").getAsString())))
                    .thenComparing(a -> a.get("name").getAsString());
            have.sort(order);
            missing.sort(order);
            JsonObject out = new JsonObject();
            JsonArray h = new JsonArray();
            have.forEach(h::add);
            JsonArray mi = new JsonArray();
            missing.forEach(mi::add);
            out.add("owned", h);
            out.add("missing", mi);
            out.addProperty("mp_computed", total);
            out.addProperty("catalogue_total", catalogue.size());
            out.addProperty("bag_items", items.stream().filter(i -> tagOf(i) != null).count());
            out.addProperty("unique_owned", owned.size());
            out.addProperty("covered_by_upgrade", covered);
            PvModel.AccessoryStorage s = m.accessoryStorage();
            if (s != null) {
                if (s.highestMp() != null) {
                    out.addProperty("mp_highest", n(s.highestMp()));
                }
                if (s.selectedPower() != null && !s.selectedPower().isEmpty()) {
                    out.addProperty("power", key(s.selectedPower()));
                }
                if (s.bagUpgrades() != null) {
                    out.addProperty("bag_upgrades", n(s.bagUpgrades()));
                }
                if (s.tuningSlot0() != null) {
                    JsonObject t = new JsonObject();
                    s.tuningSlot0().forEach((k, v) -> {
                        if (v != 0) {
                            t.addProperty(key(k), n(v));
                        }
                    });
                    out.add("tuning", t);
                }
            }
            return out;
        }

        private static String accName(PvResources.Accessory meta, String id) {
            return meta == null || meta.name() == null || meta.name().isEmpty() ? key(pretty(id))
                    : PvText.formatted(meta.name(), NAME_LEN);
        }

        private JsonElement firstSlot(String blob, String section) {
            JsonArray slots = slots(blob, section);
            return slots == null || slots.isEmpty() ? JsonNull.INSTANCE : slots.get(0);
        }

        /**
         * {@code member.loadout} as three lists: the armour wardrobe, the equipment wardrobe, and the
         * loadouts that combine one slot of each with a pet, a power stone and tree presets.
         *
         * <p>Every wardrobe slot is listed, empty ones too, because the slot number is what a loadout
         * points at. The <b>worn</b> slot is empty in the API (its pieces are on the body), so it is
         * filled from {@code inv_armor} / {@code equipment_contents} and marked {@code on_body}.
         * Armour set N and equipment set N are never paired by number: only a loadout links them.
         */
        private JsonObject loadouts() {
            PvModel.Loadout lo = m.loadout();
            if (lo == null) {
                return null;
            }
            String wornArmor = lo.equippedArmor() == null ? null : String.valueOf(lo.equippedArmor().longValue());
            String wornEquip = lo.equippedEquipment() == null ? null
                    : String.valueOf(lo.equippedEquipment().longValue());
            JsonArray body = slots(m.inventory().containers().get("inv_armor"), "loadouts");
            JsonArray bodyArmor = new JsonArray();
            if (body != null) {
                for (int i = body.size() - 1; i >= 0; i--) {
                    bodyArmor.add(body.get(i));   // inv_armor is boots first; sets are helmet first
                }
            }
            JsonArray bodyEquip = slots(m.inventory().containers().get("equipment_contents"), "loadouts");

            JsonArray sets = wardrobe(lo.armor(), LOADOUT_PIECES, "armor", wornArmor, bodyArmor);
            JsonArray equipSets = wardrobe(lo.equipment(), EQUIPMENT_SLOTS, "equipment", wornEquip, bodyEquip);

            Map<String, PvModel.Pet> petsById = new HashMap<>();
            for (PvModel.Pet pet : m.pets()) {
                // A loadout names its pet by uniqueId (verified on a real profile); uuid as fallback.
                if (pet.uuid() != null) {
                    petsById.putIfAbsent(pet.uuid(), pet);
                }
                if (pet.uniqueId() != null) {
                    petsById.put(pet.uniqueId(), pet);
                }
            }
            JsonArray entries = new JsonArray();
            List<PvModel.LoadoutEntry> ordered = new ArrayList<>(lo.entries());
            // Hypixel writes them highest first; the menu numbers them 1, 2, 3...
            ordered.sort(Comparator.comparingLong(e -> Long.parseLong(e.id())));
            for (PvModel.LoadoutEntry e : ordered) {
                JsonObject l = new JsonObject();
                l.addProperty("id", Long.parseLong(e.id()));
                l.addProperty("name", e.name() == null || e.name().isEmpty() ? "Loadout " + e.id()
                        : PvText.plain(e.name(), 64));
                if (e.armorSet() != null) {
                    l.addProperty("armor_set", Long.parseLong(e.armorSet()));
                }
                if (e.equipmentSet() != null) {
                    l.addProperty("equipment_set", Long.parseLong(e.equipmentSet()));
                }
                if (e.powerStone() != null && !e.powerStone().isEmpty()) {
                    l.addProperty("power_stone", key(e.powerStone()));
                }
                PvModel.Pet pet = e.petUuid() == null ? null : petsById.get(e.petUuid());
                if (pet != null && pet.type() != null) {
                    String type = pet.type().toUpperCase(Locale.ROOT);
                    String tier = pet.tier() == null ? "COMMON" : pet.tier().toUpperCase(Locale.ROOT);
                    JsonObject p = new JsonObject();
                    p.addProperty("type", key(type));
                    p.addProperty("tier", key(tier));
                    p.addProperty("level", petLevel(tier, d(pet.exp()), type.equals("GOLDEN_DRAGON") ? 200 : 100));
                    if (pet.heldItem() != null && !pet.heldItem().isEmpty()) {
                        p.addProperty("item", key(pet.heldItem().toUpperCase(Locale.ROOT)));
                    }
                    if (pet.skin() != null && !pet.skin().isEmpty()) {
                        p.addProperty("skin", key(pet.skin().toUpperCase(Locale.ROOT)));
                    }
                    l.add("pet", p);
                }
                if (e.tuningSlot() != null) {
                    l.addProperty("tuning_slot", n(e.tuningSlot()));
                }
                if (e.hotmSlot() != null) {
                    l.addProperty("hotm_slot", n(e.hotmSlot()));
                }
                if (e.hotfSlot() != null) {
                    l.addProperty("hotf_slot", n(e.hotfSlot()));
                }
                // An unused loadout carries nothing but its id and name.
                l.addProperty("used", e.armorSet() != null || e.equipmentSet() != null || pet != null);
                entries.add(l);
            }
            if (sets.isEmpty() && equipSets.isEmpty() && entries.isEmpty()) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.add("sets", sets);
            out.add("equipment_sets", equipSets);
            out.add("loadouts", entries);
            if (lo.equippedArmor() != null) {
                out.addProperty("equipped_armor", n(lo.equippedArmor()));
            }
            if (lo.equippedEquipment() != null) {
                out.addProperty("equipped_equipment", n(lo.equippedEquipment()));
            }
            return out;
        }

        /** One wardrobe: every numbered slot, the worn one filled from the body. */
        private JsonArray wardrobe(Map<String, Map<String, String>> source, List<String> pieces,
                                   String field, String worn, JsonArray onBody) {
            JsonArray out = new JsonArray();
            for (String k : digitKeys(source.keySet())) {
                JsonArray items = new JsonArray();
                boolean any = false;
                for (String piece : pieces) {
                    JsonElement slot = firstSlot(source.get(k).get(piece), "loadouts");
                    any |= !slot.isJsonNull();
                    items.add(slot);
                }
                boolean isWorn = k.equals(worn);
                boolean fromBody = false;
                if (isWorn && !any && onBody != null) {
                    items = new JsonArray();
                    for (int i = 0; i < pieces.size(); i++) {
                        JsonElement slot = i < onBody.size() ? onBody.get(i) : JsonNull.INSTANCE;
                        fromBody |= !slot.isJsonNull();
                        items.add(slot);
                    }
                    any = fromBody;
                }
                JsonObject set = new JsonObject();
                set.addProperty("id", Long.parseLong(k));
                set.add(field, items);
                set.addProperty("worn", isWorn);
                set.addProperty("on_body", fromBody);
                set.addProperty("empty", !any);
                out.add(set);
            }
            return out;
        }

        /**
         * {@code skill_tree} as {@code {mountain, forest}}: each with its presets (a preset is one of
         * the {@code mining}, {@code mining_2}... node objects), the selected preset, ability, tokens
         * spent and tree XP. Perk ids are passed through as Hypixel writes them; placing them on a
         * tree is the page's job, because only the Heart of the Mountain has a verified layout.
         */
        private JsonObject heartTrees() {
            PvModel.SkillTree t = m.skillTree();
            if (t == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            put(out, "mountain", heartTree(t, "mining", "mountain"));
            put(out, "forest", heartTree(t, "foraging", "forest"));
            return out;
        }

        private JsonObject heartTree(PvModel.SkillTree t, String treeKey, String tokenKey) {
            JsonArray presets = new JsonArray();
            for (Map.Entry<String, Map<String, Double>> e : t.levels().entrySet()) {
                int preset = presetOf(e.getKey(), treeKey);
                if (preset < 1) {
                    continue;
                }
                JsonObject p = new JsonObject();
                p.addProperty("slot", preset);
                JsonObject perks = new JsonObject();
                e.getValue().forEach((perk, level) -> perks.addProperty(key(perk), n(level)));
                p.add("perks", perks);
                JsonArray disabled = new JsonArray();
                t.toggles().getOrDefault(e.getKey(), Map.of()).forEach((perk, on) -> {
                    if (!on) {
                        disabled.add(key(perk));
                    }
                });
                p.add("disabled", disabled);
                String tokens = preset == 1 ? tokenKey : tokenKey + "_" + preset;
                if (t.tokensSpent().containsKey(tokens)) {
                    p.addProperty("tokens_spent", n(t.tokensSpent().get(tokens)));
                }
                presets.add(p);
            }
            if (presets.isEmpty() && !t.experience().containsKey(treeKey)) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.add("presets", presets);
            if (t.selectedSlot().containsKey(treeKey)) {
                out.addProperty("selected_slot", n(t.selectedSlot().get(treeKey)));
            }
            if (t.selectedAbility().containsKey(treeKey)) {
                out.addProperty("ability", key(t.selectedAbility().get(treeKey)));
            }
            if (t.experience().containsKey(treeKey)) {
                out.addProperty("xp", n(t.experience().get(treeKey)));
            }
            return out;
        }

        /** {@code mining} -> 1, {@code mining_3} -> 3, anything else (another tree) -> 0. */
        static int presetOf(String key, String treeKey) {
            if (key.equals(treeKey)) {
                return 1;
            }
            String rest = key.startsWith(treeKey + "_") ? key.substring(treeKey.length() + 1) : "";
            return PvParser.isDigits(rest) ? Integer.parseInt(rest) : 0;
        }

        private static List<String> digitKeys(Set<String> keys) {
            List<String> out = new ArrayList<>(keys);
            out.sort(Comparator.comparingLong(Long::parseLong));
            return out;
        }

        // ---- pets / names / extras / inventories ----------------------------

        private JsonArray pets() {
            List<JsonObject> pets = new ArrayList<>();
            for (PvModel.Pet pet : m.pets()) {
                if (pet.type() == null || pet.type().isEmpty()) {
                    continue;
                }
                String type = pet.type().toUpperCase(Locale.ROOT);
                String tier = pet.tier() == null ? "COMMON" : pet.tier().toUpperCase(Locale.ROOT);
                int level = petLevel(tier, d(pet.exp()), type.equals("GOLDEN_DRAGON") ? 200 : 100);
                JsonObject p = new JsonObject();
                p.addProperty("type", key(type));
                p.addProperty("tier", key(tier));
                p.addProperty("level", level);
                if (pet.heldItem() != null && !pet.heldItem().isEmpty()) {
                    p.addProperty("item", key(pet.heldItem().toUpperCase(Locale.ROOT)));
                } else {
                    p.add("item", JsonNull.INSTANCE);
                }
                p.addProperty("active", pet.active());
                if (pet.skin() != null && !pet.skin().isEmpty()) {
                    p.addProperty("skin", key(pet.skin().toUpperCase(Locale.ROOT)));
                }
                pets.add(p);
            }
            pets.sort(Comparator.comparingInt((JsonObject p) -> p.get("level").getAsInt())
                    .thenComparingInt(p -> Math.max(0, PvTables.PET_TIERS.indexOf(p.get("tier").getAsString())))
                    .reversed());
            JsonArray arr = new JsonArray();
            pets.forEach(arr::add);
            return arr;
        }

        /** {@code _inv_names}: plain display names of the items with a tag. */
        private List<String> names(String container) {
            List<String> out = new ArrayList<>();
            List<CompoundTag> items = decode(m.inventory().containers().get(container), container);
            if (items == null) {
                return out;
            }
            for (CompoundTag item : items) {
                CompoundTag tag = tagOf(item);
                if (tag == null) {
                    continue;
                }
                String name = PvText.plain(stringOf(tag.getCompoundOrEmpty("display").get("Name")), NAME_LEN).strip();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
            return out;
        }

        private JsonObject extras(long nowMillis) {
            JsonObject ex = new JsonObject();
            if (profile.bankBalance() != null) {
                ex.addProperty("bank", n(profile.bankBalance()));
            }
            Double purse = m.currencies() == null ? null : m.currencies().coinPurse();
            if (purse == null) {
                purse = m.legacyCoinPurse();
            }
            if (purse != null) {
                ex.addProperty("purse", n(purse));
            }
            if (m.firstJoin() != null) {
                double days = (nowMillis / 1000.0 - m.firstJoin() / 1000.0) / DAY;
                ex.addProperty("days_joined", Math.max(0L, (long) days));
            }
            PvModel.PlayerStats ps = m.stats();
            if (ps != null) {
                if (ps.auctions() != null) {
                    ex.add("auctions", intMap(ps.auctions()));
                }
                long mined = counter(ps.minedOres()).total();
                if (mined == 0) {
                    mined = n(ps.blocksMined());
                }
                if (mined != 0) {
                    ex.addProperty("ores_mined", mined);
                }
                if (ps.seaCreatureKills() != null) {
                    ex.addProperty("sea_creatures", n(ps.seaCreatureKills()));
                }
                if (ps.itemsFished() != null) {
                    ex.addProperty("items_fished", n(ps.itemsFished().get("total")));
                    ex.addProperty("treasures_fished", n(ps.itemsFished().get("treasure")));
                    ex.addProperty("large_treasures", n(ps.itemsFished().get("treasure_large")));
                }
                Counter kills = counter(ps.kills());
                if (kills.total() != 0 || !kills.entries().isEmpty()) {
                    ex.addProperty("kills_total", kills.total());
                    ex.add("kills", kills.entriesJson());
                }
                Counter deaths = counter(ps.deaths());
                if (deaths.total() != 0 || !deaths.entries().isEmpty()) {
                    ex.addProperty("deaths_total", deaths.total());
                    ex.add("deaths", deaths.entriesJson());
                }
            }
            if (m.currencies() != null) {
                JsonObject ess = new JsonObject();
                for (Map.Entry<String, Double> e : m.currencies().essence().entrySet()) {
                    if (e.getValue() > 0) {
                        ess.addProperty(key(e.getKey().toUpperCase(Locale.ROOT)), n(e.getValue()));
                    }
                }
                if (!ess.isEmpty()) {
                    ex.add("essence", ess);
                }
            }
            if (m.fairySouls() != null) {
                ex.addProperty("fairy_souls", n(m.fairySouls()));
            }
            return ex;
        }

        private JsonObject inventories() {
            JsonObject out = new JsonObject();
            PvModel.Inventory inv = m.inventory();
            String[][] sources = {
                    {"inventory", "inv_contents", "c"}, {"ender_chest", "ender_chest_contents", "c"},
                    {"armor", "inv_armor", "c"}, {"equipment", "equipment_contents", "c"},
                    {"vault", "personal_vault_contents", "c"}, {"accessory_bag", "talisman_bag", "b"},
                    {"potion_bag", "potion_bag", "b"}, {"fishing_bag", "fishing_bag", "b"},
                    {"quiver", "quiver", "b"}};
            for (String[] s : sources) {
                String blob = (s[2].equals("c") ? inv.containers() : inv.bags()).get(s[1]);
                JsonArray slots = slots(blob, s[0]);
                if (slots == null || slots.isEmpty()) {
                    continue;
                }
                if (s[0].equals("armor")) {
                    JsonArray rev = new JsonArray();
                    for (int i = slots.size() - 1; i >= 0; i--) {
                        rev.add(slots.get(i));
                    }
                    slots = rev;
                }
                out.add(s[0], slots);
            }
            List<String> keys = new ArrayList<>(inv.backpacks().keySet());
            keys.sort(Comparator.comparingLong(k -> PvParser.isDigits(k) ? Long.parseLong(k) : 0L));
            JsonArray packs = new JsonArray();
            JsonArray numbers = new JsonArray();
            for (String k : keys) {
                JsonArray slots = slots(inv.backpacks().get(k), "backpacks");
                if (slots != null && !slots.isEmpty()) {
                    packs.add(slots);
                    // The in-game number (keys count from 0); a missing pack would shift positions.
                    numbers.add(PvParser.isDigits(k) ? Long.parseLong(k) + 1 : packs.size());
                }
            }
            if (!packs.isEmpty()) {
                out.add("backpacks", packs);
                out.add("backpack_numbers", numbers);
            }
            return out;
        }

        /** {@code _inv_slots}: position-true slot list; {@code null} when absent or undecodable. */
        private JsonArray slots(String blob, String section) {
            if (blob == null) {
                return null;
            }
            List<CompoundTag> items = decode(blob, section);
            if (items == null) {
                return null;
            }
            JsonArray out = new JsonArray();
            for (CompoundTag item : items) {
                CompoundTag tag = tagOf(item);
                if (tag == null) {
                    out.add(JsonNull.INSTANCE);
                    continue;
                }
                String id = idOf(tag.getCompoundOrEmpty("ExtraAttributes"));
                CompoundTag disp = tag.getCompoundOrEmpty("display");
                String name = PvText.formatted(stringOf(disp.get("Name")), NAME_LEN).strip();
                long count = (long) number(item.get("Count"));
                if (count == 0) {
                    count = 1;
                }
                JsonObject slot = new JsonObject();
                slot.add("id", id.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(key(id)));
                slot.addProperty("name", !name.isEmpty() ? name : id.isEmpty() ? "?" : key(id));
                slot.addProperty("count", Math.max(1, count));
                if (disp.get("Lore") instanceof ListTag lore && !lore.isEmpty()) {
                    JsonArray lines = new JsonArray();
                    for (int i = 0; i < lore.size() && i < LORE_LINES; i++) {
                        lines.add(PvText.formatted(stringOf(lore.get(i)), LORE_LINE_LEN));
                    }
                    slot.add("lore", lines);
                }
                if (disp.get("color") instanceof NumericTag color) {
                    slot.addProperty("color", ((long) color.doubleValue()) & 0xFFFFFF);
                }
                // An applied skin lives only in the head's own texture (SkullOwner), never in the id.
                String skull = skullTexture(tag);
                if (skull != null) {
                    slot.addProperty("skull", skull);
                }
                // The item's attributes as SNBT, so the page can rebuild a stack the offline
                // appraisal prices exactly as it prices the real item (enchants, stars, gems...).
                CompoundTag ea = tag.getCompoundOrEmpty("ExtraAttributes");
                if (!ea.isEmpty()) {
                    String snbt = ea.toString();
                    if (snbt.length() <= EA_LEN) {
                        slot.addProperty("ea", snbt);
                    }
                }
                out.add(slot);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // NBT access
    // ------------------------------------------------------------------

    /** The item's {@code tag} compound, or {@code null} when absent or empty (an empty slot). */
    static CompoundTag tagOf(CompoundTag item) {
        return item.get("tag") instanceof CompoundTag c && !c.isEmpty() ? c : null;
    }

    static String idOf(CompoundTag ea) {
        Tag id = ea.get("id");
        String s = id instanceof StringTag st ? st.value()
                : id instanceof NumericTag num ? String.valueOf(num.box()) : "";
        return s.toUpperCase(Locale.ROOT);
    }

    /** Longest head texture carried; real ones are a few hundred characters of base64. */
    private static final int SKULL_LEN = 4096;

    /** The base64 texture of a player-head item ({@code SkullOwner.Properties.textures[0].Value}), or null. */
    static String skullTexture(CompoundTag tag) {
        if (!(tag.getCompoundOrEmpty("SkullOwner").getCompoundOrEmpty("Properties").get("textures")
                instanceof ListTag textures) || textures.isEmpty()
                || !(textures.get(0) instanceof CompoundTag first)) {
            return null;
        }
        String value = stringOf(first.get("Value"));
        return value.isEmpty() || value.length() > SKULL_LEN || !value.matches("[A-Za-z0-9+/=]+") ? null : value;
    }

    static String stringOf(Tag tag) {
        if (tag == null) {
            return "";
        }
        return tag instanceof StringTag s ? s.value() : tag.toString();
    }

    static double number(Tag tag) {
        return tag instanceof NumericTag n ? n.doubleValue() : 0.0;
    }
}
