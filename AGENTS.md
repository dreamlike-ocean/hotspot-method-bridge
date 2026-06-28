# AGENTS.md

1. 保持项目最小化，不引入非必要依赖。
2. Java 代码使用 JDK 25，优先用标准库和当前 HotSpot 导出的 VMStructs。
3. 不把 `-Xcomp`、`-Xbatch` 或 warmup loop 作为方案前提。
4. 修改后至少运行 `./run.sh` 验证。
