/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * Everything the Diana toolkit currently believes, as one JSON object - written into the guard's
 * error report so a crash can be read against the state that produced it.
 *
 * <p>Java cannot capture a failed method's locals, so the state has to be offered: each tracker class
 * answers {@code snapshot()} with its own fields, and this asks each one. Every part is asked inside
 * its own try/catch, because the moment this runs is the moment something in that state has just
 * thrown, and a snapshot that throws in turn must cost its own section, not the report.
 *
 * <p>Read-only by contract: no part may prune, reset or otherwise change what it reports.
 */
public final class DianaSnapshot {

    /** Most entries any one list in a snapshot carries; past it the list says how many it left out. */
    static final int MAX_ENTRIES = 200;

    private DianaSnapshot() {
    }

    /** The whole toolkit. Called on the client thread, by the guard. */
    public static JsonObject capture() {
        JsonObject out = new JsonObject();
        part(out, "event", () -> {
            JsonObject event = new JsonObject();
            event.addProperty("state", DianaEvent.state().name());
            event.addProperty("heldSpadeTier", String.valueOf(DianaEvent.heldSpadeTier()));
            return event;
        });
        part(out, "detector", () -> BurrowDetector.getInstance().snapshot());
        part(out, "burrows", () -> BurrowStore.getInstance().snapshot());
        part(out, "chat", () -> BurrowChat.getInstance().snapshot());
        part(out, "chains", () -> ChainTracker.getInstance().snapshot());
        part(out, "spadeGuess", () -> SpadeGuess.getInstance().snapshot());
        part(out, "arrowGuess", () -> ArrowGuess.getInstance().snapshot());
        part(out, "creatures", () -> MythMobTracker.getInstance().snapshot());
        part(out, "tracker", () -> DianaTracker.getInstance().snapshot());
        return out;
    }

    private static void part(JsonObject into, String name, Supplier<JsonElement> part) {
        try {
            into.add(name, part.get());
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            JsonObject failed = new JsonObject();
            failed.addProperty("snapshotFailed", failure.toString());
            into.add(name, failed);
        }
    }

    /** A block as {@code "x y z"}, or {@code null}. The form every snapshot uses for positions. */
    static String pos(BlockPos pos) {
        return pos == null ? null : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Points as {@code [x, y, z]} triples, capped at {@link #MAX_ENTRIES}. */
    static JsonArray points(List<Vec3> points) {
        JsonArray out = new JsonArray();
        for (Vec3 point : points) {
            if (out.size() >= MAX_ENTRIES) {
                out.add("+" + (points.size() - MAX_ENTRIES) + " more");
                break;
            }
            JsonArray xyz = new JsonArray();
            xyz.add(num(point.x));
            xyz.add(num(point.y));
            xyz.add(num(point.z));
            out.add(xyz);
        }
        return out;
    }

    /**
     * A double for a snapshot, or {@code null} when it is not finite. Gson writes a bare NaN into the
     * report, which is not JSON, and a report that does not parse is the one report that mattered.
     */
    static Double num(double value) {
        return Double.isFinite(value) ? value : null;
    }
}
