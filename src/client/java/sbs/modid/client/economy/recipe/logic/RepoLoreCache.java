/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-demand cache of an item's full repo lore (the in-game description: stats, enchant slots,
 * ability, rarity), used to make the Recipe Viewer tooltips show the real item tooltip instead of
 * just the name + rarity line. The first time an item is hovered its {@code items/<ID>.json} is
 * fetched, the {@code lore} array is cached, and the tooltip fills in on the next frame – exactly
 * the pattern the profile viewer uses for pet skulls, independent of the recipe-repo download.
 */
public final class RepoLoreCache {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/<ITEM>.json
// METHOD: GET
// PURPOSE: The tooltip lines of an item the player is not holding, so the Recipe Viewer can
//   show what a crafting result actually looks like.
// DATA SENT: Only the item id, as the filename in the path. No headers, no body.
// DATA RECEIVED: Read-only public JSON for one item. Read key by key with Gson's JsonParser.
// SAFETY DECLARATION: A public read-only data repository over HTTPS, needing no
//   authentication. Nothing identifying is sent - no licence token, no uuid, no account
//   data, no OS telemetry - and the request is not gated on any consent switch because
//   it carries nothing about the user. Provenance and licensing: THIRD-PARTY.md,
//   "Game data sources". What leaves the machine at all: PRIVACY.md.
// ============================================================================
    private static final String RAW = "https://raw.githubusercontent.com/NotEnoughUpdates/"
            + "NotEnoughUpdates-REPO/master/items/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> EMPTY = List.of();

    private static final RepoLoreCache INSTANCE = new RepoLoreCache();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SBS-RepoLore");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, List<String>> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private RepoLoreCache() {
    }

    public static RepoLoreCache getInstance() {
        return INSTANCE;
    }

    /** The cached lore lines for an item, or {@code null} while it is still loading. */
    public List<String> get(String id) {
        if (id == null || id.isEmpty()) {
            return EMPTY;
        }
        String key = id.toUpperCase(Locale.ROOT);
        List<String> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        if (inFlight.add(key)) {
            io.execute(() -> fetch(key));
        }
        return null;
    }

    private void fetch(String id) {
        try {
            String file = URLEncoder.encode(id + ".json", StandardCharsets.UTF_8).replace("+", "%20");
            HttpRequest request = HttpRequest.newBuilder(URI.create(RAW + file))
                    .timeout(TIMEOUT).header("User-Agent", "SkyblockSimplifiedSBS").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            List<String> lore = EMPTY;
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("lore") && json.get("lore").isJsonArray()) {
                    JsonArray arr = json.getAsJsonArray("lore");
                    List<String> lines = new ArrayList<>(arr.size());
                    for (var element : arr) {
                        lines.add(element.getAsString());
                    }
                    lore = lines;
                }
            }
            cache.put(id, lore);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][RepoLore] {} fetch failed: {}", id, e.toString());
            cache.put(id, EMPTY); // don't hammer a failing id
        } finally {
            inFlight.remove(id);
        }
    }

    /**
     * The full Recipe-Viewer tooltip for an item: its colored name plus the real repo lore when
     * cached (name + rarity line as a fallback while it loads). Callers append their own hints.
     */
    public static List<Component> buildTooltip(ItemStack icon, String id) {
        // A pet: the real pet tooltip at its max level and highest rarity (PetData), built from the
        // repo's per-rarity lore and stat table - the plain repo lore is a template full of {LVL}.
        if (SkyBlockRepoRecipeProvider.isPetKey(id)) {
            List<String> pet = PetData.tooltip(id, PetData.defaults(id));
            if (!pet.isEmpty()) {
                List<Component> lines = new ArrayList<>(pet.size());
                for (String line : pet) {
                    lines.add(Component.literal(line));
                }
                return lines;
            }
        }
        List<Component> vanilla = Screen.getTooltipFromItem(Minecraft.getInstance(), icon);
        List<Component> out = new ArrayList<>();
        if (!vanilla.isEmpty()) {
            out.add(vanilla.get(0)); // the item's (colored) display name
        }
        List<String> lore = getInstance().get(id);
        if (lore != null && !lore.isEmpty()) {
            for (String line : lore) {
                out.add(Component.literal(line));
            }
        } else {
            // Still loading (null) or no repo lore (empty): keep the catalogue's rarity line.
            for (int i = 1; i < vanilla.size(); i++) {
                out.add(vanilla.get(i));
            }
        }
        return out;
    }
}
