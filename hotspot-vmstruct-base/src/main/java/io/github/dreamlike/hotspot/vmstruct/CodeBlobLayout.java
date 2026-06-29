package io.github.dreamlike.hotspot.vmstruct;

public record CodeBlobLayout(long codeOffset) {
    public static CodeBlobLayout load() {
        return new CodeBlobLayout(new VmStructs().offset("CodeBlob", "_code_offset"));
    }
}
