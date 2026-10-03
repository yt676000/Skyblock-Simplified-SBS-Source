/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.warp;

import java.util.List;

/**
 * The SkyBlock warp destinations, grouped by island, for the {@link WarpMenuScreen}.
 *
 * <p>Each {@link Island} is a box in the menu: its title runs the island's own warp, and the entries
 * under it are the sub-warps that land somewhere specific on that island.
 *
 * <p>Hypixel exposes no warp list over the API, so this is a hand-maintained table – if a warp is
 * renamed or added, it is one line here and nothing else changes.
 */
public final class WarpCatalog {

    private WarpCatalog() {
    }

    /** One warp destination: the label shown, the command run, and an optional note. */
    public record Warp(String label, String command, String note) {
        public Warp(String label, String command) {
            this(label, command, "");
        }
    }

    /** An island box: its own warp plus the sub-warps that live on it. */
    public record Island(String name, String command, String note, List<Warp> warps) {
        public Island(String name, String command, List<Warp> warps) {
            this(name, command, "", warps);
        }
    }

    /** Every box of the menu, in the order they are laid out. */
    public static final List<Island> ISLANDS = List.of(
            new Island("Private Island", "/warp home", List.of(
                    new Warp("The Garden", "/warp garden"))),
            new Island("Hub", "/warp hub", List.of(
                    new Warp("Museum", "/warp museum"),
                    new Warp("Elizabeth", "/warp elizabeth", "Community Shop"),
                    new Warp("Castle", "/warp castle"),
                    new Warp("Crypt", "/warp crypt", "Graveyard"),
                    new Warp("Dark Auction", "/warp da"),
                    new Warp("Wizard Tower", "/warp wizard"),
                    new Warp("Bank", "/warp bank"))),
            new Island("The Park", "/warp park", List.of(
                    new Warp("Howling Cave", "/warp howl"),
                    new Warp("Jungle", "/warp jungle"))),
            new Island("Galatea", "/warp galatea", List.of(
                    new Warp("Murkwater", "/warp murkwater"))),
            new Island("The Barn", "/warp barn", List.of(
                    new Warp("Mushroom Desert", "/warp desert"),
                    new Warp("Trapper", "/warp trapper"),
                    new Warp("Glowing Mushroom Cave", "/warp glowing"))),
            // The fishing isles are islands in their own right, not Barn spots - they only sat
            // under The Barn because that is where their portals stand.
            new Island("Backwater Bayou", "/warp bayou", List.of()),
            new Island("Lotus Atoll", "/warp lotus", List.of()),
            // Deep Caverns and the Crystal Hollows are their own boxes rather than a line under the
            // island above them: both are destinations in their own right with their own sub-warps,
            // and the menu only nests one level deep. The Gold Mine rides along in the Deep Caverns
            // box - the surface entrance to the same shaft, not worth an (empty) box of its own.
            new Island("Deep Caverns", "/warp deep", List.of(
                    new Warp("Gold Mine", "/warp gold"))),
            new Island("Dwarven Mines", "/warp mines", List.of(
                    new Warp("Camp", "/warp base"),
                    new Warp("Forge", "/warp forge"))),
            new Island("Crystal Hollows", "/warp crystals", List.of(
                    new Warp("Crystal Nucleus", "/warp nucleus"))),
            new Island("Spider's Den", "/warp spider", List.of(
                    new Warp("Top of Nest", "/warp nest"),
                    new Warp("Arachne", "/warp arachne"))),
            new Island("The End", "/warp end", List.of(
                    new Warp("Dragon's Nest", "/warp drag"),
                    new Warp("Void", "/warp void"))),
            new Island("Crimson Isle", "/warp nether", List.of(
                    new Warp("Magma Boss", "/warp magma"),
                    new Warp("Dojo", "/warp dojo"),
                    new Warp("Kuudra", "/warp kuudra"),
                    new Warp("Smoldering Tomb", "/warp smoldering"))),
            new Island("Dungeon Hub", "/warp dungeon_hub", List.of()),
            new Island("The Rift", "/warp rift", List.of()),
            new Island("Jerry's Workshop", "/warp winter", List.of()),
            // Not an island: a public lobby that holds every portal, so players without the rank
            // that unlocks /warp still have a way to travel.
            new Island("Portal Hub", "/visit prtl",
                    "youtuber with all portals for not mvp+ players", List.of()));
}
