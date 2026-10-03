/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.skills.hunting.logic.AttributeMenuReader;
import sbs.modid.client.skills.hunting.logic.HuntingBoxScanner;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Why <b>this</b> item has no price - answered for the item under the cursor, in one command.
 *
 * <p><b>The question it exists for.</b> "No price is shown" has four completely different causes that
 * look identical from the outside: the item resolved to no key at all, it resolved to a key no market
 * trades, it resolved to the right key and the market has no book for it, or the price map is simply
 * empty because nothing ever fetched it. Guessing between those is what cost a session when every
 * attribute shard priced as unknown in three screens at once - the answer turned out to be the fourth,
 * and nothing anywhere said so. So this prints all four states side by side, and the <b>exact key each
 * cache was asked for</b> beside what it answered.
 *
 * <p><b>Read the same stack three times and compare.</b> The Hunting Box, the Attribute Menu and the
 * player inventory may hand out quite different stacks for one shard - the box and the menu can be
 * showing display icons with no {@code ExtraAttributes.id} at all - so the header records which screen
 * the capture came from and whether the hunting readers claim it. Three files, one diff, no guessing.
 *
 * <p><b>Capture only.</b> Nothing is clicked, no command is sent, no cache is mutated. The one side
 * effect is the one every price read has: consulting {@link BazaarPriceCache} marks the map as wanted,
 * which is what makes it refresh - so running this on an empty map is also how you ask for one.
 *
 * <p><b>Behind {@link DevMode}</b>, unlike {@code MenuProbe}: that probe is written to be sent back by
 * a player, and this one answers a question about our own resolution chain that only somebody reading
 * the code can act on.
 */
public final class PriceProbe {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private PriceProbe() {
    }

    /** {@code /sbs priceprobe} - dump the hovered stack, or the held one with no menu open. */
    public static void handleCommand() {
        if (!DevMode.ACTIVE) {
            say("§7Price probe is a developer-mode command.");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            say("§cNot in a world.");
            return;
        }
        ItemStack stack = hovered(minecraft);
        if (stack == null || stack.isEmpty()) {
            // An empty slot is a legitimate thing to probe and must not read as a failure - saying
            // so, with the market state, is still the useful half of the answer.
            say("§7No item under the cursor. Market state: §f" + marketState());
            return;
        }
        try {
            String text = report(minecraft, stack);
            Path file = write(stack, text);
            summarise(stack);
            say("§7Full dump §8-> §f" + (file == null ? "(could not be written - see the log)" : file));
        } catch (Throwable failed) {
            // A diagnostic must never be the thing that breaks the session it is diagnosing.
            say("§cPrice probe failed - see the log.");
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][PriceProbe] Capture failed", failed);
        }
    }

    // ------------------------------------------------------------------
    // The short answer, in chat
    // ------------------------------------------------------------------

    private static void summarise(ItemStack stack) {
        ItemPriceKey.Resolved resolved = ItemPriceKey.of(stack, ItemPriceKey.Quantity.MENU_ENTRY);
        String skyblockId = SkyblockItem.id(stack);

        say("§b--- price probe ---");
        say("§7context  §f" + context());
        say("§7name     §f" + plain(stack.getHoverName().getString()));
        say("§7sb id    §f" + (skyblockId == null || skyblockId.isBlank() ? "§c(none)" : skyblockId));
        say("§7count    §f" + stack.getCount()
                + " §7lore amount §f" + ItemPriceKey.loreAmount(stack)
                + " §7used §f" + resolved.amount());
        say("§7origin   §f" + resolved.origin());
        say("§7keys     §f" + (resolved.known() ? String.join(", ", resolved.keys()) : "§c(none)"));

        String bazaarKey = BazaarPriceCache.getInstance().keyFor(resolved.key());
        say("§7bz key   §f" + (bazaarKey == null ? "§c(no product under any key)" : bazaarKey));
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(resolved.key());
        say("§7bz price §f" + (price == null ? "§c-" : "buy " + price.buy() + " / sell " + price.sell()));

        Long sell = ItemAppraisal.firstPrice(resolved.keys(), ItemAppraisal.Side.SELL);
        say("§7appraisal §f" + (sell == null ? "§cunpriced" : sell + " coins (sell side, per item)"));
        say("§7market   §f" + marketState());
    }

    // ------------------------------------------------------------------
    // The long answer, in a file
    // ------------------------------------------------------------------

    private static String report(Minecraft minecraft, ItemStack stack) {
        RegistryOps<JsonElement> ops =
                RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
        ItemPriceKey.Resolved resolved = ItemPriceKey.of(stack, ItemPriceKey.Quantity.MENU_ENTRY);
        StringBuilder out = new StringBuilder(1 << 13);

        out.append("SkyBlock Simplified - price probe\n");
        out.append("=================================\n\n");
        out.append("One hovered item, and every step between it and a price. Read-only.\n\n");

        out.append("when      : ").append(LocalDateTime.now()).append('\n');
        out.append("context   : ").append(context()).append('\n');
        out.append("minecraft : ").append(minecraft.getLaunchedVersion()).append('\n');
        out.append('\n');

        out.append("-- the item ------------------------------------------------\n");
        out.append("item      : ").append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append('\n');
        out.append("name      : ").append(plain(stack.getHoverName().getString())).append('\n');
        out.append("name raw  : ").append(stack.getHoverName().getString()).append('\n');
        out.append("count     : ").append(stack.getCount())
                .append(stack.getCount() == 1 ? "  (vanilla draws no number for 1)" : "").append('\n');
        out.append("max stack : ").append(stack.getMaxStackSize()).append('\n');
        out.append("rarity    : ").append(Rarity.detect(stack)).append('\n');
        out.append('\n');

        out.append("-- ExtraAttributes (the whole compound, flattened form included) --\n");
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        out.append("id        : ").append(nullable(SkyblockItem.id(stack))).append('\n');
        out.append("keys      : ").append(extra.keySet()).append('\n');
        out.append("compound  : ").append(extra).append('\n');
        out.append('\n');

        out.append("-- every data component (the full NBT) ---------------------\n");
        var components = stack.getComponents();
        out.append("count     : ").append(components.size()).append('\n');
        for (TypedDataComponent<?> component : components) {
            var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            out.append("  - ").append(key == null ? component.type() : key).append(" = ")
                    .append(encode(component, ops)).append('\n');
        }
        out.append('\n');

        out.append("-- lore ----------------------------------------------------\n");
        List<String> lore = ItemPriceKey.lore(stack);
        out.append("lines     : ").append(lore.size()).append('\n');
        for (int i = 0; i < lore.size(); i++) {
            out.append("  [").append(i).append("] ").append(lore.get(i)).append('\n');
        }
        out.append('\n');

        out.append("-- amount --------------------------------------------------\n");
        out.append("stack size    : ").append(stack.getCount()).append('\n');
        out.append("lore amount   : ").append(ItemPriceKey.loreAmount(stack))
                .append("   (0 = no lore line stated one)\n");
        out.append("STACK reading : ").append(ItemPriceKey.amount(stack, ItemPriceKey.Quantity.STACK))
                .append('\n');
        out.append("MENU reading  : ").append(resolved.amount())
                .append("   (what the Hunting Box uses)\n");
        out.append('\n');

        out.append("-- resolution ----------------------------------------------\n");
        out.append("origin        : ").append(resolved.origin()).append('\n');
        out.append("shard id      : ").append(nullable(ItemPriceKey.shardId(stack))).append('\n');
        out.append("keys, in order, and what each cache says when asked for exactly that key:\n");
        if (!resolved.known()) {
            out.append("  (none - this item resolves to no price key at all)\n");
        }
        for (String key : resolved.keys()) {
            out.append("  key ").append(key).append('\n');
            out.append("    bazaar : ").append(describe(BazaarPriceCache.getInstance().get(key)))
                    .append('\n');
            out.append("    lbin   : ").append(nullable(LbinCache.getInstance().getLbin(key)))
                    .append('\n');
            out.append("    chat   : ").append(nullable(ChatPriceCache.getInstance().getUnitPrice(key)))
                    .append('\n');
        }
        out.append('\n');
        out.append("key the bazaar actually answers for : ")
                .append(nullable(BazaarPriceCache.getInstance().keyFor(resolved.key()))).append('\n');
        out.append("appraised (sell side, per item)     : ")
                .append(nullable(ItemAppraisal.firstPrice(resolved.keys(), ItemAppraisal.Side.SELL)))
                .append('\n');
        out.append("appraised (buy side, per item)      : ")
                .append(nullable(ItemAppraisal.firstPrice(resolved.keys(), ItemAppraisal.Side.BUY)))
                .append('\n');
        out.append('\n');

        out.append("-- market state --------------------------------------------\n");
        out.append(marketState()).append('\n');
        out.append("price age : ").append(BazaarPriceCache.getInstance().priceAgeMs())
                .append(" ms since the snapshot was generated\n");
        out.append('\n');
        out.append("If the product count is 0 the item is not the problem - nothing has fetched the\n");
        out.append("Bazaar yet. Reading it (this probe counts) asks for a pull; try again in ~15s.\n");
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Small readers
    // ------------------------------------------------------------------

    /** The stack under the cursor, else the one in hand - so the command works with no menu open. */
    private static ItemStack hovered(Minecraft minecraft) {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = ((AbstractContainerScreenAccessor) container).skyblockSimplified$hoveredSlot();
            if (slot != null && slot.hasItem()) {
                return slot.getItem();
            }
            return ItemStack.EMPTY;
        }
        return minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
    }

    /**
     * Which screen this capture came from, and whether the hunting readers claim it.
     *
     * <p>The claim flags are the point: an Attribute Menu that nothing recognises and one that is
     * recognised and understood produce the same empty panel, and this line separates them.
     */
    private static String context() {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return "no container open (hand item)";
        }
        String title = container.getTitle() == null ? "" : plain(container.getTitle().getString());
        String claims = HuntingBoxScanner.isBox(container) ? "claimed by the Hunting Box scanner"
                : AttributeMenuReader.getInstance().lastFacts() != null
                        ? "Attribute Menu reader has facts from this session"
                        : "claimed by neither hunting reader";
        return "\"" + title + "\" (" + claims + ")";
    }

    /** One line saying whether the markets have anything in them at all. */
    private static String marketState() {
        BazaarPriceCache bazaar = BazaarPriceCache.getInstance();
        int shards = 0;
        for (String id : bazaar.ids()) {
            if (ItemPriceKey.isShard(id)) {
                shards++;
            }
        }
        return "bazaar products " + bazaar.productCount() + " (" + shards + " shards)"
                + (bazaar.ready() ? "" : "  <-- EMPTY, nothing has fetched the Bazaar");
    }

    private static Path write(ItemStack stack, String text) {
        try {
            Path file = freeFile(slug(plain(stack.getHoverName().getString())));
            SBSFiles.ensureParent(file);
            Files.writeString(file, text);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PriceProbe] wrote {}", file);
            return file;
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][PriceProbe] Writing the capture failed", e);
            return null;
        }
    }

    private static Path freeFile(String name) {
        Path file = SBSFiles.probeFile("price-" + name);
        for (int i = 2; Files.exists(file) && i < 100; i++) {
            file = SBSFiles.probeFile("price-" + name + "-" + i);
        }
        return file;
    }

    private static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "item";
        }
        return LocalDateTime.now().format(STAMP) + "-"
                + (slug.length() > 32 ? slug.substring(0, 32) : slug);
    }

    private static String describe(BazaarPriceCache.BzPrice price) {
        return price == null ? "(no product)" : "buy " + price.buy() + " / sell " + price.sell();
    }

    private static String nullable(Object value) {
        return value == null ? "(none)" : String.valueOf(value);
    }

    private static String encode(TypedDataComponent<?> component, RegistryOps<JsonElement> ops) {
        try {
            var result = component.encodeValue(ops);
            return result.result().map(JsonElement::toString)
                    .orElseGet(() -> "<not encodable: "
                            + result.error().map(Object::toString).orElse("unknown") + ">");
        } catch (Throwable t) {
            return "<encode failed: " + t.getClass().getSimpleName() + ">";
        }
    }

    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("§.", "").trim();
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
