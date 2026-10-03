/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.overlayinspector;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * "Who is drawing right now?" – answered from the call stack at the moment a rectangle is handed to
 * the render pipeline.
 *
 * <p>There is no ownership tag on drawn pixels: the pipeline receives geometry, not authorship. What
 * it does receive it on is the drawing mod's own call stack, so the first frame below the drawing
 * API that belongs to an installed mod <i>is</i> the answer. Overlays are drawn by their mod's own
 * classes (a HUD class, a renderer, a lambda), which is exactly what this finds.
 *
 * <p>Two cases need more than the class name:
 * <ul>
 *   <li>Code a mod injects into a game class runs as that class, so the stack reads
 *       {@code net.minecraft...}. The method name still carries the mod's prefix, and
 *       {@link ModIndex#ownerOfToken} turns that back into a mod – see the token pass below.</li>
 *   <li>A stack of nothing but game classes means the game drew it, which is a useful answer in its
 *       own right: it is how the inspector says "this is vanilla, not one of your mods".</li>
 * </ul>
 *
 * <p>Walking a stack is not free, which is why it happens only while the inspector is switched on.
 * The walk short-circuits at the first frame it can attribute (usually three or four frames up) and
 * never retains frames, so no stack trace is ever materialised.
 */
public final class ModAttribution {

    private static final StackWalker WALKER = StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE);

    /** Deep enough to leave the drawing API and reach the caller; short enough to stay cheap. */
    private static final int MAX_FRAMES = 28;

    /** This feature's own package – its frames are never an answer, only the road to one. */
    private static final String OWN_PACKAGE = "sbs.modid.client.helper.overlayinspector.";

    /**
     * Method-name marker of the capture hook. The hook is merged into the pipeline class, so it
     * looks like a game frame carrying an SBS-shaped name – left alone, it would answer every single
     * element in the client with "SkyBlock Simplified".
     *
     * <p>Matched anywhere in the name, not just at the start: a merged handler can be renamed to
     * {@code handler$<id>$<original>}, which would slip past a prefix test.
     */
    private static final String CAPTURE_PREFIX = "sbsInspectorCapture$";

    private ModAttribution() {
    }

    /** The frame the last {@link #currentOwner()} decided on – shown on the inspector's card. */
    private static Class<?> sourceClass;
    private static String sourceMethod;

    /**
     * The id of the mod responsible for the draw call in progress, {@link ModIndex#MINECRAFT} when
     * the stack holds nothing but the game, or {@link ModIndex#UNKNOWN} when it cannot be told.
     *
     * <p>Two passes over one walk, and the order between them matters:
     * <ol>
     *   <li>a frame in a class that <i>belongs</i> to a mod – the mod is drawing its own code;</li>
     *   <li>only if there is none: a game frame whose method name carries a mod's prefix, i.e. code
     *       injected into a game class.</li>
     * </ol>
     * The other order looks equivalent and is not. Injected code sits <i>between</i> the drawer and
     * the pipeline whenever a mod wraps a draw call it did not originate – a themed vanilla widget,
     * a recoloured bar – so taking the innermost injection first would credit the wrapper with
     * everything it passes through. The class that owns the drawing is the honest answer.
     */
    public static String currentOwner() {
        sourceClass = null;
        sourceMethod = null;
        try {
            String owner = WALKER.walk(ModAttribution::firstOwner);
            return owner == null ? ModIndex.UNKNOWN : owner;
        } catch (Throwable t) {
            return ModIndex.UNKNOWN;
        }
    }

    /** Declaring class of the frame the last decision came from, or {@code null}. */
    public static Class<?> lastSourceClass() {
        return sourceClass;
    }

    /** Method name of the frame the last decision came from, or {@code null}. */
    public static String lastSourceMethod() {
        return sourceMethod;
    }

    private static String firstOwner(Stream<StackFrame> frames) {
        String injectedOwner = null;
        Class<?> injectedClass = null;
        String injectedMethod = null;

        Iterator<StackFrame> iterator = frames.limit(MAX_FRAMES).iterator();
        while (iterator.hasNext()) {
            StackFrame frame = iterator.next();
            String method = frame.getMethodName();
            if (frame.getClassName().startsWith(OWN_PACKAGE) || method.contains(CAPTURE_PREFIX)) {
                continue; // the plumbing that asked the question
            }
            String owner = ModIndex.ownerOf(frame.getDeclaringClass());
            if (owner != null && !owner.isEmpty() && !ModIndex.MINECRAFT.equals(owner)) {
                sourceClass = frame.getDeclaringClass();
                sourceMethod = method;
                return owner;
            }
            if (injectedOwner == null) {
                String injected = injectedOwner(method);
                if (injected != null) {
                    injectedOwner = injected;
                    injectedClass = frame.getDeclaringClass();
                    injectedMethod = method;
                }
            }
        }
        if (injectedOwner != null) {
            sourceClass = injectedClass;
            sourceMethod = injectedMethod;
            return injectedOwner;
        }
        return ModIndex.MINECRAFT;
    }

    /**
     * The mod named inside an injected method's name, or {@code null}.
     *
     * <p>Injected methods are prefixed with something identifying their source – {@code modid$name},
     * or a generated {@code handler$xyz000$modid$name} – so every {@code $}-separated token is a
     * candidate. Tokens that match no installed mod are ignored, which is the common case for the
     * generated middle parts.
     */
    private static String injectedOwner(String methodName) {
        if (methodName == null || methodName.indexOf('$') < 0) {
            return null;
        }
        int from = 0;
        while (from < methodName.length()) {
            int next = methodName.indexOf('$', from);
            String token = next < 0 ? methodName.substring(from) : methodName.substring(from, next);
            String owner = ModIndex.ownerOfToken(token);
            if (owner != null && !owner.isEmpty()) {
                return owner;
            }
            if (next < 0) {
                break;
            }
            from = next + 1;
        }
        return null;
    }
}
