package io.github.dreamlike.hotspot.methodbridge;

import io.github.dreamlike.hotspot.vmstruct.HotSpotMethodMetadata;

import java.lang.reflect.Method;

final class MethodAddressResolver {
    private static final HotSpotMethodMetadata METADATA = HotSpotMethodMetadata.load();

    private MethodAddressResolver() {
    }

    static long methodAddress(Method method) {
        return METADATA.methodAddress(method);
    }
}
