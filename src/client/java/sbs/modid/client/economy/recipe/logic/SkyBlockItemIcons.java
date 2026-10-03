/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.item.Rarity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves every SkyBlock / vanilla item to a renderable {@link ItemStack} icon – the fix for
 * custom items showing as Barrier blocks.
 *
 * <p>Two real causes of unresolvable icons are handled here:
 * <ul>
 *   <li><b>Legacy materials</b> – the recipe database uses 1.8-era ids ("skull", "log", "wood_sword")
 *       that no longer exist in the modern registry; {@link #LEGACY} maps them to modern paths.</li>
 *   <li><b>Custom looks</b> – the live Hypixel item catalogue
 *       ({@code /v2/resources/skyblock/items}) describes how every item really looks, and all of it
 *       is applied here: the base64 skull skin as a {@code minecraft:profile} component
 *       ({@link ResolvableProfile}), the custom model id of modern items as
 *       {@code minecraft:item_model}, the leather tint as {@code minecraft:dyed_color} and the
 *       permanent glint as {@code minecraft:enchantment_glint_override}. The item-data repo is only
 *       a fallback for the handful of items that catalogue misses.</li>
 * </ul>
 *
 * <p>Icons are built once and cached ({@link ConcurrentHashMap}); {@link #prewarm()} builds the whole
 * registry up-front on the catalogue's background thread (stack building is pure data – no GL), so
 * the first Recipe Viewer open is already fast. Only successful (non-barrier) resolutions are cached,
 * so items resolve correctly once the catalogue finishes loading.
 */
public final class SkyBlockItemIcons {

    private static final SkyBlockItemIcons INSTANCE = new SkyBlockItemIcons();

    /** Legacy (1.8-era) material path → modern registry path. */
    private static final Map<String, String> LEGACY = new HashMap<>();

    static {
        LEGACY.put("skull", "player_head");
        LEGACY.put("skull_item", "player_head");
        LEGACY.put("ink_sack", "ink_sac");
        LEGACY.put("dye", "ink_sac");
        LEGACY.put("wool", "white_wool");
        LEGACY.put("log", "oak_log");
        LEGACY.put("log_2", "acacia_log");
        LEGACY.put("log2", "acacia_log");
        LEGACY.put("planks", "oak_planks");
        LEGACY.put("wood", "oak_planks");
        LEGACY.put("sapling", "oak_sapling");
        LEGACY.put("leaves", "oak_leaves");
        LEGACY.put("leaves_2", "acacia_leaves");
        LEGACY.put("fence", "oak_fence");
        LEGACY.put("wood_step", "oak_slab");
        LEGACY.put("wooden_slab", "oak_slab");
        LEGACY.put("wood_slab", "oak_slab");
        LEGACY.put("stone_slab", "smooth_stone_slab");
        LEGACY.put("stone_slab2", "red_sandstone_slab");
        LEGACY.put("double_stone_slab", "smooth_stone");
        LEGACY.put("stained_glass", "white_stained_glass");
        LEGACY.put("stained_glass_pane", "white_stained_glass_pane");
        LEGACY.put("stained_clay", "white_terracotta");
        LEGACY.put("stained_hardened_clay", "white_terracotta");
        LEGACY.put("hard_clay", "terracotta");
        LEGACY.put("hardened_clay", "terracotta");
        LEGACY.put("fish", "cod");
        LEGACY.put("raw_fish", "cod");
        LEGACY.put("cooked_fish", "cooked_cod");
        LEGACY.put("reeds", "sugar_cane");
        LEGACY.put("sugar_canes", "sugar_cane");
        LEGACY.put("waterlily", "lily_pad");
        LEGACY.put("water_lily", "lily_pad");
        LEGACY.put("snow_layer", "snow");
        LEGACY.put("boat", "oak_boat");
        LEGACY.put("firework", "firework_rocket");
        LEGACY.put("fireball", "fire_charge");
        LEGACY.put("speckled_melon", "glistering_melon_slice");
        LEGACY.put("melon", "melon_slice");
        LEGACY.put("melon_block", "melon");
        LEGACY.put("potato_item", "potato");
        LEGACY.put("carrot_item", "carrot");
        LEGACY.put("nether_stalk", "nether_wart");
        LEGACY.put("nether_star", "nether_star");
        LEGACY.put("mycel", "mycelium");
        LEGACY.put("ender_stone", "end_stone");
        LEGACY.put("thin_glass", "glass_pane");
        LEGACY.put("iron_fence", "iron_bars");
        LEGACY.put("smooth_brick", "stone_bricks");
        LEGACY.put("book_and_quill", "writable_book");
        // Bukkit material names that differ from the modern registry path.
        LEGACY.put("redstone_comparator", "comparator");
        LEGACY.put("redstone_torch_on", "redstone_torch");
        LEGACY.put("redstone_lamp_off", "redstone_lamp");
        LEGACY.put("diode", "repeater");
        LEGACY.put("command", "command_block");
        LEGACY.put("workbench", "crafting_table");
        LEGACY.put("storage_minecart", "chest_minecart");
        LEGACY.put("powered_minecart", "furnace_minecart");
        LEGACY.put("explosive_minecart", "tnt_minecart");
        LEGACY.put("carrot_stick", "carrot_on_a_stick");
        LEGACY.put("empty_map", "map");
        LEGACY.put("mob_spawner", "spawner");
        LEGACY.put("exp_bottle", "experience_bottle");
        LEGACY.put("eye_of_ender", "ender_eye");
        LEGACY.put("grilled_pork", "cooked_porkchop");
        LEGACY.put("pork", "porkchop");
        LEGACY.put("raw_beef", "beef");
        LEGACY.put("raw_chicken", "chicken");
        LEGACY.put("sulphur", "gunpowder");
        LEGACY.put("seeds", "wheat_seeds");
        // Remaining Bukkit 1.8 names actually served by the Hypixel items API (verified against
        // /v2/resources/skyblock/items): doors, plates, stairs, records, horse armor, misc blocks.
        LEGACY.put("leash", "lead");
        LEGACY.put("wood_door", "oak_door");
        LEGACY.put("wooden_door", "oak_door");
        LEGACY.put("brewing_stand_item", "brewing_stand");
        LEGACY.put("cauldron_item", "cauldron");
        LEGACY.put("flower_pot_item", "flower_pot");
        LEGACY.put("bed", "red_bed");
        LEGACY.put("bed_block", "red_bed");
        LEGACY.put("banner", "white_banner");
        LEGACY.put("carpet", "white_carpet");
        LEGACY.put("wood_stairs", "oak_stairs");
        LEGACY.put("birch_wood_stairs", "birch_stairs");
        LEGACY.put("spruce_wood_stairs", "spruce_stairs");
        LEGACY.put("jungle_wood_stairs", "jungle_stairs");
        LEGACY.put("smooth_stairs", "stone_brick_stairs");
        LEGACY.put("step", "smooth_stone_slab");
        LEGACY.put("double_step", "smooth_stone");
        LEGACY.put("stone_plate", "stone_pressure_plate");
        LEGACY.put("wood_plate", "oak_pressure_plate");
        LEGACY.put("gold_plate", "light_weighted_pressure_plate");
        LEGACY.put("iron_plate", "heavy_weighted_pressure_plate");
        LEGACY.put("wood_button", "oak_button");
        LEGACY.put("trap_door", "oak_trapdoor");
        LEGACY.put("sign", "oak_sign");
        LEGACY.put("sign_post", "oak_sign");
        LEGACY.put("wall_sign", "oak_sign");
        LEGACY.put("fence_gate", "oak_fence_gate");
        LEGACY.put("rails", "rail");
        LEGACY.put("grass", "grass_block");
        LEGACY.put("long_grass", "short_grass");
        LEGACY.put("red_rose", "poppy");
        LEGACY.put("yellow_flower", "dandelion");
        LEGACY.put("double_plant", "sunflower");
        LEGACY.put("web", "cobweb");
        LEGACY.put("piston_base", "piston");
        LEGACY.put("piston_sticky_base", "sticky_piston");
        LEGACY.put("enchantment_table", "enchanting_table");
        LEGACY.put("ender_portal_frame", "end_portal_frame");
        LEGACY.put("monster_egg", "pig_spawn_egg");
        LEGACY.put("monster_eggs", "infested_stone");
        LEGACY.put("nether_brick", "nether_bricks");
        LEGACY.put("nether_brick_item", "nether_brick");
        LEGACY.put("nether_fence", "nether_brick_fence");
        LEGACY.put("quartz_ore", "nether_quartz_ore");
        LEGACY.put("snow_ball", "snowball");
        LEGACY.put("watch", "clock");
        LEGACY.put("brick", "bricks");
        LEGACY.put("clay_brick", "brick");
        LEGACY.put("huge_mushroom_1", "brown_mushroom_block");
        LEGACY.put("huge_mushroom_2", "red_mushroom_block");
        LEGACY.put("cobble_wall", "cobblestone_wall");
        LEGACY.put("firework_charge", "firework_star");
        LEGACY.put("iron_barding", "iron_horse_armor");
        LEGACY.put("gold_barding", "golden_horse_armor");
        LEGACY.put("diamond_barding", "diamond_horse_armor");
        LEGACY.put("gold_record", "music_disc_13");
        LEGACY.put("green_record", "music_disc_cat");
        LEGACY.put("record_3", "music_disc_blocks");
        LEGACY.put("record_4", "music_disc_chirp");
        LEGACY.put("record_5", "music_disc_far");
        LEGACY.put("record_6", "music_disc_mall");
        LEGACY.put("record_7", "music_disc_mellohi");
        LEGACY.put("record_8", "music_disc_stal");
        LEGACY.put("record_9", "music_disc_strad");
        LEGACY.put("record_10", "music_disc_ward");
        LEGACY.put("record_11", "music_disc_11");
        LEGACY.put("record_12", "music_disc_wait");
        LEGACY.put("soil", "farmland");
        LEGACY.put("crops", "wheat");
        LEGACY.put("sugar_cane_block", "sugar_cane");
        LEGACY.put("cocoa", "cocoa_beans");
        LEGACY.put("totem", "totem_of_undying");
        LEGACY.put("chorus_fruit_popped", "popped_chorus_fruit");
        LEGACY.put("dragons_breath", "dragon_breath");
        LEGACY.put("end_bricks", "end_stone_bricks");
        LEGACY.put("grass_path", "dirt_path");
        LEGACY.put("magma", "magma_block");
        LEGACY.put("red_nether_brick", "red_nether_bricks");
        // Tools / armor renames (wood_ → wooden_, gold_ → golden_, spade → shovel).
        for (String kind : new String[]{"sword", "pickaxe", "axe", "hoe"}) {
            LEGACY.put("wood_" + kind, "wooden_" + kind);
            LEGACY.put("gold_" + kind, "golden_" + kind);
        }
        LEGACY.put("wood_spade", "wooden_shovel");
        LEGACY.put("gold_spade", "golden_shovel");
        LEGACY.put("iron_spade", "iron_shovel");
        LEGACY.put("stone_spade", "stone_shovel");
        LEGACY.put("diamond_spade", "diamond_shovel");
        for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            LEGACY.put("gold_" + piece, "golden_" + piece);
        }
    }

    /** Icon cache keyed by SkyBlock id (or material for plain vanilla refs). Successes only. */
    private final Map<String, ItemStack> cache = new ConcurrentHashMap<>();

    /**
     * When each unresolvable key last failed to build. A failure is never cached as a result – the
     * item must still resolve the moment the catalogue finishes loading – but without this stamp a
     * grid full of not-yet-resolvable icons re-ran the whole {@link #build} pipeline for every cell
     * on every frame, which is exactly the hitch felt when opening the Recipe Viewer early. A failed
     * key is simply not retried for {@link #FAILED_RETRY_MS}, then resolves as before.
     */
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private static final long FAILED_RETRY_MS = 500;

    private SkyBlockItemIcons() {
    }

    public static SkyBlockItemIcons getInstance() {
        return INSTANCE;
    }

    /** The renderable icon for an item ref; barrier only when nothing at all resolves. */
    public ItemStack icon(String skyblockId, String material, int count) {
        String key = skyblockId != null && !skyblockId.isEmpty() ? skyblockId : ("mat:" + material);
        ItemStack cached = cache.get(key);
        if (cached != null) {
            return cached.copyWithCount(Math.max(1, count));
        }
        // Recently failed: don't re-run the resolution pipeline every frame, retry shortly.
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < FAILED_RETRY_MS) {
            return new ItemStack(Items.BARRIER, Math.max(1, count));
        }
        ItemStack built;
        try {
            built = build(skyblockId, material);
        } catch (Exception e) {
            // One malformed entry must never take the render loop down – barrier and move on.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Icons] Icon build failed for {} / {}: {}",
                    skyblockId, material, e.toString());
            built = null;
        }
        if (built != null) {
            cache.put(key, built);
            failedAt.remove(key);
            return built.copyWithCount(Math.max(1, count));
        }
        failedAt.put(key, System.currentTimeMillis());
        return new ItemStack(Items.BARRIER, Math.max(1, count));
    }

    /**
     * The icon as a <b>shared, read-only</b> stack for pure rendering ({@code g.item} /
     * {@code g.itemDecorations} never mutate their stack). Skips the per-call {@code copyWithCount}
     * of {@link #icon} – the dominant per-frame cost of a full icon grid. Callers that modify the
     * returned stack (dye, skin, custom name) must keep using {@link #icon}, which copies.
     */
    public ItemStack iconShared(String skyblockId, String material, int count) {
        String key = skyblockId != null && !skyblockId.isEmpty() ? skyblockId : ("mat:" + material);
        ItemStack cached = cache.get(key);
        if (cached != null && cached.getCount() == Math.max(1, count)) {
            return cached;
        }
        return icon(skyblockId, material, count);
    }

    /**
     * Clears the icon cache so every icon re-resolves – called when the repo appearance index
     * arrives after icons were already built (they cached e.g. paper instead of the real skull).
     */
    public void invalidate() {
        cache.clear();
        failedAt.clear();
    }

    /** Set by each catalogue publish; cleared when {@link #tick} starts the prewarm it asks for. */
    private volatile boolean prewarmPending;

    /** Re-requests after a prewarm where nothing built; bounded, so a broken catalogue cannot spin. */
    private int prewarmRetries;
    private static final int MAX_PREWARM_RETRIES = 3;

    /**
     * Asks for the whole icon registry to be built once a world is loaded. The catalogue publishes
     * during startup, before item components are bound, and building a stack then throws
     * {@code Components not bound yet} - so the publish only raises this flag.
     */
    public void requestPrewarm() {
        prewarmPending = true;
    }

    /**
     * Client tick: starts a requested prewarm once a level exists. The first ticks still run
     * inside the resource reload, so "the client ticks" is not yet "components are bound"; a
     * level is. The build itself stays off the render thread, as it always has.
     */
    public void tick(Minecraft minecraft) {
        if (!prewarmPending || minecraft.level == null) {
            return;
        }
        prewarmPending = false;
        Thread thread = new Thread(this::prewarm, "SBS-IconPrewarm");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Builds the entire icon registry up-front. Each entry is guarded on its own: one icon that
     * cannot be built is counted and skipped, never allowed to end the loop, and the failures are
     * logged once as a total. If every single entry failed, components were evidently not ready
     * after all, so the prewarm is asked for again rather than given up on.
     */
    void prewarm() {
        long start = System.currentTimeMillis();
        int resolved = 0;
        int failed = 0;
        String firstFailure = null;
        for (SkyBlockItemCatalog.Entry entry : SkyBlockItemCatalog.getInstance().all()) {
            try {
                if (!icon(entry.id, entry.material, 1).is(Items.BARRIER)) {
                    resolved++;
                }
            } catch (Exception e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = entry.id + ": " + e;
                }
            }
        }
        if (failed > 0) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Icons] {} icon(s) could not be built (first: {}).",
                    failed, firstFailure);
            if (resolved == 0 && prewarmRetries++ < MAX_PREWARM_RETRIES) {
                requestPrewarm();
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Icons] Prewarmed {} item icon(s) in {} ms.",
                resolved, System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    private ItemStack build(String skyblockId, String material) {
        // The live catalogue is authoritative for BOTH data and look: modern material name, custom
        // skull skin, custom item model, leather tint and glint all come from the Hypixel API.
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(skyblockId);
        String resolvedMaterial = entry != null && entry.material != null ? entry.material : material;
        String skin = entry != null ? entry.skinValue : null;

        // The item-data repo only fills the gaps the live API leaves: items it misses entirely and
        // the (few) entries whose look it does not describe at all.
        SkyBlockRepoRecipeProvider.Appearance repoLook =
                SkyBlockRepoRecipeProvider.getInstance().appearanceOf(skyblockId);

        // Custom model = how current Hypixel items carry their texture. The API value wins; the repo
        // value is only consulted when the API has none. Either is applied only if the client really
        // has that model (Hypixel's server resource pack active), so it never yields a blank item.
        Identifier modelId = usableModel(entry != null ? entry.itemModel : null);
        if (modelId == null && repoLook != null) {
            modelId = usableModel(repoLook.itemModel);
        }
        // No live skin and no usable model: fall back to the repo's skull texture (that is what keeps
        // e.g. the Abiphones - "PAPER" without a model in the API - from rendering as plain paper).
        if ((skin == null || skin.isEmpty()) && modelId == null
                && repoLook != null && repoLook.skull != null && !repoLook.skull.isEmpty()) {
            skin = repoLook.skull;
            resolvedMaterial = "player_head";
        }

        Item item = resolveItem(resolvedMaterial);
        if (item == null && resolvedMaterial != material) {
            item = resolveItem(material); // stored material as fallback
        }
        if (item == null && repoLook != null && repoLook.itemid != null) {
            item = resolveItem(repoLook.itemid + (repoLook.damage > 0 ? "-" + repoLook.damage : ""));
        }
        if (item == null && (material == null || material.isEmpty())) {
            // Uncatalogued refs (repo ingredients like "WOOD-4") carry no material at all –
            // the id itself is a legacy material name, so run it through the same resolution.
            item = resolveItem(skyblockId);
        }
        if (item == null) {
            return null;
        }
        ItemStack stack = new ItemStack(item);
        if (skin != null && !skin.isEmpty() && item == Items.PLAYER_HEAD) {
            applySkin(stack, skyblockId, skin);
        }
        if (modelId != null) {
            stack.set(DataComponents.ITEM_MODEL, modelId);
        }
        if (entry != null) {
            // Leather-armor pieces are dyed by the API's "color" field ("139,0,0" for the Arachne
            // set); without it every custom leather piece renders in the default vanilla brown.
            if (entry.color >= 0) {
                stack.set(DataComponents.DYED_COLOR, new DyedItemColor(entry.color));
            }
            if (entry.glowing) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
            }
            applySkyBlockIdentity(stack, entry);
        }
        return stack;
    }

    /**
     * The model id to stamp on an icon, or {@code null} when the value is absent / unparsable / not
     * loaded in this client. The load check matters: a {@code hypixel_skyblock:item/...} model only
     * exists while Hypixel's server resource pack is applied, and stamping a missing model would
     * render the icon as the missing-model cube instead of the plain base item.
     */
    private static Identifier usableModel(String itemModel) {
        if (itemModel == null || itemModel.isEmpty()) {
            return null;
        }
        Identifier modelId = Identifier.tryParse(itemModel);
        if (modelId == null) {
            return null;
        }
        // {@link #prewarm} runs on the catalogue's background thread while the client is still
        // starting, so the model manager may not exist yet - no model then, the reload listener
        // ({@code ItemModelReloadMixin}) re-resolves every icon once models are in.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getModelManager() == null) {
            return null;
        }
        return minecraft.getModelManager().getItemModel(modelId)
                instanceof net.minecraft.client.renderer.item.MissingItemModel ? null : modelId;
    }

    /**
     * Stamps the icon stack with its SkyBlock identity so vanilla tooltip / detection systems work:
     * the real display name (rarity-colored, non-italic), a rarity lore line, and the internal id as
     * {@code ExtraAttributes.id} custom data – which makes the existing price-tooltip injection and
     * rarity overlay treat these icons exactly like live server items.
     */
    private static void applySkyBlockIdentity(ItemStack stack, SkyBlockItemCatalog.Entry entry) {
        Rarity rarity = rarityOf(entry.tier);
        MutableComponent name = Component.literal(entry.name)
                .withStyle(style -> style.withItalic(false));
        if (rarity != null) {
            name = name.withColor(rarity.color() & 0xFFFFFF);
        }
        stack.set(DataComponents.CUSTOM_NAME, name);

        if (entry.tier != null && !entry.tier.isEmpty()) {
            MutableComponent tierLine = Component.literal(entry.tier.replace('_', ' '))
                    .withStyle(style -> style.withItalic(false));
            if (rarity != null) {
                tierLine = tierLine.withColor(rarity.color() & 0xFFFFFF);
            }
            stack.set(DataComponents.LORE, new ItemLore(java.util.List.of(tierLine)));
        }

        CompoundTag extra = new CompoundTag();
        extra.putString("id", entry.id);
        CompoundTag tag = new CompoundTag();
        tag.put("ExtraAttributes", extra);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Maps an API tier string ("LEGENDARY", "VERY_SPECIAL", ...) onto the rarity color enum. */
    private static Rarity rarityOf(String tier) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        String key = tier.toUpperCase(Locale.ROOT);
        if (key.startsWith("VERY_")) {
            key = key.substring(5);
        }
        try {
            return Rarity.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Item resolveItem(String material) {
        if (material == null || material.isEmpty()) {
            return null;
        }
        String path = material.toLowerCase(Locale.ROOT);
        int namespace = path.indexOf(':');
        if (namespace >= 0 && !isDigits(path.substring(namespace + 1))) {
            path = path.substring(namespace + 1);
        }
        // Legacy numeric damage value: "wood-4", "log:1", "ink_sack-4" → base + data.
        int data = -1;
        int suffix = Math.max(path.lastIndexOf('-'), path.lastIndexOf(':'));
        if (suffix > 0 && suffix < path.length() - 1 && isDigits(path.substring(suffix + 1))) {
            data = Integer.parseInt(path.substring(suffix + 1));
            path = path.substring(0, suffix);
        }
        // 1) The data value picks a concrete modern variant (acacia planks, lapis lazuli, ...).
        if (data >= 0) {
            String variantPath = variantPath(path, data);
            if (variantPath != null) {
                Item variant = byPath(variantPath);
                if (variant != null) {
                    return variant;
                }
            }
        }
        // 2) Exact modern path, 3) legacy rename, 4) Bukkit "_item" suffix ("acacia_door_item").
        Item item = byPath(path);
        if (item != null) {
            return item;
        }
        String modern = LEGACY.get(path);
        if (modern != null && (item = byPath(modern)) != null) {
            return item;
        }
        if (path.endsWith("_item")) {
            String stripped = path.substring(0, path.length() - "_item".length());
            item = byPath(stripped);
            if (item != null) {
                return item;
            }
            modern = LEGACY.get(stripped);
            if (modern != null) {
                return byPath(modern);
            }
        }
        return null;
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] DYES = {"ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli",
            "purple_dye", "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye",
            "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal"};

    /**
     * Maps a legacy base material + 1.8 data value onto the modern variant path ("wood" + 4 →
     * "acacia_planks", "ink_sack" + 4 → "lapis_lazuli"), or {@code null} when the base has no
     * data-driven variants (the caller then falls back to the plain / legacy resolution).
     */
    private static String variantPath(String base, int data) {
        return switch (base) {
            case "wood", "planks" -> pick(WOODS, data) + "_planks";
            case "log" -> pick(new String[]{"oak", "spruce", "birch", "jungle"}, data & 3) + "_log";
            case "log_2", "log2" -> pick(new String[]{"acacia", "dark_oak"}, data & 1) + "_log";
            case "sapling" -> pick(WOODS, data) + "_sapling";
            case "leaves" -> pick(new String[]{"oak", "spruce", "birch", "jungle"}, data & 3) + "_leaves";
            case "leaves_2", "leaves2" -> pick(new String[]{"acacia", "dark_oak"}, data & 1) + "_leaves";
            case "wood_step", "wooden_slab", "wood_slab" -> pick(WOODS, data) + "_slab";
            case "step", "stone_slab" -> pick(new String[]{"smooth_stone_slab", "sandstone_slab",
                    "oak_slab", "cobblestone_slab", "brick_slab", "stone_brick_slab",
                    "nether_brick_slab", "quartz_slab"}, data);
            case "stone_slab2" -> "red_sandstone_slab";
            case "wool" -> pick(COLORS, data) + "_wool";
            case "carpet" -> pick(COLORS, data) + "_carpet";
            case "stained_glass" -> pick(COLORS, data) + "_stained_glass";
            case "stained_glass_pane" -> pick(COLORS, data) + "_stained_glass_pane";
            case "stained_clay", "stained_hardened_clay" -> pick(COLORS, data) + "_terracotta";
            case "concrete" -> pick(COLORS, data) + "_concrete";
            case "bed" -> pick(COLORS, data) + "_bed";
            case "ink_sack", "dye" -> pick(DYES, data);
            case "fish", "raw_fish" -> pick(new String[]{"cod", "salmon", "tropical_fish", "pufferfish"}, data);
            case "cooked_fish" -> pick(new String[]{"cooked_cod", "cooked_salmon"}, data);
            case "stone" -> pick(new String[]{"stone", "granite", "polished_granite", "diorite",
                    "polished_diorite", "andesite", "polished_andesite"}, data);
            case "dirt" -> pick(new String[]{"dirt", "coarse_dirt", "podzol"}, data);
            case "sand" -> pick(new String[]{"sand", "red_sand"}, data);
            case "sponge" -> pick(new String[]{"sponge", "wet_sponge"}, data);
            case "sandstone" -> pick(new String[]{"sandstone", "chiseled_sandstone", "cut_sandstone"}, data);
            case "red_sandstone" -> pick(new String[]{"red_sandstone", "chiseled_red_sandstone",
                    "cut_red_sandstone"}, data);
            case "prismarine" -> pick(new String[]{"prismarine", "prismarine_bricks", "dark_prismarine"}, data);
            case "quartz_block" -> pick(new String[]{"quartz_block", "chiseled_quartz_block", "quartz_pillar"}, data);
            case "smooth_brick" -> pick(new String[]{"stone_bricks", "mossy_stone_bricks",
                    "cracked_stone_bricks", "chiseled_stone_bricks"}, data);
            case "cobble_wall", "cobblestone_wall" -> pick(new String[]{"cobblestone_wall",
                    "mossy_cobblestone_wall"}, data);
            case "double_plant" -> pick(new String[]{"sunflower", "lilac", "tall_grass", "large_fern",
                    "rose_bush", "peony"}, data);
            case "red_rose" -> pick(new String[]{"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                    "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"}, data);
            case "long_grass", "tall_grass" -> pick(new String[]{"dead_bush", "short_grass", "fern"}, data);
            case "skull", "skull_item" -> pick(new String[]{"skeleton_skull", "wither_skeleton_skull",
                    "zombie_head", "player_head", "creeper_head", "dragon_head"}, data);
            case "golden_apple" -> data == 1 ? "enchanted_golden_apple" : "golden_apple";
            case "coal" -> data == 1 ? "charcoal" : "coal";
            default -> null;
        };
    }

    private static String pick(String[] variants, int data) {
        return variants[data >= 0 && data < variants.length ? data : 0];
    }

    private static Item byPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(path);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }

    /**
     * A player-head {@link ItemStack} carrying the given base64 texture, for callers that already
     * have a skull value (e.g. the profile viewer's on-demand pet-texture fetch). Public so pet
     * icons don't depend on the recipe-repo download having finished.
     */
    public static ItemStack playerHead(String textureBase64, String cacheKey) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        applySkin(stack, cacheKey, textureBase64);
        return stack;
    }

    /** Applies the base64 skin as a profile component so the real custom head texture renders. */
    private static void applySkin(ItemStack stack, String skyblockId, String skinValue) {
        try {
            UUID uuid = UUID.nameUUIDFromBytes(("SBS:" + skyblockId).getBytes(StandardCharsets.UTF_8));
            PropertyMap properties = new PropertyMap(
                    ImmutableMultimap.of("textures", new Property("textures", skinValue)));
            GameProfile profile = new GameProfile(uuid, "SBSItem", properties);
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        } catch (Exception e) {
            // Icon still renders as a plain player head if the skin is malformed.
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Icons] Skin apply failed for {}: {}", skyblockId, e.toString());
        }
    }
}
