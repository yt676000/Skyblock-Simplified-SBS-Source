/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import sbs.modid.client.social.playerviewer.ui.page.AccessoriesPage;
import sbs.modid.client.social.playerviewer.ui.page.CollectionsPage;
import sbs.modid.client.social.playerviewer.ui.page.ContainerPage;
import sbs.modid.client.social.playerviewer.ui.page.GearPage;
import sbs.modid.client.social.playerviewer.ui.page.HeartTreePage;
import sbs.modid.client.social.playerviewer.ui.page.LoadoutCardsPage;
import sbs.modid.client.social.playerviewer.ui.page.StoragePagesPage;
import sbs.modid.client.social.playerviewer.ui.page.MuseumPage;
import sbs.modid.client.social.playerviewer.ui.page.combat.CombatOverviewPage;
import sbs.modid.client.social.playerviewer.ui.page.combat.DianaPage;
import sbs.modid.client.social.playerviewer.ui.page.combat.DungeonsPage;
import sbs.modid.client.social.playerviewer.ui.page.combat.KuudraPage;
import sbs.modid.client.social.playerviewer.ui.page.combat.SlayerPage;
import sbs.modid.client.social.playerviewer.ui.page.event.HoppityPage;
import sbs.modid.client.social.playerviewer.ui.page.event.RiftPage;
import sbs.modid.client.social.playerviewer.ui.page.home.HomeOverviewPage;
import sbs.modid.client.social.playerviewer.ui.page.home.HomeStatsPage;
import sbs.modid.client.social.playerviewer.ui.page.pets.PetsHeldPage;
import sbs.modid.client.social.playerviewer.ui.page.pets.PetsListPage;
import sbs.modid.client.social.playerviewer.ui.page.pets.PetsRarityPage;
import sbs.modid.client.social.playerviewer.ui.page.skill.FarmingPage;
import sbs.modid.client.social.playerviewer.ui.page.skill.FishingPage;
import sbs.modid.client.social.playerviewer.ui.page.skill.ForagingPage;
import sbs.modid.client.social.playerviewer.ui.page.skill.MiningPage;

import java.util.List;

/**
 * The profile viewer's two-level navigation: a fixed top bar of categories, and per category a left
 * sidebar of its sub-pages.
 *
 * <p>This registry is the single place that knows the structure – the shell reads it to lay out both
 * levels, so <b>adding a category is one entry here plus its page classes</b>, and the top bar
 * re-flows itself around the new label automatically. The list is built per screen (not static) so
 * one player's selections never bleed into the next profile you open.
 *
 * <p>Sub-pages track what Hypixel actually returns, verified against live profiles – e.g. Mining
 * splits into Powder / Crystals / Glacite because that is what the API stores, and there is no
 * "Commissions" page because there is no commission history to put on one.
 */
public final class PvNav {

    /**
     * A sub-page: its sidebar label, the page that draws it, and the view sections it depends on.
     * When any of those sections is unavailable the shell shows a neutral message instead of the
     * page. A sub-page with no sections (the overviews) shows a footnote naming what is missing.
     */
    public record Sub(String label, PvPage page, List<String> sections) {
        public Sub(String label, PvPage page) {
            this(label, page, List.of());
        }
    }

    /** A top-bar category and the sub-pages of its sidebar. */
    public record Category(String label, List<Sub> subs) {
    }

    private PvNav() {
    }

    /** A fresh navigation tree, with fresh page instances (and therefore fresh page state). */
    public static List<Category> build() {
        return List.of(
                new Category("Home", List.of(
                        new Sub("Overview", new HomeOverviewPage()),
                        new Sub("Stats", new HomeStatsPage()))),
                new Category("Combat", List.of(
                        new Sub("Overview", new CombatOverviewPage()),
                        new Sub("Dungeons", new DungeonsPage(), List.of("dungeons")),
                        new Sub("Kuudra", new KuudraPage(), List.of("crimson")),
                        new Sub("Slayer", new SlayerPage(), List.of("slayer")),
                        new Sub("Diana", new DianaPage(), List.of("diana")))),
                new Category("Inventory", List.of(
                        new Sub("Overview", new GearPage()),
                        new Sub("Accessories", new AccessoriesPage(), List.of("accessories")),
                        new Sub("Inventory", new ContainerPage("inventory", "Inventory"), List.of("inventory")),
                        new Sub("Ender Chest", new StoragePagesPage(StoragePagesPage.Mode.ENDER_CHEST), List.of("inventory","ender_chest")),
                        new Sub("Backpacks", new StoragePagesPage(StoragePagesPage.Mode.BACKPACKS), List.of("inventory","backpacks")),
                        new Sub("Accessory Bag", new ContainerPage("accessory_bag", "Accessory Bag"), List.of("inventory","accessory_bag")),
                        new Sub("Quiver", new ContainerPage("quiver", "Quiver"), List.of("inventory","quiver")),
                        new Sub("Potion Bag", new ContainerPage("potion_bag", "Potion Bag"), List.of("inventory","potion_bag")),
                        new Sub("Bait Sack", new ContainerPage("bait_sack", "Bait Sack"), List.of("inventory","fishing_bag")),
                        new Sub("Loadouts", new LoadoutCardsPage(LoadoutCardsPage.Mode.LOADOUTS), List.of("loadouts")),
                        new Sub("Wardrobe", new LoadoutCardsPage(LoadoutCardsPage.Mode.WARDROBE), List.of("loadouts")),
                        new Sub("Equipment", new LoadoutCardsPage(LoadoutCardsPage.Mode.EQUIPMENT), List.of("loadouts")),
                        new Sub("Vault", new ContainerPage("vault", "Personal Vault"), List.of("inventory","vault")))),
                new Category("Pets", List.of(
                        new Sub("Overview", new PetsListPage(), List.of("pets")),
                        new Sub("By Rarity", new PetsRarityPage(), List.of("pets")),
                        new Sub("Held Items", new PetsHeldPage(), List.of("pets")))),
                new Category("Collections", List.of(
                        new Sub("Overview", new CollectionsPage(null), List.of("collections")),
                        new Sub("Farming", new CollectionsPage("FARMING"), List.of("collections")),
                        new Sub("Mining", new CollectionsPage("MINING"), List.of("collections")),
                        new Sub("Combat", new CollectionsPage("COMBAT"), List.of("collections")),
                        new Sub("Foraging", new CollectionsPage("FORAGING"), List.of("collections")),
                        new Sub("Fishing", new CollectionsPage("FISHING"), List.of("collections")),
                        new Sub("Rift", new CollectionsPage("RIFT"), List.of("collections")))),
                new Category("Farming", List.of(
                        new Sub("Overview", new FarmingPage(FarmingPage.Mode.OVERVIEW), List.of("farming")),
                        new Sub("Contests", new FarmingPage(FarmingPage.Mode.CONTESTS), List.of("farming")),
                        new Sub("Personal Bests", new FarmingPage(FarmingPage.Mode.BESTS), List.of("farming")),
                        new Sub("Garden", new FarmingPage(FarmingPage.Mode.GARDEN), List.of("farming")))),
                new Category("Foraging", List.of(
                        new Sub("Overview", new ForagingPage(ForagingPage.Mode.OVERVIEW), List.of("foraging")),
                        new Sub("Trees", new ForagingPage(ForagingPage.Mode.TREES), List.of("foraging")),
                        new Sub("HotF", new HeartTreePage(HeartTreePage.Mode.FOREST), List.of("skill_tree")),
                        new Sub("Harp", new ForagingPage(ForagingPage.Mode.HARP), List.of("foraging")))),
                new Category("Fishing", List.of(
                        new Sub("Overview", new FishingPage(FishingPage.Mode.OVERVIEW), List.of("fishing")),
                        new Sub("Trophy Fish", new FishingPage(FishingPage.Mode.TROPHY), List.of("fishing")),
                        new Sub("Stats", new FishingPage(FishingPage.Mode.STATS), List.of("fishing")))),
                new Category("Mining", List.of(
                        new Sub("Overview", new MiningPage(MiningPage.Mode.OVERVIEW), List.of("mining")),
                        new Sub("Powder", new MiningPage(MiningPage.Mode.POWDER), List.of("mining")),
                        new Sub("Crystals", new MiningPage(MiningPage.Mode.CRYSTALS), List.of("mining")),
                        new Sub("Glacite", new MiningPage(MiningPage.Mode.GLACITE), List.of("mining")),
                        new Sub("HotM", new HeartTreePage(HeartTreePage.Mode.MOUNTAIN), List.of("skill_tree")))),
                new Category("Museum", List.of(
                        new Sub("Overview", new MuseumPage(), List.of("museum")))),
                new Category("Hoppity", List.of(
                        new Sub("Overview", new HoppityPage(HoppityPage.Mode.OVERVIEW), List.of("hoppity")),
                        new Sub("Rabbits", new HoppityPage(HoppityPage.Mode.RABBITS), List.of("hoppity")),
                        new Sub("Factory", new HoppityPage(HoppityPage.Mode.FACTORY), List.of("hoppity")))),
                new Category("Rift", List.of(
                        new Sub("Overview", new RiftPage(RiftPage.Mode.OVERVIEW), List.of("rift")),
                        new Sub("Progress", new RiftPage(RiftPage.Mode.PROGRESS), List.of("rift")),
                        new Sub("Enigma Souls", new RiftPage(RiftPage.Mode.SOULS), List.of("rift")))));
    }
}
