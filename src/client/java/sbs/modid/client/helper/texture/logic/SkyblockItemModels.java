/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.logic;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The classic (pre-server-pack) look of every SkyBlock item: id → vanilla item model, and for
 * skull-based items id → the old head texture.
 *
 * <p>Since SkyBlock 0.26 Hypixel forces a resource pack and stamps every item with an
 * {@code item_model} component in the {@code hypixel_skyblock} namespace. With the pack blocked
 * (Texture Pack module → "Ignore Enforced Texture Packs") those model ids resolve to nothing and
 * every item renders as the pink/black missing texture. This map is what the item <i>used</i> to
 * look like, so the remap mixins can restore the vanilla visuals.
 *
 * <p>Data source: {@code sbs-skyblock-items.json}, a CC0-licensed id → {model, texture} mapping.
 * Loaded async at client init (~2.5 MB); lookups return {@code null} until it lands, which simply
 * leaves the original model untouched for a frame or two.
 */
public final class SkyblockItemModels {

    private static final String RESOURCE = "/sbs-skyblock-items.json";

    /** The namespace the enforced Hypixel pack stamps into item_model components. */
    public static final String HYPIXEL_NAMESPACE = "hypixel_skyblock";

    private static volatile Map<String, Identifier> models = Map.of();
    private static volatile Map<String, ResolvableProfile> skulls = Map.of();

    private SkyblockItemModels() {
    }

    /** Kicks off the async json load; call once from client init. */
    public static void start() {
        Thread thread = new Thread(SkyblockItemModels::load, "SBS-SkyblockItemModels");
        thread.setDaemon(true);
        thread.start();
    }

    private static void load() {
        try (InputStream in = SkyblockItemModels.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pack] {} missing from the jar", RESOURCE);
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Identifier> modelMap = new HashMap<>();
            Map<String, ResolvableProfile> skullMap = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject item = entry.getValue().getAsJsonObject();
                String model = item.has("model") ? item.get("model").getAsString() : null;
                if (model == null || model.isEmpty()) {
                    continue;
                }
                try {
                    modelMap.put(entry.getKey(), Identifier.parse(model));
                } catch (Exception ignored) {
                    continue;
                }
                String texture = item.has("texture") ? item.get("texture").getAsString() : null;
                if (texture != null && !texture.isEmpty()) {
                    skullMap.put(entry.getKey(), profile(entry.getKey(), texture));
                }
            }
            models = Map.copyOf(modelMap);
            skulls = Map.copyOf(skullMap);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pack] {} item models, {} skull textures loaded",
                    modelMap.size(), skullMap.size());
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Pack] item model map failed to load", t);
        }
    }

    /** A pre-resolved skull profile carrying the classic head texture. */
    private static ResolvableProfile profile(String skyblockId, String texture) {
        PropertyMap properties = new PropertyMap(
                ImmutableMultimap.of("textures", new Property("textures", texture)));
        GameProfile gameProfile = new GameProfile(
                UUID.nameUUIDFromBytes(("sbs:" + skyblockId).getBytes(StandardCharsets.UTF_8)),
                "SBSPack", properties);
        return ResolvableProfile.createResolved(gameProfile);
    }

    /** The vanilla model for a SkyBlock id, or {@code null} when unknown / not yet loaded. */
    public static Identifier modelFor(String skyblockId) {
        return skyblockId == null ? null : models.get(skyblockId);
    }

    /** The classic skull profile for a SkyBlock id (or its skin id), or {@code null}. */
    public static ResolvableProfile skullFor(String skyblockId) {
        return skyblockId == null ? null : skulls.get(skyblockId);
    }

    /** The SkyBlock id inside a stack's custom data (root or ExtraAttributes), or {@code null}. */
    public static String skyblockId(ItemStack stack) {
        CompoundTag tag = customData(stack);
        return tag == null ? null : idIn(tag, "id");
    }

    /** The applied-skin id ({@code ExtraAttributes.skin}), or {@code null}. */
    public static String skinId(ItemStack stack) {
        CompoundTag tag = customData(stack);
        return tag == null ? null : idIn(tag, "skin");
    }

    /** Whether the stack's custom data carries the given key anywhere we read ids from. */
    public static boolean hasCustomKey(ItemStack stack, String key) {
        CompoundTag tag = customData(stack);
        if (tag == null) {
            return false;
        }
        return tag.contains(key) || tag.getCompoundOrEmpty("ExtraAttributes").contains(key);
    }

    private static CompoundTag customData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Object component = stack.get(DataComponents.CUSTOM_DATA);
        return component instanceof CustomData data ? data.copyTag() : null;
    }

    /** Reads an id-ish key from the tag root, falling back to the legacy ExtraAttributes nesting. */
    private static String idIn(CompoundTag tag, String key) {
        String value = tag.getStringOr(key, "");
        if (value.isEmpty()) {
            value = tag.getCompoundOrEmpty("ExtraAttributes").getStringOr(key, "");
        }
        return value.isEmpty() ? null : value.replace(":", "-");
    }
}
