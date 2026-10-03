/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * Duck interface mixed onto {@code EntityRenderState}: remembers what kind of entity the state was
 * extracted from, because the render state itself carries no entity identity. Written during
 * {@code LivingEntityRenderer.extractRenderState}, read when the Animation &amp; Scaling module
 * applies its per-axis entity scale.
 */
public interface EntityScaleKind {

    int KIND_OTHER_ENTITY = 0;
    int KIND_SELF = 1;
    int KIND_OTHER_PLAYER = 2;

    /**
     * A player-model NPC: an entity rendered through the player renderer that is <b>not</b> a real
     * player. On Hypixel these are ordinary {@code Avatar} entities with a fabricated profile, so
     * nothing about the entity class distinguishes them - the discriminator is the player list,
     * which every real player has an entry in and no NPC does.
     */
    int KIND_NPC = 3;

    int sbs$scaleKind();

    void sbs$setScaleKind(int kind);

    /**
     * Registry id of the entity the state came from ({@code "minecraft:villager"}), or {@code null}
     * for states never tagged. Carried so the per-entity-type selection can be honoured without
     * re-deriving identity at submit time, where the entity is long gone.
     */
    String sbs$scaleTypeId();

    void sbs$setScaleTypeId(String typeId);
}
