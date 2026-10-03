/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.overlayinspector;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Which mod does this class belong to?" – the lookup the Overlay Inspector answers every hovered
 * pixel with.
 *
 * <p>The loader knows every installed mod and where it was loaded from, but offers no
 * class&nbsp;&rarr;&nbsp;mod query, so this builds one from two independent signals:
 *
 * <ol>
 *   <li><b>Code source</b> (exact): every class remembers the jar it was defined from, and the
 *       loader reports the jar each mod was loaded from. Matching the two is a map lookup and is
 *       right by construction.</li>
 *   <li><b>Package prefix</b> (fallback): mods packaged inside another mod's jar share that jar's
 *       code source, so the first signal would name the host. Listing each mod's own package tree
 *       (only as deep as it takes to reach the first class file) separates them again.</li>
 * </ol>
 *
 * <p>Everything is built once, lazily, on the first lookup – i.e. the first time the inspector is
 * switched on, never during startup – and cached per class in a {@link ClassValue}, so the hot
 * capture path costs one field read after the first sighting of a class.
 *
 * <p>Ambiguity is reported as "unknown" rather than guessed: two mods claiming one package prefix
 * cancel each other out. A wrong mod name is worse than no name – the whole point of the feature is
 * to be able to trust the answer.
 */
public final class ModIndex {

    /** What the inspector shows for one mod. */
    public record ModInfo(String id, String name, String version) {

        /** Name plus version, e.g. {@code Example Mod 1.2.3}. */
        public String label() {
            return version == null || version.isBlank() ? name : name + " " + version;
        }
    }

    /** Owner id used for everything the game itself drew. */
    public static final String MINECRAFT = "minecraft";

    /** Returned instead of a mod id when nothing could be resolved. */
    public static final String UNKNOWN = "";

    /** How deep the package walk descends before giving up on a branch. */
    private static final int MAX_PACKAGE_DEPTH = 5;

    /** Upper bound on directory entries visited per mod, so a huge jar cannot stall the walk. */
    private static final int WALK_BUDGET = 4000;

    private static final Map<String, ModInfo> BY_ID = new HashMap<>();

    /** Absolute path of a loaded jar / classes directory &rarr; the mod it belongs to. */
    private static final Map<String, String> BY_ORIGIN = new HashMap<>();

    /** Package prefix &rarr; mod id ({@link #UNKNOWN} once two mods claim the same prefix). */
    private static final Map<String, String> BY_PACKAGE = new HashMap<>();

    /** Normalised id / name &rarr; mod id, for reading a mod out of an injected method's name. */
    private static final Map<String, String> BY_TOKEN = new HashMap<>();

    private static volatile boolean built;

    private static final ClassValue<String> OWNER = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return resolveUncached(type);
        }
    };

    private ModIndex() {
    }

    /**
     * Builds the index now, if it has not been built yet.
     *
     * <p>Called when the inspector is switched on so the jar walk happens there – a hitch at a
     * deliberate keypress – instead of inside the first draw call it is asked about.
     */
    public static void warmUp() {
        ensureBuilt();
    }

    /** The id of the mod that shipped {@code type}, or {@link #UNKNOWN}. Cached per class. */
    public static String ownerOf(Class<?> type) {
        if (type == null) {
            return UNKNOWN;
        }
        return OWNER.get(type);
    }

    /**
     * The mod behind a token taken from an injected method's name, or {@code null}.
     *
     * <p>Code injected into a game class runs <i>as</i> that class, so the stack says
     * {@code net.minecraft...} and the owning mod is invisible. The one trace left is the method
     * name, which by convention carries the mod's own prefix – SBS writes
     * {@code skyblockSimplified$reskin}. Matching those tokens against the installed mods recovers
     * the owner for the minority of overlays drawn entirely inside an injected method.
     */
    public static String ownerOfToken(String token) {
        if (token == null || token.length() < 3) {
            return null;
        }
        ensureBuilt();
        return BY_TOKEN.get(normalise(token));
    }

    /** Metadata for a resolved id, or {@code null} when the id is not an installed mod. */
    public static ModInfo info(String modId) {
        if (modId == null || modId.isEmpty()) {
            return null;
        }
        ensureBuilt();
        return BY_ID.get(modId);
    }

    /** Display name for an owner id – falls back to the raw id, then to "Unknown". */
    public static String displayName(String modId) {
        ModInfo info = info(modId);
        if (info != null) {
            return info.name();
        }
        return modId == null || modId.isEmpty() ? "Unknown" : modId;
    }

    private static String resolveUncached(Class<?> type) {
        String name = type.getName();
        // Fast path: the game's own classes are by far the most common caller, and their origin is
        // the one jar we never need a map for.
        if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) {
            return MINECRAFT;
        }
        if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")) {
            return UNKNOWN;
        }
        ensureBuilt();

        String byPackage = packageOwner(name);
        // The package map wins over the code source: it is the only signal that can tell a mod
        // bundled inside another mod's jar from its host.
        if (byPackage != null && !byPackage.isEmpty()) {
            return byPackage;
        }
        String byOrigin = originOwner(type);
        if (byOrigin != null) {
            return byOrigin;
        }
        return UNKNOWN;
    }

    private static String packageOwner(String className) {
        int end = className.lastIndexOf('.');
        while (end > 0) {
            String prefix = className.substring(0, end);
            String owner = BY_PACKAGE.get(prefix);
            if (owner != null) {
                return owner;
            }
            end = prefix.lastIndexOf('.');
        }
        return null;
    }

    private static String originOwner(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
                return null;
            }
            Path path = Paths.get(location.toURI());
            return BY_ORIGIN.get(path.toAbsolutePath().normalize().toString());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static synchronized void ensureBuilt() {
        if (built) {
            return;
        }
        built = true;
        long start = System.nanoTime();
        try {
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                index(container);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Inspector] mod index build failed", t);
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Inspector] indexed {} mods, {} packages, {} jars in {} ms",
                BY_ID.size(), BY_PACKAGE.size(), BY_ORIGIN.size(),
                (System.nanoTime() - start) / 1_000_000L);
    }

    private static void index(ModContainer container) {
        ModMetadata metadata = container.getMetadata();
        String id = metadata.getId();
        BY_ID.put(id, new ModInfo(id, metadata.getName(), String.valueOf(metadata.getVersion())));
        BY_TOKEN.putIfAbsent(normalise(id), id);
        BY_TOKEN.putIfAbsent(normalise(metadata.getName()), id);

        try {
            for (Path origin : container.getOrigin().getPaths()) {
                BY_ORIGIN.putIfAbsent(origin.toAbsolutePath().normalize().toString(), id);
            }
        } catch (Throwable ignored) {
            // Nested and built-in mods have no path of their own – the package walk covers them.
        }

        // The game and the loader would otherwise claim half the package tree ("net", "com") and
        // shadow the mods that live under the same roots.
        if (id.equals(MINECRAFT) || id.equals("java") || id.equals("fabricloader")) {
            return;
        }
        for (Path root : container.getRootPaths()) {
            int[] budget = {WALK_BUDGET};
            walkPackages(root, "", 0, id, budget);
        }
    }

    /**
     * Records the shallowest packages of one mod that actually contain classes.
     *
     * <p>Stopping at the first level with class files is what keeps this cheap: a mod is claimed by
     * {@code com.example.mymod}, not by its hundred sub-packages, and never by the bare
     * {@code com} that every other mod shares.
     */
    private static void walkPackages(Path dir, String pkg, int depth, String modId, int[] budget) {
        if (depth > MAX_PACKAGE_DEPTH || budget[0] <= 0) {
            return;
        }
        boolean hasClasses = false;
        List<Path> subdirectories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (--budget[0] <= 0) {
                    break;
                }
                String name = segment(entry);
                if (Files.isDirectory(entry)) {
                    if (isPackageName(name)) {
                        subdirectories.add(entry);
                    }
                } else if (!hasClasses && name.endsWith(".class")) {
                    hasClasses = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (hasClasses && !pkg.isEmpty()) {
            claimPackage(pkg, modId);
            return;
        }
        for (Path sub : subdirectories) {
            String name = segment(sub);
            walkPackages(sub, pkg.isEmpty() ? name : pkg + "." + name, depth + 1, modId, budget);
        }
    }

    /** Directory name without the trailing separator some archive filesystems keep. */
    private static String segment(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return "";
        }
        String text = name.toString();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    private static void claimPackage(String pkg, String modId) {
        String previous = BY_PACKAGE.putIfAbsent(pkg, modId);
        if (previous != null && !previous.equals(modId)) {
            BY_PACKAGE.put(pkg, UNKNOWN); // shared prefix – no honest answer, so give none
        }
    }

    private static boolean isPackageName(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Lowercase, letters and digits only – so {@code SkyBlock-Simplified} and {@code skyblocksimplified} match. */
    private static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
