package io.github.dreamlike.hotspot.vmstruct;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class HotSpotMethodMetadata {
    private static final int MAX_CLASS_LOADER_DATA = 1_000_000;
    private static final int MAX_KLASSES_PER_LOADER = 1_000_000;

    private final long classLoaderDataGraphHeadAddress;
    private final long classLoaderDataNextOffset;
    private final long classLoaderDataKlassesOffset;
    private final long klassNextLinkOffset;
    private final long klassNameOffset;
    private final long instanceKlassMethodsOffset;
    private final long arrayLengthOffset;
    private final long methodArrayDataOffset;
    private final long methodConstMethodOffset;
    private final long constMethodConstantsOffset;
    private final long constMethodNameIndexOffset;
    private final long constMethodSignatureIndexOffset;
    private final long constantPoolHolderOffset;
    private final long constantPoolSize;
    private final long symbolLengthOffset;
    private final long symbolBodyOffset;
    private final ConcurrentMap<MethodKey, Long> methodCache = new ConcurrentHashMap<>();

    private HotSpotMethodMetadata() {
        VmStructs vm = VmStructs.current();
        classLoaderDataGraphHeadAddress = vm.staticAddress("ClassLoaderDataGraph", "_head");
        classLoaderDataNextOffset = vm.offset("ClassLoaderData", "_next");
        classLoaderDataKlassesOffset = vm.offset("ClassLoaderData", "_klasses");
        klassNextLinkOffset = vm.offset("Klass", "_next_link");
        klassNameOffset = vm.offset("Klass", "_name");
        instanceKlassMethodsOffset = vm.offset("InstanceKlass", "_methods");
        arrayLengthOffset = vm.offset("Array<int>", "_length");
        methodArrayDataOffset = vm.offset("Array<Method*>", "_data");
        methodConstMethodOffset = vm.offset("Method", "_constMethod");
        constMethodConstantsOffset = vm.offset("ConstMethod", "_constants");
        constMethodNameIndexOffset = vm.offset("ConstMethod", "_name_index");
        constMethodSignatureIndexOffset = vm.offset("ConstMethod", "_signature_index");
        constantPoolHolderOffset = vm.offset("ConstantPool", "_pool_holder");
        constantPoolSize = vm.typeSize("ConstantPool");
        symbolLengthOffset = vm.offset("Symbol", "_length");
        symbolBodyOffset = vm.offset("Symbol", "_body");
    }

    public static HotSpotMethodMetadata load() {
        return Holder.INSTANCE;
    }

    public long methodAddress(Method method) {
        Objects.requireNonNull(method, "method");
        MethodKey key = new MethodKey(method.getDeclaringClass(), method.getName(), descriptor(method));
        return methodCache.computeIfAbsent(key, this::findUniqueMethod);
    }

    public String methodSignature(long method) {
        if (method == 0) {
            return null;
        }
        String holderName = methodHolderName(method);
        String methodName = methodName(method);
        String methodDescriptor = methodDescriptor(method);
        if (holderName != null) {
            holderName = holderName.replace('/', '.');
        }
        if (holderName == null && methodName == null && methodDescriptor == null) {
            return null;
        }
        return nullToUnknown(holderName) + "::" + nullToUnknown(methodName) + nullToUnknown(methodDescriptor);
    }

    public String methodHolderName(long method) {
        long constants = constants(method);
        if (constants == 0) {
            return null;
        }
        long holder = HotSpotMemory.getAddress(constants + constantPoolHolderOffset);
        return holder == 0 ? null : klassName(holder);
    }

    public String methodName(long method) {
        long constMethod = constMethod(method);
        long constants = constMethod == 0 ? 0 : HotSpotMemory.getAddress(constMethod + constMethodConstantsOffset);
        if (constants == 0) {
            return null;
        }
        int nameIndex = HotSpotMemory.getUnsignedShort(constMethod + constMethodNameIndexOffset);
        return symbolString(symbolAt(constants, nameIndex));
    }

    public String methodDescriptor(long method) {
        long constMethod = constMethod(method);
        long constants = constMethod == 0 ? 0 : HotSpotMemory.getAddress(constMethod + constMethodConstantsOffset);
        if (constants == 0) {
            return null;
        }
        int signatureIndex = HotSpotMemory.getUnsignedShort(constMethod + constMethodSignatureIndexOffset);
        return symbolString(symbolAt(constants, signatureIndex));
    }

    public static String descriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : method.getParameterTypes()) {
            descriptor.append(parameterType.descriptorString());
        }
        return descriptor.append(')').append(method.getReturnType().descriptorString()).toString();
    }

    //  1. 从 ClassLoaderDataGraph::_head 开始遍历所有 ClassLoaderData。
    //  2. 每个 ClassLoaderData 读 _klasses，再沿 Klass::_next_link 遍历这个 loader 下挂着的 Klass。
    //  3. 读 Klass::_name，和 method.getDeclaringClass() 算出来的 internal name 比，比如 java/lang/String。
    //  4. 命中 holder 后读 InstanceKlass::_methods。
    //  5. 遍历 Array<Method*>。
    //  6. 对每个 Method* 读：
    //      - Method::_constMethod
    //      - ConstMethod::_constants
    //      - ConstMethod::_name_index
    //      - ConstMethod::_signature_index
    //      - 从 ConstantPool + sizeof(ConstantPool) + index * addressSize 读 Symbol*
    //
    //  7. 把 Symbol 解成 method name 和 descriptor，和 Java 反射 Method 的 getName() + descriptor 比。
    //  8. 唯一匹配就返回 Method*；0 个或多个都抛异常。
    private long findUniqueMethod(MethodKey key) {
        String holderName = internalName(key.holder);
        long result = 0;
        int matches = 0;
        int loaderGuard = 0;
        for (long cld = HotSpotMemory.getAddress(classLoaderDataGraphHeadAddress);
             cld != 0 && loaderGuard++ < MAX_CLASS_LOADER_DATA;
             cld = HotSpotMemory.getAddress(cld + classLoaderDataNextOffset)) {
            int klassGuard = 0;
            for (long klass = HotSpotMemory.getAddress(cld + classLoaderDataKlassesOffset);
                 klass != 0 && klassGuard++ < MAX_KLASSES_PER_LOADER;
                 klass = HotSpotMemory.getAddress(klass + klassNextLinkOffset)) {
                if (!holderName.equals(klassName(klass))) {
                    continue;
                }
                long candidate = findMethodInKlass(klass, key.name, key.descriptor);
                if (candidate != 0) {
                    result = candidate;
                    matches++;
                }
            }
        }

        if (matches == 1) {
            return result;
        }
        if (matches == 0) {
            throw new IllegalArgumentException("HotSpot Method* not found: "
                    + key.holder.getName() + "::" + key.name + key.descriptor);
        }
        //todo 这里需要一个不绕过 GC barrier 的 Class<?> -> Klass 精确匹配方案。
        throw new IllegalStateException("ambiguous HotSpot Method* for "
                + key.holder.getName() + "::" + key.name + key.descriptor
                + "; holder name is present in multiple ClassLoaderData nodes");
    }

    private long findMethodInKlass(long klass, String name, String descriptor) {
        long methods = HotSpotMemory.getAddress(klass + instanceKlassMethodsOffset);
        if (methods == 0) {
            return 0;
        }
        int length = HotSpotMemory.getInt(methods + arrayLengthOffset);
        if (length <= 0) {
            return 0;
        }
        long data = methods + methodArrayDataOffset;
        for (int i = 0; i < length; i++) {
            long method = HotSpotMemory.getAddress(data + (long) i * HotSpotMemory.ADDRESS_SIZE);
            if (method != 0 && name.equals(methodName(method)) && descriptor.equals(methodDescriptor(method))) {
                return method;
            }
        }
        return 0;
    }

    private long constMethod(long method) {
        return method == 0 ? 0 : HotSpotMemory.getAddress(method + methodConstMethodOffset);
    }

    private long constants(long method) {
        long constMethod = constMethod(method);
        return constMethod == 0 ? 0 : HotSpotMemory.getAddress(constMethod + constMethodConstantsOffset);
    }

    private String klassName(long klass) {
        if (klass == 0) {
            return null;
        }
        return symbolString(HotSpotMemory.getAddress(klass + klassNameOffset));
    }

    private long symbolAt(long constantPool, int index) {
        return HotSpotMemory.getAddress(constantPool + constantPoolSize + (long) index * HotSpotMemory.ADDRESS_SIZE);
    }

    private String symbolString(long symbol) {
        if (symbol == 0) {
            return null;
        }
        int length = HotSpotMemory.getUnsignedShort(symbol + symbolLengthOffset);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = HotSpotMemory.getByte(symbol + symbolBodyOffset + i);
        }
        return decodeModifiedUtf8(bytes);
    }

    private static String internalName(Class<?> type) {
        if (type.isArray()) {
            return type.descriptorString();
        }
        String descriptor = type.descriptorString();
        if (descriptor.length() >= 2 && descriptor.charAt(0) == 'L' && descriptor.charAt(descriptor.length() - 1) == ';') {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return type.getName().replace('.', '/');
    }

    private static String decodeModifiedUtf8(byte[] bytes) {
        byte[] prefixed = new byte[bytes.length + Short.BYTES];
        prefixed[0] = (byte) (bytes.length >>> Byte.SIZE);
        prefixed[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, prefixed, Short.BYTES, bytes.length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(prefixed))) {
            return input.readUTF();
        } catch (IOException | RuntimeException e) {
            return new String(bytes, UTF_8);
        }
    }

    private static String nullToUnknown(String value) {
        return value == null ? "<unknown>" : value;
    }

    private record MethodKey(Class<?> holder, String name, String descriptor) {
    }

    private static final class Holder {
        private static final HotSpotMethodMetadata INSTANCE = new HotSpotMethodMetadata();
    }
}
