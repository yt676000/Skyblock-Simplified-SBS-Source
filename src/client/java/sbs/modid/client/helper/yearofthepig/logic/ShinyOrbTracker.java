/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.yearofthepig.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.YearOfThePigSettings;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.core.tracker.TrackerStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows what a Year of the Pig session produced: orbs spent, pigs delivered, and
 * everything the piglet's orb paid out.
 *
 * <p><b>The event.</b> Every 12 SkyBlock years Shiny Pigs spawn around the Village, the Farm and the
 * Combat Settlement. A Shiny Orb (5,000 coins from Piggy) is used on a pig, which launches it into
 * the air; the orb stays behind where the pig stood and you have <b>90 seconds</b> to knock the pig
 * back to it. Deliver it and the orb charges, and clicking the charged orb pays out one Shiny Token
 * plus one roll of the loot table (coins, skill XP, enchanted potatoes / porkchops, or the rare
 * Flying Pig, Blood God Crest and Potato Talisman).
 *
 * <p><b>Why the income is booked from chat and the cost from the inventory.</b> Hypixel announces
 * every payout on its own line – that line names the exact reward and is the only channel a coin or
 * XP reward has at all, since neither ever touches the inventory. The <i>cost</i> side has no line:
 * nothing is printed when an orb is consumed, so the only evidence is the Shiny Orb stack shrinking
 * ({@link #scanOrbConsumption}). Splitting it this way means a session's profit is right even when
 * the loot goes straight to a sack and never appears in the inventory either.
 *
 * <p><b>Why the orb position is remembered rather than found.</b> The orb is what you have to bring
 * the pig back to, so the overlay needs its location – but the launch also moves the pig far away,
 * and there is no reliable client-side handle on the orb entity itself. The position the pig
 * occupied at the instant the orb was consumed <i>is</i> the orb's position by the event's own
 * rules, so it is captured then and kept until chat says the orb charged or expired.
 *
 * <p><b>Why several orbs are tracked at once.</b> Nothing stops a second orb being thrown while the
 * first pig is still in the air, and running two at a time is how the event is actually played, so
 * every activation gets its own entry with its own pig, countdown and marker. Hypixel's charge and
 * expiry lines do not say <i>which</i> orb they mean, so each is matched to one: an expiry always
 * belongs to the oldest orb (the 90 seconds are a fixed clock), and a charge to the orb nearest the
 * player, since delivering a pig means standing at the orb it was knocked into.
 */
public final class ShinyOrbTracker {

    private static final ShinyOrbTracker INSTANCE = new ShinyOrbTracker();

    /** What Piggy charges for one Shiny Orb – the cost side of every session's profit. */
    private static final long ORB_COST = 5_000L;

    /** How long an activated orb lives before it and its pig vanish (the event's own limit). */
    private static final long ORB_LIFETIME_MS = 90_000L;

    /** Item ids the event runs on. The rod is a held booster, not a consumable – see {@link #hasOrbs}. */
    private static final String ORB_ID = "SHINY_ORB";
    private static final String ROD_ID = "SHINY_ROD";
    private static final String TOKEN_ID = "SHINY_TOKEN";

    /** Pseudo id for raw coin rewards; {@link #price} values it at 1 coin apiece. */
    private static final String COIN_ID = "SKYBLOCK_COIN";

    /** After this long with no orb activity the HUD card hides itself again. */
    private static final long IDLE_HIDE_MS = 5 * 60_000L;

    /** Entity scan rate. Four times a second is plenty for a walking pig and costs nothing. */
    private static final long SCAN_INTERVAL_MS = 250L;

    /**
     * After (re)joining a world the server streams the inventory back in over several ticks. For this
     * long the orb diff only rebuilds its baseline, so the orbs arriving back never read as a stack
     * that shrank to zero and then refilled – which would book a lobby swap as dozens of spent orbs.
     */
    private static final long JOIN_SETTLE_MS = 4_000L;

    /**
     * Largest per-tick Shiny Orb decrease still booked as a use. One activation consumes exactly one
     * orb (the allowance covers a laggy sync batching two); a whole stack disappearing is the player
     * stashing it in storage or selling it back, which is never a cost.
     */
    private static final int MAX_ORBS_CONSUMED_PER_TICK = 2;

    /** How far from the crosshair / player a pig may be to be taken as the one just launched. */
    private static final double ACTIVATION_RADIUS = 8.0;

    /** How far shiny pigs are collected for the highlight overlay. */
    private static final double PIG_SCAN_RADIUS = 48.0;

    /** How long a charged-but-unclaimed orb keeps its marker before it is given up on. */
    private static final long CHARGED_MARKER_TTL_MS = 3 * 60_000L;

    /** Slack past the nominal 90s before the local fallback declares an orb dead. */
    private static final long ORB_EXPIRY_GRACE_MS = 3_000L;

    /**
     * How long an orb dropped by the local fallback waits for Hypixel's own expiry line before the
     * expiry is booked. Both paths fire for the same orb moments apart; whichever arrives first
     * claims the pending entry, so a dead orb is counted exactly once.
     */
    private static final long EXPIRY_CONFIRM_MS = 3_000L;

    // ------------------------------------------------------------------
    // Chat lines (colour codes already stripped by the caller).
    // ------------------------------------------------------------------

    /** "SHINY! The orb is charged! Click on it for loot!" – the pig made it back. */
    private static final Pattern ORB_CHARGED = Pattern.compile(
            "SHINY! The orb is charged! Click on it for loot!");

    /** "Your Shiny Orb and associated pig expired and disappeared." – the 90s ran out. */
    private static final Pattern ORB_EXPIRED = Pattern.compile(
            "Your Shiny Orb and associated pig expired and disappeared");

    /**
     * "SHINY! You extracted Shiny Token and +25,000 Coins from the piglet's orb!" – the payout.
     * The Shiny Token half is optional and the diminutive is spelled both ways in the wild
     * ("piglet's" / "pigglet's"), so neither is allowed to lose the line.
     */
    private static final Pattern ORB_LOOTED = Pattern.compile(
            "SHINY! You extracted (?:Shiny Token and )?(.+?) from the pigg?let's orb!");

    /** "Oink! This pig has already been clicked by someone else!" – your orb was NOT spent. */
    private static final Pattern PIG_TAKEN = Pattern.compile(
            "Oink! This pig has already been clicked by someone else!");

    /** A coin reward inside the loot line: "+25,000 Coins". */
    private static final Pattern REWARD_COINS = Pattern.compile("\\+?([\\d,]+) Coins");

    /** A skill XP reward inside the loot line: "+1,000 Farming XP". */
    private static final Pattern REWARD_SKILL_XP = Pattern.compile("\\+?([\\d,]+) (.+?) XP");

    /** An item reward inside the loot line: "16x Enchanted Potato", or a bare "Flying Pig". */
    private static final Pattern REWARD_ITEM = Pattern.compile("(?:([\\d,]+)x )?(.+)");

    /**
     * One activated orb: where it sits, which pig belongs to it, and when it dies. The {@code id} is
     * a session-local serial, so two orbs thrown in the same millisecond are still distinguishable.
     */
    public record ActiveOrb(int id, Vec3 position, int pigEntityId, long startedAt, long expiresAt) {

        /** Milliseconds left before the orb and its pig vanish, floored at zero. */
        public long remainingMs() {
            return Math.max(0, expiresAt - System.currentTimeMillis());
        }
    }

    /** An orb that has been charged and is waiting to be clicked. */
    public record ChargedOrb(Vec3 position, long chargedAt) {
    }

    // ------------------------------------------------------------------ session state

    private int orbsUsed;
    private int orbsCharged;
    private int orbsExpired;
    private int shinyTokens;
    private long coinsGained;

    /** Item id -> how many this session paid out. */
    private final Map<String, Integer> loot = new LinkedHashMap<>();
    /** Skill name -> XP awarded this session. Shown, never monetised – XP has no coin value. */
    private final Map<String, Long> skillXp = new LinkedHashMap<>();

    private long firstEventAt;
    private long lastEventAt;

    /**
     * The orbs waiting for their pigs, oldest first – empty while none is out. Replaced wholesale on
     * every change so the render thread always reads a complete, consistent list.
     */
    private volatile List<ActiveOrb> activeOrbs = List.of();

    /**
     * Charged orbs still waiting to be clicked, oldest first. Held separately from
     * {@link #activeOrbs} so a marker keeps pointing at the orb after its pig is delivered – that is
     * exactly the moment the player has to walk back and click it.
     */
    private volatile List<ChargedOrb> chargedOrbs = List.of();

    /** Serial behind {@link ActiveOrb#id()}. */
    private int nextOrbId;

    /** Ids of orbs whose low-time warning already sounded, so it fires once per orb, not per tick. */
    private final Set<Integer> warnedOrbs = new HashSet<>();

    /**
     * Deadlines of orbs the local fallback dropped, each waiting on Hypixel's own expiry line. The
     * expiry is booked when a line claims the entry or when its deadline passes – see
     * {@link #EXPIRY_CONFIRM_MS}.
     */
    private final Deque<Long> pendingExpiries = new ArrayDeque<>();

    /**
     * Whether the player carries an orb or the rod. Cached on the tick rather than read per frame:
     * the world overlay asks on every frame it draws nothing, and that is a full inventory walk.
     */
    private volatile boolean hasEventGear;

    /** Shiny pigs seen by the last scan, for the highlight overlay. Replaced wholesale each scan. */
    private volatile List<LivingEntity> nearbyPigs = List.of();

    /** Last seen Shiny Orb count, the baseline the consumption diff works against. */
    private int lastOrbCount = -1;
    private long settleUntil;
    private long lastScanAt;

    private ShinyOrbTracker() {
    }

    public static ShinyOrbTracker getInstance() {
        return INSTANCE;
    }

    private static YearOfThePigSettings cfg() {
        return ConfigManager.getInstance().get().yearOfThePig;
    }

    private static boolean enabled() {
        return cfg().enabled;
    }

    // ------------------------------------------------------------------
    // Chat intake
    // ------------------------------------------------------------------

    /** Called for every chat line: the payout, the charge, the expiry and the "already clicked" oink. */
    public void onChat(String message) {
        if (!enabled() || message == null) {
            return;
        }
        if (ORB_CHARGED.matcher(message).find()) {
            orbsCharged++;
            touch();
            // The orb is now the thing to walk back to, so its marker outlives the pig chase.
            ActiveOrb orb = deliveredOrb();
            if (orb != null) {
                retire(orb);
                chargedOrbs = append(chargedOrbs,
                        new ChargedOrb(orb.position(), System.currentTimeMillis()));
            }
            if (cfg().chargedAlert) {
                playSound(1.6f);
            }
            return;
        }
        if (ORB_EXPIRED.matcher(message).find()) {
            orbsExpired++;
            touch();
            // Either this line confirms an orb the local fallback already dropped, or it is the
            // first word of the oldest orb's death - the 90s clock makes that the only candidate.
            if (pendingExpiries.poll() == null && !activeOrbs.isEmpty()) {
                retire(activeOrbs.get(0));
            }
            return;
        }
        if (PIG_TAKEN.matcher(message).find()) {
            // No orb was consumed – Hypixel refuses the click – so nothing is booked. The sound is
            // the point: it tells you to pick a different pig before wasting the walk.
            if (cfg().takenAlert) {
                playSound(0.7f);
            }
            return;
        }
        Matcher looted = ORB_LOOTED.matcher(message);
        if (looted.find()) {
            shinyTokens++;
            bookReward(looted.group(1).trim());
            touch();
            // The orb that paid out is the one that was just clicked, so the one being stood on.
            ChargedOrb clicked = nearestToPlayer(chargedOrbs, ChargedOrb::position);
            if (clicked != null) {
                chargedOrbs = remove(chargedOrbs, clicked);
            }
        }
    }

    /**
     * Which of the live orbs a charge line belongs to: the one nearest the player. A pig is charged
     * by being knocked <i>into</i> its orb, so the player is standing at it when the line lands –
     * and unlike the pig entity, which Hypixel removes on delivery, that holds either way.
     */
    private ActiveOrb deliveredOrb() {
        return nearestToPlayer(activeOrbs, ActiveOrb::position);
    }

    /**
     * The entry standing closest to the player, or the oldest one when there is no player to measure
     * from – null only when the list is empty.
     */
    private static <T> T nearestToPlayer(List<T> orbs, Function<T, Vec3> position) {
        LocalPlayer player = Minecraft.getInstance().player;
        T best = null;
        double bestDistance = Double.MAX_VALUE;
        for (T orb : orbs) {
            if (player == null) {
                return orb;
            }
            double distance = player.position().distanceToSqr(position.apply(orb));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = orb;
            }
        }
        return best;
    }

    /** Drops one orb from the live list and forgets the per-orb state hanging off it. */
    private void retire(ActiveOrb orb) {
        activeOrbs = remove(activeOrbs, orb);
        warnedOrbs.remove(orb.id());
    }

    private static <T> List<T> append(List<T> list, T value) {
        List<T> copy = new ArrayList<>(list);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static <T> List<T> remove(List<T> list, T value) {
        List<T> copy = new ArrayList<>(list);
        copy.remove(value);
        return List.copyOf(copy);
    }

    /**
     * Books one payout from the loot line's reward text. Coins and skill XP are their own lines in
     * the reward slot and never arrive as items; anything else is a real item and is priced.
     */
    private void bookReward(String reward) {
        Matcher coins = REWARD_COINS.matcher(reward);
        if (coins.matches()) {
            long amount = parseAmount(coins.group(1));
            coinsGained += amount;
            TrackerStore.record("shinypigtracker", COIN_ID, amount);
            return;
        }
        Matcher xp = REWARD_SKILL_XP.matcher(reward);
        if (xp.matches()) {
            skillXp.merge(xp.group(2).trim(), parseAmount(xp.group(1)), Long::sum);
            return;
        }
        Matcher item = REWARD_ITEM.matcher(reward);
        if (!item.matches()) {
            return;
        }
        int amount = item.group(1) == null ? 1 : (int) parseAmount(item.group(1));
        String itemId = resolveItemId(item.group(2).trim());
        if (itemId != null && amount > 0) {
            loot.merge(itemId, amount, Integer::sum);
            TrackerStore.record("shinypigtracker", itemId, amount);
        }
    }

    // ------------------------------------------------------------------
    // Tick: orb consumption, orb expiry, pig scan
    // ------------------------------------------------------------------

    /** Called once per client tick. Gates itself on the module toggle. */
    public void onClientTick() {
        if (!enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            // Out of a world: drop the live state so a rejoin never resumes a stale orb, and force
            // the diff to re-baseline against the inventory the server is about to stream back.
            activeOrbs = List.of();
            chargedOrbs = List.of();
            warnedOrbs.clear();
            pendingExpiries.clear();
            nearbyPigs = List.of();
            hasEventGear = false;
            lastOrbCount = -1;
            settleUntil = System.currentTimeMillis() + JOIN_SETTLE_MS;
            return;
        }
        scanOrbConsumption(player);
        expireOrbs();
        long now = System.currentTimeMillis();
        // A charged orb whose loot line never arrived (chat filtered, or the player simply walked
        // away) must not leave a marker standing in the world for the rest of the session.
        if (!chargedOrbs.isEmpty()) {
            List<ChargedOrb> alive = new ArrayList<>(chargedOrbs.size());
            for (ChargedOrb orb : chargedOrbs) {
                if (now - orb.chargedAt() <= CHARGED_MARKER_TTL_MS) {
                    alive.add(orb);
                }
            }
            if (alive.size() != chargedOrbs.size()) {
                chargedOrbs = List.copyOf(alive);
            }
        }
        if (now - lastScanAt >= SCAN_INTERVAL_MS) {
            lastScanAt = now;
            nearbyPigs = scanPigs(level, player);
            hasEventGear = countInInventory(player, ORB_ID) > 0 || countInInventory(player, ROD_ID) > 0;
        }
    }

    /**
     * Books a spent Shiny Orb from the stack shrinking, and captures where the orb was left.
     *
     * <p>Only a small decrease counts: one activation consumes one orb, so a stack dropping by more
     * than {@link #MAX_ORBS_CONSUMED_PER_TICK} is the player stashing or selling it, never a use.
     * Decreases while a container screen is open are refused for the same reason – that is moving
     * items around, not clicking a pig.
     */
    private void scanOrbConsumption(LocalPlayer player) {
        int count = countInInventory(player, ORB_ID);
        long now = System.currentTimeMillis();
        if (lastOrbCount < 0 || now < settleUntil) {
            lastOrbCount = count;   // rebuilding the baseline, booking nothing
            return;
        }
        int used = lastOrbCount - count;
        lastOrbCount = count;
        if (used <= 0 || used > MAX_ORBS_CONSUMED_PER_TICK
                || GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;
        }
        orbsUsed += used;
        touch();
        captureActivation(player);
    }

    /**
     * Remembers where the orb was left and which pig it belongs to: the pig under the crosshair if
     * the player is looking at one (that is the pig they just clicked), otherwise the nearest pig.
     * The position is taken <i>now</i>, before the launch moves the pig – that spot is the orb.
     *
     * <p>Pigs already anchored to a live orb are skipped, so a second orb thrown while the first pig
     * is still out gets its own animal rather than stealing the one being chased.
     */
    private void captureActivation(LocalPlayer player) {
        LivingEntity pig = crosshairPig(player);
        if (pig == null || isAnchored(pig.getId())) {
            pig = nearestPig(player);
        }
        if (pig == null) {
            return;   // nothing to anchor to; the counters still booked the orb
        }
        long now = System.currentTimeMillis();
        activeOrbs = append(activeOrbs,
                new ActiveOrb(nextOrbId++, pig.position(), pig.getId(), now, now + ORB_LIFETIME_MS));
    }

    /** Whether a pig already belongs to one of the live orbs. */
    private boolean isAnchored(int entityId) {
        for (ActiveOrb orb : activeOrbs) {
            if (orb.pigEntityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retires the orbs whose 90 seconds ran out, and sounds each one's low-time warning on the way.
     *
     * <p>The expiry chat line is the authority and normally gets there first; this is the fallback
     * for when it is missed (chat filtered, message reworded), so a dead orb can never sit on the
     * HUD forever counting down past zero. A dropped orb is not booked immediately – it waits
     * {@link #EXPIRY_CONFIRM_MS} for Hypixel's line to claim it, which is what keeps one death from
     * counting twice.
     */
    private void expireOrbs() {
        long now = System.currentTimeMillis();
        for (ActiveOrb orb : activeOrbs) {
            // The grace lets Hypixel's own line be the one that resolves a normal expiry: the server
            // clock is the real one, and a client that gives up a beat early would book an expiry
            // for a pig that still made it back.
            if (now > orb.expiresAt() + ORB_EXPIRY_GRACE_MS) {
                retire(orb);
                pendingExpiries.add(now + EXPIRY_CONFIRM_MS);
            } else if (cfg().expiryWarning && !warnedOrbs.contains(orb.id())
                    && orb.remainingMs() <= cfg().expiryWarningSeconds * 1000L) {
                warnedOrbs.add(orb.id());
                playSound(0.9f);
            }
        }
        // Nothing came from chat within the window, so the fallback's own verdict stands.
        while (!pendingExpiries.isEmpty() && pendingExpiries.peek() <= now) {
            pendingExpiries.poll();
            orbsExpired++;
            touch();
        }
    }

    /**
     * The Shiny Pigs around the player, for the highlight overlay.
     *
     * <p>Matched by name rather than by entity type: the event's pigs carry a "Shiny Pig" nametag –
     * either on a floating armor stand (Hypixel's usual encoding) or on the mob itself – and the
     * Village is full of ordinary pigs that must not light up. When {@code highlightAllPigs} is on
     * the plain type name matches too, as a fallback for a nametag encoding we do not know yet.
     */
    private List<LivingEntity> scanPigs(ClientLevel level, LocalPlayer player) {
        boolean anyPig = cfg().highlightAllPigs;
        List<LivingEntity> found = new ArrayList<>();
        Set<Integer> claimed = new HashSet<>();

        // Pass 1: the nametag stand naming a shiny pig -> the mob standing under it.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()
                    || stand.distanceTo(player) > PIG_SCAN_RADIUS) {
                continue;
            }
            var name = stand.getCustomName();
            if (name == null || !isShinyPigName(name.getString())) {
                continue;
            }
            LivingEntity pig = mobBelow(level, stand);
            if (pig != null && claimed.add(pig.getId())) {
                found.add(pig);
            }
        }

        // Pass 2: the name sitting on the mob itself, plus (optionally) any plain pig.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living instanceof ArmorStand
                    || living == player || !living.isAlive()
                    || claimed.contains(living.getId())
                    || living.distanceTo(player) > PIG_SCAN_RADIUS) {
                continue;
            }
            var custom = living.getCustomName();
            boolean named = custom != null && isShinyPigName(custom.getString());
            if ((named || (anyPig && isPigType(living))) && claimed.add(living.getId())) {
                found.add(living);
            }
        }
        return List.copyOf(found);
    }

    /** Whether a nametag names a shiny pig, colour codes and level prefixes included. */
    private static boolean isShinyPigName(String raw) {
        return raw != null && raw.replaceAll("§.", "").toLowerCase(Locale.ROOT).contains("shiny pig");
    }

    /** Whether an entity is a vanilla pig, by type name so no version-specific class is imported. */
    private static boolean isPigType(LivingEntity living) {
        return "Pig".equalsIgnoreCase(living.getType().getDescription().getString());
    }

    /**
     * The living mob a nametag stand is floating above. Hypixel parks the tag a little over the mob,
     * so the closest living entity just below the stand is its owner.
     */
    private static LivingEntity mobBelow(ClientLevel level, ArmorStand stand) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living instanceof ArmorStand
                    || !living.isAlive()) {
                continue;
            }
            double dx = living.getX() - stand.getX();
            double dz = living.getZ() - stand.getZ();
            double dy = stand.getY() - living.getY();
            if (dy < -0.5 || dy > 2.5 || dx * dx + dz * dz > 1.0) {
                continue;
            }
            double distance = dx * dx + dz * dz + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /** The pig the player is looking at right now, or null when the crosshair is on nothing. */
    private static LivingEntity crosshairPig(LocalPlayer player) {
        var hit = Minecraft.getInstance().hitResult;
        if (hit instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof LivingEntity living
                && living.distanceTo(player) <= ACTIVATION_RADIUS) {
            return living;
        }
        return null;
    }

    /**
     * The closest tracked shiny pig within {@link #ACTIVATION_RADIUS} that no live orb already owns,
     * or null when none is near.
     */
    private LivingEntity nearestPig(LocalPlayer player) {
        LivingEntity best = null;
        double bestDistance = ACTIVATION_RADIUS;
        for (LivingEntity pig : nearbyPigs) {
            double distance = pig.distanceTo(player);
            if (pig.isAlive() && !isAnchored(pig.getId()) && distance < bestDistance) {
                bestDistance = distance;
                best = pig;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Accessors for the HUD and the world overlay
    // ------------------------------------------------------------------

    /** Whether the tracker card should draw: an orb is live, or one was recently resolved. */
    public boolean sessionVisible() {
        return !activeOrbs.isEmpty()
                || (lastEventAt > 0 && System.currentTimeMillis() - lastEventAt < IDLE_HIDE_MS);
    }

    /** Whether the player carries the event's gear at all – the overlay's self-gate. */
    public boolean hasOrbs() {
        return hasEventGear;
    }

    /** Every orb currently out, oldest first. */
    public List<ActiveOrb> activeOrbs() {
        return activeOrbs;
    }

    /**
     * The orb the single-orb HUDs speak for: the one running out first, since that is the deadline
     * the player is actually racing when two are up.
     */
    public ActiveOrb activeOrb() {
        List<ActiveOrb> orbs = activeOrbs;
        // Every orb gets the same 90 seconds, so the oldest is always the one expiring first.
        return orbs.isEmpty() ? null : orbs.get(0);
    }

    /** Every charged orb still waiting to be clicked. */
    public List<ChargedOrb> chargedOrbs() {
        return chargedOrbs;
    }

    public List<LivingEntity> nearbyPigs() {
        return nearbyPigs;
    }

    /** The pig belonging to one orb, or null once it is gone. */
    public LivingEntity pigOf(ActiveOrb orb) {
        ClientLevel level = Minecraft.getInstance().level;
        if (orb == null || level == null) {
            return null;
        }
        Entity entity = level.getEntity(orb.pigEntityId());
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    /** The pig belonging to {@link #activeOrb()}, or null once it is gone. */
    public LivingEntity activePig() {
        return pigOf(activeOrb());
    }

    public int orbsUsed() {
        return orbsUsed;
    }

    public int orbsCharged() {
        return orbsCharged;
    }

    public int orbsExpired() {
        return orbsExpired;
    }

    public int shinyTokens() {
        return shinyTokens;
    }

    public long coinsGained() {
        return coinsGained;
    }

    public Map<String, Integer> loot() {
        return loot;
    }

    public Map<String, Long> skillXp() {
        return skillXp;
    }

    /** What the orbs spent this session cost, in coins. */
    public double spent() {
        return (double) orbsUsed * ORB_COST;
    }

    /** Gross income: the coin rewards plus the market value of every item pulled from an orb. */
    public double income() {
        double total = coinsGained;
        for (Map.Entry<String, Integer> entry : loot.entrySet()) {
            total += price(entry.getKey()) * entry.getValue();
        }
        return total;
    }

    /** Net session profit: income minus the orbs it took. Negative on a bad run, and shown as such. */
    public double profit() {
        return income() - spent();
    }

    /** Profit extrapolated to an hour, or 0 until the session is long enough to mean anything. */
    public double profitPerHour() {
        long span = lastEventAt - firstEventAt;
        return span < 60_000L ? 0 : profit() * 3_600_000.0 / span;
    }

    /** Average net profit per orb spent – the number that says whether the run is worth continuing. */
    public double profitPerOrb() {
        return orbsUsed == 0 ? 0 : profit() / orbsUsed;
    }

    /** Share of activated orbs whose pig actually made it back, as a percentage. */
    public double successRate() {
        int resolved = orbsCharged + orbsExpired;
        return resolved == 0 ? 0 : orbsCharged * 100.0 / resolved;
    }

    /** Value of one item id: Bazaar instasell first, lowest BIN second, 0 when unknown. */
    public double price(String itemId) {
        if (COIN_ID.equals(itemId)) {
            return 1;
        }
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(itemId);
        if (value != null && value.sell() > 0) {
            return value.sell();
        }
        Long lbin = LbinCache.getInstance().getLbin(itemId);
        return lbin == null ? 0 : lbin;
    }

    /** What one Shiny Token is worth, when the market knows – tokens are Piggy's own currency. */
    public double tokenPrice() {
        return price(TOKEN_ID);
    }

    /** Clears the session counters (the settings button). Live orb state is left alone. */
    public void resetSession() {
        orbsUsed = 0;
        orbsCharged = 0;
        orbsExpired = 0;
        shinyTokens = 0;
        coinsGained = 0;
        loot.clear();
        skillXp.clear();
        firstEventAt = 0;
        lastEventAt = 0;
    }

    // ------------------------------------------------------------------ helpers

    /** Marks session activity, starting the clock on the first event. */
    private void touch() {
        long now = System.currentTimeMillis();
        if (firstEventAt == 0) {
            firstEventAt = now;
        }
        lastEventAt = now;
    }

    private static void playSound(float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch);
        }
    }

    /** How many of one SkyBlock item id the player carries loose. */
    private static int countInInventory(LocalPlayer player, String itemId) {
        int total = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && itemId.equals(SkyblockItem.id(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The SkyBlock id behind a reward's display name: the item catalogue first, a mechanical
     * UPPER_SNAKE guess as the fallback – a guessed id still renders and often prices, an
     * unresolved one would book nothing at all.
     */
    private static String resolveItemId(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String name = displayName.replaceAll("§.", "").trim();
        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        SkyBlockItemCatalog.Entry entry = catalog.byName(name);
        if (entry == null) {
            entry = catalog.byNormalizedName(name);
        }
        if (entry != null) {
            return entry.id;
        }
        String constructed = name.toUpperCase(Locale.ROOT).replace(' ', '_').replaceAll("[^A-Z0-9_]", "");
        return constructed.isEmpty() ? null : constructed;
    }

    /** "25,000" -> 25000; a broken group counts as 0 so a reworded line can never book a wild number. */
    private static long parseAmount(String group) {
        if (group == null) {
            return 0;
        }
        try {
            return Long.parseLong(group.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
