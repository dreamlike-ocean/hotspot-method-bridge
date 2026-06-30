package io.github.dreamlike.hotspot.vmstruct;

public sealed interface NativeSymbols permits MachO, Elf, PeCoff {
    long threadCurrent();

    long whiteBoxCompileMethod();

    long methodGetI2cEntry();

    long methodSetNativeFunction();

    NativeCode installCodeBlob(String name, byte[] code);
}
