/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.player.PetIconCache;
import sbs.modid.client.core.tab.TabWidgets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the player's active SkyBlock pet for the "Show Active Pet" HUD card.
 *
 * <p><b>Identity and skin come from a pet you were seen picking; the level comes from the tab
 * list.</b> Those two jobs are deliberately split, because only one source can do each:
 * <ul>
 *   <li><b>The pick</b> - the pet you click in the Pets menu ({@link #onMenuSlotClicked}), the pet
 *       carried by a loadout you equip, or the pet the menu scan finds already summoned. This is the
 *       <i>only</i> source of the real menu stack, and with it the pet's skull skin and its held
 *       item. Whatever it captures is remembered per profile, skin included.</li>
 *   <li><b>The level</b> - Hypixel's tab-list "Pet" widget ({@link #readTab()}), the only source that
 *       keeps up on its own. A pick tells us which pet, and from then on the widget is what moves the
 *       level and the XP bar; the menu is not open often enough to do that job.</li>
 * </ul>
 *
 * <p>When the widget names a pet that is <b>not</b> the one we captured - swapped by Autopet, by a
 * command, on another device - we have its name and level but no skin for it, so the card falls back
 * to the <b>default pet</b> icon ({@link sbs.modid.client.core.player.PetIconCache#iconOrFallback})
 * rather than leaving the previous pet's head on a pet it does not belong to. The captured skin is
 * kept, not thrown away: swap back and it returns without another menu visit.
 *
 * <p>Two further fallbacks fill the gaps: <b>Autopet / summon chat</b> ("Autopet equipped your
 * [Lvl 100] Jellyfish!") for name and level on servers with the widget switched off, and the
 * <b>per-profile cache</b> so the card is filled the moment you log in.
 */
public final class PetTracker implements ProfileScopedStore {

    private static final PetTracker INSTANCE = new PetTracker();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);
    private static final Pattern NAME_LEVEL = Pattern.compile("\\[Lvl\\s*([0-9]+)]\\s*(.+)");
    private static final Pattern PROGRESS = Pattern.compile("Progress to Level\\s*[0-9]+:\\s*([0-9.]+)%");
    private static final Pattern AUTOPET = Pattern.compile("Autopet equipped your \\[Lvl\\s*([0-9]+)]\\s*([^!]+)!");
    /** Manual / loadout summon: "You summoned your [Lvl 100] Golden Dragon!". */
    private static final Pattern SUMMON = Pattern.compile("You summoned your \\[Lvl\\s*([0-9]+)]\\s*([^!]+)!");
    /** The absolute XP lore line under the progress bar: "5,862.2/1.6m". */
    private static final Pattern XP_LINE = Pattern.compile("^([0-9,.]+[kKmMbB]?)\\s*/\\s*([0-9,.]+[kKmMbB]?)$");
    /** The tab widget's XP row: "126,351.3/192.7k XP (65.6%)" - anywhere in the line. */
    private static final Pattern TAB_XP = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?)\\s*/\\s*([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?)");
    /** The percentage on that same row, on its own or in brackets. */
    private static final Pattern TAB_PERCENT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%");

    /** Pet exp share: 100% from the pet's own skill, one third from every other skill. */
    private static final double MATCHING_SKILL_SHARE = 1.0;
    private static final double OTHER_SKILL_SHARE = 1.0 / 3.0;

    /** Pet -> levelling skill (community data). Unknown pets fall back to the conservative share. */
    private static final java.util.Map<String, String> PET_SKILL = java.util.Map.ofEntries(
            java.util.Map.entry("ENDER_DRAGON", "COMBAT"), java.util.Map.entry("TIGER", "COMBAT"),
            java.util.Map.entry("LION", "COMBAT"), java.util.Map.entry("WOLF", "COMBAT"),
            java.util.Map.entry("TARANTULA", "COMBAT"), java.util.Map.entry("BLAZE", "COMBAT"),
            java.util.Map.entry("GOLEM", "COMBAT"), java.util.Map.entry("GRIFFIN", "COMBAT"),
            java.util.Map.entry("PHOENIX", "COMBAT"), java.util.Map.entry("SPIDER", "COMBAT"),
            java.util.Map.entry("HOUND", "COMBAT"), java.util.Map.entry("ZOMBIE", "COMBAT"),
            java.util.Map.entry("SKELETON", "COMBAT"), java.util.Map.entry("ENDERMAN", "COMBAT"),
            java.util.Map.entry("MAGMA_CUBE", "COMBAT"), java.util.Map.entry("GHOUL", "COMBAT"),
            java.util.Map.entry("BABY_YETI", "FISHING"), java.util.Map.entry("SQUID", "FISHING"),
            java.util.Map.entry("DOLPHIN", "FISHING"), java.util.Map.entry("FLYING_FISH", "FISHING"),
            java.util.Map.entry("BLUE_WHALE", "FISHING"), java.util.Map.entry("MEGALODON", "FISHING"),
            java.util.Map.entry("SILVERFISH", "MINING"), java.util.Map.entry("ROCK", "MINING"),
            java.util.Map.entry("MITHRIL_GOLEM", "MINING"), java.util.Map.entry("BAL", "MINING"),
            java.util.Map.entry("SCATHA", "MINING"), java.util.Map.entry("MOLE", "MINING"),
            java.util.Map.entry("BEE", "MINING"), java.util.Map.entry("GLACITE_GOLEM", "MINING"),
            java.util.Map.entry("MONKEY", "FORAGING"), java.util.Map.entry("OCELOT", "FORAGING"),
            java.util.Map.entry("GIRAFFE", "FORAGING"),
            java.util.Map.entry("ELEPHANT", "FARMING"), java.util.Map.entry("MOOSHROOM_COW", "FARMING"),
            java.util.Map.entry("RABBIT", "FARMING"), java.util.Map.entry("BEE_FARMING", "FARMING"),
            java.util.Map.entry("CHICKEN", "FARMING"), java.util.Map.entry("PIG", "FARMING"),
            java.util.Map.entry("PARROT", "ALCHEMY"), java.util.Map.entry("JELLYFISH", "ALCHEMY"),
            java.util.Map.entry("SHEEP", "ENCHANTING"), java.util.Map.entry("GUARDIAN", "ENCHANTING"));

    private volatile boolean active;
    private volatile String name = "";
    private volatile int level;
    private volatile double xpPercent = -1;
    private volatile String heldItem = "";
    private volatile ItemStack icon = ItemStack.EMPTY;
    /**
     * Which pet {@link #icon} was captured from. The skin is only ever drawn for that pet: once the
     * tab list names a different one we hold no stack for it, and showing the old head on it would be
     * a confident lie. Kept (rather than cleared) so swapping back restores the real skin for free.
     */
    private volatile String iconName = "";

    /** Live XP maths: absolute progress within the current level (calibrated from menu/tab). */
    private volatile double xpCurrent = -1;
    private volatile double xpNeeded = -1;

    /** True when the pet is max level – shown as "MAX LEVEL" instead of percent / xp. */
    private volatile boolean maxLevel;

    private int ticksUntilScan;

    private PetTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static PetTracker getInstance() {
        return INSTANCE;
    }

    public boolean hasPet() {
        return active && !name.isEmpty();
    }

    public String name() {
        return name;
    }

    public int level() {
        return level;
    }

    /**
     * XP progress to the next level in percent, or {@code -1} when unknown.
     *
     * <p><b>A published percentage beats one worked out from the figures.</b> Both sources hand over
     * the percentage and the absolute XP in the same reading, but the larger of those two figures
     * carries Hypixel's own shorthand: the tab row "388,653.9/1.9M XP (20.6%)" means 1,886,669 and
     * not 1,900,000, so dividing gave 20.5% and the card contradicted the widget it had just read.
     * The maths is rounded twice over - it cannot recover a digit the source never printed - so it is
     * kept only for the cases where nobody published a percentage at all, and for the stretch where
     * {@link #onSkillXp} is carrying the bar between readings: there the figures have moved on and
     * the last published percentage has not.
     */
    public double xpPercent() {
        if (xpPercent >= 0) {
            return xpPercent;
        }
        double needed = xpNeeded;
        if (needed > 0 && xpCurrent >= 0) {
            return Math.min(100.0, xpCurrent / needed * 100.0);
        }
        return -1;
    }

    /** Absolute progress text ("5,862/1.6M"), or empty when only the percentage is known. */
    public String xpText() {
        if (xpNeeded <= 0 || xpCurrent < 0) {
            return "";
        }
        return compact(xpCurrent) + "/" + compact(xpNeeded);
    }

    /**
     * Live XP: skill XP gains from the action bar ("+40 Combat (…)") feed the pet's counter –
     * full share from the pet's own skill, a third from every other – keeping the percentage
     * moving between menu visits.
     *
     * <p>Only a stand-in for the tab widget: while that is publishing the real figures twice a second
     * this stands down entirely, since an estimate layered on top of a measurement can only make the
     * bar jitter backwards each time the widget corrects it.
     */
    public void onSkillXp(String skill, double amount) {
        if (!active || xpNeeded <= 0 || xpCurrent < 0 || amount <= 0 || tabXpIsLive()) {
            return;
        }
        String petSkill = PET_SKILL.get(sbs.modid.client.core.item.SkyblockItem.normalizeName(name));
        double share = skill.equalsIgnoreCase(petSkill) ? MATCHING_SKILL_SHARE : OTHER_SKILL_SHARE;
        xpCurrent = Math.min(xpNeeded, xpCurrent + amount * share);
        // The figures have now moved past the percentage that was published alongside them, so the
        // ratio is the fresher of the two: drop the stale reading rather than freeze the bar on it.
        xpPercent = -1;
    }

    private static String compact(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    public String heldItem() {
        return heldItem;
    }

    public boolean isMaxLevel() {
        return maxLevel;
    }

    /**
     * The pet's head: the captured menu stack when it belongs to the pet currently shown, and the
     * default pet icon when it does not (see the class doc). Never empty, so the card always has
     * something in its icon slot.
     */
    public ItemStack icon() {
        resolvePendingIcon();
        if (!icon.isEmpty() && !name.isEmpty() && name.equalsIgnoreCase(iconName)) {
            return icon;
        }
        return PetIconCache.getInstance()
                .iconOrFallback(sbs.modid.client.core.item.SkyblockItem.normalizeName(name));
    }

    /**
     * The real stack captured for the pet shown now (its applied skin included), or
     * {@link ItemStack#EMPTY} when none was captured for it. Unlike {@link #icon()} it never falls
     * back to the default pet head, so a caller can tell "the real skin" from "a stand-in".
     */
    public ItemStack capturedIcon() {
        resolvePendingIcon();
        return !icon.isEmpty() && !name.isEmpty() && name.equalsIgnoreCase(iconName) ? icon : ItemStack.EMPTY;
    }

    /** Scans the tab list, and the Pets menu when it is open, for the active pet; ~twice per second. */
    public void tick(Minecraft minecraft) {
        try {
            if (minecraft == null || --ticksUntilScan > 0) {
                return;
            }
            ticksUntilScan = 10;
            ensureLoaded();
            readTab();
            Screen screen = GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            String title = strip(screen.getTitle() != null ? screen.getTitle().getString() : "");
            if (!title.toLowerCase(Locale.ROOT).contains("pets")) {
                return;
            }
            AbstractContainerMenu menu = container.getMenu();
            int upper = Math.max(0, menu.getItems().size() - 36);
            for (int i = 0; i < upper; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (parseMenuPet(stack)) {
                    return; // active pet found
                }
            }
        } catch (Throwable ignored) {
            // scan must never break the tick
        }
    }

    /** Everything one Pets-menu entry says about its pet. */
    private record MenuPet(String name, int level, boolean maxLevel, double percent,
                           double current, double needed, String held, boolean summoned) {
    }

    /**
     * Reads one Pets-menu entry, or {@code null} when the stack is not a pet at all.
     *
     * <p>{@code summoned} is the entry's "Click to despawn" line: that is Hypixel telling you what
     * the click will <i>do</i>, so on the currently summoned pet it reads "despawn" and on every
     * other one "summon". It identifies the active pet for the scan and tells the click handler which
     * way the click goes.
     */
    private MenuPet readMenuPet(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return null;
        }
        Matcher nameMatcher = NAME_LEVEL.matcher(strip(stack.getHoverName().getString()).trim());
        if (!nameMatcher.find()) {
            return null;
        }
        String petName = cleanPetName(nameMatcher.group(2));
        if (petName.isEmpty()) {
            return null;
        }
        boolean summoned = false;
        boolean isMax = false;
        double progress = -1;
        double cur = -1;
        double needed = -1;
        String held = "";
        for (Component line : lore.lines()) {
            String text = strip(line.getString()).trim();
            if (text.contains("Click to despawn")) {
                summoned = true;
            }
            if (text.contains("MAX LEVEL")) {
                isMax = true;
            }
            Matcher m = PROGRESS.matcher(text);
            if (m.find()) {
                try {
                    progress = Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            Matcher xp = XP_LINE.matcher(text);
            if (xp.matches()) {
                cur = parseAmount(xp.group(1));
                needed = parseAmount(xp.group(2));
            }
            if (text.startsWith("Held Item:")) {
                held = text.substring("Held Item:".length()).trim();
            }
        }
        return new MenuPet(petName, Integer.parseInt(nameMatcher.group(1)),
                isMax, progress, cur, needed, held, summoned);
    }

    /** Parses one Pets-menu entry; returns true when it was the active pet. */
    private boolean parseMenuPet(ItemStack stack) {
        MenuPet pet = readMenuPet(stack);
        if (pet == null || !pet.summoned()) {
            return false;
        }
        // The tab widget publishes the same figures and keeps publishing them, so the menu only fills
        // the XP in when the widget is not carrying it (it is one of the toggleable ones).
        adopt(pet, stack, !tabXpIsLive());
        return true;
    }

    /**
     * A click on a slot in the Pets menu - <b>the pick</b>, and the moment the skin is captured.
     *
     * <p>Called before the click reaches the server, from the pet entry you actually clicked, so the
     * card follows the swap instantly and with the right head on it. Waiting for the passive scan
     * would not do: the menu closes on a summon, and the entry that was clicked is gone by the time
     * the next scan runs.
     *
     * <p>Clicking the summoned pet despawns it, which is the one case where a click means "no pet".
     */
    public void onMenuSlotClicked(String screenTitle, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty() || screenTitle == null
                    || !strip(screenTitle).toLowerCase(Locale.ROOT).contains("pets")) {
                return;
            }
            MenuPet pet = readMenuPet(stack);
            if (pet == null) {
                return;
            }
            if (pet.summoned()) {
                this.active = false;   // clicking the summoned pet puts it away
                return;
            }
            // Straight from the menu entry, so its XP is as good as it gets - the widget has not even
            // heard about this pet yet.
            adopt(pet, stack, true);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Pet] picked [Lvl {}] {} from the Pets menu (skin captured).",
                    pet.level(), pet.name());
        } catch (Throwable ignored) {
            // a click must never break because the HUD card wanted to know about it
        }
    }

    /**
     * Takes a menu entry as the active pet, skin and all.
     *
     * <p>{@code trustXp} is false only for the passive scan while the tab widget is supplying live
     * figures - re-applying the menu's older numbers over those would make the bar jump backwards.
     */
    private void adopt(MenuPet pet, ItemStack stack, boolean trustXp) {
        this.level = pet.level();
        this.name = pet.name();
        this.maxLevel = pet.maxLevel();
        if (trustXp) {
            this.xpPercent = pet.percent();
            this.xpCurrent = pet.current();
            this.xpNeeded = pet.needed();
        }
        this.heldItem = pet.held();
        this.icon = stack.copyWithCount(1);   // the real menu icon, skull skin included
        this.iconName = pet.name();
        this.pendingIconSnbt = "";            // a live icon supersedes any remembered one
        this.active = true;
        // The menu is the ONLY source of the real icon and the held item, and it used to keep both
        // to itself - nothing here wrote the cache, so a restart brought back a pet with a name and
        // a level but no head and no held item. Saved on a real change rather than on every scan:
        // the passive scan runs twice a second for as long as the menu stays open.
        saveIfChanged();
    }

    /** Parses "5,862.2", "1.6m", "300k", "1.2b" into a plain double. */
    private static double parseAmount(String raw) {
        try {
            String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
            double factor = 1;
            if (text.endsWith("k")) {
                factor = 1_000;
                text = text.substring(0, text.length() - 1);
            } else if (text.endsWith("m")) {
                factor = 1_000_000;
                text = text.substring(0, text.length() - 1);
            } else if (text.endsWith("b")) {
                factor = 1_000_000_000;
                text = text.substring(0, text.length() - 1);
            }
            return Double.parseDouble(text) * factor;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Chat updates: Autopet swaps, manual / loadout summons, and manual despawns. */
    public void parseChat(String message) {
        if (message == null) {
            return;
        }
        String text = strip(message);
        // Autopet AND "You summoned your ..." both announce the new active pet - the summon line is
        // what fires when a loadout swap changes the pet, so tracking it keeps the HUD in sync.
        Matcher autopet = AUTOPET.matcher(text);
        Matcher summon = SUMMON.matcher(text);
        Matcher match = autopet.find() ? autopet : summon.find() ? summon : null;
        if (match != null) {
            setActivePet("[Lvl " + match.group(1) + "] " + match.group(2).trim(), ItemStack.EMPTY);
            return;
        }
        if (text.contains("You despawned your")) {
            this.active = false;
        }
    }

    /**
     * Sets the active pet from a "[Lvl X] Name" line (an Autopet / summon message, or the pet listed
     * in a loadout being equipped). XP progress is unknown until the next Pets-menu scan re-reads it;
     * a non-empty {@code icon} (e.g. the loadout's resolved pet head) is shown until then.
     */
    public void setActivePet(String petLine, ItemStack icon) {
        if (petLine == null) {
            return;
        }
        Matcher matcher = NAME_LEVEL.matcher(strip(petLine).trim());
        if (!matcher.find()) {
            return;
        }
        String newName = cleanPetName(matcher.group(2));
        if (newName.isEmpty()) {
            return;
        }
        this.level = Integer.parseInt(matcher.group(1));
        this.name = newName;
        this.xpPercent = -1;   // unknown until the menu is opened again
        this.xpCurrent = -1;
        this.xpNeeded = -1;
        this.maxLevel = false;
        this.heldItem = "";
        if (icon != null && !icon.isEmpty()) {
            // A loadout supplies the pet's resolved head - a pick like any other, so it is captured
            // as this pet's skin.
            this.icon = icon.copyWithCount(1);
            this.iconName = newName;
            this.pendingIconSnbt = "";
        }
        // No icon supplied (a chat line): nothing is dropped. icon() shows the captured skin only
        // while it belongs to this pet and the default pet otherwise, so a stale head cannot leak
        // through, and the old skin is still there if you swap back.
        this.active = true;
        save();
    }

    /** Forgets the captured skin entirely - decoded, pending and its owner alike. */
    private void clearIcon() {
        this.icon = ItemStack.EMPTY;
        this.pendingIconSnbt = "";
        this.iconName = "";
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }

    // ------------------------------------------------------------------
    // Tab list
    // ------------------------------------------------------------------

    /** How many lines after the "Pet" header may still carry the pet itself. */
    private static final int PET_SECTION_LOOKAHEAD = 3;
    /** How long a tab XP reading keeps the other sources out of the XP figures. */
    private static final long TAB_XP_TTL_MS = 5_000L;
    /** Throttle for the tuning log below. */
    private long lastTabLogAt;
    /** Whether a "Pet" widget header has been seen at all this session - see {@link #readTab()}. */
    private boolean tabHeaderSeen;
    /** When the tab widget last handed us real XP figures. */
    private volatile long lastTabXpAt;

    /** True while the tab widget is supplying the XP figures - it then owns them outright. */
    private boolean tabXpIsLive() {
        return System.currentTimeMillis() - lastTabXpAt < TAB_XP_TTL_MS;
    }

    /** The XP figures a tab-list "Pet" section carries, if any. */
    private record TabProgress(double current, double needed, double percent, boolean maxLevel) {
        static final TabProgress NONE = new TabProgress(-1, -1, -1, false);

        boolean hasFigures() {
            return needed > 0 && current >= 0;
        }
    }

    /**
     * Reads the active pet from Hypixel's tab-list "Pet" widget - name, level <b>and</b> the XP row.
     *
     * <p>This is the primary source: the Pets menu is read when you happen to open it and the chat
     * messages only fire on a swap, so anything sourced from those is stale the moment you gain XP.
     * The widget is republished continuously, so a level-up, a pet swapped by any means and the XP
     * creeping up all land within half a second.
     *
     * <p>Deliberately tolerant about the layout: the pet may sit on the header line
     * ("Pet: [Lvl 100] Golden Dragon") or on one of the lines under it, and the XP row may carry the
     * absolute figures, the percentage, both or neither. What it will not do is match <b>"Pests"</b>,
     * which shares the first three letters with "Pet" and would otherwise turn a Garden pest count
     * into a pet.
     */
    private void readTab() {
        List<String> lines = TabWidgets.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (!isPetHeader(lines.get(i).trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            tabHeaderSeen = true;
            int last = Math.min(lines.size() - 1, i + PET_SECTION_LOOKAHEAD);
            int petLine = -1;
            for (int j = i; j <= last && petLine < 0; j++) {
                if (NAME_LEVEL.matcher(strip(lines.get(j)).trim()).find()) {
                    petLine = j;
                }
            }
            if (petLine < 0) {
                logTabFormat(lines.subList(i, last + 1));
                continue;
            }
            // The XP row sits under the pet, so only the rest of the section can hold it.
            applyTabPet(lines.get(petLine), readTabProgress(lines, petLine + 1, last));
            return;
        }
        // No "Pet" header anywhere. Either the widget is switched off (/widget), or the lines are not
        // reaching us at all - and those two look identical from the card, which is why the whole tab
        // gets dumped once. Only until a header has been seen once this session: after that a missing
        // one is the player toggling the widget, not something to investigate.
        if (!tabHeaderSeen) {
            logTabFormat(lines.size() > 24 ? lines.subList(0, 24) : lines);
        }
    }

    /** True for the "Pet" widget header - and false for "Pests", which merely starts the same. */
    private static boolean isPetHeader(String lower) {
        if (!lower.startsWith("pet")) {
            return false;
        }
        return lower.length() == 3 || !Character.isLetter(lower.charAt(3));
    }

    /**
     * Scans the lines under the pet for its XP row ("126,351.3/192.7k XP (65.6%)").
     *
     * <p>Stops at the first line carrying a colon: blank widget separators are dropped before we ever
     * see them, so the next thing after the section is the following widget's header - and a header
     * like "Bank: 1B / 100.8M" would otherwise read as a perfectly good XP row.
     */
    private static TabProgress readTabProgress(List<String> lines, int from, int to) {
        double current = -1;
        double needed = -1;
        double percent = -1;
        boolean max = false;
        for (int i = from; i <= to; i++) {
            String text = strip(lines.get(i)).trim();
            if (text.indexOf(':') >= 0) {
                break;   // the next widget's header - this section has ended
            }
            if (text.toUpperCase(Locale.ROOT).contains("MAX LEVEL")) {
                max = true;
                continue;
            }
            Matcher xp = TAB_XP.matcher(text);
            if (xp.find()) {
                current = parseAmount(xp.group(1));
                needed = parseAmount(xp.group(2));
            }
            Matcher pct = TAB_PERCENT.matcher(text);
            if (pct.find()) {
                try {
                    percent = Double.parseDouble(pct.group(1));
                } catch (NumberFormatException ignored) {
                    // leave it unknown
                }
            }
        }
        return current < 0 && needed < 0 && percent < 0 && !max
                ? TabProgress.NONE : new TabProgress(current, needed, percent, max);
    }

    /**
     * Applies the tab list's pet: identity from the "[Lvl N] Name" line, XP from the row under it.
     *
     * <p>Runs twice a second, so it is careful about two things. The identity is only written when it
     * actually changed - {@link #setActivePet} cannot be reused here because it clears the XP, and
     * doing that twice a second would leave the card permanently without a bar. And the XP figures are
     * only cleared on a real level-up or swap: when the widget carries no XP row (Hypixel lets you
     * switch parts of it off) whatever the Pets menu worked out stays on the card.
     */
    private void applyTabPet(String line, TabProgress progress) {
        Matcher matcher = NAME_LEVEL.matcher(strip(line).trim());
        if (!matcher.find()) {
            return;
        }
        int tabLevel = Integer.parseInt(matcher.group(1));
        String tabName = cleanPetName(matcher.group(2));
        if (tabName.isEmpty()) {
            return;
        }
        boolean samePet = tabName.equalsIgnoreCase(name);
        boolean unchanged = active && samePet && tabLevel == level;

        if (!unchanged) {
            level = tabLevel;
            name = tabName;
            active = true;
            // A level-up invalidates the progress within the level; a different pet invalidates
            // everything that described the old one.
            xpPercent = -1;
            xpCurrent = -1;
            xpNeeded = -1;
            maxLevel = false;
            if (!samePet) {
                // The held item belonged to the old pet. The captured skin is NOT dropped: it is
                // simply no longer this pet's, which icon() decides by name - so swapping back
                // restores the real head without another menu visit, and until then the card shows
                // the default pet.
                heldItem = "";
            }
        }

        // The widget is the live source of the progress, so it overwrites what the menu left behind
        // rather than deferring to it - that is the whole point of reading it here.
        if (progress.maxLevel()) {
            maxLevel = true;
        }
        if (progress.hasFigures()) {
            xpCurrent = progress.current();
            xpNeeded = progress.needed();
            lastTabXpAt = System.currentTimeMillis();
        }
        if (progress.percent() >= 0) {
            xpPercent = progress.percent();
            lastTabXpAt = System.currentTimeMillis();
        } else if (progress.hasFigures()) {
            // Figures without a percentage: Hypixel published this reading without one, so the
            // percentage from an earlier reading no longer describes it. Cleared rather than kept,
            // which puts the ratio back in charge until the next row carries one.
            xpPercent = -1;
        }
        // After the progress, not before: MAX LEVEL is part of what gets cached, and saving on the
        // identity change alone would have written the maxed pet down as still levelling.
        saveIfChanged();
    }

    /**
     * Strips the decoration Hypixel hangs off the widget's pet name - "Golden Dragon ✦" on a maxed
     * pet, where the star is drawn in the pet's rarity colour.
     *
     * <p>Without this the tab's name never equals the Pets menu's, so every scan would look like a
     * pet swap: the icon would be dropped and the cache rewritten twice a second.
     */
    private static String cleanPetName(String raw) {
        String text = raw.trim();
        int end = text.length();
        while (end > 0 && !Character.isLetterOrDigit(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).trim();
    }

    /**
     * Dumps the lines around a "Pet" header that produced no match, at most once every 30 s. The
     * widget's exact layout is a guess until it has been seen on a live server; this is what makes
     * it tunable from a log instead of by redeploying blind.
     */
    private void logTabFormat(List<String> section) {
        long now = System.currentTimeMillis();
        if (now - lastTabLogAt < 30_000L) {
            return;
        }
        lastTabLogAt = now;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Pet] no pet read from the tab list (headerSeen={}); lines were: {}",
                tabHeaderSeen, section);
    }

    // ------------------------------------------------------------------
    // Per-profile cache
    // ------------------------------------------------------------------

    /**
     * The equipped pet, remembered per account+profile so the card is filled in the moment you log
     * in instead of staying blank until you next open the Pets menu. It doubles as the fallback for
     * the tab reader above: on a server that does not publish the Pet widget (it is one of the
     * toggleable ones), the remembered pet is what the card shows.
     *
     * <p>Written whenever the pet actually changes, which is rare - a swap or a level-up.
     */
    private static final String CACHE_FILE = "pet.json";

    private boolean loaded;

    /** What gets written; the icon rides along as SNBT so its skull skin survives a restart. */
    private static final class Cached {
        String name = "";
        int level;
        String heldItem = "";
        String iconSnbt = "";
        /** Which pet the icon belongs to - without it the skin outlives the pet across a restart. */
        String iconName = "";
        /** Without this a maxed pet comes back showing a progress bar it can never fill. */
        boolean maxLevel;
    }

    /**
     * The contents of the last write, so a scan that runs twice a second only touches the disk when
     * something actually changed. The icon is represented by whether there is one: it is taken from
     * the menu entry whose name is exactly "[Lvl N] Name", so it cannot change without the name or
     * the level changing with it, and encoding it just to compare would defeat the point.
     */
    private volatile String savedSignature = "";

    private String signature() {
        return name + '|' + level + '|' + heldItem + '|' + maxLevel
                + '|' + iconName + '|' + icon.isEmpty();
    }

    /** Writes the cache only when this pet differs from the one already on disk. */
    private void saveIfChanged() {
        if (!signature().equals(savedSignature)) {
            save();
        }
    }

    /** SNBT of the icon we have not managed to decode yet (no level up = no registry context). */
    private String pendingIconSnbt = "";

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = ProfileContext.getInstance().file(CACHE_FILE);
            if (!Files.exists(path)) {
                return;
            }
            Cached cached = new com.google.gson.Gson()
                    .fromJson(Files.readString(path, StandardCharsets.UTF_8), Cached.class);
            if (cached == null || cached.name == null || cached.name.isBlank()) {
                return;
            }
            // Never overwrite something a live source already established this session.
            if (!active) {
                name = cached.name;
                level = cached.level;
                heldItem = cached.heldItem == null ? "" : cached.heldItem;
                maxLevel = cached.maxLevel;
                active = true;
                // XP stays unknown on purpose: it is the one figure that keeps moving while the
                // client is closed, so a remembered bar would be confidently out of date. The next
                // menu or tab reading fills it in.
                xpPercent = -1;
                xpCurrent = -1;
                xpNeeded = -1;
            }
            pendingIconSnbt = cached.iconSnbt == null ? "" : cached.iconSnbt;
            // Older caches predate the field. Their icon was written for whatever pet was active at
            // the time, which is exactly cached.name - assuming that keeps their skin working.
            iconName = cached.iconName == null || cached.iconName.isBlank()
                    ? cached.name : cached.iconName;
            savedSignature = signature();   // loaded state matches the file - nothing to rewrite
        } catch (Throwable t) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pet] could not read the cached pet", t);
        }
    }

    /** Decodes the remembered icon once a world (and with it a registry context) exists. */
    private void resolvePendingIcon() {
        if (pendingIconSnbt.isEmpty() || !icon.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;   // try again next frame; the SNBT stays pending
        }
        try {
            CompoundTag tag = TagParser.parseCompoundFully(pendingIconSnbt);
            var ops = minecraft.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                icon = stack;
            }
        } catch (Throwable ignored) {
            // a stale or unparsable stack is not worth a log line every frame
        }
        pendingIconSnbt = "";   // one attempt with a live registry is enough
    }

    /** Writes the current pet to the profile cache. Cheap and rare - only called on a real change. */
    void save() {
        try {
            Cached cached = new Cached();
            cached.name = name;
            cached.level = level;
            cached.heldItem = heldItem;
            cached.maxLevel = maxLevel;
            cached.iconSnbt = encodeIcon();
            cached.iconName = iconName;
            Path path = ProfileContext.getInstance().file(CACHE_FILE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, new com.google.gson.Gson().toJson(cached), StandardCharsets.UTF_8);
            savedSignature = signature();
        } catch (Throwable t) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pet] could not cache the pet", t);
        }
    }

    private String encodeIcon() {
        if (icon.isEmpty()) {
            return pendingIconSnbt;   // keep what we loaded if we never decoded it
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return pendingIconSnbt;
            }
            var ops = minecraft.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            return ItemStack.OPTIONAL_CODEC.encodeStart(ops, icon).result()
                    .map(Object::toString).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** Flush on a profile switch - see {@link ProfileScopedStore}. */
    @Override
    public void flushProfile() {
        if (active) {
            save();
        }
    }

    /** Drop this profile's pet and read the new profile's. */
    @Override
    public void reloadProfile() {
        loaded = false;
        active = false;
        name = "";
        level = 0;
        heldItem = "";
        clearIcon();
        savedSignature = "";
        xpPercent = -1;
        xpCurrent = -1;
        xpNeeded = -1;
        maxLevel = false;
        ensureLoaded();
    }
}
