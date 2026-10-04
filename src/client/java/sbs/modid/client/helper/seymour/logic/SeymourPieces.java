/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.logic;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The four pieces of Seymour's Special Armor, and what a stack says about its colour.
 *
 * <p>The ids are the ones the cached repo files list under the pieces' names
 * ({@code Repo_Item_Appearance.json}, each a leather piece). They have not been read off a piece in
 * game, so the catalogue is also asked by display name, and the first sighting of each piece logs
 * its id, colour and custom-data keys under {@code [SBS][Seymour]} - one look settles both the id
 * and where the colour lives.
 */
public final class SeymourPieces {

    public enum Piece {
        HAT("VELVET_TOP_HAT", "Velvet Top Hat", "Hat"),
        JACKET("CASHMERE_JACKET", "Cashmere Jacket", "Jacket"),
        TROUSERS("SATIN_TROUSERS", "Satin Trousers", "Trousers"),
        SHOES("OXFORD_SHOES", "Oxford Shoes", "Shoes");

        private final String defaultId;
        private final String displayName;
        private final String shortName;

        Piece(String defaultId, String displayName, String shortName) {
            this.defaultId = defaultId;
            this.displayName = displayName;
            this.shortName = shortName;
        }

        public String displayName() {
            return displayName;
        }

        public String shortName() {
            return shortName;
        }

        /** The id the catalogue gives this name, or the repo's when the catalogue has not loaded. */
        String catalogId() {
            SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byName(displayName);
            return entry == null ? defaultId : entry.id.toUpperCase(Locale.ROOT);
        }

        boolean matches(String id) {
            return id.equals(defaultId) || id.equals(catalogId());
        }
    }

    /**
     * What one stack says about its colour.
     *
     * @param piece   the Seymour piece, or {@code null} for any other item
     * @param id      the SkyBlock id, or {@code null}
     * @param rgb     {@code minecraft:dyed_color}, or {@code -1} when the stack has none
     * @param dyeItem custom data {@code dye_item} (a dye applied to the piece), or {@code ""}
     * @param uuid    the SkyBlock instance uuid, or {@code null}
     */
    public record Facts(Piece piece, String id, int rgb, String dyeItem, String uuid) {
        /** Whether the shown colour is the piece's own: it has one, and no dye replaced it. */
        public boolean ownColour() {
            return rgb >= 0 && dyeItem.isEmpty();
        }
    }

    private static final Set<Piece> LOGGED = EnumSet.noneOf(Piece.class);

    private SeymourPieces() {
    }

    /** Every id a Seymour piece may carry - the repo's and the catalogue's. */
    public static Set<String> ids() {
        Set<String> out = new HashSet<>();
        for (Piece piece : Piece.values()) {
            out.add(piece.defaultId);
            out.add(piece.catalogId());
        }
        return out;
    }

    public static Piece pieceOf(String id) {
        if (id == null) {
            return null;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        for (Piece piece : Piece.values()) {
            if (piece.matches(upper)) {
                return piece;
            }
        }
        return null;
    }

    /** Reads a live stack. Client thread only (it reads the stack's components). */
    public static Facts read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new Facts(null, null, -1, "", null);
        }
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String id = extra.getStringOr("id", "");
        id = id.isEmpty() ? null : id;
        String uuid = extra.getStringOr("uuid", "");
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        Facts facts = new Facts(pieceOf(id), id, dyed == null ? -1 : dyed.rgb() & 0xFFFFFF,
                extra.getStringOr("dye_item", ""), uuid.isEmpty() ? null : uuid);
        if (facts.piece() != null) {
            logFirstSighting(facts, extra);
        }
        return facts;
    }

    private static void logFirstSighting(Facts facts, CompoundTag extra) {
        synchronized (LOGGED) {
            if (!LOGGED.add(facts.piece())) {
                return;
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Seymour] first sighting of {}: id={}, dyed_color={}, dye_item='{}', custom_data keys={}",
                facts.piece().displayName(), facts.id(),
                facts.rgb() < 0 ? "none" : SeymourColour.hex(facts.rgb()), facts.dyeItem(), extra.keySet());
    }
}
