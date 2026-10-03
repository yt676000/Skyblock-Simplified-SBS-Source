/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.mobhighlight.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.combat.mobhighlight.render.MobHighlightRenderer;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the SkyBlock mobs to highlight once per client tick and hands the list to
 * {@link MobHighlightRenderer} to draw every frame. Collecting on the tick (not in render) mirrors the
 * dungeon starred-mob boxes: entity iteration happens twenty times a second at most, while the boxes
 * still follow the mobs smoothly because the renderer samples each entity's live bounding box.
 *
 * <p><b>How a SkyBlock mob is recognised.</b> SkyBlock mobs are vanilla entities wearing a floating
 * armor-stand nametag ("§8[§7Lv5§8] §cLapis Zombie §a30/30§c❤"). So the primary pass walks the
 * nametag stands, reads the mob name out of the tag ({@link #mobNameInNametag}), and – if that name
 * is one the player ticked – boxes the living mob directly beneath the stand (the same stand→mob
 * association the dungeon and sea-creature detectors use). The tag is recognised by carrying a health
 * readout (a digit or a heart glyph) rather than one exact heart character, so a client whose heart
 * symbol differs still detects every mob.
 *
 * <p><b>Farm animals.</b> Cows, pigs, sheep and the like carry no health nametag, so a second pass
 * matches non-hostile entities by their own type name. It is deliberately limited to non-{@code
 * MONSTER} categories, so hostile mobs are only ever matched by their exact nametag name and a broad
 * "Zombie" pick can never light up every unrelated zombie in the world.
 *
 * <p><b>Not a highlight.</b> A mob is only ever boxed while the player has an unobstructed line of sight
 * to it – anything behind a wall is skipped, so this only ever highlights mobs you can actually see.
 */
public final class MobHighlightTracker {

    private static final MobHighlightTracker INSTANCE = new MobHighlightTracker();

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** Throttle for the diagnostic heartbeat below. */
    private long lastDebugAt;

    /**
     * How long a mob keeps being highlighted after its nametag was last read. Hypixel's nametag
     * stand flickers / isn't always parseable every tick, which made the box blink or wait; once a
     * mob is identified it stays boxed for this long even when the tag momentarily isn't detected.
     */
    private static final long PERSIST_MS = 4_000L;

    /** Entity id -> the name it was identified as, and when it was last confirmed. */
    private final java.util.Map<Integer, long[]> lastSeenAt = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> identifiedName = new java.util.HashMap<>();

    private MobHighlightTracker() {
    }

    public static MobHighlightTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.MobHighlightSettings cfg() {
        return ConfigManager.getInstance().get().mobHighlight;
    }

    /** Called every client tick; refreshes the highlighted-target cache the highlight renders. */
    public void onClientTick() {
        SBSConfig.MobHighlightSettings settings = cfg();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!settings.enabled || settings.selectedMobs.isEmpty() || level == null || player == null) {
            MobHighlightRenderer.getInstance().setTargets(List.of());
            return;
        }
        // Lower-cased selection for O(1), case-insensitive matching against extracted names.
        Set<String> wanted = new HashSet<>();
        for (String name : settings.selectedMobs) {
            wanted.add(name.toLowerCase(Locale.ROOT));
        }

        List<MobHighlightRenderer.Target> targets = new ArrayList<>();
        Set<Integer> boxed = new HashSet<>();

        // Pass 1: SkyBlock mobs by their nametag stand (only those in clear line of sight). The
        // nametag name must EXACTLY match one the player ticked - that match is the whole filter, so
        // no health readout is required. Many custom Hypixel mobs (bosses, mini-bosses, event mobs)
        // carry a plain name with no "30/30❤", which the old health-tag gate silently rejected.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) {
                continue;
            }
            var custom = stand.getCustomName();
            String raw = custom == null ? null : custom.getString();
            if (raw == null) {
                continue;
            }
            String name = mobNameInNametag(raw);
            if (name == null || !matchesWanted(wanted, name)) {
                continue;
            }
            LivingEntity mob = mobBelow(level, stand);
            if (mob != null && mob != player && !boxed.contains(mob.getId())
                    && player.hasLineOfSight(mob) && boxed.add(mob.getId())) {
                targets.add(new MobHighlightRenderer.Target(mob, name));
            }
        }

        // Pass 1b: mobs carrying their SkyBlock name as their OWN custom name. On the modern
        // protocol Hypixel does not always ship a separate nametag stand - the name can sit right
        // on the mob entity - and a pass that only walks armor stands never sees those at all
        // (which showed exactly as "only vanilla animals highlight"). Same extraction, same match;
        // the entity IS the mob, no stand association needed. ArmorStands are excluded - they are
        // LivingEntities too and pass 1 already handled them.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living instanceof ArmorStand
                    || living == player || !living.isAlive() || boxed.contains(living.getId())
                    || !living.hasCustomName()) {
                continue;
            }
            var custom = living.getCustomName();
            String name = custom == null ? null : mobNameInNametag(custom.getString());
            if (name == null || !matchesWanted(wanted, name)) {
                continue;
            }
            if (player.hasLineOfSight(living) && boxed.add(living.getId())) {
                targets.add(new MobHighlightRenderer.Target(living, name));
            }
        }

        // Pass 2: non-hostile mobs (farm animals) by their own type name, again line-of-sight only.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()
                    || boxed.contains(living.getId())
                    || living.getType().getCategory() == MobCategory.MONSTER) {
                continue;
            }
            String typeName = living.getType().getDescription().getString();
            if (!wanted.contains(typeName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (player.hasLineOfSight(living) && boxed.add(living.getId())) {
                targets.add(new MobHighlightRenderer.Target(living, typeName));
            }
        }

        // Persistence: remember every mob identified this tick, then re-add recently-identified ones
        // whose nametag was not parsed this tick - so the highlight sticks instead of blinking with
        // the (flickery) nametag. Still line-of-sight gated, still only mobs seen at least once.
        long now = System.currentTimeMillis();
        for (MobHighlightRenderer.Target target : targets) {
            int id = target.entity().getId();
            lastSeenAt.put(id, new long[]{now});
            identifiedName.put(id, target.label());
        }
        var iterator = lastSeenAt.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int id = entry.getKey();
            if (now - entry.getValue()[0] > PERSIST_MS) {
                iterator.remove();
                identifiedName.remove(id);
                continue;
            }
            if (boxed.contains(id)) {
                continue;   // already boxed fresh this tick
            }
            Entity ent = level.getEntity(id);
            if (!(ent instanceof LivingEntity living) || !living.isAlive()) {
                iterator.remove();
                identifiedName.remove(id);
                continue;
            }
            if (living != player && player.hasLineOfSight(living) && boxed.add(id)) {
                targets.add(new MobHighlightRenderer.Target(living, identifiedName.getOrDefault(id, "")));
            }
        }

        MobHighlightRenderer.getInstance().setTargets(targets);
        diagnostic(level, wanted, targets.size());
    }

    /**
     * Throttled heartbeat (~every 5s while mobs are ticked): what the detector currently sees.
     * The mob encoding on Hypixel's side has shifted repeatedly (type symbols, the ᛤ marker, names
     * on the entity instead of a stand) - when a wanted mob is not highlighted in-game, this line
     * in the instance log shows the raw nametags and what was extracted from them, so the next fix
     * is aimed rather than guessed.
     */
    private void diagnostic(ClientLevel level, Set<String> wanted, int matched) {
        long now = System.currentTimeMillis();
        if (now - lastDebugAt < 5_000L) {
            return;
        }
        lastDebugAt = now;
        int stands = 0;
        int namedMobs = 0;
        List<String> sample = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.hasCustomName()) {
                continue;
            }
            if (living instanceof ArmorStand) {
                stands++;
            } else {
                namedMobs++;
            }
            if (sample.size() < 4) {
                var custom = living.getCustomName();
                String raw = custom == null ? "" : custom.getString();
                sample.add((living instanceof ArmorStand ? "stand" : "mob") + " \"" + raw
                        + "\" -> \"" + mobNameInNametag(raw) + "\"");
            }
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][MobHL] stands={} namedMobs={} matched={} wanted={} sample={}",
                stands, namedMobs, matched, wanted, sample);
    }

    /**
     * The SkyBlock mob name inside a nametag string, or {@code null} when none is left after the
     * decoration is stripped. Removes the colour codes, the bracketed level tag ("[Lv120]"), every
     * decoration glyph and the trailing health readout ("30/30❤", "1.2M♥", "████"), leaving just
     * the name.
     *
     * <p>The glyph strip is a <b>whitelist</b>, not a symbol list: since the July 2025 "Mob Types"
     * update every mob carries a type symbol before its name ("[Lv5] ⚔ Lapis Zombie"), and the
     * Hunting update appends a shard marker after it ("Lapis Zombie ᛤ 30/30❤"). ᛤ in particular is
     * a Unicode <i>letter</i>, so a "strip trailing symbols" rule missed it and the extracted name
     * ("⚔ Lapis Zombie ᛤ") matched nothing – which silently killed the highlight for every SkyBlock
     * mob. Real mob names are plain ASCII ("Lapis Zombie", "Barbarian Duke X", "Millennia-Aged
     * Blaze"), so everything outside ASCII letters/digits and name punctuation is decoration – that
     * also survives whatever symbol Hypixel adds next. Digits and ".,/" stay through the strip so
     * the health readout keeps its shape for the pattern that then cuts it off the tail.
     */
    /**
     * Variant words Hypixel prefixes onto a mob's real name ("Corrupted Lapis Zombie",
     * "Runic Enderman"). A ticked "Lapis Zombie" should light those variants up too – they ARE the
     * same mob, just buffed – so the match strips these prefixes (repeatedly, "Corrupted Runic X"
     * included) before the exact-name compare. Only known variant words are stripped: dropping
     * arbitrary leading words would make "Zombie Villager" match a ticked "Villager".
     */
    private static final String[] VARIANT_PREFIXES = {"corrupted ", "runic "};

    /** Whether an extracted nametag name matches a ticked mob, variant prefixes tolerated. */
    private static boolean matchesWanted(Set<String> wanted, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        while (true) {
            if (wanted.contains(lower)) {
                return true;
            }
            String stripped = null;
            for (String prefix : VARIANT_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    stripped = lower.substring(prefix.length()).trim();
                    break;
                }
            }
            if (stripped == null || stripped.isEmpty()) {
                return false;
            }
            lower = stripped;
        }
    }

    public static String mobNameInNametag(String rawName) {
        if (rawName == null) {
            return null;
        }
        String core = rawName.replaceAll(SECTION_SIGN + ".", "");
        core = core.replaceAll("\\[[^\\]]*\\]", " ");           // [Lv120] and any other bracketed tag
        core = core.replaceAll("[^A-Za-z0-9'.,/\\- ]", " ");     // type symbols, ᛤ, stars, hearts, bars
        // Trailing health ("30/30", "1,000", "12k", "1.2M") plus whatever trails it (spaces by now).
        core = core.replaceAll("[0-9][0-9.,]*\\s*[kKmMbB]?\\s*(?:/\\s*[0-9][0-9.,]*\\s*[kKmMbB]?)?[^\\p{L}]*$", " ");
        core = core.replaceAll("\\s+", " ").trim();
        return core.isEmpty() ? null : core;
    }

    /**
     * The living mob a nametag armor stand belongs to: nearest living entity just below it. The
     * search box is generous (1 block out, 4 blocks down) because custom Hypixel mobs are often tall
     * and their nametag floats well above the body - a tight box missed them.
     */
    public static LivingEntity mobBelow(ClientLevel level, ArmorStand stand) {
        List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class,
                stand.getBoundingBox().inflate(1.0, 0, 1.0).expandTowards(0, -4, 0),
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
}
