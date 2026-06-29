package io.github.dreamlike.hotspot.vmstruct;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class HotSpotLibrary {
    private static final String LIBJVM = findLibjvm();
    private static final SymbolLookup JVM = SymbolLookup.libraryLookup(LIBJVM, Arena.global());

    private HotSpotLibrary() {
    }

    public static String libjvm() {
        return LIBJVM;
    }

    public static Path libjvmPath() {
        return Path.of(LIBJVM);
    }

    public static SymbolLookup libjvmLookup() {
        return JVM;
    }

    public static long runtimeAddress(String name) {
        return JVM.find(name)
                .orElseThrow(() -> new IllegalStateException("symbol not found: " + name))
                .address();
    }

    private static String findLibjvm() {
        String javaHome = System.getProperty("java.home");
        Path unix = Paths.get(javaHome, "lib", "server", System.mapLibraryName("jvm"));
        if (Files.exists(unix)) {
            return unix.toString();
        }
        Path windows = Paths.get(javaHome, "bin", "server", System.mapLibraryName("jvm"));
        if (Files.exists(windows)) {
            return windows.toString();
        }
        return System.mapLibraryName("jvm");
    }
}
