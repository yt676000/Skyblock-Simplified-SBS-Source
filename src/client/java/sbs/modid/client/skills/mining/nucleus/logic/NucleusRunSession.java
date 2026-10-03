/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.skills.garden.pests.PestChat;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything between the game and the ledger that needs no game object: chat lines to events, events
 * to bookings, sack messages through the filter, cost lines through the window. Pure - the tracker
 * feeds it text, decreases and a clock; tests feed it the logged lines and get the same bookings.
 *
 * <p>The only things that can put coins into a run are a reward block's item lines and the sack
 * gains {@link SackGainFilter} lets through. There is no path from an inventory increase to revenue:
 * inventory changes only ever reach {@link CostWindow}, as decreases.
 */
public final class NucleusRunSession {

    /** How a run is valued when it finishes or is previewed. */
    public record Valuation(NucleusPricing.PriceBook book, NucleusPricing.Side side,
                            boolean selfObtainedCounted) {
    }

    /**
     * What one call changed: whether anything worth saving moved, any run it finished, and the parser
     * events it applied, in order - {@link MoleReminder} reads those instead of parsing chat again.
     */
    public record Result(boolean changed, List<NucleusRunLedger.Finished> finished,
                         List<NucleusChatParser.Event> events) {
        static final Result NONE = new Result(false, List.of(), List.of());
    }

    private final NucleusRunLedger ledger;
    private final NucleusChatParser parser = new NucleusChatParser();
    private final CostWindow costs = new CostWindow();
    private final Function<String, String> resolver;
    private final Consumer<String> log;
    private final Set<String> loggedUnresolved = new HashSet<>();

    private long lastContainerOpenAt = -1L;
    private long lastStashPickupAt = -1L;

    /**
     * @param resolver display name to SkyBlock id, {@code null} when unknown
     * @param log      where the capture lines go ({@code [SBS][Nucleus]} in game)
     */
    public NucleusRunSession(NucleusRunLedger ledger, Function<String, String> resolver, Consumer<String> log) {
        this.ledger = ledger;
        this.resolver = resolver;
        this.log = log;
    }

    public NucleusRunLedger ledger() {
        return ledger;
    }

    public NucleusChatParser parser() {
        return parser;
    }

    /** Whether a cost line is waiting for its decrease. */
    public boolean costWaiting() {
        return costs.waiting();
    }

    /** A container screen is open now. */
    public void containerOpen(long now) {
        lastContainerOpenAt = now;
    }

    /** One chat message on the Crystal Hollows; {@code hover} is its hover text, or empty. */
    public Result onChat(String raw, String hover, long now, Valuation valuation) {
        boolean changed = false;
        List<NucleusRunLedger.Finished> finished = new ArrayList<>();
        String plain = NucleusChatParser.strip(raw);
        if (NucleusSignals.SACKS.matcher(plain).find()) {
            changed = onSacks(plain, hover == null ? "" : hover, now);
        }
        List<NucleusChatParser.Event> events = parser.onLine(raw, now);
        for (NucleusChatParser.Event event : events) {
            changed |= apply(event, now, valuation, finished);
        }
        return new Result(changed, finished, events);
    }

    /** An item left the inventory while no container was open. */
    public Result onDecrease(String id, long qty, long now) {
        return new Result(book(costs.onDecrease(id, qty, now), now), List.of(), List.of());
    }

    /** Every tick on the Crystal Hollows: idle blocks close, cost windows settle. */
    public Result poll(long now, Valuation valuation) {
        boolean changed = false;
        List<NucleusRunLedger.Finished> finished = new ArrayList<>();
        List<NucleusChatParser.Event> events = parser.poll(now);
        for (NucleusChatParser.Event event : events) {
            changed |= apply(event, now, valuation, finished);
        }
        changed |= book(costs.poll(now), now);
        return changed || !finished.isEmpty() || !events.isEmpty()
                ? new Result(changed, finished, events) : Result.NONE;
    }

    /** Lobby change: an open block and a waiting cost line do not carry across. */
    public void clear() {
        parser.clear();
        costs.clear();
    }

    /** After a reset: names may be logged as unresolved again. */
    public void forgetLogged() {
        loggedUnresolved.clear();
    }

    private boolean onSacks(String plain, String hover, long now) {
        Map<String, Long> gains = PestChat.sackGains(hover);
        SackGainFilter.Decision decision = SackGainFilter.evaluate(plain, gains, now,
                lastContainerOpenAt, lastStashPickupAt, ledger.data().arrivals);
        if (decision.skipped() != null) {
            log.accept("sacks skipped (" + decision.skipped() + "): " + plain);
        }
        for (Map.Entry<String, Long> entry : decision.booked().entrySet()) {
            ledger.sackItem(entry.getKey(), resolve(entry.getKey()), entry.getValue(), now);
        }
        return !gains.isEmpty();
    }

    private boolean apply(NucleusChatParser.Event event, long now, Valuation valuation,
                          List<NucleusRunLedger.Finished> finished) {
        switch (event) {
            case NucleusChatParser.CrystalFound found -> ledger.crystalFound(found.crystal(), now);
            case NucleusChatParser.CrystalPlaced placed -> ledger.crystalPlaced(placed.crystal(), now);
            case NucleusChatParser.BlockOpened opened -> {
                return false;
            }
            case NucleusChatParser.Loot loot -> {
                String id = resolve(loot.name());
                if (loot.source() == NucleusChatParser.Source.BUNDLE) {
                    ledger.bundleItem(loot.name(), id, loot.qty(), now);
                } else {
                    ledger.chestItem(loot.name(), id, loot.qty(), now);
                }
            }
            case NucleusChatParser.NonCoin nonCoin -> ledger.nonCoin(nonCoin.kind(), nonCoin.amount(), now);
            case NucleusChatParser.BlockClosed closed -> {
                if (closed.source() != NucleusChatParser.Source.BUNDLE) {
                    return false;
                }
                finished.add(ledger.finish(now, valuation.book(), valuation.side(),
                        valuation.selfObtainedCounted()));
            }
            case NucleusChatParser.Cost cost -> {
                return book(costs.onLine(cost.rule(), cost.itemName(), now), now);
            }
            case NucleusChatParser.StashPickup ignored -> {
                lastStashPickupAt = now;
                return false;
            }
        }
        return true;
    }

    private boolean book(List<CostWindow.Outcome> outcomes, long now) {
        boolean changed = false;
        for (CostWindow.Outcome outcome : outcomes) {
            if (outcome.unresolved()) {
                log.accept("cost line found no matching inventory decrease, nothing booked: rule="
                        + outcome.rule().id());
                continue;
            }
            String id = outcome.itemId() != null ? outcome.itemId() : resolve(outcome.itemName());
            String name = outcome.itemName() != null ? outcome.itemName() : id;
            ledger.cost(outcome.rule().id(), name, id, outcome.qty(), outcome.estimated(), now);
            log.accept("cost booked: rule=" + outcome.rule().id() + " item=" + id + " qty=" + outcome.qty()
                    + (outcome.estimated() ? " (fallback, no decrease seen)" : ""));
            changed = true;
        }
        return changed;
    }

    private String resolve(String name) {
        String id = resolver.apply(name);
        if (id == null && name != null && loggedUnresolved.add(name)) {
            log.accept("unresolved: no item id for \"" + name + "\" - kept by name, unpriced");
        }
        return id;
    }
}
