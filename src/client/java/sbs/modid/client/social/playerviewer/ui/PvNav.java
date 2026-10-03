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
import sbs.modid.client.social.playerviewer.ui.page.LoadoutsPage;
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

    /** A sub-page: its sidebar label and the page that draws it. */
    public record Sub(String label, PvPage page) {
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
                        new Sub("Dungeons", new DungeonsPage()),
                        new Sub("Kuudra", new KuudraPage()),
                        new Sub("Slayer", new SlayerPage()),
                        new Sub("Diana", new DianaPage()))),
                new Category("Inventory", List.of(
                        new Sub("Overview", new GearPage()),
                        new Sub("Accessories", new AccessoriesPage()),
                        new Sub("Inventory", new ContainerPage("inventory", "Inventory")),
                        new Sub("Ender Chest", new ContainerPage("ender_chest", "Ender Chest")),
                        new Sub("Backpacks", new ContainerPage("backpacks", "Backpacks")),
                        new Sub("Accessory Bag", new ContainerPage("accessory_bag", "Accessory Bag")),
                        new Sub("Quiver", new ContainerPage("quiver", "Quiver")),
                        new Sub("Potion Bag", new ContainerPage("potion_bag", "Potion Bag")),
                        new Sub("Fishing Bag", new ContainerPage("fishing_bag", "Fishing Bag")),
                        new Sub("Loadouts", new LoadoutsPage()),
                        new Sub("Vault", new ContainerPage("vault", "Personal Vault")))),
                new Category("Pets", List.of(
                        new Sub("Overview", new PetsListPage()),
                        new Sub("By Rarity", new PetsRarityPage()),
                        new Sub("Held Items", new PetsHeldPage()))),
                new Category("Collections", List.of(
                        new Sub("Overview", new CollectionsPage(null)),
                        new Sub("Farming", new CollectionsPage("FARMING")),
                        new Sub("Mining", new CollectionsPage("MINING")),
                        new Sub("Combat", new CollectionsPage("COMBAT")),
                        new Sub("Foraging", new CollectionsPage("FORAGING")),
                        new Sub("Fishing", new CollectionsPage("FISHING")),
                        new Sub("Rift", new CollectionsPage("RIFT")))),
                new Category("Farming", List.of(
                        new Sub("Overview", new FarmingPage(FarmingPage.Mode.OVERVIEW)),
                        new Sub("Contests", new FarmingPage(FarmingPage.Mode.CONTESTS)),
                        new Sub("Personal Bests", new FarmingPage(FarmingPage.Mode.BESTS)),
                        new Sub("Garden", new FarmingPage(FarmingPage.Mode.GARDEN)))),
                new Category("Foraging", List.of(
                        new Sub("Overview", new ForagingPage(ForagingPage.Mode.OVERVIEW)),
                        new Sub("Trees", new ForagingPage(ForagingPage.Mode.TREES)),
                        new Sub("Harp", new ForagingPage(ForagingPage.Mode.HARP)))),
                new Category("Fishing", List.of(
                        new Sub("Overview", new FishingPage(FishingPage.Mode.OVERVIEW)),
                        new Sub("Trophy Fish", new FishingPage(FishingPage.Mode.TROPHY)),
                        new Sub("Stats", new FishingPage(FishingPage.Mode.STATS)))),
                new Category("Mining", List.of(
                        new Sub("Overview", new MiningPage(MiningPage.Mode.OVERVIEW)),
                        new Sub("Powder", new MiningPage(MiningPage.Mode.POWDER)),
                        new Sub("Crystals", new MiningPage(MiningPage.Mode.CRYSTALS)),
                        new Sub("Glacite", new MiningPage(MiningPage.Mode.GLACITE)))),
                new Category("Museum", List.of(
                        new Sub("Overview", new MuseumPage()))),
                new Category("Hoppity", List.of(
                        new Sub("Overview", new HoppityPage(HoppityPage.Mode.OVERVIEW)),
                        new Sub("Rabbits", new HoppityPage(HoppityPage.Mode.RABBITS)),
                        new Sub("Factory", new HoppityPage(HoppityPage.Mode.FACTORY)))),
                new Category("Rift", List.of(
                        new Sub("Overview", new RiftPage(RiftPage.Mode.OVERVIEW)),
                        new Sub("Progress", new RiftPage(RiftPage.Mode.PROGRESS)),
                        new Sub("Enigma Souls", new RiftPage(RiftPage.Mode.SOULS)))));
    }
}
