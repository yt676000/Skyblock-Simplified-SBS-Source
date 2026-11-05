/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.hideplayers.logic;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.NameClass;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.helper.npc.SkyblockNpcs;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.helper.hideplayers.model.HidingRule;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.social.notes.logic.PlayerNotesStore;
import sbs.modid.client.social.notes.model.NoteTag;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Hide Nearby Players: which other players are not drawn this tick.
 *
 * <p><b>Rendering only.</b> The render cull ({@code ArmorStandRenderCullMixin} on
 * {@code EntityRenderDispatcher.shouldRender}) skips a hidden player's model, layers, shadow and
 * nametag. Nothing else is touched: the entity still exists, collides, and is picked by the crosshair,
 * so it can still be hit or right-clicked.
 *
 * <p><b>Per tick, not per frame.</b> {@link #tick} applies {@link HidingRule} to each player once a
 * tick and keeps the result as a set of UUIDs; {@link #skipRender} - called per entity per frame - is
 * an {@code instanceof} and a {@code contains}. The party roster, the dungeon team and the Trusted
 * notes are read in the tick, never in the render check.
 *
 * <p>Real player vs NPC is {@link sbs.modid.client.core.player.RealPlayers}: the player list, the
 * same test entity scaling uses (the UUID version is not reliable on Hypixel - see that class).
 *
 * <p><b>The NPC mode</b> hides players crowding an NPC you are within
 * {@link HidingRule#NPC_ACTIVE_RANGE} of. The NPCs near you are collected once a tick from the
 * catalogue ({@code SkyblockNpcs}, your island only) and from live player-shaped entities that are
 * not real players, in case the catalogue lacks one or it has moved. Clicks are untouched: a hidden
 * player still takes the click exactly as vanilla, never redirected to the NPC behind (that would be
 * click manipulation). The optional hint only says so on screen.
 *
 * <p><b>Hide Low-Level Players</b> runs in the same pass under its own switch: a real player whose
 * SkyBlock level ({@link PlayerLevels}, in memory only) is known and below the minimum is hidden,
 * with the same exceptions, and never the player under your crosshair.
 */
public final class PlayerHiding {

    private static volatile Set<UUID> hidden = Set.of();
    /** Whether the crosshair points through a hidden player at an NPC (the "step aside" hint). */
    private static volatile boolean hintShown;
    /** Entity ids of the pet stands not to draw. Client thread only: filled in the tick, read in render. */
    private static final IntOpenHashSet hiddenPets = new IntOpenHashSet();
    /** Lower-cased names of the Trusted players seen this tick, for their pets. */
    private static final Set<String> trustedNames = new HashSet<>();

    // The NPC scan's change key and its last result.
    private static boolean npcKeyValid;
    private static long npcKeyBlock;
    private static int npcKeyPlayerRevision;
    private static ClientLevel npcKeyLevel;
    private static List<Vec3> npcCache = List.of();

    private PlayerHiding() {
    }

    private static SBSConfig.HidePlayersSettings cfg() {
        return ConfigManager.getInstance().get().hidePlayers;
    }

    /** Render check, per entity per frame: nothing but a type test and a set lookup. */
    public static boolean skipRender(Entity entity) {
        if (entity instanceof ArmorStand) {
            return !hiddenPets.isEmpty() && hiddenPets.contains(entity.getId());
        }
        Set<UUID> current = hidden;
        return !current.isEmpty() && entity instanceof Player && current.contains(entity.getUUID());
    }

    /** Game tick: rebuilds the set of players not to draw. */
    public static void tick(Minecraft minecraft) {
        SBSConfig.HidePlayersSettings cfg = cfg();
        ClientLevel level = minecraft.level;
        ClientPacketListener connection = minecraft.getConnection();
        // Two independent switches share this pass: the card's own hiding, and Hide Low-Level Players.
        boolean nearby = cfg.enabled;
        boolean lowLevel = cfg.lowLevelEnabled && cfg.lowLevelWorld;
        if (!(nearby || lowLevel) || !cfg.hiding || level == null || minecraft.player == null
                || connection == null || !HidingRule.activeHere(inCombatArea(), cfg.hideInCombat)) {
            hidden = Set.of();
            hintShown = false;
            hiddenPets.clear();
            npcKeyValid = false;
            return;
        }
        HidingRule.Settings settings = PlayerLevels.settings(cfg);
        Entity crosshair = minecraft.crosshairPickEntity;
        List<Vec3> npcs = nearby && cfg.nearNpcs ? npcsNear(minecraft, level) : List.of();
        trustedNames.clear();
        Set<String> dungeonTeam = cfg.keepDungeonTeam ? DungeonTeamClasses.runRoster() : Set.of();
        PartyTracker party = PartyTracker.getInstance();
        PlayerNotesStore notes = PlayerNotesStore.getInstance();
        Set<UUID> next = new HashSet<>();
        for (Player player : level.players()) {
            boolean self = player == minecraft.player;
            String name = player.getGameProfile().name();
            HidingRule.Candidate candidate = new HidingRule.Candidate(
                    self,
                    !self && sbs.modid.client.core.player.RealPlayers.hasPlayerListEntry(player),
                    cfg.keepParty && party.isMember(name),
                    cfg.keepDungeonTeam && dungeonTeam.contains(name.toLowerCase(Locale.ROOT)),
                    cfg.keepTrusted && notes.markerTag(player.getUUID(), name) == NoteTag.TRUSTED,
                    player.distanceToSqr(minecraft.player),
                    nearestSq(player.position(), npcs));
            if (candidate.trusted() && hidePetsActive(cfg)) {
                trustedNames.add(name.toLowerCase(Locale.ROOT));
            }
            if ((nearby && HidingRule.mayHide(candidate, settings))
                    || (lowLevel && HidingRule.mayHideBelowLevel(candidate, settings, PlayerLevels.level(name),
                            cfg.minLevel, player == crosshair))) {
                next.add(player.getUUID());
            }
        }
        hidden = next.isEmpty() ? Set.of() : next;
        hintShown = nearby && cfg.nearNpcs && cfg.npcHint && !next.isEmpty() && !npcs.isEmpty()
                && hiddenPlayerInTheWay(minecraft, level, next, npcs);
        if (nearby) {
            hidePets(minecraft, cfg, dungeonTeam, party);
        } else {
            hiddenPets.clear();
        }
    }

    /**
     * Other players' pets: the {@link NameClass#PET} name stands in the {@link EntityIndex} stand
     * bucket whose owner is not you, your party, your dungeon team or a Trusted player, and that
     * your crosshair is not on. The owner is parsed once per name change ({@link Tracked#petOwner}),
     * so an unchanged stand costs a class check and a few string compares.
     *
     * <p>Only the name stand for now: telling a pet's body stand (and cosmetics) apart from every
     * other headed stand needs the EntityProbe capture first.
     */
    /**
     * The one switch for pet hiding: the setting, and only in the development environment until the
     * pet body and cosmetic stands are classified - a shared config (ConfigShare) can carry
     * {@code hidePets = true} into a normal build.
     */
    private static boolean hidePetsActive(SBSConfig.HidePlayersSettings cfg) {
        return cfg.hidePets && FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    private static void hidePets(Minecraft minecraft, SBSConfig.HidePlayersSettings cfg, Set<String> dungeonTeam,
                                 PartyTracker party) {
        hiddenPets.clear();
        if (!hidePetsActive(cfg)) {
            return;
        }
        String self = minecraft.player.getGameProfile().name();
        Entity target = minecraft.crosshairPickEntity;
        List<Tracked> stands = EntityIndex.bucket(EntityKind.ARMOR_STAND);
        for (int i = 0, n = stands.size(); i < n; i++) {
            Tracked stand = stands.get(i);
            if (stand.nameClass() != NameClass.PET || stand.entity == target) {
                continue;
            }
            String owner = stand.petOwner();
            if (owner == null || owner.equalsIgnoreCase(self)
                    || (cfg.keepParty && party.isMember(owner))
                    || (cfg.keepDungeonTeam && dungeonTeam.contains(owner))
                    || trustedNames.contains(owner)) {
                continue;
            }
            hiddenPets.add(stand.entity.getId());
        }
    }

    /**
     * NPC positions within {@link HidingRule#NPC_ACTIVE_RANGE} of you: catalogue plus live entities.
     * Recomputed only when you moved to another block, the level changed or a player-shaped entity
     * loaded or unloaded (the bucket revision, so a join and a leave in one tick still count);
     * otherwise the last list is returned as is.
     */
    private static List<Vec3> npcsNear(Minecraft minecraft, ClientLevel level) {
        long block = minecraft.player.blockPosition().asLong();
        int playerRevision = EntityIndex.revision(EntityKind.PLAYER);
        if (npcKeyValid && block == npcKeyBlock && playerRevision == npcKeyPlayerRevision
                && level == npcKeyLevel) {
            return npcCache;
        }
        npcKeyValid = true;
        npcKeyBlock = block;
        npcKeyPlayerRevision = playerRevision;
        npcKeyLevel = level;
        npcCache = scanNpcs(minecraft, level);
        return npcCache;
    }

    private static List<Vec3> scanNpcs(Minecraft minecraft, ClientLevel level) {
        Vec3 me = minecraft.player.position();
        double range = HidingRule.NPC_ACTIVE_RANGE;
        List<Vec3> out = new ArrayList<>();
        for (SkyblockNpcs.Npc npc : SkyblockNpcs.all()) {
            // Distance first - it is cheap and rules out almost all of them - then the island.
            if (me.distanceToSqr(npc.x(), npc.y(), npc.z()) <= range * range
                    && SkyBlockLocation.onIsland(npc.island())) {
                out.add(new Vec3(npc.x(), npc.y(), npc.z()));
            }
        }
        for (Player entity : level.players()) {
            if (entity != minecraft.player && entity.distanceToSqr(minecraft.player) <= range * range
                    && !sbs.modid.client.core.player.RealPlayers.hasPlayerListEntry(entity)) {
                out.add(entity.position());
            }
        }
        return out;
    }

    private static double nearestSq(Vec3 at, List<Vec3> npcs) {
        double best = Double.POSITIVE_INFINITY;
        for (Vec3 npc : npcs) {
            best = Math.min(best, at.distanceToSqr(npc));
        }
        return best;
    }

    /**
     * Whether the crosshair ray meets a hidden player first and an NPC behind them. Display only - it
     * decides whether a hint is drawn, never where a click goes.
     */
    private static boolean hiddenPlayerInTheWay(Minecraft minecraft, ClientLevel level, Set<UUID> hiddenNow,
                                                List<Vec3> npcs) {
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        double reach = minecraft.player.entityInteractionRange();
        Vec3 end = eye.add(minecraft.player.getViewVector(1.0f).scale(reach + 3));
        double nearestHidden = Double.POSITIVE_INFINITY;
        for (Player player : level.players()) {
            if (hiddenNow.contains(player.getUUID())) {
                var hit = player.getBoundingBox().clip(eye, end);
                if (hit.isPresent()) {
                    nearestHidden = Math.min(nearestHidden, eye.distanceToSqr(hit.get()));
                }
            }
        }
        if (nearestHidden > reach * reach) {
            return false;
        }
        for (Vec3 npc : npcs) {
            var box = new net.minecraft.world.phys.AABB(npc.x - 0.4, npc.y, npc.z - 0.4, npc.x + 0.4, npc.y + 1.9,
                    npc.z + 0.4);
            var hit = box.clip(eye, end);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) > nearestHidden) {
                return true;
            }
        }
        return false;
    }

    /** The hint under the crosshair, from the HUD pass. */
    public static void renderHint(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (!hintShown || sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        String text = "Hidden player in the way - step aside";
        g.text(font, Component.literal(text), (g.guiWidth() - font.width(text)) / 2, g.guiHeight() / 2 + 12,
                0xFFFFD166);
    }

    /** Dungeons, Kuudra's Hollow and a live slayer boss - where the players around you matter. */
    private static boolean inCombatArea() {
        return SkyBlockLocation.inDungeon() || SkyBlockLocation.onIsland("Kuudra's Hollow")
                || SlayerTracker.getInstance().bossUp();
    }

    /** The toggle key (from {@code KeybindDispatch}). */
    public static void onKeyPressed(int code) {
        SBSConfig.HidePlayersSettings cfg = cfg();
        if (cfg.toggleKey == 0 || code != cfg.toggleKey || !(cfg.enabled || cfg.lowLevelEnabled)) {
            return;
        }
        cfg.hiding = !cfg.hiding;
        ConfigManager.getInstance().save();
        SBSChat.send(Component.literal(cfg.hiding ? " Hiding nearby players" : " Showing all players")
                .withColor(SBSChat.WHITE));
    }
}
