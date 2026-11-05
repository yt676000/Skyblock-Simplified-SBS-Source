/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.kuudra.logic.KuudraTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.AutoRequeueSettings;
import sbs.modid.client.core.item.ItemAnalysis;
import sbs.modid.client.core.tick.Island;
import sbs.modid.client.core.tick.TickContext;
import sbs.modid.client.core.tick.TickContexts;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.requeue.model.InstanceIds;
import sbs.modid.client.dungeons.requeue.model.RequeueTarget;
import sbs.modid.client.dungeons.requeue.model.RunEnd;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.social.chat.command.PartyChatCommands;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Auto Requeue in the game: feeds {@link RunEndDetector} from chat and {@link RequeueController}
 * from the tick, sends the one {@code /joininstance} the controller asks for, and prepares the HUD
 * card's lines. The decisions themselves are all in those two pure classes.
 *
 * <p>What this class measures for the controller:
 * <ul>
 *   <li><b>Outside</b> - on a known SkyBlock island that is neither the Catacombs nor Kuudra's
 *       Hollow, from the tick-computed context (never the sidebar).</li>
 *   <li><b>Input</b> during the countdown - moving away from where it started, any screen (menu,
 *       chat: typing), attack / use held, or any key press reaching {@link #onKeyPressed}. Looking
 *       around does not count; the camera drifts on a world load.</li>
 *   <li><b>Idle</b> - the last time any of that, or a camera turn, happened before the run ended.</li>
 * </ul>
 */
public final class AutoRequeue {

    private static final AutoRequeue INSTANCE = new AutoRequeue();

    /** An abort reason stays on the card this long. */
    private static final long ABORT_SHOW_MS = 8_000L;
    /** Moving further than this (squared, blocks) from where the countdown started cancels it. */
    private static final double MOVE_CANCEL_SQ = 0.25;

    private final RunEndDetector detector = new RunEndDetector();
    private final RequeueController controller = new RequeueController();

    private int lastEpoch = Integer.MIN_VALUE;
    private long outsideSince;
    private long lastInputAt = System.currentTimeMillis();
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private double anchorX, anchorY, anchorZ;
    private boolean keyPressed;
    private int toldAbortSeq;
    private boolean wasEnabled;
    /** The command behind the server's "Click HERE to re-queue" for the current run, without '/'. */
    private String offeredCommand;

    /** The card's lines, prepared on the tick; empty when there is nothing to show. */
    private volatile List<String> hudLines = List.of();

    private AutoRequeue() {
    }

    public static AutoRequeue getInstance() {
        return INSTANCE;
    }

    private static AutoRequeueSettings cfg() {
        return ConfigManager.getInstance().get().autoRequeue;
    }

    private static boolean enabled(RequeueTarget.Category category) {
        AutoRequeueSettings cfg = cfg();
        return category == RequeueTarget.Category.KUUDRA ? cfg.kuudra : cfg.dungeons;
    }

    private static boolean anyEnabled() {
        return cfg().dungeons || cfg().kuudra;
    }

    // ------------------------------------------------------------------ chat

    /** Every candidate chat line, colour already stripped; {@code message} for its click event. */
    public void onChat(String text, Component message) {
        if (!anyEnabled() || text == null) {
            return;
        }
        // CONFIRMED (maintainer's log 2026-10-18): after the summary the server prints "Click HERE to
        // re-queue into The Catacombs!", and the leader clicking it yields "You have been re-queued!"
        // - from inside the finished dungeon, nobody leaves. The click's own command is what we send.
        if (text.contains("to re-queue into")) {
            String command = requeueCommand(message);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Requeue] offered: command='{}' state={}", command,
                    controller.state());
            if (command != null) {
                offeredCommand = command;
                controller.onRequeueOffered();
            }
            return;
        }
        if (text.strip().equals("You have been re-queued!")) {
            controller.onRequeued();
            return;
        }
        DungeonStateManager dungeon = DungeonStateManager.getInstance();
        KuudraTracker kuudra = KuudraTracker.getInstance();
        RunEnd end = detector.accept(text, new RunEndDetector.Snapshot(
                dungeon.phase() == DungeonEvents.Phase.BOSS, dungeon.floorType(), dungeon.floorNumber(),
                kuudra.inHollow(), kuudra.tier()));
        if (end == null) {
            if (detector.rejected() != null) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Requeue] summary ignored ({}): '{}'",
                        detector.rejected(), text.strip());
            }
            return;
        }
        long now = System.currentTimeMillis();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Requeue] end: {} {} {} line='{}'", end.kind(),
                end.category(), end.target(), end.line());
        controller.onRunEnd(end, enabled(end.category()), cfg().afterLoss,
                PartyTracker.getInstance().members(), now - lastInputAt, now);
    }

    /**
     * Whether the run-end downtime reminder should keep its entries: with Auto Requeue on for the
     * category, a pending {@code !dt} blocks requeues until {@code !undt}, a cancel or a disband.
     */
    public boolean keepsDowntime(boolean kuudraLine) {
        return enabled(kuudraLine ? RequeueTarget.Category.KUUDRA : RequeueTarget.Category.DUNGEONS);
    }

    // ------------------------------------------------------------------ commands

    /** {@code /sbs requeue cancel} and the cancel key. Clears pending downtime too. */
    public void cancel(boolean announce) {
        boolean was = controller.cancel(System.currentTimeMillis());
        PartyChatCommands.getInstance().clearDowntime();
        toldAbortSeq = controller.abortSeq();
        if (announce) {
            SBSChat.send("Auto Requeue", Component.literal(was ? " Requeue cancelled, counter reset."
                    : " Nothing to cancel - counter reset."));
        }
    }

    /**
     * {@code !up} / {@code !down} / {@code !same} and {@code /sbs requeue up|down|same}. Returns the
     * reply, or {@code null} when this client is not the one that would send (not leader, or off).
     */
    public String step(int delta) {
        if (!anyEnabled() || !leader()) {
            return null;
        }
        return controller.requestStep(delta, liveTarget());
    }

    /** A key press with no screen open (from {@code KeybindDispatch}). */
    public void onKeyPressed(int code) {
        int cancelKey = cfg().cancelKey;
        if (cancelKey != 0 && code == cancelKey) {
            cancel(true);
            return;
        }
        if (controller.state() == RequeueController.State.COUNTDOWN) {
            keyPressed = true;
        }
    }

    // ------------------------------------------------------------------ tick

    public void onClientTick() {
        boolean on = anyEnabled();
        if (!on) {
            if (wasEnabled) {
                controller.onDisabled();
                hudLines = List.of();
            }
            wasEnabled = false;
            return;
        }
        wasEnabled = true;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        TickContext ctx = TickContexts.current();
        long now = System.currentTimeMillis();
        if (ctx.worldEpoch() != lastEpoch) {
            lastEpoch = ctx.worldEpoch();
            detector.reset();
            controller.onWorldChange();
        }
        boolean inInstance = ctx.inCatacombs() || ctx.on(Island.KUUDRAS_HOLLOW);
        boolean outside = ctx.inSkyBlock() && !ctx.islandUnknown() && !inInstance;
        if (!outside) {
            outsideSince = 0;
        } else if (outsideSince == 0) {
            outsideSince = now;
        }
        boolean screen = ctx.screen() != TickContext.ScreenKind.NONE;
        trackIdle(player, screen, mc, now);

        if (controller.state() == RequeueController.State.IDLE && !showingAbort(now)) {
            hudLines = List.of();
            return;
        }
        boolean input = keyPressed || screen || moved(player)
                || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();
        RequeueTarget.Category category = controller.category();
        RequeueController.State before = controller.state();
        String send = before == RequeueController.State.IDLE ? null : controller.tick(new RequeueController.Env(
                category != null && enabled(category), leader(),
                PartyChatCommands.getInstance().downtimeSummary(), PartyTracker.getInstance().members(),
                category == RequeueTarget.Category.KUUDRA && hasKey(player),
                outsideSince == 0 ? 0 : now - outsideSince, inInstance, input,
                delaySeconds(category) * 1000L, cfg().maxConsecutive), now);
        keyPressed = false;
        if (controller.state() == RequeueController.State.COUNTDOWN && before != RequeueController.State.COUNTDOWN
                && player != null) {
            anchorX = player.getX();
            anchorY = player.getY();
            anchorZ = player.getZ();
        }
        if (send != null) {
            send(mc, send, inInstance);
        }
        tellAbortOnce();
        List<String> next = buildLines(now);
        if (!next.equals(hudLines)) {
            hudLines = next; // a new list only on change: the HUD rebuilds on identity
        }
    }

    private void trackIdle(LocalPlayer player, boolean screen, Minecraft mc, long now) {
        if (player == null) {
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (screen || x != lastX || y != lastY || z != lastZ || yaw != lastYaw || pitch != lastPitch
                || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) {
            lastInputAt = now;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    private boolean moved(LocalPlayer player) {
        if (player == null || controller.state() != RequeueController.State.COUNTDOWN) {
            return false;
        }
        double dx = player.getX() - anchorX;
        double dy = player.getY() - anchorY;
        double dz = player.getZ() - anchorZ;
        return dx * dx + dy * dy + dz * dz > MOVE_CANCEL_SQ;
    }

    /** Solo counts as leader: there is nobody else who could start the instance. */
    private static boolean leader() {
        PartyTracker party = PartyTracker.getInstance();
        return !party.hasParty() || party.selfIsLeader();
    }

    private static int delaySeconds(RequeueTarget.Category category) {
        int seconds = category == RequeueTarget.Category.KUUDRA ? cfg().kuudraDelay : cfg().dungeonsDelay;
        return Math.max(0, Math.min(60, seconds));
    }

    /** Whether the inventory holds the key for the target tier. Only asked while a Kuudra requeue is live. */
    private boolean hasKey(LocalPlayer player) {
        RequeueTarget target = controller.target();
        String key = target == null ? null : InstanceIds.kuudraKey(target.number());
        if (player == null || key == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && key.equals(ItemAnalysis.of(stack).id())) {
                return true;
            }
        }
        return false;
    }

    /** The instance being played right now, for !up between runs. */
    private static RequeueTarget liveTarget() {
        KuudraTracker kuudra = KuudraTracker.getInstance();
        if (kuudra.inHollow() && kuudra.tier() > 0) {
            return RequeueTarget.kuudra(kuudra.tier());
        }
        DungeonStateManager dungeon = DungeonStateManager.getInstance();
        return dungeon.inDungeon() ? RequeueTarget.floor(dungeon.floorType(), dungeon.floorNumber()) : null;
    }

    /**
     * Exactly one command; {@link RequeueController} never asks twice for one run. Inside the finished
     * instance that is the server's own re-queue command (same instance only - an !up / !down step
     * still goes out as {@code /joininstance}); from outside it is {@code /joininstance}.
     */
    private void send(Minecraft mc, String instanceId, boolean inInstance) {
        // tick() has cleared the step by now, so target() is the run's own instance again.
        RequeueTarget run = controller.target();
        boolean same = run != null && run.instanceId().equals(instanceId);
        String command = inInstance && controller.inPlace() && offeredCommand != null && same
                ? offeredCommand : "joininstance " + instanceId;
        offeredCommand = null;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Requeue] sending /{} ({} in a row)",
                command, controller.consecutive());
        if (mc.player == null || mc.player.connection == null) {
            SBSChat.send("Auto Requeue", Component.literal(" Could not requeue - not connected."));
            return;
        }
        try {
            mc.player.connection.sendCommand(command);
        } catch (Throwable e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Requeue] /joininstance failed: {}", e.toString());
            SBSChat.send("Auto Requeue", Component.literal(" The requeue did not go out. Queue yourself."));
        }
    }

    /** The run-command behind the line's click, without the leading '/'; {@code null} when it has none. */
    private static String requeueCommand(Component message) {
        if (message == null) {
            return null;
        }
        return message.visit((style, part) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand run) {
                String command = run.command().strip();
                command = command.startsWith("/") ? command.substring(1) : command;
                if (!command.isEmpty()) {
                    return Optional.of(command);
                }
            }
            return Optional.<String>empty();
        }, Style.EMPTY).orElse(null);
    }

    private void tellAbortOnce() {
        int seq = controller.abortSeq();
        if (seq == toldAbortSeq) {
            return;
        }
        toldAbortSeq = seq;
        String text = abortText();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Requeue] stopped: {}", text);
        SBSChat.send("Auto Requeue", Component.literal(" " + text));
    }

    private String abortText() {
        RequeueController.Abort abort = controller.lastAbort();
        if (abort == null) {
            return "";
        }
        String detail = controller.lastAbortDetail();
        return switch (abort) {
            case DOWNTIME -> "Downtime: " + detail;
            case NO_KEY -> abort.text() + " " + detail;
            case LIMIT -> "Stopped after " + detail + " requeues in a row";
            // Master Mode unlock state is not visible client-side, so a Master floor that did not
            // take is most likely one the leader has not unlocked yet.
            case FAILED -> detail.startsWith("M")
                    ? detail + " not unlocked? Requeue did not go through - stopped"
                    : "Requeue to " + detail + " did not go through - stopped";
            default -> abort.text();
        };
    }

    private boolean showingAbort(long now) {
        return controller.lastAbort() != null && now - controller.lastAbortAt() < ABORT_SHOW_MS;
    }

    // ------------------------------------------------------------------ HUD

    private List<String> buildLines(long now) {
        if (!cfg().showHud) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(4);
        RequeueTarget target = controller.target();
        String label = target == null ? "?" : target.label();
        switch (controller.state()) {
            case PENDING -> lines.add("Auto Requeue  §7" + label + " after leaving");
            case COUNTDOWN -> {
                int left = controller.secondsLeft(now);
                lines.add(controller.sender() ? "Next: " + label + " in " + left + "s"
                        : "Leader queues " + label + " in " + left + "s");
                if (controller.sender()) {
                    int cancelKey = cfg().cancelKey;
                    lines.add("§7Move or " + (cancelKey != 0 ? "press " + keyName(cancelKey)
                            : "/sbs requeue cancel") + " to cancel");
                }
            }
            case SENT -> lines.add("Requeued " + label + " §7- waiting for the transfer");
            default -> {
                if (showingAbort(now)) {
                    lines.add("Auto Requeue  §c" + abortText());
                }
            }
        }
        String downtime = PartyChatCommands.getInstance().downtimeSummary();
        if (downtime != null && !lines.isEmpty() && controller.lastAbort() != RequeueController.Abort.DOWNTIME) {
            lines.add("§7Downtime: §f" + downtime);
        }
        return List.copyOf(lines);
    }

    private static String keyName(int code) {
        String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0);
        return name == null ? "key " + code : name.toUpperCase(java.util.Locale.ROOT);
    }

    /** Prepared on the tick; the HUD only draws it. */
    public List<String> hudLines() {
        return hudLines;
    }

    /** Debug / status line for {@code /sbs requeue}. */
    public String status() {
        RequeueTarget target = controller.target();
        return "state=" + controller.state() + " target=" + (target == null ? "-" : target.label())
                + " inARow=" + controller.consecutive() + " step=" + controller.pendingStep()
                + " dungeons=" + cfg().dungeons + " kuudra=" + cfg().kuudra + " leader=" + leader();
    }
}
