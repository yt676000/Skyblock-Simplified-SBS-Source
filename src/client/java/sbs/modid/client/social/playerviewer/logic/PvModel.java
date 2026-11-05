/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import java.util.List;
import java.util.Map;

/**
 * The fixed shapes the Player Viewer reads out of a relayed Hypixel profile. Only fields the
 * calculations use are here; everything else in the body is ignored by {@link PvParser}.
 *
 * <p>Conventions: a {@code null} field or section means "absent in the data" (shown as n/a). Maps
 * keep the document order of the source object, because several old-server outputs depend on it.
 * Inventory blobs are carried as their raw base64 text and are only decoded, under
 * {@code NbtLimits}, by {@link PvCompute}.
 */
public final class PvModel {

    private PvModel() {
    }

    /** One entry of the player's profile list ({@code /skyblock/profiles}). */
    public record ProfileRef(String profileId, String cuteName, boolean selected, String gameMode) {
    }

    /** One {@code /skyblock/profile} body, reduced to the viewed member. */
    public record Profile(String profileId, Double bankBalance, Member member) {
    }

    public record Member(
            Double levelingXp,
            AccessoryStorage accessoryStorage,
            Map<String, Double> skillXp,
            Dungeons dungeons,
            Map<String, SlayerBoss> slayerBosses,
            MiningCore miningCore,
            PlayerStats stats,
            Double legacyMythosKills,
            Bestiary bestiary,
            Nether nether,
            List<Pet> pets,
            Inventory inventory,
            Map<String, Double> collection,
            Glacite glacite,
            ForagingCore foragingCore,
            Foraging foraging,
            TrophyFish trophyFish,
            Jacob jacob,
            Garden garden,
            Easter easter,
            Rift rift,
            Currencies currencies,
            Double legacyCoinPurse,
            Double firstJoin,
            Double fairySouls,
            Loadout loadout,
            SkillTree skillTree,
            List<String> invalid) {
    }

    public record AccessoryStorage(Double highestMp, String selectedPower, Double bagUpgrades,
                                   Map<String, Double> tuningSlot0) {
    }

    public record DungeonType(Map<String, Double> tierCompletions, Map<String, Double> fastest,
                              Map<String, Double> fastestS, Map<String, Double> fastestSPlus,
                              Map<String, Double> bestScore) {
    }

    public record Dungeons(Double cataXp, DungeonType catacombs, DungeonType master, Double secrets,
                           Map<String, Double> classXp) {
    }

    /** {@code tierKills} holds {@code boss_kills_tier_N} -> kills. */
    public record SlayerBoss(Double xp, Map<String, Double> tierKills) {
    }

    public record Crystal(Double found, Double placed, String state) {
    }

    public record MiningCore(Double experience, Map<String, Double> numbers, Map<String, Crystal> crystals,
                             Map<String, Double> biomesFlat) {
    }

    public record PlayerStats(Double summoningEyes, Double mythosKills, Map<String, Double> burrows,
                              Map<String, Double> auctions, Map<String, Double> minedOres,
                              Double blocksMined, Double seaCreatureKills, Map<String, Double> itemsFished,
                              Map<String, Double> kills, Map<String, Double> deaths,
                              Map<String, Double> rift) {
    }

    public record Bestiary(Double milestone, Map<String, Double> kills) {
    }

    /** {@code abiphoneContacts}: size of {@code abiphone.active_contacts}, or null when absent. */
    public record Nether(Map<String, Double> kuudra, String faction, Double reputation,
                         Map<String, Double> dojo, Integer abiphoneContacts) {
    }

    /** {@code uuid} is the pet's own id; loadouts reference their pet by it. */
    /**
     * {@code uuid} and {@code uniqueId} are two different ids of the same pet; a loadout names its pet
     * by {@code uniqueId}.
     */
    public record Pet(String uuid, String uniqueId, String type, String tier, Double exp, String heldItem,
                      boolean active, String skin) {
    }

    /**
     * Inventory blobs by Hypixel key. {@code containers} covers the keys directly on the inventory
     * root, {@code bags} the ones under {@code bag_contents}, {@code backpacks} the numbered packs
     * in document order.
     */
    public record Inventory(Map<String, String> containers, Map<String, String> bags,
                            Map<String, String> backpacks) {
    }

    public record Glacite(Double mineshafts, Double fossilDust, Integer fossilsDonated,
                          Map<String, Double> corpses) {
    }

    public record ForagingCore(Double whispers, Double whispersSpent, String dailyEffect) {
    }

    public record Hina(Integer completed, Double tierClaimed) {
    }

    public record Foraging(Map<String, Double> treeGifts, Hina hina, Map<String, Double> harp,
                           Map<String, Double> personalBests) {
    }

    public record TrophyFish(Map<String, Double> counts, Integer rewards) {
    }

    public record Jacob(Map<String, Double> medals, Map<String, Double> perks, Integer contests,
                        List<Double> claimedPositions, Map<String, Double> personalBests,
                        Map<String, Integer> uniqueBrackets) {
    }

    public record Garden(Double copper, Double larvaConsumed, Integer greenhouseCrops) {
    }

    public record Easter(Double chocolate, Double totalChocolate, Double sincePrestige, Double barnLevel,
                         Map<String, Double> employees, Map<String, Double> timeTower,
                         Map<String, Double> rabbits, Map<String, Double> eggs) {
    }

    public record Rift(List<String> souls, Boolean cloak, Integer witherEyes, Integer deadCats,
                       boolean montezuma, Integer trophies, Integer boundaries, Double wizardStep,
                       Double eliseStep) {
    }

    public record Currencies(Double motes, Double coinPurse, Map<String, Double> essence) {
    }

    /**
     * {@code member.loadout}. The armour wardrobe and the equipment wardrobe are two independent
     * numbered lists (each set maps its digit key to piece key -> blob); a {@link LoadoutEntry}
     * combines one set of each with a pet and the other per-loadout picks.
     */
    public record Loadout(Map<String, Map<String, String>> armor, Double equippedArmor,
                          Map<String, Map<String, String>> equipment, Double equippedEquipment,
                          List<LoadoutEntry> entries) {
    }

    /**
     * One {@code loadout.loadouts.<id>}. Every field but {@code id} may be null: an unused loadout
     * carries only its id and name. The set ids point into {@link Loadout#armor} / {@link
     * Loadout#equipment}; {@code petUuid} into the pet list; the three {@code *Slot} fields name a
     * preset of the tuning points, the Heart of the Mountain and the Heart of the Forest.
     */
    public record LoadoutEntry(String id, String name, String armorSet, String equipmentSet,
                               String powerStone, String petUuid, Double tuningSlot,
                               Double hotmSlot, Double hotfSlot) {
    }

    /**
     * {@code member.skill_tree}: the Heart of the Mountain and Heart of the Forest. Keyed by tree id
     * as Hypixel writes it ({@code mining}, {@code mining_2}... are the presets of one tree;
     * {@code foraging}... likewise). {@code levels} holds perk -> level, {@code toggles} perk ->
     * enabled, read from the {@code toggle_<perk>} keys beside them.
     */
    public record SkillTree(Map<String, Map<String, Double>> levels,
                            Map<String, Map<String, Boolean>> toggles,
                            Map<String, String> selectedAbility, Map<String, Double> tokensSpent,
                            Map<String, Double> experience, Map<String, Double> selectedSlot) {
    }
}
