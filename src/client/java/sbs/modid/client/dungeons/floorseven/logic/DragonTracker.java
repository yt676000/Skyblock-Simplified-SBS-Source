/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The five dragons of the M7 dragon phase: which are up, what each has left, and - the part the
 * phase is actually decided by - <b>where each one has to die</b>. A dragon only brings its statue
 * down when it is killed at that statue, so a dragon in the wrong half of the room is a dragon that
 * has to be dragged back, not one that is nearly dead.
 *
 * <p><b>Where the statue positions come from.</b> Not from a table in this file. A dragon comes up at
 * its own statue, so the first position we see it at <i>is</i> that statue, and that is what the box
 * is anchored to and what gets remembered per colour ({@code m7DragonStatues}) for the next run. If
 * a position is ever off, {@link #setStatue} overwrites it with where you are standing - a run's
 * worth of guessing beats a constant that cannot be checked from outside the game.
 *
 * <p><b>Which dragon is which.</b> Asked of the game in the same order a player would: the name
 * Hypixel puts on the entity if there is one, otherwise the colour of the ring of blocks that rides
 * under the dragon's head - the collar people call the dragon by. Neither answering leaves it an
 * unnamed dragon with a working box, which is the part that matters; identification is retried every
 * tick, so a collar that had not loaded yet still lands a moment later.
 *
 * <p><b>The health is the entity's own.</b> Unlike the dungeon's zombies, whose real health only
 * exists in their nametag, a dragon is a dragon: the number the client already has is the number.
 * It is shown as a fraction as well as a figure, so it stays readable either way.
 */
public final class DragonTracker {

    private static final DragonTracker INSTANCE = new DragonTracker();

    /** The arena is large and dragons are fought across it - this reaches the far statues. */
    private static final double SCAN_RADIUS = 160.0;

    /** How far around a dragon its collar blocks ride. */
    private static final double COLLAR_RADIUS = 8.0;

    /**
     * One of the five: the colour it is drawn in, the word its name contains, and the block colours
     * its collar can be made of.
     */
    public enum Kind {
        POWER("Power", 0xFFFF5555, "power", "red"),
        APEX("Apex", 0xFF7CFF6A, "apex", "lime", "green"),
        FLAME("Flame", 0xFFFFAA00, "flame", "orange"),
        ICE("Ice", 0xFF6ADFFF, "ice", "light_blue", "cyan", "blue"),
        SOUL("Soul", 0xFFB070FF, "soul", "purple", "magenta"),
        /** Seen, but neither its name nor its collar said which one it is. */
        UNKNOWN("Dragon", 0xFFCCCCCC, "");

        private final String label;
        private final int color;
        private final String word;
        private final String[] collarColours;

        Kind(String label, int color, String word, String... collarColours) {
            this.label = label;
            this.color = color;
            this.word = word;
            this.collarColours = collarColours;
        }

        public String label() {
            return label;
        }

        public int color() {
            return color;
        }

        /** Whether a block id starts with one of this dragon's collar colours. */
        private boolean wearsCollar(String itemPath) {
            for (String colour : collarColours) {
                if (itemPath.startsWith(colour + "_")) {
                    return true;
                }
            }
            return false;
        }

        /** The kind a command argument or a dragon's name names, or null. */
        public static Kind byWord(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind != UNKNOWN && lower.contains(kind.word)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * One living dragon. {@code statue} is where it has to die, {@code inZone} whether it is there
     * right now, and {@code fraction} is -1 when the health could not be read as one.
     */
    public record Dragon(int id, Kind kind, double health, double maxHealth, Vec3 position,
                         Vec3 statue, boolean inZone, double statueDistance) {

        public double fraction() {
            return maxHealth <= 0 ? -1 : Math.max(0, Math.min(1, health / maxHealth));
        }
    }

    /**
     * How long the scan has to have been running before a first sighting counts as a spawn. A dragon
     * that was already in the air when we started looking - the toggle flicked on mid-phase, the boss
     * room entered late - would otherwise teach the config a statue that is just wherever it happened
     * to be flying.
     */
    private static final long SPAWN_TRUST_MS = 2_000L;

    private volatile List<Dragon> dragons = List.of();

    /** Per entity: the position it was first seen at (its statue) and which dragon it is. */
    private final Map<Integer, Vec3> spawnPositions = new HashMap<>();
    private final Map<Integer, Kind> identified = new HashMap<>();

    /** When the current stretch of scanning began; 0 while it is not running. */
    private long trackingSinceMs;

    private DragonTracker() {
    }

    public static DragonTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ---- read by the HUD / highlight ------------------------------------------------------------------

    /** The dragons that are up right now, weakest first. */
    public List<Dragon> dragons() {
        return dragons;
    }

    /** The remembered statue of a kind, or null when it has never been seen or set. */
    public Vec3 statueOf(Kind kind) {
        double[] stored = cfg().m7DragonStatues.get(kind.name());
        return stored == null || stored.length < 3 ? null : new Vec3(stored[0], stored[1], stored[2]);
    }

    /** Half-width of a statue box, in blocks. */
    public static double zoneRadius() {
        return Math.max(2, cfg().m7DragonBoxRadius);
    }

    /** Whether a point is inside a statue's box. */
    public static boolean inside(Vec3 point, Vec3 statue) {
        return statue != null
                && Math.abs(point.x - statue.x) <= zoneRadius()
                && Math.abs(point.z - statue.z) <= zoneRadius()
                && Math.abs(point.y - statue.y) <= zoneRadius();
    }

    /** M7 specifically: F7 has no dragon phase at all, so nothing here ever runs there. */
    public static boolean onMasterSeven() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return state.inDungeon() && state.floorNumber() == 7 && state.floorType() == 'M';
    }

    /** M7, in the boss room - the window in which the boxes are worth drawing at all. */
    public static boolean inBossRoom() {
        return onMasterSeven() && DungeonStateManager.getInstance().phase() == DungeonEvents.Phase.BOSS;
    }

    /**
     * The dragon phase itself, as the phase timer sees it. Used to decide whether to draw the
     * statues nobody is fighting: through Maxor, Storm, Goldor and Necron they would be five boxes
     * of pure noise around a fight that has nothing to do with them.
     */
    public static boolean inDragonPhase() {
        return FloorSevenPhaseTimer.getInstance().current() == FloorSevenPhaseTimer.Phase.DRAGONS;
    }

    // ---- tick ---------------------------------------------------------------------------------

    public void onClientTick() {
        SBSConfig.DungeonsSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if ((!cfg.m7DragonHp && !cfg.m7DragonBoxes) || !inBossRoom() || level == null || player == null) {
            if (!dragons.isEmpty()) {
                dragons = List.of();
            }
            if (!DungeonStateManager.getInstance().inDungeon() && !spawnPositions.isEmpty()) {
                spawnPositions.clear();  // the remembered statues live in the config, these do not
                identified.clear();
            }
            trackingSinceMs = 0;
            return;
        }
        if (trackingSinceMs == 0) {
            trackingSinceMs = System.currentTimeMillis();
        }
        collect(level, player);
    }

    private void collect(ClientLevel level, LocalPlayer player) {
        List<Dragon> found = new ArrayList<>(5);
        Set<Integer> alive = new HashSet<>();
        for (EnderDragon dragon : level.getEntitiesOfClass(EnderDragon.class,
                player.getBoundingBox().inflate(SCAN_RADIUS), Entity::isAlive)) {
            int id = dragon.getId();
            alive.add(id);
            Vec3 position = dragon.position();
            // First sighting is the spawn, and the spawn is the statue.
            Vec3 spawn = spawnPositions.computeIfAbsent(id, ignored -> position);

            Kind kind = identified.getOrDefault(id, Kind.UNKNOWN);
            if (kind == Kind.UNKNOWN) {
                kind = identify(level, dragon);
                if (kind != Kind.UNKNOWN) {
                    identified.put(id, kind);
                    if (System.currentTimeMillis() - trackingSinceMs > SPAWN_TRUST_MS) {
                        rememberStatue(kind, spawn);
                    }
                }
            }
            // A statue set by hand wins over the spawn: it is the one somebody checked.
            Vec3 statue = kind == Kind.UNKNOWN ? spawn : firstNonNull(statueOf(kind), spawn);
            found.add(new Dragon(id, kind, dragon.getHealth(), dragon.getMaxHealth(), position,
                    statue, inside(position, statue), position.distanceTo(statue)));
        }
        spawnPositions.keySet().retainAll(alive);
        identified.keySet().retainAll(alive);
        // Weakest first: the top line of the card is the one being focused.
        found.sort((a, b) -> Double.compare(a.fraction() < 0 ? Double.MAX_VALUE : a.fraction(),
                b.fraction() < 0 ? Double.MAX_VALUE : b.fraction()));
        dragons = List.copyOf(found);
    }

    // ---- identification -----------------------------------------------------------------------

    /** The dragon's own name first, then the colour of the collar riding under its head. */
    private static Kind identify(ClientLevel level, EnderDragon dragon) {
        Kind named = Kind.byWord(dragon.getName().getString().replaceAll("§.", ""));
        if (named != null) {
            return named;
        }
        Kind collar = collarColour(level, dragon);
        return collar == null ? Kind.UNKNOWN : collar;
    }

    /**
     * The ring of coloured blocks a dragon carries: on Hypixel those are stands wearing one block
     * each, so the dragon's colour is the colour of a block worn near it. Any equipment slot is read,
     * not just the head - what matters is that the block is there, not how it is being held.
     *
     * <p>The <b>nearest</b> coloured block wins, not the first one found: two dragons crossing over
     * each other would otherwise trade collars, and a wrong colour is worse than none - it would put
     * the box on somebody else's statue.
     */
    private static Kind collarColour(ClientLevel level, EnderDragon dragon) {
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                dragon.getBoundingBox().inflate(COLLAR_RADIUS),
                candidate -> candidate != dragon && !(candidate instanceof EnderDragon));
        Kind nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : nearby) {
            double distance = entity.distanceToSqr(dragon);
            if (distance >= nearestDistance) {
                continue;
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                Kind kind = colourOf(entity.getItemBySlot(slot));
                if (kind != null) {
                    nearest = kind;
                    nearestDistance = distance;
                    break;
                }
            }
        }
        return nearest;
    }

    /** The dragon whose colour this block item is, or null when it is not one of the five. */
    private static Kind colourOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        for (Kind kind : Kind.values()) {
            if (kind != Kind.UNKNOWN && kind.wearsCollar(path)) {
                return kind;
            }
        }
        return null;
    }

    // ---- statue memory ------------------------------------------------------------------------

    /** Remembers a statue the first time that colour is placed; an existing one is left alone. */
    private static void rememberStatue(Kind kind, Vec3 position) {
        if (kind == Kind.UNKNOWN || cfg().m7DragonStatues.containsKey(kind.name())) {
            return;
        }
        cfg().m7DragonStatues.put(kind.name(), new double[]{position.x, position.y, position.z});
        ConfigManager.getInstance().save();
    }

    /** Sets a statue by hand - what the command uses to correct a position that reads wrong. */
    public static void setStatue(Kind kind, Vec3 position) {
        cfg().m7DragonStatues.put(kind.name(), new double[]{position.x, position.y, position.z});
        ConfigManager.getInstance().save();
    }

    public static void clearStatues() {
        cfg().m7DragonStatues.clear();
        ConfigManager.getInstance().save();
    }

    private static Vec3 firstNonNull(Vec3 first, Vec3 fallback) {
        return first != null ? first : fallback;
    }
}
