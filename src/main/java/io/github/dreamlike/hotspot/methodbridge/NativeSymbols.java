package io.github.dreamlike.hotspot.methodbridge;

sealed interface NativeSymbols permits MachO, Elf, PeCoff {
    long checkedResolveJmethodId();

    long threadCurrent();

    long whiteBoxCompileMethod();

    long methodGetI2cEntry();

    NativeCode installCodeBlob(byte[] code);
}
