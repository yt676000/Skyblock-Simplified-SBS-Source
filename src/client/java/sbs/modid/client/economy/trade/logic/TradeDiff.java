/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import sbs.modid.client.economy.trade.model.ItemPrint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What changed between two fingerprints, as short human lines ("5✪ → 0✪", "Recomb: yes → no",
 * "Hyperion → Astraea").
 *
 * <p>{@code worse} is false only when every difference is the kind that cannot hurt the receiver:
 * more of the same item, or more coins. Anything else - a different id, a lost upgrade, even a
 * sideways reforge - counts, because a scam is exactly a change the receiver did not ask for.
 */
public final class TradeDiff {

    public record Result(List<String> lines, boolean worse) {
        public boolean changed() {
            return !lines.isEmpty();
        }
    }

    private TradeDiff() {
    }

    public static Result compare(ItemPrint a, ItemPrint b) {
        List<String> out = new ArrayList<>();
        boolean worse = false;
        if (a == null || b == null) {
            return new Result(List.of(), false);
        }
        if (a.isCoins() || b.isCoins()) {
            if (a.coins() != b.coins()) {
                out.add("Coins " + compact(a.coins()) + " → " + compact(b.coins()));
                worse = b.coins() < a.coins();
            }
            return new Result(out, worse);
        }
        if (!Objects.equals(a.skyblockId(), b.skyblockId())) {
            if (a.name().equals(b.name())) {
                out.add(pretty(a.skyblockId()) + " → " + pretty(b.skyblockId()) + " (same name, other item!)");
            } else {
                out.add(a.name() + " → " + b.name());
            }
            worse = true;
        } else if (!a.name().equals(b.name())) {
            out.add("Name: " + a.name() + " → " + b.name());
        }
        if (a.uuid() != null && b.uuid() != null && !a.uuid().equals(b.uuid())) {
            out.add("Different item (uuid changed)");
            worse = true;
        }
        if (a.count() != b.count()) {
            out.add("Count " + a.count() + " → " + b.count());
            worse |= b.count() < a.count();
        }
        worse |= num(out, "", a.stars(), b.stars(), "✪");
        worse |= num(out, "Master stars ", a.masterStars(), b.masterStars(), "");
        if (a.recomb() != b.recomb()) {
            out.add("Recomb: " + yes(a.recomb()) + " → " + yes(b.recomb()));
            worse = true;
        }
        if (!Objects.equals(a.reforge(), b.reforge())) {
            out.add("Reforge: " + opt(a.reforge()) + " → " + opt(b.reforge()));
            worse = true;
        }
        worse |= num(out, "Potato books ", a.hotPotato(), b.hotPotato(), "");
        worse |= mapDiff(out, a.enchants(), b.enchants(), "");
        worse |= mapDiff(out, a.gems(), b.gems(), "Gem ");
        if (!a.scrolls().equals(b.scrolls())) {
            out.add("Scrolls: " + list(a.scrolls()) + " → " + list(b.scrolls()));
            worse = true;
        }
        worse |= petDiff(out, a.pet(), b.pet());
        if (!Objects.equals(a.dye(), b.dye())) {
            out.add("Dye: " + opt(a.dye()) + " → " + opt(b.dye()));
            worse = true;
        }
        if (!Objects.equals(a.skin(), b.skin())) {
            out.add("Skin: " + opt(a.skin()) + " → " + opt(b.skin()));
            worse = true;
        }
        worse |= mapDiff(out, a.other(), b.other(), "");
        // The id line already says it all; with a different id the rest is the same story told twice.
        return new Result(out, worse);
    }

    private static boolean petDiff(List<String> out, ItemPrint.Pet a, ItemPrint.Pet b) {
        if (a == null && b == null) {
            return false;
        }
        if (a == null || b == null) {
            out.add("Pet: " + (a == null ? "none" : pretty(a.type())) + " → " + (b == null ? "none" : pretty(b.type())));
            return true;
        }
        boolean worse = false;
        if (!Objects.equals(a.type(), b.type())) {
            out.add("Pet " + pretty(a.type()) + " → " + pretty(b.type()));
            worse = true;
        }
        if (a.level() != b.level()) {
            out.add("Pet level " + a.level() + " → " + b.level());
            worse |= b.level() < a.level();
        } else if (Math.abs(a.exp() - b.exp()) >= 1) {
            out.add("Pet exp " + compact((long) a.exp()) + " → " + compact((long) b.exp()));
            worse |= b.exp() < a.exp();
        }
        if (!Objects.equals(a.tier(), b.tier())) {
            out.add("Pet rarity " + opt(a.tier()) + " → " + opt(b.tier()));
            worse = true;
        }
        if (!Objects.equals(a.heldItem(), b.heldItem())) {
            out.add("Pet item " + opt(a.heldItem()) + " → " + opt(b.heldItem()));
            worse = true;
        }
        if (!Objects.equals(a.skin(), b.skin())) {
            out.add("Pet skin " + opt(a.skin()) + " → " + opt(b.skin()));
            worse = true;
        }
        return worse;
    }

    private static boolean num(List<String> out, String label, int a, int b, String suffix) {
        if (a == b) {
            return false;
        }
        out.add(label + a + suffix + " → " + b + suffix);
        return b < a;
    }

    private static <V> boolean mapDiff(List<String> out, Map<String, V> a, Map<String, V> b, String prefix) {
        boolean worse = false;
        for (String key : new TreeSet<>(union(a, b))) {
            V va = a.get(key);
            V vb = b.get(key);
            if (Objects.equals(va, vb)) {
                continue;
            }
            out.add(prefix + pretty(key) + " " + val(va) + " → " + val(vb));
            if (va instanceof Integer ia && vb instanceof Integer ib) {
                worse |= ib < ia;
            } else {
                Double da = number(va);
                Double db = number(vb);
                worse |= da != null && db != null ? db < da : true;
            }
        }
        return worse;
    }

    private static <V> java.util.Set<String> union(Map<String, V> a, Map<String, V> b) {
        java.util.Set<String> s = new java.util.HashSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }

    private static Double number(Object v) {
        if (v == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String val(Object v) {
        if (v == null) {
            return "none";
        }
        if (v instanceof Integer i) {
            return String.valueOf(i);
        }
        Double d = number(v);
        if (d != null && Math.abs(d) >= 1000) {
            return compact(Math.round(d));
        }
        return pretty(v.toString());
    }

    /** 10000000 → "10M", 1500 → "1.5k". */
    public static String compact(long v) {
        long abs = Math.abs(v);
        String[] units = {"", "k", "M", "B", "T"};
        double d = v;
        int u = 0;
        while (Math.abs(d) >= 1000 && u < units.length - 1) {
            d /= 1000;
            u++;
        }
        if (u == 0) {
            return String.valueOf(v);
        }
        String s = abs >= 1000 && Math.abs(d) < 100 ? String.format(Locale.ROOT, "%.1f", d) : String.format(Locale.ROOT, "%.0f", d);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s + units[u];
    }

    /** "ULTIMATE_WISE" / "drill_fuel" → "Ultimate Wise" / "Drill Fuel". */
    public static String pretty(String id) {
        if (id == null || id.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String opt(String s) {
        return s == null || s.isEmpty() ? "none" : pretty(s);
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    private static String list(List<String> l) {
        return l.isEmpty() ? "none" : String.valueOf(l.size());
    }
}
