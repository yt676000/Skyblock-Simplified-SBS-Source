/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.itemprotection.model.ProtectionCategory;
import sbs.modid.client.helper.itemprotection.model.ProtectionMode;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The gate: the single place that answers "must this be refused, and what do I tell the player".
 * The three mixins around it are thin - each asks one question here and cancels.
 *
 * <h2>Where the veto happens, and why nothing can desync</h2>
 * Two choke points cover every way a protected item can leave:
 * {@code MultiPlayerGameMode.handleContainerInput} for anything done to a container, and
 * {@code LocalPlayer.drop} for the in-world drop key. Both are vetoed at HEAD, and the ordering
 * inside those methods is what makes the veto safe rather than merely early:
 * {@code handleContainerInput} calls {@code containerMenu.clicked(...)} <b>first</b> and then builds
 * {@code ServerboundContainerClickPacket} out of the slots that changed; {@code drop} calls
 * {@code removeFromSelected} before {@code connection.send}. Cancelling ahead of either means the
 * local menu is never mutated <i>and</i> no packet is sent, so the client and the server cannot come
 * to disagree - there is no ghost item to refresh away, because nothing was applied.
 *
 * <h2>What is examined</h2>
 * A click is only ever looked at when it would actually move a protected stack. {@link #movingStack}
 * resolves which stack that is, per action shape, and everything else returns immediately. A
 * pagination pane, a close button, a category icon and a hover are all "not a protected stack", so
 * none of them can be refused - the "never block a click that is not moving the item" rule is
 * structural here rather than a list of exceptions.
 *
 * <h2>Direction</h2>
 * Only movement <i>towards</i> a menu is examined. Pulling a protected item out of a menu and back
 * into the inventory is retrieval and is always allowed.
 *
 * <h2>Both halves of the screen, not just the container half</h2>
 * A vanilla menu does nothing when you click your own inventory except pick the stack up, and this
 * gate was written to that model - every click on a player-inventory slot was waved through. <b>A
 * Hypixel menu is server-drawn, and the server decides what a click means.</b> Inside an NPC shop,
 * clicking an item in your own inventory sells it on the spot, and that entire path was invisible
 * here: a protected item could be sold with one ordinary left click. So whenever
 * {@link #screenCategory} names the open screen as something that acts - a shop, a salvage menu, a
 * sack, or a menu we simply do not recognise - the player's own slots are examined as well. The
 * player's own inventory screen and the verified safe menus name no category, so tidying your
 * inventory in a backpack or an ender chest is untouched.
 *
 * <p>The screen is named by its <i>contents</i> first and its title second - see
 * {@link DestructiveScreens#sellCapable}. An NPC shop is titled with the NPC's name, so no title
 * table can ever match one.
 *
 * <h2>And the sale where no stack is clicked at all</h2>
 * Everything above still assumes a click that <i>moves something</i>, and the whole of the Bazaar
 * escapes through that assumption. Selling there is a press on "Sell Instantly" or "Create Sell
 * Offer": the button is a menu icon, the stock is taken by the server out of an inventory the
 * player never touched, and not one protected stack passes through the click. {@link #movingStack}
 * correctly answers "nothing is moving", and the sale goes through. So a second rule sits beside
 * the first - {@link #consumingControl} - which asks the <i>inventory</i> rather than the click:
 * when the pressed control is one that consumes items, anything protected in the inventory produces
 * a confirmation. It can only ever ask, because the client cannot know what such a control would
 * take, and it is reached only when the movement rule found nothing, so it can never downgrade a
 * hard block into a question.
 */
public final class ItemProtection {

    /** Minimum gap between two identical cues, so a held click cannot spam chat or sound. */
    private static final long FEEDBACK_COOLDOWN_MS = 400L;

    /** Unrecognised titles already logged, so one strange menu cannot fill the log. */
    private static final Set<String> loggedUnknownTitles = new HashSet<>();

    private static String lastMessage = "";
    private static long lastMessageAt;

    private ItemProtection() {
    }

    private static SBSConfig.ItemProtectionSettings cfg() {
        return ConfigManager.getInstance().get().itemProtection;
    }

    // ------------------------------------------------------------------ gating

    /**
     * Whether the feature may act at all: switched on, and on Hypixel SkyBlock.
     *
     * <p>The SkyBlock test is {@code SkyBlockLocation} answering with anything - it returns empty
     * strings when there is no sidebar, which is to say off SkyBlock. Asked through the shared
     * location source rather than by reading the scoreboard here, and cheap: that class throttles
     * its own refresh.
     */
    public static boolean enabled() {
        if (!cfg().enabled) {
            return false;
        }
        return !SkyBlockLocation.island().isEmpty() || !SkyBlockLocation.zone().isEmpty();
    }

    /**
     * Whether any interaction needs inspecting at all - the first question every hot path asks.
     * With an empty list this is one boolean and one map check, so an unused feature costs nothing.
     */
    private static boolean active() {
        return enabled() && !ProtectedItems.getInstance().isEmpty();
    }

    // ------------------------------------------------------------------ identity

    /** How a stack is protected, or {@code null} when it is not. */
    public enum Kind {
        /** This one physical item, by {@code ExtraAttributes.uuid}. */
        ITEM,
        /** Every stack of this kind, by {@code ExtraAttributes.id}. */
        TYPE
    }

    /**
     * The protection covering a stack: which list matched, and the key it matched under.
     *
     * <p>The uuid list is asked first so a specific item reports as {@link Kind#ITEM} even when its
     * type is also protected - that is the more precise thing to say in a message, and the identity
     * the confirm window should key on.
     */
    public record Protection(Kind kind, String key, String displayName) {
    }

    /**
     * The protection on a stack, or {@code null}.
     *
     * <p><b>Both lists are asked</b>, and that is deliberate: the two lists are separate for storing
     * and for marking - a toggle only ever writes to one - but "protect this item type" would not
     * mean what it says if the one Hyperion that happens to carry a uuid were the one it skipped.
     */
    public static Protection protectionOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ProtectedItems store = ProtectedItems.getInstance();
        String uuid = SkyblockItem.uuid(stack);
        if (uuid != null && store.isProtectedUuid(uuid)) {
            return new Protection(Kind.ITEM, uuid, displayName(stack));
        }
        String id = SkyblockItem.id(stack);
        if (id != null && store.isProtectedType(id)) {
            return new Protection(Kind.TYPE, id, displayName(stack));
        }
        return null;
    }

    /** Whether a stack is protected either way. The per-frame decorator's question. */
    public static boolean isProtected(ItemStack stack) {
        return protectionOf(stack) != null;
    }

    /** The stack's display name with formatting stripped - for messages and for the store. */
    public static String displayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return PlainText.strip(stack.getHoverName().getString()).trim();
    }

    // ------------------------------------------------------------------ the container funnel

    /**
     * Whether a container interaction must be refused. Asked at the HEAD of
     * {@code MultiPlayerGameMode.handleContainerInput}, before the local click is applied and before
     * any packet is built.
     *
     * @param player the acting player; its {@code containerMenu} resolves the slot ids
     * @param slotId the clicked menu slot, or {@code -999} for a click outside the window
     * @param button action-specific - for {@link ContainerInput#SWAP} the destination hotbar index
     * @param input  what the click is trying to do
     */
    public static boolean blocksContainerInput(Player player, int slotId, int button,
                                               ContainerInput input) {
        boolean blocked = decideContainerInput(player, slotId, button, input);
        if (cfg().debugLog) {
            ProtectionDebug.logClick(player, slotId, button, input, blocked);
        }
        return blocked;
    }

    /**
     * The decision itself. Split out so {@link ProtectionDebug} can wrap it and report the answer
     * beside the inputs that produced it - including on the paths that return early, which are the
     * ones worth seeing when protection did not fire and nobody can say why.
     */
    private static boolean decideContainerInput(Player player, int slotId, int button,
                                                ContainerInput input) {
        if (!active() || player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return false;
        }
        SBSConfig.ItemProtectionSettings cfg = cfg();

        // 1. The two drop shapes a container produces: THROW on a slot (Q / Ctrl+Q over it), and a
        //    PICKUP outside the window (-999) with the item on the cursor. Both leave the inventory
        //    entirely, whatever menu happens to be open, so the screen is not consulted.
        ItemStack dropping = null;
        if (input == ContainerInput.THROW && inRange(menu, slotId)) {
            dropping = menu.getSlot(slotId).getItem();
        } else if (input == ContainerInput.PICKUP && slotId == -999) {
            dropping = menu.getCarried();
        }
        if (dropping != null && !dropping.isEmpty()) {
            Protection protection = protectionOf(dropping);
            return protection != null && cfg.blockDrop
                    && decide(protection, ProtectionCategory.DROP, false);
        }

        String title = screenTitle();

        // 2. Movement towards the menu - and, in a menu that acts on the click server-side, movement
        //    inside the player's own inventory too. The screen is classified first because that is
        //    what decides whether the player-inventory half is even in scope; a safe menu and the
        //    player's own inventory screen leave it out, exactly as before.
        //
        //    Classifying reads every container slot's lore, so it is worth not paying for a click on
        //    a pagination arrow: nothing below can refuse an action none of whose stacks is
        //    protected, and this test covers every stack movingStack can name.
        //
        //    This rule is asked first and, whenever it has an answer, that answer stands. Rule 3 can
        //    only ever ask, so letting it claim a click this rule would have refused outright would
        //    quietly downgrade a hard block into a confirm - a press over a "Sell All" button with a
        //    protected item in the hotbar is exactly that shape.
        if (touchesProtectedStack(player, menu, slotId, button, input)) {
            Boolean verdict = movementVerdict(player, menu, slotId, button, input, title, cfg);
            if (verdict != null) {
                return verdict;
            }
        }

        // 3. Controls that consume the inventory without moving anything through the click:
        //    "Sell All", "Sell Instantly", "Create Sell Offer", "Salvage All", and the button on a
        //    sale confirmation screen. Rule 2 is structurally blind to all of them - it asks which
        //    stack the click moves, and the answer is none. The items are taken by the server out of
        //    an inventory the player never touched, which is why this asks the inventory rather than
        //    the click, and why it is the only rule that covers a Bazaar sale at all.
        ProtectionCategory consuming = consumingControl(menu, slotId, input, title);
        if (consuming != null && categoryEnabled(cfg, consuming)) {
            ItemStack atRisk = firstProtectedInInventory(player, title);
            if (atRisk != null) {
                // One sale, one question. A Bazaar instant sell is a button press followed by a
                // confirmation screen, and both are consuming controls, so without this the player
                // would be asked twice for one action - which teaches them to click through the
                // prompt, and a prompt people click through protects nothing.
                if (DestructiveScreens.isSaleConfirmation(title)
                        && ProtectionConfirm.grantedRecently(consuming, confirmWindowMs(cfg))) {
                    return false;
                }
                // Confirm-only, whatever the mode: we cannot know what such a control actually
                // consumes, and refusing an ordinary "sell all" outright on a guess would be worse
                // than asking.
                return decide(protectionOf(atRisk), consuming, true);
            }
        }
        return false;
    }

    /**
     * The movement rule's verdict, or {@code null} when it has nothing to say about this click and
     * the consuming-control rule should be asked instead.
     *
     * <p><b>Why a third answer and not just {@code false}.</b> "This click moves nothing protected"
     * and "this click must be allowed" are different statements, and collapsing them reopens the
     * very hole this class exists to close. A Hypixel menu icon is a real {@code ItemStack}, so a
     * Bazaar "Sell Instantly" button can itself be a stack of the product being sold - which makes
     * {@link #touchesProtectedStack} answer yes for a player who protected that type, while
     * {@link #movingStack} correctly answers that a press on a menu slot with an empty cursor moves
     * nothing. Returning {@code false} there would consume the click and skip the only rule that can
     * see the sale. Returning {@code null} hands it on.
     */
    private static Boolean movementVerdict(Player player, AbstractContainerMenu menu, int slotId,
                                           int button, ContainerInput input, String title,
                                           SBSConfig.ItemProtectionSettings cfg) {
        ProtectionCategory category = screenCategory(menu, title, cfg);
        ItemStack moving = movingStack(player, menu, slotId, button, input, category != null);
        if (moving == null || moving.isEmpty()) {
            return null;
        }
        Protection protection = protectionOf(moving);
        if (protection == null) {
            return null;   // not ours - never touch a click that is not moving a protected item
        }
        if (category == null) {
            return null;
        }
        if (category == ProtectionCategory.UNKNOWN) {
            logUnknown(title, protection);
        }
        if (!categoryEnabled(cfg, category)) {
            return null;
        }
        return decide(protection, category, false);
    }

    /**
     * The consuming action the clicked menu control advertises, or {@code null} when the click is
     * not a press on such a control.
     *
     * <p>Two signals, and the split between them is the point. The <b>control's own wording</b>
     * ({@code DestructiveScreens.consumingControl}) is a list of guesses that Hypixel can reword
     * without notice. The <b>screen being a sale confirmation</b> is the backstop, and it rests on
     * titles this tree already matches against the live game - so if "Sell Instantly" is renamed,
     * the sale is still caught one screen later, at the point where it actually completes.
     *
     * <p>Only container slots qualify: a player-inventory slot is not a control, and a click there
     * is rule 2's business. A verified safe menu is excluded outright - a backpack cannot consume
     * the inventory however its contents happen to be named.
     */
    static ProtectionCategory consumingControl(AbstractContainerMenu menu, int slotId,
                                               ContainerInput input, String title) {
        if (!isControlPress(input) || !inRange(menu, slotId)) {
            return null;
        }
        Slot clicked = menu.getSlot(slotId);
        if (isPlayerSlot(clicked)) {
            return null;
        }
        if (!title.isEmpty() && DestructiveScreens.isSafe(title)) {
            return null;
        }
        ItemStack control = clicked.getItem();
        ProtectionCategory byWording = DestructiveScreens.consumingControl(control);
        if (byWording != null) {
            return byWording;
        }
        // The backstop. A way out of the menu is the one control on a confirmation screen that
        // cannot sell anything, so asking about it would refuse a click that risks nothing.
        if (DestructiveScreens.isSaleConfirmation(title) && !DestructiveScreens.isBackControl(control)) {
            return ProtectionCategory.SELL;
        }
        return null;
    }

    /**
     * Whether a click shape can press a menu control. Every shape a Hypixel menu reads as a press:
     * a plain click, a double click, a shift-click and a number-key press over the slot all reach
     * the server as an interaction with it.
     */
    private static boolean isControlPress(ContainerInput input) {
        return input == ContainerInput.PICKUP || input == ContainerInput.PICKUP_ALL
                || input == ContainerInput.QUICK_MOVE || input == ContainerInput.SWAP;
    }

    /** How long a confirmation stays open, in milliseconds. */
    private static long confirmWindowMs(SBSConfig.ItemProtectionSettings cfg) {
        return Math.max(1, cfg.confirmSeconds) * 1000L;
    }

    /**
     * What the open menu would do to an item put into it, or {@code null} when it would do nothing
     * worth refusing - the player's own inventory screen, or a menu on the verified safe list.
     *
     * <p>Also the answer to "may the player-inventory half of this screen be examined at all": a
     * non-{@code null} category means the menu acts on clicks itself, and on Hypixel that makes a
     * click on your own inventory an action rather than a rearrangement.
     */
    static ProtectionCategory screenCategory(AbstractContainerMenu menu, String title,
                                             SBSConfig.ItemProtectionSettings cfg) {
        if (isOwnInventory(menu)) {
            return null;
        }
        ProtectionCategory category = DestructiveScreens.classify(menu, title);
        if (category != null) {
            return category;
        }
        // An empty title means the screen is not tracked yet, not that it is harmless - only the
        // menu-type test above may answer "this is the player's own inventory".
        if (!title.isEmpty() && DestructiveScreens.isSafe(title)) {
            return null;
        }
        return cfg.confirmUnknownMenus ? ProtectionCategory.UNKNOWN : null;
    }

    /**
     * The stack an interaction would put at risk, or {@code null} when the action risks nothing.
     * This method is the whole "never block the wrong click" rule.
     *
     * <ul>
     *   <li>{@code QUICK_MOVE} - shift-click. Only from a player-inventory slot: the same action on
     *       a menu slot is pulling an item <i>out</i>, which is retrieval and always allowed.</li>
     *   <li>{@code PICKUP} / {@code PICKUP_ALL} - the carried stack onto a menu slot; and, when
     *       {@code menuActsOnInventory}, the clicked stack in the player's own half.</li>
     *   <li>{@code QUICK_CRAFT} - a drag distributing the carried stack. Every stage is judged the
     *       same way, so a refused drag never advances and leaves no half-applied state.</li>
     *   <li>{@code SWAP} - a number-key or offhand swap. The item at risk is the one in the
     *       destination slot, which {@code slotId} does not name; reading {@code button} is the only
     *       way to see it at all.</li>
     *   <li>{@code CLONE} is creative-mode only and moves nothing; {@code THROW} is handled as a
     *       drop before this method is reached.</li>
     * </ul>
     *
     * <h3>Why the player's own half is examined at all</h3>
     * On a vanilla menu, clicking your own inventory rearranges it and nothing leaves, which is why
     * this method used to return {@code null} for every such click. <b>A Hypixel menu is not a
     * vanilla menu.</b> It is drawn by the server, and the server decides what a click means: inside
     * an NPC shop, clicking an item in your own inventory sells it outright. Every one of those
     * clicks was invisible here, and that is the bug this parameter exists to close. It is deliberately
     * not "always examine the player's half" - {@code menuActsOnInventory} is false for the player's
     * own inventory screen and for the verified safe menus, so ordinary inventory tidying is
     * untouched.
     *
     * @param menuActsOnInventory whether the open screen was classified as something that acts on a
     *                            click rather than merely holding items - see
     *                            {@link #screenCategory}
     */
    private static ItemStack movingStack(Player player, AbstractContainerMenu menu, int slotId,
                                         int button, ContainerInput input,
                                         boolean menuActsOnInventory) {
        Slot clicked = inRange(menu, slotId) ? menu.getSlot(slotId) : null;
        boolean playerSlot = isPlayerSlot(clicked);
        switch (input) {
            case QUICK_MOVE -> {
                if (playerSlot) {
                    return clicked.getItem();
                }
                return null;
            }
            case PICKUP, PICKUP_ALL -> {
                if (clicked == null) {
                    return null;
                }
                if (!playerSlot) {
                    return menu.getCarried();
                }
                // Picking a stack up out of your own inventory. Harmless in a menu that only holds
                // items; in a server-driven one it is the whole transaction. A non-empty cursor
                // makes it a deposit into your own inventory instead, which is retrieval.
                return menuActsOnInventory && menu.getCarried().isEmpty() ? clicked.getItem() : null;
            }
            case QUICK_CRAFT -> {
                // A drag's stage packets carry slot -999 with no meaningful slot, so the start and
                // end of every drag are judged here whatever it passed over. The stack at risk is
                // always the carried one - the item under the cursor mid-drag is not moving, and
                // returning it would refuse a drag that merely crosses a protected slot.
                return !playerSlot || menuActsOnInventory ? menu.getCarried() : null;
            }
            case SWAP -> {
                ItemStack destination = swapDestination(player, button);
                if (!playerSlot) {
                    return destination;
                }
                // A number key pressed over your own inventory only rearranges it in vanilla, so
                // nothing leaves. In a server-driven menu the press is read as an action on the
                // slot under the cursor, which puts both ends of the swap at risk.
                return menuActsOnInventory
                        ? firstProtected(clicked.getItem(), destination)
                        : null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The inventory stack a {@code SWAP} would move into the clicked slot, or {@code null} for a
     * button naming no slot.
     *
     * <p>{@code button} is a destination index, not a slot id: {@code 0-8} are the hotbar and
     * {@link Inventory#SLOT_OFFHAND} (40) is the offhand, which the client sends for the swap-offhand
     * key and its mouse binding alike. The offhand was missed before - the range test stopped at the
     * hotbar - so a protected item in the offhand could be swapped into a menu.
     */
    private static ItemStack swapDestination(Player player, int button) {
        if (button >= 0 && button < Inventory.getSelectionSize()) {
            return player.getInventory().getItem(button);
        }
        if (button == Inventory.SLOT_OFFHAND) {
            return player.getInventory().getItem(Inventory.SLOT_OFFHAND);
        }
        return null;
    }

    /**
     * Whether any stack this action could possibly move is protected - the cheap gate in front of
     * screen classification.
     *
     * <p>Deliberately wider than {@link #movingStack}: it names every stack that method can return,
     * without deciding which one an action actually moves, so it can never answer {@code false} for
     * a click the gate would have refused. Being wider is what makes it safe; being cheap is what
     * makes it worth having.
     */
    private static boolean touchesProtectedStack(Player player, AbstractContainerMenu menu,
                                                 int slotId, int button, ContainerInput input) {
        if (inRange(menu, slotId) && isProtected(menu.getSlot(slotId).getItem())) {
            return true;
        }
        if (isProtected(menu.getCarried())) {
            return true;
        }
        return input == ContainerInput.SWAP && isProtected(swapDestination(player, button));
    }

    /** The first protected stack among the candidates, or {@code null} when none of them is. */
    private static ItemStack firstProtected(ItemStack... candidates) {
        for (ItemStack candidate : candidates) {
            if (candidate != null && !candidate.isEmpty() && isProtected(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The protected stack in the player's inventory that a consuming control is most likely about,
     * or {@code null} when nothing in the inventory is protected.
     *
     * <p>A consuming control names no slot, so the client cannot know which items it would take -
     * which is exactly why the answer only ever asks rather than refuses. It can, however, usually
     * name the right item: a Bazaar product page is titled "&#10145; Enchanted Diamond", so a
     * protected stack whose name the title mentions is the one to say out loud. Without that, a
     * player selling Enchanted Diamonds is warned about a Hyperion sitting in their hotbar, which
     * reads as a bug even though the prompt is doing its job.
     *
     * <p><b>Cosmetic only.</b> The preference picks which protected item is named in the message; it
     * never decides whether to ask. Anything protected in the inventory produces the question.
     */
    private static ItemStack firstProtectedInInventory(Player player, String title) {
        String haystack = DestructiveScreens.normalize(title);
        Inventory inventory = player.getInventory();
        ItemStack first = null;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !isProtected(stack)) {
                continue;
            }
            if (first == null) {
                first = stack;
            }
            String name = displayName(stack).toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && haystack.contains(name)) {
                return stack;
            }
        }
        return first;
    }

    private static boolean inRange(AbstractContainerMenu menu, int slotId) {
        return slotId >= 0 && slotId < menu.slots.size();
    }

    /** Whether a menu slot is one of the player's own - the destination side of "leaving". */
    private static boolean isPlayerSlot(Slot slot) {
        return slot != null && slot.container instanceof Inventory;
    }

    /**
     * Whether the open menu is the player's own inventory screen. Asked by menu type rather than by
     * title, so it holds in every client language and cannot be spoofed by a menu that happens to be
     * named "Inventory".
     */
    private static boolean isOwnInventory(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu;
    }

    // ------------------------------------------------------------------ the drop key

    /**
     * Whether the in-world drop key must be refused. Asked at the HEAD of {@code LocalPlayer.drop},
     * so nothing is removed locally and no packet is sent.
     *
     * <p>The dungeon exemption is <b>not</b> here: it belongs to the mixin, because in the Catacombs
     * the press is a class ability rather than a drop and must never reach any veto at all.
     */
    public static boolean blocksDrop(Player player) {
        if (!active() || player == null || !cfg().blockDrop) {
            return false;
        }
        ItemStack selected = player.getInventory().getSelectedItem();
        Protection protection = protectionOf(selected);
        return protection != null && decide(protection, ProtectionCategory.DROP, false);
    }

    // ------------------------------------------------------------------ decision + feedback

    private static boolean categoryEnabled(SBSConfig.ItemProtectionSettings cfg,
                                           ProtectionCategory category) {
        return switch (category) {
            case DROP -> cfg.blockDrop;
            case SELL -> cfg.blockSell;
            case SALVAGE -> cfg.blockSalvage;
            case SACK -> cfg.blockSack;
            case AUCTION -> cfg.blockAuction;
            case CONSUME -> cfg.blockConsume;
            case UNKNOWN -> cfg.confirmUnknownMenus;
        };
    }

    /**
     * Refuse or allow, and say so. Returns {@code true} when the caller must cancel.
     *
     * @param forceConfirm the caller knows this action can only ever be asked about, never refused
     *                     outright - the bulk buttons, whose target the client cannot see
     */
    private static boolean decide(Protection protection, ProtectionCategory category,
                                  boolean forceConfirm) {
        if (protection == null) {
            return false;
        }
        SBSConfig.ItemProtectionSettings cfg = cfg();
        boolean confirmable = forceConfirm || category.confirmOnly() || cfg.mode == ProtectionMode.CONFIRM;
        String name = protection.displayName().isEmpty() ? "That item" : protection.displayName();

        if (!confirmable) {
            cue(name + " is protected - it cannot be " + category.verb()
                    + ". Unprotect it first.", 0.6f);
            return true;
        }
        long window = Math.max(1, cfg.confirmSeconds) * 1000L;
        if (ProtectionConfirm.offer(protection.key(), category, window)) {
            return false;   // the confirming repeat: let it through untouched
        }
        cue(name + " is protected - click again within " + Math.max(1, cfg.confirmSeconds)
                + "s to confirm.", 1.0f);
        return true;
    }

    /** Chat line plus the optional sound, with identical repeats rate-limited. */
    private static void cue(String message, float pitch) {
        long now = System.currentTimeMillis();
        if (message.equals(lastMessage) && now - lastMessageAt < FEEDBACK_COOLDOWN_MS) {
            return;
        }
        lastMessage = message;
        lastMessageAt = now;
        SBSChat.send(Component.literal(" " + message).withColor(0xFFC83F));
        if (cfg().denySound) {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, pitch);
            }
        }
    }

    /**
     * Records a menu that was neither recognised as destructive nor as safe, once per title.
     *
     * <p>This is the line that turns the guessed patterns in {@code DestructiveScreens} into real
     * ones: it prints exactly what Hypixel called the screen, so a single trip in game replaces a
     * hypothesis with a string somebody has seen.
     */
    private static void logUnknown(String title, Protection protection) {
        String key = DestructiveScreens.normalize(title);
        if (!loggedUnknownTitles.add(key)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Protect] unrecognised menu \"{}\" - asking before letting protected item "
                        + "'{}' leave. Classify it in DestructiveScreens if this is wrong.",
                title, protection.displayName());
    }

    // ------------------------------------------------------------------ marking

    /**
     * Toggles protection for a stack and reports it in chat. Returns whether the key was consumed -
     * it always is once the feature is on, so a bound key never also picks the item up.
     *
     * <p>Which list is written is decided by the stack alone: a uuid goes to the unique list, an id
     * without a uuid to the type list. Nothing ever writes to both, so the two lists cannot drift
     * into holding the same item twice.
     */
    public static boolean toggle(ItemStack stack) {
        if (!cfg().enabled) {
            return false;
        }
        if (!enabled()) {
            cue("Item Protection only acts on Hypixel SkyBlock.", 0.8f);
            return true;
        }
        if (stack == null || stack.isEmpty()) {
            return true;   // empty slot: consumed, silently - nothing to say about nothing
        }
        ProtectedItems store = ProtectedItems.getInstance();
        String name = displayName(stack);
        String id = SkyblockItem.id(stack);
        String uuid = SkyblockItem.uuid(stack);

        if (uuid != null) {
            if (store.forgetUuid(uuid)) {
                cue(name + " is no longer protected.", 1.2f);
            } else {
                store.protectUuid(uuid, id, name);
                cue(name + " is now protected.", 1.4f);
            }
            return true;
        }
        if (id != null) {
            if (store.forgetType(id)) {
                cue("Every " + name + " is no longer protected.", 1.2f);
            } else {
                store.protectType(id, name);
                cue("Every " + name + " is now protected.", 1.4f);
            }
            return true;
        }
        cue(name + " carries no SkyBlock identity, so it cannot be protected.", 0.7f);
        return true;
    }

    // ------------------------------------------------------------------ misc

    /**
     * The open container screen's raw title, or {@code ""} when no container screen is open.
     *
     * <p>Read through {@code GuiStateManager} rather than off {@code Minecraft}: this version has no
     * public {@code screen} field, which is the reason that tracker exists at all.
     */
    static String screenTitle() {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (screen instanceof AbstractContainerScreen<?> container && container.getTitle() != null) {
            return container.getTitle().getString();
        }
        return "";
    }
}
