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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.NucleusRunSettings;
import sbs.modid.client.core.dev.EntityProbe;
import sbs.modid.client.core.dev.NpcNametags;
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

    private void findGuardian(LocalPlayer player, ClientLevel level, String lobby, long now) {
        AABB box = player.getBoundingBox().inflate(TempleCheese.SIGHT_RANGE);
        List<Entity> nearby = level.getEntities(player, box);
        List<ArmorStand> stands = new ArrayList<>();
        for (Entity entity : nearby) {
            if (entity instanceof ArmorStand stand) {
                stands.add(stand);
            }
        }
        Entity best = null;
        String bestName = null;
        TempleCheese.Via bestVia = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : nearby) {
            if (entity instanceof ArmorStand) {
                continue;
            }
            String name = guardianName(entity, stands);
            if (name == null) {
                continue;
            }
            double distance = entity.distanceTo(player);
            TempleCheese.Via via = logic.accept(distance, player.hasLineOfSight(entity), now);
            if (via != null && distance < bestDistance) {
                best = entity;
                bestName = name;
                bestVia = via;
                bestDistance = distance;
            }
        }
        if (best != null) {
            BlockPos feet = BlockPos.containing(best.getX(), best.getY(), best.getZ());
            TempleCheese.Anchor anchor = logic.setAnchor(new TempleCheese.Pos(feet.getX(), feet.getY(),
                    feet.getZ()), bestVia, lobby);
            if (anchor != null) {
                dumpPending = false;
                log("anchor: guardian at " + format(anchor.guardian()) + " (entity " + typeId(best)
                        + ", name \"" + bestName + "\", via " + bestVia.name().toLowerCase(Locale.ROOT)
                        + ") -> target " + format(anchor.target()) + " (offset "
                        + TempleCheese.OFFSET_X + "," + TempleCheese.OFFSET_Y + "," + TempleCheese.OFFSET_Z
                        + " " + TempleCheese.OFFSET_CERTAINTY + ", lobby " + lobby + ")");
            }
            return;
        }
        if (!bodilessLogged) {
            for (ArmorStand stand : stands) {
                String text = plain(stand.getCustomName());
                if (TempleCheese.isGuardianName(text)) {
                    bodilessLogged = true;
                    log("guardian nametag without a matching body: " + EntityProbe.describe(stand, player));
                    break;
                }
            }
        }
        if (dumpPending) {
            dumpPending = false;
            dumpedThisLobby = true;
            log("no guardian body within " + (int) TempleCheese.SIGHT_RANGE + " blocks after its chat line; "
                    + nearby.size() + " entit(ies) nearby, player at " + block(player));
            for (Entity entity : nearby) {
                log("nearby: " + EntityProbe.describe(entity, player));
            }
        }
    }

    /**
     * The guardian's name if {@code body} is the guardian: its own custom name, its own name, or the
     * top line of the nametag stands stacked above it. {@code null} when none of them reads as the
     * guardian.
     */
    private static String guardianName(Entity body, List<ArmorStand> stands) {
        String custom = plain(body.getCustomName());
        if (TempleCheese.isGuardianName(custom)) {
            return custom;
        }
        String own = plain(body.getName());
        if (TempleCheese.isGuardianName(own)) {
            return own;
        }
        List<NpcNametags.Stand> above = new ArrayList<>();
        for (ArmorStand stand : stands) {
            String text = stand.getCustomName() == null ? null : stand.getCustomName().getString();
            if (text != null) {
                above.add(new NpcNametags.Stand(stand.getX() - body.getX(), stand.getY() - body.getY(),
                        stand.getZ() - body.getZ(), text));
            }
        }
        String resolved = NpcNametags.resolveName(null, above);
        return TempleCheese.isGuardianName(resolved) ? resolved : null;
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
        BlockPos feet = player.blockPosition();
        return new TempleCheese.Pos(feet.getX(), feet.getY(), feet.getZ());
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
