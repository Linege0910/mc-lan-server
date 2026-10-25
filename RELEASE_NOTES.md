## MC 局域网开服 v2.2.0

在手机上开 Minecraft Java 版局域网服务器。应用不含服务端本体，启动时从官方地址下载。

### 下载

- `MC局域网开服-v2.2.0.apk`（约 760 KB，Android 8.0+，arm64）
- 校验：`SHA256 = 89E9FE933F378F6AAD4F7BBE6BBA252DDA2EE78E3BE0E9B88867809F3384B7CC`

### 这个版本的重点

- **首次启动强制引导存储权限**：明确提示不授权会导致存档无法导出；目录固定 `/storage/emulated/0/mcserver`。
- **作者信息**：B 站 UID 102392702，设置页新增「关于」入口。
- **root 后台守护模式**（高风险，需逐条确认）：服务端以 root 系统进程运行，关闭应用后仍运行，
  应用无法停止，只能重启手机；正常关服请先在「配置 → 授予 OP」写入玩家名，让玩家进服执行 `/stop`。
- **配置页新增「授予 OP」**：直接写 `ops.json`（离线 UUID 由玩家名推导，与原版一致），可从 `usercache.json` 选人。

### 修复与改进（本版累计）

- 修复启动闪退：`server.properties` 被 root 改成 `root 660` 后 `configGet()` 抛 `EACCES`，现已全部容错。
- 修复「一直显示运行中、结束失败」：进程探测会把自己执行的 `su` 命令误判为服务端；现在只认命令行首个参数为 `/java` 的进程，并对 pid 文件做核对。
- 修复「下载 Java 进度条不动」：状态探测与下载共用单线程导致任务饿死（改为独立线程 + 端口/pid 快速路径）；解压阶段补上进度（不确定态动画 + 已写出文件数）；界面不再每 3 秒整体重建。
- 修复 root 模式启动卡死/闪退：`su` 调用移出主线程，`su` 读取加超时，改用 `.server.pid` 兜底。
- root 守护模式改用 `/storage` 镜像 JDK 复制到 `/data/local/tmp/mclan` 后执行；Magisk 无法执行应用私有目录程序时自动退回普通模式。

### 已验证环境

Redmi Note 9 Pro（MIUI V140 / Android 12 / Magisk V140，arm64-v8a，8GB）：
普通模式下 26.3 启动 `Done (1.855s)!`、25565 正常监听；点停止后干净退出（退出码 0）。

### 已知限制

- 基岩版服务端仅提供 Windows / Ubuntu x86_64，Android 无法直接运行，只提供下载与配置编辑。
- Java 8（1.16.5 及更早）Termux 仓库已不再提供。
- 部分机型 root 守护模式不可用（`setenforce 0` 也无法绕过，属 root 权限模型限制）。

### 注意

服务端运行需遵守 [Minecraft EULA](https://aka.ms/MinecraftEULA)。本仓库不附带任何 Mojang 的资源文件。
