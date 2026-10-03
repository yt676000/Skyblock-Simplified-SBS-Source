/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.hideyho.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Hideyho round: what it says in chat, which hiding places are still worth walking to, and which
 * one the router is sent to next.
 *
 * <p><b>This feature draws no critter and sees through nothing.</b> The request was explicit, and the
 * mechanic agrees with it: the moment the Hideyho hides it is somewhere else in the biome, and a box
 * on it - through walls or otherwise - would either be impossible to draw or would answer the whole
 * question the round asks. So what is published is the <i>search</i>: a marker on each place the
 * critter has been found before, occluded like anything else in the world
 * ({@link Waypoint#throughWalls} {@code false}), crossed off as the player actually looks at it, with
 * the pathfinder walking them to the nearest one that is left.
 *
 * <p><b>Crossing a spot off needs a clear line to it, not only nearness.</b> Distance alone would
 * retire a spot behind a wall the player never saw - the failure that makes a finder untrustworthy -
 * so the same {@code ClipContext.Block.COLLIDER} ray the waypoint renderer uses for its own
 * occlusion decides it here. A checked spot dims rather than vanishing, so the search is visibly
 * shrinking, and it stops being {@link Waypoint#routable} so the route moves on by itself: the goal
 * set changed, the pathfinder notices and re-searches, and there is no "advance to the next one"
 * path to get wrong.
 *
 * <p><b>The round is driven by chat, which is what makes the blindness a non-event.</b> The one
 * second of Blindness II the critter casts is a rendering effect; the state machine never looks at
 * the screen. The end is anchored on the payout line and not on "you found me", because the idle
 * greeting contains those words too and a lazy pattern would end a round that never started.
 *
 * <p><b>Every pattern here is unverified.</b> The wording came from a public wiki, not from a
 * capture, so each Hideyho line that no pattern claims is logged under {@code [SBS][Hideyho]} - one
 * trip settles the real wording, and the log is what makes it a fix rather than an investigation.
 */
public final class HideyhoTracker {

    private static final HideyhoTracker INSTANCE = new HideyhoTracker();

    /** The critter's own name, as it appears in the bundled shard data. */
    public static final String CRITTER = "Hideyho";

    /** Only every fifth tick decides whether a spot has been looked at; a walk is not fast. */
    private static final int CHECK_INTERVAL_TICKS = 5;

    /** Anything the critter says. Everything below is only asked of lines that pass this. */
    private static final Pattern SPEAKS = Pattern.compile("(?i)\\bhideyho\\b\\s*:");

    /** The critter accepting: the round starts here, before the blindness lands. */
    private static final Pattern ROUND_STARTS = Pattern.compile(
            "(?i)come find me|close your eyes|no peeking|stay within the .*biome");

    /**
     * The payout. The one line that only ever means the round is over, and the most informative one
     * available - it carries the elapsed time and the shards paid.
     */
    private static final Pattern PAYOUT = Pattern.compile(
            "(?i)it took you\\s+(.+?)[,.]?\\s+so you get\\s+(\\d+)\\s+shard");

    /**
     * The find, as a fallback for a reworded payout.
     *
     * <p>Anchored on the exclamation the wiki records before it, because the idle greeting -
     * "Hehe, you found me!" - carries the same three words with no round running. Only ever asked
     * while one is.
     */
    private static final Pattern FOUND = Pattern.compile("(?i)\\baah?\\b[^a-z]*you found me");

    /** The offer refused: no round, and nothing should be left standing. */
    private static final Pattern DECLINED = Pattern.compile("(?i)no shard for you then");

    /** Unmatched critter lines are logged, but not the same one forever. */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** When the running round started, or {@code 0} for no round. */
    private long roundStartedAt;

    /** The ids of the spots already looked at this round. */
    private final Set<String> checked = new HashSet<>();

    /** What the published set was built from, so a republish happens exactly when it would differ. */
    private String publishedSignature = "";

    /** How many spots are out, for the settings page. */
    private int publishedCount;

    private int tickCounter;
    private long lastLogAt;

    /** The last round's payout, kept for the settings page: shards, and how long it took. */
    private String lastPayout = "";

    private HideyhoTracker() {
    }

    public static HideyhoTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.HideyhoSettings cfg() {
        return ConfigManager.getInstance().get().hideyho;
    }

    // ---- state ----------------------------------------------------------------------------------

    /** Whether a round is running right now. */
    public boolean searching() {
        return roundStartedAt > 0;
    }

    /** How long the running round has been going, in milliseconds; {@code 0} when none is. */
    public long roundMillis() {
        return roundStartedAt == 0 ? 0 : System.currentTimeMillis() - roundStartedAt;
    }

    /** The last completed round's payout as one line, or empty when none has been seen. */
    public String lastPayout() {
        return lastPayout;
    }

    /**
     * Why nothing is on screen, in one line - because a finder that draws nothing without saying why
     * is the one reported as broken while it is working exactly as told.
     */
    public String status() {
        if (!cfg().enabled) {
            return "switched off - nothing is published";
        }
        if (!SkyBlockLocation.inCritterSafari()) {
            return "not in the Critter Safari (" + SkyBlockLocation.describe() + ")";
        }
        if (cfg().restrictToBiome && !inBiome()) {
            return "in the Safari, but the zone is \"" + SkyBlockLocation.zone()
                    + "\" and the biome filter wants \"" + cfg().biomeWord + "\"";
        }
        if (HideyhoSpots.getInstance().size() == 0) {
            return "no hiding places known yet - find " + CRITTER + " once and it is remembered";
        }
        if (!searching() && !cfg().showAlways) {
            return "waiting for a round to start - " + HideyhoSpots.getInstance().size()
                    + " spot(s) ready";
        }
        return "drawing " + (publishedCount - checked.size()) + " of " + publishedCount
                + " spot(s) still to check";
    }

    // ---- chat -----------------------------------------------------------------------------------

    /**
     * One chat line, raw. Cheap for everything that is not the critter speaking - one regex over a
     * colour-stripped string, which is what every other chat listener in the mod costs.
     */
    public void onChat(String raw) {
        if (!cfg().enabled) {
            return;
        }
        String text = PlainText.strip(raw);
        if (!SPEAKS.matcher(text).find()) {
            return;
        }
        if (searching()) {
            Matcher payout = PAYOUT.matcher(text);
            if (payout.find()) {
                finish(payout.group(1).trim() + " · " + payout.group(2) + " shard(s)");
                return;
            }
            if (FOUND.matcher(text).find()) {
                finish("");
                return;
            }
        }
        if (DECLINED.matcher(text).find()) {
            end("declined", false);
            return;
        }
        if (ROUND_STARTS.matcher(text).find()) {
            begin();
            return;
        }
        log(text);
    }

    /** A round has started: forget last round's crossings-off, the critter hides somewhere new. */
    private void begin() {
        roundStartedAt = System.currentTimeMillis();
        checked.clear();
        publishedSignature = "";
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hideyho] round started in zone '{}' with {} known spot(s)",
                SkyBlockLocation.zone(), HideyhoSpots.getInstance().size());
        if (cfg().announce) {
            int known = HideyhoSpots.getInstance().size();
            SBSChat.send(known == 0
                    ? CRITTER + " is hiding - no spots are known yet, and the one you find is remembered."
                    : CRITTER + " is hiding - walking you round " + known + " known spot(s).");
        }
    }

    /**
     * The round ended with a find: the place the player is standing is a hiding place, and this is
     * the only moment the game ever confirms one.
     *
     * <p>The player's own position rather than the critter's, deliberately - the critter is in front
     * of them by the time this line arrives, and reading its entity would mean a second entity sweep
     * for a coordinate that is already accurate to a couple of blocks. Spots merge within four
     * blocks, so that difference cannot split one hiding place into two.
     */
    private void finish(String payout) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean fresh = false;
        if (player != null) {
            fresh = HideyhoSpots.getInstance().learn(player.blockPosition());
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hideyho] found at {} ({}) - {}",
                    player.blockPosition().toShortString(), payout.isEmpty() ? "no payout line" : payout,
                    fresh ? "a hiding place not seen before" : "a known hiding place");
        }
        lastPayout = payout;
        if (cfg().announce) {
            SBSChat.send(fresh
                    ? "Found - that is a new hiding place, remembered ("
                            + HideyhoSpots.getInstance().size() + " known)."
                    : "Found" + (payout.isEmpty() ? "." : " - " + payout + "."));
        }
        end("found", true);
    }

    /** Drops the round and everything it had out. */
    private void end(String why, boolean quiet) {
        if (roundStartedAt == 0 && publishedCount == 0) {
            return;
        }
        if (!quiet && cfg().announce && roundStartedAt > 0) {
            SBSChat.send("The " + CRITTER + " round ended (" + why + ").");
        }
        roundStartedAt = 0;
        checked.clear();
        publishedSignature = "";
        // Not cleared unconditionally: with "show always" on the spots stay up between rounds, and
        // the next tick republishes them without their crossings-off.
        clearWaypoints();
    }

    // ---- tick -----------------------------------------------------------------------------------

    /**
     * Called every client tick. Returns on two field reads while the module is off or the player is
     * elsewhere, which is the whole "inert unless asked for" requirement.
     */
    public void onClientTick() {
        if (!cfg().enabled) {
            if (publishedCount > 0 || roundStartedAt > 0) {
                end("switched off", true);
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !SkyBlockLocation.inCritterSafari()
                || (cfg().restrictToBiome && !inBiome())) {
            if (publishedCount > 0 || roundStartedAt > 0) {
                end("left the area", true);
            }
            return;
        }
        if (roundStartedAt > 0
                && roundMillis() > Math.max(30, cfg().roundSeconds) * 1000L) {
            // No payout line ever arrived. Hypixel's own timeout is unknown, so this is a ceiling
            // rather than a reading of it - and markers standing over a finished search are worse
            // than none.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hideyho] round dropped after {}s with no payout line",
                    cfg().roundSeconds);
            end("timed out", false);
            return;
        }
        if (!searching() && !cfg().showAlways) {
            if (publishedCount > 0) {
                clearWaypoints();
            }
            return;
        }
        if (++tickCounter >= CHECK_INTERVAL_TICKS) {
            tickCounter = 0;
            crossOffLookedAt(minecraft, player);
        }
        republishIfChanged();
    }

    /** World change or server hop: the Safari is its own instance and a round does not survive it. */
    public void onWorldChange() {
        end("world change", true);
    }

    /** Forces a rebuild - the settings rows call this so the world follows a change at once. */
    public void refresh() {
        publishedSignature = "";
        onClientTick();
    }

    // ---- the walk -------------------------------------------------------------------------------

    /**
     * Marks every spot the player is both near enough to and has a clear line to.
     *
     * <p>Only while a round is running: outside one the markers are a map of the biome, and crossing
     * them off as the player wanders past would leave the next round starting half-retired.
     */
    private void crossOffLookedAt(Minecraft minecraft, LocalPlayer player) {
        if (!searching()) {
            return;
        }
        double radius = Math.max(1, cfg().arriveRadius);
        Vec3 eyes = player.getEyePosition();
        for (HideyhoSpots.Spot spot : HideyhoSpots.getInstance().all()) {
            if (checked.contains(spot.id())) {
                continue;
            }
            Vec3 centre = new Vec3(spot.x() + 0.5, spot.y() + 0.5, spot.z() + 0.5);
            if (eyes.distanceTo(centre) > radius || !hasLineTo(minecraft, eyes, centre)) {
                continue;
            }
            checked.add(spot.id());
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hideyho] checked off {} - {} of {} left",
                    spot.id(), HideyhoSpots.getInstance().size() - checked.size(),
                    HideyhoSpots.getInstance().size());
        }
    }

    /**
     * Whether nothing solid stands between the player's eyes and a spot - the same
     * {@code COLLIDER} ray the waypoint renderer casts for its own occlusion, asked here for a
     * different reason: a spot behind a wall has not been looked at, however close it is.
     */
    private static boolean hasLineTo(Minecraft minecraft, Vec3 from, Vec3 to) {
        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }
        HitResult hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() != HitResult.Type.BLOCK
                || hit.getLocation().distanceToSqr(from) >= to.distanceToSqr(from) - 1.0;
    }

    /** Whether the zone names the biome the player restricted the markers to. */
    private static boolean inBiome() {
        String word = cfg().biomeWord == null ? "" : cfg().biomeWord.trim();
        if (word.isEmpty()) {
            return true;   // an emptied field is not a filter that matches nothing
        }
        String zone = SkyBlockLocation.zone();
        return zone != null && zone.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT));
    }

    // ---- publishing -----------------------------------------------------------------------------

    /**
     * Republishes only when the set would actually differ - the tick runs every tick, and a set swap
     * invalidates the pathfinder's search, so doing it unconditionally is a search that restarts
     * forever.
     */
    private void republishIfChanged() {
        String dimension = WaypointStore.currentDimension();
        if (dimension.isEmpty()) {
            return;   // between worlds; not a reason to drop a set that is about to be correct again
        }
        SBSConfig.HideyhoSettings cfg = cfg();
        String signature = dimension + "|" + HideyhoSpots.getInstance().generation() + "|"
                + checked.size() + "|" + cfg.colorHex + "|" + cfg.showDistance;
        if (signature.equals(publishedSignature)) {
            return;
        }
        publishedSignature = signature;

        List<HideyhoSpots.Spot> spots = HideyhoSpots.getInstance().all();
        List<Waypoint> out = new ArrayList<>(spots.size());
        for (HideyhoSpots.Spot spot : spots) {
            out.add(build(spot, cfg, dimension));
        }
        WaypointStore.setTransient(Waypoint.SOURCE_HIDEYHO, out);
        publishedCount = out.size();
    }

    /** One learned spot as a marker: never through walls, dimmed and unroutable once checked off. */
    private Waypoint build(HideyhoSpots.Spot spot, SBSConfig.HideyhoSettings cfg, String dimension) {
        boolean done = checked.contains(spot.id());
        Waypoint waypoint = new Waypoint(CRITTER, spot.pos(), dimension, Waypoint.SOURCE_HIDEYHO);
        waypoint.colorHex = cfg.colorHex;
        waypoint.showDistance = cfg.showDistance;
        // The one marker set in the mod that refuses the through-walls default: see SOURCE_HIDEYHO.
        waypoint.throughWalls = false;
        // A checked spot keeps its marker so the search is visibly shrinking, but leaves the goal
        // set - which is all "route me to the next one" needs to be.
        waypoint.routable = !done;
        waypoint.opacity = done ? 30 : 100;
        waypoint.subLabel = done ? "checked" : "seen ×" + spot.hits();
        return waypoint;
    }

    private void clearWaypoints() {
        if (publishedCount > 0) {
            WaypointStore.clearTransient(Waypoint.SOURCE_HIDEYHO);
            publishedCount = 0;
        }
        publishedSignature = "";
    }

    /**
     * A critter line no pattern claimed.
     *
     * <p>The line that answers the question this client cannot: every transition here was taken from
     * a wiki, so if a round never starts or never ends, this log says what the critter actually said
     * and the fix is a pattern rather than an investigation.
     */
    private void log(String text) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hideyho] unmatched line (round={}): {}",
                searching(), text);
    }
}
