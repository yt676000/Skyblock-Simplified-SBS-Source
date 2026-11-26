/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.dev.scanner.InstanceRecorder;
import sbs.modid.client.core.dev.scanner.ScanPreset;
import sbs.modid.client.core.dev.scanner.ServerScanner;

/**
 * The Kuudra side of the Server Scanner's auto-record ("Auto-record Kuudra", developer mode): turns
 * what the Kuudra trackers already know - run start, phase changes, crates, fresh, the end and the
 * reward chests - into one {@code kuudra_run} session per run with typed marks. See
 * {@code docs/features/server-scanner.md}, "Kuudra run preset".
 *
 * <p>The decisions are {@link InstanceRecorder}'s; this class only maps Kuudra onto it, so it is
 * testable with a fake sink. Every call is a no-op unless a session this recorder started is live.
 *
 * <p>Other players are never written by name: a mark says {@code "self"} or {@code "player"}.
 */
public final class KuudraScan {

    /** After KUUDRA DOWN: how long to wait for the reward chest before stopping anyway. */
    static final long DOWN_TAIL_MS = 60_000L;
    /** After a reward block: a little longer, for the second chest's block. */
    static final long CHEST_GRACE_MS = 5_000L;
    /** After DEFEAT: the title and the last entity removals. */
    static final long DEFEAT_TAIL_MS = 3_000L;

    /** Lazy, so a test building its own instance never loads the scanner. */
    private static final class Holder {
        static final KuudraScan INSTANCE = new KuudraScan(
                new InstanceRecorder(ServerScanner.sink(), ScanPreset.KUUDRA_RUN, "kuudra"));
    }

    private final InstanceRecorder recorder;

    KuudraScan(InstanceRecorder recorder) {
        this.recorder = recorder;
    }

    public static KuudraScan getInstance() {
        return Holder.INSTANCE;
    }

    InstanceRecorder recorder() {
        return recorder;
    }

    /** Whether auto-record is wanted at all. One field read when off. */
    static boolean wanted() {
        // DEV-ONLY: the Server Scanner is a developer tool; auto-record only gates a debug recording.
        return DevMode.ACTIVE &&ConfigManager.getInstance().get().dev.scannerAutoKuudra;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Elle called the supplies: a run started. */
    void onRunStart(boolean enabled, boolean inHollow, int tier, long now) {
        JsonObject info = new JsonObject();
        info.addProperty("tier", tier);
        info.addProperty("startedMs", now);
        recorder.onRunStart(enabled, inHollow, info);
    }

    void onPhase(KuudraPhase to, String why, long now) {
        if (!to.inRun()) {
            return;
        }
        JsonObject fields = new JsonObject();
        fields.addProperty("why", why);
        recorder.onPhase(to.name(), fields);
        if (to == KuudraPhase.DOWN) {
            recorder.onRunEnd("completed", DOWN_TAIL_MS, now);
        }
    }

    /** The tracker ended the run: DEFEAT keeps a short tail, anything else (left, transfer) stops now. */
    void onRunEnded(String why, long now) {
        if ("defeat".equals(why)) {
            recorder.onRunEnd("defeat", DEFEAT_TAIL_MS, now);
        } else {
            recorder.onLeave(why);
        }
    }

    /** A reward chest's block arrived: what the end tail was waiting for. */
    public void onRewardChest(boolean paid, int items, long now) {
        JsonObject fields = new JsonObject();
        fields.addProperty("chest", paid ? "PAID" : "FREE");
        fields.addProperty("items", items);
        recorder.onEvent("reward_chest", fields);
        recorder.onTailSeen(CHEST_GRACE_MS, now);
    }

    public void tick(long now) {
        recorder.tick(now);
    }

    // ------------------------------------------------------------------ sub-events

    /** A typed sub-event; {@code kv} alternates field name and value. */
    public void event(String type, Object... kv) {
        if (recorder.state() == InstanceRecorder.State.IDLE) {
            return;   // nothing allocated while no Kuudra session runs
        }
        JsonObject fields = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object value = kv[i + 1];
            String key = String.valueOf(kv[i]);
            if (value instanceof Number number) {
                fields.addProperty(key, number);
            } else if (value instanceof Boolean flag) {
                fields.addProperty(key, flag);
            } else {
                fields.addProperty(key, value == null ? null : String.valueOf(value));
            }
        }
        recorder.onEvent(type, fields);
    }

    /** {@code "self"} for the player, {@code "player"} for anyone else - names are not recorded. */
    static String who(String name) {
        var player = Minecraft.getInstance().player;
        return player != null && player.getGameProfile().name().equalsIgnoreCase(name) ? "self" : "player";
    }
}
