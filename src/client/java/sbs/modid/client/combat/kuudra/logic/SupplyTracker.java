/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.kuudra.model.CratePile;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.model.PreSpot;
import sbs.modid.client.combat.kuudra.model.SupplySpot;
import sbs.modid.client.combat.kuudra.render.KuudraAlert;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The supplies phase: who has brought what in, where the crates currently are, which pile is finished,
 * and - the part that actually changes how a run goes - which crate never spawned.
 *
 * <p><b>"No pre" is the one call that has to be made in the first fifteen seconds.</b> Seven places
 * can produce a crate and only six ever do, so exactly one player is standing at an empty spot every
 * single run. Until they say so, three other people are waiting for a seventh crate that does not
 * exist. This class watches the player's own camp and makes that call for them.
 *
 * <p><b>It is deliberately only ever about the player's own spot.</b> A client can only see the
 * crates near it - the far side of the arena is outside entity range - so any attempt to work out the
 * whole board from here would be guessing about entities that were never sent. Watching one camp and
 * being right about it beats watching seven and being wrong about five.
 *
 * <p><b>Crates are giants, not items.</b> The thing that surfaces holding a supply is a Giant with a
 * skull in its hand, down in the lava below the walkways - hence the {@link #CARRIER_MAX_Y} floor,
 * which is what keeps the phase-two build stands and any other giant out of the count.
 *
 * <p>Incoming party calls are parsed too, so somebody else's "no tri" lights up the matching pile.
 * That text comes from other players and is never fed back into a command - it only ever selects one
 * of the {@link SupplySpot} constants, and an unrecognised word selects nothing.
 */
public final class SupplyTracker {

    private static final SupplyTracker INSTANCE = new SupplyTracker();

    /** "Bob recovered one of Elle's supplies! (3/6)" - the name, then the running count. */
    private static final Pattern PLACED =
            Pattern.compile("(\\w{2,16}) recovered[^(]*\\((\\d)/6\\)");

    /** "Bob dropped Elle's supplies back into the lava! Oops!" */
    private static final Pattern DROPPED =
            Pattern.compile("(\\w{2,16}) dropped Elle's supplies back into the lava");

    /** Somebody's call in party chat: "no tri", "missing x cannon". Two words at most. */
    private static final Pattern NO_PRE_CALL =
            Pattern.compile("\\b(?:no|missing)\\s+([a-z]+)(?:\\s+([a-z]+))?");

    /** Party chat, with the rank prefix that may or may not be there: "Party > [MVP+] Bob: text". */
    private static final Pattern PARTY_LINE =
            Pattern.compile("^Party\\s*>\\s*(?:\\[[^]]*]\\s*)?(\\w{2,16})\\s*:\\s*(.+)$");

    private static final String SELF_PICKUP = "You retrieved some of Elle's supplies from the Lava!";
    private static final String SELF_SLIPPED = "You moved and the Chest slipped out of your hands!";

    /** A supply carrier is down in the lava; anything above this is scenery. */
    private static final double CARRIER_MAX_Y = 67.0;

    /** How far from a camp a carrier counts as "that camp's crate". */
    private static final double CARRIER_MATCH_RADIUS = 14.0;

    /** How far out to look for carriers and pile stands. */
    private static final double SCAN_RADIUS = 90.0;

    /** Entity scans cost a query per call; twice a tick is far more than the eye needs. */
    private static final long SCAN_INTERVAL_MS = 150L;

    /** The marker Hypixel puts over a pile that has had its crate: "SUPPLIES RECEIVED". */
    private static final String PILE_DONE = "SUPPLIES RECEIVED";

    /** The build-phase progress stand: "PROGRESS: 40%". */
    private static final Pattern PILE_PROGRESS = Pattern.compile("PROGRESS:\\s*(\\d+)%");

    /** One player's delivery, for the timing list. */
    public record Delivery(String player, int number, long atMs) {
    }

    private volatile long suppliesStart;
    private volatile int delivered;
    private volatile PreSpot myPre;
    private volatile boolean primarySeen;
    private volatile boolean secondarySeen;
    private volatile boolean primaryCalled;
    private volatile boolean secondaryCalled;

    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    /** Live carrier positions, republished by the scan and read by the renderer. */
    private volatile List<Vec3> carriers = List.of();

    /** Piles that already have their crate - drawn differently, or not at all. */
    private final Set<CratePile> completed = EnumSet.noneOf(CratePile.class);

    /** Spots someone (including us) has called missing. Cleared with the phase. */
    private final Set<SupplySpot> called = EnumSet.noneOf(SupplySpot.class);

    /**
     * Stand labels already written to the tuning log, so each wording is reported once rather than
     * four times a second. Only ever grows to the handful of labels a run actually produces.
     */
    private final Set<String> seenLabels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Build-phase pile progress, republished by the scan. */
    private volatile List<PileProgress> buildPiles = List.of();

    /** One ballista pile mid-build: where its stand is, and how far along it reads. */
    public record PileProgress(Vec3 position, int percent) {
    }

    private long lastScan;

    /** Last reported rejection count, so the tuning log only fires when the picture changes. */
    private int lastRejected = -1;

    private SupplyTracker() {
    }

    public static SupplyTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    // ------------------------------------------------------------------ what the renderer reads

    public int delivered() {
        return delivered;
    }

    public List<Delivery> deliveries() {
        return deliveries;
    }

    public List<Vec3> carriers() {
        return carriers;
    }

    public List<PileProgress> buildPiles() {
        return buildPiles;
    }

    public boolean isCompleted(CratePile pile) {
        return completed.contains(pile);
    }

    /** Whether this spot has been called missing by anybody this run. */
    public boolean isCalledMissing(SupplySpot spot) {
        return called.contains(spot);
    }

    /** The camp the player was standing on when the phase started, or {@code null}. */
    public PreSpot myPre() {
        return myPre;
    }

    /** Seconds since the supplies phase began, or {@code -1} outside it. */
    public double suppliesSeconds() {
        return suppliesStart == 0 ? -1 : (System.currentTimeMillis() - suppliesStart) / 1000.0;
    }

    // ------------------------------------------------------------------ lifecycle

    void onRunStart() {
        reset();
    }

    /** Supplies have begun: pin down which camp we are on and start the no-pre clock. */
    void onSuppliesStarted() {
        suppliesStart = System.currentTimeMillis();
        LocalPlayer player = Minecraft.getInstance().player;
        myPre = player == null ? null : PreSpot.at(player.position());
        KuudraTracker.getInstance().log("supplies started, camp = {}",
                myPre == null ? "none" : myPre.displayName());
    }

    /** All six are in - the no-pre watch has nothing left to say. */
    void onSuppliesDone() {
        primaryCalled = true;
        secondaryCalled = true;
    }

    void reset() {
        suppliesStart = 0;
        delivered = 0;
        myPre = null;
        primarySeen = false;
        secondarySeen = false;
        primaryCalled = false;
        secondaryCalled = false;
        deliveries.clear();
        carriers = List.of();
        buildPiles = List.of();
        completed.clear();
        called.clear();
        seenLabels.clear();
        lastRejected = -1;
    }

    // ------------------------------------------------------------------ the tick

    /** Called from {@link KuudraTracker}'s tick, once the run is known to be live. */
    public void onClientTick() {
        SBSConfig.KuudraSettings cfg = cfg();
        KuudraTracker tracker = KuudraTracker.getInstance();
        if (!cfg.enabled || !tracker.running()) {
            return;
        }
        KuudraPhase phase = tracker.phase();
        if (phase != KuudraPhase.SUPPLIES && phase != KuudraPhase.BUILD) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        AABB area = player.getBoundingBox().inflate(SCAN_RADIUS);
        if (phase == KuudraPhase.SUPPLIES) {
            scanCarriers(level, area);
            scanCompletedPiles(level, area);
            checkNoPre(cfg, now);
        } else {
            scanBuildPiles(level, area);
        }
    }

    /**
     * Republishes where the crates are, and notes whether our own two spots have produced one.
     *
     * <p>Also reports the giants that were looked at and <i>rejected</i> to the tuning log. What
     * makes a giant a carrier is a guess about how Hypixel builds them, and the difference between
     * "no crate spawned" and "the filter is wrong" is invisible from the outside - the rejection
     * count is what tells them apart.
     */
    private void scanCarriers(ClientLevel level, AABB area) {
        List<Vec3> found = new ArrayList<>();
        int rejected = 0;
        for (Giant giant : level.getEntitiesOfClass(Giant.class, area, Giant::isAlive)) {
            if (!isCarrier(giant)) {
                rejected++;
                continue;
            }
            Vec3 pos = giant.position();
            found.add(pos);
            if (myPre == null) {
                continue;
            }
            if (near(pos, myPre.primary())) {
                primarySeen = true;
            }
            if (myPre.secondary() != null && near(pos, myPre.secondary())) {
                secondarySeen = true;
            }
        }
        if (found.size() != carriers.size() || rejected != lastRejected) {
            lastRejected = rejected;
            KuudraTracker.getInstance().log("carriers: {} in range, {} giant(s) rejected",
                    found.size(), rejected);
        }
        carriers = List.copyOf(found);
    }

    /**
     * A supply carrier: a giant down in the lava with a skull in its hand. The skull is what makes it
     * a carrier rather than scenery, and the height is what keeps the build phase's own giants out.
     */
    private static boolean isCarrier(Giant giant) {
        if (!giant.isAlive() || giant.getY() > CARRIER_MAX_Y) {
            return false;
        }
        ItemStack held = giant.getMainHandItem();
        if (held.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        return id.endsWith("_head") || id.endsWith("_skull");
    }

    private static boolean near(Vec3 pos, SupplySpot spot) {
        double dx = pos.x - spot.position().x;
        double dz = pos.z - spot.position().z;
        return dx * dx + dz * dz <= CARRIER_MATCH_RADIUS * CARRIER_MATCH_RADIUS;
    }

    /**
     * Piles wearing Hypixel's "SUPPLIES RECEIVED" tag are done and stop being drawn.
     *
     * <p>That tag text is the one guess in this class, so every <i>other</i> label standing on a pile
     * is written to the tuning log the first time it is seen. If Hypixel words it differently, the
     * log says what it actually says instead of the feature silently never firing.
     */
    private void scanCompletedPiles(ClientLevel level, AABB area) {
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, area,
                ArmorStand::hasCustomName)) {
            String name = standName(stand);
            CratePile closest = closestPile(stand.position());
            if (closest == null) {
                continue;
            }
            if (name.contains(PILE_DONE)) {
                if (completed.add(closest)) {
                    KuudraTracker.getInstance().log("pile {} is done", closest.displayName());
                }
            } else if (seenLabels.add(name)) {
                KuudraTracker.getInstance().log("label on pile {}: \"{}\"", closest.displayName(), name);
            }
        }
    }

    /** The build phase's own stands: one per pile, carrying that pile's completion percentage. */
    private void scanBuildPiles(ClientLevel level, AABB area) {
        List<PileProgress> found = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, area,
                ArmorStand::hasCustomName)) {
            Matcher matcher = PILE_PROGRESS.matcher(standName(stand));
            if (matcher.find()) {
                found.add(new PileProgress(stand.position(), Integer.parseInt(matcher.group(1))));
            }
        }
        buildPiles = List.copyOf(found);
    }

    private static String standName(ArmorStand stand) {
        return stand.getCustomName() == null ? "" : stand.getCustomName().getString();
    }

    /** The pile nearest a position, or {@code null} when nothing is close enough to be one. */
    private static CratePile closestPile(Vec3 pos) {
        CratePile best = null;
        double bestSqr = 9.0;
        for (CratePile pile : CratePile.values()) {
            double dx = pos.x - pile.position().x;
            double dz = pos.z - pile.position().z;
            double sqr = dx * dx + dz * dz;
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = pile;
            }
        }
        return best;
    }

    /**
     * The no-pre call.
     *
     * <p>The wait is not a guess about lag - it is the answer to "has the spawn wave happened yet".
     * Crates surface within a couple of seconds of each other, so once the configured window has
     * passed <b>and at least one crate has been seen anywhere</b>, a camp that still has nothing is
     * genuinely empty rather than slow. Without that second condition a laggy server produced a
     * "no tri" on a run where the tri crate was simply late, which is worse than saying nothing.
     */
    private void checkNoPre(SBSConfig.KuudraSettings cfg, long now) {
        if (!cfg.noPreAlert || myPre == null || suppliesStart == 0) {
            return;
        }
        if (now - suppliesStart < cfg.noPreDelaySeconds * 1000L) {
            return;
        }
        boolean waveArrived = !carriers.isEmpty() || primarySeen || secondarySeen;
        if (!waveArrived) {
            return;
        }
        if (!primarySeen && !primaryCalled) {
            primaryCalled = true;
            callMissing(cfg, myPre.primary());
        }
        // The second spot is only worth calling once the first one is dealt with - shouting about
        // both at the same moment tells the party nothing about which one to cover.
        if (cfg.secondSupplyAlert && myPre.secondary() != null
                && primarySeen && !secondarySeen && !secondaryCalled) {
            secondaryCalled = true;
            callMissing(cfg, myPre.secondary());
        }
    }

    /** Flashes the call locally and, if the player asked for it, says it in party chat. */
    private void callMissing(SBSConfig.KuudraSettings cfg, SupplySpot spot) {
        called.add(spot);
        KuudraTracker.getInstance().log("no pre: {}", spot.displayName());
        KuudraAlert.getInstance().flash("NO " + spot.displayName().toUpperCase(Locale.ROOT) + "!",
                0xFFFF5555, cfg.noPreSound, 0.8f);
        if (cfg.noPreToParty) {
            KuudraSay.party("No " + spot.displayName() + "!");
        }
    }

    // ------------------------------------------------------------------ chat

    /** One colour-stripped chat line, from the module's chat hook. */
    public void onChat(String text) {
        SBSConfig.KuudraSettings cfg = cfg();
        if (!cfg.enabled || !KuudraTracker.getInstance().running()) {
            return;
        }
        Matcher placed = PLACED.matcher(text);
        if (placed.find()) {
            onPlaced(cfg, placed.group(1), Integer.parseInt(placed.group(2)));
            return;
        }
        Matcher dropped = DROPPED.matcher(text);
        if (dropped.find()) {
            onDropped(cfg, dropped.group(1));
            return;
        }
        if (text.contains(SELF_SLIPPED)) {
            onDropped(cfg, selfName());
            return;
        }
        if (text.contains(SELF_PICKUP)) {
            KuudraTracker.getInstance().log("picked up a supply at {}s",
                    String.format(Locale.US, "%.1f", suppliesSeconds()));
            return;
        }
        readPartyCall(text);
    }

    private void onPlaced(SBSConfig.KuudraSettings cfg, String player, int number) {
        delivered = Math.max(delivered, number);
        long at = suppliesStart == 0 ? 0 : System.currentTimeMillis() - suppliesStart;
        deliveries.add(new Delivery(player, number, at));
        KuudraTracker.getInstance().log("supply {}/6 by {} at {}",
                number, player, KuudraTracker.clock(at));
        if (cfg.supplyAlert && number >= 6) {
            KuudraAlert.getInstance().flash("SUPPLIES IN", 0xFF7CFF6A, cfg.supplySound, 1.6f);
        }
    }

    private void onDropped(SBSConfig.KuudraSettings cfg, String player) {
        KuudraTracker.getInstance().log("{} dropped a supply", player);
        if (cfg.supplyDropAlert) {
            KuudraAlert.getInstance().flash(player + " DROPPED", 0xFFFF9A2E, cfg.supplySound, 0.7f);
        }
    }

    /**
     * Somebody else's "no x" in party chat, turned into a highlighted pile.
     *
     * <p>The words come from another player, so nothing here trusts them beyond looking them up:
     * {@link SupplySpot#match} either recognises the word as one of seven known names or it does not,
     * and the text itself is never repeated, stored or sent anywhere.
     */
    private void readPartyCall(String text) {
        Matcher party = PARTY_LINE.matcher(text);
        if (!party.matches()) {
            return;
        }
        String body = party.group(2)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        Matcher call = NO_PRE_CALL.matcher(body);
        while (call.find()) {
            // "x cannon" is two words and "x" is one, and the two-word reading has to win or every
            // "no x cannon" would be read as "no x" - a different spot on the other side of the map.
            SupplySpot spot = call.group(2) == null ? null
                    : SupplySpot.match(call.group(1) + call.group(2));
            if (spot == null) {
                spot = SupplySpot.match(call.group(1));
            }
            if (spot != null && called.add(spot)) {
                KuudraTracker.getInstance().log("{} called no {}", party.group(1), spot.displayName());
            }
        }
    }

    private static String selfName() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? "You" : player.getGameProfile().name();
    }
}
