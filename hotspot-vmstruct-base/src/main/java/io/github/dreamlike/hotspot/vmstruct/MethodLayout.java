package io.github.dreamlike.hotspot.vmstruct;

public record MethodLayout(
        long methodFlagsOffset,
        long methodInterpreterEntryOffset,
        long methodCodeOffset,
        long methodCompiledEntryOffset,
        long methodInterpretedEntryOffset) {

    public static MethodLayout load() {
        return Holder.INSTANCE;
    }

    private static MethodLayout create() {
        VmStructs vm = VmStructs.current();
        return new MethodLayout(
                // MethodFlags is not exported directly; this matches the current Method layout.
                vm.offset("Method", "_intrinsic_id") - Integer.BYTES,
                vm.offset("Method", "_i2i_entry"),
                vm.offset("Method", "_code"),
                vm.offset("Method", "_from_compiled_entry"),
                vm.offset("Method", "_from_interpreted_entry"));
    }

    private static final class Holder {
        private static final MethodLayout INSTANCE = create();
    }
}
