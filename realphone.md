迁移到真机主要要做这些：

1. **真机 root**
   - [x] 解锁 bootloader。
   - [x] 安装 Magisk 或等价 root。
   - [x] 确认 app 能直接跑 `su -c id`，不再依赖 emulator 的 adb root bridge。
   - [x] 注意：解锁 bootloader 通常会清空手机数据。

2. **确认内核支持 eBPF/ftrace** 
   真机 kernel 至少要有：
   - [x] `CONFIG_BPF`
   - [x] `CONFIG_BPF_SYSCALL`
   - [x] `CONFIG_BPF_JIT`
   - [x] `CONFIG_FTRACE`
   - [x] `CONFIG_FTRACE_SYSCALLS`
   - [x] `CONFIG_KPROBES`
   - [x] `CONFIG_UPROBES`
   - [x] tracefs: `/sys/kernel/tracing`

   如果没有 `CONFIG_FTRACE_SYSCALLS`，我们现在抓 `sendto/recvfrom/openat` 这类 syscall tracepoint 会缺一大块，需要换 kernel 或改成 kprobe/其他 tracepoint。

3. **准备 arm64 工具**
   emulator 现在是 x86_64，真机基本是 arm64，所以要重新准备：
   - [x] `bpftrace` arm64 Android 版
   - [x] `bpftool` arm64 Android 版
   - [x] `libmaple_engine.so` arm64-v8a
   - [x] APK 里已经会编 `arm64-v8a` 的 `maple-jni`，但 MAPLE engine 那个大 native lib 也要 arm64 编译进 APK 或部署到 app 可加载位置。(来自 **Java_Herobrine** 的注记：位于/data/local/tmp/libmaple_engine.so)

4. **模型部署**
   - 把 GGUF 放到真机，比如 `/sdcard/MEMO/models/Qwen3.5-0.8B-Q4_K_M.gguf`(来自 **Java_Herobrine** 的注记：java.io.FileNotFoundException: No gguf files here)
   - app 启动后复制到 app 私有目录：
     `/sdcard/Android/data/com.memoos/files/models/`
   - 这样 JNI 能直接读，不受 Android scoped storage 限制。

5. **安装 APK 并授权**
   - 安装 MEMO-Appflow APK。
   - 给通知权限。
   - Magisk 弹 root 授权时允许这个 app。
   - 确认 app 能执行：
     - [x] `bpftrace`
     - [x] `bpftool`
     - [x] `cat /proc/meminfo`
     - [x] `dumpsys`
     - [x] `am start`
     - [x] `service list`

6. **把 emulator bridge 去掉**
   现在 emulator 因为 `/system/xbin/su` 权限奇怪，用了 adb root bridge。真机上不能靠这个，应该走正常 Magisk `su -c ...`。代码里已有直接 `su` 路径，真机主要是验证它能通。

7. **真机采集验证**
   要拿真实动作跑：
   - 解锁手机
   - 打开聊天 app
   - 拍照
   - 看视频
   - 支付/扫码
   - 滚动浏览

   看 app 内是否能显示 Top-3 应用，并且日志里有 eBPF evidence、MAPLE result、executed actions。

一句话：真机需要 root + 支持 eBPF/ftrace 的 kernel + arm64 工具/引擎 + 模型部署。代码产品逻辑不用重新设计，主要是把 emulator 的 x86_64/custom-kernel/root-bridge 环境换成真机的 arm64/Magisk/kernel 能力。
