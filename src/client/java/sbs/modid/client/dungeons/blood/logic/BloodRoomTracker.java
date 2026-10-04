/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.player.RealPlayers;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonAlert;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomLocator;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The blood-room helper: knows when the room is running, tracks every mob in it, says which ones can
 * already be hit and which one to hit next, and calls out the moment the room is worth swinging at.
 * Everything the renderer and the HUD card draw is collected here, once per client tick.
 *
 * <p><b>The room is found two ways, and the second one is why it is reliable.</b> Chat is the precise
 * signal - {@code "The BLOOD DOOR has been opened"} is the exact instant the wait starts - but a
 * helper that <i>only</i> listens to chat draws nothing at all the moment a line is reworded, missed
 * while chat is spammed, or hidden by a chat filter. So the Watcher's own presence is a second,
 * independent start: he stands in that room and nowhere else. The room then also ends on its own (the
 * Watcher's line, the boss phase, or the room going quiet), so a missed closing line cannot leave it
 * stuck open either.
 *
 * <p><b>Starting the room is not the same as being in it.</b> Both signals fire from outside - the
 * door line as you open it, the Watcher's taunt from behind it - so all a start does is set the clock
 * running. What gets drawn is decided separately, and only inside his room (below).
 *
 * <p><b>The two starts are not equal, and the code keeps them apart.</b> The start-killing countdown
 * is measured from the <i>door</i>; the Watcher merely tells you which room is his. A room opened by
 * the Watcher therefore runs without a countdown, and if the door line shows up afterwards it
 * re-stamps the clock and the countdown appears. A wrong countdown would be worse than none - it
 * would have people swinging at invulnerable mobs.
 *
 * <p><b>A blood mob is a mob inside the blood room - the room itself is the filter.</b> Distance is
 * not: a radius around the player reaches through the open blood door into the corridor and boxes
 * whatever is standing there, which is exactly how this helper used to be wrong. The footprint comes
 * from the two sources that cannot reach past the walls. First choice is the room the player is
 * locked into when {@link DungeonRoomTracker} knows it is the blood room - matched against the
 * scanned "Blood Room" archive entry (eight hoppers, nothing else looks like that) or simply painted
 * red on the dungeon map, red being the blood room's own colour. And when the player is not in it
 * yet, the Watcher himself places it: blood rooms are always exactly 1x1 on Hypixel's fixed 32-block
 * room grid, so the grid cell he is standing in <i>is</i> the room, wall to wall, no measuring and
 * no radius anywhere. Nothing else needs to be guessed about his mobs: not whether they carry a ✯,
 * not whether they were summoned while somebody was watching, not what they are called on this floor.
 *
 * <p><b>And it only runs while you are in there.</b> The blood door opening does not put you in the
 * room - the room behind it is still a corridor away - so the clock starts at the door while nothing
 * is drawn until you are inside the Watcher's footprint. The stand-ins that answer "near the blood
 * room" when the question is "in it" stay rejected: a radius round the player, who stands at the
 * door with half of that circle in the corridor behind him, and the player's own room measured while
 * he is still standing in that corridor.
 *
 * <p><b>Being inside the room is necessary, not sufficient.</b> The door the run came through stays
 * open, and ordinary dungeon mobs wander in through it - and their names cannot be used to turn them
 * away, because the Watcher's own summons reuse the floor's mob names. What tells them apart is
 * where they were born: his mobs come into existence inside the footprint, a wanderer was seen
 * outside it first. So every entity first seen outside the room stays ignored even after it walks
 * in, and a blood mob that chases someone into the corridor keeps its box on the way back
 * (already-tracked mobs are exempt from the outsider stamp). The player's own entourage is dropped
 * on top of that: Witherborn heads, power orbs and flares by nametag, and the nameless thrown flare
 * by the fact that its flight starts at the player's feet rather than at the Watcher.
 *
 * <p><b>A mob is tracked from its body, not from its nametag.</b> The body is in the world first and
 * the nametag lands after. Tracking bodies is what lets the helper mark a summon while it is still
 * arriving ({@link BloodMob#ready()}) instead of popping it into existence once it is already
 * swinging at you. The name comes from the stand above it when there is one.
 *
 * <p><b>What ends a summon's wait is the floor, not a clock.</b> A mob that has touched the ground is
 * an ordinary mob with an ordinary hitbox, so that is exactly when it gets an ordinary box - an
 * observation, where every timer answering the same question is a guess. {@link #SUMMON_TRAVEL_MS} is
 * only the backstop for one that was never seen landing, and it is deliberately <b>one</b> number for
 * every mob in the room rather than a measurement per mob.
 *
 * <p><b>A throw is a straight line of fixed length - the drop spot is geometry, not physics.</b>
 * The server carries each summon in a straight angled line and drops it at the end; the line's
 * length is a constant of the wave, so the end is known three ticks in, however hard the room lags.
 * That end gets the mob's own hitbox drawn into the air to pre-aim at. See {@link BloodDrop}.
 */
public final class BloodRoomTracker {

    private static final BloodRoomTracker INSTANCE = new BloodRoomTracker();

    /** How far from the player entities are looked at at all - the room fits comfortably inside. */
    private static final double SCAN_RADIUS = 48.0;

    /** The ✯ Hypixel puts in the nametag of a starred mob - shown by the debug line, nothing else. */
    private static final String STAR = "✯";

    /**
     * How long one of the Watcher's summons is in the air on its way into the room - <b>one number
     * for all of them</b>. It is a backstop, not the signal: what ends the wait is the mob touching
     * the floor (see {@link #landed}), which is the thing that actually happens, and this only
     * decides how long a summon that was never seen landing may stay a violet marker. Deliberately a
     * single constant rather than a per-mob measurement: the mobs land within a hair of each other,
     * so four separate countdowns for the four things in the air would be four ways of saying "about
     * two seconds" - and the measured version of it was what produced numbers like "500ms" on a mob
     * that was already standing on the floor.
     */
    private static final long SUMMON_TRAVEL_MS = 2000L;

    /** A skull hanging around longer than this was never a countdown to anything - stop drawing it. */
    private static final long MAX_MARK_AGE_MS = 5000L;

    /** The room archive entry the blood room was scanned into (eight hoppers, red, 1x1). */
    private static final String BLOOD_ROOM_NAME = "Blood Room";

    /** The blood room's painted colour on the dungeon map - red identifies it without the archive. */
    private static final String BLOOD_MAP_COLOR = "red";

    /**
     * Nametag fragments of the player's own entourage - things that live right next to the player,
     * which is now inside the room, and are never the Watcher's: the Witherborn heads of a wither
     * armor, deployed power orbs (Radiant / Mana Flux / Overflux / Plasmaflux), flares and decoys.
     * Matched case-insensitively on the raw tag so colour codes and timers around the name are free.
     */
    private static final String[] PLAYER_GEAR_NAMES = {"witherborn", "flare", "flux", "radiant", "decoy"};

    /**
     * How close to the local player a skull's flight may start before it counts as thrown <i>by</i>
     * them (a flare) rather than by the Watcher. His skulls set off from him, across the room.
     */
    private static final double GEAR_THROW_RADIUS = 4.0;

    /**
     * Speed (blocks per tick) above which a mob is flying rather than walking. A thrown mob crosses
     * the room in a second or two; a walking one manages about a tenth of this.
     */
    private static final double FLY_SPEED = 0.25;

    /**
     * How far one of the Watcher's throws carries, in blocks, measured from where its direction
     * settles - the community-measured constants of the mechanic. <b>The distance is what is fixed,
     * not the time</b>: a throw is a straight angled line of this length however long the server
     * takes to walk it, which is why the flight times looked random under lag while the drop spots
     * did not. The first wave of summons flies the shorter figure, every later wave the longer one.
     */
    private static final double THROW_RANGE_FIRST = 9.6;
    private static final double THROW_RANGE_LATER = 10.4;

    /** How many summons belong to the first wave (the shorter throw). */
    private static final int FIRST_WAVE_MOBS = 4;

    /**
     * How many per-tick movement samples the throw's direction is read from. The line is straight,
     * so a few are enough - and every extra one is another tick the aiming mark is not on screen.
     */
    private static final int DIRECTION_SAMPLES = 3;

    /** Consecutive slow ticks after which a flying mob has stopped: it is down, the flight is over. */
    private static final int LANDED_TICKS = 2;

    /**
     * Height above the room floor below which fast movement is a mob <b>running</b>, not flying.
     * Speedy summons sprint at throw-like speeds, and without this floor every one of them dragged
     * its drop line around the room for the rest of the fight.
     */
    private static final double WALK_HEADROOM = 1.0;

    /**
     * How close to the Watcher a grounded summon still counts as <b>in his hands</b> rather than
     * landed. The stand sits at his feet for a moment before he throws it, and one tick of onGround
     * there must not mark it down for good - that is how whole flights got drawn as standing mobs.
     */
    private static final double WATCHER_HOLD_RADIUS = 3.0;

    /** Health readout in a mob nametag - what tells a mob tag apart from a decoration stand. */
    private static final String HEALTH_MARKS = "❤♥";

    /** Ticks between Watcher sweeps while no room is running - twice a second is plenty to walk in. */
    private static final int IDLE_SCAN_TICKS = 10;

    /** A running room with no mobs and no Watcher for this long has quietly ended. */
    private static final long IDLE_END_MS = 45_000L;

    /** A Master ranks as if it were half as far away (squared distances, hence a quarter). */
    private static final double MASTER_REACH_BONUS = 0.25;

    /** The start-killing flash: red, because it is a "go" and not an informational line. */
    private static final int ALERT_COLOR = 0xFFFF5555;

    /**
     * One mob in the room.
     *
     * <p>{@code ready} is the only state that matters while fighting: false means the body has landed
     * but the mob cannot be hit yet, and {@code etaMs} is what is left of the measured spawn-in (-1
     * while nothing has been timed). {@code visible} is the line-of-sight answer for the <b>box</b>
     * only - the mob stays in the list either way, because the line to the mob behind the pillar is
     * the one actually worth drawing. It is true without a ray being cast while the filter is off.
     */
    public record BloodMob(LivingEntity entity, String name, Kind kind, boolean visible,
                           boolean ready, long etaMs) {
    }

    /**
     * A mob still in the air, with the point its straight throw line ends at - where it stops and
     * drops, which is where to have the crosshair parked. {@code etaMs} is the time until it gets
     * there at its currently measured speed, re-derived every tick so lag stretches the number
     * instead of breaking it; -1 while the speed cannot be read this instant.
     *
     * <p><b>The throw is a line, not an arc.</b> The server carries these mobs in a straight angled
     * line of fixed length and lets them fall at its end - there is no gravity to simulate and no
     * curve to draw. The end of the line is where the mob will hang and become hittable, so that is
     * the one point marked, with the mob's own hitbox drawn there to pre-aim into.
     */
    public record BloodDrop(LivingEntity entity, Vec3 dropPoint, long etaMs) {
    }

    /**
     * A skull hanging in the room: the Watcher's own marker for where a mob is about to appear, and
     * the earliest warning the room gives. {@code etaMs} is what is left of {@link #SUMMON_TRAVEL_MS}
     * since it appeared.
     */
    public record BloodMark(LivingEntity entity, Vec3 pos, long etaMs) {
    }

    /** What a mob is - each gets its own colour and its own place in the kill order. */
    public enum Kind {
        /** Skeleton Masters and the other "Master" mobs - the ones that actually kill people. */
        MASTER,
        /** Ordinary blood-room mob. */
        NORMAL
    }

    private volatile List<BloodMob> mobs = List.of();
    private volatile List<BloodDrop> drops = List.of();
    private volatile List<BloodMark> marks = List.of();
    private volatile BloodMob target;
    private volatile int readyCount;
    private volatile int spawningCount;

    /** Whether the player is standing in the Watcher's room right now - the gate on everything drawn. */
    private volatile boolean insideRoom;

    /** When each mob body was first seen in the room, by entity id - the start of its spawn-in. */
    private final Map<Integer, Long> firstSeen = new HashMap<>();

    /** Mobs whose nametag has been read at least once: live from then on, whatever a later tick says. */
    private final Set<Integer> tagged = new HashSet<>();

    /** The blood room's footprint this tick - the filter itself, once it is known. */
    private DungeonRoomBorders.Borders roomBorders;

    /**
     * Everything that was ever seen <b>outside</b> the footprint while the room ran: ordinary
     * dungeon mobs, whatever they are called - the Watcher's are born inside, so being seen outside
     * is proof enough. Walking in through the open door later does not get them boxed.
     */
    private final Set<Integer> outsiders = new HashSet<>();

    /**
     * Everything proven to belong to the player or to the Watcher himself rather than to the fight:
     * Witherborn, orbs, flares, decoys, and whatever the Watcher's own nametag is sitting on. Sticky
     * on purpose - a nametag that flickers for one tick must not put a box back on your own gear.
     */
    private final Set<Integer> playerGear = new HashSet<>();

    /**
     * Summons that have touched the floor. The moment one does it is an ordinary mob with an ordinary
     * hitbox, so it gets an ordinary box and loses its arc - "it is on the ground" is a fact, where
     * every timer for the same question is a guess.
     */
    private final Set<Integer> landed = new HashSet<>();

    /** Last tick's position per entity - what the throw's movement samples are measured from. */
    private final Map<Integer, Vec3> lastPos = new HashMap<>();

    /**
     * One throw in progress. The direction is accumulated over the first {@link #DIRECTION_SAMPLES}
     * moving ticks and then fixed as a drop point ({@code position + direction * range}); the speed
     * is refreshed on every moving tick so the countdown tracks the server's actual pace, and
     * {@code slowTicks} counts the still ticks that end the flight. Mutable on purpose - a throw is
     * a measurement being taken, not a fact being stored.
     */
    private static final class Throw {
        final boolean firstWave;
        Vec3 direction = Vec3.ZERO;
        int samples;
        Vec3 dropPoint;
        double speed;
        int slowTicks;

        Throw(boolean firstWave) {
            this.firstWave = firstWave;
        }
    }

    /**
     * The throw each flying entity is on, kept until it lands. Held rather than re-derived: the
     * server does not move the stand on every client tick, and a mark rebuilt from scratch each tick
     * spent half the flight not being drawn - which from inside the room is indistinguishable from
     * it never being drawn at all.
     */
    private final Map<Integer, Throw> flights = new HashMap<>();

    /** Summons seen flying this room - what decides whether a throw belongs to the first wave. */
    private int throwsSeen;

    /**
     * Everything that has been seen crossing the room through the air at least once. For the skulls
     * this is the whole identification: the Watcher <i>throws</i> his, and a skull that has never
     * moved is part of the room rather than part of the fight.
     */
    private final Set<Integer> flown = new HashSet<>();

    /** When each spawn skull was first seen - the start of its {@link #SUMMON_TRAVEL_MS} countdown. */
    private final Map<Integer, Long> markSeen = new HashMap<>();

    /** The last name read for each mob, so a mob keeps its label through a missed stand lookup. */
    private final Map<Integer, String> names = new HashMap<>();

    private long startedAt;
    private long clearedAt;
    private boolean killAnnounced;

    /** The room was timed from the door line - without it there is no honest countdown to show. */
    private boolean doorTimed;

    /** Last time the room showed any sign of life (a mob or the Watcher), for the quiet-end rule. */
    private long lastAliveAt;

    /** Where the Watcher was last seen - the anchor the room's mobs are collected around. */
    private Vec3 watcherPos;

    private int idleTicks;

    private BloodRoomTracker() {
        // Opening signal: the door that lets you into the room. This is where the start-killing wait
        // begins - the room is live from the moment it can be walked into, not from the first mob -
        // so this line, and only this line, stamps the clock.
        ChatPatternRegistry.getInstance().register(
                "(?i)The BLOOD DOOR has been opened",
                matcher -> onDoorOpened(), "blood room: door opened");
        // Second opening signal: the Watcher speaking at all. Deliberately broad, and it starts the
        // room without claiming to have timed it.
        ChatPatternRegistry.getInstance().register(
                "\\[BOSS\\]\\s+The Watcher\\s*:",
                matcher -> onRoomSeen(), "blood room: watcher");
        // Closing signal: the Watcher standing down. The blood door is NOT one - it is what opened
        // the room in the first place.
        ChatPatternRegistry.getInstance().register(
                "(?i)That will be enough for now",
                matcher -> onCleared(), "blood room: cleared");
    }

    public static BloodRoomTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.BloodSettings cfg() {
        return ConfigManager.getInstance().get().blood;
    }

    // ---- read by the renderers / HUD ----------------------------------------------------------

    /** Every mob in the room this tick, live and still spawning (empty while no room is running). */
    public List<BloodMob> mobs() {
        return mobs;
    }

    /** The mobs currently in the air, with where each one is going to come down. */
    public List<BloodDrop> drops() {
        return drops;
    }

    /** The Watcher's spawn skulls: where the next mobs are going to appear. */
    public List<BloodMark> marks() {
        return marks;
    }

    /** Whether you are standing in the Watcher's room - nothing is boxed, lined or counted outside it. */
    public boolean insideRoom() {
        return insideRoom;
    }

    /**
     * The mob to swing at next: the closest one that can already be hit, Masters first because they
     * are what kills people. Null while nothing is live.
     */
    public BloodMob target() {
        return target;
    }

    /** How many mobs can be hit right now. */
    public int readyCount() {
        return readyCount;
    }

    /** How many summons are still in the air or otherwise not hittable yet. */
    public int spawningCount() {
        return spawningCount;
    }

    /** Whether the blood room is running right now. */
    public boolean running() {
        return startedAt != 0 && clearedAt == 0;
    }

    /** Whether there is anything to show at all (running, or a finished clear still on screen). */
    public boolean active() {
        return startedAt != 0;
    }

    /** Milliseconds since the room started - frozen at the clear time once it is done. */
    public long elapsedMs() {
        if (startedAt == 0) {
            return 0;
        }
        return (clearedAt == 0 ? System.currentTimeMillis() : clearedAt) - startedAt;
    }

    public boolean cleared() {
        return clearedAt != 0;
    }

    /** Whether the clock was stamped by the door line - only then is the countdown meaningful. */
    public boolean doorTimed() {
        return doorTimed;
    }

    /**
     * Seconds left before the mobs are worth swinging at, or -1 when there is no honest answer (the
     * wait is over, the room is not running, or it was never timed from the door).
     */
    public int startKillingEtaSeconds() {
        if (!running() || !doorTimed) {
            return -1;
        }
        long left = cfg().startKillingSeconds * 1000L - elapsedMs();
        return left <= 0 ? -1 : (int) Math.ceil(left / 1000.0);
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Called every client tick: finds the room, then refreshes what is in it. */
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        DungeonStateManager state = DungeonStateManager.getInstance();
        if (!state.inDungeon()) {
            reset();
            return;
        }
        // The run reached the boss: whatever the chat did or did not say, the blood room is behind
        // us. Ending it here is what keeps this helper to the one room it is about.
        if (running() && state.phase() == DungeonEvents.Phase.BOSS) {
            onCleared();
        }
        if (!cfg().enabled || level == null || player == null) {
            clearFrame();
            return;
        }
        if (!running()) {
            // No room yet: sweep for the Watcher a couple of times a second. He stands in the blood
            // room and nowhere else, so finding him is finding the room - no chat line required.
            if (!cleared() && ++idleTicks >= IDLE_SCAN_TICKS) {
                idleTicks = 0;
                Vec3 found = watcherNearby(level, player);
                if (found != null) {
                    onRoomSeen();
                    watcherPos = found; // set after the start, which clears the previous room's anchor
                } else if (trackerBloodBorders() != null) {
                    // Third start signal: the room tracker identified the room the player walked
                    // into as the blood room (archive match / red map tile). Catches a missed door
                    // line even before the Watcher is in sight.
                    onRoomSeen();
                }
            }
            clearFrame();
            return;
        }
        announceStartKilling(player);
        collect(level, player);
    }

    private void reset() {
        clearFrame();
        firstSeen.clear();
        tagged.clear();
        names.clear();
        landed.clear();
        lastPos.clear();
        flights.clear();
        throwsSeen = 0;
        flown.clear();
        outsiders.clear();
        playerGear.clear();
        markSeen.clear();
        roomBorders = null;
        startedAt = 0;
        clearedAt = 0;
        killAnnounced = false;
        doorTimed = false;
        lastAliveAt = 0;
        idleTicks = 0;
        watcherPos = null;
    }

    /** Drops this frame's view of the room without touching the room's own state. */
    private void clearFrame() {
        mobs = List.of();
        drops = List.of();
        marks = List.of();
        target = null;
        readyCount = 0;
        spawningCount = 0;
        insideRoom = false;
    }

    /** Fires the "go" the moment the wait is over, exactly once per room. */
    private void announceStartKilling(LocalPlayer player) {
        if (killAnnounced || !doorTimed || elapsedMs() < cfg().startKillingSeconds * 1000L) {
            return;
        }
        killAnnounced = true;
        if (!cfg().startKillingAlert) {
            return;
        }
        DungeonAlert.getInstance().trigger("START KILLING", ALERT_COLOR, true, 1.4f);
        player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r §cStart killing §7- blood mobs are up"));
    }

    /**
     * One pass over the entities near the player: the nametag stands hand out the names, the bodies
     * are the mobs. Both come out of the same loop because the association runs stand→body, and a
     * second loop would only be a second chance to disagree with the first.
     */
    private void collect(ClientLevel level, LocalPlayer player) {
        long now = System.currentTimeMillis();
        Map<Integer, String> tags = new HashMap<>();
        List<LivingEntity> bodies = new ArrayList<>();
        List<LivingEntity> standingMarks = new ArrayList<>();
        boolean watcher = false;
        // Built from last tick's anchor, because the Watcher's own position is only known once this
        // loop has walked past him. One tick of lag on a room that does not move is not worth a
        // second pass.
        roomBorders = bloodBorders();
        boolean known = roomBorders != null;

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)
                    || living.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
                continue;
            }
            // The Watcher is the room's host, not one of its mobs: he keeps the room open, he anchors
            // it, and he never gets boxed or pointed at. Checked before players are dropped, because
            // he may well be one - and looked for every tick, even before the room is known, since
            // finding him is what makes it known. Only real players are dropped below: a
            // player-model mob is a fake player and is one of the room's mobs like any other.
            if (isWatcher(living)) {
                watcher = true;
                watcherPos = living.position();
                // His nametag is a stand of its own, and the thing it is floating over is him -
                // which without this is a nameless body standing in the middle of the room, i.e.
                // exactly what a freshly summoned mob looks like. Blacklisting it is what stops the
                // helper telling you to go and swing at the Watcher.
                blacklistBelow(level, living);
                continue;
            }
            if (!known || RealPlayers.isRealPlayerEntity(living)) {
                continue; // the room is not identified yet: nothing else in the dungeon counts
            }
            Component custom = living.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            String name = raw.isEmpty() ? null : MobHighlightTracker.mobNameInNametag(raw);
            // Your own entourage, and it is judged on the nametag STAND as well as on the body: a
            // Witherborn's name floats above it like any mob's, so testing only the body's own name
            // asked the one entity that never carries one. Whatever the tag names is blacklisted
            // together with the thing underneath it, and it stays blacklisted.
            if (isPlayerGear(raw) || isGearEntity(living)) {
                playerGear.add(living.getId());
                blacklistBelow(level, living);
                continue;
            }
            if (playerGear.contains(living.getId())) {
                continue; // proven yours on an earlier tick - a flickering nametag does not undo that
            }
            if (!inBloodRoom(living)) {
                // Another room's mob, seen from inside this one - and remembered: anything that was
                // ever outside the footprint is not the Watcher's, whatever it is called, and
                // walking in through the open door later must not get it boxed. Mobs already being
                // tracked inside are exempt, so a blood mob that chases somebody into the corridor
                // keeps its box on the way back.
                if (!firstSeen.containsKey(living.getId())) {
                    outsiders.add(living.getId());
                }
                continue;
            }
            if (outsiders.contains(living.getId())) {
                continue; // walked in from outside: the corridor's mob, not the Watcher's
            }
            if (living instanceof ArmorStand stand) {
                // A skull with no health on it is not a mob at all - it is either the Watcher's
                // marker for where one is about to appear, or a piece of the room's furniture. Both
                // go to trackMarks, which tells them apart by whether the thing was ever in the air;
                // wearing a head only makes it a candidate. (His mobs are skulls too, but they carry
                // a nametag with health, so they never come through here.)
                if (name == null || !isMobTag(raw)) {
                    if (wearsSkull(stand)) {
                        standingMarks.add(stand);
                    }
                    continue; // otherwise a decoration stand, not a mob's nametag
                }
                LivingEntity below = MobHighlightTracker.mobBelow(level, stand);
                if (below == null) {
                    below = mobFarBelow(level, stand);
                }
                if (below != null && below.isAlive()) {
                    tags.put(below.getId(), name);
                    continue;
                }
                // A mob tag with no body under it: the stand IS the mob. The Watcher's are built
                // that way - a floating head wearing the mob's skin, thrown into the room - and
                // treating every stand as somebody's nametag is exactly why they were the one thing
                // in the room that never got boxed.
                tags.put(stand.getId(), name);
                bodies.add(stand);
                continue;
            }
            if (!living.isAlive()) {
                continue;
            }
            if (name != null && isMobTag(raw)) {
                tags.put(living.getId(), name); // a mob carrying its own nametag
            }
            bodies.add(living);
        }

        // The blacklist is applied once more at the end, because the sweep visits entities in no
        // particular order: a Witherborn's body is just as likely to come up before the nametag that
        // condemns it as after, and the tick where it slipped through is the tick you see a box on
        // your own gear.
        bodies.removeIf(body -> playerGear.contains(body.getId()));
        standingMarks.removeIf(mark -> playerGear.contains(mark.getId()));

        // The helper belongs to the blood room and nowhere else. The blood door opening does not put
        // you in it - the room behind that door is still a corridor away, and its mobs are ordinary
        // dungeon mobs. So nothing is collected until you are standing in the Watcher's room, which
        // is also exactly what "show me the blood mobs" means.
        insideRoom = known && inBloodRoom(player.blockPosition());
        if (!insideRoom) {
            clearFrame();
            if (watcher) {
                lastAliveAt = now;
            }
            return;
        }

        List<BloodMob> found = new ArrayList<>(bodies.size());
        List<BloodDrop> falling = new ArrayList<>();
        boolean lineOfSightOnly = cfg().lineOfSightOnly;
        double floorY = roomBorders != null ? roomBorders.min().getY() : player.getY();
        int live = 0;
        int pending = 0;

        for (LivingEntity body : bodies) {
            int id = body.getId();
            long first = firstSeen.computeIfAbsent(id, key -> now);
            long waited = now - first;
            String tag = tags.get(id);
            // Being in the room is the whole qualification: it is the Watcher's room, and he is the
            // only one who puts anything in it.
            if (tag != null) {
                names.put(id, tag);
                tagged.add(id);
            }
            // Where it is going, if it is going anywhere: the Watcher throws his mobs in, and a mob
            // in the air is a mob that cannot be hit yet no matter what its nametag says. Once it has
            // touched down the line is history - it is a mob standing in a room, not a projectile.
            BloodDrop drop = flightOf(body, id, floorY);
            if (drop != null) {
                falling.add(drop);
            }
            // A stand at the Watcher's feet is a summon he has not thrown yet, whatever its onGround
            // flag says - marking it landed there is how whole flights got drawn as standing mobs.
            boolean held = !landed.contains(id) && watcherPos != null
                    && body.position().distanceToSqr(watcherPos)
                            <= WATCHER_HOLD_RADIUS * WATCHER_HOLD_RADIUS;
            // On the floor = hittable, full stop. That is an observation rather than a prediction,
            // which is why it outranks the travel time: the countdown only decides how long a summon
            // that was never seen landing stays violet, and it is one number for all of them.
            boolean ready = landed.contains(id)
                    || (drop == null && !held && (body.onGround() || waited >= SUMMON_TRAVEL_MS));
            if (ready) {
                landed.add(id);
            }
            boolean visible = !lineOfSightOnly || player.hasLineOfSight(body);
            String name = names.getOrDefault(id, "");
            // In the air: the time until its line ends, which is when to shoot it. On the ground but
            // not live yet: what is left of the travel time. In the Watcher's hands: no number at
            // all, because his throw has not started anything yet.
            long eta = ready ? 0
                    : drop != null ? drop.etaMs()
                    : held ? -1
                    : Math.max(0, SUMMON_TRAVEL_MS - waited);
            found.add(new BloodMob(body, name, kindOf(name), visible, ready, eta));
            if (ready) {
                live++;
            } else {
                pending++;
            }
        }

        // Live first, then by reach: the head of the list is the next target. A Master counts as
        // half as far away as it is, so it wins over an equally placed filler mob without ever
        // sending you across the room past something already swinging at you - "most dangerous" and
        // "actually reachable" are both part of the answer, and neither one alone is.
        found.sort(Comparator.comparing((BloodMob mob) -> !mob.ready())
                .thenComparingDouble(mob -> mob.entity().distanceToSqr(player)
                        * (mob.kind() == Kind.MASTER ? MASTER_REACH_BONUS : 1.0)));

        marks = trackMarks(standingMarks, falling, now, floorY);
        mobs = List.copyOf(found);
        drops = List.copyOf(falling);
        readyCount = live;
        spawningCount = pending;
        target = found.isEmpty() || !found.get(0).ready() ? null : found.get(0);
        if (!found.isEmpty() || watcher) {
            lastAliveAt = now;
        } else if (lastAliveAt != 0 && now - lastAliveAt > IDLE_END_MS) {
            // Nothing here for a long time and no closing line ever came: the room is over. Ended
            // silently - a clear time nobody watched happen is not worth printing.
            reset();
        }
    }

    /**
     * A second, deeper look for the body under a nametag: the shared lookup reaches 4 blocks down,
     * which is right for a zombie and short for the room's big ones - their tag floats well clear of
     * the model. Only used when the near lookup found nothing, so the normal case keeps its tight
     * box and only the tall mobs pay for the wider search.
     */
    private static LivingEntity mobFarBelow(ClientLevel level, ArmorStand stand) {
        List<LivingEntity> below = level.getEntitiesOfClass(LivingEntity.class,
                stand.getBoundingBox().inflate(2.0, 0, 2.0).expandTowards(0, -8, 0),
                mob -> mob != stand && !(mob instanceof ArmorStand) && !RealPlayers.isRealPlayerEntity(mob)
                        && mob.isAlive());
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : below) {
            double distance = mob.distanceToSqr(stand);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    /**
     * The spawn skulls this tick, with what is left of each one's wait - and an arc for any that are
     * still flying to their spot, since a marker in the air is going to spawn its mob wherever it
     * comes down rather than where it is now.
     *
     * <p>The wait is the same {@link #SUMMON_TRAVEL_MS} everything else in this room counts down, and
     * a skull that outlives {@link #MAX_MARK_AGE_MS} is dropped rather than left sitting at zero: it
     * was never a countdown to anything.
     *
     * <p><b>Only the ones that were thrown.</b> A head on a stand is not rare in a dungeon room - the
     * blood room is furnished with them - so "wears a skull" boxed the decoration along with the
     * markers and buried the two or three that mattered in a roomful that never meant anything. What
     * tells them apart is not what they look like but what they did: the Watcher's arrive through the
     * air, and the furniture has never moved in its life. So a skull is only a marker once it has
     * been seen flying, and it stays one after it lands - the countdown on the spot where it came to
     * rest is the entire point of drawing it.
     */
    private List<BloodMark> trackMarks(List<LivingEntity> standing, List<BloodDrop> falling,
                                       long now, double floorY) {
        List<BloodMark> out = new ArrayList<>(standing.size());
        Set<Integer> present = new HashSet<>();
        for (LivingEntity mark : standing) {
            int id = mark.getId();
            // Run for every skull, drawn or not: this is the measurement that decides which ones are
            // the Watcher's, so skipping it for the unproven ones would mean never proving any.
            BloodDrop drop = flightOf(mark, id, floorY);
            if (!flown.contains(id)) {
                continue; // has never moved: part of the room, not part of the fight
            }
            if (playerGear.contains(id)) {
                continue; // a thrown flare that landed here, not one of the Watcher's markers
            }
            // Judged the moment it is first seen flying, while it is still next to whoever threw
            // it: the Watcher's skulls set off from him, a flare from the player's own hand.
            if (!markSeen.containsKey(id) && thrownByLocalPlayer(mark)) {
                playerGear.add(id);
                continue;
            }
            present.add(id);
            long first = markSeen.computeIfAbsent(id, key -> now);
            long age = now - first;
            if (age > MAX_MARK_AGE_MS) {
                continue; // long past any summon it could have been announcing
            }
            if (drop != null) {
                falling.add(drop);
            }
            long eta = drop != null ? drop.etaMs() : Math.max(0, SUMMON_TRAVEL_MS - age);
            out.add(new BloodMark(mark, mark.position(), eta));
        }
        markSeen.keySet().retainAll(present);
        return List.copyOf(out);
    }

    /**
     * Whether an armor stand is wearing a head that could be one of the Watcher's markers - any skull
     * <b>except</b> a wither's.
     *
     * <p>Written as an exclusion rather than "must be a player head" on purpose: which skull Hypixel
     * builds its markers out of is its business and may differ per floor, so demanding a particular
     * one risks a room where nothing is ever marked. Ruling out the wither skull is the part that is
     * certain - that is a Witherborn, your own armor's, and it is what used to get an orange
     * countdown box in the middle of the fight.
     */
    private static boolean wearsSkull(ArmorStand stand) {
        return skullType(stand) != null && !wearsWitherSkull(stand);
    }

    /**
     * Whether this entity is a piece of the player's kit by its own nature rather than by its name.
     * The wither a Necron's set spawns is the case that matters: it wears a wither skull, it follows
     * you into the room, and the nametag that would have given it away is not on the body - so the
     * name test everything else uses had nothing to look at.
     */
    private static boolean isGearEntity(LivingEntity living) {
        if (living instanceof WitherBoss) {
            return true;
        }
        return living instanceof ArmorStand stand && wearsWitherSkull(stand);
    }

    /** A wither (or plain skeleton) skull on a stand - a Witherborn, never one of the Watcher's. */
    private static boolean wearsWitherSkull(ArmorStand stand) {
        SkullBlock.Type type = skullType(stand);
        return type == SkullBlock.Types.WITHER_SKELETON || type == SkullBlock.Types.SKELETON;
    }

    /** The kind of skull an armor stand is wearing on its head, or {@code null} when it is not one. */
    private static SkullBlock.Type skullType(ArmorStand stand) {
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && head.getItem() instanceof BlockItem block
                && block.getBlock() instanceof SkullBlock skull ? skull.getType() : null;
    }

    /**
     * Blacklists whatever a nametag stand is floating over, along with the stand itself. A name in
     * this room belongs to the thing underneath it, so the two are one entity as far as the helper
     * is concerned - and a body whose name says "not a blood mob" must not be boxed just because the
     * name happens to live on a separate entity.
     */
    private void blacklistBelow(ClientLevel level, LivingEntity tag) {
        playerGear.add(tag.getId());
        if (!(tag instanceof ArmorStand stand)) {
            return;
        }
        LivingEntity below = MobHighlightTracker.mobBelow(level, stand);
        if (below == null) {
            below = mobFarBelow(level, stand);
        }
        if (below != null) {
            playerGear.add(below.getId());
        }
    }

    /**
     * The throw a mob is on, or null when it is not on one. Everything is measured off two
     * consecutive positions rather than taken from the entity's own motion: these mobs are moved by
     * the server, so the client's copy of their velocity is whatever the last packet left behind,
     * while where they <i>were</i> is never wrong.
     *
     * <p><b>The model is a straight line of fixed length, because that is what the server does.</b>
     * A summon is carried in a straight angled line - no gravity, no drag - and the line is always
     * the same length for its wave ({@link #THROW_RANGE_FIRST} / {@link #THROW_RANGE_LATER}); at its
     * end the mob stops and drops. So the drop point is simply {@code position + direction * range},
     * with the direction read off the first {@link #DIRECTION_SAMPLES} moving ticks. The gravity
     * simulation this replaces predicted a curve the mob was never on, which is why its numbers
     * drifted with every lag spike: it was solving the wrong equation, correctly.
     *
     * <p><b>A flight ends on observation, twice over, and ending is forever.</b> Stopping
     * ({@link #LANDED_TICKS} still ticks) or reaching the floor ends the throw and puts the mob in
     * {@link #landed}, and a landed mob can never fly again - Speedy summons run at throw-like
     * speeds, and re-detecting them was how grounded mobs dragged drop lines around the room. The
     * knockbacks of the fight cannot re-arm it either, for the same reason: anything being knocked
     * around was hittable, so it has landed. {@link #WALK_HEADROOM} keeps the fast runners out on
     * the way in, and a stand sitting at the Watcher's feet is his to throw, not down
     * ({@link #WATCHER_HOLD_RADIUS}).
     */
    private BloodDrop flightOf(LivingEntity body, int id, double floorY) {
        Vec3 pos = body.position();
        Vec3 previous = lastPos.put(id, pos);
        if (landed.contains(id) || previous == null) {
            return null; // down for good, or a first sighting with nothing to measure from yet
        }
        Vec3 delta = pos.subtract(previous);
        double speed = delta.length();
        boolean moving = speed >= FLY_SPEED;
        Throw flight = flights.get(id);
        if (flight == null) {
            if (!moving || pos.y <= floorY + WALK_HEADROOM) {
                return null; // walking, standing, or running fast along the floor - not thrown
            }
            flight = new Throw(++throwsSeen <= FIRST_WAVE_MOBS);
            flights.put(id, flight);
            // Remembered rather than re-asked every tick: this is what marks a skull as one of the
            // Watcher's, and the answer must survive the moment it lands and stops moving.
            flown.add(id);
        }
        boolean held = watcherPos != null
                && pos.distanceToSqr(watcherPos) <= WATCHER_HOLD_RADIUS * WATCHER_HOLD_RADIUS;
        if (moving) {
            flight.slowTicks = 0;
            flight.speed = speed;
            if (flight.dropPoint == null && ++flight.samples <= DIRECTION_SAMPLES) {
                flight.direction = flight.direction.add(delta);
                if (flight.samples == DIRECTION_SAMPLES) {
                    double range = flight.firstWave ? THROW_RANGE_FIRST : THROW_RANGE_LATER;
                    Vec3 end = pos.add(flight.direction.normalize().scale(range));
                    // The line may aim below the room - the mob still ends up standing on the floor.
                    flight.dropPoint = new Vec3(end.x, Math.max(end.y, floorY), end.z);
                }
            }
        } else if (++flight.slowTicks >= LANDED_TICKS) {
            land(id);
            return null; // stopped: it is down (or the mob has taken over the spot)
        }
        // Touching down mid-line beats the line: the floor is an observation, the range a constant.
        if (!held && (body.onGround() || pos.y <= floorY + 0.2)) {
            land(id);
            return null;
        }
        if (flight.dropPoint == null) {
            return null; // direction not settled yet - one or two ticks at most
        }
        long eta = flight.speed < FLY_SPEED ? -1
                : (long) (pos.distanceTo(flight.dropPoint) / flight.speed * 50.0);
        return new BloodDrop(body, flight.dropPoint, eta);
    }

    /** Ends an entity's flight for good: it is on the ground, and the ground is where it stays. */
    private void land(int id) {
        flights.remove(id);
        landed.add(id);
    }

    /** Inside the blood room, for one of its entities. */
    private boolean inBloodRoom(LivingEntity entity) {
        return inBloodRoom(entity.blockPosition());
    }

    /**
     * The footprint is the whole test: it answers "in the room" rather than "near it", and near is
     * what once put boxes on a corridor full of Crypt Lurkers while the blood room was still a door
     * away. No footprint, nothing inside - and with the Watcher's grid cell as the second source,
     * "no footprint" only means neither he nor the identified room has been seen at all yet.
     */
    private boolean inBloodRoom(BlockPos block) {
        return roomBorders != null && roomBorders.contains(block);
    }

    /**
     * The blood room's footprint right now, cheap enough to re-derive every tick. The room tracker's
     * verdict wins - the room the player is locked into, when the archive matched it as "Blood Room"
     * or the map painted it red - because it is grid-exact and works even before the Watcher has been
     * sighted. Otherwise the room is placed from the Watcher himself: blood rooms are always exactly
     * 1x1 on the fixed 32-block grid, so the cell he stands in is the room, wall to wall. He does not
     * leave it, so neither can the footprint.
     */
    private DungeonRoomBorders.Borders bloodBorders() {
        DungeonRoomBorders.Borders identified = trackerBloodBorders();
        if (identified != null) {
            return identified;
        }
        if (watcherPos == null) {
            return null; // no Watcher and no identified room - there is nothing to be inside of
        }
        BlockPos watcher = BlockPos.containing(watcherPos);
        int cornerX = DungeonRoomLocator.cornerCoord(watcher.getX());
        int cornerZ = DungeonRoomLocator.cornerCoord(watcher.getZ());
        BlockPos min = new BlockPos(cornerX, watcher.getY(), cornerZ);
        BlockPos max = new BlockPos(cornerX + DungeonRoomLocator.ROOM_SPAN, watcher.getY(),
                cornerZ + DungeonRoomLocator.ROOM_SPAN);
        return new DungeonRoomBorders.Borders(
                List.of(new DungeonRoomBorders.Rect(min.getX(), min.getZ(), max.getX(), max.getZ())),
                min, max, "1x1");
    }

    /**
     * The locked room's footprint when {@link DungeonRoomTracker} knows it is the blood room -
     * matched against the archive's {@value #BLOOD_ROOM_NAME} entry, or simply painted red on the
     * dungeon map. {@code null} whenever the player's current room is anything else (or none), so
     * this can never hand back the corridor.
     */
    private static DungeonRoomBorders.Borders trackerBloodBorders() {
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        DungeonRoomBorders.Borders locked = tracker.borders();
        if (locked == null) {
            return null;
        }
        boolean blood = BLOOD_ROOM_NAME.equals(tracker.activeRoomName())
                || BLOOD_MAP_COLOR.equals(tracker.mapColor());
        return blood ? locked : null;
    }

    /** Whether a raw nametag names the player's own entourage rather than a mob. */
    private static boolean isPlayerGear(String raw) {
        if (raw.isEmpty()) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String gear : PLAYER_GEAR_NAMES) {
            if (lower.contains(gear)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this skull's flight is starting at the local player - a thrown flare, not one of the
     * Watcher's markers. Deliberately the <b>local</b> player only: Hypixel's NPCs are fake players,
     * so testing "near any player" would put the Watcher's own throws on the gear list.
     */
    private static boolean thrownByLocalPlayer(LivingEntity mark) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && mark.distanceToSqr(player) <= GEAR_THROW_RADIUS * GEAR_THROW_RADIUS;
    }

    /** Where the Watcher is standing, or null when he is not in range - the room-finding sweep. */
    private static Vec3 watcherNearby(ClientLevel level, LocalPlayer player) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)
                    || living.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
                continue;
            }
            if (isWatcher(living)) {
                return living.position();
            }
        }
        return null;
    }

    /**
     * Whether this entity is the Watcher himself - checked on the nametag <b>and</b> on the plain
     * display name, players included. Hypixel's NPCs are routinely fake players, so skipping
     * everything player-shaped is exactly how the host of the room would go unnoticed, and with him
     * the room. "The Watcher" contains a space, which an account name cannot, so a real player can
     * never be mistaken for him.
     */
    private static boolean isWatcher(LivingEntity living) {
        Component custom = living.getCustomName();
        if (custom != null) {
            String name = MobHighlightTracker.mobNameInNametag(custom.getString());
            if (name != null && name.toLowerCase(Locale.ROOT).contains("watcher")) {
                return true;
            }
        }
        return living.getName().getString().toLowerCase(Locale.ROOT).contains("the watcher");
    }

    /** {@code 320ms} once the spawn-in has been measured, {@code spawning} until it has. */
    public static String spawnLabel(BloodMob mob) {
        return mob.etaMs() < 0 ? "spawning" : mob.etaMs() + "ms";
    }

    /** A mob nametag rather than a decoration stand: it carries a health readout. */
    private static boolean isMobTag(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (HEALTH_MARKS.indexOf(raw.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static Kind kindOf(String name) {
        return name.toLowerCase(Locale.ROOT).contains("master") ? Kind.MASTER : Kind.NORMAL;
    }

    // ---- room state ---------------------------------------------------------------------------

    /**
     * The blood door went: this is the one signal that <b>times</b> the room. It re-stamps a room the
     * Watcher already opened, so walking in early costs the countdown nothing.
     */
    private void onDoorOpened() {
        if (!cfg().enabled || !DungeonStateManager.getInstance().inDungeon() || cleared()) {
            return;
        }
        if (!running()) {
            startRoom();
        }
        startedAt = System.currentTimeMillis();
        doorTimed = true;
        killAnnounced = false;
    }

    /**
     * The room was recognised without the door line - the Watcher spoke, or he is standing in front
     * of you. Everything runs except the countdown, which has nothing honest to count from.
     */
    private void onRoomSeen() {
        if (!cfg().enabled || !DungeonStateManager.getInstance().inDungeon() || running() || cleared()) {
            return;
        }
        startRoom();
    }

    private void startRoom() {
        startedAt = System.currentTimeMillis();
        clearedAt = 0;
        killAnnounced = false;
        doorTimed = false;
        lastAliveAt = System.currentTimeMillis();
        watcherPos = null;
        firstSeen.clear();
        tagged.clear();
        names.clear();
        landed.clear();
        lastPos.clear();
        flights.clear();
        throwsSeen = 0;
        flown.clear();
        outsiders.clear();
        playerGear.clear();
        markSeen.clear();
        roomBorders = null;
    }

    /** The room is done: freeze the clock and report the clear time once. */
    private void onCleared() {
        if (!running()) {
            return;
        }
        clearedAt = System.currentTimeMillis();
        clearFrame();
        DungeonAlert.getInstance().clear();
        var player = Minecraft.getInstance().player;
        if (cfg().chatOnClear && player != null && doorTimed) {
            player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r §cBlood cleared §7in §f"
                    + seconds(elapsedMs())));
        }
    }

    /**
     * A one-line answer to "why is it drawing that / why is it drawing nothing", printed by
     * {@code /sbsdev blood}. The identification rules above are invisible in-game: without a way to
     * read them back, every wrong box costs a full run of guessing what the room actually looked
     * like. Lists the nearby nametags too, because the star and the Watcher's own name are exactly
     * the things a floor could spell differently.
     */
    public List<String> debugLines() {
        Minecraft minecraft = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        out.add("§bBlood§7: " + (running() ? "§arunning" : cleared() ? "§7cleared" : "§cno room")
                + " §7| door-timed §f" + doorTimed()
                // With his distance: the fallback radius is measured from him, so "found" alone does
                // not say whether it reaches you or the mobs.
                + " §7| watcher §f" + (watcherPos == null ? "§cnot found"
                        : minecraft.player == null ? "found"
                        : "found §7at §f" + (int) Math.sqrt(watcherPos.distanceToSqr(
                                minecraft.player.position())) + "m")
                + " §7| you're in §f" + insideRoom()
                + " §7| dungeon §f" + DungeonStateManager.getInstance().inDungeon());
        out.add("§7mobs §f" + readyCount() + " live §7/ §f" + spawningCount() + " spawning §7/ §f"
                + drops().size() + " in flight §7/ §f" + marks().size() + " skulls"
                + " §7| tracked §f" + tagged.size()
                + " §7| landed §f" + landed.size()
                + " §7| gear/watcher §f" + playerGear.size()
                + " §7| outsiders §f" + outsiders.size());
        // The throw model, verifiable in the room: the wave decides the range, so if the boxes sit
        // consistently short or long of where mobs actually stop, these are the numbers to tune.
        out.add("§7throws §f" + throwsSeen + " §7seen (wave range §f"
                + (throwsSeen <= FIRST_WAVE_MOBS ? THROW_RANGE_FIRST : THROW_RANGE_LATER)
                + "§7 blocks), §f" + flights.size() + " §7in the air"
                + " §7| grounded backstop §f" + SUMMON_TRAVEL_MS + "ms");
        // The footprint is the filter, so where it came from is the first thing worth knowing:
        // no footprint at all means nothing is drawn, however loud the room is.
        out.add("§7room §f" + (roomBorders == null
                ? "§cunknown §7(no archive/red-map match, no Watcher sighted)"
                : roomBorders.shape() + " §7" + roomBorders.min().getX() + "," + roomBorders.min().getZ()
                        + " → " + roomBorders.max().getX() + "," + roomBorders.max().getZ()
                        + " §7via §f" + (trackerBloodBorders() != null ? "archive/map" : "watcher cell")));
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return out;
        }
        // Then the entities themselves, exactly as the collector sees them: what they are, what their
        // nametag says, whether the room contains them and how they were judged. Every guess this
        // class makes is visible in these three columns.
        int shown = 0;
        int living = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity mob) || mob == player
                    || mob.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
                continue;
            }
            living++;
            Component custom = mob.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            // The Watcher is never cut off by the cap. He is the anchor everything else hangs on, so
            // "is he in this list, and what exactly does his nametag read" is the first question when
            // the helper is drawing nothing - and a blood room has far more than eight entities in it.
            if (shown >= 8 && !isWatcher(mob)) {
                continue;
            }
            String verdict = outsiders.contains(mob.getId()) ? "§6outsider"
                    : playerGear.contains(mob.getId()) || isPlayerGear(raw) || isGearEntity(mob) ? "§6gear"
                    : landed.contains(mob.getId()) ? "§alive"
                    : firstSeen.containsKey(mob.getId()) ? "§eairborne" : "§8untouched";
            // Which skull a stand wears decides whether it is a marker or a Witherborn, and it is
            // invisible from inside the game - so it is printed rather than guessed at.
            SkullBlock.Type skull = mob instanceof ArmorStand stand ? skullType(stand) : null;
            out.add("§8  " + mob.getType().toShortString()
                    + (raw.isEmpty() ? " §8(no tag)" : " §7\"" + raw + "\"")
                    + " §7star §f" + raw.contains(STAR)
                    + " §7in-room §f" + inBloodRoom(mob)
                    // Whether it was ever in the air: for a skull this is the whole reason it is
                    // drawn or not, and it is the one thing you cannot see by looking at the room.
                    + " §7flown §f" + flown.contains(mob.getId())
                    + (skull == null ? "" : " §7skull §f" + skull)
                    + " §7" + verdict);
            shown++;
        }
        out.add("§7living entities in range §f" + living);
        return out;
    }

    /** {@code 12.4s} - the blood room is short enough that tenths are the useful unit. */
    public static String seconds(long ms) {
        return String.format(Locale.US, "%.1fs", Math.max(0, ms) / 1000.0);
    }
}
