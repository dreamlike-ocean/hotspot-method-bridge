# VMStructs 可扩展能力

本文记录当前 JDK 25 HotSpot 通过 VMStructs 暴露出来、可以在本项目后续支持的能力。当前项目已经使用 `gHotSpotVMStructs` 读取字段偏移和静态字段地址；还能继续扩展读取 `gHotSpotVMTypes`、`gHotSpotVMIntConstants`、`gHotSpotVMLongConstants`。

本清单刻意不以稳定性或安全性为边界，只记录技术上可以尝试的方向。

## 1. 全 JVM class graph 枚举

从 `ClassLoaderDataGraph::_head` 进入 `ClassLoaderData` 链表，再沿 `ClassLoaderData::_klasses` 枚举所有已加载类。这个路径可以看到普通反射 API 不容易覆盖的 hidden class、generated class 和不同 class loader 下的 Klass。

可继续读取 `InstanceKlass::_methods`、`_constants`、`_annotations`、`_local_interfaces`、`_transitive_interfaces`，做一个进程内 class browser。

## 2. 对象解剖器

从任意 Java 对象拿到 oop 地址后，读取 `oopDesc::_mark` 和 `oopDesc::_compressed_klass`。结合 `CompressedKlassPointers::_base/_shift` 解出 Klass 地址，再读 `Klass::_name` 和 `Symbol::_body` 得到 JVM 内部类名。

mark word 可以通过 `markWord::*` long constants 解出 hash、age、lock state、monitor state。

## 3. 对象变形和类型混淆

直接改对象头中的 `_compressed_klass`，可以把一个对象伪装成另一个 Klass。这个方向能测试 HotSpot 对对象布局、JIT 类型假设、GC 扫描和 verifier 之间的边界。

风险很高：字段布局、oop map、GC barrier 和 C2 type profile 很容易被破坏。

## 4. Method 元数据浏览和篡改

`Method::_constMethod`、`_method_data`、`_method_counters`、`_code`、`_from_compiled_entry`、`_from_interpreted_entry`、`_i2i_entry` 都已经暴露。

可以展示 Method 地址、方法名、签名、max stack、max locals、参数槽数、当前 nmethod、compiled/interpreted entry。也可以直接改 entry，把一个 Java 方法转接到 raw code、另一个 Method 的 compiled entry 或解释器入口。

## 5. 字节码 patch

通过 `Method::_constMethod` 找到 `ConstMethod`，再根据 `ConstMethod::_code_size` 和 `ConstMethod` 内部布局定位 bytecode 区域，运行期修改方法字节码。

解释执行路径会更容易观察到效果；已经生成的 nmethod 需要配合清理 `_code`、触发 deopt 或让调用方重新解析。

## 6. vtable / itable patch

`vtableEntry::_method` 已暴露。沿 `Klass` 的 vtable 区域可以改某个虚方法分发表项，让 virtual call 指向另一个 Method。

这个方向比改单个 Method entry 更接近动态派发层，能影响同类对象的虚调用行为。

## 7. ConstantPool / ConstantPoolCache patch

`ConstantPool::_tags`、`_cache`、`_resolved_klasses` 以及 `ConstantPoolCache::_resolved_field_entries`、`_resolved_method_entries`、`_resolved_indy_entries` 已暴露。

可以改已解析字段、方法、类和 invokedynamic 目标，用来实验 lambda、MethodHandle、indy bootstrap 和解析缓存行为。

## 8. nmethod / CodeBlob 浏览和机器码热 patch

`CodeBlob::_kind`、`_name`、`_size`、`_code_offset`、`_data_offset`、`_oop_maps`，以及 `nmethod::_entry_offset`、`_verified_entry_offset`、`_compile_id`、`_comp_level`、`_state` 等字段已暴露。

可以定位 JIT code begin、verified entry、stub、exception handler、deopt handler，并直接 patch 机器码。这个能力可用于把 callsite 替换成 `nop`、`prefetch`、短跳转或自定义探针。

## 9. JIT profile 操控

`MethodData`、`MethodCounters`、`InvocationCounter` 相关字段已暴露。可以读取或修改 invocation counter、backedge counter、throwout counter 和 profile data。

这个方向可以诱导编译、影响 inline 决策、改变 type profile，也可以制造错误的 speculative optimization 输入。

## 10. deopt / debug info 解析和篡改

`PcDesc`、`ImmutableOopMapSet`、`ImmutableOopMapPair`、`ImmutableOopMap`、`OopMapValue`、`CompressedStream` 已暴露。

可以从 nmethod 的 PC 反查 Java scope、bci、locals、oop map。反向修改这些结构可以测试 deoptimizer 和 GC 对 compiled frame metadata 的依赖。

## 11. 线程列表和 JavaThread 操控

`ThreadsSMRSupport::_java_thread_list`、`ThreadsList::_threads/_length`、`JavaThread::_threadObj`、`_vthread`、`_thread_state`、`_stack_base`、`_stack_size`、`_exception_oop`、`_suspend_flags` 等字段已暴露。

可以做进程内线程 dump、虚拟线程 carrier 映射、栈范围读取、pending exception 观察。写这些字段可以测试 thread state protocol 和异常投递路径。

## 12. TLAB 手工分配对象

`Thread::_tlab` 和 `ThreadLocalAllocBuffer::_start/_top/_end/_desired_size` 已暴露。

可以在当前线程 TLAB 内自己 bump pointer，写 mark word、klass pointer 和字段内容，伪造一个 Java 对象。后续需要配合对象布局、对齐、compressed oops 和 GC barrier。

## 13. 锁和 monitor 操控

`ObjectMonitor::_object`、`_owner`、`_contentions`、`_waiters`、`_recursions`，以及 `ObjectSynchronizer::_in_use_list` 已暴露。

可以枚举 inflated monitors、观察锁竞争、定位当前 owner。写这些字段可以测试 monitor enter/exit、wait/notify 和 deflation 的边界。

## 14. GC 内部视图和干预

shared GC 表暴露了 `Universe::_collectedHeap`、`CollectedHeap::_reserved`、`_total_collections`、`BarrierSet::_barrier_set`、`CardTable::_byte_map`、`CardTableBarrierSet::_card_table` 等字段。

具体 GC 还会导出自己的结构：G1 暴露 region table 和 monitoring support；ZGC 暴露 page table、colored pointer masks、forwarding table；Shenandoah 暴露 region state、free set 和 committed size。

## 15. JVM flags 活体读写

`JVMFlag::flags`、`numFlags`、`JVMFlag::_type`、`_name`、`_addr`、`_flags` 已暴露。

可以枚举所有 `-XX` flag、读取 origin、读取或修改 flag 背后的真实值。部分 flag 在运行期修改会马上影响行为，部分会制造启动期状态和运行期状态不一致。

## 16. PerfMemory / jvmstat 读写

`PerfMemory::_start/_end/_top/_capacity/_prologue`，以及 `PerfDataPrologue`、`PerfDataEntry` 字段已暴露。

可以在进程内解析 jstat 使用的 perf counters，也可以写 counter 来伪造外部监控看到的数据。

## 17. JNI handle / OopHandle 操作

`JNIHandles::_global_handles`、`_weak_global_handles`、`JNIHandleBlock::_handles/_top/_next`，以及 `OopHandle::_obj` 已暴露。

可以扫描全局 JNI 引用、弱引用和 VM 内部 oop handle。写 handle 可以把某个 native/VM handle 指向另一个 oop。

## 18. JVMTI 能力位篡改

`JvmtiExport` 部分静态能力位通过 JVMTI struct 暴露，例如 `_can_access_local_variables`、`_can_hotswap_or_post_breakpoint`、`_can_post_on_exceptions`、`_can_walk_any_space`。

可以实验打开或伪造 JVMTI capability 状态，但后端不一定完成了对应能力的初始化。

## 建议落地顺序

1. 扩展 `VmStructs` 为通用表读取器，支持 field/type/int constant/long constant。
2. 增加只读 inspector：object、klass、method、nmethod、code blob。
3. 增加 class graph 和 CodeCache browser。
4. 增加可选的 patch API：Method entry、ConstantPoolCache、vtable、nmethod code。
5. 最后再碰 TLAB 手工造对象、GC/monitor/thread state 写入。
