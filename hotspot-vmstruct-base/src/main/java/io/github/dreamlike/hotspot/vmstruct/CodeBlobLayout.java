package io.github.dreamlike.hotspot.vmstruct;

public record CodeBlobLayout(long codeOffset) {
    public static CodeBlobLayout load() {
        return Holder.INSTANCE;
    }

    private static CodeBlobLayout create() {
        return new CodeBlobLayout(VmStructs.current().offset("CodeBlob", "_code_offset"));
    }

    private static final class Holder {
        private static final CodeBlobLayout INSTANCE = create();
    }
}
