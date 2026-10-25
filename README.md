# MC 局域网开服（v2.2.0）

在手机上开 Minecraft Java 版局域网服务器：应用内下载 Java 与官方服务端、图形化改配置、
一键启停、实时看控制台。**数据目录固定为 `/storage/emulated/0/mcserver`。**

- 包名 `com.mclan.server`　版本 **2.2.0**　约 750 KB　Android 8.0+（arm64）
- 作者：**B 站 UID 102392702**（欢迎关注 → <https://space.bilibili.com/102392702>）
- 开源仓库：<https://github.com/Linege0910/mc-lan-server>
- 下载安装包：**[MC-LAN-Server-v2.2.0.apk](https://github.com/Linege0910/mc-lan-server/releases/download/v2.2.0/MC-LAN-Server-v2.2.0.apk)**
- 应用**不含**服务端本体，启动时从官方地址下载；运行需遵守
  [Minecraft EULA](https://aka.ms/MinecraftEULA)

---

## 一、第一次打开必看

1. 打开应用会弹窗要求你**授予「写入手机存储」权限**。
   - 不授权 → 存档 / 配置 / 日志都写不进去，**也无法用文件管理器导出存档**，
     换手机或升级版本时会丢档。
   - 授权后固定目录：`/storage/emulated/0/mcserver`
2. 到「设置 → 下载 Java 运行时」装 JDK（首次约 110MB，解压后约 250MB）：
   - 1.16.5 及更早 → Java 8（Termux 仓库已不提供）
   - 1.17 – 1.20.4 → Java 17
   - 1.20.5 – 1.21.x → Java 21
   - 1.22 / 26.x（含 26.3）→ Java 25
3. 到「状态 → 下载官网服务端」拉官方 server.jar（26.3 约 62MB）。
4. 点「启动服务器」，控制台出现 `Done (...)!` 即开服完成。
5. 同一 Wi-Fi 下，在 Minecraft 多人游戏里添加 `手机IP:25565`（状态页会显示地址）。

## 二、目录结构

```
/storage/emulated/0/mcserver/     ← 固定目录，文件管理器可直接访问
├── server.jar                    官方服务端
├── server.properties             配置（「配置」页可改，支持授予 OP）
├── eula.txt
├── world/                        存档（重点备份对象）
├── logs/、libraries/、versions/
├── ops.json                      OP 名单
├── java-17 / java-21 / java-25   JDK 可见副本（查看/备份用）
└── downloads/                    下载缓存
```

> 真正被执行的 JDK 在应用私有目录（`/sdcard` 挂载为 noexec，不能直接执行程序）。

## 三、内存上限

默认取设备总内存的一半，也可手动选（2G / 3G / 4G…）。16GB 手机 → 默认 8GB。
附加参数用「附加 JVM 参数」，例如 `-XX:+UseG1GC`。

## 四、保活（锁屏/后台不掉线）

设置 → **保活方案**，三档由轻到重，每一档都会先展示风险、由你确认后才执行。

1. **标准方案（推荐，零风险）**：加「电池优化白名单」。Doze 白名单是决定性因素；
   `PARTIAL_WAKE_LOCK` 在 Doze 中被忽略，光靠前台服务扛不住。
2. **ADB 方案**：应用内复制 adb 命令，电脑执行一次，重启后仍有效。
3. **Root 方案（Magisk / KernelSU）**：自动加白名单、可全局关 Doze、
   写开机脚本 `/data/adb/service.d/mclan_keepalive.sh`。风险：全局关 Doze 会提高
   其他应用待机耗电；root 应用被利用等于整机失守；部分银行/游戏会检测 root。

## 五、root 后台守护模式（可选，⚠ 高风险）

勾选「以 root 后台方式运行服务端」时会先弹窗，让你逐条确认：

1. 服务端以 **root 系统进程** 运行，不再是应用的一部分；
2. **关闭应用、划掉后台，服务端仍然继续运行**；
3. **本应用无法停止它** —— 只有重启手机才能真正关掉；
4. 想正常关服：先在「配置 → 授予 OP」写入玩家名，让玩家进服后执行 `/stop`
   （服务器会保存存档后退出）；
5. root 跑出来的存档属主是 root，应用之后可能改不了配置。

> 兼容性：部分机型（Magisk 的 su 不能执行应用私有目录里的程序）会**自动退回普通模式**，
> 该机型上此选项不可用。实测即使 `setenforce 0` 也无法绕过，属于 root 权限模型限制。

## 六、常见问题

| 现象 | 说明 |
| --- | --- |
| `JNA classes` / `noexec` 警告 | 无害。`/storage` 是 noexec，服务端读不到 CPU 硬件信息而已 |
| `SERVER IS RUNNING IN OFFLINE/INSECURE MODE` | 正常，离线局域网不需要正版校验 |
| 控制台没有日志 | 普通模式来自进程管道；root 守护模式来自 `console.log` |
| 熄屏掉线 | 没进 Doze 白名单，按「四、保活」处理 |
| 想备份存档 | 复制 `mcserver/world` 整个目录即可 |
| 解压 JDK 时进度条不动 | 已改为不确定态动画 + “已写出 N 个文件”，属正常等待 |

## 七、从源码构建

```bash
# 需要 JDK 21 与 Android SDK（compileSdk 37）
./gradlew :app:assembleRelease
```

签名配置见 `app/build.gradle.kts`（`keystore.jks`，仅用于自签名安装）。
