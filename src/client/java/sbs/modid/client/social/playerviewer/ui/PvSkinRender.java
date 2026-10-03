/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The viewed player's 3D model, drawn straight into a page's rectangle.
 *
 * <p>Vanilla's {@code PlayerSkinWidget} is the template for the drawing itself – bake the wide and
 * slim player layers once and hand one to {@code GuiGraphicsExtractor.skin}. It is deliberately
 * <b>not</b> a widget: the model belongs to one page inside a scrolling, page-switching panel, and a
 * Screen widget would keep rendering (and keep taking drags) after you navigated away.
 *
 * <p>The skin comes from {@link PlayerSkinRenderCache}, <b>not</b> from
 * {@code SkinManager.createLookup(GameProfile, boolean)}: SkinManager reads the texture property off
 * the profile it is handed, so a profile built from a bare uuid + name (all the viewer has) carries
 * no textures and silently renders Steve. {@link ResolvableProfile} is the piece that actually goes
 * and resolves the profile — the same path a player-head item takes, which is why those show real
 * skins. It resolves asynchronously and yields the default skin until it lands, so the box is never
 * empty.
 */
public final class PvSkinRender {

    /** Vanilla's framing constants, from {@code PlayerSkinWidget}. */
    private static final float MODEL_HEIGHT = 2.125f;
    private static final float FIT_SCALE = 0.97f;
    private static final float Y_OFFSET = -1.0625f;
    private static final float DEFAULT_ROTATION_X = -5.0f;
    private static final float DEFAULT_ROTATION_Y = 30.0f;
    private static final float ROTATION_X_LIMIT = 50.0f;
    private static final float ROTATION_SENSITIVITY = 2.5f;

    private final Model.Simple wideModel;
    private final Model.Simple slimModel;
    private final Supplier<PlayerSkinRenderCache.RenderInfo> skin;

    private float rotationX = DEFAULT_ROTATION_X;
    private float rotationY = DEFAULT_ROTATION_Y;

    private PvSkinRender(Supplier<PlayerSkinRenderCache.RenderInfo> skin) {
        var models = Minecraft.getInstance().getEntityModels();
        this.wideModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER),
                RenderTypes::entityTranslucent);
        this.slimModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM),
                RenderTypes::entityTranslucent);
        this.skin = skin;
    }

    /**
     * A renderer for {@code uuid} (the plain, undashed form the profile payload carries), falling
     * back to the name when the uuid is unusable, or null when neither identifies a player.
     */
    public static PvSkinRender of(String uuid, String name) {
        UUID id = parse(uuid);
        ResolvableProfile profile;
        if (id != null) {
            profile = ResolvableProfile.createUnresolved(id);
        } else if (name != null && !name.isBlank()) {
            profile = ResolvableProfile.createUnresolved(name);
        } else {
            return null;
        }
        return new PvSkinRender(
                Minecraft.getInstance().playerSkinRenderCache().createLookup(profile));
    }

    /** Hypixel/Mojang hand out UUIDs without dashes; {@link UUID#fromString} needs them. */
    private static UUID parse(String uuid) {
        if (uuid == null) {
            return null;
        }
        String plain = uuid.replace("-", "");
        if (plain.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(plain.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Draws the model to fill the given box, scaled to its height exactly as vanilla does. */
    public void render(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        PlayerSkin resolved = skin.get().playerSkin();
        Model.Simple model = resolved.model() == PlayerModelType.SLIM ? slimModel : wideModel;
        float scale = FIT_SCALE * h / MODEL_HEIGHT;
        g.skin(model, resolved.body().texturePath(), scale, rotationX, rotationY, Y_OFFSET,
                x, y, x + w, y + h);
    }

    /** Drag to spin the model, with vanilla's sensitivity and pitch clamp. */
    public void drag(double dragX, double dragY) {
        rotationX = Math.clamp(rotationX + (float) dragY * ROTATION_SENSITIVITY,
                -ROTATION_X_LIMIT, ROTATION_X_LIMIT);
        rotationY += (float) dragX * ROTATION_SENSITIVITY;
    }

    public void resetRotation() {
        rotationX = DEFAULT_ROTATION_X;
        rotationY = DEFAULT_ROTATION_Y;
    }
}
