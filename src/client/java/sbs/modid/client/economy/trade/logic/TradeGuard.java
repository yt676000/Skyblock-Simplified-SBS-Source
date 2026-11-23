/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import sbs.modid.client.economy.trade.model.ItemPrint;
import sbs.modid.client.economy.trade.model.TradeSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The trade-menu scam detector: fed one parsed {@link TradeLayout.Menu} per change, it answers which
 * of the partner's slots to frame, what to write in their tooltips, what to say in chat and how long
 * my own accept click stays blocked.
 *
 * <p>Pure logic with an injected clock, so every rule is unit-tested. It never clicks and never
 * sends anything: the only effect it can have on a trade is refusing my own click on the accept
 * button for a few seconds, and Shift overrides even that.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Swap</b> - an item leaves their side and a look-alike that is worse arrives (same slot or
 *       not). A uuid change on a look-alike is always a swap. Moving an item is not a change.</li>
 *   <li><b>Bait &amp; switch</b> - an item lands on their side that is worse than a look-alike the
 *       same partner showed within the retention window ({@link PartnerMemory}).</li>
 *   <li><b>Changed after confirm</b> - their side changed while they had confirmed or a deal timer
 *       ran; the classic "confirm, swap, hope they accept when the timer ends".</li>
 *   <li><b>Low value</b> - what I get is worth more than the threshold less than what I give.</li>
 *   <li>The deal timer ending locks accept too, without a message.</li>
 * </ul>
 */
public final class TradeGuard {

    public record Settings(double thresholdPct, boolean acceptLock, long lockMs,
                           boolean warnAnyChangeAfterConfirm, long retentionMs) {
        public static final Settings DEFAULT = new Settings(15, true, 3000, true, 60 * 60_000L);
    }

    public enum Kind { SWAP, BAIT, CHANGED_AFTER_CONFIRM, LOW_VALUE, UNKNOWN_STATE }

    /** A framed slot on their side. {@code names} marks the differing part of the two names. */
    public record Flag(int slot, Kind kind, ItemPrint now, String headline, List<String> diff,
                       NameDiff.Marked names, boolean valueUnknown) {
    }

    /** Something to tell the player (or, for UNKNOWN_STATE, only the log). */
    public record Alert(Kind kind, String message) {
    }

    /** Both sides' appraised totals. {@code pct} is (get - give) / give, {@code null} when I give nothing. */
    public record Totals(long give, long get, boolean giveUnknown, boolean getUnknown) {
        public Double pct() {
            return give <= 0 ? null : (get - give) / (double) give;
        }
    }

    private final PartnerMemory memory;
    private String partner;
    private TradeLayout.Menu prev;
    private final Map<Integer, Flag> flags = new TreeMap<>();
    private final Set<String> loggedStates = new HashSet<>();
    private Totals totals = new Totals(0, 0, false, false);
    private long lockUntil;
    private boolean lowWarned;

    public TradeGuard(PartnerMemory memory) {
        this.memory = memory;
    }

    public PartnerMemory memory() {
        return memory;
    }

    /** The menu closed: forget this trade, keep what partners have shown. */
    public void close() {
        partner = null;
        prev = null;
        flags.clear();
        totals = new Totals(0, 0, false, false);
        lockUntil = 0;
        lowWarned = false;
    }

    public String partner() {
        return partner;
    }

    public Map<Integer, Flag> flags() {
        return Collections.unmodifiableMap(flags);
    }

    public Totals totals() {
        return totals;
    }

    public long lockRemaining(long now) {
        return Math.max(0, lockUntil - now);
    }

    /** Whether my click on {@code slot} must be refused. {@code override} is Shift held. */
    public boolean blocksAccept(int slot, boolean override, Settings s, long now) {
        return slot == TradeLayout.ACCEPT && s.acceptLock() && !override && prev != null && now < lockUntil;
    }

    /** An item a partner showed outside the trade menu (chat item link). */
    public void observeShown(String who, ItemPrint print, Long value, long now) {
        memory.record(who, print, value, now, PartnerMemory.Source.CHAT);
    }

    public List<Alert> update(TradeLayout.Menu m, Settings s, long now) {
        List<Alert> out = new ArrayList<>();
        if (m == null) {
            return out;
        }
        boolean first = prev == null || !m.partner().equalsIgnoreCase(partner);
        if (first) {
            close();
            partner = m.partner();
        }
        logUnknown(m, out);

        Map<Integer, TradeSlot> theirs = items(m.theirs());
        List<ItemPrint> onTable = theirs.values().stream().map(TradeSlot::print).toList();
        dropStaleFlags(theirs);

        List<TradeSlot> added = new ArrayList<>();
        if (first) {
            added.addAll(theirs.values());
        } else {
            Map<Integer, TradeSlot> before = items(prev.theirs());
            List<TradeSlot> removed = minus(before, theirs);
            List<TradeSlot> arrivals = minus(theirs, before);
            added.addAll(arrivals);
            boolean changed = !removed.isEmpty() || !added.isEmpty();
            for (TradeSlot a : added) {
                TradeSlot r = pair(a, removed);
                if (r == null) {
                    continue;
                }
                removed.remove(r);
                TradeDiff.Result diff = TradeDiff.compare(r.print(), a.print());
                boolean uuidSwap = r.print().uuid() != null && a.print().uuid() != null
                        && !r.print().uuid().equals(a.print().uuid());
                if (diff.worse() || uuidSwap) {
                    NameDiff.Marked names = NameDiff.mark(r.print().name(), a.print().name());
                    flags.put(a.slot(), new Flag(a.slot(), Kind.SWAP, a.print(),
                            "⚠ Swapped! Was: " + label(r.print()) + " → now: " + label(a.print()),
                            diff.lines(), names, a.value() == null));
                    out.add(new Alert(Kind.SWAP, "§c⚠ " + partner + " swapped §f" + label(r.print())
                            + "§c → §f" + label(a.print()) + "§7 (" + String.join(", ", head(diff.lines())) + ")"));
                    lock(s, now);
                }
            }
            boolean wasConfirmed = prev.partnerState() == TradeLayout.PartnerState.CONFIRMED
                    || prev.partnerState() == TradeLayout.PartnerState.DEAL_TIMER
                    || prev.accept() == TradeLayout.AcceptState.DEAL_TIMER
                    || prev.accept() == TradeLayout.AcceptState.ACCEPTED
                    || prev.partnerState() == TradeLayout.PartnerState.PENDING_THEIR_CONFIRM;
            boolean lost = !removed.isEmpty() || flags.values().stream().anyMatch(f -> f.kind() == Kind.SWAP
                    && arrivals.stream().anyMatch(a -> a.slot() == f.slot()));
            if (changed && wasConfirmed && (s.warnAnyChangeAfterConfirm() || lost)) {
                for (TradeSlot a : added) {
                    flags.putIfAbsent(a.slot(), new Flag(a.slot(), Kind.CHANGED_AFTER_CONFIRM, a.print(),
                            "⚠ Changed after confirm: " + label(a.print()), List.of(), null, a.value() == null));
                }
                out.add(new Alert(Kind.CHANGED_AFTER_CONFIRM, "§c⚠ " + partner
                        + " changed the trade after confirming - check every item before accepting!"));
                lock(s, now);
            }
            if (prev.accept() == TradeLayout.AcceptState.DEAL_TIMER
                    && m.accept() != TradeLayout.AcceptState.DEAL_TIMER) {
                lock(s, now);   // the moment people click through: one more beat to look
            }
        }

        for (TradeSlot a : added) {
            if (flags.containsKey(a.slot()) && flags.get(a.slot()).kind() == Kind.SWAP) {
                continue;
            }
            PartnerMemory.Match match = memory.find(partner, a.print(), a.value(), now, s.retentionMs(), onTable);
            if (match == null) {
                continue;
            }
            ItemPrint old = match.shown().print();
            String drop = match.valueDropPct() == null ? "" : String.format(java.util.Locale.ROOT,
                    " (-%.0f%% value)", match.valueDropPct() * 100);
            String headline = "⚠ BAIT & SWITCH: " + partner + " showed you " + label(old) + " "
                    + age(match.ageMs()) + " ago, now offers " + label(a.print()) + drop;
            flags.put(a.slot(), new Flag(a.slot(), Kind.BAIT, a.print(), headline, match.diff().lines(),
                    NameDiff.mark(old.name(), a.print().name()), a.value() == null));
            out.add(new Alert(Kind.BAIT, "§c" + headline));
            lock(s, now);
        }
        for (TradeSlot t : theirs.values()) {
            memory.record(partner, t.print(), t.value(), now, PartnerMemory.Source.TRADE);
        }

        totals = totals(m);
        Double pct = totals.pct();
        boolean low = pct != null && !totals.getUnknown() && pct * 100 < -s.thresholdPct();
        if (low && !lowWarned) {
            out.add(new Alert(Kind.LOW_VALUE, String.format(java.util.Locale.ROOT,
                    "§c⚠ You give ~%s but get ~%s (%.0f%%)", TradeDiff.compact(totals.give()),
                    TradeDiff.compact(totals.get()), pct * 100)));
            lock(s, now);
        }
        lowWarned = low;
        prev = m;
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void lock(Settings s, long now) {
        if (s.acceptLock()) {
            lockUntil = Math.max(lockUntil, now + s.lockMs());
        }
    }

    private void logUnknown(TradeLayout.Menu m, List<Alert> out) {
        if (m.accept() == TradeLayout.AcceptState.UNKNOWN || m.accept() == TradeLayout.AcceptState.READY
                || m.accept() == TradeLayout.AcceptState.ACCEPTED) {
            // READY/ACCEPTED are matched by wording only - logged once until a scan confirms them.
            if (loggedStates.add("39:" + m.acceptRaw())) {
                out.add(new Alert(Kind.UNKNOWN_STATE, "slot 39 " + m.accept() + ": " + m.acceptRaw()));
            }
        }
        if (m.partnerState() == TradeLayout.PartnerState.UNKNOWN && loggedStates.add("41:" + m.partnerRaw())) {
            out.add(new Alert(Kind.UNKNOWN_STATE, "slot 41 UNKNOWN: " + m.partnerRaw()));
        }
        for (TradeSlot t : m.theirs().values()) {
            if (t.print() != null && t.print().isCoins() && loggedStates.add("coins:" + t.slot())) {
                out.add(new Alert(Kind.UNKNOWN_STATE, "their coin head at slot " + t.slot() + ": \""
                        + t.name() + "\" lore=" + t.lore()));
            }
        }
    }

    private void dropStaleFlags(Map<Integer, TradeSlot> theirs) {
        flags.values().removeIf(f -> {
            TradeSlot t = theirs.get(f.slot());
            return t == null || !t.print().sameAs(f.now());
        });
    }

    private static Map<Integer, TradeSlot> items(Map<Integer, TradeSlot> side) {
        Map<Integer, TradeSlot> out = new TreeMap<>();
        side.forEach((k, v) -> {
            if (v.print() != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** Entries of {@code a} with no identical item anywhere in {@code b} (a move is not a change). */
    private static List<TradeSlot> minus(Map<Integer, TradeSlot> a, Map<Integer, TradeSlot> b) {
        List<TradeSlot> out = new ArrayList<>();
        List<TradeSlot> pool = new ArrayList<>(b.values());
        for (TradeSlot t : a.values()) {
            TradeSlot same = pool.stream().filter(o -> o.print().sameAs(t.print())).findFirst().orElse(null);
            if (same != null) {
                pool.remove(same);
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /** The removed item this arrival replaced: same slot first, then any look-alike. */
    private static TradeSlot pair(TradeSlot added, List<TradeSlot> removed) {
        for (TradeSlot r : removed) {
            if (r.slot() == added.slot() && ItemFamily.lookAlike(r.print(), added.print())) {
                return r;
            }
        }
        for (TradeSlot r : removed) {
            if (ItemFamily.lookAlike(r.print(), added.print())) {
                return r;
            }
        }
        return null;
    }

    static Totals totals(TradeLayout.Menu m) {
        long give = 0;
        long get = 0;
        boolean giveUnknown = false;
        boolean getUnknown = false;
        for (TradeSlot t : m.mine().values()) {
            if (t.print() == null) {
                continue;
            }
            Long v = t.print().isCoins() ? Long.valueOf(t.print().coins()) : t.value();
            if (v == null) {
                giveUnknown = true;
            } else {
                give += v;
            }
        }
        for (TradeSlot t : m.theirs().values()) {
            if (t.print() == null) {
                continue;
            }
            Long v = t.print().isCoins() ? Long.valueOf(t.print().coins()) : t.value();
            if (v == null) {
                getUnknown = true;
            } else {
                get += v;
            }
        }
        return new Totals(give, get, giveUnknown, getUnknown);
    }

    static String label(ItemPrint p) {
        if (p.isCoins()) {
            return TradeDiff.compact(p.coins()) + " coins";
        }
        return (p.count() > 1 ? p.count() + "x " : "") + p.name();
    }

    private static List<String> head(List<String> lines) {
        return lines.size() <= 3 ? lines : lines.subList(0, 3);
    }

    static String age(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        long min = s / 60;
        return min < 60 ? min + "m" : (min / 60) + "h" + (min % 60) + "m";
    }
}
