/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.floordrop.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the loot lying on the ground in Galatea, Torrhus Canyon and the Critter Safari, and hands
 * the renderer a list to box.
 *
 * <p><b>The entity is a display, not a dropped item, and that is read out of the game's own
 * sources rather than assumed.</b> {@code net.minecraft.world.entity.Display.ItemDisplay} is what
 * {@code minecraft:item_display} maps to in this version, and it carries {@code getItemStack()} -
 * the item the player can see lying there. A real dropped item is an {@link ItemEntity} and vanilla
 * picks it up by walking over it, which is exactly the behaviour a floor drop does not have.
 *
 * <p><b>Two traps in that class, both of which have already been designed around.</b> An
 * {@code ITEM_DISPLAY} is registered {@code sized(0, 0)}, so its bounding box has <i>no volume</i>
 * and drawing {@code getBoundingBox()} would draw nothing at all - {@link Sighting#box()} synthesizes
 * one around the position instead. And a display entity carries no nametag, so every nametag-driven
 * sweep in this mod is structurally incapable of seeing one; so is {@code /sbs entityprobe arm},
 * which only records named entities. The bare {@code /sbs entityprobe} snapshot does list them.
 *
 * <p><b>Telling a drop from decoration.</b> Hypixel builds scenery out of display entities too, so
 * the entity class alone is not enough. Five filters, cheapest first: the display must actually
 * carry an item; that item must be one of the configured kinds, {@code string} by default, which is
 * what the ground loot in these areas is built on and what the scenery around it is not; it must be
 * near the ground, which separates loot on the floor from decoration mounted on walls and floated
 * inside builds; optionally it must have had a configured particle near it recently; and optionally
 * its name must match a filter. The last two are off and empty by default - a filter nobody verified
 * must not be the reason a highlight is dark, which is also why the item kinds are a config field
 * with the items actually in range logged beside them rather than a constant in this file.
 *
 * <p><b>Rebuilt from scratch every sweep, never diffed.</b> A collected drop is simply not found
 * again, which is what makes pickup and despawn correct with no removal path to get wrong. Between
 * sweeps the renderer drops anything that has been removed, so a collected drop loses its box on the
 * pickup frame rather than at the end of the interval.
 *
 * <p><b>Gated on {@link FloorDropAreas} and nothing else</b>, and an unreadable location counts as
 * outside: a box that outlives the instance it was found in is a stale highlight, and a stale
 * highlight is worse than a missing one.
 *
 * <p><b>What it sees is logged</b> as {@code [SBS][FloorDrop]}, throttled - the display count beside
 * the dropped-item count, the items and names walked past whether or not they were boxed, and the
 * particle types actually arriving. That line is how the three {@code ESTIMATED}s in this feature
 * (the entity class, the item the drops are built on, and the particle type) get settled in one trip
 * instead of one release. The items are listed before the item-kind filter is applied on purpose:
 * a list of only what passed could never tell anybody that the filter itself is wrong.
 */
public final class FloorDropTracker {

    private static final FloorDropTracker INSTANCE = new FloorDropTracker();

    /** Ticks between sweeps. Four a second: loot appears at pickup speed, not at frame speed. */
    private static final int SCAN_INTERVAL_TICKS = 5;

    /** Hard ceiling on the scan radius, whatever the setting says. */
    private static final double MAX_SCAN_RADIUS = 128.0;

    /** How near a configured particle has to be to count as this drop's. */
    private static final double PARTICLE_RADIUS = 1.5;

    /** Throttle for the log line, so a busy area cannot flood the file. */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** How many distinct names one log line carries. */
    private static final int LOG_SAMPLE = 8;

    /**
     * How many item kinds the settings page names before it starts counting them instead.
     *
     * <p>Four rather than the log's eight, and it is a layout number rather than a taste one: a
     * settings label wraps inside its own row and <i>drops</i> the lines that do not fit, so a long
     * list would go quietly missing exactly where the player is reading it to work out why nothing
     * is boxed. Four is a judgement and not a measurement - if the line still wraps away at a large
     * GUI scale, this is the number to lower.
     */
    private static final int PAGE_SAMPLE = 4;

    /**
     * The box drawn around a drop, as offsets from the entity's own position.
     *
     * <p>Synthesized because the entity's box has no volume - see the class note. The numbers are a
     * judgement: roughly a block wide and a block tall, sitting mostly above the anchor, which is
     * where an item display's item renders. They are not measured, and if boxes sit low or high in
     * game this is the constant to change.
     */
    private static final double BOX_HALF_WIDTH = 0.4;
    private static final double BOX_BELOW = 0.4;
    private static final double BOX_ABOVE = 0.6;

    /**
     * One found drop: the entity, and what it turned out to be holding.
     *
     * <p>{@code name} is already colour-stripped, and {@code count} is the stack size so a pile
     * reads as a pile. {@code skyblockId} is kept for the log and for the name filter - it is the
     * stable half of an item's identity, where the display name is the half Hypixel rewords.
     */
    public record Sighting(Entity entity, String name, int count, String skyblockId) {

        /** The box to draw: synthesized, because an item display's own box has no volume. */
        public AABB box() {
            Vec3 pos = entity.position();
            return new AABB(
                    pos.x - BOX_HALF_WIDTH, pos.y - BOX_BELOW, pos.z - BOX_HALF_WIDTH,
                    pos.x + BOX_HALF_WIDTH, pos.y + BOX_ABOVE, pos.z + BOX_HALF_WIDTH);
        }

        /**
         * Whether this drop is still worth drawing this frame.
         *
         * <p>{@code isRemoved} and not {@code isAlive}: a display entity is not a living thing, and
         * the client removes it the moment the server despawns it - which is the frame the player
         * picked the drop up. Re-asking per frame is what stops a box outliving its drop by up to a
         * sweep interval.
         */
        public boolean present() {
            return !entity.isRemoved();
        }
    }

    /** The last sweep's findings; replaced wholesale, never edited in place. */
    private volatile List<Sighting> sightings = List.of();

    /** What the last sweep walked past, for the settings page and the log. */
    private volatile int displaysSeen;
    private volatile int droppedSeen;

    /**
     * The distinct item kinds the last sweep walked past, boxed or not, in the order first seen.
     *
     * <p>Kept for the page rather than only for the log, because the item kind is now the filter
     * that decides whether anything is drawn at all: a default naming the wrong item makes the
     * feature silently dark, and the answer has to be readable in game rather than in a log file.
     */
    private volatile List<String> typesSeen = List.of();

    private int tickCounter;
    private long lastLogAt;

    private FloorDropTracker() {
    }

    public static FloorDropTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FloorDropSettings cfg() {
        return ConfigManager.getInstance().get().floorDrops;
    }

    // ---- read by the renderer and the page -------------------------------------------------------

    /** The drops found by the last sweep, or an empty list. Never {@code null}. */
    public List<Sighting> sightings() {
        return sightings;
    }

    /**
     * One line for the settings page: what the last sweep actually walked past.
     *
     * <p>The display count beside the dropped-item count is the whole "is the entity type right"
     * question in one row - a page that says "0 item displays, 14 dropped items nearby" has answered
     * it without anybody opening a log file.
     */
    public String status() {
        if (!cfg().enabled) {
            return "off";
        }
        if (!FloorDropAreas.live()) {
            return "outside the gated areas - nothing is scanned";
        }
        return sightings.size() + " boxed, " + displaysSeen + " item display(s) and "
                + droppedSeen + " dropped item(s) in range";
    }

    /**
     * The item kinds around the player, boxed or not, for the page's Accuracy block.
     *
     * <p>This is the row that settles the item-kind default without a log file: if it lists
     * {@code string} and nothing is boxed, the filter is not what is wrong, and if it never lists
     * {@code string} at all then the default is.
     */
    public String typesLine() {
        List<String> seen = typesSeen;
        if (seen.isEmpty()) {
            return "nothing in range";
        }
        return sample(seen, PAGE_SAMPLE);
    }

    /**
     * Up to {@code limit} of {@code values}, followed by how many were left out.
     *
     * <p>Both readers of this are places where an unbounded list is a bug rather than noise: a
     * settings label silently drops the lines that do not fit its row, and a log line long enough to
     * wrap is one nobody scans. Saying how many were left out is what keeps the shortened form
     * honest - "and 30 more" is itself the answer when the question is why nothing is being boxed.
     */
    private static String sample(Collection<String> values, int limit) {
        if (values.size() <= limit) {
            return String.join(", ", values);
        }
        List<String> shown = new ArrayList<>(values).subList(0, limit);
        return String.join(", ", shown) + " and " + (values.size() - limit) + " more";
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /**
     * Called every client tick; the sweep runs on the {@value #SCAN_INTERVAL_TICKS} throttle.
     *
     * <p>The first line is the whole "inert when off" requirement: switched off, this returns before
     * touching the level, the entity list or the clock, and drops the particle listener with it.
     */
    public void onClientTick() {
        if (!cfg().enabled) {
            if (!sightings.isEmpty() || FloorDropParticles.LISTENING) {
                stop();
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || !FloorDropAreas.live()) {
            if (!sightings.isEmpty() || FloorDropParticles.LISTENING) {
                stop();
            }
            return;
        }
        // Listening starts here rather than with the toggle: outside the three areas there is
        // nothing to corroborate, and a particle tally from the Hub would say nothing about Galatea.
        FloorDropParticles.LISTENING = true;
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        scan(level, player);
    }

    /** Called on a world change or server hop: nothing found on one instance describes the next. */
    public void onWorldChange() {
        stop();
    }

    /** Drops everything and stops listening for particles. */
    private void stop() {
        FloorDropParticles.LISTENING = false;
        FloorDropParticles.getInstance().clear();
        sightings = List.of();
        displaysSeen = 0;
        droppedSeen = 0;
        typesSeen = List.of();
        tickCounter = 0;
    }

    // ---- the sweep -------------------------------------------------------------------------------

    private void scan(ClientLevel level, LocalPlayer player) {
        double radius = Math.min(MAX_SCAN_RADIUS, Math.max(1, cfg().renderDistance));
        AABB area = player.getBoundingBox().inflate(radius);

        List<Sighting> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        // A set rather than a capped list: the page says how many kinds it is not naming, and
        // that number is only true if every one of them was counted. Bounded by the distinct items
        // standing in a 48 m box, which is tens at worst.
        Set<String> types = new LinkedHashSet<>();

        List<Display.ItemDisplay> displays =
                level.getEntitiesOfClass(Display.ItemDisplay.class, area, e -> !e.isRemoved());
        displaysSeen = displays.size();
        for (Display.ItemDisplay display : displays) {
            consider(level, display, display.getItemStack(), found, names, types);
        }

        // Counted whether or not they are boxed. If the displays turn out to be the wrong guess,
        // this number beside the one above is what says so - see the class note.
        List<ItemEntity> dropped =
                level.getEntitiesOfClass(ItemEntity.class, area, e -> !e.isRemoved());
        droppedSeen = dropped.size();
        if (cfg().includeDroppedItems) {
            for (ItemEntity item : dropped) {
                consider(level, item, item.getItem(), found, names, types);
            }
        }

        sightings = List.copyOf(found);
        typesSeen = List.copyOf(types);
        logSeen(names, types, found.size());
    }

    /** One candidate through every filter; adds a {@link Sighting} when it survives all of them. */
    private void consider(ClientLevel level, Entity entity, ItemStack stack,
                          List<Sighting> found, List<String> names, Set<String> types) {
        // 1. It must carry an item. A display with an empty stack shows nothing and is an anchor.
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String name = PlainText.strip(stack.getHoverName().getString()).trim();
        if (names.size() < LOG_SAMPLE && !name.isEmpty() && !names.contains(name)) {
            names.add(name);
        }
        Identifier itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = itemKey.getPath();
        types.add(path);
        // 2. It must be one of the configured item kinds - "string" by default, which is what the
        //    ground loot here is built on and what the scenery around it is not. Sampled just above
        //    first, so the log and the page report what is really standing there whether or not this
        //    filter passed it - a list of only what survived could never say the filter is wrong.
        if (!matchesType(cfg().itemTypes, itemKey.toString(), path)) {
            return;
        }
        // 3. It must be near the ground: loot lies on the floor, props are mounted, hung and
        //    floated. After the comparison above because it reads blocks and that one does not.
        if (!nearGround(level, entity, cfg().groundWithin)) {
            return;
        }
        // 4. Optionally, a configured particle must have arrived beside it recently. Off by
        //    default - the link is spatial and the particle type is unverified, so requiring it
        //    would risk a highlight that is silently dark.
        if (cfg().requireParticles && !FloorDropParticles.getInstance()
                .near(entity.getX(), entity.getY(), entity.getZ(), PARTICLE_RADIUS)) {
            return;
        }
        String id = SkyblockItem.id(stack);
        // 5. Optionally, the name or the id must match the player's filter. Empty means every drop.
        if (!matchesFilter(cfg().itemFilter, name, id)) {
            return;
        }
        found.add(new Sighting(entity, name.isEmpty() ? "Floor Drop" : name,
                stack.getCount(), id == null ? "" : id));
    }

    /**
     * Whether there is a solid block within {@code within} blocks below the entity.
     *
     * <p>{@code within <= 0} switches the test off, which is the answer for an area whose drops turn
     * out to hover. Collision shape rather than {@code isAir}: grass, flowers and the region's
     * ground clutter are not air and would let a decoration display two blocks up read as grounded.
     */
    private static boolean nearGround(ClientLevel level, Entity entity, int within) {
        if (within <= 0) {
            return true;
        }
        BlockPos from = entity.blockPosition();
        for (int down = 0; down <= within; down++) {
            BlockPos at = from.below(down);
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an item is one of the kinds the player counts as a floor drop.
     *
     * <p>Exact where {@link #matchesFilter} is containment, and the difference is the point: a
     * registry name is an identity the player copies out of the log rather than a phrase they type
     * part of, and containment on one would let {@code string} match every future item whose name
     * ends in it. Both spellings answer - {@code string} against the path, {@code minecraft:string}
     * against the whole id - so a value read off any of this mod's probes pastes in as it stands.
     *
     * <p>An empty field passes everything, which is the way out if the ground loot in some corner of
     * the region turns out to be built on an item nobody has listed yet.
     *
     * @param raw  the configured list, comma-separated
     * @param id   the item's full registry id, {@code minecraft:string}
     * @param path the same id without its namespace, {@code string}
     */
    public static boolean matchesType(String raw, String id, String path) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String lowerId = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        for (String part : raw.split(",")) {
            String want = part.trim().toLowerCase(Locale.ROOT);
            if (!want.isEmpty() && (want.equals(lowerPath) || want.equals(lowerId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a drop passes the player's name filter.
     *
     * <p>Plain containment against both the display name and the SkyBlock id, case-insensitively -
     * an empty filter passes everything, which is the shipped default. Containment rather than the
     * word-boundary match the critter finder uses, because an item name is a phrase the player will
     * type part of ("essence", "lucky") rather than a creature's name they know exactly.
     */
    private static boolean matchesFilter(String raw, String name, String id) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerId = id == null ? "" : id.toLowerCase(Locale.ROOT);
        for (String part : raw.split(",")) {
            String want = part.trim().toLowerCase(Locale.ROOT);
            if (!want.isEmpty() && (lowerName.contains(want) || lowerId.contains(want))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Throttled: what is standing around us, and what the particles really are.
     *
     * <p>Two unverified facts sit under this feature - that a floor drop is an item display, and
     * that its particles are the configured type - and neither can be checked from inside the
     * client. This line reports both as they actually are, at {@code info} rather than {@code debug},
     * because {@code debug} is off in every instance anybody plays on and a feature whose only
     * diagnostic is invisible has already shipped dark once in this repository.
     */
    private void logSeen(List<String> names, Set<String> types, int matched) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][FloorDrop] displays={} dropped={} boxed={} items=[{}] names={} particles={}",
                displaysSeen, droppedSeen, matched, sample(types, LOG_SAMPLE), names,
                FloorDropParticles.getInstance().tallyLine());
    }
}
