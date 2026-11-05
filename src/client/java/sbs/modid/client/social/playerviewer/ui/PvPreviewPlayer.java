/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.helper.loadouts.PreviewEntities;
import java.util.UUID;

/**
 * The viewed player as a dressable model for the loadout and wardrobe cards: the same never-ticked
 * {@link RemotePlayer} trick the SBS Loadouts grid uses, but wearing the <b>viewed</b> player's skin
 * rather than yours. A bare uuid + name profile carries no textures, so the skin is taken from
 * {@link PvSkinRender}'s profile lookup by overriding {@link RemotePlayer#getSkin}; it is the
 * default skin until that lookup lands.
 *
 * <p>Never added to the level, never ticked, silent (equipping makes no sound), and its id is a
 * {@link PreviewEntities} id so nametag hooks leave it alone. One instance per page, re-dressed per
 * card: dressing only touches a slot whose stack actually changed.
 */
public final class PvPreviewPlayer {

    private static final EquipmentSlot[] ARMOR =
            {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final UUID uuid;
    private final String name;
    private final PvSkinRender skin;
    private RemotePlayer player;

    private PvPreviewPlayer(UUID uuid, String name, PvSkinRender skin) {
        this.uuid = uuid;
        this.name = name;
        this.skin = skin;
    }

    /** A preview for the payload's player, or null when the uuid is not usable. */
    public static PvPreviewPlayer of(String plainUuid, String name) {
        PvSkinRender skin = PvSkinRender.of(plainUuid, name);
        if (skin == null || plainUuid == null || plainUuid.length() != 32) {
            return null;
        }
        try {
            UUID id = new UUID(Long.parseUnsignedLong(plainUuid.substring(0, 16), 16),
                    Long.parseUnsignedLong(plainUuid.substring(16), 16));
            return new PvPreviewPlayer(id, name == null || name.isBlank() ? "Player" : name, skin);
        } catch (NumberFormatException notHex) {
            return null;
        }
    }

    /** Draws the model wearing {@code armor} (helmet first) in the box, head following the cursor. */
    public void draw(GuiGraphicsExtractor g, ItemStack[] armor, int x1, int y1, int x2, int y2, int scale,
                     int mouseX, int mouseY) {
        RemotePlayer p = player();
        if (p == null) {
            return;
        }
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack piece = i < armor.length ? armor[i] : ItemStack.EMPTY;
            if (p.getItemBySlot(ARMOR[i]) != piece) {
                p.setItemSlot(ARMOR[i], piece);
            }
        }
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, x1, y1, x2, y2, scale, 0.0625F,
                mouseX, mouseY, p);
    }

    private RemotePlayer player() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            player = null;
            return null;
        }
        if (player == null || player.level() != mc.level) {
            player = new RemotePlayer(mc.level, new GameProfile(uuid, name)) {
                @Override
                public PlayerSkin getSkin() {
                    return skin.skin();
                }
            };
            player.setId(PreviewEntities.VIEWER_PLAYER_ID);
            player.setSilent(true);
        }
        return player;
    }
}
