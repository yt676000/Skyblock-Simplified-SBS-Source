/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.forge.ui.ForgeFlipsScreen;
import sbs.modid.client.economy.minions.logic.MinionCalcService;
import sbs.modid.client.economy.minions.logic.MinionCatalogs;
import sbs.modid.client.economy.minions.logic.MinionMath;
import sbs.modid.client.economy.minions.logic.MinionStateStore;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.minions.model.MinionModifierData;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiDropdown;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Minion Calculator window: every minion ranked by coins or skill XP <b>per slot per day</b>,
 * as projections under stated, inline-editable assumptions (collection interval, fuel, hopper,
 * compactor, extra speed) - change one and the whole ranking recomputes.
 *
 * <p>Everything shown is a projection, and the screen never hides that: the assumption chips sit
 * above the list, every row's tooltip repeats them alongside the price timestamps, and figures
 * with an unverified source carry their certainty note instead of false precision.
 *
 * <p>All computation happens on {@link MinionCalcService}'s background thread; this screen only
 * renders the last published result.
 */
public final class MinionCalcScreen extends Screen implements sbs.modid.client.ui.theme.KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "minion_calc";
    }


    private static final int ROW_H = 22;

    /** How the in-development notice names this screen. */
    private static final String NOTICE_SUBJECT = "This calculator";

    /** The XP tab's skill filter cycle; ALL ranks by XP regardless of skill. */
    private static final String[] SKILLS =
            {"ALL", "FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "SLAYER"};

    private static final String[] ROMAN =
            {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"};

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    /** Top of the in-development notice; the list starts below whatever it measures. */
    private int noticeTop;
    private int listTop;
    private int listBottom;
    private int scroll;

    /** The rows the list showed last frame, so scroll bounds and hover match what is drawn. */
    private List<MinionCalcService.Row> visibleRows = List.of();

    /** Every dropdown on the screen: only one opens at a time, all draw in the overlay pass. */
    private final List<SciFiDropdown> dropdowns = new ArrayList<>();

    public MinionCalcScreen() {
        super(Component.literal("Minion Calculator"));
    }

    private static SBSConfig.MinionCalcSettings cfg() {
        return ConfigManager.getInstance().get().minionCalc;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /**
     * Config change from a <b>button or dropdown</b> -> save, recompute, and re-init so every chip
     * label shows the new state.
     *
     * <p><b>Never call this from a text field's responder.</b> A responder runs on every keystroke,
     * and {@code rebuildWidgets()} clears and recreates every widget - including the field being
     * typed in, which takes the keyboard focus with it and re-seeds the box from the stored value.
     * The symptom is having to click back into the field after each character, with half-typed text
     * snapping back. Those fields call {@link #typed()} instead.
     */
    private void changed() {
        save();
        MinionCalcService.request(true);
        this.rebuildWidgets();
    }

    /**
     * Config change from a <b>text field</b> -> save and recompute, but leave the widgets alone so
     * the field keeps its focus and its half-typed text.
     *
     * <p>Nothing is lost by skipping the re-init: the only labels {@code changed()} exists to
     * refresh are the button and dropdown captions, and a text field already displays its own value.
     * The ranking still updates live, because that is driven by the service, not by the widgets.
     */
    private void typed() {
        save();
        MinionCalcService.request(true);
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 460, 720);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 280, 470);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;

        int tabsY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int chipsY = tabsY + SBSTheme.ENTRY_HEIGHT + 4;
        int slotsY = chipsY + SBSTheme.ENTRY_HEIGHT + 4;
        // Measured from the strings that will actually be drawn: the notice wraps to two lines on a
        // narrow panel and one on a wide one, so a constant here would overlap the first row at one
        // of the two - silently, the way overlapping text always fails.
        noticeTop = slotsY + SBSTheme.ENTRY_HEIGHT + 6;
        listTop = noticeTop + DevNotice.height(font, contentWidth, NOTICE_SUBJECT);
        int backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        listBottom = backY - 6;

        addRenderableOnly(new PanelRenderable());
        dropdowns.clear();

        // --- tab row: what the ranking answers, plus the XP tab's skill --------------
        int tabW = (contentWidth - 4 * 6) / 5;
        String[] tabs = {"Coins", "Skill XP", "Both", "Mine"};
        for (int i = 0; i < tabs.length; i++) {
            int tab = i;
            String label = (cfg().tab == i ? "§b" : "§7") + tabs[i];
            addRenderableWidget(new SciFiButton(innerX + i * (tabW + 6), tabsY, tabW,
                    SBSTheme.ENTRY_HEIGHT, Component.literal(label),
                    () -> { cfg().tab = tab; changed(); }));
        }
        addDropdown(innerX + 4 * (tabW + 6), tabsY, contentWidth - 4 * (tabW + 6), "Skill",
                () -> List.of(SKILLS), () -> cfg().rankSkill,
                picked -> { cfg().rankSkill = picked; changed(); });

        // --- row 1: interval, fuel, sell mode, extra speed ---------------------------
        //
        // Every input here is a LABELLED row (forValueRow). forRow draws no label at all - only a
        // hint that disappears on the first keystroke - so a row of them is a line of identical
        // boxes with no way to tell which number is which.
        int gap = 4;
        int intervalW = (int) (contentWidth * 0.20);
        int speedW = (int) (contentWidth * 0.19);
        int fuelW = (int) (contentWidth * 0.33);
        int hopperW = contentWidth - intervalW - speedW - fuelW - 3 * gap;
        int x = innerX;
        addRenderableWidget(SciFiTextField.forValueRow(x, chipsY, intervalW, SBSTheme.ENTRY_HEIGHT,
                "Collect h", "24", 4, 3,
                () -> String.valueOf(cfg().intervalHours),
                text -> {
                    int hours = parseInt(text, cfg().intervalHours);
                    if (hours != cfg().intervalHours && hours >= 1 && hours <= 24 * 30) {
                        cfg().intervalHours = hours;
                        typed();
                    }
                }));
        x += intervalW + gap;
        addDropdown(x, chipsY, fuelW, "Fuel", MinionCalcScreen::fuelOptions,
                () -> labelForFuel(cfg().fuelId),
                picked -> { cfg().fuelId = idForFuel(picked); changed(); });
        x += fuelW + gap;
        addDropdown(x, chipsY, hopperW, "Sell", () -> List.of(HOPPER_LABELS),
                () -> labelForHopper(cfg().hopperId),
                picked -> { cfg().hopperId = idForHopper(picked); changed(); });
        x += hopperW + gap;
        addRenderableWidget(SciFiTextField.forValueRow(x, chipsY, speedW, SBSTheme.ENTRY_HEIGHT,
                "Speed %", "0", 3, 3,
                () -> String.valueOf(cfg().extraSpeedPct),
                text -> {
                    int pct = parseInt(text, cfg().extraSpeedPct);
                    if (pct != cfg().extraSpeedPct && pct >= 0 && pct <= 222) {
                        cfg().extraSpeedPct = pct;
                        typed();
                    }
                }));

        // --- row 2: the permanent boosts, then the two upgrade slots (or auto) -------
        //
        // Mithril Infusion and Free Will take no slot - they are applied to the minion itself and
        // stay there - so they are a state toggle here rather than a pick in a slot.
        int perkW = (int) (contentWidth * 0.20);
        addDropdown(innerX, slotsY, perkW, "Perks", () -> List.of(PERK_LABELS),
                MinionCalcScreen::perkLabel,
                picked -> { pickPerk(picked); changed(); });
        int rowX = innerX + perkW + 4;
        int rowW = contentWidth - perkW - 4;
        int autoW = (int) (rowW * 0.26);
        if (cfg().autoUpgrades) {
            // In auto mode the two pickers are meaningless (each minion gets its own fuel and
            // pair), so the row becomes the thing that still matters: what you will spend.
            addRenderableWidget(new SciFiButton(rowX, slotsY, autoW, SBSTheme.ENTRY_HEIGHT,
                    Component.literal("§aBest setup"),
                    () -> { cfg().autoUpgrades = false; changed(); }));
            addRenderableWidget(SciFiTextField.forValueRow(rowX + autoW + gap, slotsY,
                    rowW - autoW - gap, SBSTheme.ENTRY_HEIGHT, "Upgrade budget", "any", 16, 6,
                    () -> cfg().upgradeBudget > 0
                            ? NumberDisplay.shorten(cfg().upgradeBudget) : "",
                    text -> {
                        long parsed = ForgeFlipsScreen.parseCoins(text);
                        if (parsed != cfg().upgradeBudget) {
                            cfg().upgradeBudget = parsed;
                            typed();
                        }
                    }));
        } else {
            int slotW = (rowW - autoW - 2 * gap) / 2;
            addRenderableWidget(new SciFiButton(rowX, slotsY, autoW, SBSTheme.ENTRY_HEIGHT,
                    Component.literal("§8Best setup"),
                    () -> { cfg().autoUpgrades = true; changed(); }));
            addDropdown(rowX + autoW + gap, slotsY, slotW, "Upgrade 1",
                    MinionCalcScreen::upgradeOptions,
                    () -> labelForUpgrade(cfg().upgradeSlot1),
                    picked -> { cfg().upgradeSlot1 = idForUpgrade(picked); changed(); });
            addDropdown(rowX + autoW + slotW + 2 * gap, slotsY,
                    rowW - autoW - slotW - 2 * gap, "Upgrade 2",
                    MinionCalcScreen::upgradeOptions,
                    () -> labelForUpgrade(cfg().upgradeSlot2),
                    picked -> { cfg().upgradeSlot2 = idForUpgrade(picked); changed(); });
        }

        int backW = (contentWidth - 6) / 2;
        addRenderableWidget(new SciFiButton(innerX, backY, backW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), () -> Minecraft.getInstance().setScreenAndShow(null)));
        addRenderableWidget(new SciFiButton(innerX + backW + 6, backY, contentWidth - backW - 6,
                SBSTheme.SEARCH_HEIGHT, Component.literal("§bOptimizer"),
                () -> Minecraft.getInstance().setScreenAndShow(new MinionPlanScreen())));

        // Last of all: an open dropdown list draws over every widget added above it.
        addRenderableOnly((g, mouseX, mouseY, partialTick) ->
                dropdowns.forEach(dropdown -> dropdown.renderOverlay(g, mouseX, mouseY)));

        MinionCalcService.request(false);
    }

    /**
     * Registers a dropdown and keeps a reference for the overlay pass: the open list is drawn on
     * top of everything afterwards, and opening one closes the others.
     */
    private void addDropdown(int x, int y, int width, String label, Supplier<List<String>> options,
                             Supplier<String> value, Consumer<String> onPick) {
        SciFiDropdown dropdown = new SciFiDropdown(x, y, width, SBSTheme.ENTRY_HEIGHT, label,
                options, value, onPick);
        dropdown.setOnOpen(() -> dropdowns.forEach(other -> {
            if (other != dropdown) {
                other.close();
            }
        }));
        dropdowns.add(dropdown);
        addRenderableWidget(dropdown);
    }

    // Option lists. Each label carries what the choice DOES, so the pick is informed rather than
    // a name to look up elsewhere; the id round-trips through the matching idFor* method.

    private static final String NONE = "none";

    private static final String[] HOPPER_LABELS =
            {"collect manually", "Budget Hopper (50%)", "Enchanted Hopper (70%)"};

    /** The permanent per-minion boosts, as one four-state pick (neither / either / both). */
    private static final String[] PERK_LABELS = {"none", "Mithril Infusion (+10%)",
            "Free Will (+10%)", "both (+20%)"};

    private static String perkLabel() {
        if (cfg().mithrilInfusion && cfg().freeWill) {
            return PERK_LABELS[3];
        }
        if (cfg().mithrilInfusion) {
            return PERK_LABELS[1];
        }
        return cfg().freeWill ? PERK_LABELS[2] : PERK_LABELS[0];
    }

    private static void pickPerk(String label) {
        cfg().mithrilInfusion = PERK_LABELS[1].equals(label) || PERK_LABELS[3].equals(label);
        cfg().freeWill = PERK_LABELS[2].equals(label) || PERK_LABELS[3].equals(label);
    }

    private static List<String> fuelOptions() {
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        List<String> out = new ArrayList<>();
        out.add(NONE);
        if (modifiers != null) {
            for (MinionModifierData.Fuel fuel : modifiers.fuels) {
                out.add(fuelLabel(fuel));
            }
        }
        return out;
    }

    private static String fuelLabel(MinionModifierData.Fuel fuel) {
        String effect = fuel.outputMult > 1 ? "x" + trimZero(fuel.outputMult)
                : "+" + trimZero(fuel.speedPct) + "%";
        return fuel.name + " (" + effect
                + (fuel.permanent ? ", forever" : ", " + trimZero(fuel.durationHours) + "h") + ")";
    }

    private static String labelForFuel(String id) {
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        MinionModifierData.Fuel fuel = modifiers == null || id == null || id.isEmpty()
                ? null : modifiers.fuel(id);
        return fuel == null ? NONE : fuelLabel(fuel);
    }

    private static String idForFuel(String label) {
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        if (modifiers != null) {
            for (MinionModifierData.Fuel fuel : modifiers.fuels) {
                if (fuelLabel(fuel).equals(label)) {
                    return fuel.id;
                }
            }
        }
        return "";
    }

    private static List<String> upgradeOptions() {
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        List<String> out = new ArrayList<>();
        out.add(NONE);
        if (modifiers != null) {
            for (MinionModifierData.Upgrade upgrade : modifiers.upgrades) {
                out.add(upgradeLabel(upgrade));
            }
        }
        return out;
    }

    /** "Flycatcher (+20% speed)" - the effect belongs in the option, not in a wiki tab. */
    private static String upgradeLabel(MinionModifierData.Upgrade upgrade) {
        StringBuilder effect = new StringBuilder();
        if (upgrade.speedPct != 0) {
            effect.append("+").append(trimZero(upgrade.speedPct)).append("% speed");
        }
        switch (upgrade.kind) {
            case "compact_ench" -> append(effect, "enchanted forms");
            case "compact_block" -> append(effect, "block forms");
            case "compact_smelt" -> append(effect, "smelt + enchanted");
            case "spread" -> append(effect, trimZero(upgrade.chance * 100) + "% extra "
                    + pretty(upgrade.item));
            case "cooldown" -> append(effect, pretty(upgrade.item) + " every "
                    + (upgrade.cooldownSeconds == null ? "?" : upgrade.cooldownSeconds + "s")
                    + (upgrade.outputMult < 1 ? ", -" + Math.round((1 - upgrade.outputMult) * 100)
                            + "% drops" : ""));
            case "transform" -> {
                if (upgrade.adds != null && !upgrade.adds.isEmpty()) {
                    StringBuilder adds = new StringBuilder();
                    for (MinionModifierData.Add add : upgrade.adds) {
                        append(adds, "+" + trimZero(add.amount) + " " + pretty(add.item));
                    }
                    append(effect, adds.toString());
                } else if (upgrade.to != null) {
                    append(effect, pretty(upgrade.from) + " -> " + pretty(upgrade.to));
                } else if (upgrade.smelts) {
                    append(effect, "smelts drops");
                }
            }
            default -> { }
        }
        String where = upgrade.restrictType != null ? pretty(upgrade.restrictType) + " only"
                : upgrade.restrictSkill != null
                        ? upgrade.restrictSkill.toLowerCase(java.util.Locale.ROOT) + " only"
                        : upgrade.restrictTypes != null && !upgrade.restrictTypes.isEmpty()
                                ? "mob minions" : null;
        if (where != null) {
            append(effect, where);
        }
        return effect.length() == 0 ? upgrade.name : upgrade.name + " (" + effect + ")";
    }

    private static void append(StringBuilder builder, String text) {
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(text);
    }

    /** "CORRUPTED_FRAGMENT" -> "Corrupted Fragment", for option labels. */
    private static String pretty(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        String[] words = id.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String labelForUpgrade(String id) {
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        MinionModifierData.Upgrade upgrade = modifiers == null || id == null || id.isEmpty()
                ? null : modifiers.upgrade(id);
        return upgrade == null ? NONE : upgradeLabel(upgrade);
    }

    private static String idForUpgrade(String label) {
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        if (modifiers != null) {
            for (MinionModifierData.Upgrade upgrade : modifiers.upgrades) {
                if (upgradeLabel(upgrade).equals(label)) {
                    return upgrade.id;
                }
            }
        }
        return "";
    }

    private static String labelForHopper(String id) {
        return switch (id == null ? "" : id) {
            case "BUDGET_HOPPER" -> HOPPER_LABELS[1];
            case "ENCHANTED_HOPPER" -> HOPPER_LABELS[2];
            default -> HOPPER_LABELS[0];
        };
    }

    private static String idForHopper(String label) {
        if (HOPPER_LABELS[1].equals(label)) {
            return "BUDGET_HOPPER";
        }
        return HOPPER_LABELS[2].equals(label) ? "ENCHANTED_HOPPER" : "";
    }

    private static String trimZero(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Routes a click to an open option list before anything else on the screen.
     *
     * <p>Widgets are offered a click in insertion order and only where they say the cursor is, so
     * without this a pick landed on the row the list was painted over - a chip changed value while
     * the option the player aimed at did nothing, and the list did not close on an outside click
     * either. The keybind editor carries the same loop; it is the idiom, not a patch.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Over a copy: a pick rebuilds the widgets, replacing the very list being iterated.
        for (SciFiDropdown dropdown : List.copyOf(dropdowns)) {
            if (dropdown.isOpen() && dropdown.mouseClicked(event, doubled)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /** True while any of this screen's option lists is open. */
    private boolean dropdownOpen() {
        for (SciFiDropdown dropdown : dropdowns) {
            if (dropdown.isOpen()) {
                return true;
            }
        }
        return false;
    }

    /** Escape closes an open list rather than the calculator - the list is the innermost thing open. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            for (SciFiDropdown dropdown : dropdowns) {
                if (dropdown.isOpen()) {
                    dropdown.close();
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // An open option list owns the wheel; otherwise scrolling it would move the ranking behind.
        for (SciFiDropdown dropdown : dropdowns) {
            if (dropdown.isOpen()) {
                return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }
        }
        if (scrollY != 0 && !visibleRows.isEmpty()) {
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            int max = Math.max(0, visibleRows.size() - visible);
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Ranking selection
    // ------------------------------------------------------------------

    /** The rows the current tab shows, sorted by its metric. */
    private static List<MinionCalcService.Row> rank(MinionCalcService.Result result) {
        List<MinionCalcService.Row> rows = new ArrayList<>(result.rows());
        int tab = cfg().tab;
        if (tab == 1 && !"ALL".equals(cfg().rankSkill)) {
            rows.removeIf(row -> !row.minion().skill.equals(cfg().rankSkill));
        }
        if (tab == 3) {
            // Mine: only what the island scan saw placed, best next upgrade first - the rows
            // answer "which of MY minions should I touch", not "what is best in a vacuum".
            rows.removeIf(row -> row.placedCount() <= 0);
            rows.sort(Comparator.comparingDouble(
                    (MinionCalcService.Row row) -> row.ownedDelta() == null
                            ? Double.NEGATIVE_INFINITY
                            : row.ownedDelta().addedCoinsPerDay()).reversed());
            return rows;
        }
        if (tab == 0) {
            rows.sort(Comparator.comparingDouble(
                    (MinionCalcService.Row row) -> row.projection().netCoinsPerDay()).reversed());
        } else if (tab == 1) {
            rows.sort(Comparator.comparingDouble(
                    (MinionCalcService.Row row) -> row.projection().xpPerDay()).reversed());
        } else {
            // Both: mean of the two metrics normalized over this set - a stated, simple blend.
            double maxCoins = 1;
            double maxXp = 1;
            for (MinionCalcService.Row row : rows) {
                maxCoins = Math.max(maxCoins, row.projection().netCoinsPerDay());
                maxXp = Math.max(maxXp, row.projection().xpPerDay());
            }
            double coinsScale = maxCoins;
            double xpScale = maxXp;
            rows.sort(Comparator.comparingDouble(
                    (MinionCalcService.Row row) -> row.projection().netCoinsPerDay() / coinsScale
                            + row.projection().xpPerDay() / xpScale).reversed());
        }
        return rows;
    }

    /** The number the current tab ranks by. */
    private static double metric(MinionCalcService.Row row) {
        return switch (cfg().tab) {
            case 1 -> row.projection().xpPerDay();
            case 3 -> row.ownedDelta() == null ? 0 : Math.max(0, row.ownedDelta().addedCoinsPerDay());
            default -> row.projection().netCoinsPerDay();
        };
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private static String coins(double value) {
        return NumberDisplay.format(value);
    }

    private static String hoursText(double hours) {
        if (Double.isInfinite(hours) || hours != hours) {
            return "∞";
        }
        if (hours < 48) {
            return Math.round(hours) + "h";
        }
        return String.format(java.util.Locale.ROOT, "%.1fd", hours / 24);
    }

    private static String daysText(double days) {
        if (Double.isInfinite(days) || days != days || days > 3650) {
            return "never";
        }
        if (days < 1) {
            return Math.round(days * 24) + "h";
        }
        return Math.round(days) + "d";
    }

    private static String displayName(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        return entry == null ? itemId : entry.name;
    }

    /**
     * "slots 12/24 · 87 uniques" from what has been witnessed: the Crafted Minions menu's own
     * limit when read, the unique-craft table otherwise, and a prompt while neither exists.
     */
    private static String slotsLine() {
        MinionStateStore store = MinionStateStore.getInstance();
        int limit = store.minionsLimit();
        int uniques = store.uniqueCraftCount();
        if (limit <= 0 && store.craftedReadAt() == 0) {
            return "§8slots ?/? - open /craftedminions once";
        }
        if (limit <= 0) {
            MinionModifierData modifiers = MinionCatalogs.modifiers();
            limit = modifiers == null ? 0 : modifiers.slotsFor(uniques, cfg().communitySlots);
        }
        int placed = store.placedTotal();
        String placedText = store.scanAt() == 0 ? "?" : String.valueOf(placed);
        String unknown = store.unknownPlaced() > 0 ? "+" + store.unknownPlaced() + "?" : "";
        return "§8slots " + placedText + unknown + "/" + limit + " · " + uniques + " uniques";
    }

    private static String roman(int tier) {
        return tier >= 0 && tier < ROMAN.length ? ROMAN[tier] : String.valueOf(tier);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            // Keeps the projection at most 30s behind the market, and tells the LBIN cache the
            // screen is alive (the signal decays on its own - no close hook to forget).
            MinionCalcService.touchScreen();
            MinionCalcService.request(false);
            var font = MinionCalcScreen.this.font;
            g.fill(0, 0, MinionCalcScreen.this.width, MinionCalcScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Minion Calculator"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);

            // Price freshness top-right: every figure below is only as current as this.
            MinionCalcService.Result result = MinionCalcService.result();
            if (result != null && result.bazaarDataAt() > 0) {
                long age = (System.currentTimeMillis() - result.bazaarDataAt()) / 1000;
                String stamp = "§8prices " + (age < 120 ? age + "s" : (age / 60) + "m") + " old";
                g.text(font, Component.literal(stamp),
                        panelX + panelW - SBSTheme.PANEL_PADDING - font.width(stamp), titleY,
                        SBSTheme.TEXT_MUTED);
            }
            // Slot budget top-left: the scarce resource every ranking is really about.
            g.text(font, Component.literal(slotsLine()), innerX, titleY, SBSTheme.TEXT_MUTED);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);

            // Above the ranking, not below it: a warning under a list nobody scrolls to the end of is
            // a warning nobody reads. It scrolls with nothing, for the same reason.
            DevNotice.draw(g, font, innerX, noticeTop, contentWidth, NOTICE_SUBJECT);

            drawList(g, mouseX, mouseY);
        }
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var font = this.font;
        MinionCalcService.Result result = MinionCalcService.result();
        if (result == null) {
            String text = MinionCalcService.error() != null ? "§c" + MinionCalcService.error()
                    : "§7Computing minion projections...";
            g.centeredText(font, Component.literal(text), innerX + contentWidth / 2, listTop + 8,
                    SBSTheme.TEXT_MUTED);
            visibleRows = List.of();
            return;
        }
        visibleRows = rank(result);
        if (visibleRows.isEmpty()) {
            g.centeredText(font, Component.literal("§7No minions match this view."),
                    innerX + contentWidth / 2, listTop + 8, SBSTheme.TEXT_MUTED);
            return;
        }
        double best = Math.max(1e-9, metric(visibleRows.get(0)));
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        int start = clamp(scroll, 0, Math.max(0, visibleRows.size() - visible));
        int rowY = listTop;
        for (int i = start; i < visibleRows.size() && i < start + visible; i++) {
            drawRow(g, visibleRows.get(i), i + 1, best, result, innerX, rowY, contentWidth,
                    mouseX, mouseY);
            rowY += ROW_H;
        }
    }

    private void drawRow(GuiGraphicsExtractor g, MinionCalcService.Row row, int rank, double best,
                         MinionCalcService.Result result, int x, int y, int w,
                         int mouseX, int mouseY) {
        var font = this.font;
        int h = ROW_H - 2;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

        // Score bar: this row's metric against the best on screen.
        int barW = (int) ((w - 2) * Math.max(0, Math.min(1, metric(row) / best)));
        g.fill(x + 1, y + h - 2, x + 1 + barW, y + h - 1, SBSTheme.ACCENT);

        // The Mine tab shows the tier the player actually runs; the others show the top tier.
        boolean mine = cfg().tab == 3 && row.ownedProjection() != null;
        MinionData.Tier shownTier = mine ? row.minion().tier(row.ownedTier()) : row.tier();
        if (shownTier == null) {
            shownTier = row.tier();
        }
        MinionMath.Projection projection = mine ? row.ownedProjection() : row.projection();

        ItemStack icon = SkyBlockItemIcons.getInstance().icon(shownTier.itemId, null, 1);
        g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.PLAYER_HEAD) : icon, x + 2, y + 2);

        MinionMath.UpgradeDelta delta = row.ownedDelta();
        String main = switch (cfg().tab) {
            case 1 -> "§b" + coins(projection.xpPerDay()) + " xp§8/d";
            case 2 -> "§a" + coins(projection.netCoinsPerDay()) + "§8/d §b"
                    + coins(projection.xpPerDay()) + "xp";
            case 3 -> delta != null
                    ? "§6" + coins(delta.cost()) + " §8-> §a+" + coins(delta.addedCoinsPerDay()) + "§8/d"
                    : "§8maxed §7" + coins(projection.netCoinsPerDay()) + "§8/d";
            default -> "§a" + coins(projection.netCoinsPerDay()) + "§8/d";
        };
        int mainW = font.width(main);
        g.text(font, Component.literal(main), x + w - 4 - mainW, y + 3, SBSTheme.TEXT);

        String badge = row.placedCount() > 0 ? " §7⌂" + row.placedCount() : "";
        String name = "§7#" + rank + " §f" + row.minion().name + " " + roman(shownTier.tier) + badge;
        int nameX = x + 22;
        int nameSpace = (x + w - 8 - mainW) - nameX;
        if (nameSpace > 8) {
            g.text(font, Component.literal(trim(name, nameSpace)), nameX, y + 3, SBSTheme.TEXT);
        }

        StringBuilder sub = new StringBuilder();
        if (mine && delta != null) {
            sub.append("§8T").append(delta.fromTier()).append("->T").append(delta.toTier())
                    .append(" payback §7").append(daysText(delta.paybackDays())).append(" §8· ");
        }
        sub.append("§8fill §7").append(hoursText(projection.hoursToFull()));
        if (projection.utilization() < 0.999) {
            sub.append(" §8· §c").append(Math.round(projection.utilization() * 100)).append("%");
        }
        if (!mine) {
            sub.append(" §8· payback §7").append(daysText(row.paybackDays()));
        }
        if (row.maxMarketShare() > 0.10) {
            sub.append(" §8· §6⚠ volume");
        }
        if (projection.xpIncomplete()) {
            sub.append(" §8· xp?");
        }
        g.text(font, Component.literal(trim(sub.toString(), w - 26)), nameX,
                y + 3 + font.lineHeight, SBSTheme.TEXT_MUTED);

        // Not while an option list is open: a tooltip is drawn in a stratum above even the overlay
        // pass, so the ranking row underneath the list would paint its breakdown straight over the
        // options being read. The list is what the player is looking at, so the list wins.
        if (hovered && !dropdownOpen()) {
            g.setTooltipForNextFrame(font, tooltip(row, result), java.util.Optional.empty(),
                    mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    /** The full projection breakdown: assumptions, production, streams, costs, certainty. */
    private List<Component> tooltip(MinionCalcService.Row row, MinionCalcService.Result result) {
        boolean mine = cfg().tab == 3 && row.ownedProjection() != null;
        MinionMath.Projection projection = mine ? row.ownedProjection() : row.projection();
        MinionData.Tier tier = mine ? row.minion().tier(row.ownedTier()) : row.tier();
        if (tier == null) {
            tier = row.tier();
            projection = row.projection();
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("§f" + row.minion().name + " Minion " + roman(tier.tier)
                + " §8(" + row.minion().skill.toLowerCase(java.util.Locale.ROOT) + ")"));

        tip.add(Component.literal("§8Assumes: every " + cfg().intervalHours + "h · fuel "
                + result.fuelName() + " · " + (cfg().hopperId.isEmpty() ? "manual sell" : "hopper")
                + (cfg().extraSpeedPct > 0 ? " · +" + cfg().extraSpeedPct + "% extra" : "")));

        // What this minion's upgrade slots hold - in auto mode the pair differs per minion, so
        // the row's own assumptions are the ones that count - and whether it accepts them at all.
        MinionMath.Assumptions rowAssumptions = row.assumptions();
        List<MinionModifierData.Upgrade> fitted = rowAssumptions.upgrades();
        if (fitted.isEmpty()) {
            tip.add(Component.literal("§8Upgrade slots: both empty"));
        } else {
            for (MinionModifierData.Upgrade upgrade : fitted) {
                boolean applies = upgrade.appliesTo(row.minion());
                tip.add(Component.literal((applies ? "§8Slot: §7" : "§8Slot: §c")
                        + upgradeLabel(upgrade) + (applies ? "" : " §c- not on this minion")));
            }
        }
        if (cfg().autoUpgrades) {
            Long upgradeCost = sbs.modid.client.economy.minions.logic.MinionAutoSetup
                    .costOf(fitted, sbs.modid.client.economy.minions.logic.MinionPricesLive.getInstance());
            tip.add(Component.literal("§8Fuel and upgrades chosen automatically"
                    + (upgradeCost == null ? "" : " · upgrades cost §6" + coins(upgradeCost))));
        }

        // The fuel, when this minion is the one that can use it, plus the Inferno mechanics that
        // only show up here: a multiplicative speed factor and the self-boost from its own kind.
        MinionModifierData.Fuel usable = rowAssumptions.usableFuel(row.minion());
        if (usable != null && usable.speedMult > 0) {
            tip.add(Component.literal("§7Fuel §f" + usable.name + " §8x"
                    + trimZero(usable.speedMult) + " speed §8(effective x"
                    + trimZero(usable.speedMult + 1) + " - the game adds 1)"));
        } else if (rowAssumptions.fuel() != null && usable == null) {
            tip.add(Component.literal("§cFuel " + rowAssumptions.fuel().name
                    + " does not fit this minion - counted as no fuel"));
        }
        double selfBoost = row.minion().selfBoostPct(rowAssumptions.sameTypePlaced());
        if (selfBoost > 0) {
            tip.add(Component.literal("§7Self-boost §a+" + trimZero(selfBoost) + "% §8from "
                    + rowAssumptions.sameTypePlaced() + "x " + row.minion().name
                    + " placed (18%/minion, max 180%)"));
        }
        tip.add(Component.literal(""));

        double effective = MinionMath.effectiveActionSeconds(tier.actionSeconds,
                rowAssumptions.speedPctWith(row.minion())) / rowAssumptions.speedMultWith(row.minion());
        tip.add(Component.literal("§7Action §f" + trimZero(tier.actionSeconds) + "s §8-> §f"
                + String.format(java.util.Locale.ROOT, "%.1fs", effective)
                + " §8· §7" + Math.round(projection.harvestsPerDay()) + " harvests/d"));
        tip.add(Component.literal("§7Storage §f" + tier.storage + " §8· full in §7"
                + hoursText(projection.hoursToFull()) + " §8· utilization §7"
                + Math.round(projection.utilization() * 100) + "%"));
        tip.add(Component.literal(""));

        // When one item carries the row, say so. An Inferno Minion's headline figure is ~87%
        // Chili Peppers - a 1-in-136 roll - and a number that size deserves to name its source
        // rather than let the reader assume it is steady output.
        MinionMath.ValuedStream dominant = null;
        for (MinionMath.ValuedStream stream : projection.streams()) {
            if (dominant == null || stream.coinsPerDay() > dominant.coinsPerDay()) {
                dominant = stream;
            }
        }
        if (dominant != null && projection.coinsPerDay() > 0
                && dominant.coinsPerDay() > projection.coinsPerDay() * 0.5) {
            tip.add(Component.literal("§6" + Math.round(
                    dominant.coinsPerDay() / projection.coinsPerDay() * 100)
                    + "% of this is " + displayName(dominant.stream().itemId())
                    + " §8- one item carries the row"));
        }

        for (MinionMath.ValuedStream stream : projection.streams()) {
            String line = "§7" + NumberDisplay.format(stream.stream().itemsPerDay()) + "x §f"
                    + displayName(stream.stream().itemId()) + " §8· "
                    + stream.source().name().toLowerCase(java.util.Locale.ROOT) + " §8· §6"
                    + coins(stream.coinsPerDay()) + "/d";
            if (stream.marketShare() > 0.10) {
                line += " §6⚠ " + Math.round(stream.marketShare() * 100) + "% of daily volume";
            }
            tip.add(Component.literal(line));
        }
        if (result.fuelCostPerDay() > 0) {
            tip.add(Component.literal("§7Fuel drain §c-" + coins(result.fuelCostPerDay()) + "/d"));
        }
        tip.add(Component.literal("§7Net §a" + coins(projection.netCoinsPerDay()) + "/d"));

        String xpLine = "§7XP §b" + coins(projection.xpPerDay()) + "/d";
        if (cfg().hopperId.isEmpty()) {
            if (cfg().xpBoostPct > 0) {
                xpLine += " §8(incl. +" + cfg().xpBoostPct + "% boost)";
            }
            if (projection.xpIncomplete()) {
                xpLine += " §8+ unknown per-item XP";
            }
        } else {
            xpLine = "§7XP §b0/d §8(hopper-sold items grant none)";
        }
        tip.add(Component.literal(xpLine));
        tip.add(Component.literal(""));

        if (row.fromScratchCost() != null) {
            tip.add(Component.literal("§7Craft from scratch §6" + coins(row.fromScratchCost())
                    + " §8· payback §7" + daysText(row.paybackDays())));
        } else {
            tip.add(Component.literal("§8Not craftable with coins alone (drop or unpriced tier)"));
        }
        MinionMath.UpgradeDelta step = row.lastStep();
        if (step != null && !mine) {
            tip.add(Component.literal("§7T" + step.fromTier() + "->T" + step.toTier() + " §6"
                    + coins(step.cost()) + " §8· §a+" + coins(step.addedCoinsPerDay())
                    + "/d §8· payback §7" + daysText(step.paybackDays())));
        }

        // What the player actually runs, as far as the island scan and GUI opens have seen.
        MinionStateStore store = MinionStateStore.getInstance();
        if (row.placedCount() > 0) {
            tip.add(Component.literal(""));
            tip.add(Component.literal("§7You run §f" + row.placedCount() + "x §7up to T"
                    + row.ownedTier()));
            MinionStateStore.Config config = store.configFor(row.minion().type);
            if (config != null) {
                StringBuilder line = new StringBuilder("§8Seen in its GUI: fuel ")
                        .append(config.fuelId.isEmpty() ? "none" : displayName(config.fuelId));
                if (!config.hopperId.isEmpty()) {
                    line.append(", ").append(displayName(config.hopperId));
                }
                for (String upgrade : config.upgradeIds) {
                    line.append(", ").append(displayName(upgrade));
                }
                tip.add(Component.literal(line.toString()));
            }
            MinionMath.UpgradeDelta owned = row.ownedDelta();
            if (owned != null) {
                tip.add(Component.literal("§7Upgrade T" + owned.fromTier() + "->T" + owned.toTier()
                        + " §6" + coins(owned.cost()) + " §8· §a+" + coins(owned.addedCoinsPerDay())
                        + "/d §8· payback §7" + daysText(owned.paybackDays())));
            }
        }

        // Crafting value beyond output: uniques move the slot table and grant SkyBlock XP.
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        if (store.craftedReadAt() == 0) {
            tip.add(Component.literal("§8Open /craftedminions once to track uniques and slots"));
        } else if (modifiers != null && !modifiers.skyblockXpPerTier.isEmpty()) {
            int missing = 0;
            int xp = 0;
            for (MinionData.Tier each : row.minion().tiers) {
                if (!"drop".equals(each.source)
                        && !store.craftedTiers().contains(row.minion().type + "_" + each.tier)) {
                    missing++;
                    if (each.tier < modifiers.skyblockXpPerTier.size()) {
                        xp += modifiers.skyblockXpPerTier.get(each.tier);
                    }
                }
            }
            if (missing > 0) {
                tip.add(Component.literal("§8Uncrafted tiers: " + missing + " (worth +" + xp
                        + " SkyBlock XP, +" + missing + " toward slot unlocks)"));
            }
        }
        if (row.minion().unlockCollection != null) {
            tip.add(Component.literal("§8Unlocks at " + row.minion().unlockCollection + " "
                    + row.minion().unlockCollectionTier));
        }
        if (tier.npc != null && !tier.npc.isEmpty()) {
            tip.add(Component.literal("§8Top tier is an NPC trade at " + tier.npc));
        }
        if (!"wiki".equals(tier.statsCertainty)) {
            tip.add(Component.literal("§8Stats certainty: " + tier.statsCertainty));
        }
        return tip;
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }
}
