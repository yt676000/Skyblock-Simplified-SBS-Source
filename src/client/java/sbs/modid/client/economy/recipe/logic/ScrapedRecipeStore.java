/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures Hypixel SkyBlock recipes from the game's own recipe menus and persists them to disk –
 * the {@link RecipeProvider} for custom SkyBlock recipes.
 *
 * <p>Hypixel has no official recipe API endpoint, but it <i>does</i> expose every custom recipe
 * in-game through its recipe-book menus (titles ending in "Recipe", with the ingredients laid out in
 * a 3x3 grid and the result to its right). Whenever such a menu is open, the scraper (ticked from
 * {@code GuiTrackingMixin}, exactly like {@link sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker}) reads
 * the grid + result, builds an {@link SbsRecipe} and stores it in
 * {@code config/<modid>-recipes.json}. Nothing is hardcoded: the library grows as the player browses
 * recipes, and persists across restarts.
 */
public final class ScrapedRecipeStore implements RecipeProvider {

    private static final ScrapedRecipeStore INSTANCE = new ScrapedRecipeStore();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, SbsRecipe>>() {
    }.getType();

    /** The 3x3 ingredient grid slots in Hypixel recipe menus (6-row chest layout). */
    private static final int[] GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    /** Result slot candidates, right of the grid's middle row (layout varies slightly per menu). */
    private static final int[] RESULT_SLOTS = {23, 24, 25};

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private final Map<String, SbsRecipe> recipes = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    /** In-memory de-dup signatures so unchanged rescans never touch the disk. */
    private final Map<String, String> signatures = new ConcurrentHashMap<>();

    private int ticksUntilScan;

    private ScrapedRecipeStore() {
    }

    public static ScrapedRecipeStore getInstance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "SkyBlock (in-game recipe menus)";
    }

    @Override
    public List<SbsRecipe> loadRecipes() {
        ensureLoaded();
        return new ArrayList<>(recipes.values());
    }

    /** Scans the open screen for a Hypixel recipe menu, every ~half second, on the client thread. */
    public void tick(Minecraft minecraft) {
        try {
            if (!ConfigManager.getInstance().get().recipeViewer.enabled || minecraft == null) {
                return;
            }
            if (--ticksUntilScan > 0) {
                return;
            }
            ticksUntilScan = 10;

            Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            String title = stripCodes(screen.getTitle() != null ? screen.getTitle().getString() : "").trim();
            if (!title.toLowerCase(Locale.ROOT).endsWith("recipe")) {
                return;
            }
            scrape(container.getMenu(), title);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Recipes] Recipe scrape failed", t);
        }
    }

    private void scrape(AbstractContainerMenu menu, String title) {
        int slotCount = menu.getItems().size();
        if (slotCount < 36) {
            return; // not a chest-style recipe menu
        }

        ItemRef[] grid = new ItemRef[9];
        List<ItemRef> ingredients = new ArrayList<>();
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            int slot = GRID_SLOTS[i];
            if (slot >= slotCount) {
                return;
            }
            ItemStack stack = menu.getSlot(slot).getItem();
            if (isReal(stack)) {
                ItemRef ref = toRef(stack);
                grid[i] = ref;
                merge(ingredients, ref);
            }
        }
        if (ingredients.isEmpty()) {
            return; // empty grid – nothing to record
        }

        ItemRef result = null;
        for (int slot : RESULT_SLOTS) {
            if (slot < slotCount) {
                ItemStack stack = menu.getSlot(slot).getItem();
                if (isReal(stack)) {
                    result = toRef(stack);
                    break;
                }
            }
        }
        if (result == null) {
            return;
        }

        SbsRecipe recipe = new SbsRecipe("Crafting", title, result);
        recipe.grid = grid;
        recipe.ingredients = ingredients;

        String key = recipe.key();
        String signature = signature(recipe);
        if (signature.equals(signatures.get(key))) {
            return; // already stored identically
        }
        ensureLoaded();
        signatures.put(key, signature);
        recipes.put(key, recipe);
        save();
        RecipeRegistry.getInstance().markDirty(); // new recipe appears in searches immediately
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Recipes] Captured recipe for {} ({} ingredient type(s)).",
                result.name, ingredients.size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Real ingredient / result – filters out the glass-pane filler Hypixel pads menus with. */
    private static boolean isReal(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("glass_pane")) {
            return false;
        }
        return !stripCodes(stack.getHoverName().getString()).isBlank();
    }

    private static ItemRef toRef(ItemStack stack) {
        String material = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        String name = stripCodes(stack.getHoverName().getString()).trim();
        return new ItemRef(SkyblockItem.id(stack), material, name, stack.getCount());
    }

    /** Aggregates equal ingredients (same lookup id) into one entry with a summed count. */
    private static void merge(List<ItemRef> ingredients, ItemRef ref) {
        for (ItemRef existing : ingredients) {
            if (existing.lookupId().equals(ref.lookupId())) {
                existing.count += ref.count;
                return;
            }
        }
        ingredients.add(new ItemRef(ref.skyblockId, ref.material, ref.name, ref.count));
    }

    private static String signature(SbsRecipe recipe) {
        StringBuilder sb = new StringBuilder(recipe.result.lookupId());
        for (ItemRef ref : recipe.ingredients) {
            sb.append('|').append(ref.lookupId()).append('x').append(ref.count);
        }
        return sb.toString();
    }

    private static String stripCodes(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = filePath();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, SbsRecipe> stored = GSON.fromJson(reader, MAP_TYPE);
                    if (stored != null) {
                        recipes.putAll(stored);
                        stored.forEach((key, recipe) -> signatures.put(key, signature(recipe)));
                    }
                }
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Recipes] Loaded {} cached recipe(s).", recipes.size());
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Recipes] Failed to load recipe cache", e);
        }
    }

    private synchronized void save() {
        Path path = filePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(recipes, MAP_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Recipes] Failed to save recipe cache", e);
        }
    }

    private static Path filePath() {
        return SBSFiles.scrapedRecipesFile();
    }
}
