package io.github.dreamlike.hotspot.vmstruct;

public final class PeCoff implements NativeSymbols {
    @Override
    public long threadCurrent() {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }

    @Override
    public long whiteBoxCompileMethod() {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }

    @Override
    public long methodGetI2cEntry() {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }

    @Override
    public long methodSetNativeFunction() {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }

    @Override
    public NativeCode installCodeBlob(String name, byte[] code) {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }
}
