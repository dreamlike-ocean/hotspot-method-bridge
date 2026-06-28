package io.github.dreamlike.hotspot.methodbridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

final class MachO implements NativeSymbols {
    private static final int LC_SYMTAB = 0x2;
    private static final MethodHandle MMAP = HotSpotMethodBridge.LINKER.downcallHandle(
            HotSpotMethodBridge.LINKER.defaultLookup().find("mmap").orElseThrow(),
            FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG));
    private static final MethodHandle MPROTECT = HotSpotMethodBridge.LINKER.downcallHandle(
            HotSpotMethodBridge.LINKER.defaultLookup().find("mprotect").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    static final long MAP_FAILED = -1L;
    static final int PROT_READ = 0x1;
    static final int PROT_WRITE = 0x2;
    static final int PROT_EXEC = 0x4;
    static final int MAP_PRIVATE = 0x2;
    static final int MAP_ANONYMOUS = mapAnonymousFlag();


    private final long base = VmStructs.runtimeAddress("gHotSpotVMStructs") - fileSymbol("_gHotSpotVMStructs");

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

    private long currentThreadEnableWx() {
        return symbol("_ZN2os24current_thread_enable_wxE6WXMode");
    }

    @Override
    public NativeCode installCodeBlob(String name, byte[] code) {
        return InstallerHolder.CODE_BLOB_INSTALLER.install(name, code, bufferBlobCreate(), currentThreadEnableWx());
    }

    static MemorySegment executableMemory(byte[] code) {
        try {
            MemorySegment memory = (MemorySegment) MMAP.invokeExact(
                    MemorySegment.NULL,
                    (long) code.length,
                    PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS,
                    -1,
                    0L);
            if (memory.address() == MAP_FAILED) {
                throw new OutOfMemoryError("mmap executable memory allocation failed");
            }

            HotSpotMethodBridge.memory(memory.address(), code.length).copyFrom(MemorySegment.ofArray(code));
            int res = (int) MPROTECT.invokeExact(memory, (long) code.length, PROT_READ | PROT_EXEC);
            if (res != 0) {
                throw new IllegalStateException("mprotect(PROT_READ | PROT_EXEC) failed: " + res);
            }
            return memory;
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

    static int mapAnonymousFlag() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return 0x1000;
        }
        if (os.contains("linux")) {
            return 0x20;
        }
        throw new UnsupportedOperationException("unsupported OS: " + os);
    }

    private static final class InstallerHolder {
        private static final MachOAArch64CodeBlobInstaller CODE_BLOB_INSTALLER = new MachOAArch64CodeBlobInstaller();
    }

    private long symbol(String name) {
        long value = fileSymbol("_" + name);
        if (value == 0) {
            throw new IllegalStateException("Mach-O symbol not found: " + name);
        }
        return base + value;
    }

    private long fileSymbol(String name) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(VmStructs.libjvm()));
            MemorySegment file = MemorySegment.ofArray(bytes);
            ValueLayout.OfInt intLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            ValueLayout.OfLong longLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            if (file.get(intLayout, 0) != 0xfeedfacf) {
                throw new IllegalStateException("only little-endian Mach-O 64 is implemented");
            }
            int ncmds = file.get(intLayout, 16);
            int command = 32;
            int symoff = 0;
            int nsyms = 0;
            int stroff = 0;
            for (int i = 0; i < ncmds; i++) {
                int cmd = file.get(intLayout, command);
                int cmdsize = file.get(intLayout, command + 4);
                if (cmd == LC_SYMTAB) {
                    symoff = file.get(intLayout, command + 8);
                    nsyms = file.get(intLayout, command + 12);
                    stroff = file.get(intLayout, command + 16);
                    break;
                }
                command += cmdsize;
            }
            for (int i = 0; i < nsyms; i++) {
                int entry = symoff + i * 16;
                int strx = file.get(intLayout, entry);
                if (name.equals(fileCString(bytes, stroff + strx))) {
                    return file.get(longLayout, entry + 8);
                }
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String fileCString(byte[] bytes, int offset) {
        int end = offset;
        while (bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // 这个安装 trampoline 只给 macOS/aarch64 用：切 WXWrite，写 CodeCache，再切回 WXExec。
    private static final class MachOAArch64CodeBlobInstaller {
        private static final MethodHandle HANDLE = installHandle();
        private static final int X0 = 0;
        private static final int X1 = 1;
        private static final int X2 = 2;
        private static final int X3 = 3;
        private static final int X4 = 4;
        private static final int X5 = 5;
        private static final int X6 = 6;
        private static final int X8 = 8;
        private static final int X16 = 16;
        private static final int X30 = 30;
        private static final int SP = 31;
        private static final int STACK_SIZE = 96;
        private static final int NAME_SLOT = 0;
        private static final int LENGTH_SLOT = 8;
        private static final int SOURCE_SLOT = 16;
        private static final int CODE_OFFSET_SLOT = 24;
        private static final int CREATE_SLOT = 32;
        private static final int WX_SLOT = 40;
        private static final int ENTRY_SLOT = 48;
        private static final int LR_SLOT = 88;

        private final ArrayList<Integer> code = new ArrayList<>();
        private final HashMap<String, Integer> labels = new HashMap<>();
        private final ArrayList<Patch> patches = new ArrayList<>();

        public NativeCode install(String name, byte[] code, long bufferBlobCreate, long currentThreadEnableWx) {
            try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                // 业务机器码放进 HotSpot CodeCache；mmap 只承载安装用 trampoline。
                MemorySegment source = arena.allocate(code.length);
                source.copyFrom(MemorySegment.ofArray(code));
                MemorySegment entry = (MemorySegment) HANDLE.invokeExact(
                        java.lang.foreign.Arena.global().allocateFrom(name),
                        (long) code.length,
                        source,
                        HotSpotMethodBridge.CODE_BLOB_LAYOUT.codeOffset(),
                        MemorySegment.ofAddress(bufferBlobCreate),
                        MemorySegment.ofAddress(currentThreadEnableWx));
                if (entry.address() == 0) {
                    throw new OutOfMemoryError("BufferBlob::create failed");
                }
                return new NativeCode(entry.address() - HotSpotMethodBridge.CODE_BLOB_LAYOUT.codeOffset(), entry.address(), code.length);
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

        private static MethodHandle installHandle() {
            try {
                MemorySegment stub = executableMemory(generate());
                return HotSpotMethodBridge.LINKER.downcallHandle(
                        stub,
                        FunctionDescriptor.of(
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
            } catch (Throwable e) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(e);
            }
        }

        private static byte[] generate() {
            String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
            if (!arch.equals("aarch64") && !arch.equals("arm64")) {
                throw new UnsupportedOperationException("raw machine code install only supports aarch64/arm64 now: " + arch);
            }
            MachOAArch64CodeBlobInstaller e = new MachOAArch64CodeBlobInstaller();
            e.emit();
            return e.bytes();
        }

        private void emit() {
            subSp(STACK_SIZE);
            str(X30, SP, LR_SLOT);
            str(X0, SP, NAME_SLOT);
            str(X1, SP, LENGTH_SLOT);
            str(X2, SP, SOURCE_SLOT);
            str(X3, SP, CODE_OFFSET_SLOT);
            str(X4, SP, CREATE_SLOT);
            str(X5, SP, WX_SLOT);

            movW(X0, 0);
            blr(X5);

            ldr(X0, SP, NAME_SLOT);
            ldr(X1, SP, LENGTH_SLOT);
            ldr(X16, SP, CREATE_SLOT);
            blr(X16);

            ldr(X3, SP, CODE_OFFSET_SLOT);
            addReg(X4, X0, X3);
            str(X4, SP, ENTRY_SLOT);
            ldr(X5, SP, SOURCE_SLOT);
            ldr(X6, SP, LENGTH_SLOT);

            label("copy_loop");
            cbz(X6, "copy_done");
            ldrbPost(X8, X5, 1);
            strbPost(X8, X4, 1);
            subsImm(X6, X6, 1);
            bCond(1, "copy_loop");
            label("copy_done");

            ldr(X0, SP, ENTRY_SLOT);
            ldr(X1, SP, LENGTH_SLOT);
            flushICache();

            ldr(X5, SP, WX_SLOT);
            movW(X0, 1);
            blr(X5);

            ldr(X0, SP, ENTRY_SLOT);
            ldr(X30, SP, LR_SLOT);
            addSp(STACK_SIZE);
            word(0xd65f03c0); // ret
        }

        private void flushICache() {
            // macOS 用户态不能稳定读取 CTR_EL0；这里按 AArch64 常见 64B cache line 过量 flush。
            movZ(X3, 64);
            subImm(X4, X3, 1);
            word(0x8a240004); // bic x4, x0, x4
            addReg(X5, X0, X1);

            label("dc_loop");
            word(0xd50b7b24); // dc cvau, x4
            addReg(X4, X4, X3);
            cmpReg(X4, X5);
            bCond(3, "dc_loop");

            word(0xd5033b9f); // dsb ish
            subImm(X4, X3, 1);
            word(0x8a240004); // bic x4, x0, x4

            label("ic_loop");
            word(0xd50b7524); // ic ivau, x4
            addReg(X4, X4, X3);
            cmpReg(X4, X5);
            bCond(3, "ic_loop");
            word(0xd5033b9f); // dsb ish
            word(0xd5033fdf); // isb
        }

        private byte[] bytes() {
            for (Patch patch : patches) {
                int target = labels.get(patch.label);
                int delta = target - patch.index;
                code.set(patch.index, patch.opcode | ((delta & 0x7ffff) << 5));
            }
            byte[] bytes = new byte[code.size() * Integer.BYTES];
            MemorySegment out = MemorySegment.ofArray(bytes);
            ValueLayout.OfInt intLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < code.size(); i++) {
                out.set(intLayout, (long) i * Integer.BYTES, code.get(i));
            }
            return bytes;
        }

        private void label(String label) {
            labels.put(label, code.size());
        }

        private void word(int value) {
            code.add(value);
        }

        private void cbz(int rt, String label) {
            patch(0xb4000000 | rt, label);
        }

        private void bCond(int cond, String label) {
            patch(0x54000000 | cond, label);
        }

        private void patch(int opcode, String label) {
            patches.add(new Patch(code.size(), opcode, label));
            word(opcode);
        }

        private void subSp(int bytes) {
            word(0xd10003ff | (bytes << 10));
        }

        private void addSp(int bytes) {
            word(0x910003ff | (bytes << 10));
        }

        private void str(int rt, int rn, int offset) {
            word(0xf9000000 | ((offset / Long.BYTES) << 10) | (rn << 5) | rt);
        }

        private void ldr(int rt, int rn, int offset) {
            word(0xf9400000 | ((offset / Long.BYTES) << 10) | (rn << 5) | rt);
        }

        private void ldrbPost(int rt, int rn, int offset) {
            word(0x38400400 | ((offset & 0x1ff) << 12) | (rn << 5) | rt);
        }

        private void strbPost(int rt, int rn, int offset) {
            word(0x38000400 | ((offset & 0x1ff) << 12) | (rn << 5) | rt);
        }

        private void movW(int rd, int value) {
            word(0x52800000 | (value << 5) | rd);
        }

        private void movZ(int rd, int value) {
            word(0xd2800000 | (value << 5) | rd);
        }

        private void blr(int rn) {
            word(0xd63f0000 | (rn << 5));
        }

        private void addReg(int rd, int rn, int rm) {
            word(0x8b000000 | (rm << 16) | (rn << 5) | rd);
        }

        private void subImm(int rd, int rn, int imm) {
            word(0xd1000000 | (imm << 10) | (rn << 5) | rd);
        }

        private void subsImm(int rd, int rn, int imm) {
            word(0xf1000000 | (imm << 10) | (rn << 5) | rd);
        }

        private void cmpReg(int rn, int rm) {
            word(0xeb00001f | (rm << 16) | (rn << 5));
        }

        private record Patch(int index, int opcode, String label) {
        }
    }
}
