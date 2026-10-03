/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

/**
 * Every decision freecam makes, as pure functions: may it run here, when does it snap back, how far
 * may the camera go, which entities are hidden. Kept free of game types so each rule is unit-tested
 * ({@code FreecamRulesTest}) instead of trusted.
 */
public final class FreecamRules {

    private FreecamRules() {
    }

    /**
     * Where the player is, as far as the gate cares. {@code ownPrivateIsland} is the player's OWN
     * island only ({@link #ownPrivateIsland}); {@code garden} is any Garden, the player's or one they
     * are visiting - copying another player's farm design is allowed on purpose.
     */
    public record Place(boolean singleplayer, boolean ownPrivateIsland, boolean garden, boolean dungeon,
                        boolean kuudra, boolean rift) {
    }

    /** The multiplayer settings the gate reads. */
    public record Settings(boolean multiplayerEnabled) {
    }

    /** The scoreboard zone on the player's own island. CONFIRMED - 11,191 readings in the play logs. */
    public static final String OWN_ISLAND_ZONE = "Your Island";

    /**
     * Whether a Private Island reading is the player's own. The tab's {@code Area: Private Island}
     * reads the same on a visit, so it cannot decide this; the scoreboard zone can. On the player's
     * own island it is always {@code Your Island}; on a visit (play log, {@code Sending a visit
     * request...}) the zone read empty, the tab header said {@code Island} instead of the co-op list,
     * and the player was listed among the guests with {@code [✌]}. Anything but the exact
     * own-island zone counts as a visit - an unreadable scoreboard refuses.
     */
    public static boolean ownPrivateIsland(boolean onPrivateIsland, String zone) {
        return onPrivateIsland && zone != null && OWN_ISLAND_ZONE.equalsIgnoreCase(zone.trim());
    }

    public static final String NO_DUNGEON = "Freecam is never available in dungeons, Kuudra or the Rift";
    public static final String MP_OFF = "Freecam on servers is off - switch on \"Freecam On Servers\" on its "
            + "settings page (Build Tools or Cinematic Camera)";
    public static final String AREA = "Freecam only works on your Private Island and on Gardens";

    /**
     * Why freecam may not run at {@code place}, or {@code null} when it may. Dungeons, Kuudra and the
     * Rift refuse whatever the settings; singleplayer has no other limit; on a server the multiplayer
     * toggle must be on and the player must be on their own Private Island or on a Garden (theirs or
     * a visited one). No setting widens this.
     */
    public static String refusal(Place place, Settings settings) {
        if (place.dungeon() || place.kuudra() || place.rift()) {
            return NO_DUNGEON;
        }
        if (place.singleplayer()) {
            return null;
        }
        if (!settings.multiplayerEnabled()) {
            return MP_OFF;
        }
        if (!place.ownPrivateIsland() && !place.garden()) {
            return AREA;
        }
        return null;
    }

    /** Why freecam snapped back to the player's view. */
    public enum Snap {
        NONE, PAUSE_MENU, DAMAGE, TELEPORT, WORLD_CHANGE, LEFT_AREA
    }

    /** One tick's reading of the player, compared with the tick before. */
    public record Reading(Object level, Object player, double x, double y, double z, float health, int hurtTime,
                          boolean pauseMenuOpen, boolean allowed) {
    }

    /** A move of the player larger than this in one tick is a teleport (walking is ~0.3, knockback ~1.5). */
    public static final double TELEPORT_DISTANCE = 4.0;

    /**
     * Whether this tick ends freecam, and why. The first matching reason wins, in the order a player
     * would want to hear it.
     */
    public static Snap snap(Reading before, Reading now) {
        if (before.level() != now.level() || before.player() != now.player()) {
            return Snap.WORLD_CHANGE;
        }
        if (now.pauseMenuOpen()) {
            return Snap.PAUSE_MENU;
        }
        if (now.health() < before.health() || (now.hurtTime() > 0 && before.hurtTime() == 0)) {
            return Snap.DAMAGE;
        }
        double dx = now.x() - before.x();
        double dy = now.y() - before.y();
        double dz = now.z() - before.z();
        if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            return Snap.TELEPORT;
        }
        if (!now.allowed()) {
            return Snap.LEFT_AREA;
        }
        return Snap.NONE;
    }

    /**
     * The camera position kept within {@code range} blocks of the player's eye ({@code range <= 0}
     * means no limit): a camera pushed past the edge stops on the sphere instead of snapping back.
     */
    public static double[] clampToRange(double cx, double cy, double cz, double ex, double ey, double ez, double range) {
        if (range <= 0) {
            return new double[] {cx, cy, cz};
        }
        double dx = cx - ex;
        double dy = cy - ey;
        double dz = cz - ez;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= range) {
            return new double[] {cx, cy, cz};
        }
        double scale = range / distance;
        return new double[] {ex + dx * scale, ey + dy * scale, ez + dz * scale};
    }

    /** The range that applies: the setting on a server; in singleplayer only if the player set one. */
    public static double effectiveRange(boolean singleplayer, int multiplayerRange, int singleplayerRange) {
        return singleplayer ? Math.max(0, singleplayerRange) : Math.max(1, multiplayerRange);
    }

    /**
     * Whether an entity is left out of the picture: only while freecam is on, only on a server, only
     * with the setting on, and never the player's own model (it stands where they are).
     */
    public static boolean hidesEntity(boolean freecamOn, boolean singleplayer, boolean hideSetting, boolean localPlayer) {
        return freecamOn && !singleplayer && hideSetting && !localPlayer;
    }

    /** Which freecam is running: the Build Tools one that selects, or the pure camera for filming. */
    public enum Mode {
        BUILD("Build"), CINEMATIC("Cinematic");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What a freecam key or command does, given what is running. */
    public enum Transition {
        START, STOP, SWITCH
    }

    /**
     * {@code requested}'s key pressed while {@code running} ({@code null} = none) is on: the same mode
     * again ends it, the other mode switches over without snapping back, none running starts it.
     */
    public static Transition transition(Mode running, Mode requested) {
        if (running == null) {
            return Transition.START;
        }
        return running == requested ? Transition.STOP : Transition.SWITCH;
    }

    /**
     * The one "build input active" rule: the Magic Stick Thingy in hand, or build freecam on. The
     * selection clicks, the targeting, the previews and the help card all ask this, so holding the
     * stick and flying in build freecam can never behave differently. Cinematic freecam is never build
     * input, even with the stick in hand: it is a pure camera.
     */
    public static boolean buildInputActive(boolean stickHeld, Mode running) {
        if (running == Mode.CINEMATIC) {
            return false;
        }
        return stickHeld || running == Mode.BUILD;
    }

    /** Whether a click in freecam selects a corner. In cinematic freecam a click does nothing at all. */
    public static boolean clickSelects(Mode running) {
        return running == Mode.BUILD;
    }

    /** Whether the HUD - vanilla and SBS cards - is hidden: cinematic freecam with "Hide HUD" on. */
    public static boolean hidesHud(Mode running, boolean hideHudSetting) {
        return running == Mode.CINEMATIC && hideHudSetting;
    }

    /** Whether SBS world markers (holograms, waypoints, highlights) go with the HUD. */
    public static boolean hidesWorldMarkers(Mode running, boolean hideHudSetting, boolean showMarkersSetting) {
        return hidesHud(running, hideHudSetting) && !showMarkersSetting;
    }

    /** How long the "Freecam" indicator stays on a hidden HUD after cinematic freecam starts. */
    public static final long INDICATOR_MS = 2000;

    /** Whether the on-screen indicator shows: always, except on a hidden HUD after its first 2 s. */
    public static boolean indicatorShown(Mode running, boolean hideHudSetting, long msSinceStart) {
        if (running == null) {
            return false;
        }
        return !hidesHud(running, hideHudSetting) || msSinceStart < INDICATOR_MS;
    }

    /**
     * One tick of eased movement: the velocity covers a share of the way to the wanted one.
     * {@code smoothing} 0 is instant, 95 the longest glide; clamped to that range.
     */
    public static double ease(double velocity, double wanted, int smoothing) {
        double s = Math.max(0, Math.min(95, smoothing)) / 100.0;
        double next = velocity + (wanted - velocity) * (1 - s);
        return wanted == 0 && Math.abs(next) < 1e-4 ? 0 : next;
    }
}
