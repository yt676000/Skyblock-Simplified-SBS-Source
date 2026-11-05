/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.mobhighlight.logic;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import sbs.modid.client.combat.mobhighlight.render.MobHighlightRenderer;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.NameClass;
import sbs.modid.client.core.entity.NametagText;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.core.player.RealPlayers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 *
 * <p><b>Cost.</b> The passes walk {@link EntityIndex} buckets, not the whole level, and every
 * per-entity answer that only depends on the name (the extracted mob name and whether it is wanted)
 * is cached against the name's revision, so an unchanged tag costs a reference compare. The renderer
 * is only handed a new list when the targets changed.
 */
public final class MobHighlightTracker {

    private static final MobHighlightTracker INSTANCE = new MobHighlightTracker();

    /** Throttle for the diagnostic heartbeat below. */
    private long lastDebugAt;

    /**
     * How long a mob keeps being highlighted after its nametag was last read. Hypixel's nametag
     * stand flickers / isn't always parseable every tick, which made the box blink or wait; once a
     * mob is identified it stays boxed for this long even when the tag momentarily isn't detected.
     */
    private static final long PERSIST_MS = 4_000L;

    /** Ticks between sweeps of the per-entity caches for entities that left the level. */
    private static final int PRUNE_TICKS = 100;

    /** Entity id -> when it was last confirmed, and the name it was identified as. */
    private final Int2LongOpenHashMap lastSeenAt = new Int2LongOpenHashMap();
    private final Int2ObjectOpenHashMap<String> identifiedName = new Int2ObjectOpenHashMap<>();

    /** Per entity id: whether its current name is wanted, keyed by entity, name revision and selection. */
    private static final class NameMatch {
        Tracked owner;
        int revision = -1;
        int wantedVersion = -1;
        boolean matches;
    }

    private final Int2ObjectOpenHashMap<NameMatch> matches = new Int2ObjectOpenHashMap<>();

    /** The selection as last seen, lower-cased; rebuilt only when the config set changes. */
    private final Set<String> wantedSource = new LinkedHashSet<>();
    private final Set<String> wanted = new HashSet<>();
    private int wantedVersion;

    /** Lower-cased and display type names per entity type, re-read when the language changes. */
    private final Reference2ObjectOpenHashMap<EntityType<?>, String[]> typeNames = new Reference2ObjectOpenHashMap<>();
    private String typeNamesLanguage = "";

    /** This tick's picks (scratch, client thread only) and the list the renderer last got. */
    private final IntOpenHashSet boxed = new IntOpenHashSet();
    private final ArrayList<Entity> pickEntities = new ArrayList<>();
    private final ArrayList<String> pickLabels = new ArrayList<>();
    private List<MobHighlightRenderer.Target> published = List.of();
    private int tick;

    private MobHighlightTracker() {
    }

    public static MobHighlightTracker getInstance() {
        return INSTANCE;
    }

    /**
     * World leave: the persistence maps are keyed by entity id, and an id reused in the next world
     * would keep a stranger boxed under the old mob's name for {@link #PERSIST_MS}.
     */
    public void onWorldChange() {
        lastSeenAt.clear();
        identifiedName.clear();
        MobHighlightRenderer.getInstance().setTargets(List.of());
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
            pickEntities.clear();
            pickLabels.clear();
            publish();
            return;
        }
        refreshWanted(settings.selectedMobs);

        pickEntities.clear();
        pickLabels.clear();
        boxed.clear();

        // Pass 1: SkyBlock mobs by their nametag stand (only those in clear line of sight). The
        // nametag name must EXACTLY match one the player ticked - that match is the whole filter, so
        // no health readout is required. Many custom Hypixel mobs (bosses, mini-bosses, event mobs)
        // carry a plain name with no "30/30❤", which the old health-tag gate silently rejected.
        List<Tracked> stands = EntityIndex.bucket(EntityKind.ARMOR_STAND);
        for (int i = 0, n = stands.size(); i < n; i++) {
            Tracked tracked = stands.get(i);
            if (tracked.nameClass() == NameClass.NONE || !isWanted(tracked)) {
                continue;
            }
            LivingEntity mob = mobBelow(level, (ArmorStand) tracked.entity);
            if (mob != null && mob != player && !boxed.contains(mob.getId())
                    && player.hasLineOfSight(mob) && boxed.add(mob.getId())) {
                pick(mob, tracked.mobName());
            }
        }

        // Pass 1b: mobs carrying their SkyBlock name as their OWN custom name. On the modern
        // protocol Hypixel does not always ship a separate nametag stand - the name can sit right
        // on the mob entity - and a pass that only walks armor stands never sees those at all
        // (which showed exactly as "only vanilla animals highlight"). Same extraction, same match;
        // the entity IS the mob, no stand association needed. ArmorStands are their own bucket and
        // pass 1 already handled them.
        namedMobPass(EntityIndex.bucket(EntityKind.LIVING), player);
        namedMobPass(EntityIndex.bucket(EntityKind.PLAYER), player);

        // Pass 2: non-hostile mobs (farm animals) by their own type name, again line-of-sight only.
        refreshTypeNameLanguage(minecraft);
        typeNamePass(EntityIndex.bucket(EntityKind.LIVING), player);
        typeNamePass(EntityIndex.bucket(EntityKind.PLAYER), player);

        // Persistence: remember every mob identified this tick, then re-add recently-identified ones
        // whose nametag was not parsed this tick - so the highlight sticks instead of blinking with
        // the (flickery) nametag. Still line-of-sight gated, still only mobs seen at least once.
        long now = System.currentTimeMillis();
        for (int i = 0, n = pickEntities.size(); i < n; i++) {
            int id = pickEntities.get(i).getId();
            lastSeenAt.put(id, now);
            identifiedName.put(id, pickLabels.get(i));
        }
        for (ObjectIterator<Int2LongOpenHashMap.Entry> iterator = lastSeenAt.int2LongEntrySet().fastIterator();
             iterator.hasNext(); ) {
            Int2LongOpenHashMap.Entry entry = iterator.next();
            int id = entry.getIntKey();
            if (now - entry.getLongValue() > PERSIST_MS) {
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
                String label = identifiedName.get(id);
                pick(living, label == null ? "" : label);
            }
        }

        publish();
        if (++tick % PRUNE_TICKS == 0) {
            matches.int2ObjectEntrySet().removeIf(e -> EntityIndex.get(e.getIntKey()) == null);
        }
        diagnostic(pickEntities.size());
    }

    private void namedMobPass(List<Tracked> bucket, LocalPlayer player) {
        for (int i = 0, n = bucket.size(); i < n; i++) {
            Tracked tracked = bucket.get(i);
            LivingEntity living = (LivingEntity) tracked.entity;
            if (living == player || !living.isAlive() || boxed.contains(living.getId())
                    || tracked.nameClass() == NameClass.NONE || !isWanted(tracked)) {
                continue;
            }
            if (player.hasLineOfSight(living) && boxed.add(living.getId())) {
                pick(living, tracked.mobName());
            }
        }
    }

    private void typeNamePass(List<Tracked> bucket, LocalPlayer player) {
        for (int i = 0, n = bucket.size(); i < n; i++) {
            LivingEntity living = (LivingEntity) bucket.get(i).entity;
            if (living == player || !living.isAlive() || boxed.contains(living.getId())
                    || living.getType().getCategory() == MobCategory.MONSTER) {
                continue;
            }
            String[] names = typeNames(living.getType());
            if (!wanted.contains(names[0])) {
                continue;
            }
            if (player.hasLineOfSight(living) && boxed.add(living.getId())) {
                pick(living, names[1]);
            }
        }
    }

    private void pick(Entity entity, String label) {
        pickEntities.add(entity);
        pickLabels.add(label);
    }

    /** Hands the renderer a new list only when this tick's picks differ from the last one. */
    private void publish() {
        int n = pickEntities.size();
        boolean same = published.size() == n;
        for (int i = 0; same && i < n; i++) {
            MobHighlightRenderer.Target target = published.get(i);
            same = target.entity() == pickEntities.get(i) && target.label().equals(pickLabels.get(i));
        }
        if (same) {
            return;
        }
        List<MobHighlightRenderer.Target> next = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            next.add(new MobHighlightRenderer.Target(pickEntities.get(i), pickLabels.get(i)));
        }
        published = List.copyOf(next);
        MobHighlightRenderer.getInstance().setTargets(published);
    }

    /** Wanted-ness of a tracked entity's current name, computed once per name and selection. */
    private boolean isWanted(Tracked tracked) {
        int id = tracked.entity.getId();
        NameMatch match = matches.get(id);
        if (match == null) {
            match = new NameMatch();
            matches.put(id, match);
        }
        int revision = tracked.revision();
        if (match.owner != tracked || match.revision != revision || match.wantedVersion != wantedVersion) {
            String name = tracked.mobName();
            match.matches = name != null && matchesWanted(wanted, name);
            match.revision = revision;
            match.wantedVersion = wantedVersion;
            match.owner = tracked;
        }
        return match.matches;
    }

    /** Lower-cased selection for O(1), case-insensitive matching; rebuilt only when it changed. */
    private void refreshWanted(Set<String> selected) {
        if (wantedSource.equals(selected)) {
            return;
        }
        wantedSource.clear();
        wantedSource.addAll(selected);
        wanted.clear();
        for (String name : selected) {
            wanted.add(name.toLowerCase(Locale.ROOT));
        }
        wantedVersion++;
    }

    private void refreshTypeNameLanguage(Minecraft minecraft) {
        String language = minecraft.getLanguageManager().getSelected();
        if (!language.equals(typeNamesLanguage)) {
            typeNames.clear();
            typeNamesLanguage = language;
        }
    }

    /** {lower-cased, as displayed} type name of {@code type}, read once per language. */
    private String[] typeNames(EntityType<?> type) {
        String[] names = typeNames.get(type);
        if (names == null) {
            String display = type.getDescription().getString();
            names = new String[] {display.toLowerCase(Locale.ROOT), display};
            typeNames.put(type, names);
        }
        return names;
    }

    /**
     * Throttled heartbeat (~every 5s while mobs are ticked): what the detector currently sees.
     * The mob encoding on Hypixel's side has shifted repeatedly (type symbols, the ᛤ marker, names
     * on the entity instead of a stand) - when a wanted mob is not highlighted in-game, this line
     * in the instance log shows the raw nametags and what was extracted from them, so the next fix
     * is aimed rather than guessed.
     */
    private void diagnostic(int matched) {
        long now = System.currentTimeMillis();
        if (now - lastDebugAt < 5_000L) {
            return;
        }
        lastDebugAt = now;
        int stands = 0;
        int namedMobs = 0;
        List<String> sample = new ArrayList<>();
        for (EntityKind kind : DIAGNOSTIC_KINDS) {
            List<Tracked> bucket = EntityIndex.bucket(kind);
            for (int i = 0, n = bucket.size(); i < n; i++) {
                Tracked tracked = bucket.get(i);
                if (tracked.nameClass() == NameClass.NONE && !tracked.entity.hasCustomName()) {
                    continue;
                }
                if (kind == EntityKind.ARMOR_STAND) {
                    stands++;
                } else {
                    namedMobs++;
                }
                if (sample.size() < 4) {
                    sample.add((kind == EntityKind.ARMOR_STAND ? "stand" : "mob") + " \"" + tracked.raw()
                            + "\" -> \"" + tracked.mobName() + "\"");
                }
            }
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][MobHL] stands={} namedMobs={} matched={} wanted={} sample={}",
                stands, namedMobs, matched, wanted, sample);
    }

    private static final EntityKind[] DIAGNOSTIC_KINDS = {EntityKind.ARMOR_STAND, EntityKind.LIVING, EntityKind.PLAYER};

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

    /** {@link NametagText#mobName}; per-tick callers should use {@link Tracked#mobName}, which caches it. */
    public static String mobNameInNametag(String rawName) {
        return NametagText.mobName(rawName);
    }

    /**
     * The living mob a nametag armor stand belongs to: nearest living entity just below it. The
     * search box is generous (1 block out, 4 blocks down) because custom Hypixel mobs are often tall
     * and their nametag floats well above the body - a tight box missed them. Real players are
     * skipped; Hypixel's player-model mobs are fake players and stay candidates.
     */
    public static LivingEntity mobBelow(ClientLevel level, ArmorStand stand) {
        List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class,
                stand.getBoundingBox().inflate(1.0, 0, 1.0).expandTowards(0, -4, 0),
                m -> m != stand && !(m instanceof ArmorStand) && !RealPlayers.isRealPlayerEntity(m) && m.isAlive());
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
