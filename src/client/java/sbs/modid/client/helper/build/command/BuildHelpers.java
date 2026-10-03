/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.client.core.build.logic.BlockStates;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.logic.WorldCapture;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.core.build.model.StateStrings;
import sbs.modid.client.core.build.render.GhostCollector;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.helper.build.logic.BuildChat;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.BuildGuide;
import sbs.modid.client.helper.build.model.BlockFamily;
import sbs.modid.client.helper.build.model.EditShapes;
import sbs.modid.client.helper.build.model.MaterialList;
import sbs.modid.client.helper.build.model.PaletteSwap;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The read-only helpers, all usable on a server: material list and cost, the build guide, palette
 * swaps on the hologram, and smart select. None of them changes a block.
 */
public final class BuildHelpers {

    /** Smart select searches this far from the block you look at, in each direction. */
    private static final int SELECT_RADIUS = 64;

    /** And stops after this many blocks. */
    private static final int SELECT_LIMIT = 300_000;

    /** Material lines printed; the rest are summed. */
    private static final int MATERIAL_LINES = 20;

    private BuildHelpers() {
    }

    // ---------------------------------------------------------------- materials

    /** {@code materials [hologram|clipboard|selection]}: what the build needs, have, and cost. */
    static void materials(String rest) {
        String source = rest.trim().toLowerCase(Locale.ROOT);
        Hologram hologram = HologramManager.getInstance().hologram();
        Map<String, Integer> states;
        String what;
        if ((source.isEmpty() || source.equals("hologram")) && hologram != null) {
            states = countStates(hologram.display(), hologram.palette());
            what = "the hologram" + (hologram.swaps().isEmpty() ? "" : " (with its swaps)");
        } else if ((source.isEmpty() || source.equals("clipboard")) && Clipboard.get() != null) {
            states = Clipboard.get().countByState();
            what = "the clipboard";
        } else if (source.isEmpty() || source.equals("selection")) {
            Selection box = SelectionManager.getInstance().selection();
            ClientLevel level = Minecraft.getInstance().level;
            if (box == null || level == null || box.volume() > BuildCommands.MAX_COPY_VOLUME) {
                BuildChat.warn("Nothing to list - show a hologram, copy something, or select an area");
                return;
            }
            states = WorldCapture.capture(level, box, SchematicHeader.untitled(), null).countByState();
            what = "the selection";
        } else {
            BuildChat.warn("Usage: //materials [hologram|clipboard|selection]");
            return;
        }
        List<MaterialList.Line> lines = MaterialList.of(states);
        if (lines.isEmpty()) {
            BuildChat.info("Nothing to build in " + what);
            return;
        }
        Map<String, Long> have = inventoryCounts();
        boolean priced = !BuildGate.singleplayer() && BazaarPriceCache.getInstance().ready();
        long totalNeed = 0;
        long totalCost = 0;
        int pricedLines = 0;
        BuildChat.info("Materials for " + what + " - " + lines.size() + " kinds"
                + (priced ? ", Bazaar instant-buy prices (cached)" : ""));
        for (int i = 0; i < lines.size(); i++) {
            MaterialList.Line line = lines.get(i);
            long owned = have.getOrDefault(line.item(), 0L);
            long need = Math.max(0, line.count() - owned);
            totalNeed += need;
            Long unit = priced ? bazaarBuy(line.item()) : null;
            if (unit != null) {
                totalCost += unit * need;
                pricedLines++;
            }
            if (i < MATERIAL_LINES) {
                StringBuilder text = new StringBuilder("  ").append(StateStrings.displayName(line.item()))
                        .append(String.format(Locale.ROOT, " ×%,d", line.count()));
                if (owned > 0) {
                    text.append(String.format(Locale.ROOT, "  (have %,d, need %,d)", owned, need));
                }
                if (unit != null && need > 0) {
                    text.append("  •  ").append(coins(unit * need));
                }
                BuildChat.info(text.toString());
            }
        }
        if (lines.size() > MATERIAL_LINES) {
            BuildChat.hint("... and " + (lines.size() - MATERIAL_LINES) + " more kinds");
        }
        String summary = String.format(Locale.ROOT, "Still to get: %,d items", totalNeed);
        if (priced) {
            summary += "  •  about " + coins(totalCost) + " for the " + pricedLines + " of " + lines.size()
                    + " kinds the Bazaar sells (the rest have no Bazaar price)";
        } else if (!BuildGate.singleplayer()) {
            summary += "  •  Bazaar prices are not loaded yet";
        }
        BuildChat.info(summary);
        BuildChat.hint("\"have\" counts your inventory only - sack contents are not known to the mod yet");
    }

    /** Counts per state string using {@code palette} (the hologram's swapped one) for the cells. */
    static Map<String, Integer> countStates(Schematic schematic, List<String> palette) {
        int[] counts = new int[palette.size()];
        for (int cell : schematic.nonAirCells()) {
            counts[schematic.paletteAt(cell)]++;
        }
        Map<String, Integer> out = new HashMap<>();
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > 0 && !palette.get(i).equals(Hologram.REMOVE_MARKER)) {
                out.merge(palette.get(i), counts[i], Integer::sum);
            }
        }
        return out;
    }

    /** Items in the player's inventory by registry id. */
    private static Map<String, Long> inventoryCounts() {
        Map<String, Long> out = new HashMap<>();
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return out;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                out.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
            }
        }
        return out;
    }

    /**
     * The Bazaar instant-buy price for a vanilla item, or {@code null}. The product id is guessed as
     * the upper-cased item name ({@code minecraft:cobblestone -> COBBLESTONE}) - UNVERIFIED for most
     * blocks: SkyBlock names some vanilla items differently, and those simply show no price.
     */
    private static Long bazaarBuy(String itemId) {
        String guess = itemId.substring(itemId.indexOf(':') + 1).toUpperCase(Locale.ROOT);
        BazaarPriceCache cache = BazaarPriceCache.getInstance();
        String key = cache.keyFor(guess);
        return key == null ? null : cache.getBuy(key);
    }

    private static String coins(long amount) {
        if (amount >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm coins", amount / 1_000_000.0);
        }
        if (amount >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk coins", amount / 1_000.0);
        }
        return amount + " coins";
    }

    // ---------------------------------------------------------------- guide

    /** {@code guide [on|off]}. */
    static void guide(String rest) {
        String word = rest.trim().toLowerCase(Locale.ROOT);
        boolean turnOn = word.isEmpty() ? !BuildGuide.active() : word.equals("on");
        if (!word.isEmpty() && !word.equals("on") && !word.equals("off")) {
            BuildChat.warn("Usage: //guide [on|off]");
            return;
        }
        if (!turnOn) {
            BuildGuide.stop();
            BuildChat.info("Build guide off");
            return;
        }
        if (!BuildGuide.start()) {
            BuildChat.warn("No hologram to guide you - //paste one, or copy a plot with Garden Blueprint");
            return;
        }
        BuildChat.info("Build guide on - one layer at a time; the wheel changes layer, the green box is the next block");
    }

    // ---------------------------------------------------------------- swap

    /** {@code swap <from> <to>} | {@code swap reset} | {@code swap}: palette swaps on the hologram. */
    static void swap(String rest) {
        HologramManager manager = HologramManager.getInstance();
        Hologram hologram = manager.hologram();
        if (hologram == null) {
            BuildChat.warn("No hologram - //paste a build first; swaps change what it would place");
            return;
        }
        String[] words = rest.trim().isEmpty() ? new String[0] : rest.trim().split("\\s+");
        if (words.length == 0) {
            if (hologram.swaps().isEmpty()) {
                BuildChat.info("No swaps - //swap oak spruce turns every oak block of the hologram into spruce");
            } else {
                hologram.swaps().forEach((from, to) -> BuildChat.info("  " + StateStrings.displayName(from)
                        + " → " + StateStrings.displayName(to)));
            }
            return;
        }
        if (words.length == 1 && words[0].equalsIgnoreCase("reset")) {
            manager.update(hologram.withSwaps(Map.of()));
            GhostCollector.invalidate();
            BuildChat.info("Swaps cleared - the hologram shows the build as saved");
            return;
        }
        if (words.length != 2) {
            BuildChat.warn("Usage: //swap <from> <to>  (e.g. //swap oak spruce)  •  //swap reset");
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String state : hologram.display().palette()) {
            ids.add(StateStrings.blockId(state));
        }
        String to = words[1];
        if (to.equalsIgnoreCase(sbs.modid.client.helper.build.model.BlockPattern.HAND)
                || to.equalsIgnoreCase(sbs.modid.client.helper.build.model.BlockPattern.OFFHAND)) {
            try {
                to = StateStrings.blockId(sbs.modid.client.helper.build.logic.HeldBlocks.RESOLVER
                        .held(to.equalsIgnoreCase(sbs.modid.client.helper.build.model.BlockPattern.OFFHAND)).state());
            } catch (sbs.modid.client.helper.build.model.BlockPattern.PatternException refused) {
                BuildChat.warn(refused.getMessage());
                return;
            }
        }
        Map<String, String> planned = PaletteSwap.plan(ids, words[0], to, id -> BlockStates.tryParse(id) != null);
        if (planned.isEmpty()) {
            BuildChat.warn("Nothing in the hologram turns into a real block with that swap");
            return;
        }
        manager.update(hologram.withSwaps(PaletteSwap.chain(hologram.swaps(), planned)));
        GhostCollector.invalidate();
        BuildChat.info("Swapped " + planned.size() + " kind" + (planned.size() == 1 ? "" : "s") + " of block in the hologram"
                + " - the saved build is unchanged (//swap reset undoes it)");
    }

    // ---------------------------------------------------------------- smart select

    /** {@code select connected [family|any]}: flood-select the structure under the crosshair. */
    static void select(String rest) {
        String[] words = rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 0 || !words[0].equals("connected")) {
            BuildChat.warn("Usage: //select connected [family|any]");
            return;
        }
        String mode = words.length > 1 ? words[1] : "build";
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        HitResult hit = minecraft.hitResult;
        if (level == null || !(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            BuildChat.warn("Look at a block of the build first");
            return;
        }
        BlockPos start = blockHit.getBlockPos();
        BlockState startState = level.getBlockState(start);
        String family = BlockFamily.of(BlockStates.serialize(startState));
        Map<BlockState, Boolean> memo = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        EditShapes.Passable passable = (x, y, z) -> {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (state.isAir()) {
                return false;
            }
            return memo.computeIfAbsent(state, s -> {
                String text = BlockStates.serialize(s);
                return switch (mode) {
                    case "any" -> true;
                    case "family" -> BlockFamily.of(text).equals(family);
                    default -> !BlockFamily.isTerrain(text);
                };
            });
        };
        if (!mode.equals("any") && !mode.equals("family") && BlockFamily.isTerrain(BlockStates.serialize(startState))) {
            BuildChat.warn("That is ground, not a build - look at the build, or use //select connected any");
            return;
        }
        Selection bounds = Selection.single(start.getX(), start.getY(), start.getZ()).expandAll(SELECT_RADIUS);
        EditShapes.Flood flood = EditShapes.flood(start.getX(), start.getY(), start.getZ(), bounds, SELECT_LIMIT, passable);
        if (flood.count() == 0) {
            BuildChat.warn("Nothing connected there to select");
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < flood.count(); i++) {
            long packed = flood.cells()[i];
            minX = Math.min(minX, EditShapes.unpackX(packed));
            minY = Math.min(minY, EditShapes.unpackY(packed));
            minZ = Math.min(minZ, EditShapes.unpackZ(packed));
            maxX = Math.max(maxX, EditShapes.unpackX(packed));
            maxY = Math.max(maxY, EditShapes.unpackY(packed));
            maxZ = Math.max(maxZ, EditShapes.unpackZ(packed));
        }
        Selection box = new Selection(minX, minY, minZ, maxX, maxY, maxZ);
        SelectionManager.getInstance().set(box);
        BuildChat.info(String.format(Locale.ROOT, "Selected %,d connected blocks  •  box %s", flood.count(), box.sizeLabel()));
        if (flood.escaped()) {
            BuildChat.hint("It reached the " + SELECT_RADIUS + "-block search limit - the build may go on past the box; "
                    + "//expand to take in the rest");
        }
    }
}
