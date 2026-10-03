/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.combat.carry.model.SlayerBoss;
import sbs.modid.client.combat.carry.render.CarryHighlight;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.CarryCounterSettings;
import sbs.modid.client.core.config.SBSConfig.CarryCounterSettings.Carry;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Slayer Carry Counter: point the tracker at the person you are carrying with
 * {@code /sbs trackcarry <boss> <player>}, then every slayer boss you help kill next to them is
 * counted for that person – with a running total on the HUD.
 *
 * <p><b>How a kill is detected.</b> Nothing in chat tells the <i>carrier</i> that the carried
 * player's boss died (the "SLAYER QUEST COMPLETE" line only reaches the quest owner), so detection is
 * done from the world: a slayer mini-boss is recognised by its floating health nametag
 * ({@link SlayerBoss#matchNametag}), its living entity is tracked, and the moment that entity
 * vanishes while its tag was still being read, a kill is booked.
 *
 * <p><b>Whose kill it was</b> is answered two ways, best first:
 * <ol>
 *   <li><b>The owner line.</b> Hypixel writes "Spawned by: &lt;IGN&gt;" into the boss's own nametag
 *       stack, so the boss states who it belongs to. When that line is on the stack it settles the
 *       question outright: the kill goes to the carry of the player it names, whichever carry that
 *       is, and a stranger's boss dying beside you is <i>refused</i> rather than counted – which is
 *       exactly how a busy slayer area used to inflate the total. All that is still asked of the
 *       kill itself is that the boss was part of your fight at some point, so one that dies across
 *       the arena counts just as much as one that dies at your feet.</li>
 *   <li><b>Proximity.</b> Only when no owner line is on the stack does it fall back to the old
 *       guess: a boss that died right next to you (a stand-in for "you assisted") counts for the
 *       active carry.</li>
 * </ol>
 * A short post-count cooldown guards both paths against a boss's own phase change being read as a
 * second kill. If it ever miscounts on a given server the carrier can turn auto-detect off and use
 * {@code /sbs trackcarry +/-}, and the throttled {@code [SBS][Carry]} log lines name the path that
 * claimed each kill so it can be tuned live.
 *
 * <p>Runtime state (the live boss tracks) lives here; the carries themselves and the active target
 * live in {@link CarryCounterSettings} so an in-progress carry survives a relog.
 */
public final class CarryCounter {

    private static final CarryCounter INSTANCE = new CarryCounter();

    /**
     * How often the world scan actually runs. Walking every rendered entity and string-matching
     * every armor-stand nametag 20×/s is pure waste in a Hypixel lobby full of holograms – all the
     * detection windows below are seconds, so a few scans per second see exactly the same things
     * (the Slayer module scans on the same cadence). highlight boxes are still projected every frame.
     */
    private static final long SCAN_INTERVAL_MS = 150L;

    /** Minimum gap between two auto-counted kills – slayer bosses respawn far slower than this. */
    private static final long COUNT_COOLDOWN_MS = 5_000L;
    /** A vanished boss counts only if it was seen, and close, within this window (else it just left). */
    private static final long DEATH_WINDOW_MS = 4_000L;
    /**
     * A pending death is cancelled by a boss of the same type and owner turning up within this of
     * where it vanished. Wide enough for a teleporting Voidgloom, narrow next to two bosses.
     */
    private static final double REAPPEAR_RADIUS = 6.0;
    /** Drop a track whose nametag we have not parsed for this long even though the entity still lives. */
    private static final long STALE_MS = 8_000L;

    /** A relevant player within this many blocks of a boss owns it – the lag-proof highlight proximity gate. */
    private static final double HIGHLIGHT_OWNER_RADIUS_SQ = 8.0 * 8.0;
    /**
     * An ownership hologram within this of a boss's nametag stand belongs to that boss. The stands of
     * one nametag stack sit a fraction of a block apart, so this is generous on purpose – it only has
     * to separate one boss's stack from the next boss's.
     */
    private static final double OWNER_TAG_RADIUS_SQ = 3.0 * 3.0;

    /** The "Slayed by <player>" ownership hologram floating over a slayer boss (from the sidebar name). */
    private static final Pattern SLAYED_BY = Pattern.compile(
            "Slayed by\\s+([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);

    /**
     * The owner line Hypixel writes into every slayer boss's nametag stack – the middle of the three
     * stacked stands, e.g. {@code "Spawned by: Steve"}. It names the player whose quest spawned the
     * boss, which is the one piece of hard evidence for "whose boss is this".
     */
    private static final Pattern SPAWNED_BY = Pattern.compile(
            "Spawned by:\\s+([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** Live boss tracks, keyed by the boss living-entity id. Runtime only – never persisted. */
    private final Map<Integer, Track> tracks = new java.util.HashMap<>();

    /** Boss-highlight box snapshot rebuilt each tick, drawn by {@link CarryHighlight} every frame. Runtime only. */
    private volatile List<CarryHighlight.Box> highlightBoxes = List.of();

    /** Holds every vanish until it has stayed vanished long enough to be a kill. */
    private final CarryKillJudge judge = new CarryKillJudge(3_000L, COUNT_COOLDOWN_MS, REAPPEAR_RADIUS);
    private long lastDebugAt;
    private long lastScanAt;
    /** Throttle for the "which path claimed this kill" line. */
    private long lastClaimLogAt;
    /** Throttle for the "that boss belonged to nobody tracked" line – the noisy one in a busy area. */
    private long lastForeignLogAt;

    private CarryCounter() {
    }

    public static CarryCounter getInstance() {
        return INSTANCE;
    }

    private static CarryCounterSettings cfg() {
        return ConfigManager.getInstance().get().carryCounter;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------ commands

    /**
     * Entry point for {@code /sbs trackcarry <args>} (also reachable as {@code /sbs carry}). All
     * feedback is printed client-side via {@link SBSChat}; nothing is sent to the server.
     */
    public void handleCommand(String rawArgs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!cfg().enabled) {
            SBSChat.send("Slayer Carry Counter is disabled (Quality of Life module).");
            return;
        }
        String args = rawArgs == null ? "" : rawArgs.trim();
        if (args.isEmpty()) {
            printStatus();
            return;
        }
        String[] t = args.split("\\s+");
        switch (t[0].toLowerCase(Locale.ROOT)) {
            case "+": case "add": case "up":
                adjustActive(+1);
                return;
            case "-": case "sub": case "down": case "remove":
                adjustActive(-1);
                return;
            case "list": case "ls":
                printList();
                return;
            case "clear": case "reset":
                clearAll();
                return;
            case "auto":
                cfg().autoDetect = !cfg().autoDetect;
                save();
                SBSChat.send("Carry auto-detect " + (cfg().autoDetect ? "on." : "off (manual +/- only)."));
                return;
            case "done": case "finish": case "stop":
                finish(t.length > 1 ? t[1] : null);
                return;
            case "goal":
                setActiveGoal(t.length > 1 ? t[1] : null);
                return;
            default:
                startFromTokens(t);
        }
    }

    /** Parses the {@code <boss> <player>} / {@code <player> [boss] [goal n]} start forms. */
    private void startFromTokens(String[] t) {
        SlayerBoss boss = null;
        int tier = 0;
        String player = null;
        int goal = 0;

        // Boss-first: "/sbs trackcarry eman4 Steve".
        SlayerBoss first = SlayerBoss.fromToken(t[0]);
        int i;
        if (first != null && t.length >= 2) {
            boss = first;
            tier = first.tierInToken(t[0]);
            player = t[1];
            i = 2;
        } else {
            player = t[0];
            i = 1;
        }
        // Trailing extras in any order: another boss token, or "goal <n>".
        for (; i < t.length; i++) {
            String tok = t[i];
            if (tok.equalsIgnoreCase("goal") && i + 1 < t.length) {
                goal = parseInt(t[++i], 0);
                continue;
            }
            SlayerBoss b = SlayerBoss.fromToken(tok);
            if (b != null && boss == null) {
                boss = b;
                tier = b.tierInToken(tok);
            }
        }

        String ign = cleanName(player);
        if (ign.isEmpty()) {
            SBSChat.send("Usage: /sbs trackcarry <boss> <player>  (e.g. /sbs trackcarry emant4 Steve)");
            return;
        }
        start(ign, boss, tier, goal);
    }

    /** Starts a carry (or re-activates / updates an existing one for the same player). */
    private void start(String ign, SlayerBoss boss, int tier, int goal) {
        Carry carry = find(ign);
        boolean isNew = carry == null;
        if (isNew) {
            carry = new Carry();
            carry.player = ign;
            carry.startedAt = System.currentTimeMillis();
            cfg().carries.add(carry);
        }
        if (boss != null) {
            carry.boss = boss.name();
            carry.tier = tier;
        }
        if (goal > 0) {
            carry.goal = goal;
        }
        cfg().activePlayer = ign;
        save();

        String label = describe(carry);
        SBSChat.send(Component.literal((isNew ? " Now carrying " : " Tracking ")).withColor(SBSChat.WHITE)
                .append(Component.literal(carry.player).withColor(SBSChat.PREFIX_COLOR))
                .append(Component.literal("  " + label
                        + (carry.count > 0 ? "  (" + carry.count + ")" : "")).withColor(SBSChat.WHITE)));
    }

    private void adjustActive(int delta) {
        Carry carry = activeCarry();
        if (carry == null) {
            SBSChat.send("No active carry. Start one with /sbs trackcarry <boss> <player>.");
            return;
        }
        carry.count = Math.max(0, carry.count + delta);
        save();
        announce(carry, true);
        if (delta > 0) {
            maybePartyLine(carry);
        }
    }

    private void setActiveGoal(String value) {
        Carry carry = activeCarry();
        if (carry == null) {
            SBSChat.send("No active carry to set a goal on.");
            return;
        }
        carry.goal = Math.max(0, parseInt(value, carry.goal));
        save();
        SBSChat.send("Goal for " + carry.player + " set to " + (carry.goal > 0 ? carry.goal : "none") + ".");
    }

    private void finish(String name) {
        Carry carry = name == null || name.isBlank() ? activeCarry() : find(cleanName(name));
        if (carry == null) {
            SBSChat.send("No such carry to finish.");
            return;
        }
        cfg().carries.remove(carry);
        if (carry.player.equalsIgnoreCase(cfg().activePlayer)) {
            cfg().activePlayer = cfg().carries.isEmpty() ? "" : cfg().carries.get(cfg().carries.size() - 1).player;
        }
        save();
        SBSChat.send("Carry finished: " + carry.player + " — " + describe(carry) + "  ×" + carry.count + ".");
    }

    private void clearAll() {
        int n = cfg().carries.size();
        cfg().carries.clear();
        cfg().activePlayer = "";
        save();
        SBSChat.send("Cleared " + n + " carr" + (n == 1 ? "y" : "ies") + ".");
    }

    private void printStatus() {
        Carry carry = activeCarry();
        if (carry == null) {
            SBSChat.send("No active carry. Start one with /sbs trackcarry <boss> <player>.");
            return;
        }
        announce(carry, false);
    }

    private void printList() {
        List<Carry> carries = cfg().carries;
        if (carries.isEmpty()) {
            SBSChat.send("No carries tracked.");
            return;
        }
        SBSChat.send("Carries (" + carries.size() + "):");
        for (Carry c : carries) {
            boolean active = c.player.equalsIgnoreCase(cfg().activePlayer);
            SBSChat.send((active ? "▶ " : "  ") + c.player + "  " + describe(c) + "  "
                    + c.count + (c.goal > 0 ? "/" + c.goal : ""));
        }
    }

    // ------------------------------------------------------------------ detection

    /** Called every client tick from {@code AutoSprintMixin}. */
    public void onClientTick() {
        CarryCounterSettings settings = cfg();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (!settings.enabled || level == null || player == null) {
            tracks.clear();
            judge.clear();
            highlightBoxes = List.of();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;   // scan throttled – tracks and highlight boxes keep their last state until the next pass
        }
        lastScanAt = now;
        judge.setGraceMs(Math.max(0, settings.deathGraceMs));
        double radius = Math.max(2, settings.detectRadius);
        double radiusSq = radius * radius;
        Set<Integer> seen = new HashSet<>();
        int bossStands = 0;

        // --- Boss highlight setup ----------------------------------------------------------------------
        // Only bosses OWNED by a relevant player are highlighted: party members and every /sbs
        // trackcarry target. Ownership is read from the boss's "Slayed by <name>" hologram, or from a
        // relevant player standing within HIGHLIGHT_OWNER_RADIUS of it (the lag-proof proximity gate for a
        // freshly spawned miniboss that has no ownership hologram yet). highlightOnlyBoss narrows it further
        // to the carried boss type. Turn "owned only" off to fall back to highlighting every slayer.
        boolean highlightOn = settings.highlightEnabled;
        SlayerBoss highlightOnlyBoss = highlightOn && settings.highlightActiveBossOnly ? bossOf(activeCarry()) : null;
        int highlightColor = settings.highlightColor.argb();
        boolean ownedOnly = highlightOn && settings.highlightOwnedOnly;
        Set<String> relevant = ownedOnly ? relevantPlayers() : Set.of();
        List<OwnerTag> ownerTags = ownedOnly ? new ArrayList<>() : null;   // every "Slayed by" stand
        // Every "Spawned by" line seen this scan, matched to its boss after the loop – the world hands
        // out entities in no order, so a boss's stand can turn up before the line that explains it.
        List<SpawnedByTag> spawnedByTags = settings.autoDetect ? new ArrayList<>() : null;
        // Always collected: the highlight needs them for its boxes, the counter for stack ownership.
        List<BossCandidate> candidates = new ArrayList<>();

        // Pass 1: refresh a track for every slayer boss whose nametag is visible right now – a live
        // health readout OR the final-phase "N Hits" counter (Voidgloom's hit phase drops the health
        // bar, and the box must not vanish then). Its stand is noted for ownership resolution below.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) {
                continue;
            }
            var custom = stand.getCustomName();
            String stripped = custom == null ? "" : strip(custom.getString());

            // "Slayed by <player>" hologram: remember every one with whether its owner is relevant, so
            // the boss it floats over is attributed – a stranger's boss is then excluded even when a
            // relevant player happens to stand next to it.
            if (ownerTags != null) {
                Matcher slayed = SLAYED_BY.matcher(stripped);
                if (slayed.find()) {
                    boolean isRelevant = relevant.contains(slayed.group(1).toLowerCase(Locale.ROOT));
                    ownerTags.add(new OwnerTag(stand.position(), isRelevant));
                }
            }

            // "Spawned by <player>": the boss's own statement of whose quest it belongs to. Nothing
            // below can match this stand as well - an owner line carries no health readout - so it is
            // taken and the rest of the boss checks are skipped.
            if (spawnedByTags != null) {
                Matcher spawned = SPAWNED_BY.matcher(stripped);
                if (spawned.find()) {
                    spawnedByTags.add(new SpawnedByTag(stand.position(),
                            spawned.group(1).toLowerCase(Locale.ROOT)));
                    continue;
                }
            }

            SlayerBoss boss = SlayerBoss.matchNametag(stripped);
            if (boss == null || !looksLikeBossTag(stripped)) {
                continue;
            }
            bossStands++;
            LivingEntity mob = bossBelow(level, stand);
            if (mob == null) {
                continue;
            }
            int id = mob.getId();
            Track track = tracks.computeIfAbsent(id, x -> new Track());
            track.boss = boss;
            track.lastSeenMs = now;
            track.pos = mob.position();
            // Voidgloom's shield: the tag shows "N Hits" in place of the health. It cannot die then.
            track.shielded = !looksLikeHealthTag(stripped);
            if (mob.distanceToSqr(player) <= radiusSq) {
                track.everClose = true;
                track.lastCloseMs = now;
            }
            seen.add(id);
            candidates.add(new BossCandidate(mob, boss, stand.position()));
        }

        // Bind each boss seen this scan to the player its own stack names. The owner never changes, so
        // once a track knows it, it keeps it even while the line is briefly not being parsed.
        if (spawnedByTags != null && !spawnedByTags.isEmpty()) {
            for (BossCandidate candidate : candidates) {
                String owner = ownerOfStack(candidate.standPos(), spawnedByTags);
                Track track = owner == null ? null : tracks.get(candidate.mob().getId());
                if (track != null) {
                    track.owner = owner;
                }
            }
        }

        // A boss body showing up where one just vanished is the same fight changing phase: drop the
        // pending death and carry the old track's history over to the new body.
        for (BossCandidate candidate : candidates) {
            Track track = tracks.get(candidate.mob().getId());
            if (track == null || track.pos == null) {
                continue;
            }
            CarryKillJudge.Pending resumed = judge.seen(track.boss, track.owner,
                    track.pos.x, track.pos.y, track.pos.z, now);
            if (resumed != null) {
                if (track.owner == null) {
                    track.owner = resumed.owner();
                }
                track.everClose |= resumed.everClose();
                track.lastCloseMs = Math.max(track.lastCloseMs, resumed.lastCloseMs());
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Carry] {} of '{}' came back as entity {} after {}ms - same fight continued, not counted",
                        track.boss, resumed.owner(), candidate.mob().getId(), now - resumed.vanishedAt());
            }
        }

        // Resolve highlight boxes once every stand is seen: keep a candidate when the boss-type filter passes
        // and, when "owned only" is on, when a relevant player owns it (Slayed-by tag or 8-block reach).
        // Strictly gated on the toggle - the renderer draws whatever snapshot it is handed.
        List<CarryHighlight.Box> highlight = null;
        if (highlightOn) {
            highlight = new ArrayList<>(candidates.size());
            List<Vec3> relevantPlayerPos = ownedOnly
                    ? relevantPlayerPositions(level, relevant) : List.of();
            for (BossCandidate candidate : candidates) {
                if (highlightOnlyBoss != null && candidate.boss() != highlightOnlyBoss) {
                    continue;
                }
                if (ownedOnly && !isOwnedByRelevant(candidate, ownerTags, relevantPlayerPos)) {
                    continue;
                }
                highlight.add(new CarryHighlight.Box(candidate.mob().getBoundingBox(), highlightColor,
                        settings.highlightLabel ? candidate.boss().shortLabel() : null));
            }
        }
        highlightBoxes = highlight == null ? List.of() : List.copyOf(highlight);

        // Pass 2: a boss that was here and is now gone may have died. It is only held as pending -
        // the body changes entity mid-fight (phases), so it counts once it has stayed gone for the
        // grace window (pass 3). Bosses that walked off or unloaded far away just prune.
        String self = player.getGameProfile().name();
        Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Track> e = it.next();
            if (seen.contains(e.getKey())) {
                continue;
            }
            Track track = e.getValue();
            Entity ent = level.getEntity(e.getKey());
            boolean gone = !(ent instanceof LivingEntity le) || !le.isAlive() || le.isDeadOrDying();
            if (!gone) {
                if (now - track.lastSeenMs > STALE_MS) {
                    it.remove(); // still alive but we lost its tag long ago – stop watching it
                }
                continue;
            }
            it.remove();
            // Its tag was still being read a moment ago and the entity is now gone. A boss that
            // merely unloaded stops being parsed first, so its tag is already stale by then.
            if (!settings.autoDetect || now - track.lastSeenMs > DEATH_WINDOW_MS || track.pos == null) {
                continue;
            }
            if (track.owner != null && self != null && track.owner.equalsIgnoreCase(self)) {
                continue; // your own boss: "SLAYER QUEST COMPLETE!" is the exact signal (onChat)
            }
            boolean proximity = track.owner == null;
            if (!proximity && find(track.owner) == null) {
                logForeignBoss(now, track.owner);
                continue;
            }
            // Owner path: only that it was ever part of your fight. Proximity path: it went out
            // right beside you.
            boolean eligible = track.everClose
                    && (!proximity || now - track.lastCloseMs <= DEATH_WINDOW_MS);
            if (!eligible) {
                continue;
            }
            if (judge.vanished(track.boss, track.owner, track.pos.x, track.pos.y, track.pos.z, now,
                    track.shielded, track.everClose, track.lastCloseMs) == CarryKillJudge.Refusal.SHIELDED) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Carry] {} of '{}' (entity {}) vanished in its Hits shield - a phase, not counted",
                        track.boss, track.owner, e.getKey());
            } else {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Carry] {} of '{}' (entity {}) vanished - pending for {}ms",
                        track.boss, track.owner, e.getKey(), settings.deathGraceMs);
            }
        }

        // Pass 3: a pending death that stayed gone for the whole window is a kill - booked against
        // whoever its stack named, or the active carry when it named nobody. One per cooldown.
        for (CarryKillJudge.Pending kill : judge.confirmed(now)) {
            Carry carry = kill.owner() != null ? find(kill.owner()) : activeCarry();
            if (!judge.mayClaim(now)) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Carry] {} of '{}' confirmed inside the {}ms cooldown - not counted",
                        kill.boss(), kill.owner(), COUNT_COOLDOWN_MS);
            } else if (countKill(kill.boss(), carry)) {
                judge.claimed(now);
                logClaim(now, kill.owner() != null ? "owner line (" + kill.owner() + ")" : "proximity", carry);
            }
        }

        diagnostic(now, bossStands);
    }

    /**
     * Fed every chat line. "SLAYER QUEST COMPLETE!" reaches only the quest's owner, so it is the
     * exact kill signal for a boss of your own - which is why the vanish check skips those. It
     * counts only when a carry tracks your own name (carrying yourself, or testing the counter).
     */
    public void onChat(String text) {
        CarryCounterSettings settings = cfg();
        if (!settings.enabled || !settings.autoDetect || text == null
                || !strip(text).contains("SLAYER QUEST COMPLETE")) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        Carry own = player == null ? null : find(player.getGameProfile().name());
        long now = System.currentTimeMillis();
        if (own != null && judge.mayClaim(now) && countKill(bossOf(own), own)) {
            judge.claimed(now);
            logClaim(now, "quest complete (own boss)", own);
        }
    }

    /**
     * Books one detected kill against {@code carry} and returns whether it was actually counted. The
     * caller decides which carry that is – the one the boss's owner line named, or the active one when
     * the fallback path is running. When the carry names a boss, a kill of a <i>different</i> slayer is
     * ignored: that is somebody's own quest, not the carry.
     */
    private boolean countKill(SlayerBoss boss, Carry carry) {
        if (carry == null) {
            return false;
        }
        if (!carry.boss.isEmpty() && boss != null && !carry.boss.equals(boss.name())) {
            return false; // a different slayer than the one being carried
        }
        carry.count++;
        // Adopt the boss when the carry was started without one, so the overlay/label fill in.
        if (carry.boss.isEmpty() && boss != null) {
            carry.boss = boss.name();
        }
        save();
        if (cfg().announce) {
            announce(carry, true);
        }
        maybePartyLine(carry);
        return true;
    }

    /**
     * Sends the party-chat progress line when the toggle is on:
     * {@code /pc carry <label> <count>/<goal-or-?> <player>}. Fired on each counted kill (and manual
     * {@code +}) so the customer sees the running total in party chat. Off by default – nothing is
     * ever sent to the server unless the carrier enables it.
     */
    private void maybePartyLine(Carry carry) {
        if (!cfg().partyAnnounce || carry == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            return;
        }
        String goal = carry.goal > 0 ? String.valueOf(carry.goal) : "?";
        String message = "carry " + describe(carry) + " " + carry.count + "/" + goal + " " + carry.player;
        try {
            mc.player.connection.sendCommand(
                    "pc " + sbs.modid.client.core.util.ChatTag.tag(message));
        } catch (Throwable ignored) {
            // Not connected / command rejected – a progress line is best-effort, never fatal.
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The carry currently receiving auto-detected kills, or {@code null} when none is active. */
    public Carry activeCarry() {
        return find(cfg().activePlayer);
    }

    /** All tracked carries (read-only use by the overlay). */
    public List<Carry> carries() {
        return cfg().carries;
    }

    /** Boss-highlight boxes rebuilt each tick, drawn by {@link CarryHighlight} every frame (read-only snapshot). */
    public List<CarryHighlight.Box> highlightBoxes() {
        return highlightBoxes;
    }

    public boolean isActive(Carry carry) {
        return carry != null && carry.player.equalsIgnoreCase(cfg().activePlayer);
    }

    /** The overlay label for a carry, e.g. {@code "Eman T4"}, {@code "T3"} or {@code "Slayer"}. */
    public static String describe(Carry carry) {
        SlayerBoss boss = bossOf(carry);
        if (boss != null) {
            return boss.label(carry.tier);
        }
        return carry.tier > 0 ? "T" + carry.tier : "Slayer";
    }

    static SlayerBoss bossOf(Carry carry) {
        if (carry == null || carry.boss == null || carry.boss.isEmpty()) {
            return null;
        }
        try {
            return SlayerBoss.valueOf(carry.boss);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Carry find(String ign) {
        if (ign == null || ign.isBlank()) {
            return null;
        }
        for (Carry c : cfg().carries) {
            if (c.player.equalsIgnoreCase(ign)) {
                return c;
            }
        }
        return null;
    }

    private void announce(Carry carry, boolean withCount) {
        String label = describe(carry);
        String tail = withCount
                ? "  " + carry.count + (carry.goal > 0 ? "/" + carry.goal : "")
                : (carry.goal > 0 ? "  " + carry.count + "/" + carry.goal : "  " + carry.count);
        SBSChat.send(Component.literal(" Carry ").withColor(SBSChat.WHITE)
                .append(Component.literal(carry.player).withColor(SBSChat.PREFIX_COLOR))
                .append(Component.literal("  " + label).withColor(SBSChat.WHITE))
                .append(Component.literal(tail).withColor(0x57D977)));
    }

    private static String cleanName(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Removes {@code §x} colour / format codes from a string. */
    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Whether a colour-stripped tag carries a health readout – a heart glyph, or a "x/y" pair, or a
     * "5M" style number. This keeps a boss <i>title</i> hologram (name only, no health) from being
     * mistaken for the live health bar the detector actually wants.
     */
    private static boolean looksLikeHealthTag(String stripped) {
        if (stripped == null) {
            return false;
        }
        if (stripped.indexOf('❤') >= 0 || stripped.indexOf('♥') >= 0) { // ❤ ♥
            return true;
        }
        return stripped.matches(".*\\d[\\d.,]*\\s*/\\s*\\d.*")
                || stripped.matches(".*\\d[\\d.,]*[kKmMbB].*");
    }

    /**
     * Whether a colour-stripped tag is a <i>live</i> boss tag – a health readout ({@link
     * #looksLikeHealthTag}) or the final-phase "N Hits" counter. Voidgloom's hit phase replaces the
     * health bar with a hit count, so without this the box (and the track) would vanish exactly while
     * the boss is being finished off.
     */
    private static boolean looksLikeBossTag(String stripped) {
        return looksLikeHealthTag(stripped)
                || (stripped != null && stripped.toLowerCase(Locale.ROOT).contains("hits"));
    }

    /** Players whose slayer boss the highlight may highlight: party members + every carry target (lower-cased). */
    private Set<String> relevantPlayers() {
        Set<String> out = new HashSet<>();
        for (String member : PartyTracker.getInstance().members()) {
            out.add(member.toLowerCase(Locale.ROOT));
        }
        for (Carry carry : cfg().carries) {
            if (carry.player != null && !carry.player.isEmpty()) {
                out.add(carry.player.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** In-world positions of the relevant players, for the proximity ownership check. */
    private static List<Vec3> relevantPlayerPositions(ClientLevel level, Set<String> relevant) {
        List<Vec3> out = new ArrayList<>();
        for (Player p : level.players()) {
            String name = p.getGameProfile().name();
            if (name != null && relevant.contains(name.toLowerCase(Locale.ROOT))) {
                out.add(p.position());
            }
        }
        return out;
    }

    /**
     * Whether a boss candidate is owned by a relevant player. The boss's own "Slayed by" hologram is
     * authoritative in <b>both</b> directions – found means we know the owner, so a stranger's boss is
     * excluded even when a relevant player wanders within reach. Only when no hologram is on the boss
     * yet (freshly spawned) does it fall back to a relevant player standing within
     * {@link #HIGHLIGHT_OWNER_RADIUS_SQ} of the mob.
     */
    private static boolean isOwnedByRelevant(BossCandidate candidate, List<OwnerTag> ownerTags,
                                             List<Vec3> players) {
        OwnerTag nearest = null;
        double best = OWNER_TAG_RADIUS_SQ;
        for (OwnerTag tag : ownerTags) {
            double distance = tag.pos().distanceToSqr(candidate.standPos());
            if (distance <= best) {
                best = distance;
                nearest = tag;
            }
        }
        if (nearest != null) {
            return nearest.relevant();
        }
        Vec3 mobPos = candidate.mob().position();
        for (Vec3 pos : players) {
            if (pos.distanceToSqr(mobPos) <= HIGHLIGHT_OWNER_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player named by the owner line of the nametag stack at {@code standPos}, or {@code null}
     * when no line is close enough to be part of that stack. The whole stack occupies well under a
     * block, so the nearest line inside {@link #OWNER_TAG_RADIUS_SQ} is this boss's own; two bosses
     * standing that close would have to be inside one another.
     */
    private static String ownerOfStack(Vec3 standPos, List<SpawnedByTag> tags) {
        String owner = null;
        double best = OWNER_TAG_RADIUS_SQ;
        for (SpawnedByTag tag : tags) {
            double distance = tag.pos().distanceToSqr(standPos);
            if (distance <= best) {
                best = distance;
                owner = tag.owner();
            }
        }
        return owner;
    }

    /**
     * Names the path that claimed a kill, so a wrong total can be traced back to the evidence behind
     * it rather than guessed at. Throttled, though kills are rare enough that it rarely bites.
     */
    private void logClaim(long now, String path, Carry carry) {
        if (now - lastClaimLogAt < 2_000L) {
            return;
        }
        lastClaimLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Carry] kill counted for {} via {} - now at {}",
                carry.player, path, carry.count);
    }

    /**
     * One line every 30s while bosses whose owner line names somebody you are NOT carrying die around
     * you – precisely what the proximity path used to count. Prints the owner it read, so a carry
     * started under a mistyped name shows up here instead of as a total that silently never moves.
     */
    private void logForeignBoss(long now, String owner) {
        if (now - lastForeignLogAt < 30_000L) {
            return;
        }
        lastForeignLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Carry] boss of '{}' died nearby - no carry tracks that player, not counted",
                owner);
    }

    /** Nearest living, non-stand, non-player entity just below a boss nametag stand (its body). */
    private static LivingEntity bossBelow(ClientLevel level, ArmorStand stand) {
        List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class,
                stand.getBoundingBox().inflate(1.0, 0, 1.0).expandTowards(0, -5, 0),
                m -> m != stand && !(m instanceof ArmorStand) && !(m instanceof Player) && m.isAlive());
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mobs) {
            double distance = mob.distanceToSqr(stand);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    /**
     * Throttled heartbeat so the detector can be tuned against a live server. {@code owned} is how
     * many of the tracked bosses have read an owner line: it is the quickest way to see whether the
     * exact path is working at all, or whether every kill is still riding on the proximity guess.
     */
    private void diagnostic(long now, int bossStands) {
        if (bossStands == 0 && tracks.isEmpty()) {
            return;
        }
        if (now - lastDebugAt < 5_000L) {
            return;
        }
        lastDebugAt = now;
        int owned = 0;
        for (Track track : tracks.values()) {
            if (track.owner != null) {
                owned++;
            }
        }
        Carry active = activeCarry();
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Carry] bossStands={} tracks={} owned={} auto={} active={} count={}",
                bossStands, tracks.size(), owned, cfg().autoDetect,
                active == null ? "-" : active.player, active == null ? 0 : active.count);
    }

    /** Live per-boss tracking state (one entry per boss living entity). */
    private static final class Track {
        private SlayerBoss boss;
        private long lastSeenMs;
        private long lastCloseMs;
        private boolean everClose;
        /** Where the body was last seen - a returning body is matched against it. */
        private Vec3 pos;
        /** Whether the last tag was a shield phase (Voidgloom "N Hits") in which it cannot die. */
        private boolean shielded;
        /**
         * The player this boss's own stack was spawned by, lower-cased, or {@code null} while no owner
         * line has been read for it. Sticky once set – a boss does not change hands, and the line is
         * not on every frame.
         */
        private String owner;
    }

    /** A slayer boss found this tick: its mob, type and its nametag stand position (for ownership). */
    private record BossCandidate(LivingEntity mob, SlayerBoss boss, Vec3 standPos) {
    }

    /** A "Slayed by <player>" ownership hologram: its position and whether the owner is relevant. */
    private record OwnerTag(Vec3 pos, boolean relevant) {
    }

    /** A "Spawned by: <player>" owner line: where it floats and the (lower-cased) name it states. */
    private record SpawnedByTag(Vec3 pos, String owner) {
    }
}
