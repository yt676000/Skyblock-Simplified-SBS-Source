/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the critters that hide from you inside the Critter Safari and hands the renderer a list to
 * box.
 *
 * <p><b>Written for a family, not for one critter.</b> Every "hide-on-something" critter works the
 * same way - it is somewhere nearby and the point of the content is that you cannot see it - so the
 * watch list is a set of names from the config rather than a constant. The planned Hideyho finder is
 * the same problem with a chat state machine on top; it adds a name here rather than a second scan
 * loop and a second renderer.
 *
 * <p><b>The name is the only discriminator, and it is deliberately not a literal.</b> Nametags are
 * put through {@link MobHighlightTracker#mobNameInNametag}, which already strips the colour codes,
 * the {@code [Lv1]} tag, the mob-type symbol, the {@code ᛤ} shard marker and the trailing health
 * readout - so the compare is against a plain name, case-insensitively and on word boundaries. It is
 * never a {@code Hide} prefix: that would box every critter in the family at once and could not be
 * narrowed from the config.
 *
 * <p><b>Both nametag carriers are handled.</b> Hypixel names some mobs directly and floats an armor
 * stand over others, and a stand-only sweep has already silently missed animals once in this
 * codebase. So the tag is whatever carries the name, and the body underneath it is resolved through
 * {@link MobHighlightTracker#mobBelow} - which is also what makes an invisible or sunken critter
 * survivable: with no body under the tag, the tag's own position is what gets boxed.
 *
 * <p><b>The sparkling half matches the raw tag, not the extracted name - and that is deliberate.</b>
 * A sparkling critter is any critter carrying the marker, whatever it is called, so it is matched
 * against the nametag with only the colour codes taken out.
 * {@link MobHighlightTracker#mobNameInNametag} strips every character outside
 * {@code [A-Za-z0-9'.,/\- ]} - that is how it removes {@code ᛤ}, stars and hearts - so if Hypixel
 * marks these critters with a glyph rather than a word, the extracted name cannot contain it and a
 * match against that name would fail silently. Two strings, one line apart, on purpose.
 *
 * <p><b>The two halves gate independently.</b> The sweep runs while either is on, and a critter that
 * is both watched and sparkling is reported sparkling - two boxes on one critter reads as two
 * critters, and "it is sparkling" is the fact that changes what the player does.
 *
 * <p><b>Gated on the Safari and on nothing else.</b> {@link SafariTracker#inSafariArea()} is the
 * single source, so the same configurable word covers this feature and the trip summary. An
 * unreadable location counts as outside here - the trip tracker must not end a trip on a mid-warp
 * blink, but a box left hanging over a warp is a stale highlight, which is the failure that matters
 * for something drawn on screen.
 *
 * <p><b>Every critter nametag seen in the Safari is logged</b> as {@code [SBS][Critter]}, throttled.
 * That is not debug scaffolding: which name Hypixel really uses is the one fact that cannot be
 * checked from inside the client, and the log is how it gets pinned in a single trip.
 */
public final class HidingCritterTracker {

    private static final HidingCritterTracker INSTANCE = new HidingCritterTracker();

    /** Ticks between sweeps. {@link PeltTracker}'s number: a critter does not move 20 times a second. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** Hard ceiling on the scan radius, whatever the render distance is set to. */
    private static final double MAX_SCAN_RADIUS = 128.0;

    /** Throttle for the nametag log, so a busy Safari cannot flood the log file. */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** How many distinct names one log line carries. */
    private static final int LOG_SAMPLE = 8;

    /**
     * One found critter: the entity carrying the nametag - always present - and the body under it,
     * which is {@code null} when the critter is invisible, sunk into the ground or simply not
     * resolvable beneath its stand.
     *
     * <p>{@code sparkling} is what the renderer colours by: it is set when the raw nametag carried
     * one of the configured markers, whether or not the critter is also on the watch list.
     */
    public record Sighting(Entity tag, LivingEntity mob, String name, boolean sparkling) {

        /** The box to draw: the body's own when there is one, else a man-sized box at the nametag. */
        public AABB box() {
            if (mob != null && mob.isAlive()) {
                return mob.getBoundingBox();
            }
            Vec3 pos = tag.position();
            return new AABB(pos.x - 0.5, pos.y - 1.5, pos.z - 0.5, pos.x + 0.5, pos.y, pos.z + 0.5);
        }

        /** Whether this sighting is still worth drawing this frame. */
        public boolean alive() {
            return tag.isAlive();
        }
    }

    /** The last sweep's findings; replaced wholesale, never edited in place. */
    private volatile List<Sighting> sightings = List.of();

    /** Entity ids already announced, so the ping fires once per critter and not once per sweep. */
    private final Set<Integer> announced = new HashSet<>();

    private int tickCounter;
    private long lastLogAt;

    private HidingCritterTracker() {
    }

    public static HidingCritterTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.CritterFinderSettings cfg() {
        return ConfigManager.getInstance().get().critterFinder;
    }

    // ---- read by the renderer -------------------------------------------------------------------

    /** The critters found by the last sweep, or an empty list. Never {@code null}. */
    public List<Sighting> sightings() {
        return sightings;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /**
     * Called every client tick; the sweep itself runs on the {@value #SCAN_INTERVAL_TICKS} throttle.
     *
     * <p>The first line is the whole "inert when off" requirement: with <i>both</i> halves off this
     * method returns before touching the level, the entity list or the clock.
     */
    public void onClientTick() {
        if (!cfg().enabled && !cfg().sparkling.enabled) {
            if (!sightings.isEmpty()) {
                clear();   // switched off mid-Safari: drop the boxes with the feature
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || !SafariTracker.inSafariArea()) {
            if (!sightings.isEmpty()) {
                clear();
            }
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        scan(level, player);
    }

    /** Called on a world change or server hop: nothing found on one instance describes the next. */
    public void onWorldChange() {
        clear();
    }

    private void clear() {
        sightings = List.of();
        announced.clear();
    }

    // ---- the sweep ------------------------------------------------------------------------------

    /**
     * One pass over the custom-named entities in range.
     *
     * <p>Rebuilt from scratch every time rather than diffed, which is what makes a dead, removed or
     * departed critter disappear on its own: it is simply not found again. Between sweeps the
     * renderer drops any sighting whose tag has stopped being alive, so the longest a stale box can
     * survive is one frame, not one sweep.
     */
    private void scan(ClientLevel level, LocalPlayer player) {
        // Each half contributes its own words, and only while it is switched on - so a sparkling-only
        // player is not made to fill in a critter name, and vice versa.
        Set<String> wanted = cfg().enabled ? split(cfg().critterNames) : Set.of();
        Set<String> markers = cfg().sparkling.enabled ? split(cfg().sparkling.markers) : Set.of();
        if (wanted.isEmpty() && markers.isEmpty()) {
            clear();   // both fields emptied; nothing can be identified
            return;
        }
        double radius = Math.min(MAX_SCAN_RADIUS, Math.max(1, cfg().renderDistance));
        AABB area = player.getBoundingBox().inflate(radius);

        List<Sighting> found = new ArrayList<>();
        Set<Integer> bodies = new HashSet<>();
        List<String> seen = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, area,
                e -> e.isAlive() && e.hasCustomName())) {
            Component custom = entity.getCustomName();
            if (custom == null) {
                continue;
            }
            // Colour codes out first, everything else left standing: the marker may be a symbol the
            // name extractor is about to remove, so the two matches need different strings.
            String tag = custom.getString().replaceAll("§.", "");
            String name = MobHighlightTracker.mobNameInNametag(tag);
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (seen.size() < LOG_SAMPLE && !seen.contains(name)) {
                seen.add(name);
            }
            boolean sparkling = carriesMarker(markers, tag);
            if (!sparkling && !matches(wanted, name)) {
                continue;
            }
            LivingEntity body = bodyOf(level, entity);
            // A named mob wearing its own tag is found twice when Hypixel ALSO floats a stand over
            // it - once as the stand and once as the mob - and two boxes on one critter read as two
            // critters. The body is what identifies the creature, so the first tag to claim it wins.
            if (body != null && !bodies.add(body.getId())) {
                continue;
            }
            found.add(new Sighting(entity, body, name, sparkling));
            announce(player, entity, name, sparkling);
        }
        sightings = List.copyOf(found);
        logSeen(wanted, markers, seen, found.size());
    }

    /**
     * The living body a nametag belongs to: the mob under it when the tag is a separate stand, else
     * the tagged entity itself. {@code null} when there is nothing to find, which is the ordinary
     * case for a critter that has hidden itself.
     */
    private static LivingEntity bodyOf(ClientLevel level, Entity tag) {
        if (tag instanceof ArmorStand stand) {
            return MobHighlightTracker.mobBelow(level, stand);
        }
        return tag instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    /** A comma-separated setting as a set of lower-cased words; blanks and duplicates dropped. */
    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> words = new HashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        return words;
    }

    /**
     * Whether an extracted name is one of the watched critters.
     *
     * <p>Equality first, then a word-boundary containment so a tag that carries a prefix or a suffix
     * this build has never seen ("Baby Hideonfloor") still matches. Containment is bounded by word
     * edges on purpose: a bare {@code contains} would make a watched "Hideon" match every critter in
     * the family, which is exactly what a per-critter finder must not do.
     */
    private static boolean matches(Set<String> wanted, String name) {
        if (wanted.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (wanted.contains(lower)) {
            return true;
        }
        for (String want : wanted) {
            if (containsWord(lower, want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a raw nametag (colour codes already out) carries one of the sparkling markers.
     *
     * <p>A marker of letters and digits is matched on word boundaries, exactly as a critter name is.
     * A marker carrying anything else - {@code ✦}, {@code ✨}, a bracketed tag - is matched by plain
     * containment instead, because a symbol has no word edge to anchor against and the boundary test
     * would reject every occurrence of it.
     */
    private static boolean carriesMarker(Set<String> markers, String tag) {
        if (markers.isEmpty()) {
            return false;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (wordLike(marker) ? containsWord(lower, marker) : lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Whether every character of a marker is a letter or a digit, so word edges mean something. */
    private static boolean wordLike(String marker) {
        for (int i = 0; i < marker.length(); i++) {
            if (!Character.isLetterOrDigit(marker.charAt(i))) {
                return false;
            }
        }
        return !marker.isEmpty();
    }

    /** {@code needle} inside {@code haystack} on whole-word edges; both already lower-cased. */
    private static boolean containsWord(String haystack, String needle) {
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end == haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            at = haystack.indexOf(needle, at + 1);
        }
        return false;
    }

    /**
     * First sighting of this critter: one chat line and a ping, then silence until it is gone.
     *
     * <p>The two halves ping separately - a player watching a common critter silently may still want
     * to hear a sparkling one arrive - and the sparkling ping is the same sound a fifth higher, which
     * is enough to tell them apart without reaching for a second sound event.
     */
    private void announce(LocalPlayer player, Entity tag, String name, boolean sparkling) {
        if (!announced.add(tag.getId())) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Critter] found {}'{}' at {}",
                sparkling ? "sparkling " : "", name, tag.blockPosition().toShortString());
        if (!(sparkling ? cfg().sparkling.ping : cfg().foundPing)) {
            return;
        }
        player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, sparkling ? 2.0f : 1.6f);
        player.sendSystemMessage(Component.literal(sparkling
                ? "§8[§bSBS§8]§r §b✦ " + name + " §7sparkling"
                : "§8[§bSBS§8]§r §d" + name + " §7found"));
    }

    /**
     * Throttled: the critter names standing around us and how many were matched.
     *
     * <p>This is the line that answers the one question the client cannot. The feature ships watching
     * for a name and a marker taken from the request, and no dataset in this repository contains
     * either - so if the boxes never appear, this log says what the critters here are really called
     * and the fix is a config edit. Written only while the module is on and only inside the Safari.
     *
     * <p>The names listed are the <i>extracted</i> ones, so a symbol marker can never show up among
     * them. That is why the watched markers are printed beside them: "Sparkling" missing from every
     * nearby name is the evidence that the marker is not a word at all.
     */
    private void logSeen(Set<String> wanted, Set<String> markers, List<String> seen, int matched) {
        long now = System.currentTimeMillis();
        if (seen.isEmpty() || now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Critter] watching={} sparkling={} matched={} nearby names={}",
                wanted, markers, matched, seen);
    }
}
