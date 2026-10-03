/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learns the player's minion state from what the client can already see - no network, no keys:
 *
 * <ul>
 *   <li><b>The Crafted Minions menu</b> ({@code /craftedminions}): each entry's per-tier check
 *       marks, and the info item's own "Minions limit: N" line - the authoritative slot count.</li>
 *   <li><b>An open minion GUI</b> ("Snow Minion V"): fuel, hopper and upgrade slots, read at the
 *       positions the menu actually uses (skin 10, fuel 19, shipping 28, upgrades 37/46) and
 *       validated by item id, so a reshuffled menu degrades to "nothing learned", never to junk.</li>
 *   <li><b>The island's armor stands</b>: every minion is a small stand wearing a tier-specific
 *       skull, and the items catalog knows every tier's skin - so placed minions (type AND tier)
 *       are readable by texture without opening a single GUI. Stands wearing a cosmetic minion
 *       skin match nothing and are counted as "unknown" rather than guessed.</li>
 * </ul>
 *
 * <p>Driven from the client tick; menu reads are gated on the menu's own state id (the BitsShop
 * pattern), the stand scan on a 5s throttle and the Private Island. All results land in
 * {@link MinionStateStore}.
 */
public final class MinionStateCapture {

    /** Hypixel's page prefix on paginated menus: "(1/3) Crafted Minions". */
    private static final Pattern PAGE_PREFIX = Pattern.compile("^\\(\\d+/\\d+\\)\\s*");

    private static final Pattern MINION_TITLE = Pattern.compile("^(.+) Minion ([IVX]+)$");
    /** Both glyph families, in case the menu's check marks differ from the documented ones. */
    private static final Pattern TIER_LINE = Pattern.compile("([✔✓✖✗✘])\\s*Tier\\s+([IVX]+)");
    private static final Pattern LIMIT_LINE = Pattern.compile("Minions limit:\\s*(\\d+)");
    private static final Pattern SKIN_URL = Pattern.compile("\"url\"\\s*:\\s*\"[^\"]*/([0-9a-f]+)\"");

    /** Minion GUI modifier positions, verified against the menu's real layout. */
    private static final int SLOT_FUEL = 19;
    private static final int SLOT_SHIPPING = 28;
    private static final int[] SLOT_UPGRADES = {37, 46};

    private static final long SCAN_INTERVAL_MS = 5_000L;
    private static final double SCAN_RADIUS = 128;

    private static final Map<String, Integer> ROMAN = buildRoman();

    private static Screen learnedScreen;
    private static int learnedState = -1;

    private static long lastScanAt;
    private static boolean wasOnIsland;

    /** Texture hash -> generator item id, built lazily from the items catalog's skins. */
    private static volatile Map<String, String> skinIndex = Map.of();

    private MinionStateCapture() {
    }

    /** Called once per client tick (from the shared GUI tracking hook). */
    public static void tick(Minecraft minecraft) {
        try {
            learnMenus();
            scanIsland(minecraft);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Minions] capture failed: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------ menus

    private static void learnMenus() {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        int stateId = container.getMenu().getStateId();
        if (learnedScreen == screen && learnedState == stateId) {
            return;
        }
        learnedScreen = screen;
        learnedState = stateId;

        // Hypixel prefixes paginated menus with their page: the Crafted Minions menu is really
        // titled "(1/3) Crafted Minions", which is what defeated a startsWith on the plain name.
        String title = PAGE_PREFIX.matcher(
                plain(screen.getTitle() == null ? "" : screen.getTitle().getString()).trim())
                .replaceFirst("").trim();
        if (title.startsWith("Crafted Minions")) {
            learnCraftedMenu(container);
            return;
        }
        Matcher minion = MINION_TITLE.matcher(title);
        if (minion.matches()) {
            learnMinionGui(container, minion.group(1), roman(minion.group(2)));
        }
    }

    /** One Crafted Minions page: per-type check marks + the info item's slot limit. */
    private static void learnCraftedMenu(AbstractContainerScreen<?> container) {
        Set<String> crafted = new LinkedHashSet<>();
        boolean sawAnyEntry = false;
        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = plain(stack.getHoverName().getString()).trim();
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) {
                continue;
            }
            if (name.endsWith(" Minion")) {
                MinionData.Minion minion = byName(name.substring(0, name.length() - " Minion".length()));
                if (minion == null) {
                    continue;
                }
                sawAnyEntry = true;
                for (Component line : lore.lines()) {
                    Matcher tier = TIER_LINE.matcher(line.getString());
                    if (tier.find() && ("✔".equals(tier.group(1)) || "✓".equals(tier.group(1)))) {
                        crafted.add(minion.type + "_" + roman(tier.group(2)));
                    }
                }
            } else {
                for (Component line : lore.lines()) {
                    Matcher limit = LIMIT_LINE.matcher(plain(line.getString()));
                    if (limit.find()) {
                        MinionStateStore.getInstance().recordMinionsLimit(
                                Integer.parseInt(limit.group(1)));
                    }
                }
            }
        }
        if (sawAnyEntry) {
            MinionStateStore store = MinionStateStore.getInstance();
            store.recordCrafted(crafted);
            // One line per page read: the only way to tell "menu never seen" from "menu seen and
            // nothing was crafted" when the slot line looks wrong.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Minions] crafted menu page: {} tier(s) checked, {} known total, limit {}",
                    crafted.size(), store.uniqueCraftCount(), store.minionsLimit());
        }
    }

    /** One open minion GUI: the modifier slots a stand scan cannot see. */
    private static void learnMinionGui(AbstractContainerScreen<?> container, String name, int tier) {
        MinionData.Minion minion = byName(name);
        if (minion == null || tier <= 0) {
            return;
        }
        // Only this profile's own island: a minion GUI cannot open anywhere else, but the gate
        // costs nothing and protects the store from any menu that merely apes the title.
        if (!SkyBlockLocation.matches("Private Island")) {
            return;
        }
        var slots = container.getMenu().slots;
        if (slots.size() < 54) {
            return;
        }
        MinionStateStore.Config config = new MinionStateStore.Config();
        config.type = minion.type;
        config.tier = tier;
        config.fuelId = modifierId(slots.get(SLOT_FUEL).getItem());
        config.hopperId = modifierId(slots.get(SLOT_SHIPPING).getItem());
        for (int index : SLOT_UPGRADES) {
            String id = modifierId(slots.get(index).getItem());
            if (!id.isEmpty()) {
                config.upgradeIds.add(id);
            }
        }
        MinionStateStore.getInstance().recordConfig(config);
    }

    /** A modifier slot's SkyBlock id; empty for the placeholder panes and empty slots. */
    private static String modifierId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String id = SkyblockItem.id(stack);
        // The empty-slot placeholders are stained glass panes with no SkyBlock id.
        return id == null ? "" : id;
    }

    // ------------------------------------------------------------------ island scan

    private static void scanIsland(Minecraft minecraft) {
        boolean onIsland = SkyBlockLocation.matches("Private Island");
        if (onIsland && !wasOnIsland) {
            // A fresh visit: the next scan becomes the placed baseline, so picked-up minions
            // actually disappear from the records instead of lingering forever.
            MinionStateStore.getInstance().beginVisit();
        }
        wasOnIsland = onIsland;
        if (!onIsland || minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        Map<String, String> index = skinIndexOrBuild();
        if (index.isEmpty()) {
            return; // items catalog not loaded yet - next scan retries
        }
        Map<String, Map<Integer, Integer>> counts = new HashMap<>();
        int unknown = 0;
        AABB area = minecraft.player.getBoundingBox().inflate(SCAN_RADIUS);
        // One pass over every stand in range, partitioned as it goes. A minion is small and wears a
        // player head; a hologram is a named stand and never both, so the two sets are disjoint and
        // the stoppage check costs a name read per stand rather than a second world query.
        List<MinionStoppages.MinionStand> minions = new ArrayList<>();
        List<MinionStoppages.Hologram> holograms = new ArrayList<>();
        for (ArmorStand stand : minecraft.level.getEntitiesOfClass(ArmorStand.class, area,
                ArmorStand::isAlive)) {
            if (!looksLikeMinion(stand)) {
                Component name = stand.getCustomName();
                if (name != null) {
                    String text = plain(name.getString()).trim();
                    if (!text.isEmpty()) {
                        holograms.add(new MinionStoppages.Hologram(text, stand.getX(), stand.getY(), stand.getZ()));
                    }
                }
                continue;
            }
            String hash = headTextureHash(stand);
            if (hash == null) {
                continue;
            }
            String generatorId = index.get(hash);
            if (generatorId == null) {
                unknown++;
                continue;
            }
            // "SNOW_GENERATOR_5" -> type SNOW, tier 5.
            int marker = generatorId.lastIndexOf("_GENERATOR_");
            if (marker <= 0) {
                continue;
            }
            String type = generatorId.substring(0, marker);
            int tier;
            try {
                tier = Integer.parseInt(generatorId.substring(marker + "_GENERATOR_".length()));
            } catch (NumberFormatException e) {
                continue;
            }
            counts.computeIfAbsent(type, key -> new HashMap<>())
                    .merge(tier, 1, Integer::sum);
            minions.add(new MinionStoppages.MinionStand(stand.blockPosition().asLong(), stand.getX(), stand.getY(),
                    stand.getZ(), type, tier));
        }
        MinionStoppages.record(minions, holograms);
        if (!counts.isEmpty() || unknown > 0) {
            MinionStateStore store = MinionStateStore.getInstance();
            int before = store.placedTotal();
            store.recordScan(counts, unknown);
            int after = store.placedTotal();
            if (after != before) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Minions] island scan: {} placed minion(s) matched, {} unmatched head(s)",
                        after, unknown);
            }
        }
    }

    /** Minions are small armor stands wearing a player head. */
    private static boolean looksLikeMinion(ArmorStand stand) {
        if (!stand.isAlive() || !stand.isSmall()) {
            return false;
        }
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty()
                && BuiltInRegistries.ITEM.getKey(head.getItem()).getPath().equals("player_head");
    }

    /** The texture hash of a stand's head skin, or {@code null} when it carries none. */
    private static String headTextureHash(ArmorStand stand) {
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        var profile = head.get(DataComponents.PROFILE);
        if (profile == null) {
            return null;
        }
        var textures = profile.partialProfile().properties().get("textures");
        for (var property : textures) {
            String hash = textureHash(property.value());
            if (hash != null) {
                return hash;
            }
        }
        return null;
    }

    /**
     * Base64 skin payload -> the texture's own hash (the last path segment of its URL). Compared
     * by hash rather than the whole URL because payloads differ in protocol and timestamps while
     * the hash is the texture's identity.
     */
    private static String textureHash(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            Matcher matcher = SKIN_URL.matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** hash -> generator item id, from the items catalog's per-tier skins. Built once, lazily. */
    private static Map<String, String> skinIndexOrBuild() {
        Map<String, String> index = skinIndex;
        if (!index.isEmpty()) {
            return index;
        }
        Map<String, String> built = new HashMap<>();
        for (SkyBlockItemCatalog.Entry entry : SkyBlockItemCatalog.getInstance().allSorted()) {
            if (entry.skinValue == null || !entry.id.contains("_GENERATOR_")) {
                continue;
            }
            String hash = textureHash(entry.skinValue);
            if (hash != null) {
                built.put(hash, entry.id.toUpperCase(Locale.ROOT));
            }
        }
        if (!built.isEmpty()) {
            skinIndex = Map.copyOf(built);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Minions] skin index ready: {} tier textures",
                    built.size());
        }
        return skinIndex;
    }

    // ------------------------------------------------------------------ shared

    private static final Set<String> WARNED_NAMES = new HashSet<>();

    /** The catalog entry for a display name ("Snow"), or {@code null} (warned once per name). */
    private static MinionData.Minion byName(String name) {
        MinionData data = MinionCatalogs.minions();
        if (data == null) {
            return null;
        }
        for (MinionData.Minion minion : data.minions) {
            if (minion.name.equalsIgnoreCase(name)) {
                return minion;
            }
        }
        if (WARNED_NAMES.add(name)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Minions] unknown minion name in menu: {}", name);
        }
        return null;
    }

    private static int roman(String numeral) {
        Integer value = ROMAN.get(numeral.toUpperCase(Locale.ROOT));
        return value == null ? 0 : value;
    }

    private static Map<String, Integer> buildRoman() {
        Map<String, Integer> map = new HashMap<>();
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"};
        for (int i = 0; i < numerals.length; i++) {
            map.put(numerals[i], i + 1);
        }
        return map;
    }

    private static String plain(String text) {
        return text == null ? "" : text.replaceAll(String.valueOf((char) 0x00A7) + ".", "");
    }
}
