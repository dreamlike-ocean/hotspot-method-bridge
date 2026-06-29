package io.github.dreamlike.hotspot.vmstruct;

public record NarrowOopEncoding(long base, int shift) {
    public static NarrowOopEncoding load() {
        return Holder.INSTANCE;
    }

    private static NarrowOopEncoding create() {
        VmStructs vm = VmStructs.current();
        return new NarrowOopEncoding(
                HotSpotMemory.getAddress(vm.staticAddress("CompressedOops", "_base")),
                HotSpotMemory.getInt(vm.staticAddress("CompressedOops", "_shift")));
    }

    public long decodeNarrowOop(long value) {
        return value == 0 ? 0 : base + (value << shift);
    }

    public long decodeReference(long value, int referenceSize) {
        return switch (referenceSize) {
            case Integer.BYTES -> decodeNarrowOop(value);
            case Long.BYTES -> value;
            default -> throw new IllegalArgumentException("unsupported reference size: " + referenceSize);
        };
    }

    private static final class Holder {
        private static final NarrowOopEncoding INSTANCE = create();
    }
}
