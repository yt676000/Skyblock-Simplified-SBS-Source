/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The click path that led to the open screen: a root action (an NPC, an entity, a block, a typed
 * command, a held item) followed by every menu click that replaced one menu with the next. Pure -
 * time is passed in - so the chaining rules are unit-tested.
 *
 * <p>Rules:
 * <ul>
 *   <li>A root action starts a new chain.</li>
 *   <li>A menu click appends a step (at most {@value #MAX_DEPTH} steps in all).</li>
 *   <li>A screen that opens within {@value #OPEN_WINDOW_MS} ms of the last action was opened by
 *       it. One that opens with no action that recent was pushed by the server: when no menu is
 *       open the chain restarts with an {@code unknown} root.</li>
 *   <li>Closing every screen ends the chain only after {@value #CLOSE_RESET_MS} ms: Hypixel closes
 *       and reopens between some pages, and that short gap must not break the path.</li>
 *   <li>A chat line arriving while the chain is alive is attached to its last step.</li>
 * </ul>
 * Steps are JSON objects so they go into the layout files unchanged.
 */
public final class OpenerPath {

    static final int MAX_DEPTH = 8;
    static final long OPEN_WINDOW_MS = 2_000L;
    static final long CLOSE_RESET_MS = 1_000L;

    private final List<JsonObject> steps = new ArrayList<>();
    private long lastActionAt = Long.MIN_VALUE / 2;
    private long closedSince = -1;
    /** Whether the last action has already been consumed by a screen opening. */
    private boolean actionUsed = true;

    /** A root action: starts a fresh chain. */
    public void root(JsonObject step, long now) {
        steps.clear();
        steps.add(step);
        lastActionAt = now;
        actionUsed = false;
        closedSince = -1;
    }

    /** A click inside an open menu; becomes a step if it opens the next screen. */
    public void menuClick(JsonObject step, long now) {
        if (steps.isEmpty()) {
            steps.add(unknownRoot());
        }
        if (steps.size() < MAX_DEPTH) {
            steps.add(step);
        }
        lastActionAt = now;
        actionUsed = false;
    }

    /**
     * A menu click that did not open anything (the same menu stayed): the step it added is taken
     * back, so only clicks that changed the screen remain in the path.
     */
    public void menuClickStayed() {
        if (steps.size() > 1 && "menu".equals(stepVia(steps.getLast())) && !actionUsed) {
            steps.removeLast();
            actionUsed = true;
        }
    }

    /** A new screen appeared. Returns the path that opened it (a copy). */
    public JsonArray screenOpened(long now) {
        closedSince = -1;
        boolean caused = !actionUsed && now - lastActionAt <= OPEN_WINDOW_MS;
        if (!caused && steps.isEmpty()) {
            steps.add(unknownRoot());
        } else if (!caused && !"menu".equals(stepVia(steps.getLast()))) {
            // Nothing the player did opened it, and no menu chain is running: server-pushed.
            steps.clear();
            steps.add(unknownRoot());
        }
        actionUsed = true;
        return snapshot();
    }

    /** Called every tick with whether any screen is open. */
    public void screenState(boolean anyOpen, long now) {
        if (anyOpen) {
            closedSince = -1;
            return;
        }
        if (closedSince < 0) {
            closedSince = now;
        } else if (now - closedSince > CLOSE_RESET_MS && actionUsed) {
            steps.clear();
        }
    }

    /** A chat line (already stripped and redacted) while the chain is alive. */
    public void chat(String line, long now) {
        if (steps.isEmpty() || line == null || line.isBlank()) {
            return;
        }
        JsonObject last = steps.getLast();
        JsonArray chat = last.has("chat") ? last.getAsJsonArray("chat") : new JsonArray();
        if (chat.size() < 4) {
            chat.add(line);
        }
        last.add("chat", chat);
    }

    /** The current path, copied. */
    public JsonArray snapshot() {
        JsonArray out = new JsonArray();
        steps.forEach(step -> out.add(step.deepCopy()));
        return out;
    }

    public int depth() {
        return steps.size();
    }

    private static JsonObject unknownRoot() {
        JsonObject step = new JsonObject();
        step.addProperty("via", "unknown");
        return step;
    }

    private static String stepVia(JsonObject step) {
        return step.has("via") ? step.get("via").getAsString() : "";
    }
}
