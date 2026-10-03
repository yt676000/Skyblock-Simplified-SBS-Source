/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

/**
 * The transform between world X/Z and pixels on a map canvas: scale, the world point at the
 * canvas centre, and an optional rotation about that centre.
 *
 * <p>North (-Z) is up and east (+X) is right when the rotation is zero, which is how every SkyBlock
 * map is drawn. The rotation is in screen terms - positive turns clockwise on screen, because screen
 * Y grows downward - and matches what {@code Matrix3x2f.rotate} does to the pose, so a canvas drawn
 * inside a rotated pose and a point placed with {@link #toScreen} agree.
 *
 * <p>Plain arithmetic with no game types, so it is unit-tested directly.
 */
public final class MapViewport {

    private final double minX;
    private final double minZ;
    private final double maxX;
    private final double maxZ;

    private double canvasX;
    private double canvasY;
    private double canvasW = 1;
    private double canvasH = 1;

    /** Pixels per block. */
    private double scale = 1;

    private double centerX;
    private double centerZ;

    /** Radians, see the class note for the direction. */
    private double rotation;

    public MapViewport(double minX, double minZ, double maxX, double maxZ) {
        this.minX = Math.min(minX, maxX);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxZ = Math.max(minZ, maxZ);
        this.centerX = (this.minX + this.maxX) / 2.0;
        this.centerZ = (this.minZ + this.maxZ) / 2.0;
    }

    public void setCanvas(double x, double y, double w, double h) {
        canvasX = x;
        canvasY = y;
        canvasW = Math.max(1, w);
        canvasH = Math.max(1, h);
    }

    /** The scale at which the whole bounds just fit the canvas. */
    public double fitScale() {
        return Math.min(canvasW / Math.max(1, maxX - minX), canvasH / Math.max(1, maxZ - minZ));
    }

    public double scale() {
        return scale;
    }

    public void setScale(double pixelsPerBlock) {
        scale = Math.max(1e-6, pixelsPerBlock);
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public void setCenter(double worldX, double worldZ) {
        centerX = worldX;
        centerZ = worldZ;
    }

    public double rotation() {
        return rotation;
    }

    public void setRotation(double radians) {
        rotation = radians;
    }

    public double canvasW() {
        return canvasW;
    }

    public double canvasH() {
        return canvasH;
    }

    public double canvasCenterX() {
        return canvasX + canvasW / 2.0;
    }

    public double canvasCenterY() {
        return canvasY + canvasH / 2.0;
    }

    /** World X/Z to screen pixels, {@code {x, y}}. */
    public double[] toScreen(double worldX, double worldZ) {
        double dx = (worldX - centerX) * scale;
        double dy = (worldZ - centerZ) * scale;
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        return new double[]{
                canvasCenterX() + dx * cos - dy * sin,
                canvasCenterY() + dx * sin + dy * cos};
    }

    /** Screen pixels to world X/Z, {@code {x, z}} - the inverse of {@link #toScreen}. */
    public double[] toWorld(double screenX, double screenY) {
        double dx = screenX - canvasCenterX();
        double dy = screenY - canvasCenterY();
        double cos = Math.cos(-rotation);
        double sin = Math.sin(-rotation);
        return new double[]{
                centerX + (dx * cos - dy * sin) / scale,
                centerZ + (dx * sin + dy * cos) / scale};
    }

    /**
     * Multiplies the scale by {@code factor}, clamped to {@code [minScale, maxScale]}, keeping the
     * world point under {@code (screenX, screenY)} where it is - so what you zoom toward stays under
     * the pointer instead of sliding away. Then re-clamps the centre.
     */
    public void zoomAt(double factor, double screenX, double screenY, double minScale, double maxScale) {
        double[] before = toWorld(screenX, screenY);
        scale = Math.max(minScale, Math.min(maxScale, scale * factor));
        double[] after = toWorld(screenX, screenY);
        centerX += before[0] - after[0];
        centerZ += before[1] - after[1];
        clampCenter();
    }

    /** Moves the view by a mouse drag of {@code (dx, dy)} pixels: the map follows the pointer. */
    public void panBy(double dx, double dy) {
        double cos = Math.cos(-rotation);
        double sin = Math.sin(-rotation);
        centerX -= (dx * cos - dy * sin) / scale;
        centerZ -= (dx * sin + dy * cos) / scale;
        clampCenter();
    }

    /** Keeps the centre inside the bounds, so the map can never be dragged out of sight. */
    public void clampCenter() {
        centerX = Math.max(minX, Math.min(maxX, centerX));
        centerZ = Math.max(minZ, Math.min(maxZ, centerZ));
    }

    /**
     * The rotation that puts the direction a player is facing straight up the screen - the
     * "turn with me" minimap. Minecraft yaw: 0 faces south (+Z), 90 west (-X), 180 north.
     */
    public static double rotationFacingUp(double yawDegrees) {
        double[] facing = facing(yawDegrees);
        return -Math.PI / 2 - Math.atan2(facing[1], facing[0]);
    }

    /** The unit vector a yaw points along on an unrotated map, {@code {x, y}} with +y = south. */
    public static double[] facing(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(yaw), Math.cos(yaw)};
    }

    /** The on-screen unit direction of a yaw under this viewport's rotation, {@code {x, y}}. */
    public double[] screenDirection(double yawDegrees) {
        double[] f = facing(yawDegrees);
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        return new double[]{f[0] * cos - f[1] * sin, f[0] * sin + f[1] * cos};
    }
}
