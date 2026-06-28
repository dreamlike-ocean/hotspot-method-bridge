package io.github.dreamlike.hotspot.methodbridge;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.charset.StandardCharsets.US_ASCII;

final class VmStructs {
    private static final SymbolLookup JVM = SymbolLookup.libraryLookup(libjvm(), Arena.global());

    private final long entries;
    private final long typeNameOffset;
    private final long fieldNameOffset;
    private final long isStaticOffset;
    private final long offsetOffset;
    private final long addressOffset;
    private final long stride;

    VmStructs() {
        entries = HotSpotMethodBridge.getAddress(symbol("gHotSpotVMStructs"));
        typeNameOffset = HotSpotMethodBridge.getLong(symbol("gHotSpotVMStructEntryTypeNameOffset"));
        fieldNameOffset = HotSpotMethodBridge.getLong(symbol("gHotSpotVMStructEntryFieldNameOffset"));
        isStaticOffset = HotSpotMethodBridge.getLong(symbol("gHotSpotVMStructEntryIsStaticOffset"));
        offsetOffset = HotSpotMethodBridge.getLong(symbol("gHotSpotVMStructEntryOffsetOffset"));
        addressOffset = HotSpotMethodBridge.getLong(symbol("gHotSpotVMStructEntryAddressOffset"));
        stride = HotSpotMethodBridge.getLong(symbol("gHotSpotVMStructEntryArrayStride"));
    }

    long offset(String typeName, String fieldName) {
        long entry = find(typeName, fieldName, false);
        return HotSpotMethodBridge.getLong(entry + offsetOffset);
    }

    long staticAddress(String typeName, String fieldName) {
        long entry = find(typeName, fieldName, true);
        return HotSpotMethodBridge.getAddress(entry + addressOffset);
    }

    private long find(String typeName, String fieldName, boolean isStatic) {
        // VMStructEntry 本质上是 HotSpot 给“外部观察者”导出的 C++ 字段元数据表。每条记录描述一个 VM 内部字段
        for (long entry = entries; ; entry += stride) {
            long typePtr = HotSpotMethodBridge.getAddress(entry + typeNameOffset);
            long fieldPtr = HotSpotMethodBridge.getAddress(entry + fieldNameOffset);
            if (typePtr == 0 || fieldPtr == 0) {
                break;
            }
            if ((HotSpotMethodBridge.getInt(entry + isStaticOffset) != 0) == isStatic
                    && typeName.equals(cString(typePtr))
                    && fieldName.equals(cString(fieldPtr))) {
                return entry;
            }
        }
        throw new IllegalArgumentException("VMStruct not found: " + typeName + "::" + fieldName);
    }

    static long symbol(String name) {
        return JVM.find(name)
                .orElseThrow(() -> new IllegalStateException("symbol not found: " + name))
                .address();
    }

    static String libjvm() {
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

    static long runtimeAddress(String name) {
        return SymbolLookup.libraryLookup(libjvm(), Arena.global())
                .find(name)
                .orElseThrow()
                .address();
    }

    private static String cString(long address) {
        int length = 0;
        while (HotSpotMethodBridge.getByte(address + length) != 0) {
            length++;
        }
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = HotSpotMethodBridge.getByte(address + i);
        }
        return new String(bytes, US_ASCII);
    }
}
