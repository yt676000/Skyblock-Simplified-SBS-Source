/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static sbs.modid.client.social.playerviewer.logic.PvValidator.NAME_LEN;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.num;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.obj;
import static sbs.modid.client.social.playerviewer.logic.PvValidator.str;

/**
 * The two Hypixel resources the old server joined into the view: the accessory catalogue (from the
 * items resource) and the collections resource. Either may be empty, in which case the matching
 * page shows "n/a" exactly like the old server did.
 *
 * @param accessories id -> catalogue entry, in catalogue order
 * @param collections the collection categories, in resource order
 * @param ladders     accessory id -> its rung on an upgrade ladder, for the ids that sit on one.
 *                    Hypixel publishes no ladders; they come from the bundled accessory catalogue,
 *                    the same data the Missing Accessories screen reads. Empty means "no ladder
 *                    knowledge", never "nothing is an upgrade of anything".
 */
public record PvResources(Map<String, Accessory> accessories, List<CollectionCategory> collections,
                          Map<String, Rung> ladders) {

    public static final PvResources EMPTY = new PvResources(Map.of(), List.of(), Map.of());

    public PvResources(Map<String, Accessory> accessories, List<CollectionCategory> collections) {
        this(accessories, collections, Map.of());
    }

    public record Accessory(String name, String tier) {
    }

    /** One accessory's place on an upgrade ladder: its family and its step, ascending. */
    public record Rung(String family, int step) {
    }

    public record Tier(double tier, double amountRequired) {
    }

    public record CollectionItem(String id, String name, double maxTiers, List<Tier> tiers) {
    }

    public record CollectionCategory(String key, String name, List<CollectionItem> items) {
    }

    /** Parses {@code /v2/resources/skyblock/collections} under the usual limits. */
    public static List<CollectionCategory> parseCollections(String body) throws PvInvalidDataException {
        JsonElement root = PvJson.parse(body);
        if (!root.isJsonObject()) {
            throw new PvInvalidDataException("type:root");
        }
        JsonObject cats = obj(root.getAsJsonObject(), "collections");
        List<CollectionCategory> out = new ArrayList<>();
        if (cats == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> cat : cats.entrySet()) {
            if (!cat.getValue().isJsonObject()) {
                throw new PvInvalidDataException("type:collection");
            }
            JsonObject body0 = cat.getValue().getAsJsonObject();
            List<CollectionItem> items = new ArrayList<>();
            JsonObject rawItems = obj(body0, "items");
            if (rawItems != null) {
                for (Map.Entry<String, JsonElement> it : rawItems.entrySet()) {
                    if (!it.getValue().isJsonObject()) {
                        throw new PvInvalidDataException("type:collection_item");
                    }
                    JsonObject meta = it.getValue().getAsJsonObject();
                    List<Tier> tiers = new ArrayList<>();
                    JsonArray rawTiers = PvValidator.arr(meta, "tiers");
                    if (rawTiers != null) {
                        for (JsonElement t : rawTiers) {
                            if (!t.isJsonObject()) {
                                throw new PvInvalidDataException("type:tier");
                            }
                            Double tier = num(t.getAsJsonObject(), "tier");
                            Double req = num(t.getAsJsonObject(), "amountRequired");
                            tiers.add(new Tier(tier == null ? 0 : tier, req == null ? 0 : req));
                        }
                    }
                    Double max = num(meta, "maxTiers");
                    items.add(new CollectionItem(it.getKey(), str(meta, "name", NAME_LEN),
                            max == null ? 0 : max, tiers));
                }
            }
            out.add(new CollectionCategory(cat.getKey(), str(body0, "name", NAME_LEN), items));
        }
        return out;
    }

    /** Builds the accessory map from (id, name, tier) triples, keeping their order. */
    public static Map<String, Accessory> accessories(Iterable<String[]> idNameTier) {
        Map<String, Accessory> out = new LinkedHashMap<>();
        for (String[] e : idNameTier) {
            if (e[0] != null && !e[0].isEmpty()) {
                out.put(e[0], new Accessory(e[1], e[2]));
            }
        }
        return out;
    }
}
