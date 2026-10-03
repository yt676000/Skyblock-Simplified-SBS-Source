/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.carry.logic.CarryCounter;
import sbs.modid.client.combat.carry.model.SlayerBoss;
import sbs.modid.client.combat.slayer.model.BlazeAttunement;
import sbs.modid.client.combat.slayer.model.SlayerData;
import sbs.modid.client.combat.slayer.render.SlayerAlerts;
import sbs.modid.client.combat.slayer.render.SlayerHighlight;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.CarryCounterSettings;
import sbs.modid.client.core.config.SBSConfig.SlayerSettings;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Slayer module's brain: quest/boss state, the Voidgloom fight helpers (beacon, nukekubi heads,
 * phase), the Revenant enrage/TNT, Tarantula egg-sac and Sven pup helpers, miniboss detection for
 * every slayer, the Blaze pillar and Vampire attack alerts, and the session tracker (bosses, kill
 * times, drops, profit).
 *
 * <p><b>State sources.</b> The sidebar tells whether a quest/boss is up ("Slay the boss!" /
 * "Boss slain!") and which slayer it is; the world scan finds the boss entity (nearest matching
 * slayer boss while yours is up), the thrown beacon (block scan around the boss), nukekubi heads
 * (skull-wearing stands near the boss) and minibosses (nametag names). Chat books the session
 * events: quest started, boss slain (kill time, boss counter), rare-drop lines, and the
 * "[Sacks]" breakdown for bulk drops. Prices use instasell-first like every SBS profit view.
 */
public final class SlayerTracker {

    private static final SlayerTracker INSTANCE = new SlayerTracker();

    private static final long SCAN_INTERVAL_MS = 250;
    private static final long LOOT_WINDOW_MS = 12_000L;
    private static final long IDLE_HIDE_MS = 10 * 60_000L;

    /** One "[Sacks]" hover line: "+24 Revenant Flesh (Slayer Sack)". */
    private static final Pattern SACK_LINE = Pattern.compile("([+-][\\d,.]+) (.+?) \\((.+)\\)");
    /**
     * The Blaze fire-pillar label: the seconds left before it explodes and the hits still needed to
     * break it, e.g. {@code "7s 8 hits"}.
     *
     * <p>Matched against the WHOLE stripped tag rather than searched inside it. An Inferno arena is
     * full of floating numbers – boss health, other people's bosses, damage tags – and a loose search
     * for "a number followed by s" hits most of them.
     */
    private static final Pattern FIRE_PILLAR =
            Pattern.compile("(\\d+)s (\\d+) hits?", Pattern.CASE_INSENSITIVE);

    /** How far a pillar may stand from your boss (or from you) and still be yours. */
    private static final int PILLAR_RANGE = 24;
    /** The countdown only becomes an alert this late – it starts at 7s and screaming for all of it is noise. */
    private static final int PILLAR_ALERT_SECONDS = 5;
    /** No pillar tag for this long ends the countdown (it was broken, or it went off). */
    private static final long PILLAR_FORGET_MS = 1_500L;

    /**
     * The line that says whose slayer boss a nametag stack belongs to, e.g.
     * {@code "Spawned by: Panikstrange"}. It is the only owner information in the world – everything
     * else about two players' bosses looks identical – so it is what turns "which of these is mine"
     * from a guess into a fact.
     */
    private static final Pattern SPAWNED_BY = Pattern.compile("Spawned by: (\\S+)");

    /**
     * The Blaze attunement tag: the word, optionally followed by the shield's number, e.g.
     * {@code "ASHEN ✦6 04:16"}. The number is only taken when nothing digit-like or a colon follows
     * it, so the quest clock on the same line is never mistaken for it.
     */
    private static final Pattern ATTUNEMENT_TAG = Pattern.compile(
            "\\b(?:ASHEN|SPIRIT|AURIC|CRYSTAL)\\b(?:\\D{0,4}(\\d{1,2})(?![\\d:]))?",
            Pattern.CASE_INSENSITIVE);

    /** How far a tag may sit from your own "Spawned by" line and still be part of the same stack. */
    private static final double OWNED_STACK_RANGE = 4.0;
    /** How far a demon may have wandered from where your fight was and still be yours. */
    private static final double DEMON_RANGE = 12.0;
    /** The fight anchor goes stale this long after the last sign of your own Inferno fight. */
    private static final long ANCHOR_TTL_MS = 15_000L;
    /** The attunement readout stops drawing this long after the tag was last seen. */
    private static final long ATTUNEMENT_FRESH_MS = 2_000L;
    /** Hits the shield takes before it hands over – anything outside 0..8 is not a hit count. */
    private static final int SHIELD_HITS = 8;

    /**
     * How far to either side of the boss's nametag its mob is looked for. Wider than the miniboss
     * search because the Voidgloom's hit phase teleports it a block in any direction many times a
     * second, so the tag and the mob under it are routinely a little out of line.
     */
    private static final double BOSS_TAG_RADIUS = 2.0;
    /** How far the boss already boxed may sit from its nametag before it stops counting as that boss. */
    private static final double BOSS_KEEP_RANGE = 8.0;

    /** How far a head may drift from where it was first seen and still count as never having moved. */
    private static final double HEAD_DRIFT = 0.5;
    /**
     * A head already this old the first time the fight sees it did not come out of this fight. The
     * boss only starts throwing fixations below a third of its health, and they are cleared by half
     * a second of looking at them, so anything that was standing there long before that is a
     * deployable – a flare burns for three minutes and is usually planted before the boss is even
     * called. Measured once, at first sight: a head that simply stands still for a long time is NOT
     * a flare, since fixations float in place too.
     */
    private static final long DEPLOYABLE_AGE_MS = 20_000L;
    /** How long after a flare was last in your hand a new head next to you counts as that flare. */
    private static final long FLARE_HAND_MS = 5_000L;
    /** How far from you a head may appear and still be the flare you just deployed. */
    private static final double FLARE_DEPLOY_RANGE = 12.0;
    /** A head this new (in ticks) is one that just spawned – see {@link #trackHead}. */
    private static final int HEAD_FRESH_TICKS = 60;
    /** Head tracks are forgotten this long after the head was last seen. */
    private static final long HEAD_FORGET_MS = 60_000L;

    /**
     * How far a state line ("Protected", "ENRAGED", …) may float from your boss's nametag and still
     * describe your boss. State words ride the same nametag stack as the boss line, so this only
     * has to span the stack plus the hit phase's one-block teleporting.
     */
    private static final double STATE_TAG_RANGE = 5.0;

    /**
     * The words that mean a Revenant boss has changed state: T3/T4 go "Mad" and then "Enraged"
     * (faster, harder-hitting), and the Atoned Horror (T5) announces the charge-up of its huge
     * blast. All three are worth a warning, and all three are printed at the boss – the wording is
     * a best guess to be tuned off the tags-near-boss log.
     */
    private static final Pattern REV_STATE =
            Pattern.compile("\\b(mad|enraged|boom)\\b", Pattern.CASE_INSENSITIVE);
    /** Sven at half health: "Calling the pups!" then "Protected." while they live. */
    private static final Pattern SVEN_STATE =
            Pattern.compile("\\b(calling the pups|protected)\\b", Pattern.CASE_INSENSITIVE);
    /** Tarantula's egg phase wraps the boss (invulnerable) while its egg sacs are down. */
    private static final Pattern TARA_STATE =
            Pattern.compile("\\b(cocooned?|web of lies)\\b", Pattern.CASE_INSENSITIVE);

    /** Re-alert throttle for the pup / egg sighting alerts (both spawn as a burst, alert once). */
    private static final long BROOD_ALERT_MS = 10_000L;
    /** Re-alert throttle for the Atoned Horror's TNT (thrown every second while the attack runs). */
    private static final long TNT_ALERT_MS = 3_000L;

    private static final char SECTION_SIGN = (char) 0x00A7;

    /**
     * One highlighted world box handed to {@link SlayerHighlight}.
     *
     * <p><b>Why a box carries its entity.</b> The scan runs four times a second, but a Voidgloom in
     * its hit phase teleports within a block up to sixty times a second and every mob walks between
     * ticks – a box frozen at scan time therefore hangs next to the mob instead of around it, which
     * reads as the box having the wrong size and sitting up by the nametag. {@code anchor} is the
     * entity the box was measured on and {@code anchorPos} where that entity stood at the time, so
     * {@link #currentBox()} can re-place the box – offsets and inflation included – on wherever the
     * entity is when the frame is drawn.
     *
     * <p>{@code tracer} is the box's own crosshair line, for the highlights that have a per-target
     * tracer toggle (beacon, nukekubi heads); the module-wide toggle is applied by the renderer on
     * top of it.
     */
    public record HighlightBox(AABB box, int argb, String label, boolean tracer, Entity anchor, Vec3 anchorPos) {

        /** A box on something that does not move: a block, a marker stand. */
        public HighlightBox(AABB box, int argb, String label) {
            this(box, argb, label, false, null, null);
        }

        /** A box on something that does not move, with its own tracer line. */
        public HighlightBox(AABB box, int argb, String label, boolean tracer) {
            this(box, argb, label, tracer, null, null);
        }

        /** A box measured on an entity, re-placed on that entity every frame. */
        public static HighlightBox onEntity(Entity anchor, AABB box, int argb, String label, boolean tracer) {
            return new HighlightBox(box, argb, label, tracer, anchor, anchor.position());
        }

        /** The box around where its entity is right now (the box as taken when it has no entity). */
        public AABB currentBox() {
            if (anchor == null) {
                return box;
            }
            Vec3 now = anchor.position();
            return box.move(now.x - anchorPos.x, now.y - anchorPos.y, now.z - anchorPos.z);
        }

        /** Whether the entity this box belongs to is gone, i.e. the box must not be drawn any more. */
        public boolean gone() {
            return anchor != null && !anchor.isAlive();
        }
    }

    /**
     * One attunement word found in the world, with where it floats and how it is drawn. {@code tag}
     * is the whole line it came out of, carried purely so the diagnostic can print what was parsed.
     */
    private record AttunementTag(BlazeAttunement attunement, int hits, int argb, Vec3 pos, String tag) {
    }

    /** One slayer-boss nametag: the mob under it, where the tag floats, and its phase wording. */
    private record BossTag(LivingEntity mob, Vec3 pos, boolean hitsPhase, String state) {
    }

    /** One state word found floating in the world, with where – claimed by the boss it stands at. */
    private record StateTag(Vec3 pos, String word) {
    }

    /**
     * What is remembered about one skull-wearing stand near the boss between scans – enough to tell
     * a nukekubi fixation from a deployable standing in the same fight. See {@link #trackHead}.
     */
    private static final class HeadTrack {
        /** Where the head was when it was first seen, and whether it has left that spot since. */
        private final Vec3 spawnPos;
        /** How long the head had already been in the world when the fight first saw it (ms). */
        private final long ageAtFirstSight;
        private long lastSeenAt;
        private boolean moved;
        /** Whether this head turned up as a flare out of your own hand. Cleared the moment it moves. */
        private boolean deployed;

        private HeadTrack(Vec3 spawnPos, long ageAtFirstSight, long now) {
            this.spawnPos = spawnPos;
            this.ageAtFirstSight = ageAtFirstSight;
            this.lastSeenAt = now;
        }
    }

    /**
     * Boss box phases (colour-selectable). BEACON and HITS are the Voidgloom's; STATE is the other
     * slayers' special moments – Rev enraged, Sven protected, Tara cocooned – sharing one colour.
     */
    private enum Phase { NORMAL, BEACON, HITS, STATE }

    // ---- live fight state (rebuilt every scan) ----
    private volatile List<HighlightBox> highlightBoxes = List.of();
    private SlayerBoss questBoss;
    /**
     * Whether a slayer quest of your own is running right now. Distinct from {@link #questBoss},
     * which is sticky (it remembers WHICH slayer you last did so a fight helper still knows the type
     * after the sidebar line goes), and wider than {@link #bossUp}: the quest is already active while
     * you are still filling the XP bar, which is exactly when minibosses matter.
     */
    private boolean questActive;
    private boolean bossUp;
    private long bossSpawnAt;
    private LivingEntity bossEntity;
    private BlockPos beaconPos;
    private long lastBeaconAlertAt;
    private final Set<Integer> alertedEntities = new HashSet<>();
    /** Skull-wearing stands seen near the boss, by entity id – see {@link #trackHead}. */
    private final Map<Integer, HeadTrack> headTracks = new HashMap<>();
    /** When a flare was last sitting in your main hand, i.e. when one could have been deployed. */
    private long flareInHandAt;
    /** Throttle for the head-candidate diagnostic. */
    private long lastHeadLogAt;
    /** Throttle for the "boss tag over the wrong mob" diagnostic. */
    private long lastBossSpeciesLogAt;
    /**
     * The special-state word currently standing at your boss (upper-cased), or {@code null}. What
     * turns into the STATE phase colour, and – via {@link #alertedState} – into one alert per
     * occurrence rather than one per scan.
     */
    private String bossState;
    /** The state already alerted, cleared when the boss leaves it so the next one alerts again. */
    private String alertedState;
    /** Throttle for the Sven pup sighting alert. */
    private long lastPupAlertAt;
    /** Throttle for the Tarantula egg sighting alert. */
    private long lastEggAlertAt;
    /** Throttle for the Atoned Horror TNT alert. */
    private long lastTntAlertAt;
    /** Throttle for the tags-near-boss diagnostic. */
    private long lastStackLogAt;
    /** Throttle for the "miniboss hidden" diagnostic – it would otherwise print every scan. */
    private long lastMinibossGateLogAt;

    /**
     * How far a miniboss may be from you to be drawn at all. Wide enough to cover the whole loaded
     * part of a slayer area, because with ownership filtering off the point IS the rest of the lobby.
     */
    private static final double MINIBOSS_LOBBY_RANGE_SQ = 100 * 100;

    /**
     * How close a miniboss has to be to your own boss to count as yours. Generous: minibosses walk
     * off after spawning, and a miss here hides the thing the feature exists for.
     */
    private static final double MINIBOSS_OWN_RANGE_SQ = 30 * 30;

    /**
     * Whether a miniboss belongs to your fight.
     *
     * <p>Unlike a slayer boss, a miniboss carries no {@code Spawned by:} line, so there is no owner
     * to read - but yours spawn at your boss, so being near your boss entity is the same statement.
     * Before a boss is known (the seconds while the XP bar fills, which is exactly when minibosses
     * appear) the test falls back to distance from you, which at that moment means the same thing.
     */
    private boolean minibossIsOwn(LivingEntity mob, LocalPlayer player) {
        LivingEntity boss = bossEntity;
        if (boss != null && boss.isAlive()) {
            return mob.distanceToSqr(boss) < MINIBOSS_OWN_RANGE_SQ;
        }
        return mob.distanceToSqr(player) < MINIBOSS_OWN_RANGE_SQ;
    }
    /**
     * The fire-pillar second that is currently on screen, or {@code -1} while no pillar is up. Scans
     * run four times a second but the label only ticks once a second, so this is what turns "the tag
     * is still there" into one alert per second of the countdown.
     */
    private int pillarSecondShown = -1;
    /** When a pillar label was last seen, so a broken pillar ends its countdown. */
    private long pillarSeenAt;
    /** Throttle for the "pillar ignored" diagnostic. */
    private long lastPillarGateLogAt;

    // ---- Blaze attunement ----
    /** The mode your dagger has to be on right now, or {@code null} while none is known. */
    private volatile BlazeAttunement neededAttunement;
    /** That mode's colour as Hypixel draws it, so the readout matches the tag on the boss. */
    private volatile int attunementColor;
    /** The mode your own dagger is switched to, or {@code null} – see {@link #scanHotbarDagger}. */
    private volatile BlazeAttunement heldAttunement;
    /** The hotbar slot that dagger sits in, or {@code -1} when it is not in the hotbar. */
    private volatile int daggerSlot = -1;
    private volatile long attunementSeenAt;
    /**
     * Where your Inferno fight is. Pinned exactly wherever your own "Spawned by" tag is visible, and
     * carried by the demons while it is not – see {@link #resolveAttunement}.
     */
    private Vec3 blazeAnchor;
    private long blazeAnchorAt;
    /** Throttle for the raw-attunement-tag diagnostic. */
    private long lastAttunementLogAt;

    // ---- session stats ----
    private int bossesSlain;
    private int questsFailed;
    private long killTimeTotalMs;
    private int killTimeCount;
    private long lastSlainAt;
    /** When a kill was last BOOKED – dedupes the two announcement lines of one kill. */
    private long lastSlainBookedAt;
    private long cycleTotalMs;
    private int cycleCount;
    private final Map<String, Integer> drops = new LinkedHashMap<>();
    private long lootWindowUntil;
    private long firstEventAt;
    private long lastEventAt;

    private long lastScanAt;

    private SlayerTracker() {
    }

    public static SlayerTracker getInstance() {
        return INSTANCE;
    }

    /** Whether your own slayer boss is up right now (read-only; Hide Nearby Players stands down). */
    public boolean bossUp() {
        return bossUp;
    }

    private static SlayerSettings cfg() {
        return ConfigManager.getInstance().get().slayer;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick (throttled internally). */
    public void onClientTick() {
        SlayerSettings settings = cfg();
        long now = System.currentTimeMillis();
        if (settings.enabled && settings.rngMeter) {
            RngMeterTracker.getInstance().onClientTick();   // throttled on its own
        }
        if (!settings.enabled || now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            highlightBoxes = List.of();
            bossEntity = null;
            bossUp = false;
            questActive = false;
            return;
        }
        readSidebar(now);
        scanHeldFlare(player, now);

        List<HighlightBox> boxes = new ArrayList<>();
        scanEntities(level, player, boxes, now, settings);
        scanBeacon(level, boxes, now, settings);
        highlightBoxes = List.copyOf(boxes);
    }

    /**
     * Quest/boss state from the sidebar: which slayer, whether a quest is running, and whether the
     * boss is up right now.
     *
     * <p>The quest section only exists in the sidebar while a quest does, so any of its three shapes
     * proves one is running: the "Slayer Quest" header, the boss line under it ("☠ Revenant Horror
     * IV") and the "Slay the boss!" state it turns into.
     */
    private void readSidebar(long now) {
        boolean slayBoss = false;
        boolean quest = false;
        SlayerBoss sidebarBoss = null;
        for (String line : DungeonScoreboard.sidebarLines()) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("slayer quest")) {
                quest = true;
            }
            if (lower.contains("slay the boss")) {
                slayBoss = true;
            }
            SlayerBoss matched = SlayerBoss.matchNametag(line);
            if (matched != null) {
                sidebarBoss = matched;
                quest = true;
            }
        }
        questActive = quest || slayBoss;
        if (sidebarBoss != null) {
            questBoss = sidebarBoss;
        }
        if (slayBoss && !bossUp) {
            bossSpawnAt = now;   // the boss just spawned - kill time starts here
            touch(now);
        }
        if (!slayBoss) {
            bossEntity = null;
            beaconPos = null;
            pillarSecondShown = -1;
            bossState = null;
            alertedState = null;
            // Drop the fight anchor too: with no boss of your own, the nearest attuned mob is by
            // definition somebody else's, and a stale anchor is exactly how it would latch onto one.
            blazeAnchor = null;
            clearAttunement();
        }
        bossUp = slayBoss;
    }

    /** Boss entity, phase colour, nukekubi heads, minibosses, pillars, attunement, vampire stands. */
    private void scanEntities(ClientLevel level, LocalPlayer player, List<HighlightBox> boxes,
                              long now, SlayerSettings settings) {
        boolean minibossHelpers = minibossHelpersAllowed(settings);
        boolean readAttunement = questBoss == SlayerBoss.INFERNO && blazeHelpersOn(settings);
        String myName = player.getGameProfile().name();

        // Collected here, decided after the loop: whose boss a tag belongs to is settled by the
        // "Spawned by" line, and the world hands out entities in no particular order, so that line
        // can turn up long after the tags it explains.
        Vec3 ownerPos = null;
        List<BossTag> bossTags = new ArrayList<>();
        List<AttunementTag> attunementTags = new ArrayList<>();
        Pattern stateWords = bossUp ? stateWordsFor(questBoss) : null;
        List<StateTag> stateTags = stateWords == null ? List.of() : new ArrayList<>();
        // Wording sampler: every 30s of a Rev/Tara/Sven fight, remember every tag in sight so the
        // ones near the boss can be printed afterwards - the state words, pup and egg names in here
        // are educated guesses, and that log is what turns them into the real wording.
        List<StateTag> tagSample =
                stateWords != null && now - lastStackLogAt > 30_000L ? new ArrayList<>() : null;

        // A pillar seen nowhere this scan ends the countdown: it was broken, or it already went off.
        if (now - pillarSeenAt > PILLAR_FORGET_MS) {
            pillarSecondShown = -1;
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.hasCustomName()) {
                continue;
            }
            var custom = entity.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            String name = strip(raw);
            if (name.isEmpty()) {
                continue;
            }
            if (tagSample != null && entity.distanceToSqr(player) < 40 * 40) {
                tagSample.add(new StateTag(entity.position(), name));
            }

            // The fire pillar is checked before the armor-stand filter below: its label rides on
            // whatever entity Hypixel builds the pillar out of, which is not a nametag stand.
            if (scanFirePillar(entity, name, player, now, settings)) {
                continue;
            }

            Matcher owner = SPAWNED_BY.matcher(name);
            if (owner.find()) {
                if (owner.group(1).equalsIgnoreCase(myName)) {
                    ownerPos = entity.position();
                }
                continue;
            }
            if (readAttunement) {
                AttunementTag tag = readAttunementTag(entity, raw, name);
                if (tag != null) {
                    attunementTags.add(tag);
                    continue;
                }
            }

            if (!(entity instanceof ArmorStand stand)) {
                continue;
            }

            // A boss of the slayer you are on. WHICH of them is yours is decided after the loop.
            if (bossUp && questBoss != null && SlayerBoss.matchNametag(name) == questBoss) {
                LivingEntity mob = mobBelow(level, stand, BOSS_TAG_RADIUS, bossSpecies(questBoss));
                if (mob != null) {
                    bossTags.add(new BossTag(mob, entity.position(),
                            name.toLowerCase(Locale.ROOT).contains("hits"),
                            stateIn(stateWords, name)));
                }
                continue;
            }

            // A state word on its own line ("Protected.", "ENRAGED"). Which boss it describes is
            // settled after the loop, exactly like the boss tags themselves.
            if (stateWords != null) {
                String word = stateIn(stateWords, name);
                if (word != null) {
                    stateTags.add(new StateTag(entity.position(), word));
                    continue;
                }
            }

            // Sven Pups: the wolves the boss hides behind at half health. Killing them is the only
            // way to make it hittable again, so they are boxed the moment they are called.
            if (questBoss == SlayerBoss.SVEN && bossUp
                    && name.toLowerCase(Locale.ROOT).contains("sven pup")
                    && (settings.svenPupHighlight || settings.svenPupAlert)
                    && stand.distanceToSqr(player) < 40 * 40) {
                if (settings.svenPupHighlight) {
                    boxes.add(broodBox(level, stand, settings.svenPupColor.argb(), "Pup", "wolf"));
                }
                if (settings.svenPupAlert && now - lastPupAlertAt > BROOD_ALERT_MS) {
                    lastPupAlertAt = now;
                    SlayerAlerts.getInstance().trigger("PUPS!", settings.svenPupColor.argb());
                }
                continue;
            }

            // Tarantula egg sacs: laid at 66%/33%, hatch if not broken while the boss sits it out
            // cocooned. Named-stand match on "egg"/"sac" - wording best-guess, see the tag sampler.
            if (questBoss == SlayerBoss.TARANTULA && bossUp
                    && (settings.taraEggHighlight || settings.taraEggAlert)
                    && stand.distanceToSqr(player) < 40 * 40) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("egg") || lower.contains("sac")) {
                    if (settings.taraEggHighlight) {
                        boxes.add(broodBox(level, stand, settings.taraEggColor.argb(), "Egg", null));
                    }
                    if (settings.taraEggAlert && now - lastEggAlertAt > BROOD_ALERT_MS) {
                        lastEggAlertAt = now;
                        SlayerAlerts.getInstance().trigger("EGGS!", settings.taraEggColor.argb());
                    }
                    continue;
                }
            }

            // Minibosses (any slayer): named stand -> the mob below it.
            SlayerBoss minibossOf = SlayerData.minibossOf(name);
            if (minibossOf != null) {
                if (!settings.minibossAlert && !settings.minibossHighlight) {
                    continue;
                }
                if (!minibossHelpers) {
                    logMinibossGate(name, now);
                    continue;
                }
                LivingEntity mob = mobBelow(level, stand);
                if (mob != null && mob.distanceToSqr(player) < MINIBOSS_LOBBY_RANGE_SQ
                        && (!settings.minibossOwnOnly || minibossIsOwn(mob, player))) {
                    // Once the boss is up the box is the player's call, per slayer: before the
                    // spawn a miniboss is the thing worth turning for, during the fight it is
                    // usually one more box competing with the boss's own. Keyed on the miniboss's
                    // OWN slayer rather than on questBoss, which is sticky and outlives the fight.
                    if (settings.minibossHighlight
                            && (!bossUp || settings.minibossInFight(minibossOf))) {
                        // Anchored: a miniboss walks, and a box snapshotted on the 250ms scan would
                        // trail behind it the way the boss box used to.
                        boxes.add(HighlightBox.onEntity(mob, mob.getBoundingBox(),
                                settings.minibossColor.argb(), "Miniboss", settings.minibossTracer));
                    }
                    if (settings.minibossAlert && alertedEntities.add(mob.getId())) {
                        SlayerAlerts.getInstance().trigger("MINIBOSS", settings.minibossColor.argb());
                    }
                }
                continue;
            }

            // Vampire: Twinclaws warning + Blood Ichor / Killer Spring highlights.
            if (questBoss == SlayerBoss.BLOODFIEND && bossUp) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (settings.twinclawsAlert && lower.contains("twinclaws")
                        && alertedEntities.add(stand.getId())) {
                    SlayerAlerts.getInstance().trigger("TWINCLAWS", 0xFFFF2020);
                }
                if (settings.ichorHighlight
                        && (lower.contains("blood ichor") || lower.contains("killer spring"))) {
                    boxes.add(new HighlightBox(stand.getBoundingBox().inflate(0.3),
                            lower.contains("ichor") ? 0xFFFF2020 : 0xFF30E030,
                            lower.contains("ichor") ? "Ichor" : "Spring"));
                }
            }
        }

        BossTag mine = pickOwnBoss(bossTags, ownerPos, player);
        LivingEntity closestBoss = stickToBoss(mine, now);
        bossEntity = closestBoss;
        resolveAttunement(attunementTags, ownerPos, now, settings);
        scanHotbarDagger(player);
        updateBossState(mine, stateTags, settings);
        if (tagSample != null && mine != null) {
            logTagsNearBoss(tagSample, mine.pos(), now);
        }

        // Boss phase box. Anchored to the boss so it follows the hit phase's teleporting instead of
        // hanging wherever the boss was when the scan ran.
        if (closestBoss != null && settings.phaseHighlight) {
            Phase phase = mine.hitsPhase() ? Phase.HITS
                    : beaconPos != null ? Phase.BEACON
                    : bossState != null ? Phase.STATE : Phase.NORMAL;
            int color = switch (phase) {
                case HITS -> settings.phaseHitsColor.argb();
                case BEACON -> settings.phaseBeaconColor.argb();
                case STATE -> settings.bossStateColor.argb();
                case NORMAL -> settings.phaseNormalColor.argb();
            };
            boxes.add(HighlightBox.onEntity(closestBoss, closestBoss.getBoundingBox(), color, null, false));
        }

        // Nukekubi fixations: skull-wearing stands near the Voidgloom boss.
        if (questBoss == SlayerBoss.VOIDGLOOM && bossUp && closestBoss != null
                && (settings.nukekubiHighlight || settings.nukekubiAlert)) {
            scanNukekubi(level, player, closestBoss, boxes, now, settings);
        }

        // The Atoned Horror's thrown TNT (Rev T5): every hit heals it half a million, so the throw
        // is worth both the box and the flash.
        if (questBoss == SlayerBoss.REVENANT && bossUp && closestBoss != null
                && (settings.revTntHighlight || settings.revTntAlert)) {
            scanRevTnt(level, closestBoss, boxes, now, settings);
        }
    }

    /**
     * The special-state word standing at your boss right now – on the boss's own line, or on a line
     * of its own within {@link #STATE_TAG_RANGE} of the tag. One alert per occurrence: the word has
     * to leave (boss back to normal) before the same word alerts again, so a Rev that enrages every
     * cycle flashes once per enrage instead of four times a second.
     */
    private void updateBossState(BossTag mine, List<StateTag> stateTags, SlayerSettings settings) {
        String state = mine == null ? null : mine.state();
        if (state == null && mine != null) {
            double bestSqr = STATE_TAG_RANGE * STATE_TAG_RANGE;
            for (StateTag tag : stateTags) {
                double sqr = tag.pos().distanceToSqr(mine.pos());
                if (sqr < bestSqr) {
                    bestSqr = sqr;
                    state = tag.word();
                }
            }
        }
        bossState = state == null ? null : state.toUpperCase(Locale.ROOT);
        if (bossState == null) {
            alertedState = null;
            return;
        }
        if (!bossState.equals(alertedState)) {
            alertedState = bossState;
            if (stateAlertOn(settings)) {
                SlayerAlerts.getInstance().trigger(bossState + "!", settings.bossStateColor.argb());
            }
        }
    }

    /**
     * The state words worth watching for the current slayer, or {@code null} when it has none. Also
     * what decides whether the tag sampler runs – the three are the fights whose wording is still
     * unconfirmed.
     */
    private static Pattern stateWordsFor(SlayerBoss boss) {
        if (boss == null) {
            return null;
        }
        return switch (boss) {
            case REVENANT -> REV_STATE;
            case SVEN -> SVEN_STATE;
            case TARANTULA -> TARA_STATE;
            default -> null;
        };
    }

    /** The state word inside a tag, or {@code null}. */
    private static String stateIn(Pattern stateWords, String name) {
        if (stateWords == null) {
            return null;
        }
        Matcher matcher = stateWords.matcher(name);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Whether the state alert is wanted for the current slayer. Sven's is deliberately off: the
     * pups themselves alert ("PUPS!"), and "Protected" appearing at the same moment would just say
     * it twice.
     */
    private boolean stateAlertOn(SlayerSettings settings) {
        return switch (questBoss) {
            case REVENANT -> settings.revEnrageAlert;
            case TARANTULA -> settings.taraEggAlert;
            default -> false;
        };
    }

    /**
     * The box for one of a boss's brood (a pup, an egg sac): the mob under the named stand when one
     * is there, the stand itself when the thing IS just the stand. Anchored either way, so the box
     * rides along when it moves.
     */
    private static HighlightBox broodBox(ClientLevel level, ArmorStand stand, int argb, String label,
                                   String species) {
        LivingEntity mob = mobBelow(level, stand, 1.0, species);
        Entity anchor = mob != null ? mob : stand;
        AABB box = mob != null ? mob.getBoundingBox().inflate(0.1)
                : stand.getBoundingBox().inflate(0.3);
        return HighlightBox.onEntity(anchor, box, argb, label, false);
    }

    /**
     * The Atoned Horror's TNT: real primed-TNT entities thrown at the circle under your feet. Only
     * ones near your own boss count – the Rev area is shoulder to shoulder, and everyone's T5
     * throws the identical thing.
     */
    private void scanRevTnt(ClientLevel level, LivingEntity boss, List<HighlightBox> boxes, long now,
                            SlayerSettings settings) {
        for (PrimedTnt tnt : level.getEntitiesOfClass(PrimedTnt.class,
                boss.getBoundingBox().inflate(24, 12, 24), Entity::isAlive)) {
            if (settings.revTntHighlight) {
                boxes.add(HighlightBox.onEntity(tnt, tnt.getBoundingBox().inflate(0.15),
                        settings.revTntColor.argb(), "TNT", false));
            }
            if (settings.revTntAlert && now - lastTntAlertAt > TNT_ALERT_MS) {
                lastTntAlertAt = now;
                SlayerAlerts.getInstance().trigger("TNT!", settings.revTntColor.argb());
            }
        }
    }

    /**
     * The wording log for the Rev/Tara/Sven helpers: every nametag within 6 blocks of your boss,
     * once per 30s of a fight. The state words, "Sven Pup" and the egg-sac names in this class are
     * educated guesses – this line is what confirms or corrects them from a real fight.
     */
    private void logTagsNearBoss(List<StateTag> tagSample, Vec3 bossPos, long now) {
        List<String> near = new ArrayList<>();
        for (StateTag tag : tagSample) {
            if (tag.pos().distanceToSqr(bossPos) < 6 * 6) {
                near.add(tag.word());
            }
        }
        if (near.isEmpty()) {
            return;
        }
        lastStackLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Slayer] tags near your {} boss: {}", questBoss, near);
    }

    /**
     * The mob to box as your boss, out of what stands under your own nametag.
     *
     * <p>The tag alone is not enough during the Voidgloom's hit phase: the boss teleports inside a
     * one-block radius many times a second, so for whole scans the thing nearest the tag is not the
     * boss at all but whatever else is floating there – which is what put a small box up by the
     * nametag instead of one around the mob. A slayer boss is always the same vanilla mob (an
     * enderman here), so when the tag hands over something else and the boss already boxed is still
     * alive next to that tag, the boss already boxed is kept.
     */
    private LivingEntity stickToBoss(BossTag mine, long now) {
        if (mine == null || mine.mob() == null) {
            return null;
        }
        LivingEntity found = mine.mob();
        String species = bossSpecies(questBoss);
        if (species == null || species.equals(speciesOf(found))) {
            return found;
        }
        LivingEntity previous = bossEntity;
        boolean keep = previous != null && previous.isAlive() && species.equals(speciesOf(previous))
                && previous.distanceToSqr(mine.pos()) < BOSS_KEEP_RANGE * BOSS_KEEP_RANGE;
        logBossSpecies(found, keep ? previous : null, species, now);
        return keep ? previous : found;
    }

    /**
     * The vanilla mob a slayer boss is built from, or {@code null} when it is not one – the vampire
     * boss is player-shaped, so there is nothing to hold its box to.
     */
    private static String bossSpecies(SlayerBoss boss) {
        if (boss == null) {
            return null;
        }
        return switch (boss) {
            case REVENANT -> "zombie";
            case TARANTULA -> "spider";
            case SVEN -> "wolf";
            case VOIDGLOOM -> "enderman";
            case INFERNO -> "blaze";
            case BLOODFIEND -> null;
        };
    }

    /** An entity's type path ({@code "enderman"}, {@code "zombie"}, …). */
    private static String speciesOf(Entity entity) {
        return EntityType.getKey(entity.getType()).getPath();
    }

    /**
     * One line every 30s while the boss nametag is sitting over something that is not the slayer's
     * mob. Names what was found and what was done about it – the hit phase is the case this exists
     * for, and knowing which entity Hypixel parks under the tag there is what would let the pick be
     * made exact rather than kept steady.
     */
    private void logBossSpecies(LivingEntity found, LivingEntity kept, String species, long now) {
        if (now - lastBossSpeciesLogAt < 30_000L) {
            return;
        }
        lastBossSpeciesLogAt = now;
        AABB box = found.getBoundingBox();
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Slayer] boss tag sits over a {} ({}) instead of a {} - {}",
                speciesOf(found),
                String.format(Locale.US, "%.1fx%.1f", box.getXsize(), box.getYsize()), species,
                kept != null ? "kept the " + speciesOf(kept) + " already boxed" : "boxing it anyway");
    }

    /**
     * Which of the slayer bosses standing around is yours.
     *
     * <p>Hypixel writes the owner into the nametag stack ("Spawned by: …"), so when your own line was
     * found this scan the answer is exact: your boss is the one whose name tag shares that stack. Only
     * when the line is missing does this fall back to the old guess – the nearest one – which is right
     * alone in an area and a coin flip in a busy one.
     */
    private BossTag pickOwnBoss(List<BossTag> tags, Vec3 ownerPos, LocalPlayer player) {
        BossTag best = null;
        double bestSqr = Double.MAX_VALUE;
        for (BossTag tag : tags) {
            double sqr = ownerPos != null
                    ? tag.pos().distanceToSqr(ownerPos) : tag.mob().distanceToSqr(player);
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = tag;
            }
        }
        if (ownerPos != null && bestSqr > OWNED_STACK_RANGE * OWNED_STACK_RANGE) {
            return null;   // bosses are up, but none of them stands under your own line
        }
        return best;
    }

    /** One attunement word off a nametag, with its number and the colour Hypixel drew it in. */
    private static AttunementTag readAttunementTag(Entity entity, String raw, String name) {
        BlazeAttunement attunement = BlazeAttunement.inNametag(name);
        if (attunement == null) {
            return null;
        }
        int hits = -1;
        Matcher matcher = ATTUNEMENT_TAG.matcher(name);
        if (matcher.find() && matcher.group(1) != null) {
            int parsed = parseInt(matcher.group(1));
            hits = parsed >= 0 && parsed <= SHIELD_HITS ? parsed : -1;
        }
        int argb = nametagColor(raw, attunement.name());
        return new AttunementTag(attunement, hits,
                argb != 0 ? argb : attunement.fallbackColor(), entity.position(), name);
    }

    /**
     * Settles which attunement is the one YOU have to match, out of every attuned mob in sight.
     *
     * <p>The hard part is not reading the word – it is printed plainly – but knowing whose fight it
     * belongs to, and that changes shape halfway through. While the Demonlord is whole its stack
     * carries your name, so the tag next to that name is yours, full stop. When it splits into the two
     * demons the name goes with it: the demons are labelled with their attunement and nothing else.
     *
     * <p>So the fight keeps an anchor instead of an identity. It is re-pinned to your own "Spawned by"
     * line every scan that line exists, and while it does not, it rides along with the attuned mob it
     * already pointed at – the demons walk, and an anchor left behind where the boss stood would go
     * stale in seconds.
     *
     * <p>Both demons usually sit inside that radius and only one of them can be hit at a time, which
     * the tags do not say; the nearer one to the anchor is reported, on the grounds that the one
     * engaging you is the one you are meant to be swinging at.
     */
    private void resolveAttunement(List<AttunementTag> tags, Vec3 ownerPos, long now,
                                   SlayerSettings settings) {
        if (questBoss != SlayerBoss.INFERNO || !blazeHelpersOn(settings)) {
            clearAttunement();
            return;
        }
        if (ownerPos != null) {
            blazeAnchor = ownerPos;
            blazeAnchorAt = now;
        }
        if (blazeAnchor == null || now - blazeAnchorAt > ANCHOR_TTL_MS) {
            logAttunementGate(tags, "no fight anchor", now);
            clearAttunement();
            return;
        }

        double range = ownerPos != null ? OWNED_STACK_RANGE : DEMON_RANGE;
        AttunementTag primary = null;
        double bestDistance = Double.MAX_VALUE;
        for (AttunementTag tag : tags) {
            double distance = tag.pos().distanceTo(blazeAnchor);
            if (distance <= range && distance < bestDistance) {
                bestDistance = distance;
                primary = tag;
            }
        }
        if (primary == null) {
            logAttunementGate(tags, "all of them further than " + range + " from the anchor", now);
            clearAttunement();
            return;
        }
        if (ownerPos == null) {
            blazeAnchor = primary.pos();   // the demons carry the fight; follow them
            blazeAnchorAt = now;
        }
        BlazeAttunement previous = neededAttunement;
        neededAttunement = primary.attunement();
        attunementColor = primary.argb();
        attunementSeenAt = now;
        if (previous != neededAttunement) {
            logAttunement(primary, ownerPos != null, now);
            if (settings.attunementAlert) {
                SlayerAlerts.getInstance().trigger("SWAP: " + neededAttunement, primary.argb());
            }
        }
    }

    /**
     * Whether anything at all wants the attunement read. All three Blaze helpers hang off the same
     * parse, so it runs when any one of them is on and is skipped outright when none is – the tag
     * regex would otherwise be walked over every nametag in the area for nobody.
     */
    private static boolean blazeHelpersOn(SlayerSettings settings) {
        return settings.attunementDisplay || settings.attunementAlert || settings.daggerHighlight;
    }

    private void clearAttunement() {
        neededAttunement = null;
        heldAttunement = null;
        daggerSlot = -1;
    }

    /**
     * Which mode your own dagger is switched to. Hypixel swaps the item itself when you flip a
     * dagger, so the mode is readable straight off what is sitting in the hotbar – no ability
     * messages to follow, no state to remember across a relog.
     *
     * <p>Only the dagger that can reach the needed mode is looked for. You carry both of them (four
     * modes, two daggers) and the other one's setting says nothing about the swap in front of you –
     * reporting it would put a mode on screen that is right for a shield you are not fighting.
     */
    private void scanHotbarDagger(LocalPlayer player) {
        BlazeAttunement needed = neededAttunement;
        if (needed == null) {
            heldAttunement = null;
            daggerSlot = -1;
            return;
        }
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            BlazeAttunement mode = daggerMode(player.getInventory().getItem(slot));
            if (mode == needed || mode == needed.partner()) {
                heldAttunement = mode;
                daggerSlot = slot;
                return;
            }
        }
        heldAttunement = null;   // the right dagger is not in the hotbar at all
        daggerSlot = -1;
    }

    /**
     * The mode a hotbar slot is a dagger in, or {@code null} when it is not a dagger. The sword the
     * item is built from IS the mode: stone Ashen, gold Auric, iron Spirit, diamond Crystal.
     */
    private static BlazeAttunement daggerMode(ItemStack stack) {
        if (stack.isEmpty()
                || !strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains("dagger")) {
            return null;
        }
        if (stack.is(Items.STONE_SWORD)) {
            return BlazeAttunement.ASHEN;
        }
        if (stack.is(Items.GOLDEN_SWORD)) {
            return BlazeAttunement.AURIC;
        }
        if (stack.is(Items.IRON_SWORD)) {
            return BlazeAttunement.SPIRIT;
        }
        if (stack.is(Items.DIAMOND_SWORD)) {
            return BlazeAttunement.CRYSTAL;
        }
        return null;
    }

    /**
     * One line per attunement change, printing the whole tag it was read out of and how it was
     * claimed. The number beside the word is taken on faith as the shield's hit count; this is where
     * to see whether it counts up or down, and whether the line ever looks different from what the
     * parser expects.
     */
    private void logAttunement(AttunementTag tag, boolean owned, long now) {
        if (now - lastAttunementLogAt < 2_000L) {
            return;
        }
        lastAttunementLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Slayer] attunement {} (n={}) from '{}' - claimed by {}",
                tag.attunement(), tag.hits(), tag.tag(),
                owned ? "your own Spawned-by tag" : "the fight anchor");
    }

    /**
     * The other half of the diagnostic: attuned mobs ARE in sight but none of them was claimed. Says
     * which ones and why, because "the readout stays empty in a real fight" otherwise gives nothing to
     * work from – and the two ways it can happen (your own tag never recognised, or the demons drifting
     * further than the anchor reaches) want opposite fixes.
     */
    private void logAttunementGate(List<AttunementTag> tags, String why, long now) {
        if (tags.isEmpty() || now - lastAttunementLogAt < 30_000L) {
            return;
        }
        lastAttunementLogAt = now;
        List<String> seen = new ArrayList<>(tags.size());
        for (AttunementTag tag : tags) {
            seen.add(tag.tag());
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Slayer] attunement tags {} ignored - {}", seen, why);
    }

    /**
     * The colour Hypixel draws {@code word} in inside the untouched nametag, or {@code 0} when it
     * draws it in none. Lifting the colour off the tag beats picking one per attunement: the readout
     * then matches the word the player is already looking at on the boss, whatever Hypixel recolours.
     */
    private static int nametagColor(String raw, String word) {
        int at = raw.toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT));
        if (at < 1) {
            return 0;
        }
        for (int i = at - 2; i >= 0; i--) {
            if (raw.charAt(i) != SECTION_SIGN) {
                continue;
            }
            ChatFormatting format = ChatFormatting.getByCode(raw.charAt(i + 1));
            if (format == null || format == ChatFormatting.RESET) {
                return 0;
            }
            // Bold / italic and friends sit between the colour and the word; keep walking back.
            TextColor color = TextColor.fromLegacyFormat(format);
            if (color != null) {
                return 0xFF000000 | color.getValue();
            }
        }
        return 0;
    }

    /**
     * The Blaze fire pillar: from two thirds health on, the Inferno Demonlord throws a fireball that
     * raises a pillar somewhere around it, and every player on that boss dies unless it is beaten down
     * within seven seconds. Hypixel labels the pillar with exactly that – the seconds left and the hits
     * still owed – so the label IS the state; nothing has to be timed client-side.
     *
     * <p>The countdown is announced once per second rather than once per pillar. The alert lives for
     * under two seconds, so a single flash at the moment the pillar appears is gone while the danger
     * still is, and re-firing on each new second keeps the number on screen (and the ping going) all
     * the way down.
     *
     * @return whether {@code name} was a pillar label – the caller stops looking at this entity when
     *         it was, since a pillar is nothing else
     */
    private boolean scanFirePillar(Entity entity, String name, LocalPlayer player, long now,
                                   SlayerSettings settings) {
        Matcher matcher = FIRE_PILLAR.matcher(name);
        if (!matcher.matches()) {
            return false;
        }
        if (!settings.pillarAlert || questBoss != SlayerBoss.INFERNO || !bossUp) {
            logPillarGate(name, now);
            return true;
        }
        // Everyone's pillar carries the same label, and Blaze slayer is fought shoulder to shoulder.
        // Yours is the one standing with your boss; only before the boss entity is known does raw
        // proximity to you have to stand in for that.
        Entity anchor = bossEntity != null ? bossEntity : player;
        if (entity.distanceToSqr(anchor) > PILLAR_RANGE * PILLAR_RANGE) {
            return true;
        }

        pillarSeenAt = now;
        int seconds = parseInt(matcher.group(1));
        if (seconds == pillarSecondShown) {
            return true;   // same second of the same countdown, already on screen
        }
        pillarSecondShown = seconds;
        if (seconds <= PILLAR_ALERT_SECONDS) {
            int hits = parseInt(matcher.group(2));
            // Amber while there is still time to break it, red once there is not much.
            SlayerAlerts.getInstance().trigger("PILLAR " + seconds + "s - " + hits + " HITS",
                    seconds <= 2 ? 0xFFFF3030 : 0xFFFF9A2E);
        }
        return true;
    }

    /**
     * One line every 30s while a pillar label is being ignored, naming what the gate saw. The quest
     * state is read off the sidebar and the boss out of the world scan, so a wording change on either
     * would kill the alert silently – with this, the reason is in the log the first time it looks
     * wrong in-game.
     */
    private void logPillarGate(String name, long now) {
        if (now - lastPillarGateLogAt < 30_000L) {
            return;
        }
        lastPillarGateLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Slayer] fire pillar '{}' ignored - alert on: {}, quest boss: {}, boss up: {}",
                name, cfg().pillarAlert, questBoss, bossUp);
    }

    /**
     * Whether the miniboss alert + highlight may fire at all right now.
     *
     * <p>Minibosses are the one slayer helper with nothing tying it to a fight of your own: the
     * nametags it matches sit on mobs the whole lobby shares, so without a quest it boxes and
     * announces other people's spawns at anyone standing in a slayer area. Hence the gate – and hence
     * that it is a toggle, since "show them always" is a legitimate way to hunt minibosses.
     *
     * <p>A carry is the case the toggle cannot express, because there the quest is real but belongs
     * to the person being carried: the Carry Counter waves the gate through while one is active, if
     * that is turned on in its settings.
     */
    /**
     * One line every 30s while minibosses are being held back, naming what the gate saw. "Quest
     * active" is read off the sidebar, and a wording change there would silence the helpers for good
     * without this – with it, the state is visible in the log the moment it looks wrong in-game.
     */
    private void logMinibossGate(String name, long now) {
        if (now - lastMinibossGateLogAt < 30_000L) {
            return;
        }
        lastMinibossGateLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Slayer] miniboss '{}' hidden - quest active: {}, boss up: {}, carry: {}",
                name, questActive, bossUp, CarryCounter.getInstance().activeCarry() != null);
    }

    private boolean minibossHelpersAllowed(SlayerSettings settings) {
        if (!settings.minibossOnlyWithQuest || questActive) {
            return true;
        }
        CarryCounterSettings carry = ConfigManager.getInstance().get().carryCounter;
        return carry.enabled && carry.minibossHighlight
                && CarryCounter.getInstance().activeCarry() != null;
    }

    /**
     * Skull-wearing, unnamed armor stands close to the Voidgloom boss are its nukekubi fixations –
     * except for the ones that are deployables, which are built out of exactly the same thing.
     * {@link #isDeployable} is what tells them apart.
     */
    private void scanNukekubi(ClientLevel level, LocalPlayer player, LivingEntity boss,
                              List<HighlightBox> boxes, long now, SlayerSettings settings) {
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class,
                boss.getBoundingBox().inflate(24, 12, 24), s -> !s.hasCustomName())) {
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty() || !head.is(Items.PLAYER_HEAD)) {
                continue;
            }
            if (isDeployable(trackHead(stand, player, now))) {
                continue;
            }
            if (settings.nukekubiHighlight) {
                boxes.add(HighlightBox.onEntity(stand, stand.getBoundingBox().inflate(0.2).move(0, 1.2, 0),
                        settings.nukekubiColor.argb(), "Head", settings.nukekubiTracer));
            }
            if (settings.nukekubiAlert && alertedEntities.add(stand.getId())) {
                SlayerAlerts.getInstance().trigger("NUKEKUBI", settings.nukekubiColor.argb());
            }
        }
        headTracks.entrySet().removeIf(entry -> now - entry.getValue().lastSeenAt > HEAD_FORGET_MS);
    }

    /**
     * What is known about one skull-wearing stand, kept across scans and updated with this sight of
     * it. Both things that need deciding are decided the first time the stand is seen – how old it
     * already was ({@link Entity#tickCount}, so a flare planted before the boss was called is known
     * to be one however long the fight has been running) and whether a flare of yours had just been
     * deployed where it appeared.
     *
     * <p>Movement is the one thing only a fixation ever does, so any of it clears both.
     */
    private HeadTrack trackHead(ArmorStand stand, LocalPlayer player, long now) {
        HeadTrack track = headTracks.get(stand.getId());
        if (track == null) {
            track = new HeadTrack(stand.position(), stand.tickCount * 50L, now);
            track.deployed = stand.tickCount <= HEAD_FRESH_TICKS
                    && now - flareInHandAt < FLARE_HAND_MS
                    && stand.distanceToSqr(player) < FLARE_DEPLOY_RANGE * FLARE_DEPLOY_RANGE;
            headTracks.put(stand.getId(), track);
            logHeadCandidate(stand, track, now);
        }
        track.lastSeenAt = now;
        if (!track.moved && stand.position().distanceToSqr(track.spawnPos) > HEAD_DRIFT * HEAD_DRIFT) {
            track.moved = true;
            track.deployed = false;
        }
        return track;
    }

    /**
     * Whether a head is a deployable (a flare) rather than a nukekubi fixation.
     *
     * <p>Both are unnamed armor stands wearing a player head standing in the same fight, so the
     * split is behavioural: a head is a deployable when it came out of your own hand, or when it was
     * already old the first time this fight saw it – and in either case has not moved since, because
     * a flare never does.
     *
     * <p>Deliberately NOT a test on standing still over time. Fixations are placed and then float
     * where they were put, so "it has not moved in a while" describes them just as well and would
     * end up hiding the very thing this is here to show.
     */
    private static boolean isDeployable(HeadTrack track) {
        return !track.moved && (track.deployed || track.ageAtFirstSight > DEPLOYABLE_AGE_MS);
    }

    /**
     * Notes when a flare is in your main hand. A deployed flare comes out of the hand holding it, so
     * this is what lets the head it turns into be told apart from a fixation the instant it appears,
     * which is the only thing that separates the two while both are brand new.
     */
    private void scanHeldFlare(LocalPlayer player, long now) {
        if (questBoss != SlayerBoss.VOIDGLOOM) {
            return;   // nothing else reads this, and it is the only fight that mistakes one for a head
        }
        String held = strip(player.getMainHandItem().getHoverName().getString());
        if (held.toLowerCase(Locale.ROOT).contains("flare")) {
            flareInHandAt = now;
        }
    }

    /**
     * One line per new head near the boss (throttled), with the skin it wears and how it was judged.
     * Fixation and flare are the same kind of entity, so the split is behavioural and best-effort –
     * this is where to see which skin each of them actually carries, and pinning the match to those
     * textures is what would make it exact.
     */
    private void logHeadCandidate(ArmorStand stand, HeadTrack track, long now) {
        if (now - lastHeadLogAt < 5_000L) {
            return;
        }
        lastHeadLogAt = now;
        var profile = stand.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.PROFILE);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Slayer] head candidate: skin={} age={}s deployed={} -> {} at {}",
                profile == null ? "?" : profile.name().orElse("?"),
                track.ageAtFirstSight / 1000, track.deployed,
                isDeployable(track) ? "deployable, hidden" : "nukekubi",
                stand.blockPosition().toShortString());
    }

    /** The thrown beacon: a beacon block near the boss while the Voidgloom fight runs. */
    private void scanBeacon(ClientLevel level, List<HighlightBox> boxes, long now, SlayerSettings settings) {
        if (questBoss != SlayerBoss.VOIDGLOOM || !bossUp || bossEntity == null
                || !(settings.beaconHighlight || settings.beaconAlert)) {
            beaconPos = null;
            return;
        }
        BlockPos found = null;
        BlockPos center = bossEntity.blockPosition();
        // A 12-block cube around the boss, only every scan interval - cheap enough and the beacon
        // always lands close to where the boss threw it.
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-12, -6, -12), center.offset(12, 6, 12))) {
            if (level.getBlockState(pos).is(Blocks.BEACON)) {
                found = pos.immutable();
                break;
            }
        }
        if (found != null) {
            if (beaconPos == null && settings.beaconAlert
                    && now - lastBeaconAlertAt > 3_000L) {
                lastBeaconAlertAt = now;
                SlayerAlerts.getInstance().trigger("BEACON!", settings.beaconColor.argb());
            }
            beaconPos = found;
            if (settings.beaconHighlight) {
                boxes.add(new HighlightBox(new AABB(found), settings.beaconColor.argb(), "Beacon",
                        settings.beaconTracer));
            }
        } else {
            beaconPos = null;
        }
    }

    // ------------------------------------------------------------------ chat

    /** Fed every chat line; books quest events, rare drops and the sack breakdown. */
    public void onChat(String text, Component component) {
        if (!cfg().enabled || text == null) {
            return;
        }
        // Hypixel intersperses formatting codes MID-line ("§5§lNICE! §r§5SLAYER BOSS SLAIN") - a
        // contains() on the raw text then never matches, which showed as "failed quests count but
        // kills don't". Every match below runs on the stripped text.
        String plain = strip(text);
        long now = System.currentTimeMillis();
        // The quest-complete block's "<Type> Slayer LVL" and "RNG Meter - N Stored XP" lines: the
        // RNG meter is booked from here rather than from a chat listener of its own.
        if (cfg().rngMeter && (plain.contains("Slayer LVL") || plain.contains("Stored XP"))) {
            RngMeterTracker.getInstance().onChat(plain, now);
            return;
        }
        if (plain.contains("SLAYER QUEST STARTED")) {
            touch(now);
            return;
        }
        if (plain.contains("SLAYER QUEST FAILED")) {
            questsFailed++;
            touch(now);
            return;
        }
        // A kill is announced by "NICE! SLAYER BOSS SLAIN" AND "SLAYER QUEST COMPLETE" - accept
        // either (the exact wording has shifted), deduped so both lines of one kill book once.
        if (plain.contains("SLAYER BOSS SLAIN") || plain.contains("SLAYER QUEST COMPLETE")) {
            if (now - lastSlainBookedAt < 5_000L) {
                return;   // the same kill's second announcement line
            }
            lastSlainBookedAt = now;
            bossesSlain++;
            if (bossSpawnAt > 0) {
                killTimeTotalMs += now - bossSpawnAt;
                killTimeCount++;
                bossSpawnAt = 0;
            }
            if (lastSlainAt > 0 && now - lastSlainAt < 30 * 60_000L) {
                cycleTotalMs += now - lastSlainAt;
                cycleCount++;
            }
            lastSlainAt = now;
            lootWindowUntil = now + LOOT_WINDOW_MS;
            alertedEntities.clear();   // fresh fight, fresh alerts
            headTracks.clear();
            bossState = null;
            alertedState = null;
            pillarSecondShown = -1;
            blazeAnchor = null;
            clearAttunement();
            touch(now);
            return;
        }
        // The shared parser: it also strips the parentheses Hypixel puts round VERY RARE items,
        // which the old private pattern kept as part of the name ("(Null Atom)" resolved to nothing).
        sbs.modid.client.core.util.RareDropLine.Drop rare =
                sbs.modid.client.core.util.RareDropLine.parse(plain);
        if (rare != null && (now < lootWindowUntil || bossUp)) {
            String itemId = resolveItemId(rare.item());
            if (itemId != null) {
                bookDrop(itemId, rare.count(), now);
            }
            return;
        }
        if (plain.startsWith("[Sacks]") && component != null) {
            onSackMessage(component, now);
        }
    }

    /** Books slayer bulk drops out of the "[Sacks]" hover breakdown. */
    private void onSackMessage(Component component, long now) {
        boolean windowOpen = now < lootWindowUntil;
        if (!windowOpen && !bossUp) {
            return;
        }
        StringBuilder hover = new StringBuilder();
        collectHoverText(component, hover);
        // ONE amount per (item, sack) per message, collapsed by MAX (the hover can arrive twice).
        Map<String, Integer> perItem = new HashMap<>();
        for (String line : hover.toString().split("\n")) {
            Matcher matcher = SACK_LINE.matcher(strip(line));
            if (!matcher.find() || matcher.group(1).startsWith("-")) {
                continue;
            }
            String itemId = resolveItemId(matcher.group(2));
            if (itemId == null) {
                continue;
            }
            // Inside the post-kill window everything gained is the boss's shower; outside it only
            // the slayer's known bulk drops count (so mining while a quest idles stays out).
            if (windowOpen || SlayerData.isBulkDrop(questBoss, itemId)) {
                perItem.merge(itemId + "|" + matcher.group(3), parseInt(matcher.group(1)), Math::max);
            }
        }
        for (Map.Entry<String, Integer> entry : perItem.entrySet()) {
            bookDrop(entry.getKey().substring(0, entry.getKey().indexOf('|')), entry.getValue(), now);
        }
    }

    private void bookDrop(String itemId, int count, long now) {
        if (count <= 0) {
            return;
        }
        drops.merge(itemId, count, Integer::sum);
        // Lifetime drop log: config/sbs/tracker/slayertracker.txt.
        sbs.modid.client.core.tracker.TrackerStore.record("slayertracker", itemId, count);
        touch(now);
    }

    private void touch(long now) {
        if (firstEventAt == 0) {
            firstEventAt = now;
        }
        lastEventAt = now;
    }

    // ------------------------------------------------------------------ HUD read model

    public List<HighlightBox> highlightBoxes() {
        return highlightBoxes;
    }

    /** Whether the overlay should draw at all (any session activity, not too long ago). */
    public boolean sessionVisible() {
        return lastEventAt > 0 && System.currentTimeMillis() - lastEventAt < IDLE_HIDE_MS;
    }

    public SlayerBoss questBoss() {
        return questBoss;
    }

    /**
     * Whether the attunement readout has anything current to say. False the moment the tag stops
     * being seen, so the number never lingers on screen after the fight it belonged to.
     */
    public boolean attunementFresh() {
        return neededAttunement != null
                && System.currentTimeMillis() - attunementSeenAt < ATTUNEMENT_FRESH_MS;
    }

    /** The mode your dagger has to be on, or {@code null}. Check {@link #attunementFresh()} first. */
    public BlazeAttunement neededAttunement() {
        return neededAttunement;
    }

    /** The needed mode's colour, taken off the boss's own nametag where it had one. */
    public int attunementColor() {
        return attunementColor;
    }

    /**
     * The mode your own dagger is on, or {@code null} when no dagger that can reach the needed mode
     * is in the hotbar. Equal to {@link #neededAttunement()} means you are set up to hit.
     */
    public BlazeAttunement heldAttunement() {
        return heldAttunement;
    }

    /**
     * The hotbar slot holding the dagger that can reach the needed mode, or {@code -1} when none is
     * in the hotbar. This is the slot to press, whether or not the dagger is flipped the right way
     * yet – the flip is a right-click once it is in your hand.
     */
    public int daggerSlot() {
        return daggerSlot;
    }

    public int bossesSlain() {
        return bossesSlain;
    }

    public int questsFailed() {
        return questsFailed;
    }

    /** Average kill time in seconds, or 0 while unknown. */
    public double avgKillSeconds() {
        return killTimeCount == 0 ? 0 : killTimeTotalMs / 1000.0 / killTimeCount;
    }

    /** Estimated bosses per hour from the average boss-to-boss cycle, or 0 while unknown. */
    public double bossesPerHour() {
        return cycleCount == 0 ? 0 : 3_600_000.0 / (cycleTotalMs / (double) cycleCount);
    }

    /** The session's drops, insertion-ordered. */
    public Map<String, Integer> drops() {
        return drops;
    }

    /** Total session profit (instasell-first pricing). */
    public double profit() {
        double total = 0;
        for (Map.Entry<String, Integer> entry : drops.entrySet()) {
            total += price(entry.getKey()) * entry.getValue();
        }
        return total;
    }

    /** Estimated profit per hour over the session's active span. */
    public double profitPerHour() {
        long span = lastEventAt - firstEventAt;
        return span < 60_000L ? 0 : profit() * 3_600_000.0 / span;
    }

    /** Value of one item id: Bazaar instasell first, lowest BIN second, 0 when unknown. */
    public double price(String itemId) {
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(itemId);
        if (value != null && value.sell() > 0) {
            return value.sell();
        }
        Long lbin = LbinCache.getInstance().getLbin(itemId);
        return lbin == null ? 0 : lbin;
    }

    /** Resets the session tracker (settings button). */
    public void resetSession() {
        bossesSlain = 0;
        questsFailed = 0;
        killTimeTotalMs = 0;
        killTimeCount = 0;
        lastSlainAt = 0;
        cycleTotalMs = 0;
        cycleCount = 0;
        drops.clear();
        lootWindowUntil = 0;
        firstEventAt = 0;
        lastEventAt = 0;
    }

    // ------------------------------------------------------------------ helpers

    /** Display name -> SkyBlock id via the item catalogue, else the normalized name itself. */
    public static String resolveItemId(String displayName) {
        if (displayName == null) {
            return null;
        }
        String name = displayName.replaceAll("§.", "").trim();
        if (name.isEmpty()) {
            return null;
        }
        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        SkyBlockItemCatalog.Entry entry = catalog.byName(name);
        if (entry == null) {
            entry = catalog.byNormalizedName(name);
        }
        if (entry != null) {
            return entry.id;
        }
        String constructed = name.toUpperCase(Locale.ROOT).replace(' ', '_')
                .replaceAll("[^A-Z0-9_]", "");
        return constructed.isEmpty() ? null : constructed;
    }

    private static LivingEntity mobBelow(ClientLevel level, ArmorStand stand) {
        return mobBelow(level, stand, 1.0, null);
    }

    /**
     * The mob a nametag stand belongs to: the one closest to it in the column below it.
     *
     * <p>{@code species} names the vanilla mob the tag is expected to sit on. When one of those is
     * in the column it wins outright, however close something else has drifted to the tag – a boss
     * box has to end up on the boss even while the arena is full of other floating things.
     */
    private static LivingEntity mobBelow(ClientLevel level, ArmorStand stand, double radius,
                                         String species) {
        List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class,
                stand.getBoundingBox().inflate(radius, 0, radius).expandTowards(0, -5, 0),
                m -> m != stand && !(m instanceof ArmorStand) && !(m instanceof Player) && m.isAlive());
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        LivingEntity match = null;
        double matchDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mobs) {
            double distance = mob.distanceToSqr(stand);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
            if (species != null && species.equals(speciesOf(mob)) && distance < matchDistance) {
                matchDistance = distance;
                match = mob;
            }
        }
        return match != null ? match : best;
    }

    private static void collectHoverText(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }

    private static int parseInt(String raw) {
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

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
}
