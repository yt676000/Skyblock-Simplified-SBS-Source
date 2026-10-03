/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.model.HoneyItems;
import sbs.modid.client.skills.foraging.model.HoneyTimer;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The honey tree cooldowns: which tree was smeared, when, and how long it has left.
 *
 * <p><b>Two signals, each used for the thing it is good at.</b> Neither half can do this alone, and
 * the split is the whole design:
 *
 * <ul>
 *   <li>{@link #onBlockInteract} runs from the {@code MultiPlayerGameMode.useItemOn} hook and
 *       <b>arms</b> a pending smear at the block the player clicked. It is the only signal that
 *       says <i>which tree</i> - a chat line cannot name one of six - and it is
 *       <b>structurally local</b>: {@code MultiPlayerGameMode} is this client's own interaction
 *       controller and takes a {@code LocalPlayer}, so another player's right-click never reaches
 *       it and there is nothing to filter out. It is not proof of anything, though: a click fires
 *       whether the smear worked, was refused for a running cooldown, or was aimed at a rock.</li>
 *   <li>{@link #onChat} <b>confirms</b> it. The chat line is the only signal that says the smear
 *       <i>succeeded</i>, and it is deliberately not trusted to identify anything - it confirms the
 *       armed position, it does not choose one.</li>
 * </ul>
 *
 * <p>Particles and block state were both considered and rejected: a particle is cosmetic, is
 * produced by every nearby player's smear as much as by ours, and is attributable to nobody; a
 * honey tree is built from ordinary logs whose client-side state says nothing about a cooldown.
 *
 * <p><b>The chat wording is a hypothesis and this class is built for that.</b> Neither "lather" nor
 * "smear" is in any dataset here, and {@code HONEYCOMB} is in none either - see {@link HoneyItems}.
 * So the keywords are a tolerant config field, the near-miss path <b>logs the lines it did not
 * understand</b> while a confirmation window is open, and a window that expires unconfirmed can
 * still start the timer (marked as unconfirmed, and displayed as such). Refusing to start without a
 * wording nobody has seen would ship the feature dark, which is exactly the failure
 * {@code docs/issues/skills.md} records for the Attribute Menu.
 *
 * <p><b>Everything persisted is wall clock</b> - see {@link HoneyTimerStore}. Nothing here counts
 * ticks, so a relog, a hop or a crash restores the right remaining time rather than restarting it.
 */
public final class HoneyTreeTimers {

    private static final HoneyTreeTimers INSTANCE = new HoneyTreeTimers();

    /** Cooldowns move on the scale of minutes; checking four times a second is already generous. */
    private static final long CHECK_INTERVAL_MS = 250L;

    /** Silence right after joining a world - the login chat flood would swallow the ping. */
    private static final long JOIN_GRACE_MS = 15_000L;

    /** The shortest gap between two announcements, so a stampede cannot become a wall of text. */
    private static final long ALERT_GAP_MS = 1_500L;

    /** A near-miss is logged at most this often, so a reworded message cannot flood the log. */
    private static final long NEAR_MISS_LOG_MS = 30_000L;

    /** A second click on the same tree inside this window is the off-hand retry, not a new smear. */
    private static final long DUPLICATE_CLICK_MS = 500L;

    /** How many clicks may be waiting for a confirmation at once. */
    private static final int MAX_PENDING = 8;

    /**
     * A line somebody typed in chat: an optional rank tag, a name, a colon.
     *
     * <p>The one case where another player's words could confirm our smear - they type the word
     * "lather" while our window is open. Refused before the keywords are even looked at.
     */
    private static final Pattern PLAYER_CHAT =
            Pattern.compile("^(?:\\[[^]]{1,24}]\\s*)*\\w{1,16}\\s*:\\s");

    /** A click waiting for chat to say whether it worked. */
    private record Pending(String key, String island, int x, int y, int z, String label,
                           boolean dynamic, long armedAt) {
    }

    /** Guarded by {@code this}. Small enough that a list beats a map. */
    private final List<Pending> pending = new ArrayList<>(MAX_PENDING);

    private volatile long lastCheckAt;
    private volatile long inWorldSince;
    private volatile long lastAlertAt;
    private volatile long lastNearMissLogAt;

    /** What the last arm resolved to, for the settings page and {@code /sbs honey}. */
    private volatile String lastArmDescription = "";

    /**
     * The instance name, refreshed on the throttled check rather than asked for when needed.
     *
     * <p>{@link ServerWorldTime#serverName()} walks every tab-widget line and runs a regex on each,
     * and the "is this timer from another server" question is asked once per row per frame while
     * the card is up. Reading it four times a second and comparing a cached string is the same
     * answer for a fraction of the cost - the name cannot change without a world change anyway.
     */
    private volatile String currentServer = "";

    private HoneyTreeTimers() {
    }

    public static HoneyTreeTimers getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.HoneySettings cfg() {
        return ConfigManager.getInstance().get().honey;
    }

    private static HoneyTimerStore store() {
        return HoneyTimerStore.getInstance();
    }

    // ------------------------------------------------------------------ arming

    /**
     * Every block the local player right-clicks, from the shared interaction hook.
     *
     * <p>Gated cheapest-first: a switched-off module costs one boolean read, and the item is only
     * inspected once the island already matched.
     */
    public void onBlockInteract(BlockPos pos, ItemStack held) {
        SBSConfig.HoneySettings cfg = cfg();
        if (!cfg.enabled || pos == null) {
            return;
        }
        if (!SkillIslands.foragingAllowed()) {
            return;
        }
        String matchedBy = HoneyItems.matched(held, cfg.honeycombIds);
        if (matchedBy == null) {
            return;
        }
        String island = SkyBlockLocation.island();
        if (island == null || island.isEmpty()) {
            return;
        }

        List<WaypointPresetData.Group> here = WaypointPresetDatabase.activeHere();
        HoneyTreeCatalog.Match match = HoneyTreeCatalog.snap(here, pos.getX(), pos.getY(),
                pos.getZ(), Math.max(1, cfg.toleranceBlocks));

        Pending armed;
        if (match != null) {
            armed = new Pending(HoneyTimer.presetKey(island, match.pointId()), island,
                    match.x(), match.y(), match.z(), match.name(), false,
                    System.currentTimeMillis());
            lastArmDescription = match.name() + " (" + String.format(Locale.ROOT, "%.1f",
                    match.horizontalDistance()) + "m away)";
        } else {
            // No shipped tree within tolerance. Registering the hit position is what makes the
            // feature work on a tree our coordinates do not know about, and the log line below is
            // what turns that into a data fix instead of a permanent miss.
            armed = new Pending(HoneyTimer.dynamicKey(island, pos.getX(), pos.getY(), pos.getZ()),
                    island, pos.getX(), pos.getY(), pos.getZ(),
                    "Tree at " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), true,
                    System.currentTimeMillis());
            lastArmDescription = "no preset tree within " + cfg.toleranceBlocks + "m - dynamic";
        }

        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Pending existing : pending) {
                if (existing.key().equals(armed.key())
                        && now - existing.armedAt() < DUPLICATE_CLICK_MS) {
                    // One right-click can reach useItemOn once per hand. Two arms for one click
                    // would consume two confirmations and leave the second waiting forever.
                    return;
                }
            }
            if (pending.size() >= MAX_PENDING) {
                pending.remove(0);
            }
            pending.add(armed);
        }

        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Honey] armed at {} {} {} on \"{}\" holding {} -> {}",
                pos.getX(), pos.getY(), pos.getZ(), island, matchedBy, lastArmDescription);
    }

    // ------------------------------------------------------------------ confirming

    /**
     * Every chat line, raw as the funnel hands it over.
     *
     * <p>The cheap gate is that nothing is pending: with no armed click there is no smear to
     * confirm and no near-miss worth reporting, so all but a handful of lines in the game are
     * turned away on one list check.
     */
    public void onChat(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
        }
        if (!cfg().enabled) {
            return;
        }
        String stripped = StyledText.strip(raw).trim();
        if (stripped.isEmpty()) {
            return;
        }
        if (PLAYER_CHAT.matcher(stripped).find()) {
            // Somebody typed it. Never a confirmation, and never worth reporting as a near miss.
            return;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (!matchesWords(lower)) {
            reportNearMiss(stripped);
            return;
        }
        Pending armed;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            // Oldest first: Hypixel answers clicks in the order they were made, so the line that
            // arrives now belongs to the click that has been waiting longest.
            armed = pending.remove(0);
        }
        boolean firstPerson = lower.contains("you");
        if (!firstPerson) {
            // Kept, because refusing would ship the feature dark on a wording nobody has seen - but
            // logged, because "the confirmation had no first-person marker" is exactly the kind of
            // fact that turns into a tightened matcher once somebody has read one.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Honey] confirmation had no first-person marker: \"{}\"", stripped);
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Honey] confirmed by chat: \"{}\"", stripped);
        start(armed, true);
    }

    /** Whether the line carries any of the player's configured keywords. */
    private static boolean matchesWords(String lower) {
        for (String word : words()) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** The keyword list, lower-cased and de-blanked. */
    private static List<String> words() {
        String source = cfg().chatWords;
        if (source == null || source.isBlank()) {
            source = SBSConfig.HoneySettings.DEFAULT_WORDS;
        }
        List<String> out = new ArrayList<>(4);
        for (String part : source.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * A line that arrived while a window was open and was not understood.
     *
     * <p>This is the report that replaces a guess with a fact: whatever Hypixel really says lands
     * here on the first smear, and one look at the log is enough to correct the keyword list -
     * without a new build, because the keywords are a setting.
     */
    private void reportNearMiss(String stripped) {
        long now = System.currentTimeMillis();
        if (now - lastNearMissLogAt < NEAR_MISS_LOG_MS) {
            return;
        }
        lastNearMissLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Honey] unmatched line while awaiting a smear confirmation: \"{}\"", stripped);
    }

    // ------------------------------------------------------------------ starting

    /** Books a smear and replaces whatever this tree had before - which is the reset. */
    private void start(Pending armed, boolean confirmed) {
        SBSConfig.HoneySettings cfg = cfg();
        HoneyTimer timer = new HoneyTimer();
        timer.key = armed.key();
        timer.island = armed.island();
        timer.x = armed.x();
        timer.y = armed.y();
        timer.z = armed.z();
        timer.label = armed.label();
        timer.startedAt = armed.armedAt();
        timer.durationMs = configuredDurationMs(cfg);
        timer.followsConfig = true;
        timer.dynamic = armed.dynamic();
        timer.confirmed = confirmed;
        String server = ServerWorldTime.serverName();
        timer.serverName = server == null ? "" : server;
        store().put(timer);

        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Honey] timer started for {} ({}), {} on {}",
                timer.key, confirmed ? "chat-confirmed" : "unconfirmed",
                HoneyTimer.clock(timer.durationMs), timer.serverName.isEmpty() ? "?" : timer.serverName);

        if (cfg.startMessage) {
            MutableComponent body = Component.literal(" " + displayName(timer) + " smeared  •  ready in ")
                    .withColor(SBSChat.WHITE)
                    .append(Component.literal(HoneyTimer.clock(timer.durationMs))
                            .withColor(0xFFD65A));
            if (!confirmed) {
                body.append(Component.literal("  •  unconfirmed").withColor(0x9AA4B2));
            }
            SBSChat.send(body);
        }
    }

    private static long configuredDurationMs(SBSConfig.HoneySettings cfg) {
        return Math.max(1, cfg.durationMinutes) * 60_000L;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick; does its real work every {@link #CHECK_INTERVAL_MS}. */
    public void onClientTick() {
        SBSConfig.HoneySettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            inWorldSince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (inWorldSince == 0L) {
            inWorldSince = now;
        }
        if (now - lastCheckAt < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckAt = now;

        String server = ServerWorldTime.serverName();
        currentServer = server == null ? "" : server;
        settlePending(cfg, now);
        if (!store().ready()) {
            // The profile is not known yet, so every query would answer from an empty record and
            // "nothing is running" would be indistinguishable from a genuinely empty profile.
            return;
        }
        followConfigDuration(cfg);
        HoneyHologramReader.getInstance().tick(cfg, now);
        store().prune(now, Math.max(1, cfg.pruneAfterMinutes) * 60_000L);
        if (now - inWorldSince >= JOIN_GRACE_MS) {
            notifyDue(cfg, now);
        }
    }

    /** Windows that ran out: started unconfirmed, or dropped, depending on the setting. */
    private void settlePending(SBSConfig.HoneySettings cfg, long now) {
        long window = Math.max(1, cfg.confirmWindowSeconds) * 1000L;
        List<Pending> expired = null;
        synchronized (this) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (now - pending.get(i).armedAt() >= window) {
                    if (expired == null) {
                        expired = new ArrayList<>(2);
                    }
                    expired.add(pending.remove(i));
                }
            }
        }
        if (expired == null) {
            return;
        }
        for (Pending armed : expired) {
            if (cfg.startWithoutConfirmation) {
                start(armed, false);
            } else {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Honey] no confirmation for {} within {}s - dropped",
                        armed.key(), cfg.confirmWindowSeconds);
            }
        }
    }

    /**
     * Keeps assumed durations in step with the setting.
     *
     * <p>A player who learns the real cooldown mid-session changes the number and expects the six
     * timers already running to correct themselves; leaving them on the old assumption would be
     * six wrong readouts that look authoritative. A timer whose duration was <i>read</i> from the
     * world keeps its own - that one is evidence, not an assumption.
     */
    private void followConfigDuration(SBSConfig.HoneySettings cfg) {
        long configured = configuredDurationMs(cfg);
        boolean changed = false;
        for (HoneyTimer timer : store().all()) {
            if (timer.followsConfig && timer.durationMs != configured) {
                timer.durationMs = configured;
                // The run's boundaries moved, so notifications for it have not happened yet.
                timer.notifiedWarn = false;
                timer.notifiedDone = false;
                changed = true;
            }
        }
        if (changed) {
            store().touch();
        }
    }

    // ------------------------------------------------------------------ notification

    /**
     * The pre-warning and the expiry ping.
     *
     * <p><b>Coalesced rather than throttled.</b> Several trees smeared together run out together,
     * and a message each is the spam the request asks to avoid - so everything crossing the same
     * boundary in one check goes out as a single line naming all of it. The per-entry flags make
     * each announcement once-per-run rather than once-per-check; the gap is only a backstop.
     */
    private void notifyDue(SBSConfig.HoneySettings cfg, long now) {
        if (!AlertChannels.any(cfg.notifyChannels)) {
            return;
        }
        if (now - lastAlertAt < ALERT_GAP_MS) {
            return;
        }
        long warnMs = Math.max(0, cfg.preWarningSeconds) * 1000L;
        List<String> ready = new ArrayList<>(2);
        List<String> soon = new ArrayList<>(2);
        boolean changed = false;

        for (HoneyTimer timer : store().all()) {
            if (!timer.valid()) {
                continue;
            }
            if (timer.ready(now)) {
                if (!timer.notifiedDone) {
                    timer.notifiedDone = true;
                    timer.notifiedWarn = true;   // a timer that is already up gets no pre-warning
                    changed = true;
                    ready.add(displayName(timer));
                }
                continue;
            }
            if (warnMs > 0 && !timer.notifiedWarn && timer.remainingMs(now) <= warnMs) {
                timer.notifiedWarn = true;
                changed = true;
                soon.add(displayName(timer));
            }
        }
        if (changed) {
            store().touch();
        }
        if (!ready.isEmpty()) {
            announce(cfg, ready, true);
        }
        if (!soon.isEmpty()) {
            announce(cfg, soon, false);
        }
    }

    private void announce(SBSConfig.HoneySettings cfg, List<String> names, boolean done) {
        lastAlertAt = System.currentTimeMillis();
        Collections.sort(names);
        String list = String.join(", ", names);
        String headline = done
                ? (names.size() == 1 ? "Honey tree ready" : names.size() + " honey trees ready")
                : (names.size() == 1 ? "Honey tree almost ready"
                        : names.size() + " honey trees almost ready");

        if (AlertChannels.has(cfg.notifyChannels, AlertChannel.CHAT)) {
            SBSChat.send(Component.literal(" " + headline + ": ").withColor(SBSChat.WHITE)
                    .append(Component.literal(list).withColor(done ? 0x57D977 : 0xFFD65A)));
        }
        // The chat bit is cleared here: sending it through the alert path as well would print the
        // same announcement twice.
        int mask = cfg.notifyChannels & ~AlertChannel.CHAT.bit();
        if (AlertChannels.any(mask)) {
            Alerts.send(new Alerts.Alert(headline, list,
                    done ? SbsAudio.Tone.CHIME : SbsAudio.Tone.BLIP, null), mask);
        }
    }

    // ------------------------------------------------------------------ world change

    /**
     * A world change: the pending clicks die with the instance they were made on.
     *
     * <p><b>The timers themselves deliberately survive</b>, which is the exception to the
     * repo-wide "reset on world change, server hop and island change" rule and is why it is spelt
     * out here. An entry describes a cooldown on a world object that keeps running while the player
     * is somewhere else; a pending click describes a right-click on the instance just left, and
     * confirming one against a line from the next server would attach it to the wrong smear.
     */
    public void onWorldChange() {
        synchronized (this) {
            pending.clear();
        }
        inWorldSince = 0L;
        // The name belongs to the instance just left. Cleared rather than kept, so that until the
        // next tab widget arrives nothing is claimed to be foreign - unknown must not read as "not
        // from here", which would mark every timer the moment a hop starts.
        currentServer = "";
        store().flushProfile();
    }

    // ------------------------------------------------------------------ queries

    /**
     * Every timer, readiest first.
     *
     * <p>Ready trees sort to the top rather than being filtered out: "which trees are ready again"
     * is the question the feature exists to answer, and an empty list is what hides the card.
     */
    public List<HoneyTimer> sorted() {
        long now = System.currentTimeMillis();
        List<HoneyTimer> out = store().all();
        out.removeIf(timer -> !timer.valid());
        out.sort(Comparator.comparingLong(timer -> timer.remainingMs(now)));
        return out;
    }

    /** The timers to draw right now, with the island gate applied. */
    public List<HoneyTimer> visible() {
        SBSConfig.HoneySettings cfg = cfg();
        if (!cfg.enabled || !store().ready()) {
            return List.of();
        }
        List<HoneyTimer> out = sorted();
        if (cfg.onlyOnHoneyIslands) {
            out.removeIf(timer -> !SkyBlockLocation.onIsland(timer.island));
        }
        return out;
    }

    /**
     * The timers on this island that the waypoint layer may draw, or an empty list.
     *
     * <p>Empty is the fast path and is returned for every reason a sub-label should not appear -
     * the module off, the sub-label switched off, the profile not known yet, no timer here - so the
     * publisher's per-tick pass is one call and a size check while nothing is running.
     */
    public List<HoneyTimer> waypointTimers(String island) {
        SBSConfig.HoneySettings cfg = cfg();
        if (!cfg.enabled || !cfg.showOnWaypoint || island == null || island.isEmpty()
                || !store().ready()) {
            return List.of();
        }
        List<HoneyTimer> out = store().all();
        out.removeIf(timer -> !timer.valid() || !island.equals(timer.island));
        return out;
    }

    /**
     * What a tree's marker should say under its name: {@code {text, colour}}, or {@code null}.
     *
     * <p>The colour <i>is</i> the readout - amber running, green ready, muted for a timer that
     * started without a confirmation or on another instance, both of which are marked in the text
     * as well so the meaning does not depend on being able to tell two colours apart.
     */
    public static String[] subLabel(HoneyTimer timer) {
        if (timer == null || !timer.valid()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String text = HoneyTimer.clock(timer.remainingMs(now));
        boolean uncertain = !timer.confirmed || fromAnotherServer(timer);
        if (!timer.confirmed) {
            text += "?";
        }
        if (fromAnotherServer(timer)) {
            text += "*";
        }
        String colour = uncertain ? "9AA4B2" : (timer.ready(now) ? "57D977" : "FFD65A");
        return new String[] {text, colour};
    }

    /**
     * What to call a timer on screen: the live preset name when its point still resolves, else the
     * label captured when it started.
     *
     * <p>Live-first so correcting a name in the data file corrects every display, without the
     * stored key - the identity - ever depending on display text.
     */
    public static String displayName(HoneyTimer timer) {
        if (timer == null) {
            return "";
        }
        if (!timer.dynamic) {
            String pointId = pointIdOf(timer.key);
            WaypointPresetData.Point point =
                    HoneyTreeCatalog.point(WaypointPresetDatabase.groups(), pointId);
            if (point != null && point.name != null && !point.name.isBlank()) {
                return point.name;
            }
        }
        return timer.label == null || timer.label.isBlank() ? "Honey tree" : timer.label;
    }

    /** The preset point id inside a key, or empty for a dynamic one. */
    private static String pointIdOf(String key) {
        if (key == null) {
            return "";
        }
        int marker = key.indexOf("|preset:");
        return marker < 0 ? "" : key.substring(marker + "|preset:".length());
    }

    /**
     * Whether this timer was started on an instance the player is not on any more.
     *
     * <p>Answered conservatively: unknown either way is {@code false}, because marking a timer
     * foreign on missing evidence would be its own confident wrong claim.
     */
    public static boolean fromAnotherServer(HoneyTimer timer) {
        String here = INSTANCE.currentServer;
        return timer != null && !here.isEmpty()
                && timer.serverName != null && !timer.serverName.isEmpty()
                && !here.equals(timer.serverName);
    }

    // ------------------------------------------------------------------ status, for the UI

    /** How many clicks are waiting for a chat confirmation right now. */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** What the last armed click resolved to, for the settings page. */
    public String lastArm() {
        return lastArmDescription;
    }

    /**
     * Why nothing is on screen, in one line - because every gate here is otherwise a silent no-op,
     * and a feature that draws nothing without saying why is what makes a player think it is broken
     * when it is working exactly as told.
     */
    public String status() {
        SBSConfig.HoneySettings cfg = cfg();
        if (!cfg.enabled) {
            return "off - nothing is watched and nothing is drawn";
        }
        if (!store().ready()) {
            return "waiting for the SkyBlock profile to be known";
        }
        int total = sorted().size();
        int shown = visible().size();
        if (total == 0) {
            return "no timer running - smear a honey tree with Honeycomb to start one";
        }
        if (shown < total) {
            return total + " timer(s) running, " + shown + " on this island";
        }
        return total + " timer(s) running";
    }

    /** {@code /sbs honey held} - what the item in hand is, and whether it would arm. */
    public String describeHeld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return "not in a world";
        }
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.isEmpty()) {
            return "nothing in your main hand";
        }
        String matched = HoneyItems.matched(main, cfg().honeycombIds);
        String id = sbs.modid.client.core.item.SkyblockItem.id(main);
        return "held: \"" + main.getHoverName().getString() + "\", SkyBlock id "
                + (id == null || id.isEmpty() ? "(none)" : id) + " - "
                + (matched == null ? "would NOT arm the timer" : "would arm, by " + matched);
    }
}
