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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.forge.ui.ForgeFlipsScreen;
import sbs.modid.client.economy.minions.logic.MinionCatalogs;
import sbs.modid.client.economy.minions.logic.MinionOptimizer;
import sbs.modid.client.economy.minions.logic.MinionPlanService;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Minion Optimizer window: budget in, slots in, objective in - one complete recommended setup
 * out, with its shopping list, projections, beacon/crystal advice and how close the runner-up
 * was. Solved off-thread by {@link MinionPlanService}; this screen only renders the last answer.
 *
 * <p>The optimizer recommends. It never buys, crafts or places anything.
 */
public final class MinionPlanScreen extends Screen {

    private static final int ROW_H = 22;

    private static final String[] OBJECTIVES = {"Coins/day", "Skill XP/day", "Blend 50/50"};

    /** How the in-development notice names this screen. */
    private static final String NOTICE_SUBJECT = "This optimizer";

    /** One rendered row: either a plain text line or a setup line with icon + tooltip. */
    private record DisplayRow(String text, String right, MinionOptimizer.Line line) {
    }

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

    private List<DisplayRow> rows = List.of();

    public MinionPlanScreen() {
        super(Component.literal("Minion Optimizer"));
    }

    private static SBSConfig.MinionCalcSettings cfg() {
        return ConfigManager.getInstance().get().minionCalc;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    private void changed() {
        save();
        this.rebuildWidgets();
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

        int rowOneY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int rowTwoY = rowOneY + SBSTheme.ENTRY_HEIGHT + 4;
        // Measured, not assumed: the notice is one line on a wide panel and two on a narrow one.
        noticeTop = rowTwoY + SBSTheme.ENTRY_HEIGHT + 6;
        listTop = noticeTop + DevNotice.height(font, contentWidth, NOTICE_SUBJECT);
        int backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        listBottom = backY - 6;

        addRenderableOnly(new PanelRenderable());

        int gap = 4;
        // --- row 1: the hard constraints -------------------------------------------
        int budgetW = (int) (contentWidth * 0.24);
        int slotsW = (int) (contentWidth * 0.16);
        int horizonW = (int) (contentWidth * 0.16);
        int objectiveW = contentWidth - budgetW - slotsW - horizonW - 3 * gap;
        int x = innerX;
        // Labelled rows throughout: an unlabelled box only shows its hint until you type in it,
        // after which three numbers sit side by side with nothing saying which is which.
        addRenderableWidget(SciFiTextField.forValueRow(x, rowOneY, budgetW, SBSTheme.ENTRY_HEIGHT,
                "Budget", "500m", 16, 6,
                () -> cfg().planBudget > 0 ? NumberDisplay.shorten(cfg().planBudget) : "",
                text -> {
                    long parsed = ForgeFlipsScreen.parseCoins(text);
                    if (parsed != cfg().planBudget) {
                        cfg().planBudget = parsed;
                        save();
                    }
                }));
        x += budgetW + gap;
        addRenderableWidget(SciFiTextField.forValueRow(x, rowOneY, slotsW, SBSTheme.ENTRY_HEIGHT,
                "Slots", String.valueOf(MinionPlanService.effectiveSlots()), 3, 3,
                () -> cfg().planSlots > 0 ? String.valueOf(cfg().planSlots) : "",
                text -> {
                    int parsed = parseInt(text, 0);
                    if (parsed != cfg().planSlots && parsed >= 0 && parsed <= 64) {
                        cfg().planSlots = parsed;
                        save();
                    }
                }));
        x += slotsW + gap;
        addRenderableWidget(SciFiTextField.forValueRow(x, rowOneY, horizonW, SBSTheme.ENTRY_HEIGHT,
                "Days", "30", 4, 3,
                () -> String.valueOf(cfg().planHorizonDays),
                text -> {
                    int parsed = parseInt(text, cfg().planHorizonDays);
                    if (parsed != cfg().planHorizonDays && parsed >= 1 && parsed <= 365) {
                        cfg().planHorizonDays = parsed;
                        save();
                    }
                }));
        x += horizonW + gap;
        String objectiveLabel = "§7Goal: §f" + OBJECTIVES[cfg().planObjective]
                + (cfg().planObjective == 1 ? " §8(" + cfg().rankSkill + ")" : "");
        addRenderableWidget(new SciFiButton(x, rowOneY, objectiveW, SBSTheme.ENTRY_HEIGHT,
                Component.literal(objectiveLabel),
                () -> { cfg().planObjective = (cfg().planObjective + 1) % 3; changed(); }));

        // --- row 2: the soft levers + solve ----------------------------------------
        int ownedW = (int) (contentWidth * 0.28);
        int diversityW = (int) (contentWidth * 0.28);
        int solveW = contentWidth - ownedW - diversityW - 2 * gap;
        x = innerX;
        addRenderableWidget(new SciFiButton(x, rowTwoY, ownedW, SBSTheme.ENTRY_HEIGHT,
                Component.literal(cfg().planUseOwned ? "§aStart from my minions" : "§8From scratch"),
                () -> { cfg().planUseOwned = !cfg().planUseOwned; changed(); }));
        x += ownedW + gap;
        String diversityLabel = cfg().planMaxPerType > 0
                ? "§7Max §f" + cfg().planMaxPerType + "§7/type" : "§7No type cap";
        addRenderableWidget(new SciFiButton(x, rowTwoY, diversityW, SBSTheme.ENTRY_HEIGHT,
                Component.literal(diversityLabel), () -> {
                    cfg().planMaxPerType = switch (cfg().planMaxPerType) {
                        case 0 -> 1;
                        case 1 -> 3;
                        case 3 -> 5;
                        default -> 0;
                    };
                    changed();
                }));
        x += diversityW + gap;
        addRenderableWidget(new SciFiButton(x, rowTwoY, solveW, SBSTheme.ENTRY_HEIGHT,
                Component.literal("§bSolve"), MinionPlanService::solve));

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"),
                () -> Minecraft.getInstance().setScreenAndShow(new MinionCalcScreen())));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && !rows.isEmpty()) {
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            int max = Math.max(0, rows.size() - visible);
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

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String coins(double value) {
        return NumberDisplay.format(value);
    }

    private static String daysText(double days) {
        if (Double.isInfinite(days) || days != days || days > 3650) {
            return "never";
        }
        return days < 1 ? Math.round(days * 24) + "h" : Math.round(days) + "d";
    }

    private static String displayName(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        return entry == null ? itemId : entry.name;
    }

    private static final String[] ROMAN =
            {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"};

    private static String roman(int tier) {
        return tier >= 0 && tier < ROMAN.length ? ROMAN[tier] : String.valueOf(tier);
    }

    // ------------------------------------------------------------------ result -> rows

    private List<DisplayRow> buildRows(MinionOptimizer.Result result) {
        List<DisplayRow> out = new ArrayList<>();
        long remaining = result.params().budget() - result.spent();
        out.add(new DisplayRow("§fPlan: §a" + coins(result.coinsPerDay()) + "/d coins §7+ §b"
                + coins(result.xpPerDay()) + "/d " + result.params().xpSkill() + " xp", "", null));
        out.add(new DisplayRow("§7Spent §6" + coins(result.spent()) + " §8of §6"
                + coins(result.params().budget()) + " §8(§7" + coins(remaining) + " left§8) · "
                + result.slotsUsed() + "/" + result.params().slots() + " slots · payback §7"
                + daysText(result.paybackDays()), "", null));
        if (result.beaconTier() > 0) {
            out.add(new DisplayRow("§7Beacon " + roman(result.beaconTier()) + " §6"
                    + coins(result.beaconUpfront()) + " §8+ §c" + coins(result.beaconDrainPerDay())
                    + "/d power §8· " + result.beaconNote(), "", null));
        } else {
            out.add(new DisplayRow("§8" + result.beaconNote(), "", null));
        }
        if (result.newUniqueTiers() > 0) {
            out.add(new DisplayRow("§8Crafts " + result.newUniqueTiers()
                    + " new unique tier(s): +" + result.skyblockXpFromCrafts()
                    + " SkyBlock XP, progress toward the next slot", "", null));
        }
        out.add(new DisplayRow("", "", null));

        if (result.setup().isEmpty()) {
            // An empty plan is a real answer, but never a self-explanatory one - name the two
            // things that actually cause it before the notes list the counts.
            out.add(new DisplayRow("§eNothing fits this plan.", "", null));
            out.add(new DisplayRow("§7Either the budget covers no complete configuration, or the "
                    + "market caches are", "", null));
            out.add(new DisplayRow("§7still filling - prices arrive within a minute of opening "
                    + "the calculator.", "", null));
            out.add(new DisplayRow("", "", null));
        }

        for (MinionOptimizer.Line line : result.setup()) {
            MinionOptimizer.Variant variant = line.variant();
            StringBuilder text = new StringBuilder("§f").append(line.count()).append("x §b")
                    .append(variant.name()).append(" ").append(roman(variant.targetTier()));
            if (variant.fromOwned()) {
                text.append(" §7(yours");
                if (variant.fromTier() < variant.targetTier()) {
                    text.append(", from T").append(variant.fromTier());
                }
                text.append(")");
            }
            String right = "§6" + coins((double) variant.upfront() * line.count())
                    + " §8· §a+" + coins(variant.netCoinsPerDay() * line.count()) + "/d";
            out.add(new DisplayRow(text.toString(), right, line));
        }

        for (MinionOptimizer.CrystalAdvice crystal : result.crystals()) {
            out.add(new DisplayRow((crystal.worthIt() ? "§a+ " : "§8- ") + crystal.name()
                    + " §6" + coins(crystal.cost()) + " §8-> §a+" + coins(crystal.addedPerDay())
                    + "/d §8(payback " + daysText(crystal.paybackDays())
                    + (crystal.worthIt() ? ", worth it)" : ", beyond horizon)"), "", null));
        }
        if (!result.sensitivity().isEmpty()) {
            out.add(new DisplayRow("§8" + result.sensitivity(), "", null));
        }
        for (String note : result.notes()) {
            out.add(new DisplayRow("§8" + note, "", null));
        }
        out.add(new DisplayRow(String.format(Locale.ROOT,
                "§8Solved in %dms over %,d configuration(s)",
                result.elapsedMs(), result.variantsConsidered()), "", null));
        return out;
    }

    // ------------------------------------------------------------------ rendering

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            // Keeps the shared bazaar / LBIN caches refreshing while planning, exactly as the
            // calculator screen does - a solve is only as good as the prices behind it.
            sbs.modid.client.economy.minions.logic.MinionCalcService.touchScreen();
            var font = MinionPlanScreen.this.font;
            g.fill(0, 0, MinionPlanScreen.this.width, MinionPlanScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Minion Optimizer"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.text(font, Component.literal("§8recommends only - buys nothing"), innerX, titleY,
                    SBSTheme.TEXT_MUTED);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);
            DevNotice.draw(g, font, innerX, noticeTop, contentWidth, NOTICE_SUBJECT);
            drawList(g, mouseX, mouseY);
        }
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var font = this.font;
        if (MinionPlanService.solving()) {
            g.centeredText(font, Component.literal("§7" + MinionPlanService.stage() + " §8("
                            + MinionPlanService.progressPct() + "%)"),
                    innerX + contentWidth / 2, listTop + 8, SBSTheme.TEXT_MUTED);
            return;
        }
        if (MinionPlanService.error() != null) {
            g.centeredText(font, Component.literal("§c" + MinionPlanService.error()),
                    innerX + contentWidth / 2, listTop + 8, SBSTheme.TEXT_MUTED);
            return;
        }
        MinionOptimizer.Result result = MinionPlanService.result();
        if (result == null) {
            g.centeredText(font, Component.literal(
                            "§7Set a budget and press Solve. Every figure will be a projection"),
                    innerX + contentWidth / 2, listTop + 8, SBSTheme.TEXT_MUTED);
            g.centeredText(font, Component.literal(
                            "§7under the calculator's assumptions (interval, tax, XP boost)."),
                    innerX + contentWidth / 2, listTop + 8 + font.lineHeight + 2, SBSTheme.TEXT_MUTED);
            rows = List.of();
            return;
        }
        rows = buildRows(result);
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        int start = clamp(scroll, 0, Math.max(0, rows.size() - visible));
        int y = listTop;
        for (int i = start; i < rows.size() && i < start + visible; i++) {
            drawRow(g, rows.get(i), innerX, y, contentWidth, mouseX, mouseY);
            y += ROW_H;
        }
    }

    private void drawRow(GuiGraphicsExtractor g, DisplayRow row, int x, int y, int w,
                         int mouseX, int mouseY) {
        var font = this.font;
        if (row.line() == null) {
            g.text(font, Component.literal(trim(row.text(), w)), x, y + (ROW_H - font.lineHeight) / 2,
                    SBSTheme.TEXT_MUTED);
            return;
        }
        int h = ROW_H - 2;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        MinionOptimizer.Variant variant = row.line().variant();
        MinionData.Minion minion = MinionCatalogs.byType(variant.type());
        String iconId = null;
        if (minion != null) {
            MinionData.Tier tier = minion.tier(variant.targetTier());
            iconId = tier == null ? null : tier.itemId;
        }
        ItemStack icon = SkyBlockItemIcons.getInstance().icon(iconId, null, 1);
        g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.PLAYER_HEAD) : icon, x + 2, y + 2);

        int rightW = font.width(row.right());
        g.text(font, Component.literal(row.right()), x + w - 4 - rightW, y + 3, SBSTheme.TEXT);
        int nameSpace = (x + w - 8 - rightW) - (x + 22);
        g.text(font, Component.literal(trim(row.text(), nameSpace)), x + 22, y + 3, SBSTheme.TEXT);

        String sub = "§8" + (variant.fuelId().isEmpty() ? "no fuel" : displayName(variant.fuelId()));
        if (!variant.upgradeIds().isEmpty()) {
            for (String upgrade : variant.upgradeIds()) {
                sub += " §8· " + displayName(upgrade);
            }
        }
        if (!variant.hopperId().isEmpty()) {
            sub += " §8· " + displayName(variant.hopperId());
        }
        g.text(font, Component.literal(trim(sub, w - 26)), x + 22, y + 3 + font.lineHeight,
                SBSTheme.TEXT_MUTED);

        if (hovered) {
            g.setTooltipForNextFrame(font, tooltip(row.line()), java.util.Optional.empty(),
                    mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    private List<Component> tooltip(MinionOptimizer.Line line) {
        MinionOptimizer.Variant variant = line.variant();
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("§f" + line.count() + "x " + variant.name() + " Minion "
                + roman(variant.targetTier())));
        if (variant.fromOwned()) {
            tip.add(Component.literal("§7Upgrades your placed T" + variant.fromTier()
                    + " minions - the cost below is the marginal path up"));
        }
        tip.add(Component.literal("§7Upfront §6" + coins(variant.upfront()) + " §8each, §6"
                + coins((double) variant.upfront() * line.count()) + " §8total"));
        tip.add(Component.literal("§7Returns §a+" + coins(variant.netCoinsPerDay())
                + "/d §8each" + (variant.xpPerDay() > 0
                ? " §7+ §b" + coins(variant.xpPerDay()) + " xp/d" : "")));
        var projection = variant.projection();
        tip.add(Component.literal("§7Utilization §f"
                + Math.round(projection.utilization() * 100) + "% §8at the set interval"));
        if (projection.fuelCostPerDay() > 0) {
            tip.add(Component.literal("§7Fuel drain §c-" + coins(projection.fuelCostPerDay())
                    + "/d §8(already inside the returns)"));
        }
        for (var stream : projection.streams()) {
            if (stream.marketShare() > 0.10) {
                tip.add(Component.literal("§6⚠ " + displayName(stream.stream().itemId()) + " sales = "
                        + Math.round(stream.marketShare() * 100) + "% of its daily market volume"));
            }
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
