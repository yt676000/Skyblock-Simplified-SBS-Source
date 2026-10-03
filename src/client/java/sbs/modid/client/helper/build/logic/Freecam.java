/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.build.model.CameraRay;
import sbs.modid.client.helper.build.model.FreecamRules;
import sbs.modid.client.helper.build.model.FreecamRules.Mode;

import java.util.Locale;

/**
 * Freecam: the camera leaves the player's eyes and flies on its own. <b>Purely a view.</b> Two modes,
 * one at a time:
 * <ul>
 *   <li><b>Build</b> ({@code //freecam}, Build Tools' key): exactly like holding the Magic Stick
 *   Thingy - clicks set corners at the camera ray ({@link BuildInput}).</li>
 *   <li><b>Cinematic</b> ({@code /sbs freecam}, its own key and module): a pure camera for filming.
 *   Clicks do nothing, no selection, outline or preview; the HUD can be hidden; movement glides and
 *   mouse look uses vanilla's cinematic smoothing.</li>
 * </ul>
 * Starting one while the other runs switches over without snapping back.
 *
 * <p>What the server sees does not change: the player entity stays where it is with its own
 * rotation. Movement keys and the mouse go to the camera (the input mixins); no attack, use, place,
 * break or pick starts while freecam is on ({@code FreecamClickMixin}); and {@link FreecamPacketGuard}
 * drops anything outgoing that a player standing still would not send. Nothing requests or keeps
 * chunks, and render distance is never touched.
 *
 * <p>On a server both modes are gated ({@link FreecamRules#refusal}), each with its own settings: a
 * toggle, default off; only the player's own Private Island and Gardens (their own or a visited
 * one); never dungeons, Kuudra or the Rift; a range from the player; other entities not drawn. It
 * snaps back on the key, the pause menu, damage, a teleport, a world change or leaving those places.
 */
public final class Freecam {

    /** Blocks per tick at speed 1. */
    private static final double BASE_SPEED = 0.5;
    private static final double[] SPEEDS = {0.1, 0.2, 0.35, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6};
    /** Cinematic's finer steps: slow pans want speeds build freecam never needs. */
    private static final double[] FINE_SPEEDS = {0.02, 0.04, 0.07, 0.1, 0.15, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3};

    private static volatile Mode mode;
    private static long startedAt;
    private static double x;
    private static double y;
    private static double z;
    private static double prevX;
    private static double prevY;
    private static double prevZ;
    private static double vx;
    private static double vy;
    private static double vz;
    private static float yaw;
    private static float pitch;
    private static int speedIndex = 5;
    private static int fineSpeedIndex = 5;
    private static long speedShownUntil;
    private static FreecamRules.Reading last;
    /** Vanilla's smooth-camera option as it was before cinematic mouse smoothing switched it on. */
    private static Boolean smoothCameraBefore;

    // This tick's movement keys, handed over by the input mixin instead of reaching the player.
    private static boolean forward;
    private static boolean back;
    private static boolean left;
    private static boolean right;
    private static boolean up;
    private static boolean down;
    private static boolean fast;

    static {
        BuildSession.onLeave(() -> stop(null));
        sbs.modid.client.ui.hud.edit.logic.HudLayout.setHideAll(Freecam::hidesHud);
        BuildKeys.register(new BuildKeys.Handler() {
            @Override
            public boolean onKey(int key, int modifiers, boolean repeat) {
                return false;
            }

            @Override
            public boolean onScroll(double yOffset, boolean shift) {
                if (Minecraft.getInstance().hasAltDown() && BuildTargeting.depthApplies()) {
                    BuildTargeting.stepDepth(yOffset > 0 ? 1 : -1);
                    return true;
                }
                return scrollSpeed(yOffset);
            }
        });
    }

    private Freecam() {
    }

    /** Either freecam is on: the camera is detached and the input goes to it. */
    public static boolean active() {
        return mode != null;
    }

    /** The running mode, or {@code null}. */
    public static Mode mode() {
        return mode;
    }

    public static boolean cinematic() {
        return mode == Mode.CINEMATIC;
    }

    private static SBSConfig.BuildToolsSettings buildCfg() {
        return ConfigManager.getInstance().get().buildTools;
    }

    private static SBSConfig.CinematicCameraSettings cineCfg() {
        return ConfigManager.getInstance().get().cinematicCamera;
    }

    /** One mode's switch and safety settings, each read from its own card. */
    private record Limits(boolean enabled, boolean multiplayer, int range, int rangeSingleplayer,
                          boolean hideEntities, boolean noclip) {
    }

    private static Limits limits(Mode which) {
        if (which == Mode.CINEMATIC) {
            SBSConfig.CinematicCameraSettings c = cineCfg();
            return new Limits(c.enabled, c.multiplayer, c.range, c.rangeSingleplayer, c.hideEntities,
                    c.noclip);
        }
        SBSConfig.BuildToolsSettings c = buildCfg();
        return new Limits(c.enabled, c.freecamMultiplayer, c.freecamRange,
                c.freecamRangeSingleplayer, c.freecamHideEntities, c.freecamNoclip);
    }

    /** {@code //freecam} or Build Tools' key. */
    public static void toggle() {
        toggle(Mode.BUILD);
    }

    /** A freecam key or command for {@code requested}: start, stop, or switch over from the other mode. */
    public static void toggle(Mode requested) {
        switch (FreecamRules.transition(mode, requested)) {
            case START -> start(requested);
            case STOP -> stop("Freecam off");
            case SWITCH -> switchTo(requested);
        }
    }

    /** Why {@code which} may not run right now, or {@code null}. */
    private static String refusal(Mode which) {
        if (!limits(which).enabled()) {
            return which == Mode.BUILD ? "Build Tools is off - build freecam is part of it"
                    : "Cinematic Camera is off - switch it on in the settings";
        }
        return FreecamRules.refusal(place(), settings(which));
    }

    public static boolean start(Mode which) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || mode != null) {
            return false;
        }
        String refusal = refusal(which);
        if (refusal != null) {
            BuildChat.warn(refusal);
            return false;
        }
        Vec3 eye = player.getEyePosition();
        x = prevX = eye.x;
        y = prevY = eye.y;
        z = prevZ = eye.z;
        vx = vy = vz = 0;
        yaw = player.getYRot();
        pitch = player.getXRot();
        FreecamPacketGuard.begin();
        enter(which);
        last = reading(minecraft, player);
        BuildChat.info(which.label() + " freecam on - you stay where you are; WASD/space/sneak fly, wheel = speed, "
                + "the key again returns" + (which == Mode.BUILD ? "; left/right-click = corners" : ""));
        return true;
    }

    /** From one mode to the other, the camera staying where it is. Refused: the old mode keeps running. */
    private static void switchTo(Mode which) {
        String refusal = refusal(which);
        if (refusal != null) {
            BuildChat.warn(refusal);
            return;
        }
        leave();
        enter(which);
        BuildChat.info("Freecam: " + which.label());
    }

    /** Ends freecam; {@code message} (may be null) says why. */
    public static void stop(String message) {
        if (mode == null) {
            return;
        }
        leave();
        mode = null;
        if (message != null) {
            BuildChat.info(message);
        }
    }

    private static void enter(Mode which) {
        mode = which;
        startedAt = System.currentTimeMillis();
        if (which == Mode.CINEMATIC && cineCfg().mouseSmoothing) {
            var options = Minecraft.getInstance().options;
            smoothCameraBefore = options.smoothCamera;
            options.smoothCamera = true;
        }
    }

    private static void leave() {
        BuildTargeting.resetDepth();
        if (smoothCameraBefore != null) {
            Minecraft.getInstance().options.smoothCamera = smoothCameraBefore;
            smoothCameraBefore = null;
        }
    }

    private static FreecamRules.Place place() {
        boolean singleplayer = BuildGate.singleplayer();
        return new FreecamRules.Place(singleplayer,
                !singleplayer && FreecamRules.ownPrivateIsland(SkyBlockLocation.onIsland("Private Island"),
                        SkyBlockLocation.zone()),
                !singleplayer && SkyBlockLocation.onIsland("The Garden"),
                !singleplayer && SkyBlockLocation.inDungeon(),
                !singleplayer && SkyBlockLocation.onIsland("Kuudra's Hollow"),
                !singleplayer && SkyBlockLocation.onIsland("The Rift"));
    }

    private static FreecamRules.Settings settings(Mode which) {
        Limits limits = limits(which);
        return new FreecamRules.Settings(limits.multiplayer());
    }

    private static FreecamRules.Reading reading(Minecraft minecraft, LocalPlayer player) {
        Mode which = mode;
        return new FreecamRules.Reading(minecraft.level, player, player.getX(), player.getY(), player.getZ(),
                player.getHealth(), player.hurtTime, sbs.modid.client.core.api.ScreenAccess.current() instanceof PauseScreen,
                which != null && refusal(which) == null);
    }

    /** Called by the input mixin each tick with the movement keys, which then do not reach the player. */
    public static void takeInput(boolean f, boolean b, boolean l, boolean r, boolean jump, boolean sneak, boolean sprint) {
        forward = f;
        back = b;
        left = l;
        right = r;
        up = jump;
        down = sneak;
        fast = sprint;
    }

    /** The wheel while freecam is on: the next speed step, shown for 1.5 s. */
    static boolean scrollSpeed(double yOffset) {
        if (mode == null) {
            return false;
        }
        int step = yOffset > 0 ? 1 : -1;
        if (fine()) {
            fineSpeedIndex = Math.max(0, Math.min(FINE_SPEEDS.length - 1, fineSpeedIndex + step));
        } else {
            speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, speedIndex + step));
        }
        speedShownUntil = System.currentTimeMillis() + 1500;
        return true;
    }

    private static boolean fine() {
        return mode == Mode.CINEMATIC && cineCfg().fineSpeeds;
    }

    private static double speedFactor() {
        return fine() ? FINE_SPEEDS[fineSpeedIndex] : SPEEDS[speedIndex];
    }

    /** Client tick: snap-back checks, then move the camera. */
    public static void tick() {
        Mode which = mode;
        if (which == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            stop(null);
            return;
        }
        FreecamRules.Reading now = reading(minecraft, player);
        FreecamRules.Snap snap = FreecamRules.snap(last, now);
        last = now;
        if (snap != FreecamRules.Snap.NONE) {
            stop("Freecam off - " + switch (snap) {
                case PAUSE_MENU -> "pause menu";
                case DAMAGE -> "you took damage";
                case TELEPORT -> "you were moved";
                case WORLD_CHANGE -> "the world changed";
                case LEFT_AREA -> "only on your Private Island and on Gardens";
                case NONE -> "";
            });
            return;
        }
        prevX = x;
        prevY = y;
        prevZ = z;
        double speed = BASE_SPEED * speedFactor() * (fast ? 2.5 : 1);
        double forwardAmount = (forward ? 1 : 0) - (back ? 1 : 0);
        double strafe = (left ? 1 : 0) - (right ? 1 : 0);
        double vertical = (up ? 1 : 0) - (down ? 1 : 0);
        double sin = Math.sin(Math.toRadians(yaw));
        double cos = Math.cos(Math.toRadians(yaw));
        // Build freecam moves at once; cinematic glides into and out of every move.
        int smoothing = which == Mode.CINEMATIC ? cineCfg().movementSmoothing : 0;
        vx = FreecamRules.ease(vx, (strafe * cos - forwardAmount * sin) * speed, smoothing);
        vz = FreecamRules.ease(vz, (forwardAmount * cos + strafe * sin) * speed, smoothing);
        vy = FreecamRules.ease(vy, vertical * speed, smoothing);
        double nx = x + vx;
        double ny = y + vy;
        double nz = z + vz;
        Limits limits = limits(which);
        if (!limits.noclip() && solidAt(minecraft.level, nx, ny, nz)) {
            // Without noclip the camera stops at a block rather than entering it, axis by axis so it
            // slides along walls.
            nx = solidAt(minecraft.level, nx, y, z) ? x : nx;
            ny = solidAt(minecraft.level, nx, ny, z) ? y : ny;
            nz = solidAt(minecraft.level, nx, ny, nz) ? z : nz;
        }
        Vec3 eye = player.getEyePosition();
        double range = FreecamRules.effectiveRange(BuildGate.singleplayer(), limits.range(), limits.rangeSingleplayer());
        double[] clamped = FreecamRules.clampToRange(nx, ny, nz, eye.x, eye.y, eye.z, range);
        // What was actually moved is the velocity carried on: a wall or the range edge stops the glide.
        vx = clamped[0] - x;
        vy = clamped[1] - y;
        vz = clamped[2] - z;
        x = clamped[0];
        y = clamped[1];
        z = clamped[2];
    }

    private static boolean solidAt(ClientLevel level, double px, double py, double pz) {
        BlockPos pos = BlockPos.containing(px, py, pz);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * A click while freecam is on, after vanilla's was cancelled. Build freecam hands it to the handler
     * the stick uses, at the camera's target (sneak - the "down" key here - plus right-click in the air
     * clears). Cinematic freecam does nothing at all.
     */
    public static void click(boolean primary) {
        if (!FreecamRules.clickSelects(mode) || !BuildInput.active()) {
            return;
        }
        BuildTargeting.Target target = BuildTargeting.target();
        MagicStickInput.click(primary, target == null ? null : target.pos(), down);
    }

    /** Mouse movement, handed over instead of turning the player. Same scale as vanilla's turn. */
    public static void turn(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = Math.max(-90f, Math.min(90f, pitch + (float) (dy * 0.15)));
    }

    /** The camera position for this frame. */
    public static Vec3 position(float partialTick) {
        return new Vec3(prevX + (x - prevX) * partialTick, prevY + (y - prevY) * partialTick,
                prevZ + (z - prevZ) * partialTick);
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /** The unit vector the camera looks along. */
    public static Vec3 look() {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    /** Whether the renderer leaves {@code entity} out of the picture right now. */
    public static boolean hidesEntity(Entity entity) {
        Mode which = mode;
        if (which == null) {
            return false;
        }
        return FreecamRules.hidesEntity(true, BuildGate.singleplayer(), limits(which).hideEntities(),
                entity == Minecraft.getInstance().player);
    }

    /** Whether the whole HUD is hidden: cinematic freecam with "Hide HUD" on. */
    public static boolean hidesHud() {
        return FreecamRules.hidesHud(mode, cineCfg().hideHud);
    }

    /** Whether SBS world markers go with it ("Show World Markers" off). */
    public static boolean hidesWorldMarkers() {
        return FreecamRules.hidesWorldMarkers(mode, cineCfg().hideHud, cineCfg().showWorldMarkers);
    }

    /** Whether the block the camera sits in is solid - the renderer then turns off occlusion culling. */
    public static boolean cameraInSolid() {
        ClientLevel level = Minecraft.getInstance().level;
        if (mode == null || level == null) {
            return false;
        }
        BlockState state = level.getBlockState(BlockPos.containing(x, y, z));
        return state.isSolidRender();
    }

    /** A ray from the camera, for the selection. */
    static CameraRay.Hit ray(int depth) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        Vec3 look = look();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return CameraRay.cast(x, y, z, look.x, look.y, look.z, Math.max(8, Math.min(128, buildCfg().rayDistance)), depth,
                (bx, by, bz) -> {
                    pos.set(bx, by, bz);
                    return !level.getBlockState(pos).getShape(level, pos).isEmpty();
                });
    }

    /** The on-screen indicator's text this frame, or {@code null} when it is not shown. */
    public static String indicator() {
        Mode which = mode;
        if (!FreecamRules.indicatorShown(which, cineCfg().hideHud, System.currentTimeMillis() - startedAt)) {
            return null;
        }
        String line = String.format(Locale.ROOT, "Freecam: %s  •  %.1f, %.1f, %.1f", which.label(), x, y, z);
        if (System.currentTimeMillis() < speedShownUntil) {
            line += "  •  speed x" + trim(speedFactor());
        }
        return line;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
