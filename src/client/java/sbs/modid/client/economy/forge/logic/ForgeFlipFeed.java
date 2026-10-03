/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.core.api.RankingSource;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one place that decides <b>which</b> forge ranking the UI is showing, and why.
 *
 * <p>Deliberately the twin of {@link sbs.modid.client.economy.bazaar.logic.BazaarFlipFeed}: same
 * modes, same failure handling, same disclaimer contract. The two features answer the same question
 * about different markets and a reader should not have to learn two behaviours.
 *
 * <p>With a working licence and a reachable server this is a pass-through to {@link ForgeApi} and
 * nothing changes. When that ranking cannot be had — no token, an expired one, a locked one, the
 * backend down, or simply no route to it — it falls back to {@link LocalForgeEngine} instead of
 * leaving the window empty, and records which of those cases applied so the UI can say so.
 *
 * <p>The fallback is <b>automatic on every failure path</b>. The failure modes are not
 * distinguishable to the person looking at the screen, and asking them to work out which one they
 * are in before they can see anything is the worst version of this feature.
 *
 * <p>Both consumers ({@link sbs.modid.client.economy.forge.ui.ForgeFlipsOverlay} and
 * {@link sbs.modid.client.economy.forge.ui.ForgeFlipsScreen}) share one feed rather than each
 * keeping their own copy, so opening one after the other does not fetch or recompute twice.
 *
 * <p>All work is on a private daemon thread; the client thread only ever reads the {@code volatile}
 * state field.
 */
public final class ForgeFlipFeed {

    /**
     * The "nothing published yet" state.
     *
     * <p><b>Declared before {@link #INSTANCE} and it must stay that way.</b> Static fields initialize
     * in textual order and the constructor's {@code state = EMPTY} runs while {@code INSTANCE} is
     * being built, so with this below it every instance is born holding {@code null} and the first
     * render that touches it throws. The bazaar feed was bitten by exactly this.
     */
    private static final State EMPTY = new State(Mode.NONE, null, null, null, 0L, 0L);

    private static final ForgeFlipFeed INSTANCE = new ForgeFlipFeed();

    /** How long a published state is served before a UI asking for data triggers a new attempt. */
    private static final long REFRESH_MS = 60_000L;

    /** Retry sooner than a full cycle after a failure, so a brief outage is not pinned for a minute. */
    private static final long RETRY_MS = 15_000L;

    /**
     * Staleness the local pass tolerates from the shared Bazaar store. This reads
     * {@link BazaarSnapshot} exactly like every other consumer and adds no endpoint of its own; when
     * another consumer has pulled recently it costs no network at all.
     */
    private static final long LOCAL_SNAPSHOT_MAX_AGE_MS = 60_000L;

    /** Which ranking the UI currently holds. */
    public enum Mode {
        /** The licence-backed server ranking. */
        REMOTE,
        /** Computed on this client from the bazaar snapshot, with no history. */
        LOCAL,
        /** Neither could be produced. */
        NONE
    }

    /**
     * An immutable published result. Swapped in as one object so a render pass can never see a mode
     * from one attempt beside the data of another.
     *
     * @param mode        which ranking {@code remote} / {@code local} holds
     * @param remote      the server ranking, when {@code mode} is {@link Mode#REMOTE}
     * @param local       the local ranking, when {@code mode} is {@link Mode#LOCAL}
     * @param failure     why the server ranking was unavailable; {@code null} on the remote path
     * @param dataTsMs    when the underlying market data was generated (not when we rendered it)
     * @param publishedAt when this state was produced
     */
    public record State(Mode mode, ForgeApi.Response remote, LocalForgeEngine.Result local,
                        ApiFailure failure, long dataTsMs, long publishedAt) {

        /** True while the window is showing locally computed numbers. */
        public boolean isLocal() {
            return mode == Mode.LOCAL;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-ForgeFeed");
        thread.setDaemon(true);
        return thread;
    });

    private volatile State state = EMPTY;
    private volatile boolean loading;
    private volatile long lastAttemptMs;

    /** Budget the published state was produced for, so changing it forces a real refetch. */
    private volatile long stateBudget = -1;

    private ForgeFlipFeed() {
    }

    public static ForgeFlipFeed getInstance() {
        return INSTANCE;
    }

    /** The current ranking; never {@code null}, {@link Mode#NONE} before the first attempt lands. */
    public State state() {
        return state;
    }

    public boolean isLoading() {
        return loading;
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    /**
     * Asks for fresh data, honouring the refresh window unless {@code force}d.
     *
     * <p>Safe to call every frame: the window check and the in-flight guard make all but one call per
     * cycle a pair of field reads.
     */
    public void request(boolean force) {
        if (loading) {
            return;
        }
        long now = System.currentTimeMillis();
        long budget = settings().budget;
        boolean budgetChanged = stateBudget != budget;
        long window = state.mode() == Mode.NONE || state.failure() != null ? RETRY_MS : REFRESH_MS;
        if (!force && !budgetChanged && now - lastAttemptMs < window) {
            return;
        }
        loading = true;
        lastAttemptMs = now;
        // The player's own choice comes first, and picking Local means the server is not called at
        // all - not called and then ignored. A null failure is what tells the UI apart afterwards:
        // nothing went wrong here, so the notice points at the switch instead of at a token.
        if (RankingSource.useLocal(settings().flipSource, "Forge")) {
            executor.execute(() -> computeLocal(null, budget));
            return;
        }
        ForgeApi.getInstance().fetch(budget, (result, failure, detail) -> {
            if (result != null) {
                publish(new State(Mode.REMOTE, result, null, null,
                        result.data_ts * 1000L, System.currentTimeMillis()), budget);
                loading = false;
                return;
            }
            if (detail != null) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Forge] Falling back to local ranking ({}): {}",
                        failure, detail);
            }
            // Off the API's callback thread and onto ours: the local pass walks every recipe against
            // the book and has no business holding up the next API request.
            executor.execute(() -> computeLocal(failure, budget));
        });
    }

    /**
     * Recomputes the local ranking against the snapshot already held, with no network at all.
     *
     * <p>For the threshold settings: changing the minimum profit or the liquidity floor has to change
     * the list, and routing that through {@link #request} would spend a backend round trip to answer
     * a question that is entirely local. A no-op while the remote ranking is showing, since none of
     * those settings feed it.
     */
    public void recomputeLocal() {
        if (!state.isLocal()) {
            return;
        }
        ApiFailure failure = state.failure();
        long budget = settings().budget;
        executor.execute(() -> computeLocal(failure, budget));
    }

    /**
     * The fallback pass. Reads the shared Bazaar store the same way the price cache and the order
     * sync do — one snapshot, many consumers, no new endpoint — and the forge recipes out of the
     * repo cache the Recipe Viewer already maintains.
     */
    private void computeLocal(ApiFailure failure, long budget) {
        try {
            SBSConfig.ForgeSettings settings = settings();
            // The fallback toggle gates the FALLBACK, not the choice. A null failure means the
            // player picked Local on the switch, and honouring "don't fall back" there would blank
            // the window they just asked to see.
            if (failure != null && !settings.localFallback) {
                publish(new State(Mode.NONE, null, null, failure, 0L, System.currentTimeMillis()), budget);
                return;
            }
            BazaarApiClient.Response snapshot = BazaarSnapshot.getInstance().get(LOCAL_SNAPSHOT_MAX_AGE_MS);
            if (snapshot == null) {
                // No server ranking AND no bazaar data: there is nothing to compute from, and saying
                // so beats an empty list that looks like "nothing is worth forging".
                publish(new State(Mode.NONE, null, null, failure, 0L, System.currentTimeMillis()), budget);
                return;
            }
            LocalForgeEngine.Result result = LocalForgeEngine.compute(snapshot,
                    SkyBlockRepoRecipeProvider.getInstance().forgeRecipes(), settings,
                    ConfigManager.getInstance().get().bazaar);
            publish(new State(Mode.LOCAL, null, result, failure,
                    result.dataTsMs(), System.currentTimeMillis()), budget);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Forge] Local ranking failed", t);
            publish(new State(Mode.NONE, null, null, failure, 0L, System.currentTimeMillis()), budget);
        } finally {
            loading = false;
        }
    }

    private void publish(State next, long budget) {
        this.state = next;
        this.stateBudget = budget;
    }

    private static SBSConfig.ForgeSettings settings() {
        return ConfigManager.getInstance().get().forge;
    }

    // ------------------------------------------------------------------
    // The disclaimer
    // ------------------------------------------------------------------

    /** Headline of the permanent local-results notice. Always drawn, never dismissable. */
    public static String disclaimerTitle() {
        return "§eLocal estimate · no price history";
    }

    /**
     * The body of that notice, as one paragraph for the caller to wrap to its own width.
     *
     * <p>It names what is actually missing rather than saying "less accurate". Without history this
     * cannot tell whether an ingredient is priced unusually <i>for that ingredient</i>, whether the
     * result's price is trending, or whether the book will still be there when an eight-hour run
     * finishes — and that last one bites harder here than it does on the bazaar, because a forge run
     * commits you for its whole duration. It also does not model the time to buy the ingredients.
     *
     * <p>It states <b>which</b> failure put the reader here, because "your token expired" and "we
     * could not reach the server" call for completely different actions.
     */
    public static String disclaimerBody(ApiFailure failure) {
        String cause = failure == null ? "The server ranking is unavailable." : failure.sentence();
        return cause + " These forge flips were worked out on your own machine from the current "
                + "bazaar snapshot alone. Without price history this cannot tell whether an "
                + "ingredient is unusually priced right now, whether the result is trending up or "
                + "down, or whether the prices you see will still be there when a long run finishes "
                + "- and it does not model how long the ingredient orders take to fill. Every figure "
                + "below is a projection from one snapshot, not a promise.";
    }
}
