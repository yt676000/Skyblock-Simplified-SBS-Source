/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.diana.model.MythCreature;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.PlainText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rare mythological creatures near the player: what they are, how much health is left, and
 * whether anybody has put a shuriken on them.
 *
 * <h2>How a creature is found</h2>
 *
 * <p>These are vanilla entities wearing a floating armour-stand nametag, exactly as every other
 * SkyBlock mob is, so the nametag is the identity. A stand counts when its stripped name contains
 * one of the four watched creature names - or the player's override for one.
 *
 * <p><b>This is deliberately narrower than "every mythological mob".</b> A reference implementation
 * identifies the whole family by a private-use glyph Hypixel prefixes onto their names, which would
 * also catch the common ones and would work for creatures this build has never heard of. We do not
 * key on it, because a private-use glyph is exactly what a resource pack or a font change moves, and
 * an unverified glyph would make the whole feature silently dark rather than partially. The
 * narrowing is recorded in the feature's {@code Not done yet}; one capture promotes it.
 *
 * <h2>The one-second confirmation window, and what it does not cover</h2>
 *
 * <p>A stand's custom name is usually not set at the instant it loads. So a newly seen stand with
 * <b>no name at all</b> is parked and re-read for a second, and dropped if nothing arrives. Without
 * that window almost nothing is detected; without the timeout, every armour stand in the Hub is kept
 * for ever.
 *
 * <p><b>A stand that has a name which does not match is a different case, and is never dropped.</b>
 * Hypixel rewrites these names constantly - the health changes on every hit - and a creature's stand
 * can carry something else entirely for its first moments. Blacklisting on one non-matching read is
 * what made Inquisitors undetectable: a single early tick lost the entity for the rest of the visit,
 * and every feature downstream of detection went with it. Named stands are simply re-read every
 * tick, which is what the general mob highlighter does and costs a string comparison.
 *
 * <h2>Reading a nametag</h2>
 *
 * <p>Health comes out of the {@code current/max} portion with its {@code K} and {@code M} suffixes.
 * A star glyph means a shuriken has been applied - the warning is its <i>absence</i>. King Minos has
 * a phase counted in hits rather than health, which is a different reading from the same tag and is
 * why the health field can legitimately be missing on a live creature.
 */
public final class MythMobTracker {

    private static final MythMobTracker INSTANCE = new MythMobTracker();

    /** How long a freshly loaded stand is given to reveal a name. */
    private static final long CONFIRM_MS = 1_000L;

    /** How long a creature stays in the list after its stand was last read successfully. */
    private static final long PERSIST_MS = 4_000L;

    /** "1.5M/1.5M" or "430K/1.2M" or "12000/12000". */
    private static final Pattern HEALTH = Pattern.compile(
            "([0-9][0-9.,]*)\\s*([KkMmBb]?)\\s*/\\s*([0-9][0-9.,]*)\\s*([KkMmBb]?)");

    /** King Minos counts a phase in hits. */
    private static final Pattern HITS = Pattern.compile("(?i)(\\d+)\\s+hits?");

    /** Applied-shuriken marker. Its absence is what the warning is about. */
    private static final char STAR = '✯';

    /** One creature currently on screen. */
    public record Sighting(MythCreature creature, String label, BlockPos pos,
                           double health, double maxHealth, Integer hits, boolean starred,
                           long seenAt, String sharedBy) {

        /** Whether this came from another player's message rather than from our own eyes. */
        public boolean shared() {
            return sharedBy != null && !sharedBy.isBlank();
        }
    }

    /** Entity id -> when it was first seen, for stands whose name has not arrived yet. */
    private final Map<Integer, Long> unconfirmed = new HashMap<>();

    /**
     * Entity ids that never produced a name, so they are not re-parked every tick for ever.
     *
     * <p><b>Only that case.</b> A stand whose name simply does not match a watched creature is not
     * in here and is re-read on every tick: these names change constantly, and dismissing on one
     * non-matching read loses the entity permanently. That was the bug behind "Inquisitors are not
     * highlighted and not shared" - detection never happened, so nothing downstream of it could.
     */
    private final Set<Integer> dismissed = new HashSet<>();

    /** Named stands looked at on the last tick, for the readout. */
    private volatile int scanned;

    /** Named stands looked at on the last tick that matched a watched creature. */
    private volatile int matched;

    /** Entity ids already alerted on, so one creature produces one low-health alert. */
    private final Set<Integer> alerted = new HashSet<>();

    /** Entity ids already announced, so one creature produces one party message. */
    private final Set<Integer> announced = new HashSet<>();

    /** What is on screen right now, rebuilt each tick. Copy-on-write: the render pass reads it. */
    private final List<Sighting> sightings = new CopyOnWriteArrayList<>();

    /** Sightings from other players' messages, which have no entity to re-read. */
    private final List<Sighting> shared = new CopyOnWriteArrayList<>();

    private MythMobTracker() {
    }

    public static MythMobTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** Everything worth drawing: what we can see, plus what somebody told us about. */
    public List<Sighting> sightings() {
        List<Sighting> out = new ArrayList<>(sightings);
        long ttl = cfg().creatureMarkerSeconds * 1000L;
        long now = System.currentTimeMillis();
        for (Sighting sighting : shared) {
            if (now - sighting.seenAt() <= ttl) {
                out.add(sighting);
            } else {
                shared.remove(sighting);
            }
        }
        return out;
    }

    /** The creature a name belongs to, honouring the player's overrides. {@code null} for none. */
    public static MythCreature creatureFor(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        SBSConfig.DianaSettings cfg = ConfigManager.getInstance().get().diana;
        for (MythCreature creature : MythCreature.values()) {
            if (creature.matches(name, cfg.creatureNames.get(creature.name()))) {
                return creature;
            }
        }
        return null;
    }

    /** Whether the player asked to be told about this creature. */
    public static boolean watched(MythCreature creature) {
        return creature != null
                && ConfigManager.getInstance().get().diana.watchedCreatures.contains(creature.name());
    }

    /**
     * Once per tick: re-read the stands, refresh the list, and fire what needs firing.
     *
     * <p>Collected on the tick rather than in render for the reason every other detector in this mod
     * does it: entity iteration twenty times a second at most, while the markers still follow the
     * creatures because the renderer samples their live positions.
     */
    public void onClientTick() {
        SBSConfig.DianaSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (!cfg.enabled || level == null || player == null || !SkyBlockLocation.onIsland("Hub")) {
            sightings.clear();
            return;
        }

        long now = System.currentTimeMillis();
        List<Sighting> found = new ArrayList<>();
        scanned = 0;
        matched = 0;

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand)) {
                continue;
            }
            int id = stand.getId();
            if (dismissed.contains(id)) {
                continue;
            }
            Component custom = stand.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            if (raw.isEmpty()) {
                // The name has not arrived yet. Park it, and give up on it after a second - the
                // difference between detecting these creatures and detecting none of them.
                Long since = unconfirmed.putIfAbsent(id, now);
                if (since != null && now - since > CONFIRM_MS) {
                    unconfirmed.remove(id);
                    dismissed.add(id);
                }
                continue;
            }
            unconfirmed.remove(id);
            scanned++;

            String name = PlainText.strip(raw);
            MythCreature creature = creatureFor(name);
            if (creature == null) {
                // Deliberately NOT dismissed. A stand whose name does not match right now is
                // re-read on the next tick, because Hypixel rewrites these names continuously - the
                // health changes on every hit, and a creature's stand can carry something else
                // entirely for the first moments of its life. Blacklisting on one non-matching read
                // is what made Inquisitors undetectable: a single early tick was enough to lose the
                // entity for the rest of the visit, and nothing downstream could ever see it.
                if (looksMythological(name)) {
                    DianaDebug.getInstance().onUnknownCreature(name);
                }
                continue;
            }
            if (!watched(creature)) {
                continue;
            }

            double[] health = parseHealth(name);
            Integer hits = parseHits(name);
            boolean starred = name.indexOf(STAR) >= 0;
            BlockPos pos = BlockPos.containing(stand.getX(), stand.getY(), stand.getZ());
            Sighting sighting = new Sighting(creature,
                    MythCreature.stripVariantPrefix(name), pos,
                    health == null ? -1 : health[0], health == null ? -1 : health[1],
                    hits, starred, now, null);
            found.add(sighting);
            matched++;

            maybeAlert(cfg, id, sighting);
            maybeAnnounce(cfg, id, sighting, player);
        }

        sightings.clear();
        sightings.addAll(found);
        prune(level, now);
    }

    /**
     * A nametag that carries a health readout but names no creature we know.
     *
     * <p>Worth a log line and nothing else. This is how the four hypothesised names get corrected
     * from one session in game instead of from a release.
     */
    private static boolean looksMythological(String name) {
        return HEALTH.matcher(name).find() && name.toLowerCase(Locale.ROOT).contains("minos");
    }

    /** Fires the low-health alert at most once per creature. */
    private void maybeAlert(SBSConfig.DianaSettings cfg, int id, Sighting sighting) {
        if (cfg.lowHealthMillions <= 0 || sighting.health() < 0 || alerted.contains(id)) {
            return;
        }
        double threshold = cfg.lowHealthMillions * 1_000_000.0;
        if (sighting.health() > threshold) {
            return;
        }
        alerted.add(id);
        Alerts.send(new Alerts.Alert(sighting.label() + " is low",
                format(sighting.health()) + " health left",
                SbsAudio.Tone.ALARM, null), cfg.creatureAlertChannels);
    }

    /**
     * Posts the spawn to party chat, at most once per creature, and only when it is <b>yours</b>.
     *
     * <p>Off by default and opt-in, because this is the mod speaking to other people unprompted.
     * Single-shot per creature for the same reason every announcement in this mod is: a feature that
     * repeats is one the party mutes.
     *
     * <p><b>Yours</b> means a burrow you dug spawned it, which chat states and nothing else does.
     * Without that test this fires on the first sighting of any Inquisitor - including one somebody
     * else dug up forty blocks away - and posts it to your party under your name. That is not a
     * false coordinate, but it is a claim about who found it, and it turns one person's party into a
     * relay for every spawn in the lobby.
     */
    private void maybeAnnounce(SBSConfig.DianaSettings cfg, int id, Sighting sighting, Player player) {
        if (!cfg.announceSpawnsToParty || announced.contains(id)) {
            return;
        }
        if (!DianaTracker.getInstance().justSpawned(sighting.creature())) {
            return;
        }
        announced.add(id);
        RareCreatureShare.getInstance().announce(sighting, player);
    }

    /** Drops entities that have gone, and the bookkeeping sets with them. */
    private void prune(ClientLevel level, long now) {
        unconfirmed.entrySet().removeIf(e -> now - e.getValue() > CONFIRM_MS * 4
                || level.getEntity(e.getKey()) == null);
        dismissed.removeIf(id -> level.getEntity(id) == null);
        alerted.removeIf(id -> level.getEntity(id) == null);
        announced.removeIf(id -> level.getEntity(id) == null);
    }

    /** A creature somebody else found, from their coordinates in party chat. */
    public void addShared(MythCreature creature, BlockPos pos, String sender) {
        if (creature == null || pos == null) {
            return;
        }
        for (Sighting existing : shared) {
            if (existing.creature() == creature
                    && Vec3.atCenterOf(existing.pos()).distanceTo(Vec3.atCenterOf(pos)) <= 10.0) {
                // The same creature, shared twice. Ten blocks because two people standing either
                // side of one Inquisitor report positions that far apart.
                return;
            }
        }
        shared.add(new Sighting(creature, creature.defaultName(), pos,
                -1, -1, null, false, System.currentTimeMillis(), sender));
    }

    /** World change, server hop, island change, or the player asking. */
    public void reset() {
        unconfirmed.clear();
        dismissed.clear();
        alerted.clear();
        announced.clear();
        sightings.clear();
        shared.clear();
    }

    /**
     * The stand bookkeeping and every sighting, own and shared, for the guard's error report. Copies
     * of the id sets are taken first: the tick writes them, and this must not trip over that write.
     */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("scanned", scanned);
        out.addProperty("matched", matched);
        out.addProperty("unconfirmed", new ArrayList<>(unconfirmed.keySet()).toString());
        out.addProperty("dismissed", new HashSet<>(dismissed).size());
        out.addProperty("alerted", new ArrayList<>(alerted).toString());
        out.addProperty("announced", new ArrayList<>(announced).toString());
        out.add("sightings", sightingsJson(sightings));
        out.add("shared", sightingsJson(shared));
        return out;
    }

    private static JsonArray sightingsJson(List<Sighting> list) {
        JsonArray out = new JsonArray();
        for (Sighting sighting : list) {
            if (out.size() >= DianaSnapshot.MAX_ENTRIES) {
                break;
            }
            JsonObject s = new JsonObject();
            s.addProperty("creature", String.valueOf(sighting.creature()));
            s.addProperty("label", sighting.label());
            s.addProperty("pos", DianaSnapshot.pos(sighting.pos()));
            s.addProperty("health", DianaSnapshot.num(sighting.health()));
            s.addProperty("maxHealth", DianaSnapshot.num(sighting.maxHealth()));
            s.addProperty("hits", sighting.hits());
            s.addProperty("starred", sighting.starred());
            s.addProperty("seenAt", sighting.seenAt());
            s.addProperty("sharedBy", sighting.sharedBy());
            out.add(s);
        }
        return out;
    }

    /**
     * One line for the debug readout.
     *
     * <p>Leads with the scan counts when there is nothing to report, because "no watched creature in
     * sight" on its own cannot distinguish a quiet Hub from a detector that is looking at three
     * hundred nametags and recognising none of them. The four names it matches are unverified, so
     * that second case is the likely one and it needs to be visible.
     */
    public String status() {
        List<Sighting> all = sightings();
        if (all.isEmpty()) {
            return "no watched creature in sight - " + scanned + " named stand(s) read this tick, "
                    + matched + " matched; highlight: " + CreatureHighlight.status();
        }
        StringBuilder out = new StringBuilder(96);
        out.append(all.size()).append(" sighting(s)");
        out.append(" - highlight: ").append(CreatureHighlight.status());
        for (Sighting sighting : all) {
            out.append("\n  ").append(sighting.label());
            if (sighting.hits() != null) {
                out.append(" - ").append(sighting.hits()).append(" hits");
            } else if (sighting.health() >= 0) {
                out.append(" - ").append(format(sighting.health()));
            }
            if (sighting.shared()) {
                out.append(" (from ").append(sighting.sharedBy()).append(')');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Nametag reading
    // ------------------------------------------------------------------

    /**
     * {@code {current, max}}, or {@code null} when the tag carries no health.
     *
     * <p>Public and static because it is a pure function of one nametag, which is what lets the
     * shapes Hypixel actually writes be tested without a client - the same reason
     * {@code DianaEvent.evaluate} is shaped that way. Nothing about these tags has been verified
     * against the live game, so the reading is worth pinning down where it can be.
     */
    public static double[] parseHealth(String name) {
        Matcher matcher = HEALTH.matcher(name);
        if (!matcher.find()) {
            return null;
        }
        Double current = scaled(matcher.group(1), matcher.group(2));
        Double max = scaled(matcher.group(3), matcher.group(4));
        return current == null || max == null ? null : new double[] {current, max};
    }

    /** The hit count on a King Minos tag, or {@code null}. Public and static for the reason above. */
    public static Integer parseHits(String name) {
        Matcher matcher = HITS.matcher(name);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double scaled(String digits, String suffix) {
        try {
            double value = Double.parseDouble(digits.replace(",", ""));
            return switch (suffix == null ? "" : suffix.toUpperCase(Locale.ROOT)) {
                case "K" -> value * 1_000.0;
                case "M" -> value * 1_000_000.0;
                case "B" -> value * 1_000_000_000.0;
                default -> value;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "1.5M", "430K", "9200" - the way the number is read in game. */
    public static String format(double health) {
        if (health >= 1_000_000.0) {
            return String.format(Locale.ROOT, "%.1fM", health / 1_000_000.0);
        }
        if (health >= 1_000.0) {
            return String.format(Locale.ROOT, "%.0fK", health / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.0f", health);
    }
}
