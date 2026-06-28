package io.github.dreamlike.hotspot.methodbridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;

final class Elf implements NativeSymbols {
    private static final int SHT_SYMTAB = 2;
    private static final int SHT_DYNSYM = 11;
    private static final MemorySegment RAW_CODE_BLOB_NAME = java.lang.foreign.Arena.global().allocateFrom("hotspot-method-bridge raw code");
    private static final MethodHandle CODE_CACHE_BLOB_CREATE_MH = HotSpotMethodBridge.LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private final byte[] bytes;
    private final MemorySegment file;
    private final ValueLayout.OfShort shortLayout;
    private final ValueLayout.OfInt intLayout;
    private final ValueLayout.OfLong longLayout;
    private final long sectionHeaderOffset;
    private final int sectionHeaderSize;
    private final int sectionCount;
    private final long base;

    Elf() {
        try {
            bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(VmStructs.libjvm()));
            if (bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') {
                throw new IllegalStateException("not an ELF file");
            }
            if (bytes[4] != 2) {
                throw new IllegalStateException("only ELF64 is implemented");
            }
            ByteOrder order = switch (bytes[5]) {
                case 1 -> ByteOrder.LITTLE_ENDIAN;
                case 2 -> ByteOrder.BIG_ENDIAN;
                default -> throw new IllegalStateException("unknown ELF byte order");
            };
            file = MemorySegment.ofArray(bytes);
            shortLayout = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(order);
            intLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(order);
            longLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(order);
            sectionHeaderOffset = elfLong(40);
            sectionHeaderSize = Short.toUnsignedInt(elfShort(58));
            sectionCount = Short.toUnsignedInt(elfShort(60));
            base = VmStructs.runtimeAddress("gHotSpotVMStructs") - fileSymbol("gHotSpotVMStructs");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long checkedResolveJmethodId() {
        return symbol("_ZN6Method26checked_resolve_jmethod_idEP10_jmethodID");
    }

    @Override
    public long threadCurrent() {
        return symbol("_ZN6Thread7currentEv");
    }

    @Override
    public long whiteBoxCompileMethod() {
        return symbol("_ZN8WhiteBox14compile_methodEP6MethodiiP10JavaThread");
    }

    @Override
    public long methodGetI2cEntry() {
        return symbol("_ZN6Method13get_i2c_entryEv");
    }

    private long bufferBlobCreate() {
        return symbol("_ZN10BufferBlob6createEPKcj");
    }

    @Override
    public NativeCode installCodeBlob(byte[] code) {
        try {
            MemorySegment blob = (MemorySegment) CODE_CACHE_BLOB_CREATE_MH.invokeExact(
                    MemorySegment.ofAddress(bufferBlobCreate()),
                    RAW_CODE_BLOB_NAME,
                    code.length);
            if (blob.address() == 0) {
                throw new OutOfMemoryError("BufferBlob::create failed");
            }
            long entry = blob.address() + HotSpotMethodBridge.CODE_BLOB_LAYOUT.codeOffset();
            HotSpotMethodBridge.memory(entry, code.length).copyFrom(MemorySegment.ofArray(code));
            LinuxClearCache.clearCache(entry, code.length);
            return new NativeCode(blob.address(), entry, code.length);
        } catch (Throwable e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(e);
        }
    }

    private static final class LinuxClearCache {
        private static final MethodHandle HANDLE = handle();

        private static void clearCache(long start, int size) throws Throwable {
            HANDLE.invokeExact(MemorySegment.ofAddress(start), MemorySegment.ofAddress(start + size));
        }

        private static MethodHandle handle() {
            return HotSpotMethodBridge.LINKER.downcallHandle(
                    SymbolLookup.libraryLookup("libgcc_s.so.1", java.lang.foreign.Arena.global())
                            .find("__clear_cache")
                            .orElseThrow(() -> new IllegalStateException("libgcc_s __clear_cache not found")),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        }
    }

    private long symbol(String name) {
        long value = fileSymbol(name);
        if (value == 0) {
            throw new IllegalStateException("ELF symbol not found: " + name);
        }
        return base + value;
    }

    private long fileSymbol(String name) {
        for (int i = 0; i < sectionCount; i++) {
            long section = section(i);
            int type = elfInt(section + 4);
            if (type != SHT_SYMTAB && type != SHT_DYNSYM) {
                continue;
            }
            long symOffset = elfLong(section + 24);
            long symSize = elfLong(section + 32);
            int stringSectionIndex = elfInt(section + 40);
            long symEntrySize = elfLong(section + 56);
            long stringOffset = elfLong(section(stringSectionIndex) + 24);
            for (long entry = symOffset; entry < symOffset + symSize; entry += symEntrySize) {
                int nameOffset = elfInt(entry);
                if (nameOffset != 0 && name.equals(fileCString(bytes, Math.toIntExact(stringOffset + nameOffset)))) {
                    return elfLong(entry + 8);
                }
            }
        }
        return 0;
    }

    private long section(int index) {
        return sectionHeaderOffset + (long) index * sectionHeaderSize;
    }

    private short elfShort(long offset) {
        return file.get(shortLayout, offset);
    }

    private int elfInt(long offset) {
        return file.get(intLayout, offset);
    }

    private long elfLong(long offset) {
        return file.get(longLayout, offset);
    }

    private static String fileCString(byte[] bytes, int offset) {
        int end = offset;
        while (bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
