/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.model.CreatureAnnounceMode;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.SeaCreatureRarity;
import sbs.modid.client.skills.fishing.render.SeaCreatureSpawnHighlight;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Says what your own rod just hooked, in chat, the moment it spawns.
 *
 * <p>Hypixel already announces every spawn, but in one unstyled line in the middle of whatever else
 * chat is doing – a Lord Jawbus reads exactly like a Squid, and on a busy pond both are gone before
 * you look up. This restates the spawn in the player's own words, in the creature's rarity colour,
 * and only for the spawns they said they care about.
 *
 * <h2>Detection</h2>
 *
 * <p><b>Chat is the primary path, and it is also the ownership proof.</b> Every sea creature's spawn
 * is preceded by one fixed flavour sentence, and Hypixel sends that sentence <i>only to the player
 * whose hook produced the creature</i>. It is a personal message, not a broadcast: the neighbour
 * three blocks away fishing up a Thunder puts nothing in your chat at all. So "did I get this line"
 * and "is this creature mine" are the same question, which is why there is no proximity test on this
 * path and why one is not wanted – a radius check would only ever be able to turn a correct
 * detection into a missed one. The mapping from sentence to creature to rarity is
 * {@link FishingData}'s existing table, reused rather than copied: two tables of the same fact drift,
 * and the one that drifts is always the newer one.
 *
 * <p>Two guards sit in front of it, both already established by {@link FishingTracker}: a line
 * carrying a {@code "<name>: "} sender is player chat, so somebody called Nessie typing "gg" is not
 * a spawn; and the sentence must match a table entry <b>exactly</b> once colour codes are stripped,
 * so combat, immunity and death lines that mention a creature cannot trigger anything.
 *
 * <h2>The nametag fallback</h2>
 *
 * <p>Chat cannot answer two cases. A sentence several creatures share names none of them uniquely,
 * and a creature from an area newer than this build has no sentence in the table at all – Torrhus
 * Canyon's are the live example: not one of them appears anywhere in this repository, so there is
 * nothing honest to hard-code. For both, the creature is read off the armor-stand nametag Hypixel
 * floats over the mob instead.
 *
 * <p>That scan is <b>armed by an event on the local player's own tackle</b> and nothing else: the
 * falling edge of {@code player.fishing}, i.e. the moment your bobber leaves the world. A creature
 * spawning from your rod always coincides with that; a creature spawning from someone else's never
 * does. At the instant of arming, every nametag already floating near the bobber is snapshotted and
 * excluded, so a neighbour's creature that was there before yours cannot be adopted as ours. Only a
 * stand that appears <i>afterwards</i>, <i>within {@link #SCAN_RADIUS} of where our bobber was</i>,
 * <i>inside {@link #SCAN_WINDOW_MS}</i>, is a candidate. A name the table knows is announced as that
 * creature; a name it does not is logged, and announced only if the player asked for unknowns.
 *
 * <p>The logging is the point of the unknown path. A finder built on a guessed literal is silently
 * dark, and a guess is all anyone could write for creatures whose names are not in this tree – so
 * instead of guessing, this build watches, writes down what it actually saw under
 * {@code [SBS][Fishing]}, and one trip to the canyon replaces the guess with the real table entry.
 *
 * <h2>Anti-spam</h2>
 *
 * <p>The two paths overlap by design – a shared sentence fires chat <i>and</i> gets refined by the
 * nametag – so the second one to arrive must stay quiet. One creature announces at most once per
 * {@link SBSConfig.SeaCreatureAnnouncerSettings#dedupeMs}, whichever path found it, which is also
 * what keeps a stand that flickers in and out of the scan from stuttering.
 */
public final class SeaCreatureAnnouncer {

    private static final SeaCreatureAnnouncer INSTANCE = new SeaCreatureAnnouncer();

    /** A chat line with a {@code "<sender>: "} prefix is somebody talking, never server flavour. */
    private static final Pattern PLAYER_CHAT = Pattern.compile("^(?:\\[[^]]*]\\s*)?[A-Za-z0-9_]{1,16}\\s*:\\s");

    /** How long after the bobber leaves the world the nametag scan stays armed. */
    private static final long SCAN_WINDOW_MS = 2_500L;

    /** How far from the bobber's last position a new nametag counts as ours (blocks). */
    private static final double SCAN_RADIUS = 6.0;

    /** How far from the player the highlight re-finds its mob each tick (blocks). */
    private static final double HIGHLIGHT_RADIUS = 32.0;

    /** Throttle on the unknown-creature log, so a canyon full of them cannot flood the file. */
    private static final long UNKNOWN_LOG_THROTTLE_MS = 10_000L;

    /** Where our bobber was the last time we saw it, and when we last saw it. */
    private Vec3 lastHookPos;
    private long hookGoneAt;
    private boolean hookWasOut;

    /** Entity ids of the nametag stands that were already near the bobber when the scan armed. */
    private final Set<Integer> preexistingStands = new HashSet<>();

    /** Creature -> when it was last announced. The dedupe both paths consult. */
    private final Map<String, Long> announcedAt = new HashMap<>();

    /** The live highlight: what to box, in which colour, until when. */
    private String highlightCreature;
    private int highlightColor;
    private long highlightUntil;

    private long lastUnknownLogAt;
    private String lastUnknownName;

    private SeaCreatureAnnouncer() {
    }

    public static SeaCreatureAnnouncer getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SeaCreatureAnnouncerSettings cfg() {
        return ConfigManager.getInstance().get().seaCreatureAnnouncer;
    }

    // ------------------------------------------------------------------
    // Chat path
    // ------------------------------------------------------------------

    /**
     * Called for every chat line. Announces when {@code message} is one of the spawn sentences.
     *
     * <p>Never modifies or swallows the line: Hypixel's own message still arrives exactly as it was
     * sent, and this adds one of ours underneath it.
     */
    public void onChat(String message) {
        SBSConfig.SeaCreatureAnnouncerSettings cfg = cfg();
        if (!cfg.enabled || message == null || message.isEmpty()) {
            return;
        }
        if (PLAYER_CHAT.matcher(message).find()) {
            return;
        }
        String creature = FishingData.seaCreatureFor(message);
        if (creature != null) {
            announce(creature, FishingData.rarityOf(creature), cfg);
        }
    }

    // ------------------------------------------------------------------
    // Tick path: bobber tracking, nametag fallback, highlight upkeep
    // ------------------------------------------------------------------

    /** Called once per client tick. Cheap and inert while the module is off. */
    public void onClientTick() {
        SBSConfig.SeaCreatureAnnouncerSettings cfg = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cfg.enabled || player == null || level == null) {
            // Leaving the world clears everything: a spawn from the last lobby must not be
            // announced, boxed or deduped against in the next one.
            if (player == null || level == null) {
                reset();
            }
            return;
        }
        trackHook(player, level);
        if (cfg.nametagFallback) {
            scanForNewNametags(player, level, cfg);
        }
        updateHighlight(player, level, cfg);
    }

    /**
     * Follows the local player's own bobber, and arms the nametag scan on the tick it disappears.
     *
     * <p>{@code player.fishing} is by definition <i>this</i> player's hook – it is the field the
     * client keeps for its own rod – which is what makes everything downstream of it local.
     */
    private void trackHook(LocalPlayer player, ClientLevel level) {
        FishingHook hook = player.fishing;
        boolean out = hook != null;
        if (out) {
            lastHookPos = hook.position();
        } else if (hookWasOut) {
            // Falling edge: the cast just ended, which is when a creature of ours would appear.
            hookGoneAt = System.currentTimeMillis();
            snapshotExistingStands(level);
        }
        hookWasOut = out;
    }

    /**
     * Records the nametag stands already floating near the bobber at the moment the scan arms.
     * Everything in here is somebody else's, or already ours and already announced – either way it
     * is not evidence of the spawn we are about to look for.
     */
    private void snapshotExistingStands(ClientLevel level) {
        preexistingStands.clear();
        for (ArmorStand stand : standsNearHook(level)) {
            preexistingStands.add(stand.getId());
        }
    }

    /** The named armor stands within {@link #SCAN_RADIUS} of where the bobber last was. */
    private List<ArmorStand> standsNearHook(ClientLevel level) {
        if (lastHookPos == null) {
            return List.of();
        }
        AABB box = new AABB(lastHookPos, lastHookPos).inflate(SCAN_RADIUS, SCAN_RADIUS + 2.0, SCAN_RADIUS);
        return level.getEntitiesOfClass(ArmorStand.class, box, ArmorStand::hasCustomName);
    }

    /**
     * The fallback itself: within the armed window, any <i>newly appeared</i> nametag near where our
     * bobber was is the creature that spawned from it.
     */
    private void scanForNewNametags(LocalPlayer player, ClientLevel level,
                                    SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        long now = System.currentTimeMillis();
        if (hookGoneAt == 0 || now - hookGoneAt > SCAN_WINDOW_MS) {
            return;
        }
        for (ArmorStand stand : standsNearHook(level)) {
            if (!preexistingStands.add(stand.getId())) {
                continue;   // already seen this tick or present before the window opened
            }
            Component custom = stand.getCustomName();
            String raw = custom == null ? null : custom.getString();
            String name = MobHighlightTracker.mobNameInNametag(raw);
            if (name == null || name.isEmpty() || name.contains("!!!")) {
                continue;   // the bite marker is a nametag stand too, and it is not a creature
            }
            String creature = FishingData.canonicalSeaCreature(name);
            if (creature != null) {
                announce(creature, FishingData.rarityOf(creature), cfg);
            } else {
                onUnknownCreature(name, cfg);
            }
        }
    }

    /**
     * A nametag that spawned off our own hook and that this build has never heard of. Always logged
     * (throttled), announced only when the player switched unknowns on – the name is Hypixel's, not
     * one we have verified, and an unverified name in an announcement is a guess wearing a label.
     */
    private void onUnknownCreature(String name, SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        long now = System.currentTimeMillis();
        if (!name.equals(lastUnknownName) || now - lastUnknownLogAt > UNKNOWN_LOG_THROTTLE_MS) {
            lastUnknownName = name;
            lastUnknownLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] sea creature not in the table spawned from your hook: '{}' "
                            + "- add it to FishingData to give it a rarity and a spawn line", name);
        }
        if (cfg.announceUnknown) {
            announce(name, SeaCreatureRarity.UNKNOWN, cfg);
        }
    }

    // ------------------------------------------------------------------
    // Announcing
    // ------------------------------------------------------------------

    /**
     * Announces {@code creature} over whichever outputs are switched on, unless a filter or the
     * dedupe says otherwise. Every path ends here, which is what makes "one spawn, one
     * announcement" a property of the class rather than a thing each caller has to remember.
     */
    private void announce(String creature, SeaCreatureRarity rarity,
                          SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        if (creature == null || creature.isEmpty() || !wanted(creature, rarity, cfg)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = announcedAt.get(creature);
        if (previous != null && now - previous < Math.max(0, cfg.dedupeMs)) {
            return;   // the other detection path already said this one
        }
        announcedAt.put(creature, now);
        pruneDedupe(now, cfg);

        if (cfg.chat) {
            SBSChat.send(Component.literal(" " + format(creature, rarity, cfg))
                    .withColor(rarity.argb() & 0xFFFFFF));
        }
        int mask = (cfg.title ? AlertChannel.TITLE.bit() : 0)
                | (cfg.sound ? AlertChannel.SOUND.bit() : 0);
        if (mask != 0) {
            // Title and sound go through the shared alert channels rather than being re-invented
            // here, so they obey the player's alert volume and title settings like every other
            // alert in the mod. The chat line does NOT, because its whole point is the format
            // string below, which the shared chat channel has no way to carry.
            Alerts.send(new Alerts.Alert(creature, rarity.displayName() + " sea creature",
                    cfg.soundTone(), null), mask);
        }
        if (cfg.highlight) {
            highlightCreature = creature;
            highlightColor = rarity.argb();
            highlightUntil = now + Math.max(1, cfg.highlightSeconds) * 1000L;
        }
    }

    /**
     * Whether this spawn passes the filters: the per-creature exception if it has one, otherwise its
     * tier's switch. The exception wins on purpose – a player who pinned Titanoboa means it.
     */
    private boolean wanted(String creature, SeaCreatureRarity rarity,
                           SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        return switch (cfg.modeFor(creature)) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> cfg.announces(rarity);
        };
    }

    /** Expands the format string. Unknown placeholders are left alone rather than blanked. */
    private String format(String creature, SeaCreatureRarity rarity,
                          SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        String template = cfg.chatFormat == null || cfg.chatFormat.isBlank()
                ? "{prefix} {creature} ({rarity})" : cfg.chatFormat;
        String prefix = cfg.chatPrefix == null ? "" : cfg.chatPrefix;
        return template
                .replace("{prefix}", prefix)
                .replace("{creature}", creature)
                .replace("{rarity}", rarity.displayName())
                .trim();
    }

    /** Drops dedupe entries older than the window, so the map cannot grow across a session. */
    private void pruneDedupe(long now, SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        long window = Math.max(0, cfg.dedupeMs);
        announcedAt.entrySet().removeIf(entry -> now - entry.getValue() > window);
    }

    // ------------------------------------------------------------------
    // Highlight
    // ------------------------------------------------------------------

    /**
     * Re-finds the announced creature's mob each tick and hands it to the renderer.
     *
     * <p>Re-found rather than captured once, because the chat sentence arrives with the spawn and
     * often <i>before</i> the mob's nametag exists client-side – a single capture at announce time
     * would find nothing about half the time. Searching each tick also means the box follows the
     * right mob when the first one dies and a second of the same kind is nearby.
     */
    private void updateHighlight(LocalPlayer player, ClientLevel level,
                                 SBSConfig.SeaCreatureAnnouncerSettings cfg) {
        if (!cfg.highlight || highlightCreature == null
                || System.currentTimeMillis() > highlightUntil) {
            if (highlightCreature != null) {
                highlightCreature = null;
                SeaCreatureSpawnHighlight.setTarget(null, 0);
            }
            return;
        }
        String wanted = highlightCreature.toLowerCase(Locale.ROOT);
        AABB box = player.getBoundingBox().inflate(HIGHLIGHT_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.getEntitiesOfClass(ArmorStand.class, box, ArmorStand::hasCustomName)) {
            ArmorStand stand = (ArmorStand) entity;
            Component custom = stand.getCustomName();
            String name = MobHighlightTracker.mobNameInNametag(custom == null ? null : custom.getString());
            if (name == null || !name.toLowerCase(Locale.ROOT).equals(wanted)) {
                continue;
            }
            LivingEntity mob = MobHighlightTracker.mobBelow(level, stand);
            if (mob == null || !mob.isAlive()) {
                continue;
            }
            double distance = mob.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        SeaCreatureSpawnHighlight.setTarget(best, highlightColor);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Clears everything that belongs to one instance of the world. Called when the player leaves it,
     * because a dedupe entry or a highlight surviving a server hop would apply the last lobby's
     * state to the next one with full confidence.
     */
    public void reset() {
        lastHookPos = null;
        hookGoneAt = 0;
        hookWasOut = false;
        preexistingStands.clear();
        announcedAt.clear();
        highlightCreature = null;
        highlightUntil = 0;
        lastUnknownName = null;
        SeaCreatureSpawnHighlight.setTarget(null, 0);
    }
}
