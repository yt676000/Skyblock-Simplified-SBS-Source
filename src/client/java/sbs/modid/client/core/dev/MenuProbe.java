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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A <b>capture-only</b> probe for open container menus: it writes down exactly what Hypixel sent for
 * every slot of the menu you are looking at, and changes nothing.
 *
 * <p><b>Why it exists.</b> Every menu-reading feature in this mod is written against lore wording
 * that cannot be checked from outside the game, and a parser written against a guess is a parser
 * that silently reads nothing. The immediate customer is the Attribute Menu overview, where the
 * open questions are all of that shape - what the title bar and its page counter look like, whether
 * an owned entry still carries its {@code Source:} and {@code Rarity:} lines, and what the level-10
 * wording becomes. One capture answers them from the real menu instead of from an assumption. The
 * output is deliberately general, so the same file also serves later as the way to check a learned
 * catalogue against what the game currently says.
 *
 * <p><b>What it does not do.</b> No panel, no sorting, no filtering, no price lookup, no icon
 * resolution - none of the feature's code paths exist here. Nothing is clicked, no command is sent,
 * no page is turned: page flips stay manual, exactly as the player made them. The probe is inert
 * until {@code /sbs probe} invokes it, and while armed it does one integer comparison per client
 * tick and nothing at all per frame.
 *
 * <p><b>The one thing it deliberately triggers</b> is
 * {@link ItemStack#getTooltipLines} - the real tooltip pipeline, mixins and all. That is the point:
 * dumping it beside the raw {@link net.minecraft.core.component.DataComponents#LORE} component turns
 * "our injected lines are surely not in the raw lore" from an assumption into a line-by-line diff
 * that is part of the artifact. It runs on the client thread and reads the same caches a hover would.
 *
 * <p><b>Deliberately not gated behind {@link DevMode}.</b> Some of what needs capturing depends on
 * account state nobody here has (a maxed attribute, a fusion-only shard), so the file has to be
 * something a normal player can produce and send back. It stays harmless because it only ever reads.
 */
public final class MenuProbe {

    private static final MenuProbe INSTANCE = new MenuProbe();

    /** Armed captures cannot fire faster than this; a menu that refreshes on a timer cannot flood. */
    private static final long MIN_INTERVAL_MS = 1_500L;

    /** Armed captures stop after this many files, announcing it. A forgotten arm cannot fill a disk. */
    private static final int MAX_ARMED_CAPTURES = 40;

    /** Player-inventory slots are skipped: they are your own items, not the menu being studied. */
    private static final String INVENTORY_NOTE =
            "player inventory slots are not dumped (they are not part of the menu)";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private boolean armed;

    /** Identity of the screen the armed watcher last saw, so a new menu captures as well. */
    private Screen lastScreen;

    private int lastStateId = Integer.MIN_VALUE;

    private long lastCaptureAt;

    private int armedCaptures;

    private MenuProbe() {
    }

    public static MenuProbe getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Command surface
    // ------------------------------------------------------------------

    /** {@code /sbs probe [arm|off|status]} - bare form captures the open menu once, right now. */
    public void handleCommand(String argument) {
        String arg = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        switch (arg) {
            case "arm", "on", "watch" -> arm();
            case "off", "stop", "disarm" -> disarm(true);
            case "status" -> status();
            default -> captureNow();
        }
    }

    private void arm() {
        armed = true;
        armedCaptures = 0;
        lastScreen = null;
        lastStateId = Integer.MIN_VALUE;
        say("§aProbe armed §7- a file is written on every menu change (page flips included).");
        say("§7Run §f/sbs probe off§7 when you are done. Stops by itself after "
                + MAX_ARMED_CAPTURES + " files.");
        // Capture whatever is already open, so arming inside a menu does not need a page flip first.
        // The watch state is adopted at the same time: without it the next tick sees "a screen it has
        // not seen" and writes the same menu a second time.
        if (currentScreen() instanceof AbstractContainerScreen<?> container) {
            lastScreen = container;
            lastStateId = container.getMenu().getStateId();
            capture(container, "armed while this menu was open");
        }
    }

    private void disarm(boolean announce) {
        armed = false;
        lastScreen = null;
        lastStateId = Integer.MIN_VALUE;
        if (announce) {
            say("§7Probe disarmed after §f" + armedCaptures + "§7 file(s).");
        }
    }

    private void status() {
        say(armed ? "§aProbe is armed §7(" + armedCaptures + " file(s) so far)."
                : "§7Probe is off. §f/sbs probe§7 captures once, §f/sbs probe arm§7 watches.");
        say("§8Files: " + SBSFiles.probeDir());
    }

    private void captureNow() {
        Screen screen = currentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            say("§7Open the menu you want captured and run this again.");
            say("§8(Current screen: " + (screen == null ? "none" : screen.getClass().getName()) + ")");
            return;
        }
        capture(container, "/sbs probe");
    }

    // ------------------------------------------------------------------
    // Armed watching
    // ------------------------------------------------------------------

    /**
     * Client tick. Costs one boolean read while disarmed, and one integer comparison while armed -
     * {@link AbstractContainerMenu#getStateId()} is the server's own "the contents changed" counter,
     * the same signal the menu-scanning features memoise on. Whether it actually moves on a page flip
     * is one of the things this capture is meant to settle, so every change is recorded with both
     * values.
     */
    public void tick(Minecraft minecraft) {
        if (!armed) {
            return;
        }
        Screen screen = currentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            // Leaving the menu resets the watch, so re-opening it captures again.
            lastScreen = null;
            lastStateId = Integer.MIN_VALUE;
            return;
        }
        int stateId = container.getMenu().getStateId();
        boolean newScreen = screen != lastScreen;
        if (!newScreen && stateId == lastStateId) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCaptureAt < MIN_INTERVAL_MS) {
            return; // the change is still pending; the next tick past the gap takes it
        }
        String reason = newScreen
                ? "menu opened"
                : "contents changed (stateId " + lastStateId + " -> " + stateId + ")";
        lastScreen = screen;
        lastStateId = stateId;
        capture(container, reason);
        if (armedCaptures >= MAX_ARMED_CAPTURES) {
            say("§eProbe stopped by itself after " + MAX_ARMED_CAPTURES + " files.");
            disarm(false);
        }
    }

    // ------------------------------------------------------------------
    // The capture itself
    // ------------------------------------------------------------------

    private void capture(AbstractContainerScreen<?> container, String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        lastCaptureAt = System.currentTimeMillis();
        armedCaptures++;
        try {
            RegistryOps<JsonElement> ops =
                    RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
            String text = report(minecraft, container, reason, ops);
            Path file = freeFile(slug(plain(title(container))));
            SBSFiles.ensureParent(file);
            Files.writeString(file, text);
            say("§aCaptured§7 (" + reason + ") §8-> §f" + file);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Probe] {} -> {}", reason, file);
        } catch (IOException e) {
            say("§cProbe could not write its file - see the log.");
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Probe] Writing the capture failed", e);
        } catch (Throwable t) {
            // A capture is a diagnostic; it must never be the thing that breaks the session.
            say("§cProbe failed - see the log.");
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Probe] Capture failed", t);
        }
    }

    private String report(Minecraft minecraft, AbstractContainerScreen<?> container, String reason,
                          RegistryOps<JsonElement> ops) {
        AbstractContainerMenu menu = container.getMenu();
        StringBuilder out = new StringBuilder(1 << 16);

        out.append("SkyBlock Simplified - menu capture\n");
        out.append("=================================\n\n");
        out.append("This file is a read-only recording of one Hypixel menu, written so a feature can be\n");
        out.append("built against what the game really sends instead of against a guess. It contains no\n");
        out.append("account data beyond the menu itself. Please send the whole file back unedited.\n\n");

        out.append("when      : ").append(LocalDateTime.now()).append('\n');
        out.append("reason    : ").append(reason).append('\n');
        out.append("minecraft : ").append(minecraft.getLaunchedVersion()).append('\n');
        out.append("mod       : ").append(SkyblockSimplifiedSBS.MOD_ID).append('\n');
        out.append('\n');

        out.append("screen    : ").append(container.getClass().getName()).append('\n');
        out.append("menu      : ").append(menu.getClass().getName()).append('\n');
        out.append("menu type : ").append(menuType(menu)).append('\n');
        out.append("containerId: ").append(menu.containerId).append('\n');
        out.append("stateId   : ").append(menu.getStateId()).append('\n');
        out.append("slots     : ").append(menu.slots.size()).append(" total, ")
                .append(containerSlots(menu)).append(" belong to the menu (")
                .append(INVENTORY_NOTE).append(")\n");
        out.append("title json: ").append(json(title(container), ops)).append('\n');
        out.append("title text: ").append(plain(title(container))).append('\n');
        out.append("title segs: ").append(segments(title(container))).append('\n');
        out.append('\n');

        out.append("Slots follow in menu order. Empty slots are listed too - the gaps are what make the\n");
        out.append("page layout readable (which indices are chrome, where the arrows sit).\n");

        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            out.append("\n------------------------------------------------------------\n");
            ItemStack stack = slot.getItem();
            out.append("[slot ").append(slot.index)
                    .append(" | container slot ").append(slot.getContainerSlot())
                    .append(" | x=").append(slot.x).append(" y=").append(slot.y).append("]\n");
            if (stack == null || stack.isEmpty()) {
                out.append("  (empty)\n");
                continue;
            }
            dumpStack(out, stack, minecraft, ops);
        }
        return out.toString();
    }

    private void dumpStack(StringBuilder out, ItemStack stack, Minecraft minecraft,
                           RegistryOps<JsonElement> ops) {
        out.append("  item      : ").append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append('\n');
        // Its own line, and spelled out: whether the yellow number on a slot is the stack count is an
        // open question, and no data component in 26.2 overrides what gets drawn - so this field is
        // the whole answer. Vanilla draws nothing at all for a count of 1, which is exactly why a
        // count can never tell "level 1" from "not owned" on its own.
        out.append("  count     : ").append(stack.getCount())
                .append(stack.getCount() == 1 ? "  (vanilla draws no number for 1)" : "").append('\n');
        String skyblockId = SkyblockItem.id(stack);
        out.append("  skyblock  : ").append(skyblockId == null || skyblockId.isBlank()
                ? "(none)" : skyblockId).append('\n');
        out.append("  name json : ").append(json(stack.getHoverName(), ops)).append('\n');
        out.append("  name text : ").append(plain(stack.getHoverName())).append('\n');

        // Called out separately even though it is in the component dump below: whether the dye colour
        // encodes locked-vs-owned is an open question, and it should not need hunting for.
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        out.append("  dye colour: ").append(dye == null
                ? "(none)" : String.format(Locale.ROOT, "#%06X", dye.rgb() & 0xFFFFFF)).append('\n');

        var components = stack.getComponents();
        out.append("  components: ").append(components.size()).append('\n');
        for (TypedDataComponent<?> component : components) {
            var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            out.append("    - ").append(key == null ? component.type() : key).append(" = ")
                    .append(encode(component, ops)).append('\n');
        }

        List<Component> lore = loreLines(stack);
        out.append("  LORE (the raw component - this is the parse target): ")
                .append(lore.size()).append(" line(s)\n");
        for (int i = 0; i < lore.size(); i++) {
            Component line = lore.get(i);
            out.append("    [").append(i).append("] text: ").append(plain(line)).append('\n');
            out.append("    [").append(i).append("] segs: ").append(segments(line)).append('\n');
            out.append("    [").append(i).append("] json: ").append(json(line, ops)).append('\n');
        }

        List<Component> tooltip = tooltipLines(stack, minecraft);
        out.append("  TOOLTIP (what a hover renders, mixins included): ")
                .append(tooltip.size()).append(" line(s)\n");
        for (int i = 0; i < tooltip.size(); i++) {
            out.append("    [").append(i).append("] ").append(plain(tooltip.get(i))).append('\n');
        }

        out.append("  DIFF tooltip vs LORE:\n");
        diff(out, lore, tooltip, ops);
    }

    /**
     * Line-level diff of the rendered tooltip against the raw lore.
     *
     * <p>Two-pointer over the plain text: a tooltip line that matches the next unconsumed lore line
     * is the same line, anything else was added by the tooltip pipeline. The first tooltip line is
     * vanilla's display name and is labelled as such rather than reported as an addition, so what is
     * left under {@code +added} is exactly the set of lines some handler injects - which is the
     * artifact worth keeping, and the place a duplicated or contradictory price line becomes visible.
     */
    private void diff(StringBuilder out, List<Component> lore, List<Component> tooltip,
                      RegistryOps<JsonElement> ops) {
        List<String> lorePlain = new ArrayList<>(lore.size());
        for (Component line : lore) {
            lorePlain.add(plain(line));
        }
        int next = 0;
        int added = 0;
        for (int i = 0; i < tooltip.size(); i++) {
            String line = plain(tooltip.get(i));
            if (i == 0) {
                out.append("    [name ] ").append(line).append('\n');
                continue;
            }
            if (next < lorePlain.size() && lorePlain.get(next).equals(line)) {
                out.append("    [ =   ] ").append(line).append('\n');
                next++;
            } else {
                added++;
                out.append("    [+added] ").append(line).append('\n');
                out.append("    [+json ] ").append(json(tooltip.get(i), ops)).append('\n');
            }
        }
        for (int i = next; i < lorePlain.size(); i++) {
            out.append("    [-missing from tooltip] ").append(lorePlain.get(i)).append('\n');
        }
        out.append("    summary: ").append(added).append(" line(s) added on top of the raw lore\n");
    }

    // ------------------------------------------------------------------
    // Small readers
    // ------------------------------------------------------------------

    private static List<Component> loreLines(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore == null ? List.of() : lore.lines();
    }

    private static List<Component> tooltipLines(ItemStack stack, Minecraft minecraft) {
        try {
            return stack.getTooltipLines(Item.TooltipContext.of(minecraft.level),
                    minecraft.player, TooltipFlag.NORMAL);
        } catch (Throwable t) {
            // A handler that throws on this stack must not cost us the rest of the capture.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Probe] Tooltip build failed for one slot", t);
            return List.of();
        }
    }

    /**
     * The open screen. Goes through {@link GuiStateManager} rather than a field on
     * {@link Minecraft}: the field is a field on 26.1.2 and a method on 26.2, and that manager is
     * this codebase's one version-independent answer to the question.
     */
    private static Screen currentScreen() {
        return GuiStateManager.getInstance().getCurrentScreen();
    }

    private static Component title(AbstractContainerScreen<?> container) {
        Component title = container.getTitle();
        return title == null ? Component.empty() : title;
    }

    private static String menuType(AbstractContainerMenu menu) {
        try {
            var type = menu.getType();
            var key = BuiltInRegistries.MENU.getKey(type);
            return key == null ? String.valueOf(type) : key.toString();
        } catch (Throwable t) {
            // The player-inventory menu has no type and throws rather than returning null.
            return "(none)";
        }
    }

    /** How many of the menu's slots are the menu's own, by the same reckoning the features use. */
    private static int containerSlots(AbstractContainerMenu menu) {
        int count = 0;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                count++;
            }
        }
        return count;
    }

    /** The component as the game would serialize it - styles kept as structure, not flattened. */
    private static String json(Component component, RegistryOps<JsonElement> ops) {
        try {
            return ComponentSerialization.CODEC.encodeStart(ops, component)
                    .result().map(JsonElement::toString).orElse("<not encodable>");
        } catch (Throwable t) {
            return "<encode failed: " + t.getClass().getSimpleName() + ">";
        }
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

    /**
     * Flattened text, with legacy {@code §} codes removed.
     *
     * <p>{@link Component#getString()} already drops style-based formatting - a strikethrough span
     * arrives as plain text - which is why {@link #segments(Component)} exists beside this: the
     * struck-through half of a "was X, now Y" line is only visible there.
     */
    private static String plain(Component component) {
        return component.getString().replaceAll("§.", "");
    }

    /** Each styled run of a line as {@code "text"[styles]}, so formatting survives in plain text. */
    private static String segments(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            if (!text.isEmpty()) {
                if (out.length() > 0) {
                    out.append(" | ");
                }
                out.append('"').append(text).append('"').append(styleTag(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out.length() == 0 ? "(empty)" : out.toString();
    }

    private static String styleTag(Style style) {
        List<String> flags = new ArrayList<>(5);
        if (style.getColor() != null) {
            flags.add(style.getColor().serialize());
        }
        if (style.isBold()) {
            flags.add("bold");
        }
        if (style.isItalic()) {
            flags.add("italic");
        }
        if (style.isStrikethrough()) {
            flags.add("strikethrough");
        }
        if (style.isUnderlined()) {
            flags.add("underlined");
        }
        if (style.isObfuscated()) {
            flags.add("obfuscated");
        }
        return flags.isEmpty() ? "" : String.valueOf(flags);
    }

    /**
     * The capture path, with a suffix when that name is taken. The stamp only resolves to the second,
     * and a manual capture landing in the same second as an armed one would otherwise overwrite it -
     * losing the earlier of two files that were both worth having.
     */
    private static Path freeFile(String name) {
        Path file = SBSFiles.probeFile(name);
        for (int i = 2; Files.exists(file) && i < 100; i++) {
            file = SBSFiles.probeFile(name + "-" + i);
        }
        return file;
    }

    /** File name part: the menu title reduced to something a file system and a human both accept. */
    private static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "menu";
        }
        return LocalDateTime.now().format(STAMP) + "-"
                + (slug.length() > 32 ? slug.substring(0, 32) : slug);
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
