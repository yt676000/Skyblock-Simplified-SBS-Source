/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import sbs.modid.client.helper.visual.model.EntityScaleKind;

/**
 * Attaches the {@link EntityScaleKind} tag to every entity render state – the state has no entity
 * identity of its own, so {@code EntityScaleMixin} records "self / other player / other entity"
 * here during extraction and reads it back when applying the per-axis entity scale.
 */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityScaleKind,
        sbs.modid.client.helper.visual.transparency.OwnPlayerAlpha {

    /** Own Player Transparency: the fade decided at extraction (255 = untouched). */
    @Unique
    private int sbs$ownAlpha = 255;

    @Override
    public int sbs$ownAlpha() {
        return sbs$ownAlpha;
    }

    @Override
    public void sbs$setOwnAlpha(int alpha) {
        this.sbs$ownAlpha = alpha;
    }

    @Unique
    private int sbs$scaleKind = KIND_OTHER_ENTITY;

    @Unique
    private String sbs$scaleTypeId;

    @Override
    public int sbs$scaleKind() {
        return sbs$scaleKind;
    }

    @Override
    public void sbs$setScaleKind(int kind) {
        this.sbs$scaleKind = kind;
    }

    @Override
    public String sbs$scaleTypeId() {
        return sbs$scaleTypeId;
    }

    @Override
    public void sbs$setScaleTypeId(String typeId) {
        this.sbs$scaleTypeId = typeId;
    }
}
