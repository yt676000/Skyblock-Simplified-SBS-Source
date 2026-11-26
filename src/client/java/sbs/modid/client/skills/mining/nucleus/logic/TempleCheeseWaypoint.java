/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.NucleusRunSettings;
import sbs.modid.client.core.dev.EntityProbe;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.skills.mining.nucleus.model.Crystal;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData.Run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jungle Temple Cheese Waypoint: the game side of {@link TempleCheese}. Reads the guardian's chat
 * lines, the entities within {@link TempleCheese#SIGHT_RANGE} blocks of the player, the lobby id and
 * the Nucleus run state; publishes one transient waypoint.
 *
 * <p>Display only. It never moves, aims, clicks or sends anything. The guardian is found by an entity
 * query around the player, never by a block or chunk scan, and counts only when the player has line
 * of sight to it or has just received one of its chat lines - i.e. when the player is standing at the
 * temple door. The marker sits at a fixed offset from that NPC, which a player could measure by hand.
 *
 * <p>Every anchor, every arrival at the target and every Amethyst find is logged under
 * {@code [SBS][Temple]} so one run in game settles the offset's base point and the temple's
 * orientation (docs/features/jungle-temple-cheese-waypoint.md).
 */
public final class TempleCheeseWaypoint {

    /** Purple, for the Amethyst the temple holds. */
    public static final int DEFAULT_RGB = 0xB266FF;
    private static final String DEFAULT_HEX = "B266FF";
    private static final String LABEL = "Temple Cheese";
    /** Entity lookups and state checks run this often while on the Crystal Hollows. */
    private static final int EVERY_TICKS = 5;

    private static TempleCheeseWaypoint instance;

    private final TempleCheese logic = new TempleCheese();

    private int tickCounter;
    private boolean published;
    private String publishedKey = "";
    private Crystal.State lastAmethyst = Crystal.State.NONE;
    private Run lastRun;
    /** A guardian line arrived and no anchor has been taken since: dump the nearby entities if none is found. */
    private boolean dumpPending;
    private boolean dumpedThisLobby;
    private boolean bodilessLogged;

    private TempleCheeseWaypoint() {
    }

    public static synchronized TempleCheeseWaypoint getInstance() {
        if (instance == null) {
            instance = new TempleCheeseWaypoint();
        }
        return instance;
    }

    private static NucleusRunSettings cfg() {
        return ConfigManager.getInstance().get().nucleusRun;
    }

    private static boolean onHollows() {
        return SkyBlockLocation.onIsland(HollowsTracker.ISLAND);
    }

    // ------------------------------------------------------------------ hooks

    /** Every chat line. Only the guardian's own lines do anything. */
    public void onChat(String raw) {
        if (raw == null || !cfg().templeCheese || !onHollows()) {
            return;
        }
        String plain = NucleusChatParser.strip(raw);
        if (logic.onChat(plain, System.currentTimeMillis())) {
            log("guardian line: " + plain);
            if (logic.wantsAnchor() && !dumpedThisLobby) {
                dumpPending = true;
            }
        }
    }

    /** Every client tick. */
    public void onClientTick() {
        if (!cfg().templeCheese) {
            if (logic.anchor() != null || published) {
                logic.reset();
                clear();
            }
            return;
        }
        if (++tickCounter % EVERY_TICKS != 0) {
            return;
        }
        if (!onHollows()) {
            if (logic.anchor() != null) {
                log("left the Crystal Hollows: anchor dropped");
            }
            resetLobby();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String lobby = ServerWorldTime.serverName();
        if (logic.onLobby(lobby)) {
            log("lobby changed to " + lobby + ": anchor dropped");
            resetLobby();
        }

        boolean wanted = trackRun(player);
        if (wanted && logic.wantsAnchor()) {
            findGuardian(player, level, lobby, now);
        }
        TempleCheese.Anchor anchor = logic.anchor();
        if (anchor != null && wanted) {
            if (logic.reached(player.getX(), player.getY(), player.getZ())) {
                log("reached target: player at " + block(player) + ", offset from anchor "
                        + format(logic.offsetFromAnchor(pos(player))));
            }
            publish(anchor);
        } else {
            clear();
        }
    }

    /** World change, server hop, disconnect: the anchor belonged to the lobby. */
    public void onWorldChange() {
        resetLobby();
    }

    // ------------------------------------------------------------------ run state

    /**
     * Whether the run still needs the temple: a run is open and its Amethyst is not found. Logs the
     * Amethyst find against the anchor. Without the Nucleus Run tracker there is no run state, so the
     * answer is always yes and the marker lasts until the lobby changes.
     */
    private boolean trackRun(LocalPlayer player) {
        if (!cfg().enabled) {
            return true;
        }
        NucleusRunLedger ledger = NucleusRunTracker.getInstance().ledger();
        Run run = ledger.current();
        if (run != lastRun) {
            lastRun = run;
            logic.newRun();
        }
        Crystal.State amethyst = ledger.crystalState(Crystal.AMETHYST);
        if (lastAmethyst == Crystal.State.NONE && amethyst != Crystal.State.NONE) {
            TempleCheese.Anchor anchor = logic.anchor();
            log("amethyst " + amethyst.name().toLowerCase(Locale.ROOT) + ": player at " + block(player)
                    + (anchor == null ? ", no anchor this lobby"
                    : ", offset from anchor " + format(logic.offsetFromAnchor(pos(player)))));
        }
        lastAmethyst = amethyst;
        return run != null && amethyst == Crystal.State.NONE;
    }

    // ------------------------------------------------------------------ the guardian

    /** One way of seeing the guardian: the entity measured, its name, how it counts, and whether it is a bare tag. */
    private record Sighting(Entity seen, String name, TempleCheese.Via via, double distance, boolean bareTag) {
    }

    private void findGuardian(LocalPlayer player, ClientLevel level, String lobby, long now) {
        AABB box = player.getBoundingBox().inflate(TempleCheese.SIGHT_RANGE);
        List<Entity> nearby = level.getEntities(player, box);
        List<ArmorStand> tags = new ArrayList<>();
        List<LivingEntity> bodies = new ArrayList<>();
        for (Entity entity : nearby) {
            if (entity instanceof ArmorStand stand) {
                if (TempleCheese.isGuardianName(plain(stand.getCustomName()))) {
                    tags.add(stand);
                }
            } else if (entity instanceof LivingEntity living) {
                bodies.add(living);
            }
        }
        Sighting best = null;
        for (LivingEntity body : bodies) {
            String name = ownGuardianName(body);
            if (name != null) {
                best = better(best, sight(body, name, false, player, now));
            }
        }
        // Each guardian nametag: the body standing under it, else the stand itself - it sits level
        // with the guardian's feet, so its block is the anchor either way.
        for (ArmorStand tag : tags) {
            LivingEntity body = bodyUnder(tag, bodies);
            if (body == null && !bodilessLogged) {
                bodilessLogged = true;
                log("guardian nametag without a matching body, anchoring on the stand: "
                        + EntityProbe.describe(tag, player));
            }
            best = better(best, sight(body != null ? body : tag, plain(tag.getCustomName()), body == null,
                    player, now));
        }
        if (best != null) {
            Entity seen = best.seen();
            TempleCheese.Pos feet = best.bareTag()
                    ? TempleCheese.standBlock(seen.getX(), seen.getY(), seen.getZ())
                    : pos(BlockPos.containing(seen.getX(), seen.getY(), seen.getZ()));
            TempleCheese.Anchor anchor = logic.setAnchor(feet, best.via(), lobby);
            if (anchor != null) {
                dumpPending = false;
                log("anchor: guardian at " + format(anchor.guardian()) + " (entity " + typeId(seen)
                        + (best.bareTag() ? ", nametag stand only" : "") + ", name \"" + best.name()
                        + "\", via " + best.via().name().toLowerCase(Locale.ROOT) + ", "
                        + String.format(Locale.ROOT, "%.1f", best.distance()) + " blocks) -> target "
                        + format(anchor.target()) + " (offset " + TempleCheese.OFFSET_X + ","
                        + TempleCheese.OFFSET_Y + "," + TempleCheese.OFFSET_Z + " "
                        + TempleCheese.OFFSET_CERTAINTY + ", lobby " + lobby + ")");
            }
            return;
        }
        if (dumpPending) {
            dumpPending = false;
            dumpedThisLobby = true;
            log("no guardian within " + (int) TempleCheese.SIGHT_RANGE + " blocks after its chat line; "
                    + nearby.size() + " entit(ies) nearby, player at " + block(player));
            for (Entity entity : nearby) {
                log("nearby: " + EntityProbe.describe(entity, player));
            }
        }
    }

    /** {@code seen} as a sighting if it counts (range, line of sight or a fresh chat line), else {@code null}. */
    private Sighting sight(Entity seen, String name, boolean bareTag, LocalPlayer player, long now) {
        double distance = seen.distanceTo(player);
        TempleCheese.Via via = logic.accept(distance, player.hasLineOfSight(seen), now);
        return via == null ? null : new Sighting(seen, name, via, distance, bareTag);
    }

    /** A body beats a bare tag (it is the real feet block); between equals, the nearer one. */
    private static Sighting better(Sighting a, Sighting b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        if (a.bareTag() != b.bareTag()) {
            return a.bareTag() ? b : a;
        }
        return b.distance() < a.distance() ? b : a;
    }

    /** The guardian's name if the body carries it itself (custom name or own name), else {@code null}. */
    private static String ownGuardianName(Entity body) {
        String custom = plain(body.getCustomName());
        if (TempleCheese.isGuardianName(custom)) {
            return custom;
        }
        String own = plain(body.getName());
        return TempleCheese.isGuardianName(own) ? own : null;
    }

    /** The body {@code tag} is the nametag of: horizontally nearest among those it fits ({@link TempleCheese#isTagOf}). */
    private static LivingEntity bodyUnder(ArmorStand tag, List<LivingEntity> bodies) {
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity body : bodies) {
            double dx = tag.getX() - body.getX();
            double dz = tag.getZ() - body.getZ();
            if (!TempleCheese.isTagOf(dx, tag.getY() - body.getY(), dz)) {
                continue;
            }
            double sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = body;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ waypoint

    private void publish(TempleCheese.Anchor anchor) {
        TempleCheese.Pos target = anchor.target();
        String hex = colorHex();
        String key = target + "|" + hex;
        if (published && key.equals(publishedKey)) {
            return;
        }
        Waypoint waypoint = new Waypoint(LABEL, new BlockPos(target.x(), target.y(), target.z()),
                WaypointStore.currentDimension(), Waypoint.SOURCE_TEMPLE_CHEESE);
        waypoint.colorHex = hex;
        waypoint.showDistance = true;
        // A landmark, not a destination: nothing routes to it.
        waypoint.routable = false;
        // The spot is underground by construction; the renderer's default is to draw through walls.
        waypoint.throughWalls = true;
        if (TempleCheese.OFFSET_CERTAINTY != Certainty.CONFIRMED) {
            waypoint.subLabel = "offset estimated";
        }
        WaypointStore.setTransient(Waypoint.SOURCE_TEMPLE_CHEESE, List.of(waypoint));
        published = true;
        publishedKey = key;
    }

    private void clear() {
        if (published) {
            WaypointStore.clearTransient(Waypoint.SOURCE_TEMPLE_CHEESE);
            published = false;
            publishedKey = "";
        }
    }

    private void resetLobby() {
        logic.reset();
        dumpPending = false;
        dumpedThisLobby = false;
        bodilessLogged = false;
        clear();
    }

    private static String colorHex() {
        String own = cfg().templeCheeseColorHex;
        return own == null || own.isBlank() ? DEFAULT_HEX : own;
    }

    // ------------------------------------------------------------------ helpers

    private static TempleCheese.Pos pos(LocalPlayer player) {
        return pos(player.blockPosition());
    }

    private static TempleCheese.Pos pos(BlockPos block) {
        return new TempleCheese.Pos(block.getX(), block.getY(), block.getZ());
    }

    private static String block(LocalPlayer player) {
        return format(pos(player));
    }

    private static String format(TempleCheese.Pos pos) {
        return pos == null ? "?" : pos.x() + "," + pos.y() + "," + pos.z();
    }

    private static String plain(Component component) {
        return component == null ? null : PlainText.strip(component.getString()).trim();
    }

    private static String typeId(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null ? key.toString() : entity.getType().toString();
    }

    private static void log(String message) {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Temple] {}", message);
    }
}
