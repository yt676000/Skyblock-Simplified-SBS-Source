/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.helper.loadouts.LoadoutCard;
import sbs.modid.client.social.playerviewer.ui.PvContext;
import sbs.modid.client.social.playerviewer.ui.PvDraw;
import sbs.modid.client.social.playerviewer.ui.PvPage;
import sbs.modid.client.social.playerviewer.ui.PvPreviewPlayer;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.theme.SBSTheme;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory ▸ Loadouts, Wardrobe and Equipment, drawn with the SBS Loadouts card ({@link
 * LoadoutCard}) so a viewed profile looks like your own loadouts grid.
 *
 * <ul>
 *   <li><b>Loadouts</b>: {@code loadout.loadouts} - each combines one armour slot, one equipment
 *       slot, a pet, a power stone and the tree / tuning presets. Full cards with the viewed
 *       player's model wearing the armour.</li>
 *   <li><b>Wardrobe</b>: the armour wardrobe ({@code loadout.armor}), every slot, the worn one
 *       highlighted. Full cards with the model.</li>
 *   <li><b>Equipment</b>: the separate equipment wardrobe ({@code loadout.equipment}): necklace,
 *       cloak, belt, gloves. Compact cards: label and the four pieces.</li>
 * </ul>
 *
 * <p>Which loadout is active is not in the API; a loadout is highlighted when its armour (and, if
 * it names one, its equipment) slot is the one being worn, which is what equipping it does.
 */
public final class LoadoutCardsPage implements PvPage {

    public enum Mode { LOADOUTS, WARDROBE, EQUIPMENT }

    private static final int GAP = 6;
    private static final int MIN_CARD_W = 96;
    private static final int MAX_CARD_W = 150;
    /** Compact equipment card: title row plus the strip of four pieces. */
    private static final int EQUIP_CARD_H = 44;
    private static final int STEP = 10;
    private static final String[] ARMOR_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};
    private static final String[] EQUIP_NAMES = {"Necklace", "Cloak", "Belt", "Gloves"};

    /** Cards are rebuilt this often at most: often enough to pick up icons that resolve late. */
    private static final long REBUILD_MS = 500;

    private final Mode mode;
    private PvPreviewPlayer preview;
    private String previewFor;
    private JsonObject builtFor;
    private long builtAt;
    private List<Card> built = List.of();

    public LoadoutCardsPage(Mode mode) {
        this.mode = mode;
    }

    /** One card: its model, whether it is the worn one, and the armour its preview wears. */
    private record Card(LoadoutCard.Model model, boolean worn, ItemStack[] dress) {
    }

    @Override
    public void render(GuiGraphicsExtractor g, PvContext ctx) {
        List<Card> cards = cards(ctx.profile);
        if (cards.isEmpty()) {
            PvDraw.empty(g, ctx, switch (mode) {
                case LOADOUTS -> "No loadouts saved.";
                case WARDROBE -> "No wardrobe slots.";
                case EQUIPMENT -> "No equipment wardrobe slots.";
            });
            return;
        }

        int headerH = ctx.font.lineHeight + 4;
        long worn = cards.stream().filter(Card::worn).count();
        String header = "§7" + cards.size() + (mode == Mode.LOADOUTS ? " loadouts" : " slots")
                + (worn > 0 ? " §8• §agreen §8= worn" : "");
        g.text(ctx.font, Component.literal(PvDraw.trim(ctx.font, header, ctx.width)), ctx.x, ctx.y, SBSTheme.TEXT);

        // Columns from the measured width; the scrollbar strip is always reserved.
        int top = ctx.y + headerH;
        int gridW = ctx.width - SciFiScrollbar.WIDTH - 4;
        int cols = Math.max(1, (gridW + GAP) / (MIN_CARD_W + GAP));
        int cardW = Math.max(1, Math.min(MAX_CARD_W, (gridW - GAP * (cols - 1)) / cols));
        int cardH = mode == Mode.EQUIPMENT ? EQUIP_CARD_H : cardW + 60;
        int rows = (cards.size() + cols - 1) / cols;
        int contentH = rows * (cardH + GAP);
        int viewH = ctx.bottom() - top;
        int totalSteps = (contentH + STEP - 1) / STEP;
        int visibleSteps = Math.max(1, viewH / STEP);
        int scroll = ctx.rows(totalSteps, visibleSteps);
        int offset = scroll * STEP;

        boolean inBand = ctx.mouseY >= top && ctx.mouseY < ctx.bottom()
                && ctx.mouseX >= ctx.x && ctx.mouseX < ctx.x + gridW;
        int mx = inBand ? ctx.mouseX : Integer.MIN_VALUE;
        int my = inBand ? ctx.mouseY : Integer.MIN_VALUE;
        PvPreviewPlayer model = mode == Mode.EQUIPMENT ? null : preview(ctx);

        List<Component> tip = null;
        g.enableScissor(ctx.x, top, ctx.x + gridW, ctx.bottom());
        for (int i = 0; i < cards.size(); i++) {
            int cx = ctx.x + (i % cols) * (cardW + GAP);
            int cy = top + (i / cols) * (cardH + GAP) - offset;
            if (cy + cardH < top || cy > ctx.bottom()) {
                continue;
            }
            Card card = cards.get(i);
            boolean hover = mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH;
            LoadoutCard.frame(g, cx, cy, cardW, cardH, card.worn(), hover, false);
            // The entity ignores the scissor's intent at the band's edges, so a model is only drawn
            // while its whole box is inside the visible band.
            LoadoutCard.Preview body = model == null || card.dress() == null ? null
                    : (x1, y1, x2, y2, scale) -> {
                        if (y1 >= top && y2 <= ctx.bottom()) {
                            model.draw(g, card.dress(), x1, y1, x2, y2, scale,
                                    inBand ? ctx.mouseX : (x1 + x2) / 2, inBand ? ctx.mouseY : (y1 + y2) / 2);
                        }
                    };
            List<Component> t = LoadoutCard.body(g, ctx.font, card.model(), cx, cy, cardW, cardH,
                    cx + cardW - 4, body, mx, my);
            tip = t != null ? t : tip;
        }
        g.disableScissor();

        if (totalSteps > visibleSteps) {
            PvDraw.scrollbar(g, ctx.right() - SciFiScrollbar.WIDTH, top, viewH, totalSteps, visibleSteps, scroll);
        }
        if (tip != null) {
            PvDraw.tooltip(g, ctx.font, tip, ctx.mouseX, ctx.mouseY);
        }
    }

    @Override
    public void reset() {
        builtFor = null;
    }

    private List<Card> cards(JsonObject profile) {
        long now = System.currentTimeMillis();
        if (profile != builtFor || now - builtAt >= REBUILD_MS) {
            JsonObject lo = PvDraw.obj(profile, "loadouts");
            built = lo == null ? List.of() : switch (mode) {
                case LOADOUTS -> loadoutCards(profile, lo);
                case WARDROBE -> wardrobeCards(profile, lo);
                case EQUIPMENT -> equipmentCards(profile, lo);
            };
            builtFor = profile;
            builtAt = now;
        }
        return built;
    }

    private PvPreviewPlayer preview(PvContext ctx) {
        String uuid = PvDraw.str(ctx.root, "uuid");
        if (preview == null || !uuid.equals(previewFor)) {
            preview = PvPreviewPlayer.of(uuid, PvDraw.str(ctx.root, "name"));
            previewFor = uuid;
        }
        return preview;
    }

    // ------------------------------------------------------------------
    // Card models
    // ------------------------------------------------------------------

    private static List<Card> loadoutCards(JsonObject profile, JsonObject lo) {
        Map<Long, JsonObject> armor = byId(PvDraw.arr(lo, "sets"));
        Map<Long, JsonObject> equipment = byId(PvDraw.arr(lo, "equipment_sets"));
        long wornArmor = PvDraw.has(lo, "equipped_armor") ? PvDraw.num(lo, "equipped_armor") : -1;
        long wornEquip = PvDraw.has(lo, "equipped_equipment") ? PvDraw.num(lo, "equipped_equipment") : -1;
        List<Card> out = new ArrayList<>();
        for (JsonElement el : PvDraw.arr(lo, "loadouts")) {
            JsonObject l = el.getAsJsonObject();
            String label = "#" + PvDraw.num(l, "id") + " §b" + PvDraw.str(l, "name");
            if (PvDraw.has(l, "used") && !l.get("used").getAsBoolean()) {
                out.add(new Card(new LoadoutCard.Model(label, null, null, null, null, null, true, "§8unused"),
                        false, null));
                continue;
            }
            JsonObject armorSet = PvDraw.has(l, "armor_set") ? armor.get(PvDraw.num(l, "armor_set")) : null;
            JsonObject equipSet = PvDraw.has(l, "equipment_set") ? equipment.get(PvDraw.num(l, "equipment_set")) : null;
            JsonArray pieces = armorSet == null ? new JsonArray() : PvDraw.arr(armorSet, "armor");
            JsonArray equipPieces = equipSet == null ? new JsonArray() : PvDraw.arr(equipSet, "equipment");
            ItemStack[] dress = stacks(profile, pieces);
            label = valueLabel("#" + PvDraw.num(l, "id"), dress, stacks(profile, equipPieces), label);
            LoadoutCard.Model model = new LoadoutCard.Model(label, pet(l), details(l),
                    equipmentBoxes(profile, equipPieces),
                    dress, i -> pieceTooltip(pieces, i, ARMOR_NAMES[i]), false, "");
            boolean worn = PvDraw.has(l, "armor_set") && PvDraw.num(l, "armor_set") == wornArmor
                    && (!PvDraw.has(l, "equipment_set") || PvDraw.num(l, "equipment_set") == wornEquip);
            out.add(new Card(model, worn, dress));
        }
        return out;
    }

    private static List<Card> wardrobeCards(JsonObject profile, JsonObject lo) {
        List<Card> out = new ArrayList<>();
        for (JsonElement el : PvDraw.arr(lo, "sets")) {
            JsonObject set = el.getAsJsonObject();
            boolean worn = PvDraw.has(set, "worn") && set.get("worn").getAsBoolean();
            String label = "Slot " + PvDraw.num(set, "id") + (worn ? " §a(worn)" : "");
            if (PvDraw.has(set, "empty") && set.get("empty").getAsBoolean()) {
                out.add(new Card(new LoadoutCard.Model(label, null, null, null, null, null, true, "§8empty"),
                        worn, null));
                continue;
            }
            JsonArray pieces = PvDraw.arr(set, "armor");
            ItemStack[] dress = stacks(profile, pieces);
            label = valueLabel(label, dress, new ItemStack[0], label);
            out.add(new Card(new LoadoutCard.Model(label, null, null, null, dress,
                    i -> pieceTooltip(pieces, i, ARMOR_NAMES[i]), false, ""), worn, dress));
        }
        return out;
    }

    private static List<Card> equipmentCards(JsonObject profile, JsonObject lo) {
        List<Card> out = new ArrayList<>();
        for (JsonElement el : PvDraw.arr(lo, "equipment_sets")) {
            JsonObject set = el.getAsJsonObject();
            boolean worn = PvDraw.has(set, "worn") && set.get("worn").getAsBoolean();
            String label = "Slot " + PvDraw.num(set, "id") + (worn ? " §a(worn)" : "");
            if (PvDraw.has(set, "empty") && set.get("empty").getAsBoolean()) {
                out.add(new Card(new LoadoutCard.Model(label, null, null, null, null, null, true, "§8empty"),
                        worn, null));
                continue;
            }
            JsonArray pieces = PvDraw.arr(set, "equipment");
            ItemStack[] equip = stacks(profile, pieces);
            label = valueLabel(label, equip, new ItemStack[0], label);
            out.add(new Card(new LoadoutCard.Model(label, null, null, null, equip,
                    i -> pieceTooltip(pieces, i, EQUIP_NAMES[i]), false, ""), worn, null));
        }
        return out;
    }

    /**
     * "#2 1.2B": the card's number and what its gear is worth, as the storage pages show a page's
     * value (offline appraisal, the same Show Storage Value setting). Falls back to {@code plain}
     * when values are off or nothing could be priced.
     */
    private static String valueLabel(String prefix, ItemStack[] a, ItemStack[] b, String plain) {
        if (!sbs.modid.client.helper.storage.StoragePageCard.showValue()) {
            return plain;
        }
        List<ItemStack> all = new ArrayList<>(java.util.Arrays.asList(a));
        all.addAll(java.util.Arrays.asList(b));
        var total = sbs.modid.client.economy.itemvalue.ValueSum.of(all);
        return total.any() ? prefix + " §6" + total.label() : plain;
    }

    private static Map<Long, JsonObject> byId(JsonArray sets) {
        Map<Long, JsonObject> out = new HashMap<>();
        for (JsonElement el : sets) {
            JsonObject set = el.getAsJsonObject();
            out.put(PvDraw.num(set, "id"), set);
        }
        return out;
    }

    /** Four stacks from a pieces array, empty where a piece is missing. */
    private static ItemStack[] stacks(JsonObject profile, JsonArray pieces) {
        ItemStack[] out = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
        for (int i = 0; i < out.length && i < pieces.size(); i++) {
            if (pieces.get(i).isJsonObject()) {
                out[i] = PvDraw.stack(profile, pieces.get(i).getAsJsonObject());
            }
        }
        return out;
    }

    private static List<Component> pieceTooltip(JsonArray pieces, int i, String name) {
        if (i < pieces.size() && pieces.get(i).isJsonObject()) {
            return PvDraw.slotTooltip(pieces.get(i).getAsJsonObject(), null);
        }
        return List.of(Component.literal("§7" + name + ": §8None"));
    }

    private static LoadoutCard.Box[] equipmentBoxes(JsonObject profile, JsonArray pieces) {
        LoadoutCard.Box[] boxes = new LoadoutCard.Box[4];
        for (int i = 0; i < 4; i++) {
            boolean present = i < pieces.size() && pieces.get(i).isJsonObject();
            ItemStack icon = present ? PvDraw.stack(profile, pieces.get(i).getAsJsonObject()) : ItemStack.EMPTY;
            int index = i;
            boxes[i] = new LoadoutCard.Box(icon, LoadoutCard.EQUIP_LETTERS[i], present,
                    () -> pieceTooltip(pieces, index, EQUIP_NAMES[index]));
        }
        return boxes;
    }

    /** Heart of the Forest preset, Heart of the Mountain preset, power stone, tuning preset. */
    private static LoadoutCard.Box[] details(JsonObject l) {
        ItemStack[] fallback = LoadoutCard.detailFallbackIcons();
        String power = PvDraw.has(l, "power_stone") ? PvDraw.str(l, "power_stone") : null;
        ItemStack stone = power == null ? ItemStack.EMPTY : LoadoutCard.powerStoneIcon(power);
        return new LoadoutCard.Box[] {
            preset(l, "hotf_slot", fallback[0], 0, "Heart of the Forest"),
            preset(l, "hotm_slot", fallback[1], 1, "Heart of the Mountain"),
            new LoadoutCard.Box(power == null ? ItemStack.EMPTY : stone.isEmpty() ? fallback[2] : stone,
                    LoadoutCard.DETAIL_LETTERS[2], power != null,
                    () -> List.of(Component.literal("§7Power Stone: " + (power == null ? "§8None"
                            : "§f" + PvDraw.pretty(power))))),
            preset(l, "tuning_slot", fallback[3], 3, "Tuning"),
        };
    }

    private static LoadoutCard.Box preset(JsonObject l, String key, ItemStack icon, int letter, String name) {
        boolean present = PvDraw.has(l, key);
        long slot = present ? PvDraw.num(l, key) : 0;
        return new LoadoutCard.Box(present ? icon : ItemStack.EMPTY, LoadoutCard.DETAIL_LETTERS[letter], present,
                () -> List.of(Component.literal("§7" + name + ": " + (present ? "§fpreset " + slot : "§8None"))));
    }

    private static LoadoutCard.Pet pet(JsonObject l) {
        JsonObject pet = PvDraw.obj(l, "pet");
        if (pet == null) {
            return null;
        }
        String type = PvDraw.str(pet, "type");
        String tier = PvDraw.str(pet, "tier");
        String name = "[Lvl " + PvDraw.num(pet, "level") + "] " + PvDraw.pretty(type);
        String colour = PvDraw.TIER_COLOR.getOrDefault(tier, "§f");
        String skin = PvDraw.petSkinName(type, PvDraw.str(pet, "skin"));
        return new LoadoutCard.Pet(PvDraw.petIcon(type, PvDraw.str(pet, "skin")),
                skin.isEmpty() ? name : name + " (" + skin + ")", () -> {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(colour + name));
            tip.add(Component.literal("§8" + PvDraw.pretty(tier)));
            if (!skin.isEmpty()) {
                tip.add(Component.literal("§7Skin: §d" + skin));
            }
            if (PvDraw.has(pet, "item")) {
                tip.add(Component.literal("§7Held item: §f" + PvDraw.pretty(PvDraw.str(pet, "item"))));
            }
            return tip;
        });
    }
}
