/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;

/**
 * Oruo's Quiz: three SkyBlock questions, three options each, and one wrong click fails the whole
 * room. Display only - this never clicks, aims or sends anything.
 *
 * <p><b>Reading the question.</b> {@link QuizFrame} reads the confirmed frame - {@code Question #N},
 * the (possibly wrapped) question, three option lines - and {@link QuizAnswers} picks the option.
 *
 * <p><b>Showing the answer, two ways:</b>
 * <ul>
 *   <li>an SBS line of its own in chat naming the option, with the row's certainty. Hypixel's lines
 *       are never edited or suppressed;</li>
 *   <li>a box on the <b>button</b> under the floating label carrying that option's text, when
 *       exactly one label nearby matches. The room database records no button positions, so the
 *       label finds the button; the wrong options are never drawn.</li>
 * </ul>
 *
 * <p><b>Silent when unsure:</b> a question the table does not know (logged in full under
 * {@code [SBS][Quiz] unknown question:} so the table can grow), a rule that does not pick exactly one
 * option, a row below WIKI certainty, and two labels matching the answer all show nothing.
 */
public final class QuizSolver implements PuzzleStrategy {

    /** How far a matching label may be before it is assumed to belong to another room. */
    private static final double MAX_RANGE = 24.0;

    /** The nametag sweep is throttled; the labels do not move. */
    private static final long SCAN_MS = 250;

    /** How far sideways from the answer label its button may sit. */
    private static final int BUTTON_REACH = 3;

    private static final int ANSWER_TEXT = 0x55FF55;
    private static final int MUTED_TEXT = 0xAAAAAA;

    private final QuizFrame frame = new QuizFrame();
    private final QuizTimer timer = new QuizTimer();

    private String answerKey;
    /** The answer as shown in chat, for the waypoint's label. */
    private String answerText;
    private AABB target;
    /** The last answer button found; the countdown label hangs there, the buttons stand together. */
    private AABB lastButton;

    private long lastScan;
    private String lastUnknownLogged = "";

    @Override
    public PuzzleType type() {
        return PuzzleType.QUIZ;
    }

    @Override
    public void onChat(String line) {
        QuizTimer.Event event = timer.offer(line, System.currentTimeMillis());
        if (event == QuizTimer.Event.ANSWERED || event == QuizTimer.Event.FINISHED) {
            // Answered right: the waypoint goes at once, the countdown to the next question starts.
            answerKey = null;
            answerText = null;
            target = null;
            return;
        }
        QuizFrame.Question asked = frame.offer(line);
        if (asked == null) {
            return;
        }
        timer.questionShown();
        answerKey = null;
        answerText = null;
        target = null;
        QuizResolver.Resolution answer = QuizAnswers.resolve(asked.text(), asked.options());
        if (answer == null) {
            logUnresolved(asked);
            return;
        }
        if (!answer.shown()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Quiz] '{}' resolves to '{}' but the row is {} - not shown",
                    asked.text(), answer.answer(), answer.certainty().displayName());
            return;
        }
        answerKey = QuizResolver.key(answer.answer());
        answerText = answer.answer();
        announce(asked, answer);
    }

    /** SBS's own chat line naming the option; certainty spelled out when it is not CONFIRMED. */
    private static void announce(QuizFrame.Question asked, QuizResolver.Resolution answer) {
        Component body = Component.literal(" Quiz #" + asked.number() + ": ").withColor(SBSChat.WHITE)
                .append(Component.literal("✔ " + answer.answer()).withColor(ANSWER_TEXT))
                .append(Component.literal(answer.certainty() == Certainty.CONFIRMED ? ""
                        : "  (" + answer.certainty().displayName() + ")").withColor(MUTED_TEXT));
        SBSChat.send("Quiz", body);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (answerKey == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_MS) {
            return;
        }
        lastScan = now;

        AABB found = null;
        int matches = 0;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.distanceToSqr(minecraft.player.position()) > MAX_RANGE * MAX_RANGE) {
                continue;
            }
            Component name = entity.getCustomName();
            if (name == null) {
                continue;
            }
            if (QuizResolver.key(FarmingText.strip(name.getString())).contains(answerKey)) {
                matches++;
                found = entity.getBoundingBox();
            }
        }
        // Two labels carrying the same answer text means the read is ambiguous, and an ambiguous
        // read is worth exactly as much as no read at all here.
        AABB button = matches == 1 ? buttonUnder(minecraft.level, found) : null;
        if (matches == 1 && !java.util.Objects.equals(button, target)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Quiz] answer label at {} -> {}",
                    BlockPos.containing(found.getCenter()).toShortString(),
                    button == null ? "no button nearby, nothing drawn" : "button at "
                            + BlockPos.containing(button.getCenter()).toShortString());
        }
        target = button;
        if (button != null) {
            lastButton = button;
        }
    }

    /**
     * The one button the answer label stands over: the nearest button block within
     * {@link #BUTTON_REACH} sideways and a few blocks below to one above the label's foot. The
     * maintainer asked for the button alone (2026-10-16); boxing the whole floating label drew a
     * two-block box. No button there - nothing drawn, never the label as a fallback.
     */
    private static AABB buttonUnder(net.minecraft.client.multiplayer.ClientLevel level, AABB label) {
        BlockPos foot = BlockPos.containing(label.getCenter().x, label.minY, label.getCenter().z);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -BUTTON_REACH; dx <= BUTTON_REACH; dx++) {
                for (int dz = -BUTTON_REACH; dz <= BUTTON_REACH; dz++) {
                    cursor.set(foot.getX() + dx, foot.getY() + dy, foot.getZ() + dz);
                    if (!(level.getBlockState(cursor).getBlock() instanceof ButtonBlock)) {
                        continue;
                    }
                    double d = cursor.distSqr(foot);
                    if (d < bestDist) {
                        bestDist = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best == null ? null : new AABB(best);
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        List<PuzzleHighlight> out = new ArrayList<>(1);
        if (target != null) {
            // The waypoint: the button, and its label drawn on screen so it reads through walls.
            out.add(PuzzleHighlight.answer(target.inflate(0.02), PuzzleSettings.argb(PuzzleType.QUIZ),
                    answerText == null ? null : "§a✔ " + answerText));
            return out;
        }
        long remaining = timer.remainingMs(System.currentTimeMillis());
        AABB anchor = countdownAnchor();
        if (remaining > 0 && anchor != null) {
            String seconds = String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000.0);
            out.add(PuzzleHighlight.context(anchor, 0x00FFFFFF, "§eNext question in " + seconds + "s"));
        }
        return out;
    }

    /**
     * Where the countdown label hangs: over the last answer button (the three stand together), or
     * before the first question the room's centre at eye height. A zero-size box, so only the label
     * shows.
     */
    private AABB countdownAnchor() {
        net.minecraft.world.phys.Vec3 at;
        if (lastButton != null) {
            at = lastButton.getCenter().add(0, 1.5, 0);
        } else {
            sbs.modid.client.dungeons.run.logic.DungeonRoomBorders.Borders borders =
                    sbs.modid.client.dungeons.run.logic.DungeonRoomTracker.getInstance().borders();
            Minecraft mc = Minecraft.getInstance();
            if (borders == null || mc.player == null) {
                return null;
            }
            at = new net.minecraft.world.phys.Vec3((borders.min().getX() + borders.max().getX() + 1) / 2.0,
                    mc.player.getEyeY(), (borders.min().getZ() + borders.max().getZ() + 1) / 2.0);
        }
        return new AABB(at, at);
    }

    @Override
    public void reset() {
        frame.clear();
        timer.clear();
        answerKey = null;
        answerText = null;
        target = null;
        lastButton = null;
        lastScan = 0;
        lastUnknownLogged = "";
    }

    /**
     * Logs a question that produced no answer, once per distinct question, at {@code info} and in
     * full: this log is how the table grows, so a truncated or debug-level line would lose the one
     * artefact a real run produces.
     */
    private void logUnresolved(QuizFrame.Question asked) {
        if (asked.text().equals(lastUnknownLogged)) {
            return;
        }
        lastUnknownLogged = asked.text();
        if (QuizAnswers.knows(asked.text())) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Quiz] no single option matches the answer table for '{}': {}",
                    asked.text(), asked.options());
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Quiz] unknown question: {} options={}",
                    asked.text(), asked.options());
        }
    }
}
