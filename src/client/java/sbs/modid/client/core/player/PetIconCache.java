/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.player;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves pet icons for the profile viewer by fetching each pet type's skull texture directly from
 * the community item-data repository, on demand and cached. This deliberately does NOT depend on the recipe-repo
 * download having finished (which is what left pets as barriers/bones): the first time a pet type is
 * shown, its {@code items/<TYPE>;<rarity>.json} is fetched, the base64 texture is extracted and a
 * player-head {@link ItemStack} is built and cached; every later render is instant.
 *
 * <p>All fetches are async on a small daemon pool; while a texture is loading the caller renders a
 * placeholder. Types that 404 across all rarities (or fail) are remembered so they are not retried.
 */
public final class PetIconCache {

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/<PET>;<rarity>.json
// METHOD: GET
// PURPOSE: The skull texture for a pet the player has never opened the Pets menu for, so the
//   HUD card can show the right head instead of a bone.
// DATA SENT: Only the pet id and rarity, as the filename in the path. No headers, no body.
// DATA RECEIVED: Read-only public JSON for one item. A base64 skin value is matched out of it
//   with a regex; the JSON is never deserialized into an arbitrary type.
// SAFETY DECLARATION: A public read-only data repository over HTTPS, needing no
//   authentication. Nothing identifying is sent - no licence token, no uuid, no account
//   data, no OS telemetry - and the request is not gated on any consent switch because
//   it carries nothing about the user. Provenance and licensing: THIRD-PARTY.md,
//   "Game data sources". What leaves the machine at all: PRIVACY.md.
// ============================================================================
    private static final String RAW = "https://raw.githubusercontent.com/NotEnoughUpdates/"
            + "NotEnoughUpdates-REPO/master/items/";
    /** Pet files exist per rarity; try the common ones until one resolves. */
    private static final int[] RARITIES = {4, 5, 3, 6, 2, 1, 0};
    /**
     * The skull texture inside the RAW repo json: there the nbttag is a JSON string, so its quotes
     * arrive ESCAPED ({@code Value:\"base64\"}). Matching "Value" + any separators + a long base64
     * run covers both the escaped raw form and a decoded nbttag (verified against live repo files).
     */
    private static final Pattern TEXTURE_VALUE =
            Pattern.compile("Value[^A-Za-z0-9]+([A-Za-z0-9+/=]{60,})");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // MUST come after every constant above: the constructor reads TIMEOUT, and static
    // initializers run in declaration order (see the PriceApi static-init crash).
    private static final PetIconCache INSTANCE = new PetIconCache();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService io = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "SBS-PetIcon");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentHashMap<String, ItemStack> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    private PetIconCache() {
    }

    public static PetIconCache getInstance() {
        return INSTANCE;
    }

    /**
     * The pet's head icon, or {@code null} while it is still loading / unavailable (the caller then
     * draws a placeholder). Kicks off the async fetch on the first miss.
     */
    public ItemStack get(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        String key = type.toUpperCase(Locale.ROOT);
        ItemStack hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        if (!failed.contains(key) && inFlight.add(key)) {
            io.execute(() -> fetch(key));
        }
        return null;
    }

    /**
     * The pet's icon with a guaranteed result: the fetched skull texture first, then the item repo's
     * own {@code PET_<TYPE>} entry, and a bone when neither resolves.
     *
     * <p>This is the <b>default pet</b> - what gets drawn for a pet whose real menu stack we do not
     * hold (the tab list names a pet you never clicked, so its skin was never captured). Both the
     * profile viewer and the Active Pet HUD card go through here, so the same pet never ends up
     * looking like two different things in two places.
     */
    public ItemStack iconOrFallback(String type) {
        String key = type == null ? "" : type.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        ItemStack fetched = get(key);
        if (fetched != null) {
            return fetched;
        }
        ItemStack repo = SkyBlockItemIcons.getInstance().icon("PET_" + key, null, 1);
        return repo.is(Items.BARRIER) ? new ItemStack(Items.BONE) : repo;
    }

    private void fetch(String type) {
        try {
            for (int rarity : RARITIES) {
                String texture = tryRarity(type, rarity);
                if (texture != null) {
                    cache.put(type, SkyBlockItemIcons.playerHead(texture, "PET_" + type));
                    return;
                }
            }
            failed.add(type); // no rarity resolved – stop retrying
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PetIcon] No repo texture for pet {}.", type);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PetIcon] {} fetch failed: {}", type, e.toString());
            failed.add(type);
        } finally {
            inFlight.remove(type);
        }
    }

    /** Fetches one {@code <TYPE>;<rarity>.json} and returns its base64 texture, or {@code null}. */
    private String tryRarity(String type, int rarity) throws Exception {
        String file = URLEncoder.encode(type + ";" + rarity + ".json", StandardCharsets.UTF_8)
                .replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(RAW + file))
                .timeout(TIMEOUT).header("User-Agent", "SkyblockSimplifiedSBS").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        Matcher matcher = TEXTURE_VALUE.matcher(response.body());
        return matcher.find() ? matcher.group(1) : null;
    }
}
