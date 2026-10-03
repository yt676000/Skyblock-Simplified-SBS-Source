/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.diana.devlog.DevLogEvent;
import sbs.modid.client.combat.diana.devlog.DevLogEvents;
import sbs.modid.client.combat.diana.devlog.DevLogText;
import sbs.modid.client.combat.diana.devlog.DianaDevLog;
import sbs.modid.client.combat.diana.render.BurrowMarkers;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The fence between the Diana toolkit and the game: every hook the toolkit has into vanilla goes
 * through here, and nothing the toolkit throws gets past it.
 *
 * <h2>Why</h2>
 *
 * <p>A tester reported the game crashing when the first burrow of a chain was dug (2026-09-30). That
 * code runs inside the chat packet handler, the block-interaction call, the particle packet handler,
 * the client tick, the HUD pass and the chat screen's click handler - all of them vanilla code that
 * does not expect a mod to throw, and an exception in any of them takes the client down. A
 * treasure-hunting helper is never worth that. Nothing here ever re-throws.
 *
 * <h2>What a failure does</h2>
 *
 * <ol>
 *   <li>The <b>whole tracker</b> is disabled for the session - not saved to the config, so a restart
 *   or {@code /sbs devlog diana rearm} brings it back. Every hook shares one state (burrows, guesses,
 *   chains), so a throw half-way through an update leaves state the other hooks would go on drawing
 *   from with full confidence; stopping only the hook that threw (this guard's first version) was
 *   the less safe of the two.</li>
 *   <li>An {@code error} report is built: the hook, its input, the exception with its full stack and
 *   causes, a snapshot of every tracker class ({@link DianaSnapshot}) and the last inputs the toolkit
 *   was handed ({@link DianaRecent}, which runs whether or not anything is recording). With Diana Log
 *   Mode on it goes into the capture; with it off it is written alone as
 *   {@code config/sbs/probe/diana/diana-crash-<stamp>.json}.</li>
 *   <li>The toolkit's world markers are taken down: only the tick and the world-change reset remove
 *   them, and both are skipped from now on.</li>
 *   <li>One {@code [SBS][Diana]} line in the game log with the stack, and one chat line naming the
 *   file and the command that turns the tracker back on - sent on the next tick rather than from
 *   inside the hook, which may be the chat handler itself.</li>
 * </ol>
 *
 * <h2>What it catches</h2>
 *
 * <p>{@link RuntimeException}, {@link LinkageError} (a missing class or method after a bad mapping)
 * and {@link StackOverflowError}. The last is a {@code VirtualMachineError}, and the others of that
 * family - {@link OutOfMemoryError}, {@link InternalError} - are deliberately let through: they mean
 * the VM is in trouble, and nothing a mod does next can be trusted. A stack overflow in a Diana hook
 * is different: it is a recursion bug in that hook, the stack has fully unwound by the time it reaches
 * here, and letting it through would crash the game over a helper. That exception to "never catch a
 * VirtualMachineError" is recorded in {@code docs/features/diana-log-mode.md}.
 */
public final class DianaGuard {

    /** Each place the toolkit is called from vanilla code. */
    public enum Hook {
        CHAT, CHAT_HIDE, DIG, PARTICLES, ABILITY, TICK, HUD, RESET, SPHINX_CLICK
    }

    /** What tripped the guard, and where the report went. */
    public record Failure(Hook hook, String input, String exception, long at, String savedTo) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .serializeNulls().create();

    /** True from the first failure until {@link #clear}. Read first by every hook. */
    private static volatile boolean disabled;

    private static volatile Failure failure;

    /** The last report built, kept for tests and for {@code status}. */
    private static volatile JsonObject lastReport;

    /** The chat line owed to the player, sent by the next {@link #onClientTick}. */
    private static volatile String pendingChat;

    /**
     * One call of a timed hook longer than this is a visible hitch, logged as a slow hook. Three
     * frames at 60 fps; nothing the toolkit does per packet or per tick has any business near it.
     */
    public static final long SLOW_HOOK_MS = 50L;

    /** Which hooks are timed: the two the arrow guess runs inside. */
    private static final Set<Hook> TIMED = EnumSet.of(Hook.PARTICLES, Hook.TICK);

    /** Slow calls of a timed hook this session. */
    private static int slowHooks;

    /** True from the second slow hook until {@link #clear}. Read by the arrow guess. */
    private static volatile boolean arrowGuessDown;

    private DianaGuard() {
    }

    /** Runs {@code body} unless the tracker is disabled; a throw disables it and is reported once. */
    public static void run(Hook hook, Object input, Runnable body) {
        if (disabled) {
            return;
        }
        DianaRecent.record(hook, input);
        long started = System.nanoTime();
        try {
            body.run();
        } catch (RuntimeException | LinkageError | StackOverflowError thrown) {
            trip(hook, input, thrown);
            return;
        }
        timed(hook, input, started);
    }

    /** {@link #run} for a hook that answers: {@code fallback} while disabled or when it throws. */
    public static <T> T call(Hook hook, Object input, Supplier<T> body, T fallback) {
        if (disabled) {
            return fallback;
        }
        DianaRecent.record(hook, input);
        long started = System.nanoTime();
        T answer;
        try {
            answer = body.get();
        } catch (RuntimeException | LinkageError | StackOverflowError thrown) {
            trip(hook, input, thrown);
            return fallback;
        }
        timed(hook, input, started);
        return answer;
    }

    /** Whether the arrow guess has been stopped for the session by repeated slow hooks. */
    public static boolean arrowGuessDown() {
        return arrowGuessDown;
    }

    /** Whether the tracker has been disabled by a failure this session. */
    public static boolean disabled() {
        return disabled;
    }

    /**
     * Whether this hook is standing down. Every hook stands down together, so this is
     * {@link #disabled()} for any hook; kept per hook for the callers that ask that way.
     */
    public static boolean down(Hook hook) {
        return disabled;
    }

    /** One line for {@code /sbs diana} and the log's status; empty while the tracker is running. */
    public static String status() {
        Failure current = failure;
        if (!disabled) {
            return "";
        }
        if (current == null) {
            return "disabled after an error";
        }
        return "disabled after the " + current.hook() + " hook threw " + current.exception()
                + (current.savedTo() == null ? "" : " - report: " + current.savedTo());
    }

    /** The guard's state as JSON, for Diana Log Mode's {@code session_start}. */
    public static JsonObject state() {
        JsonObject out = new JsonObject();
        Failure current = failure;
        out.addProperty("disabled", disabled);
        out.addProperty("hook", current == null ? null : current.hook().name());
        out.addProperty("exception", current == null ? null : current.exception());
        out.addProperty("at", current == null ? null : current.at());
        out.addProperty("savedTo", current == null ? null : current.savedTo());
        out.addProperty("slowHooks", slowHooks);
        out.addProperty("arrowGuessDown", arrowGuessDown);
        return out;
    }

    /** The failure that disabled the tracker, or {@code null}. */
    public static Failure failure() {
        return failure;
    }

    /** The last error report built, as the JSON object a capture line holds, or {@code null}. */
    public static JsonObject lastReport() {
        return lastReport;
    }

    /**
     * Re-enables the tracker: {@code /sbs devlog diana rearm}, {@code /sbs diana clear}, and tests.
     * The next failure is reported again from scratch.
     */
    public static synchronized void clear() {
        disabled = false;
        failure = null;
        lastReport = null;
        pendingChat = null;
        slowHooks = 0;
        arrowGuessDown = false;
    }

    /** Sends the chat line a failure left owed. Called every client tick; one volatile read otherwise. */
    public static void onClientTick() {
        String message = pendingChat;
        if (message == null || Minecraft.getInstance().player == null) {
            return;
        }
        pendingChat = null;
        try {
            SBSChat.send(Component.literal(" " + message));
        } catch (RuntimeException | LinkageError e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Diana] could not show the guard's chat line", e);
        }
    }

    // ------------------------------------------------------------------
    // The hitch
    // ------------------------------------------------------------------

    /**
     * A hook that returns, but late.
     *
     * <p>The catch above only sees throws. The freeze in 1.0.0-beta.10 threw nothing: the arrow fit
     * on the particle hook got slower with every packet until the client stopped drawing frames. So
     * the two hooks the arrow guess runs in are timed too. One slow call is logged with the toolkit
     * snapshot - a first call can be slow for reasons that are not ours, like class loading. A second
     * one stops the arrow guess for the session, the suspect, as the error path stops the whole
     * tracker: not saved, {@code /sbs devlog diana rearm} brings it back. Display-only, like
     * everything here: the player gets a local chat line and nothing is sent anywhere.
     */
    private static void timed(Hook hook, Object input, long started) {
        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (ms < SLOW_HOOK_MS || !TIMED.contains(hook)) {
            return;
        }
        boolean stop;
        synchronized (DianaGuard.class) {
            slowHooks++;
            stop = slowHooks >= 2 && !arrowGuessDown;
            if (stop) {
                arrowGuessDown = true;
            }
        }
        String snapshot;
        try {
            snapshot = DianaSnapshot.capture().toString();
        } catch (RuntimeException | LinkageError | StackOverflowError snapshotFailure) {
            snapshot = "<snapshot failed: " + snapshotFailure + ">";
        }
        try {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Diana] slow hook {} {} ms on input [{}]{} - snapshot: {}",
                    hook, ms, safeDescribe(input),
                    stop ? " - second slow hook, the arrow guess is off for this session" : "", snapshot);
        } catch (RuntimeException | LinkageError ignored) {
            // No logger (unit tests without one): the flag above is what matters.
        }
        if (stop) {
            pendingChat = "§eArrow guess paused after the game hitched §7— §f/sbs devlog diana rearm";
        }
    }

    // ------------------------------------------------------------------
    // The failure
    // ------------------------------------------------------------------

    private static void trip(Hook hook, Object input, Throwable thrown) {
        synchronized (DianaGuard.class) {
            if (disabled) {
                return;
            }
            disabled = true;
        }
        String described = safeDescribe(input);
        String savedTo = null;
        try {
            JsonObject snapshot = DianaSnapshot.capture();
            JsonArray recent = DianaRecent.dump(DianaGuard::describe);
            DevLogEvent event = DevLogEvents.error(DianaDevLog.stamp(), "guard", hook.name(), described, thrown,
                    snapshot, recent);
            lastReport = DevLogEvents.toJson(0, event);
            if (DianaDevLog.enabled) {
                DianaDevLog.submit(event);
                savedTo = String.valueOf(DianaDevLog.file());
            } else {
                savedTo = writeCrashFile(event);
            }
        } catch (RuntimeException | LinkageError | StackOverflowError reportFailure) {
            log("[SBS][Diana] building the error report failed", reportFailure);
        }
        // Take the world markers down. Only the tick and the world-change reset ever remove them, and
        // both are skipped from here on, so without this the last burrows and guesses would stay drawn
        // - and routable - for the rest of the session, across every lobby. After the report, so the
        // report still describes what was on screen.
        try {
            BurrowMarkers.clear();
        } catch (RuntimeException | LinkageError | StackOverflowError clearFailure) {
            log("[SBS][Diana] removing the Diana markers failed", clearFailure);
        }
        failure = new Failure(hook, described, thrown.toString(), System.currentTimeMillis(), savedTo);
        String where = savedTo == null ? "the game log" : savedTo;
        try {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] the {} hook threw on input [{}] - the Diana tracker "
                            + "is off for the rest of this session so the game keeps running. Report: {}. "
                            + "/sbs devlog diana rearm turns it back on.", hook, described, where, thrown);
        } catch (RuntimeException | LinkageError ignored) {
            // No logger (unit tests without one): the disable and the report above are what matter.
        }
        pendingChat = "§cDiana tracker disabled after an error §7— log saved to §f" + where
                + "§7, §f/sbs devlog diana rearm";
    }

    /**
     * Writes the report on its own, for a failure with no capture running. The text is built here, on
     * the client thread; the write goes to the shared IO thread. Returns the path, or {@code null} when
     * there is nowhere to write (no game directory - the unit-test case).
     */
    private static String writeCrashFile(DevLogEvent event) {
        Path dir;
        try {
            dir = SBSFiles.dianaLogDir();
        } catch (RuntimeException e) {
            return null;
        }
        String base = "diana-crash-" + LocalDateTime.now().format(STAMP);
        Path file = dir.resolve(base + ".json");
        for (int i = 2; Files.exists(file) && i < 100; i++) {
            file = dir.resolve(base + "-" + i + ".json");
        }
        String text = PRETTY.toJson(DevLogEvents.toJson(1, event));
        Path target = file;
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(target, text, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                log("[SBS][Diana] writing the error report " + target + " failed", e);
            }
        });
        return target.toString();
    }

    private static String safeDescribe(Object input) {
        try {
            return describe(input);
        } catch (RuntimeException | LinkageError e) {
            return "<unreadable input: " + e + ">";
        }
    }

    /** A hook's input as text: a chat line as itself, a block as {@code x y z}, a particle by its fields. */
    static String describe(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof String text) {
            return DevLogText.cap(text);
        }
        if (input instanceof BlockPos pos) {
            return DianaSnapshot.pos(pos);
        }
        if (input instanceof ClientboundLevelParticlesPacket packet) {
            String type = packet.getParticle() == null ? "null"
                    : String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType()));
            return String.format(Locale.ROOT, "%s count=%d speed=%.4f offset=%.4f,%.4f,%.4f at %.3f,%.3f,%.3f",
                    type, packet.getCount(), packet.getMaxSpeed(), packet.getXDist(), packet.getYDist(),
                    packet.getZDist(), packet.getX(), packet.getY(), packet.getZ());
        }
        if (input instanceof Enum<?> constant) {
            return constant.name();
        }
        return DevLogText.cap(String.valueOf(input));
    }

    private static void log(String message, Throwable failure) {
        try {
            SkyblockSimplifiedSBS.LOGGER.error(message, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // No logger: nothing further to report through.
        }
    }
}
