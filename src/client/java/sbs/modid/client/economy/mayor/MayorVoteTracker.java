/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.mayor;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.tab.TabWidgets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers whether you have voted in the current mayor election, and nags you if you have not.
 *
 * <p>The whole feature turns on one question - <b>"is this still the same election?"</b> - and it is
 * answered two ways, because neither alone is enough:
 * <ul>
 *   <li><b>The SkyBlock year</b>, read off the booth's own tooltips ("Year 507 Candidate"). Exact,
 *       but only knowable once you have opened the booth at least once.</li>
 *   <li><b>The tab list's Election row appearing again</b> after having been gone. That needs no
 *       menu visit at all, so a player who never opens the booth still gets a correct reminder the
 *       following election instead of one stale "you already voted" forever.</li>
 * </ul>
 * Whichever notices first wins; the year overrides the transition when both are known.
 *
 * <p><b>Two ways of noticing the vote itself.</b> The click on a candidate is the reliable one - it
 * is caught in {@code MayorVoteMixin} on the same container funnel every menu interaction passes
 * through, so it works through the SBS menu overlays too. Chat confirmation is a second chance for
 * votes cast some other way. Neither is trusted blindly: the booth's own lore is re-read every time
 * the menu is open, so an already-cast vote is recognised even if this client never saw it happen.
 *
 * <p>Nothing here ever votes, opens the booth or clicks a slot. It watches and it reminds.
 */
public final class MayorVoteTracker implements ProfileScopedStore {

    private static final MayorVoteTracker INSTANCE = new MayorVoteTracker();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** "Year 507 Candidate" in a candidate's lore - the only exact election id the client can see. */
    private static final Pattern YEAR = Pattern.compile("Year\\s+([0-9]{1,5})\\s+Candidate",
            Pattern.CASE_INSENSITIVE);
    /** The un-voted candidate's call to action: "Click to vote for Aatrox!". */
    private static final Pattern VOTE_PROMPT = Pattern.compile("Click to vote for\\s+(.+?)!",
            Pattern.CASE_INSENSITIVE);
    /**
     * Hypixel's own confirmation, <b>verified from the game</b>:
     * {@code You cast 75 votes for Diana in the Year 507 Elections!}
     *
     * <p>The count is your Statesperson Fame Rank, not one vote, so it is any number and is ignored.
     * What matters is that this line carries the <b>year</b> as well as the candidate - which makes
     * chat a complete source on its own, able to identify the election without the booth ever being
     * opened.
     */
    private static final Pattern CHAT_VOTED = Pattern.compile(
            "You cast\\s+[0-9,]+\\s+votes?\\s+for\\s+([A-Za-z' ]{2,24}?)"
                    + "\\s+in the Year\\s+([0-9]{1,5})\\s+Elections?", Pattern.CASE_INSENSITIVE);
    /** The tab row that says an election is running: "Election: 7h" / "Election: 42m". */
    private static final Pattern ELECTION_TIME = Pattern.compile("^([0-9]+[dhms])(\\s*[0-9]+[dhms])*$",
            Pattern.CASE_INSENSITIVE);
    /** One unit of that duration, for turning "1d 4h" into milliseconds. */
    private static final Pattern DURATION_PART = Pattern.compile("([0-9]+)\\s*([dhms])",
            Pattern.CASE_INSENSITIVE);

    /** How long a tab reading stays trusted - the tab is scanned far more often than this. */
    private static final long TAB_TTL_MS = 5_000L;
    /** Never nag twice inside this, whatever the repeat setting says. */
    private static final long MIN_REMIND_GAP_MS = 60_000L;
    /** Grace after the election opens before the first nag, so it does not fire mid-warp. */
    private static final long FIRST_REMIND_DELAY_MS = 20_000L;

    /**
     * How far two readings of "when does this election end" may sit apart and still be the same
     * election.
     *
     * <p>Generous on purpose. The tab rounds ("3d"), so within one election the computed deadline
     * wanders by up to a day as the display ticks over - while consecutive elections are a whole
     * SkyBlock year apart, which is days. Anything inside this window is drift, anything outside it
     * is a different election.
     */
    private static final long SAME_ELECTION_MS = 2L * 24 * 3600_000L;
    /** Deadline drift smaller than this is not worth a disk write. */
    private static final long DEADLINE_WRITE_MS = 60_000L;

    private volatile boolean electionOpen;
    private volatile String timeLeft = "";
    private volatile long tabSeenAt;

    /** The election we believe we are in, and whether this profile has voted in it. */
    private volatile int year;
    private volatile boolean voted;
    private volatile String votedFor = "";
    /**
     * When the running election ends, as wall-clock ms - <b>the election's identity across
     * restarts</b>, and the reason no "have we seen it closed yet?" flag is needed. Comparing two
     * deadlines answers "same election?" from a single reading, whereas a closed-to-open transition
     * cannot tell a new election from a client that has only just started looking. That confusion is
     * exactly what made every login wipe the saved vote.
     */
    private volatile long endAt;

    private long openedAt;
    private long lastRemindAt;
    private long lastLoreLogAt;
    private boolean loaded;

    private MayorVoteTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static MayorVoteTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.MayorVoteSettings cfg() {
        return ConfigManager.getInstance().get().mayorVote;
    }

    // ------------------------------------------------------------------
    // What the rest of the mod asks
    // ------------------------------------------------------------------

    /** Whether an election is running right now, as far as the tab list has told us recently. */
    public boolean electionOpen() {
        return electionOpen && System.currentTimeMillis() - tabSeenAt < TAB_TTL_MS;
    }

    /** Whether this profile has voted in the election currently running. */
    public boolean hasVoted() {
        return voted;
    }

    /** Who the vote went to, or empty when that was never seen (a chat-less vote, say). */
    public String votedFor() {
        return votedFor;
    }

    /** The election's remaining time exactly as the tab words it ("7h"), or empty. */
    public String timeLeft() {
        return timeLeft;
    }

    /** The SkyBlock year of the running election, or {@code 0} while the booth has never been open. */
    public int year() {
        return year;
    }

    // ------------------------------------------------------------------
    // Tab list: is an election running, and how long is left
    // ------------------------------------------------------------------

    /** Called on the shared client tick; cheap enough to run at the caller's throttle. */
    public void tick(Minecraft minecraft) {
        try {
            if (minecraft == null || minecraft.player == null || !cfg().enabled) {
                return;
            }
            ensureLoaded();
            readTab();
            scanBooth(minecraft);
            remindIfDue();
        } catch (Throwable ignored) {
            // a reminder is never worth breaking the tick over
        }
    }

    /**
     * Reads the tab list's Election block.
     *
     * <p>The row is a header with a time under it ("Election:" / "7h"), the same shape every other
     * Hypixel widget uses, so the value is the next line rather than the rest of the header. A row
     * whose value is not a duration ("Election: Over") counts as closed - an election that has
     * finished is exactly when a reminder would be most annoying and least useful.
     */
    private void readTab() {
        List<String> lines = TabWidgets.lines();
        boolean open = false;
        String left = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("election")) {
                continue;
            }
            String value = valueOf(line);
            if (value.isEmpty() && i + 1 < lines.size()) {
                value = lines.get(i + 1).trim();
            }
            if (ELECTION_TIME.matcher(value.replace(" ", "")).matches()) {
                open = true;
                left = value;
            }
            break;
        }
        long now = System.currentTimeMillis();
        tabSeenAt = now;
        timeLeft = left;
        if (open && !electionOpen) {
            // Only delays the first nag. Emphatically NOT treated as a new election: on every login
            // this fires simply because nothing had been read yet.
            openedAt = now;
        }
        electionOpen = open;
        if (open) {
            long remaining = parseDuration(left);
            if (remaining > 0) {
                applyDeadline(now + remaining);
            }
        }
    }

    /**
     * A freshly computed election deadline, compared against the remembered one.
     *
     * <p>This is where "is this still the same election?" is actually decided, and it works from one
     * reading - no history, no session flags - so it is right on the first tick after a login and
     * right after being offline across an entire election.
     */
    private void applyDeadline(long seen) {
        if (endAt == 0) {
            endAt = seen;     // first reading ever: adopt it, and keep whatever vote was loaded
            save();
            return;
        }
        long drift = Math.abs(seen - endAt);
        if (drift <= SAME_ELECTION_MS) {
            if (drift > DEADLINE_WRITE_MS) {
                endAt = seen; // follow the rounding, so it never accumulates past the window
                save();
            }
            return;
        }
        onNewElection(0, seen);
    }

    /**
     * Parses the tab's remaining time ("3d", "10h", "1d 4h", "42m") into milliseconds, or {@code 0}
     * when it says nothing usable.
     */
    private static long parseDuration(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher matcher = DURATION_PART.matcher(text);
        long total = 0;
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            total += switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
                case 'd' -> value * 24 * 3600_000L;
                case 'h' -> value * 3600_000L;
                case 'm' -> value * 60_000L;
                default -> value * 1000L;
            };
        }
        return total;
    }

    /** The part after the first colon, trimmed - "Election: 7h" -> "7h". */
    private static String valueOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    // ------------------------------------------------------------------
    // The booth: the year, and whether the vote is already cast
    // ------------------------------------------------------------------

    /**
     * Walks the open election booth, which is what makes the state survive everything this client
     * did not witness: a vote cast last session, on another device, or before the mod was installed.
     * The year comes from here too.
     *
     * <p>Only the menu's own upper slots - the bottom 36 are the player inventory, and a candidate
     * head is never in there.
     */
    private void scanBooth(Minecraft minecraft) {
        var screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container)) {
            return;
        }
        String title = strip(screen.getTitle() == null ? "" : screen.getTitle().getString())
                .toLowerCase(Locale.ROOT);
        if (!title.contains("election") && !title.contains("mayor")) {
            return;
        }
        var menu = container.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        ItemStack unrecognised = null;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean before = voted;
            readBoothSlot(stack, false);
            if (!before && !voted && unrecognised == null && looksLikeCandidate(stack)) {
                unrecognised = stack;
            }
        }
        // Every candidate read and none of them claimed our vote. That is the normal "not voted yet"
        // case, so it is NOT an error - but it is also what an unrecognised "already voted" wording
        // would look like, so one slot gets logged for tuning.
        if (unrecognised != null && !voted) {
            logBoothLore(unrecognised);
        }
    }

    /** A stack carrying the "Year N Candidate" line, whatever else its lore says. */
    private static boolean looksLikeCandidate(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            if (YEAR.matcher(strip(line.getString())).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one election-booth slot. Called for every slot of the open menu and for the slot that was
     * just clicked, so it doubles as the "already voted" detector and the vote capture.
     *
     * @param clicked true when this stack is the one the player just clicked
     */
    public void readBoothSlot(ItemStack stack, boolean clicked) {
        if (stack == null || stack.isEmpty() || !cfg().enabled) {
            return;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return;
        }
        String name = strip(stack.getHoverName().getString()).trim();
        boolean isCandidate = false;
        boolean alreadyVotedHere = false;
        for (Component line : lore.lines()) {
            String text = strip(line.getString()).trim();
            Matcher yearMatch = YEAR.matcher(text);
            if (yearMatch.find()) {
                isCandidate = true;
                applyYear(Integer.parseInt(yearMatch.group(1)));
            }
            if (VOTE_PROMPT.matcher(text).find()) {
                isCandidate = true;   // this candidate is NOT the one currently voted for
            }
            String lower = text.toLowerCase(Locale.ROOT);
            // The lore of the candidate you are already backing. Hypixel's exact wording here is
            // not pinned down, so several shapes are accepted rather than one guess.
            if (lower.contains("currently voting") || lower.contains("you voted for this")
                    || lower.contains("your vote is with") || lower.contains("click to unvote")) {
                isCandidate = true;
                alreadyVotedHere = true;
            }
        }
        if (!isCandidate) {
            return;
        }
        if (alreadyVotedHere) {
            recordVote(name, "booth lore");
            return;
        }
        if (clicked) {
            // The click is what casts the vote. Recorded optimistically: the menu closes on a vote,
            // so waiting for a re-scan would mean never seeing it.
            recordVote(name, "click");
        }
    }

    /**
     * Dumps a booth slot's lore once every 30s when it looked like a candidate but said nothing we
     * recognised. The "already voted" wording is the one string here that is a guess, and this is
     * what makes it tunable from the instance log instead of by redeploying blind.
     */
    public void logBoothLore(ItemStack stack) {
        long now = System.currentTimeMillis();
        if (now - lastLoreLogAt < 30_000L) {
            return;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return;
        }
        lastLoreLogAt = now;
        StringBuilder text = new StringBuilder();
        for (Component line : lore.lines()) {
            text.append(" | ").append(strip(line.getString()).trim());
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Vote] booth slot '{}':{}",
                strip(stack.getHoverName().getString()), text);
    }

    /**
     * A year seen at the booth or in the vote confirmation. Differing from a year we already knew is
     * proof of a new election - but <b>learning one for the first time is not</b>: year {@code 0}
     * means "never established", not "a different election", and resetting on it threw away a vote
     * every time the booth was opened.
     */
    private void applyYear(int seen) {
        if (seen <= 0 || seen == year) {
            return;
        }
        if (year == 0) {
            year = seen;
            save();
            return;
        }
        onNewElection(seen, endAt);
    }

    /** Chat: a vote confirmation, for votes this client did not see clicked. */
    public void parseChat(String message) {
        if (message == null || !cfg().enabled) {
            return;
        }
        String text = strip(message);
        Matcher matcher = CHAT_VOTED.matcher(text);
        if (!matcher.find()) {
            return;
        }
        // Hypixel's confirmation stands on its own line with nothing in front of it. Anything with a
        // sender separator before the match is somebody else's message being relayed to us - party
        // chat, a guild message, a DM - and acting on that would mark YOU as having voted because a
        // friend did. A false positive here silences the reminder for the whole election, which is
        // the one failure that costs the player the feature.
        String before = text.substring(0, matcher.start());
        if (before.indexOf(':') >= 0 || before.indexOf('>') >= 0) {
            return;
        }
        // Year first: it is what identifies the election, and a year we have not seen before resets
        // the vote. Recording the vote before applying it would have the reset wipe what we just
        // learned - and the first vote of a new election is exactly when both arrive together.
        applyYear(Integer.parseInt(matcher.group(2)));
        recordVote(matcher.group(1).trim(), "chat");
    }

    // ------------------------------------------------------------------
    // State changes
    // ------------------------------------------------------------------

    /** A new election: whatever we knew about the last one no longer applies. */
    private void onNewElection(int newYear, long newEndAt) {
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Vote] new election (year {} -> {}); the saved vote no longer applies.",
                year, newYear);
        year = newYear;
        endAt = newEndAt;
        voted = false;
        votedFor = "";
        lastRemindAt = 0;
        openedAt = System.currentTimeMillis();
        save();
    }

    private void recordVote(String candidate, String source) {
        String clean = sanitize(candidate);
        if (voted && clean.equalsIgnoreCase(votedFor)) {
            return;   // nothing changed; do not touch the disk on every menu frame
        }
        voted = true;
        votedFor = clean;
        save();
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Vote] recorded a vote for '{}' (year {}, via {}).", clean, year, source);
    }

    /**
     * The nag itself: only while an election is actually running, only when this profile has not
     * voted, and never more often than the player asked for.
     */
    private void remindIfDue() {
        SBSConfig.MayorVoteSettings cfg = cfg();
        if (!cfg.remind || !electionOpen() || voted) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - openedAt < FIRST_REMIND_DELAY_MS) {
            return;   // just arrived / just opened - let the world settle first
        }
        long gap = Math.max(MIN_REMIND_GAP_MS, cfg.repeatMinutes * 60_000L);
        if (lastRemindAt != 0 && now - lastRemindAt < gap) {
            return;
        }
        lastRemindAt = now;
        String detail = timeLeft.isEmpty()
                ? "You have not voted in this election yet."
                : "You have not voted in this election yet - " + timeLeft + " left.";
        // The chat line is the feature the player asked for, so it is always in the mask; the extra
        // channels are whatever they added on top.
        Alerts.send(Alerts.Alert.of("Mayor Election", detail),
                cfg.extraChannels | AlertChannel.CHAT.bit());
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }

    /** Candidate names end up in chat and on the sidebar, so they are whitelisted, never trusted. */
    private static String sanitize(String raw) {
        String clean = strip(raw).replaceAll("[^A-Za-z0-9' ]", "").trim();
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    // ------------------------------------------------------------------
    // Per-profile persistence
    // ------------------------------------------------------------------

    private static final String CACHE_FILE = "mayor_vote.json";

    /** What gets written. One election's worth of state - there is no history worth keeping. */
    private static final class Cached {
        int year;
        boolean voted;
        String votedFor = "";
        /** The election's deadline - what identifies it when the year was never seen. */
        long endAt;
    }

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
            if (cached == null) {
                return;
            }
            year = cached.year;
            voted = cached.voted;
            votedFor = cached.votedFor == null ? "" : cached.votedFor;
            endAt = cached.endAt;
        } catch (Throwable t) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Vote] could not read the saved vote", t);
        }
    }

    private void save() {
        try {
            Cached cached = new Cached();
            cached.year = year;
            cached.voted = voted;
            cached.votedFor = votedFor;
            cached.endAt = endAt;
            Path path = ProfileContext.getInstance().file(CACHE_FILE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, new com.google.gson.Gson().toJson(cached), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Vote] could not save the vote", t);
        }
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    @Override
    public void reloadProfile() {
        loaded = false;
        year = 0;
        voted = false;
        votedFor = "";
        endAt = 0;
        lastRemindAt = 0;
        ensureLoaded();
    }
}
