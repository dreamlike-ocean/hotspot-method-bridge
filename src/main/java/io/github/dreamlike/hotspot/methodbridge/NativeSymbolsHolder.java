package io.github.dreamlike.hotspot.methodbridge;

import java.util.Locale;

final class NativeSymbolsHolder {
    private NativeSymbolsHolder() {
    }

    static NativeSymbols current() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return MachOHolder.SYMBOLS;
        }
        if (os.contains("linux")) {
            return ElfHolder.SYMBOLS;
        }
        if (os.contains("win")) {
            return PeCoffHolder.SYMBOLS;
        }
        throw new UnsupportedOperationException("unsupported OS: " + os);
    }

    private static final class MachOHolder {
        private static final NativeSymbols SYMBOLS = new MachO();
    }

    private static final class ElfHolder {
        private static final NativeSymbols SYMBOLS = new Elf();
    }

    private static final class PeCoffHolder {
        private static final NativeSymbols SYMBOLS = new PeCoff();
    }
}
