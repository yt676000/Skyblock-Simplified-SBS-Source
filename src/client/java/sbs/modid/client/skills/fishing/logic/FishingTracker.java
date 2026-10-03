/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.render.FishingAlert;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows what this fishing session produced: caught sea creatures, loot items,
 * shards, and what all of it is worth.
 *
 * <p>Multiple sources, because Hypixel gives no "you looted X" event and fishing loot does not all
 * land in the same place, so it is booked from several channels:
 * <ul>
 *   <li><b>Sea creatures</b> count when you <b>catch</b> them: Hypixel sends the spawn message only
 *       to the fisher who hooked the creature, so that message is both the detection and the proof
 *       that the catch is yours – a busy pond never fills your list with the neighbours' creatures.
 *       A "Double Hook!" line right before it means the one message stands for two.</li>
 *   <li><b>Inventory items</b> come from diffing the player's inventory each tick – a stack that
 *       grew is a drop. Only gains that fishing can explain are booked: anything arriving right
 *       after a bite or a catch message (the reeled-in item, a quick kill's drops), plus recognised
 *       fishing-loot ids while the session is demonstrably still fishing. Gains while a container
 *       screen is open (that is moving items around, not fishing) and everything else the inventory
 *       picks up never become "profit". Equipment – rods, armor, minions, sacks
 *       ({@link FishingData#isNeverLoot}) – is refused on this channel entirely: it is carried and
 *       worn, never fished up, and the held rod or worn suit streaming back in after a lobby swap
 *       used to book as a catch. Announced equipment <b>drops</b> (Yeti Rod, Squid Boots) still
 *       count via their "RARE DROP!" chat line.</li>
 *   <li><b>Sack items</b> never touch the inventory – with sacks, most of a fishing session goes
 *       straight past the diff, which is why the tracker looked dead for sack owners. The
 *       "[Sacks] +N items" chat line carries the per-item breakdown in its <i>hover text</i>
 *       ("+24 Raw Fish (Fishing Sack)"); that hover is parsed here.</li>
 *   <li><b>Attribute shards</b> go straight to the Hunting Box, announced only in chat. The
 *       catch line books immediately – it is the only line guaranteed to accompany a fishing
 *       catch – and a "You sent N ... Shards to your Hunting Box." line naming the same shard
 *       shortly after tops the booking up to N, so Hunter Fortune extras count without any line
 *       counting twice. Priced via their Bazaar ids ({@code SHARD_*}).</li>
 *   <li><b>RNG drops</b> are announced in chat ("RARE DROP! ..."): booked from the line and
 *       <i>deducted</i> from the next inventory gain of the same item, so a drop that is also
 *       picked up is never counted twice. <b>Treasure item lines</b> ("GOOD CATCH! You
 *       found/caught ...") are recognised but never booked – the item always arrives physically
 *       (inventory or sack) and that channel books it once; treasure <b>coins</b> have no
 *       physical arrival and stay booked from their chat line.</li>
 *   <li><b>Baits</b> are the cost side, never loot: the inventory diff books a bait as used when
 *       its stack <b>actually shrinks</b> while fishing, and its Bazaar value is subtracted from
 *       the session profit. Counting "one per bite" instead would overstate the cost – bait-save
 *       effects (the Caster enchant, Stingy Sinker, the Spiked Atrocity talisman, shard buffs)
 *       regularly keep the bait on a cast, and a saved bait costs nothing. Only small decreases
 *       count (a cast consumes one); a whole stack vanishing is stashing or a lobby swap, never
 *       consumption. The SkyBlock Menu item is excluded from loot entirely – opening the menu
 *       re-hands it to the inventory, which is not a catch.</li>
 * </ul>
 *
 * <p>The inventory diff only counts <b>increases</b>: spending, selling or stashing an item must
 * never subtract from a session's catch, or the tracker would read as if the drop never happened.
 *
 * <p>Prices: Bazaar instasell where the item is bazaar-tradeable, otherwise the Auction House
 * lowest BIN – so sea creature drops like armor pieces and rare AH-only items are valued too.
 */
public final class FishingTracker {

    private static final FishingTracker INSTANCE = new FishingTracker();

    /** How long after the last cast / catch recognised fishing-loot ids still count. */
    private static final long ACTIVITY_WINDOW_MS = 5 * 60_000L;

    /**
     * How long after the last cast / catch the tracker panels stay on screen once the rod is put
     * away. Separate from {@link #ACTIVITY_WINDOW_MS} on purpose: that one decides what counts as
     * fishing <i>loot</i>, this one decides when the panels stop being in the way, and the two must
     * be free to move apart. The session itself is untouched either way – the panels come back with
     * every number intact on the next cast; only the drawing stops.
     */
    private static final long IDLE_HIDE_MS = 5 * 60_000L;

    /**
     * How long after a bite or catch message <b>any</b> gained item counts as fishing loot. Long
     * enough to reel in and walk over a quick kill's drops, short enough that whatever else the
     * inventory picks up between casts stays out of the profit.
     */
    private static final long LOOT_PICKUP_WINDOW_MS = 10_000L;

    /** How long after a "Double Hook!" line the following catch still counts as the doubled one. */
    private static final long DOUBLE_HOOK_WINDOW_MS = 5_000L;

    /**
     * How long a channel's recent booking stays available for cross-channel dedup. Generous, because
     * the "[Sacks]" summary can lag the individual rare-drop line by many seconds - one physical drop
     * reported by both must still collapse to one. Same-channel repeats are NEVER deduped, so a long
     * window can only ever cancel a genuine duplicate report, never a real double.
     */
    private static final long LEDGER_TTL_MS = 30_000L;

    /**
     * Cross-channel TTL specifically for the INVENTORY↔SACK pair. The "[Sacks]" summary is
     * AGGREGATED by a user-chosen interval and can lag the physical pickup by minutes – far beyond
     * the 30s general ledger – which re-booked a flexbone that had already been counted from the
     * inventory (the "still counts double" bug). A real double (two flexbones, one to inventory and
     * one to sack, minutes apart) is rare enough that the long window is the right trade.
     */
    private static final long SACK_INV_TTL_MS = 5 * 60_000L;

    /**
     * TTL for the inventory-REMOVAL ledger: an item that leaves the inventory shortly before a
     * "[Sacks] +N" line of the same item is a TRANSFER into the sack (auto-absorb or a manual
     * stash), not new loot – the sack line must not re-book it.
     */
    private static final long REMOVAL_TTL_MS = 90_000L;

    /** Two shard lines of DIFFERENT kinds for the same shard within this window are one shard, not
     *  two – generous, because the Hunting Box transfer line can lag its catch by several seconds. */
    private static final long SHARD_DEDUPE_MS = 10_000L;

    /**
     * Ticks the open Fishing Bag's content must stay non-empty and unchanged before it is believed.
     * The server streams a container's content in AFTER the screen itself opens – on the open tick
     * every slot is still air, and Hypixel fills its menus progressively over several ticks – so
     * reading (or worse, reconciling against) the bag too early read the whole not-yet-arrived
     * reserve as "consumed" and booked thousands of phantom baits into the session cost.
     */
    private static final int BAG_SETTLE_TICKS = 4;

    /**
     * After (re)joining a world - which every Hypixel lobby / island swap is - the server streams
     * the inventory back in over several ticks. For this long the diff only rebuilds its baseline
     * and books nothing, so the returning items never read as one giant catch.
     */
    private static final long JOIN_SETTLE_MS = 4_000L;

    /** How long a purchase line keeps suppressing the matching inventory gain (bought item lands late). */
    private static final long PURCHASE_TTL_MS = 5 * 60_000L;

    /**
     * Largest per-tick bait-stack decrease still booked as consumption. A cast consumes at most one
     * bait (the allowance covers a laggy sync batching a few casts); anything larger is the player
     * stashing or dropping the stack, which is never a cost.
     */
    private static final int MAX_BAIT_CONSUMED_PER_TICK = 4;

    /**
     * The "Baits Remaining: N" lore line Hypixel writes on the rod's held-bait display (hotbar slot 9
     * while a fishing rod is held). Tolerant of colour codes (stripped before matching), an optional
     * colon and thousands separators, so a reworded "Baits Remaining 1,234" still parses.
     */
    private static final Pattern BAITS_REMAINING = Pattern.compile(
            "Baits?\\s+Remaining:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /**
     * After this long with no cast / bite / catch the session clock freezes – the tracker "pauses"
     * so a break at the bazaar or afk time never dilutes the per-hour rate. Also caps a single
     * accrual step, so a lag spike can never dump a chunk of idle time into the session at once.
     */
    private static final long PAUSE_AFTER_MS = 10_000L;

    // ------------------------------------------------------------------
    // Chat loot patterns (matched on plain text, colour codes already stripped).
    // The shapes match the Hypixel lines they parse.
    // ------------------------------------------------------------------

    /**
     * "🎣 GOOD CATCH! You caught a Shinyfish Shard!" / "You caught x2 Bal Shards!" – deliberately
     * unanchored: the fishing variant carries an icon + "GOOD CATCH!" prefix (seen live), the
     * hunting variant does not. Amount forms "2", "x2" and "2x" all parse.
     */
    private static final Pattern SHARD_CAUGHT = Pattern.compile(
            "You caught(?: an?)?(?: x?(\\d+)x?)? (.+?) Shards?!");
    /** "You sent a Tide Shard to your Hunting Box." / "You sent 3 Tide Shards to your ..." – the
     *  AUTHORITY on how many shards a catch produced; amount forms "3", "x3" and "3x" all parse.
     *  The trailing punctuation is left open on purpose, a reworded ending must not lose the line. */
    private static final Pattern SHARD_BOXED = Pattern.compile(
            "You sent (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? to your Hunting Box");
    /** "LOOT SHARE You received a Bal Shard for assisting ..." – shards you get are income too. */
    private static final Pattern SHARD_SHARED = Pattern.compile(
            "LOOT SHARE You received (?:an? )?(?:x?(\\d+)x? )?(.+?) Shards? for assisting");
    /** Every shard line, tried in order; all bind (amount?, name) as groups 1 and 2. */
    private static final Pattern[] SHARD_LINES = {SHARD_CAUGHT, SHARD_BOXED, SHARD_SHARED};

    /** "GOOD CATCH! You found a Fish Bait!" / "GOOD CATCH! You caught a Flexbone!" (also GREAT /
     *  OUTSTANDING). Recognition only – the physical arrival (inventory or sack) books the item. */
    private static final Pattern TREASURE_ITEM = Pattern.compile(
            "(?:GOOD|GREAT|OUTSTANDING) CATCH! You (?:found|caught) (?:an? )?(?:(\\d+)x )?(.+?)!?$");
    /** "⛃ GOOD CATCH! You caught 1,542 Coins!" – treasure coins are profit too, and having no
     *  physical arrival they are the one treasure that books from its chat line. */
    private static final Pattern TREASURE_COINS = Pattern.compile(
            "CATCH! You (?:caught|found) ([\\d,]+) Coins!");

    /** One hover line of the "[Sacks]" message: "+24 Raw Fish (Fishing Sack)". */
    private static final Pattern SACK_LINE = Pattern.compile(
            "([+-][\\d,.]+) (.+?) \\((.+)\\)");

    /**
     * A player-chat line: an optional guild/party channel + optional [rank] tags, a 2-16 char IGN,
     * then "<something>: ". Sea-creature spawn lines never carry a "name: " sender, so matching this
     * lets a player named after a creature ("Nessie", "Squid") not trip the catch detector.
     */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Guild|Party|Co-op|Officer|G|P|O) > )?(?:\\[[^\\]]+\\] )*[A-Za-z0-9_]{2,16}[^:]{0,4}: ");

    /**
     * Purchase lines: a bought item lands in the inventory the same as a fished drop, so without this
     * a Bazaar / Auction House / NPC buy would count as fishing profit. Each binds (count?, name).
     */
    private static final Pattern[] PURCHASE_LINES = {
            Pattern.compile("\\[Bazaar] Bought (?:([\\d,]+)x )?(.+?) for [\\d,.]+ coins!"),
            Pattern.compile("\\[Bazaar] Claimed (?:([\\d,]+)x )?(.+?) worth [\\d,.]+ coins"),
            Pattern.compile("You purchased (?:([\\d,]+)x )?(.+?) for [\\d,.]+ coins!"),
            Pattern.compile("You bought (?:([\\d,]+)x )?(.+?) for [\\d,.]+ coins!"),
            // Auction House: the item is CLAIMED from the "manage auctions" menu, often minutes
            // after the purchase line - a Water Hydra Head bought on AH arrived this way and counted.
            Pattern.compile("You claimed (.+?) from .+?(?:auction|Auction)"),
            Pattern.compile("You (?:bought|purchased) (.+?)!"),
    };

    /** Pseudo item id for treasure coins; {@link #price} values it at 1 coin apiece. */
    private static final String COIN_ID = "SKYBLOCK_COIN";

    /** When the last "Double Hook!" line arrived, or 0 once its catch has claimed it. */
    private long doubleHookAt;

    /**
     * The three channels a physical drop can reach the tracker through. The same drop can be
     * reported by more than one - a rare-drop chat line AND the "[Sacks]" summary for the catch that
     * went into the sack; a rare-drop line AND the inventory pickup - and must then be counted ONCE.
     * A repeat from the SAME channel (two rare-drop lines, inventory +2, sack +2) is a genuine double
     * and is kept. {@link #bookLoot} enforces exactly this: it deducts against the OTHER channels'
     * recent bookings, never its own.
     */
    private enum Channel { CHAT, INVENTORY, SACK }

    /** Per-channel recent bookings (id -> count, id -> time), for cross-channel dedup. */
    private final Map<Channel, Map<String, Integer>> recentByChannel = new java.util.EnumMap<>(Channel.class);
    private final Map<Channel, Map<String, Long>> recentAtByChannel = new java.util.EnumMap<>(Channel.class);

    /** Bought items (Bazaar / AH / NPC) still waiting to cancel the matching inventory gain. */
    private final Map<String, Integer> pendingPurchase = new HashMap<>();
    private final Map<String, Long> pendingPurchaseAt = new HashMap<>();

    /** Items that recently LEFT the inventory – a following sack "+N" of the same id is a transfer. */
    private final Map<String, Integer> recentRemoved = new HashMap<>();
    private final Map<String, Long> recentRemovedAt = new HashMap<>();

    /** Until this moment the inventory diff only rebuilds its baseline (post-join settle). */
    private long settleUntil;

    /**
     * How long after a container screen CLOSES gains still count as menu movement, not loot.
     * Hypixel finishes menu-driven item moves (wardrobe / equipment / loadout swaps, storage) a
     * beat after the screen is already gone, so the container-open guard alone missed them – a
     * loadout swap's returning Wither Goggles landed post-close inside the just-fished window and
     * booked as a 7m "catch". Chat-announced drops are unaffected (their channel is not gated).
     */
    private static final long CONTAINER_SETTLE_MS = 2_000L;

    /** Whether the previous tick had a container screen open (close-edge detection). */
    private boolean containerWasOpen;

    /** Until this moment gains are still the just-closed menu settling, never loot. */
    private long containerClosedSettleUntil;

    /** The last shard line's shard, so a caught+boxed pair for one shard counts once. */
    private String lastShardName = "";
    private long lastShardAt;
    /** Which of {@link #SHARD_LINES} the last shard line matched – a repeat of the SAME line kind
     *  is a second shard (two quick catches), only a DIFFERENT kind is the same shard re-announced. */
    private int lastShardPattern = -1;
    /** How many of that shard the last line booked – the re-announcement may know of MORE. */
    private int lastShardAmount;

    /** Sea creature name -> how many YOU caught this session. */
    private final Map<String, Integer> creatures = new LinkedHashMap<>();
    /** Item id -> how many were gained this session. */
    private final Map<String, Integer> loot = new LinkedHashMap<>();
    /** Bait id -> how many catches consumed one – the cost the profit subtracts, one per bite. */
    private final Map<String, Integer> baitsUsed = new LinkedHashMap<>();
    /**
     * Bait id -> how many the last-seen Fishing Bag held. Baits kept in the bag never touch the
     * inventory, so the inventory scan alone reads them as zero; refreshed by {@link #captureBaitSack}
     * whenever the Fishing Bag menu is open, and added to the loose baits by {@link #remainingBaits()}.
     * Kept across lobby swaps like the storage index, since the bag is account-wide and rarely open.
     */
    private final Map<String, Integer> baitSackReserve = new LinkedHashMap<>();
    /** The bait most recently consumed – the "bait you're currently using" for the counter HUD. */
    private String lastConsumedBaitId;
    /**
     * Bait id -> casts booked as ESTIMATED consumption since the Fishing Bag was last seen. Bag-fed
     * baits are consumed server-side with no client-visible change at all, so each cast books one
     * bait on credit – the HUD chip and the session cost move immediately – and the next look into
     * the bag replaces the guesses with the bag's real counts ({@link #reconcileBaitConsumption}).
     */
    private final Map<String, Integer> pendingBaitEstimate = new LinkedHashMap<>();

    /**
     * The rod's slot-9 bait display ({@link #readBaitDisplay}) – Hypixel's own "Baits Remaining: N"
     * readout, which is the AUTHORITY on how many of the loaded bait are left (it already sums the
     * inventory and the Fishing Bag). {@code displayBaitsRemaining} is -1 when no display is visible.
     * Read from the render thread by the HUD, so kept volatile.
     */
    private volatile int displayBaitsRemaining = -1;
    private volatile String displayBaitId;
    /** Whether a slot-9 bait display was read THIS tick – while true it is the sole bait source. */
    private volatile boolean baitDisplayActive;
    /**
     * Whether a bait display has been seen at all this session. Once true the display is authoritative
     * and the estimate paths (per-cast credit, inventory-diff shrink, bag reconcile) stop booking, so
     * consumption is never counted twice. You can only consume bait while holding the rod – which is
     * exactly when the display shows – so nothing real is lost while it is briefly absent.
     */
    private boolean baitDisplaySeen;
    /** Previous tick's display reading, for the between-tick drop that is the real consumption. */
    private int lastDisplayRemaining = -1;
    private String lastDisplayBaitId;
    /** Throttle for the {@code [SBS][Fishing] bait display} tuning log. */
    private long lastBaitDisplayLogAt;

    /** Whether the bobber was out last tick – a false->true edge is a cast ({@link #onCast}). */
    private boolean bobberOut;
    /** Whether the Fishing Bag screen was open last tick – a closed->open edge arms a reconcile. */
    private boolean baitBagWasOpen;
    /** Whether THIS opening of the bag has reconciled yet – armed on the open edge, done once the
     *  content settles; from then on the open bag is mirrored live, tick by tick. */
    private boolean baitBagReconciled;
    /** Full content of the open bag last tick (every item, not just baits) – the settle check:
     *  only a non-empty content that stops changing is the real, fully-streamed-in bag. */
    private Map<String, Integer> lastBagContent;
    /** Consecutive ticks the open bag's content has been non-empty and unchanged. */
    private int bagStableTicks;
    /** Set by {@link #reset()}: the next bag look only re-baselines, it must not book the old
     *  session's consumption into the fresh one. */
    private boolean skipNextBaitReconcile;

    /** Last seen count per item id, the baseline the diff works against. */
    private final Map<String, Integer> lastCounts = new HashMap<>();
    private boolean baselineReady;

    /** Last moment the player demonstrably fished: cast, bite, creature caught. A bobber merely
     *  sitting in the water is deliberately NOT activity – it must not hold the session clock's
     *  pause off while the player waits afk ({@link #accrueActiveTime}). */
    private volatile long lastActivityAt;

    /** Last bite or catch message – the moments fishing actually hands the player an item. */
    private volatile long lastLootEventAt;

    /** Bites reeled this session, counted by {@link BiteIndicator} ("Times Fished"). */
    private int timesFished;

    /**
     * Session time that actually counts, in milliseconds: it accrues only while the player is
     * fishing and freezes once {@link #PAUSE_AFTER_MS} pass with no cast / bite / catch, so a break
     * at the bazaar or afk never dilutes the "per hour" rate. {@link #accrueActiveTime} does the work.
     */
    private long activeMillis;
    /** Wall-clock of the previous accrual tick, 0 before the first – the base for each delta. */
    private long lastAccrualAt;

    private FishingTracker() {
        for (Channel channel : Channel.values()) {
            recentByChannel.put(channel, new HashMap<>());
            recentAtByChannel.put(channel, new HashMap<>());
        }
    }

    public static FishingTracker getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().fishing.enabled;
    }

    // ------------------------------------------------------------------
    // Intake
    // ------------------------------------------------------------------

    /**
     * Called for every chat line. Sea creature catches, attribute shards, RNG drops and the sack
     * breakdown all arrive here; the structured lines are tried first because the creature
     * detection is a loose contains-match that lines like "You caught a Thunder Shard!" would
     * otherwise trip.
     *
     * @param message   the plain text (colour codes already stripped)
     * @param component the full component, for the sack message whose items hide in the hover text
     */
    public void onChat(String message, Component component) {
        if (!enabled()) {
            return;
        }
        GoldenFishTracker.getInstance().onChat(message);
        // Checked first: the double hook line announces no creature of its own, it doubles the
        // catch that follows it.
        if (FishingData.isDoubleHook(message)) {
            lastActivityAt = System.currentTimeMillis();
            doubleHookAt = lastActivityAt;
            return;
        }
        if (component != null && message.startsWith("[Sacks]")) {
            onSackMessage(component);
            return;
        }
        // Purchases are booked as suppression only (never loot): the bought item lands in the
        // inventory just like a drop, and would otherwise count as profit.
        if (onPurchaseLine(message)) {
            return;
        }
        if (onShardLine(message) || onDropLine(message)) {
            return;
        }
        // Sea-creature spawn lines are SERVER flavour text, never a player message - so a player
        // named after a creature ("Nessie: gg") must not book a catch. Player chat carries a
        // "<name>: " sender; spawn lines never do.
        if (isPlayerChat(message)) {
            return;
        }
        String creature = FishingData.seaCreatureFor(message);
        if (creature != null) {
            lastActivityAt = System.currentTimeMillis();
            lastLootEventAt = lastActivityAt;
            creatures.merge(creature, consumeDoubleHook() ? 2 : 1, Integer::sum);
            FishingAlert.getInstance().trigger(creature);
        }
    }

    // ------------------------------------------------------------------
    // Chat loot intake
    // ------------------------------------------------------------------

    /**
     * Books an attribute shard line, or returns false when {@code message} is none. Shards go
     * straight to the Hunting Box, so chat is the only place they can be counted at all.
     *
     * <p>The catch line books immediately: it is the only line guaranteed to arrive the moment a
     * shard is fished – the Hunting Box transfer line can lag by seconds or, for some sources,
     * never come at all, and waiting for it made the tracker miss most shards. When the box line
     * DOES follow within the window it is the authority on the amount: with Hunter Fortune the
     * catch line stays "You caught a Tide Shard!" while "You sent 2 Tide Shards to your Hunting
     * Box." carries the real total – so the pair-dedup books exactly the shortfall on top, and
     * the tracker ends up showing what actually landed in the box.
     */
    private boolean onShardLine(String message) {
        for (int i = 0; i < SHARD_LINES.length; i++) {
            Matcher matcher = SHARD_LINES[i].matcher(message);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(2).trim();
            int amount = parseCount(matcher.group(1));
            long now = System.currentTimeMillis();
            // Only the Hunting Box transfer line ever RE-announces a shard: it always FOLLOWS the
            // catch / loot-share line of the same catch, so a box line naming the last shard within
            // the window books just the shortfall (the pair totals whatever the larger line said).
            // Catch and loot-share lines always open a NEW booking - a second "You caught a Tide
            // Shard!" right after a box line is a second catch, not an echo; treating any
            // different-kind pair as one (the old rule) swallowed quick same-shard catches once
            // the window grew to cover the box line's lag.
            boolean topUp = SHARD_LINES[i] == SHARD_BOXED
                    && lastShardPattern >= 0 && SHARD_LINES[lastShardPattern] != SHARD_BOXED
                    && name.equalsIgnoreCase(lastShardName)
                    && now - lastShardAt < SHARD_DEDUPE_MS;
            if (topUp) {
                lastShardAt = now;
                if (recentFishingActivity() && amount > lastShardAmount) {
                    bookLoot("SHARD_" + normalizeId(name), amount - lastShardAmount, Channel.CHAT);
                    lastShardAmount = amount;
                }
                return true;
            }
            lastShardName = name;
            lastShardAt = now;
            lastShardPattern = i;
            lastShardAmount = 0;
            // A shard charmed on land is hunting, not fishing - recognised but not booked.
            if (recentFishingActivity()) {
                bookLoot("SHARD_" + normalizeId(name), amount, Channel.CHAT);
                lastShardAmount = amount;
            }
            return true;
        }
        return false;
    }

    /** Books an RNG drop / treasure line, or returns false when {@code message} is none. */
    private boolean onDropLine(String message) {
        Matcher coins = TREASURE_COINS.matcher(message);
        if (coins.find()) {
            if (recentFishingActivity()) {
                bookLoot(COIN_ID, parseCount(coins.group(1)), Channel.CHAT);
            }
            return true;
        }
        // Treasure ITEMS are recognition only: the item always arrives physically (inventory
        // pickup or sack summary) and that channel books the one real item. Booking the
        // announcement too is how "GOOD CATCH! You caught a Flexbone!" counted 2 - once from
        // this line, once from the pickup - whenever the two resolved to different ids or the
        // sack summary outlived the cross-channel dedup window.
        if (TREASURE_ITEM.matcher(message).find()) {
            return true;
        }
        // "RARE DROP! Squid Boots (+4% Magic Find)" and the rest of the family: one shared parser.
        sbs.modid.client.core.util.RareDropLine.Drop drop =
                sbs.modid.client.core.util.RareDropLine.parse(message);
        if (drop == null) {
            return false;
        }
        if (recentFishingActivity()) {
            String itemId = resolveItemId(drop.item());
            if (itemId != null) {
                bookLoot(itemId, drop.count(), Channel.CHAT);
            }
        }
        return true;
    }

    /**
     * Books the per-item breakdown of a "[Sacks]" message. The visible line only says
     * "+227 items" – the actual items sit in the hover text, one "+24 Raw Fish (Fishing Sack)"
     * line each.
     */
    private void onSackMessage(Component component) {
        if (!recentFishingActivity()) {
            return;   // sacks also fill while farming or mining - not this tracker's business
        }
        StringBuilder hover = new StringBuilder();
        collectHoverText(component, hover);
        boolean justFished = justFished();
        long now = System.currentTimeMillis();
        // ONE amount per (item, sack) within a single message, collapsed by MAX: the hover can sit
        // on more than one styled segment of the chat line, so collectHoverText may deliver the same
        // breakdown twice - summing would book every sacked item double (the flexbone x2 bug).
        // Hypixel itself aggregates one line per item+sack, so max loses nothing real.
        Map<String, Integer> perItem = new LinkedHashMap<>();
        Map<String, String> sackOf = new HashMap<>();
        for (String line : hover.toString().split("\n")) {
            Matcher matcher = SACK_LINE.matcher(line.replaceAll("§.", ""));
            if (!matcher.find() || matcher.group(1).startsWith("-")) {
                continue;   // removals are spending, and spending never lowers a session's catch
            }
            String itemId = resolveItemId(matcher.group(2));
            if (itemId == null || FishingData.isNeverLoot(itemId)) {
                continue;   // a bait stack stashed INTO a sack is not a catch
            }
            String key = itemId + "|" + matcher.group(3);
            perItem.merge(key, parseCount(matcher.group(1)), Math::max);
            sackOf.put(key, matcher.group(3));
        }
        for (Map.Entry<String, Integer> entry : perItem.entrySet()) {
            String itemId = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            int amount = entry.getValue();
            // A recent inventory REMOVAL of the same item means this "+N" is (partly) a transfer
            // into the sack - the item was already counted when it entered the inventory.
            int transferred = consumeLedger(recentRemoved, recentRemovedAt, itemId, amount,
                    REMOVAL_TTL_MS, now);
            amount -= transferred;
            if (transferred > 0 && itemId.contains("FLEXBONE")) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Fishing] {} sack +{} suppressed as transfer ({} left)",
                        itemId, transferred, amount);
            }
            if (amount <= 0) {
                continue;
            }
            // Same two-tier rule as the inventory diff: right after a bite/catch everything is
            // fishing's doing; otherwise the item must look like fishing loot (or sit in a
            // fishing sack), so a farming sack filling mid-session stays out.
            boolean fishingSack = sackOf.get(entry.getKey()).toLowerCase(Locale.ROOT).contains("fishing");
            if (justFished || fishingSack || FishingData.isFishingLoot(itemId)) {
                bookLoot(itemId, amount, Channel.SACK);
            }
        }
    }

    /** Every hover ShowText in the component tree, one line per component, appended to {@code out}. */
    private static void collectHoverText(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }

    /**
     * Books a Bazaar / Auction House / NPC purchase for suppression (never as loot), so the bought
     * item that is about to land in the inventory is cancelled out of the profit. Returns false when
     * {@code message} is not a purchase line.
     */
    private boolean onPurchaseLine(String message) {
        for (Pattern pattern : PURCHASE_LINES) {
            Matcher matcher = pattern.matcher(message);
            if (!matcher.find()) {
                continue;
            }
            // The two-group patterns bind (count, name); the one-group AH patterns bind just (name).
            String name = matcher.groupCount() >= 2 ? matcher.group(2) : matcher.group(1);
            int count = matcher.groupCount() >= 2 ? parseCount(matcher.group(1)) : 1;
            String itemId = resolveItemId(name);
            if (itemId != null) {
                suppressPurchase(itemId, count);
            }
            return true;
        }
        return false;
    }

    /**
     * Cancels a bought item BOTH ways: it un-books any of it the inventory diff already counted (the
     * item arrived before its purchase line), and suppresses what is still to arrive (an AH item is
     * claimed minutes after the purchase line - a Water Hydra Head bought on AH was counting).
     */
    private void suppressPurchase(String itemId, int count) {
        long now = System.currentTimeMillis();
        int booked = consumeLedger(recentByChannel.get(Channel.INVENTORY),
                recentAtByChannel.get(Channel.INVENTORY), itemId, count, PURCHASE_TTL_MS, now);
        if (booked > 0) {
            int left = Math.max(0, loot.getOrDefault(itemId, 0) - booked);
            if (left == 0) {
                loot.remove(itemId);
            } else {
                loot.put(itemId, left);
            }
        }
        int remaining = count - booked;
        if (remaining > 0) {
            pendingPurchase.merge(itemId, remaining, Integer::sum);
            pendingPurchaseAt.put(itemId, now);
        }
    }

    /**
     * Books {@code count} of {@code itemId} to the session on a given {@code channel}, deduping
     * ACROSS channels: any of this drop already reported by a DIFFERENT channel is cancelled (one
     * physical drop, reported twice), and only the remainder is booked and remembered under this
     * channel. A repeat on the SAME channel is never touched, so real doubles stay doubled.
     */
    private void bookLoot(String itemId, int count, Channel channel) {
        if (itemId == null || itemId.isEmpty() || count <= 0) {
            return;
        }
        // Trophy fish are their own collection, never sold – the profit tracker ignores them whatever
        // channel they arrive on (inventory diff, sack breakdown, RNG-drop line).
        if (FishingData.isTrophyFish(itemId)) {
            return;
        }
        long now = System.currentTimeMillis();
        int net = count;
        for (Channel other : Channel.values()) {
            if (other != channel && net > 0) {
                // The sack summary is user-aggregated and can lag the inventory pickup by minutes,
                // so the INVENTORY<->SACK pair dedups over a much longer window than chat does.
                boolean sackInvPair = (channel == Channel.SACK && other == Channel.INVENTORY)
                        || (channel == Channel.INVENTORY && other == Channel.SACK);
                net -= consumeLedger(recentByChannel.get(other), recentAtByChannel.get(other),
                        itemId, net, sackInvPair ? SACK_INV_TTL_MS : LEDGER_TTL_MS, now);
            }
        }
        if (itemId.contains("FLEXBONE")) {
            // Deliberate targeted diagnostic for the long-lived "flexbone counts double" report:
            // one line per booking shows exactly which channel booked how much.
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] {} book request {} via {} -> net {}", itemId, count, channel, net);
        }
        if (net <= 0) {
            return;   // every unit was already booked by another channel - same physical drop
        }
        loot.merge(itemId, net, Integer::sum);
        recentByChannel.get(channel).merge(itemId, net, Integer::sum);
        recentAtByChannel.get(channel).put(itemId, now);
        // Lifetime drop log: config/sbs/tracker/fishingtracker.txt.
        sbs.modid.client.core.tracker.TrackerStore.record("fishingtracker", itemId, net);
    }

    /**
     * Deducts up to {@code amount} of {@code itemId} from a timestamped ledger, consuming what it
     * returns and dropping the entry once emptied or stale. The one primitive behind every dedup:
     * cross-channel loot and purchase-vs-inventory.
     */
    private static int consumeLedger(Map<String, Integer> ledger, Map<String, Long> at,
                                     String itemId, int amount, long ttl, long now) {
        Long bookedAt = at.get(itemId);
        if (bookedAt == null) {
            return 0;
        }
        if (now - bookedAt > ttl) {
            at.remove(itemId);
            ledger.remove(itemId);
            return 0;
        }
        int have = ledger.getOrDefault(itemId, 0);
        int used = Math.min(have, amount);
        if (used >= have) {
            at.remove(itemId);
            ledger.remove(itemId);
        } else {
            ledger.put(itemId, have - used);
        }
        return used;
    }

    /**
     * The SkyBlock id behind a chat display name: the official item catalogue first (exact, then
     * reforge/level-stripped), a mechanical UPPER_SNAKE guess as the last resort – a guessed id
     * still renders fine and often even prices, an unresolved one would book nothing.
     */
    private static String resolveItemId(String displayName) {
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
        String constructed = normalizeId(name);
        return constructed.isEmpty() ? null : constructed;
    }

    /** Whether {@code message} is a player chat line (has a "name: " sender), not server flavour. */
    private static boolean isPlayerChat(String message) {
        return message != null && PLAYER_CHAT.matcher(message).find();
    }

    /** "Abyssal Lanternfish" -> "ABYSSAL_LANTERNFISH". */
    private static String normalizeId(String name) {
        return name.toUpperCase(Locale.ROOT).replace(' ', '_').replaceAll("[^A-Z0-9_]", "");
    }

    /** Removes {@code §x} colour codes without the per-call regex compile of String.replaceAll –
     *  used on the per-tick paths (the rod bait display lore). */
    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** "1,542" -> 1542; a missing/broken group counts as 1 (the lines omit "x1"). */
    private static int parseCount(String group) {
        if (group == null) {
            return 1;
        }
        try {
            return Integer.parseInt(group.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Whether this catch belongs to a double hook, clearing the flag either way.
     *
     * <p>The window is short because the double hook line lands immediately before its catch: a
     * stale flag would otherwise double an unrelated catch minutes later.
     */
    private boolean consumeDoubleHook() {
        boolean doubled = doubleHookAt > 0
                && System.currentTimeMillis() - doubleHookAt < DOUBLE_HOOK_WINDOW_MS;
        doubleHookAt = 0;
        return doubled;
    }

    /** Called by {@link BiteIndicator} on each new bite - the client's one real "you fished" event. */
    public void onBite() {
        if (!enabled()) {
            return;
        }
        timesFished++;
        lastActivityAt = System.currentTimeMillis();
        lastLootEventAt = lastActivityAt;
        // Bait cost is NOT booked here: bait-save effects (the Caster enchant, Stingy Sinker, the
        // Spiked Atrocity talisman, shard buffs) keep the bait on many casts, so "one per bite"
        // would overstate the cost. The inventory diff in onTick books exactly the baits that
        // actually left the stack - a saved bait never shrinks it and so never costs anything.
    }

    /** How many bites were reeled this session ("Times Fished"). */
    public int timesFished() {
        return timesFished;
    }

    /**
     * Total baits still available for the "Baits Left" HUD: loose baits carried in the inventory plus
     * the last-seen Fishing Bag reserve ({@link #captureBaitSack}), minus the casts made since that
     * look ({@link #onCast} – one estimated bait each). So the count ticks DOWN live while fishing
     * and snaps back to the bag's real numbers on every bag open.
     */
    public int remainingBaits() {
        int total = 0;
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && FishingData.isBait(SkyblockItem.id(stack))) {
                    total += stack.getCount();
                }
            }
        }
        for (Map.Entry<String, Integer> entry : baitSackReserve.entrySet()) {
            total += Math.max(0,
                    entry.getValue() - pendingBaitEstimate.getOrDefault(entry.getKey(), 0));
        }
        return total;
    }

    /**
     * The bait the player is currently fishing with: the one most recently consumed, or – before the
     * first cast this session – whatever bait is visible loose in the inventory, then in the sack.
     * Drives the bait-counter HUD icon; null when no bait can be found at all.
     */
    public String currentBaitId() {
        if (baitDisplayActive && displayBaitId != null) {
            return displayBaitId;   // the rod display names exactly the bait now loaded
        }
        if (lastConsumedBaitId != null) {
            return lastConsumedBaitId;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    String id = SkyblockItem.id(stack);
                    if (FishingData.isBait(id)) {
                        return id;
                    }
                }
            }
        }
        for (String id : baitSackReserve.keySet()) {
            return id;
        }
        return null;
    }

    /**
     * How many of one specific bait remain: the last-seen Fishing Bag count minus the casts made
     * since that look (live countdown), plus any loose stack in the inventory.
     */
    public int remainingOf(String baitId) {
        if (baitId == null) {
            return 0;
        }
        // Hypixel's own readout wins when the rod display shows this exact bait – it already sums the
        // loose inventory and the Fishing Bag, so no estimate can beat it.
        if (baitDisplayActive && baitId.equals(displayBaitId) && displayBaitsRemaining >= 0) {
            return displayBaitsRemaining;
        }
        int bag = Math.max(0, baitSackReserve.getOrDefault(baitId, 0)
                - pendingBaitEstimate.getOrDefault(baitId, 0));
        return bag + inventoryCountOf(baitId);
    }

    /** Loose count of one item id in the player inventory. */
    private static int inventoryCountOf(String itemId) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
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
     * The bobber just deployed – one cast. Baits fed from the Fishing Bag are consumed server-side
     * with no inventory change the diff could ever see, so the cast books ONE of the current bait
     * on credit: the HUD chip counts down and the session cost grows immediately, cast by cast.
     * The next look into the bag corrects the guess ({@link #reconcileBaitConsumption}) – bait-save
     * effects make the estimate run slightly hot, and the reconcile hands that back. Baits carried
     * loose stay out of this: their stack shrink is booked exactly by the inventory diff in onTick.
     */
    private void onCast() {
        if (baitDisplaySeen) {
            return;   // the rod bait display books consumption authoritatively – no estimate needed
        }
        String bait = currentBaitId();
        if (bait == null || inventoryCountOf(bait) > 0) {
            return;   // no bait at all, or a loose stack (consumed first) the inventory diff books
        }
        if (bagRemainingOf(bait) <= 0) {
            // The remembered bait ran out (or left the bag) - fall over to whatever bait the bag
            // still holds, so the countdown follows the bait actually being consumed now.
            bait = null;
            for (String id : baitSackReserve.keySet()) {
                if (bagRemainingOf(id) > 0 && inventoryCountOf(id) == 0) {
                    bait = id;
                    break;
                }
            }
            if (bait == null) {
                return;   // the bag (as last seen) is out of baits entirely
            }
        }
        pendingBaitEstimate.merge(bait, 1, Integer::sum);
        baitsUsed.merge(bait, 1, Integer::sum);
        lastConsumedBaitId = bait;
    }

    /** What the bag (as last seen) still holds of one bait, minus the casts made since that look. */
    private int bagRemainingOf(String baitId) {
        return baitSackReserve.getOrDefault(baitId, 0) - pendingBaitEstimate.getOrDefault(baitId, 0);
    }

    /**
     * Reads Hypixel's rod bait display: while a fishing rod is held, the loaded bait sits in hotbar
     * slot 9 with a "Baits Remaining: N" lore line that is the AUTHORITY on how many of that bait are
     * left (it already sums the loose inventory and the Fishing Bag). Scanned every tick; a decrease
     * of that number between ticks is booked as consumption of the shown bait – far more reliable than
     * the old inventory-diff / bag-reconcile estimate, which only ever saw <i>loose</i> baits shrink
     * and had to guess at bag-fed ones.
     *
     * <p>Once any display is seen this session ({@link #baitDisplaySeen}) it becomes the sole
     * consumption source and the estimate paths stand down, so nothing is double-booked. Bait can only
     * be consumed while the rod is held – exactly when the display is up – so its brief absence (rod
     * put away) loses no real consumption; the delta baseline just restarts on the next appearance.
     */
    private void readBaitDisplay(Player player) {
        baitDisplayActive = false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {   // hotbar only; the display sits in slot 9 (index 8)
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Integer remaining = parseBaitsRemaining(stack);
            if (remaining == null) {
                continue;
            }
            String baitId = SkyblockItem.id(stack);
            if (!FishingData.isBait(baitId)) {
                // A display item may not carry the bait's NBT id – resolve it from the shown name.
                String byName = resolveItemId(stack.getHoverName().getString());
                if (FishingData.isBait(byName)) {
                    baitId = byName;
                }
            }
            displayBaitsRemaining = remaining;
            if (FishingData.isBait(baitId)) {
                displayBaitId = baitId;   // keep the last known id if the display item is unrecognised
            }
            baitDisplayActive = true;
            baitDisplaySeen = true;
            break;
        }
        if (!baitDisplayActive) {
            // No display this tick (rod not held): break delta continuity so a later re-appearance
            // re-baselines instead of booking the whole gap as one phantom drop.
            lastDisplayRemaining = -1;
            lastDisplayBaitId = null;
            return;
        }
        // The between-tick drop of the SAME bait is the real consumption: small and positive while
        // fishing. A rise is a refill and a bait-type switch changes the id – both only re-baseline.
        if (lastDisplayRemaining >= 0 && displayBaitId != null
                && displayBaitId.equals(lastDisplayBaitId)) {
            int used = lastDisplayRemaining - displayBaitsRemaining;
            if (used > 0 && used <= MAX_BAIT_CONSUMED_PER_TICK && recentFishingActivity()) {
                baitsUsed.merge(displayBaitId, used, Integer::sum);
                lastConsumedBaitId = displayBaitId;
            }
        }
        lastDisplayRemaining = displayBaitsRemaining;
        lastDisplayBaitId = displayBaitId;

        long now = System.currentTimeMillis();
        if (now - lastBaitDisplayLogAt > 5_000L) {
            lastBaitDisplayLogAt = now;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Fishing] bait display: {} x{} (authoritative)",
                    displayBaitId, displayBaitsRemaining);
        }
    }

    /** The "Baits Remaining: N" number on an item's lore, or null when the line is absent. */
    private static Integer parseBaitsRemaining(ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (var line : lore.lines()) {
            // strip() is the allocation-light colour-code remover; String.replaceAll would
            // recompile its pattern per lore line per tick.
            Matcher matcher = BAITS_REMAINING.matcher(strip(line.getString()));
            if (matcher.find()) {
                return parseCount(matcher.group(1));
            }
        }
        return null;
    }

    /**
     * Refreshes the {@link #baitSackReserve} from the open <b>Fishing Bag</b> ("A useful bag which can
     * hold all types of fish, bait, and fishing loot", opened via {@code /fishingbag}). The bag holds
     * fish and loot too, so only the bait stacks are kept. Baits stored here never pass through the
     * inventory, so without this "Baits Left" would only ever show the loose baits in hand. A no-op
     * for every other screen; the bottom 36 menu slots are the player inventory and are skipped so
     * held baits are not counted twice.
     *
     * <p>The bag is read EVERY tick it is open, but what is read is only believed once it has
     * SETTLED: the server streams the content in after the screen opens (the open tick shows all
     * air, Hypixel fills menus progressively), so the first look waits until the content is
     * non-empty and has stopped changing for {@link #BAG_SETTLE_TICKS} ticks. That settled first
     * look is the reconcile moment – a closed bag cannot be withdrawn from, so whatever each bait
     * dropped by against the remembered baseline is the closed period's TRUE consumption
     * ({@link #reconcileBaitConsumption}). From then on the open bag is mirrored live, tick by
     * tick, so depositing or withdrawing bait moves the HUD the moment it happens. Reconciling on
     * the open edge instead (the old behaviour) compared the baseline against the still-empty
     * slots and booked the entire reserve as phantom consumption.
     */
    private void captureBaitSack(net.minecraft.client.gui.screens.Screen screen) {
        boolean bagOpen = false;
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container) {
            String title = container.getTitle() == null ? "" : container.getTitle().getString();
            String lower = title.replaceAll("§.", "").toLowerCase(Locale.ROOT);
            // Deliberately ONLY the Fishing Bag title: a looser match (any title containing
            // "bait") also caught Bazaar / AH product pages named after a bait, whose display
            // stacks then clobbered the reserve with garbage counts.
            if (lower.contains("fishing bag")) {
                bagOpen = true;
                if (!baitBagWasOpen) {
                    baitBagReconciled = false;   // fresh opening: a reconcile is due once settled
                    bagStableTicks = 0;
                    lastBagContent = null;
                }
                net.minecraft.world.inventory.AbstractContainerMenu menu = container.getMenu();
                int containerSlots = Math.max(0, menu.getItems().size() - 36);
                Map<String, Integer> content = new LinkedHashMap<>();
                Map<String, Integer> baits = new LinkedHashMap<>();
                for (int i = 0; i < containerSlots; i++) {
                    ItemStack stack = menu.getSlot(i).getItem();
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    String id = SkyblockItem.id(stack);
                    // The settle fingerprint spans EVERY item (fish, loot, UI arrows) - a bag whose
                    // baits stream in last must not read as "settled and bait-free" early.
                    content.merge(id != null ? id : stack.getHoverName().getString(),
                            stack.getCount(), Integer::sum);
                    if (FishingData.isBait(id)) {
                        baits.merge(id, stack.getCount(), Integer::sum);
                    }
                }
                if (baitBagReconciled) {
                    // Settled earlier this opening: mirror the bag live.
                    baitSackReserve.clear();
                    baitSackReserve.putAll(baits);
                } else {
                    bagStableTicks = !content.isEmpty() && content.equals(lastBagContent)
                            ? bagStableTicks + 1 : 0;
                    lastBagContent = content;
                    if (bagStableTicks >= BAG_SETTLE_TICKS) {
                        reconcileBaitConsumption(baits);
                        baitSackReserve.clear();
                        baitSackReserve.putAll(baits);
                        baitBagReconciled = true;
                    }
                }
            }
        }
        baitBagWasOpen = bagOpen;
    }

    /**
     * First SETTLED look into the Fishing Bag after a closed period ({@link #captureBaitSack}
     * decides when that is): each bait's drop against the remembered baseline is the closed
     * period's true consumption. The per-cast estimates ({@link #onCast})
     * already booked their guess into {@link #baitsUsed}, so only the difference is applied –
     * upward when more was consumed than casts were seen, downward when bait-save effects (Caster,
     * Stingy Sinker, Spiked Atrocity) made the estimate run hot. A count that GREW is a refill and
     * only re-baselines. The estimate ledger starts fresh either way, against the new baseline.
     */
    private void reconcileBaitConsumption(Map<String, Integer> found) {
        if (skipNextBaitReconcile) {
            skipNextBaitReconcile = false;
            pendingBaitEstimate.clear();
            return;   // a reset happened: the old session's consumption must not book into this one
        }
        if (baitDisplaySeen) {
            pendingBaitEstimate.clear();
            return;   // the rod bait display already books consumption – the bag delta would double it
        }
        int biggestConsumed = 0;
        for (Map.Entry<String, Integer> prev : baitSackReserve.entrySet()) {
            String id = prev.getKey();
            int consumed = Math.max(0, prev.getValue() - found.getOrDefault(id, 0));
            int estimated = pendingBaitEstimate.getOrDefault(id, 0);
            if (consumed > biggestConsumed) {
                biggestConsumed = consumed;
                lastConsumedBaitId = id;   // the bait that actually shrank is the one in use
            }
            int diff = consumed - estimated;
            if (diff > 0) {
                baitsUsed.merge(id, diff, Integer::sum);
            } else if (diff < 0) {
                int corrected = Math.max(0, baitsUsed.getOrDefault(id, 0) + diff);
                if (corrected == 0) {
                    baitsUsed.remove(id);
                } else {
                    baitsUsed.put(id, corrected);
                }
            }
        }
        pendingBaitEstimate.clear();
    }

    /** Whether the player fished recently enough for item gains to count as fishing loot. */
    public boolean recentFishingActivity() {
        return System.currentTimeMillis() - lastActivityAt < ACTIVITY_WINDOW_MS;
    }

    /**
     * Whether the session has gone quiet long enough for its panels to get out of the way. A session
     * that collected something keeps that data for as long as the game runs, so "has this session
     * caught anything" is true forever after the first catch – on its own it is a reason to draw the
     * panels, never a reason to stop, which is why they used to sit there all day.
     */
    public boolean sessionIdle() {
        return lastActivityAt == 0 || System.currentTimeMillis() - lastActivityAt > IDLE_HIDE_MS;
    }

    /** Whether a bite/catch just happened – the moments fishing actually hands the player items. */
    private boolean justFished() {
        return System.currentTimeMillis() - lastLootEventAt < LOOT_PICKUP_WINDOW_MS;
    }

    /**
     * Called every client tick: diffs the inventory and books anything that grew.
     *
     * <p>The first pass after joining only records the baseline – otherwise your whole existing
     * inventory would count as one enormous catch.
     */
    public void onTick() {
        if (!enabled()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            baselineReady = false;   // left the world; the next baseline is a fresh one
            lastCounts.clear();
            GoldenFishTracker.getInstance().onNoPlayer();
            return;
        }
        BiteIndicator.getInstance().onTick(player);
        // Read Hypixel's rod bait display (slot 9 "Baits Remaining: N") FIRST: it is the authority on
        // the bait count, and once seen it drives consumption so the estimate paths below stand down.
        readBaitDisplay(player);
        // Snapshot the Bait Sack whenever its menu is open, so "Baits Left" counts the bulk reserve
        // and not just the baits loose in the inventory.
        captureBaitSack(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen());
        // Only the CAST (deploy edge) marks activity, not the bobber merely being out: a bobber
        // left sitting in the water is waiting or afk, and marking it every tick held the session
        // clock's 10s pause off forever - the "timer keeps counting while I'm not fishing" bug.
        // Bites and catches refresh activity on their own, so real fishing never pauses.
        boolean bobber = player.fishing != null;
        if (bobber && !bobberOut) {
            lastActivityAt = System.currentTimeMillis();
            onCast();   // deploy edge: one cast just happened, one bag-fed bait consumed
        }
        GoldenFishTracker.getInstance().onTick(player, bobber, bobberOut);
        bobberOut = bobber;
        Map<String, Integer> current = countInventory(player);
        long now = System.currentTimeMillis();
        accrueActiveTime(now);
        if (!baselineReady) {
            lastCounts.putAll(current);
            baselineReady = true;
            settleUntil = now + JOIN_SETTLE_MS;   // the inventory is still streaming in after the join
            return;
        }
        // Post-join settle: a lobby / island swap re-sends the whole inventory over several ticks;
        // absorb it into the baseline without counting so it never reads as one enormous catch.
        if (now < settleUntil) {
            lastCounts.clear();
            lastCounts.putAll(current);
            return;
        }
        // Gains inside a container screen are the player moving items (storage, bazaar, trades),
        // never a drop - they are absorbed into the baseline below without counting. The guard
        // extends CONTAINER_SETTLE_MS past the close: the server finishes menu-driven moves after
        // the screen is gone, and those late arrivals are still the menu's items, not loot.
        boolean containerOpen = sbs.modid.client.core.api.GuiStateManager.getInstance()
                .getCurrentScreen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        if (containerWasOpen && !containerOpen) {
            containerClosedSettleUntil = now + CONTAINER_SETTLE_MS;
        }
        containerWasOpen = containerOpen;
        boolean menuSettling = containerOpen || now < containerClosedSettleUntil;
        boolean fishingActive = recentFishingActivity();
        boolean justFished = justFished();
        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            int before = lastCounts.getOrDefault(entry.getKey(), 0);
            int gained = entry.getValue() - before;
            if (gained > 0) {
                // A bought item (Bazaar / AH / NPC) lands like a drop - cancel it out entirely.
                gained -= consumeLedger(pendingPurchase, pendingPurchaseAt, entry.getKey(),
                        gained, PURCHASE_TTL_MS, now);
            }
            // Right after a bite or catch, everything gained is fishing's doing: the reeled-in
            // item, a quick kill's drops. Beyond that, only recognised fishing-loot ids count
            // (a slow kill's drop picked up late), and only while the session is still fishing -
            // whatever else lands in the inventory between casts never becomes "profit".
            // bookLoot dedups against the chat / sack channels, so a drop announced there AND
            // picked up here counts once.
            if (gained > 0 && !menuSettling && !FishingData.isNeverLoot(entry.getKey())
                    && (justFished || (fishingActive && FishingData.isFishingLoot(entry.getKey())))) {
                bookLoot(entry.getKey(), gained, Channel.INVENTORY);
            }
        }
        // Inventory REMOVALS feed the transfer ledger: an item that leaves the inventory (auto-
        // absorbed into a sack on pickup, or stashed via the sack GUI) and then shows up as a
        // "[Sacks] +N" line is the SAME item moving, not new loot - onSackMessage deducts against
        // this ledger before booking. Recording every loss is safe: the ledger is only ever
        // consumed by sack "+" lines of the same id, within its short TTL.
        for (Map.Entry<String, Integer> entry : lastCounts.entrySet()) {
            int lost = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
            if (lost > 0) {
                recentRemoved.merge(entry.getKey(), lost, Integer::sum);
                recentRemovedAt.put(entry.getKey(), now);
            }
        }
        // Bait consumption: a bait stack that SHRANK while fishing is the session's cost side.
        // Diffing the stack (instead of booking one bait per bite) is what makes bait-save effects
        // free: a cast whose bait was saved by Caster / Stingy Sinker / Spiked Atrocity produces no
        // shrink and therefore no cost. Container moves are reorganising, not consumption, and a
        // large drop is the stack being stashed or thrown, so both stay unbooked.
        // Skipped once the rod bait display is the source ({@link #baitDisplaySeen}): the display's
        // count already includes loose baits, so booking their shrink here too would double the cost.
        if (fishingActive && !menuSettling && !baitDisplaySeen) {
            for (Map.Entry<String, Integer> entry : lastCounts.entrySet()) {
                if (!FishingData.isBait(entry.getKey())) {
                    continue;
                }
                int lost = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
                if (lost > 0 && lost <= MAX_BAIT_CONSUMED_PER_TICK) {
                    baitsUsed.merge(entry.getKey(), lost, Integer::sum);
                    lastConsumedBaitId = entry.getKey();   // the bait actually in use right now
                }
            }
        }
        // Replace wholesale: an item that left the inventory must lower the baseline too, or
        // picking it back up would count a second time.
        lastCounts.clear();
        lastCounts.putAll(current);
    }

    /** Every SkyBlock item currently in the player's inventory, id -> total count. */
    private Map<String, Integer> countInventory(Player player) {
        Map<String, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.id(stack);
            if (id != null) {
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // Read-out
    // ------------------------------------------------------------------

    public Map<String, Integer> creatures() {
        return creatures;
    }

    public Map<String, Integer> loot() {
        return loot;
    }

    /** The baits this session consumed, id -> count. */
    public Map<String, Integer> baitsUsed() {
        return baitsUsed;
    }

    /** The shards this session actually produced, in the order they were first caught. */
    public Map<String, Integer> shards() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : loot.entrySet()) {
            if (FishingData.isShard(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Value of one item id: Bazaar first, Auction House second, 0 when neither knows it.
     *
     * <p>Bazaar uses the <b>instasell</b> side: that is what the loot is actually worth to you right
     * now, without waiting for a sell offer to fill. Quoting the higher instabuy price would inflate
     * every session's profit. Items the Bazaar does not trade (armor drops, rare AH-only loot) fall
     * back to the lowest BIN.
     */
    public double price(String itemId) {
        if (COIN_ID.equals(itemId)) {
            return 1;   // treasure coins are already coins
        }
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(itemId);
        if (value != null && value.sell() > 0) {
            return value.sell();
        }
        Long lbin = sbs.modid.client.economy.prices.LbinCache.getInstance().getLbin(itemId);
        return lbin == null ? 0 : lbin;
    }

    /**
     * Cost price of one bait: the current Bazaar <b>instabuy</b> ({@code quick_status.buyPrice} – the
     * cheapest sell offer, i.e. what you actually pay to restock the bait), falling back to instasell
     * then the lowest BIN. Loot uses instasell ({@link #price}) because that is what you get selling
     * it; a consumed bait is the opposite side of the book, so it is valued at what it cost to buy.
     */
    public double baitPrice(String baitId) {
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(baitId);
        if (value != null) {
            if (value.buy() > 0) {
                return value.buy();
            }
            if (value.sell() > 0) {
                return value.sell();
            }
        }
        Long lbin = sbs.modid.client.economy.prices.LbinCache.getInstance().getLbin(baitId);
        return lbin == null ? 0 : lbin;
    }

    /** What one id's stack is worth this session. */
    public double valueOf(String itemId) {
        return price(itemId) * loot.getOrDefault(itemId, 0);
    }

    /** What the consumed baits cost this session, at the current Bazaar buy price – the cost side. */
    public double baitCost() {
        double total = 0;
        for (Map.Entry<String, Integer> entry : baitsUsed.entrySet()) {
            total += baitPrice(entry.getKey()) * entry.getValue();
        }
        return total;
    }

    /** Every tracked item's value added up, minus the consumed baits – the session profit. */
    public double totalProfit() {
        double total = 0;
        for (Map.Entry<String, Integer> entry : loot.entrySet()) {
            total += price(entry.getKey()) * entry.getValue();
        }
        return total - baitCost();
    }

    /**
     * Advances the session clock by the time since the last tick, but only while the player is
     * demonstrably fishing: once {@link #PAUSE_AFTER_MS} pass with no cast / bite / catch the clock
     * freezes, so "Time" and "Per Hour" reflect time fished, not time logged in. The per-tick delta
     * is capped at the same window, so a lag spike or a briefly unfocused client cannot add idle time.
     */
    private void accrueActiveTime(long now) {
        if (lastAccrualAt != 0) {
            long delta = now - lastAccrualAt;
            if (delta > 0 && delta <= PAUSE_AFTER_MS && now - lastActivityAt < PAUSE_AFTER_MS) {
                activeMillis += delta;
            }
        }
        lastAccrualAt = now;
    }

    /** Session length in milliseconds, counting only the time the player was actually fishing. */
    public long sessionMillis() {
        return activeMillis;
    }

    /** Profit extrapolated to an hour; 0 until the session is long enough to mean anything. */
    public double profitPerHour() {
        long millis = sessionMillis();
        if (millis < 5_000) {
            return 0;   // the first seconds would extrapolate one drop into billions
        }
        return totalProfit() / (millis / 3_600_000.0);
    }

    public int totalCreatures() {
        return creatures.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Clears the session (counts, value and the clock). */
    public void reset() {
        creatures.clear();
        doubleHookAt = 0;
        loot.clear();
        baitsUsed.clear();
        for (Channel channel : Channel.values()) {
            recentByChannel.get(channel).clear();
            recentAtByChannel.get(channel).clear();
        }
        pendingPurchase.clear();
        pendingPurchaseAt.clear();
        recentRemoved.clear();
        recentRemovedAt.clear();
        lastCounts.clear();
        baselineReady = false;
        timesFished = 0;
        activeMillis = 0;
        lastAccrualAt = 0;
        // The bag baseline (last-seen counts) survives - it is a real-world snapshot, not session
        // data - but the cast estimates belong to the cleared session, and the next bag look must
        // only re-baseline instead of booking the old session's consumption into the fresh one.
        pendingBaitEstimate.clear();
        skipNextBaitReconcile = true;
        // The rod display baseline belongs to the old session too: drop it so the drop across the
        // reset is never booked, and re-detect the display fresh on the next tick.
        baitDisplaySeen = false;
        baitDisplayActive = false;
        displayBaitsRemaining = -1;
        displayBaitId = null;
        lastDisplayRemaining = -1;
        lastDisplayBaitId = null;
    }
}
