package io.github.dreamlike.hotspot.methodbridge;

final class PeCoff implements NativeSymbols {
    @Override
    public long checkedResolveJmethodId() {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }

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
    public NativeCode installCodeBlob(byte[] code) {
        throw new UnsupportedOperationException("PE/COFF symbol lookup is not implemented");
    }
}
