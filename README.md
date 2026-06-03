# TreadmillFTMS — 项目进展文档

> 最后更新：2026-06-03
> 目标：让 Sole F63 Max 跑步机（运行 Seewo/希沃 Android 系统）支持 FitShow、Zwift 等标准 FTMS 健身 App

---

## 项目概述

跑步机型号：**Sole F63 Max**，内置 Rockchip RK3 Android 系统（希沃 Seewo 定制 ROM），通过 ADB + scrcpy 连接。

目标：在跑步机 Android 上运行一个后台服务，将跑步机数据桥接为标准 BLE FTMS（Fitness Machine Service）外设，使手机 App（FitShow、Zwift 等）可以连接并读取速度/坡度，以及发送控制指令。

---

## 已完成工作

### 1. 硬件通信逆向

- 串口：`/dev/ttyS3`，2400 baud，8N1
- 帧格式：`0xFF [payload] [CRC_lo] [CRC_hi] 0xFE`
- CRC 算法：16-entry nibble-based 查表法（来自 `DecodeUtil.java`）

```python
b = [0, 4225, 8450, 12675, 16900, 21125, 25350, 29575,
     33800, 38025, 42250, 46475, 50700, 54925, 59150, 63375]

def crc(data, length):
    i2 = 0xFFFF
    for i in range(length):
        i5 = b[(i2 & 15) ^ (data[i] & 15)] ^ (i2 >> 4)
        i2 = b[(i5 & 15) ^ (data[i] >> 4)] ^ (i5 >> 4)
    return i2
```

已知数据包（跑步机 → Android）：

| 子命令字节 | 含义 | 数据格式 |
|---|---|---|
| 0x24 (36) | 当前速度 | 2字节 LE，单位 0.1 km/h |
| 0x59 (89) | 步数 | 2字节 LE |
| 0x2A (42) | 坡度 | 1字节 |
| 0x5B (91) | 错误状态 | 1字节 |

### 2. AIDL 接口逆向（com.seewo.thardwareservice）

APK 路径：`/system/priv-app/SoleBrushlessThardwareService/SoleBrushlessThardwareService.apk`

ITreadmillService 关键事务码（ITREADMILL_DESCRIPTOR = "com.seewo.libthardware.ITreadmillService"）：

| 事务码 | 方法 | 说明 |
|---|---|---|
| 4 | a(double) | 设置速度（km/h），已确认有效 |
| 5 | a(int) | 设置坡度（0-15） |
| 10 | a(IRunningObserver) | 注册数据回调，已确认有效 |
| 11 | b(IRunningObserver) | 取消注册回调 |
| 22 | l() | 触发坡度校准（非启动！勿用） |

IRunningObserver 回调（IOBSERVER_DESCRIPTOR = "com.seewo.libthardware.module.treadmill.IRunningObserver"）：

| 事务码 | 回调 | 说明 |
|---|---|---|
| 2 | a(double speed) | 速度变化（km/h） |
| 3 | a(int slope) | 坡度变化 |
| 4 | a() | 跑步机启动 |
| 5 | b() | 跑步机停止 |

### 3. 启动/暂停/停止机制

- 从停止状态启动：必须在跑步机屏幕手动按启动（安全设计，setSpeed() 在 idle 状态被状态机拦截）
- 暂停：发送广播 com.seewo.trunning.to_thardwareservice，extra action="pause"
- 恢复：发送广播，extra action="resume_to_run"
- 来源：k.java（TreadmillControllerImpl）中的 BroadcastReceiver class c

### 4. Android App（TreadmillFTMS）

包名：com.treadmill.ftms

功能：
- 绑定 thardwareservice，注册 IRunningObserver 接收速度/坡度数据
- 每秒发送一次 BLE 通知（防止 Zwift 等 App 超时归零）
- 开机自启（BootReceiver）
- BLE GATT 服务器：FTMS（0x1826）+ FFF0 私有服务

已验证可用：
- Zwift 连接成功，实时速度正常显示
- 速度调节（通过网页工具）有效
- 暂停/恢复广播有效
- FitShow 尚未验证（最新广播包格式已更新，待测试）

### 5. BLE 广播包格式（FitShow 兼容版，最新，尚未测试）

根据真实 TR1200 广播包逆向，FitShow 过滤条件：
- Company ID: 0x0419（必须）
- 服务 UUID：0xFFF0 + 0x1826（必须同时存在）
- FTMS Service Data：0x1826 + 01 00 01
- 设备名：必须以 "TR" 开头（当前为 "TR1200-T"）

完整广播包结构：
  02 01 06                              Flags
  0D FF 19 04 6D 00 [mac0 mac1 mac2] 3B 00 00 00 07   Manufacturer Data (Company 0x0419)
  05 03 F0 FF 26 18                     Complete 16-bit UUIDs: FFF0 + 1826
  06 16 26 18 01 00 01                  FTMS Service Data (treadmill)
  09 09 54 52 31 32 30 30 2D 54         Scan Response: "TR1200-T"

⚠️ 此格式部署后尚未在 FitShow 中验证，这是下一个 session 的第一件事。

### 6. 真实 TR1200 完整 GATT profile（2026-06-03 用 nRF Connect 抓取）

**重大发现：真机蓝牙模块是 FitShow 自家的 FS-BT-D2**（厂商字段直接写明）。
桥接 App 现已按下表做成逐字节克隆。

Device Information (0x180A)：
| 特征 | UUID | 真实值 |
|---|---|---|
| Manufacturer Name | 0x2A29 | `FITSHOW` |
| Model Number | 0x2A24 | `FS-BT-D2` |
| Serial Number | 0x2A25 | `FS231221001` |
| Hardware Revision | 0x2A27 | `1.0` |
| Software Revision | 0x2A28 | `1.3.3` |

Fitness Machine (0x1826)：
| 特征 | UUID | 真实值 |
|---|---|---|
| Fitness Machine Feature | 0x2ACC | Features=`0x000056DC`, Target=`0x0000000F` → bytes `DC 56 00 00 0F 00 00 00` |
| Supported Speed Range | 0x2AD4 | 1.0–16.0 km/h, step 0.1 → `64 00 40 06 0A 00` |
| Supported Inclination Range | 0x2AD5 | 0.0–15.0%, step 1.0 → `00 00 96 00 0A 00` |
| Supported Resistance Range | 0x2AD6 | 0.0–25.5, step 0.1 → `00 00 FF 00 01 00` |
| Supported Power Range | 0x2AD8 | 10–9999 W, step 10 → `0A 00 0F 27 0A 00` |
| Supported HR Range | 0x2AD7 | 0–250 bpm, step 1 → `00 FA 01` |
| Training Status | 0x2AD3 | NOTIFY+READ，初值 `00 01` (Idle) |
| Treadmill Data | 0x2ACD | NOTIFY，含 距离/坡度/能量/心率/时间 多字段 |
| FMS Status / Control Point | 0x2ADA / 0x2AD9 | 同标准 FTMS |

FFF0 私有服务（FS-BT 协议）：
| 特征 | UUID | 属性 | 备注 |
|---|---|---|---|
| FFF1 | 0xFFF1 | NOTIFY | 初值 `02 51 00 51 03`（FS 帧：`02`+cmd+data+XOR校验+`03`） |
| FFF2 | 0xFFF2 | WRITE NO RESPONSE | FitShow 下行指令口（之前桥接缺失！已补） |

✅ 以上所有值已写入 FtmsBridgeService.java，桥接现在是真机的逐字节克隆。

⚠️ 仍未确定 FitShow 走标准 FTMS 还是 FFF0 私有协议——需抓 FitShow↔真机 的 btsnoop 才能定论。

### 7. FitShow 私有协议（运动秀跑步机协议 v1.1）已完整实现

来源：GitHub `bigkrys/fitshow-device-protocol`，全文存于 `FitShow_Protocol_Reference.txt`。
观测帧 `02 51 00 51 03` 已确认 = SYS_STATUS(0x51) 返回 STATUS_NORMAL(待机)。

帧格式：`02 [CMD] [DATA…] [FCS] 03`，FCS = CMD+DATA 各字节异或，小端。

桥接现已在 FFF1(notify)/FFF2(write) 上实现完整指令处理：
| 指令 | 码 | 桥接行为 |
|---|---|---|
| SYS_INFO | 0x50 | 返回 机型/速度/坡度/里程参数 |
| SYS_STATUS | 0x51 | 返回 待机/运行/暂停 + 速度/坡度/时间/距离/卡路里/步数 |
| SYS_CONTROL | 0x53 | TARGET→调速/调坡, START/PAUSE/STOP→对应 AIDL/广播 |
| SYS_DATA | 0x52 | 返回运动量/运动信息 |

代码位置：FtmsBridgeService.java 的 `handleFff2Write` 及 respondSysInfo/respondSysStatus/handleSysControl/respondSysData。

⚠️ **唯一待补：INFO_MODEL 返回的「品牌+机型」16位代码**（FitShow 识别设备的钥匙）。
当前为占位值 `FS_BRAND_CODE=0x0001 / FS_MODEL_CODE=0x0001`。
获取真实值的两种方式：
1. nRF 连真机：订阅 FFF1 → 往 FFF2 写 `02 50 00 02 03 04 55 03` → 读 FFF1 的 `50 00 [品牌LE] [机型LE] …`
2. logcat：FitShow 连桥接时看 `FFF2 RX:` / 若识别失败，从 FitShow 端反推它期望的机型码
拿到后替换这两个常量重新编译即可。

### 8. FitShow 实机联调结论（2026-06-03，新跑步机 RK3_TREADMILL_QH）

✅ **FitShow 已可用,速度与坡度双向联动正常。**

关键结论:
- **FitShow 走的是标准 FTMS(0x1826 Control Point),不是 FFF0 私有协议。** 全程只见 Control Point opCode,无 FFF2 写入。FFF0 私有协议实现保留备用,机型码占位即可。
- **transact(22) 确认是「坡度校准」而非启动**(日志铁证:一调用就坡度上抬 + setSpeed 返回 -6 持续30秒)。已彻底禁用 treadmillStart() 里的 transact(22) 调用。
- FitShow 控制流:连接→op0(请求控制)→op7(Start)→op2(调速)/op3(调坡)→op8(停止)。
  - op7 Start:已在跑时视为空操作(不再误触发校准);停止态硬件不支持软启动。
- 修复的两个编码 bug:
  1. Treadmill Data 坡度位用错(原 bit2=Total Distance,应为 **bit3=Inclination**)且长度不足(需 Inclination+RampAngle 共4字节)→ 坡度不回显。已修正。
  2. 速度 `(int)(speed*100)` 截断导致少 0.1（如 4.8999→4.8）→ 改为 Math.round。已修正。
- setSpeed/setSlope 实测返回 `result=0`(成功),硬件回调确认物理生效。

**使用方式:在跑步机面板手动按启动跑起来,再用 FitShow 连 TR1200-T 即可调速/调坡。**

### 9. 完整 AIDL 事务码表 + 软启动（2026-06-03 反编译 SoleBrushlessThardwareService + trunning）

反编译确认了 ITreadmillService 全部 24 个事务码（b.java Stub）及控制器 k.java（TreadmillControllerImpl）实现：

| transact | 接口方法 | 控制器 | 作用 |
|---|---|---|---|
| **1** | a() | **l()** | **真正的启动**：复位运动计数、置运行标志、状态机 Halted→Running（= 面板"快速启动"底层原语）|
| 2 | b() | m() | 停止/结束 |
| 3 | c() | n() | 复位/结束辅助 |
| 4 | a(double) | d(双) 绝对 | 设速度（绝对值，写主设定 A，**有效**）|
| 5 | a(int) | h(int) 绝对 | 设坡度（绝对）|
| 6 | b(double) | d(A+Δ) | 速度**相对增量** |
| 7 | b(int) | h(B+Δ) | 坡度**相对增量** |
| 8 | d() | 读 A | 读当前速度设定 |
| 10/11 | a/b(observer) | — | 注册/注销数据回调 |
| 22 | l() | a() | **坡度校准**（非启动！之前误用）|
| 23 | m() | b() | 停止校准 |
| 24 | c(int) | — | 恒返回 false（空实现）|

**软启动结论（已放弃远程软启动）：**
- 低层 `transact(1)`（controller.l()）只能"半启动"：皮带按 speedStart 蠕动，但控制器停在一个 setSpeed 返回成功(result=0)却不驱动电机的状态。实测确认。
- 真正的启动由 trunning 的 `QuickStartActivity` 完成，它做了两件我们做不到的事：① 写 `motor_en` GPIO（`/sys/devices/platform/cvte_gpio/motor_en`="1"，见 `TRunning.e()`）；② 走完整运行态。**实测：拉起 QuickStartActivity 后，大屏进运行界面 + FitShow 速度/坡度完全双向驱动电机。**
- 但我们的 App **无法**自己拉起它：`QuickStartActivity` 未 `exported`，需 `START_ANY_ACTIVITY`（signature 级），而该 ROM 平台证书是**厂商私有平台密钥**（指纹 `efa27443…`，≠ 公开 AOSP 平台密钥 `c8a2e9bc…`，私钥不可得）→ 无法平台签名。

**支持的使用流程（完整可用）：**
> 在跑步机屏幕上点「快速启动」让皮带跑起来 → 用 FitShow 连 `TR1200-T` → 速度/坡度双向联动、大屏同步。

**已知限制：**
1. **不能从 FitShow 远程"软启动"皮带**：必须先在面板点快速启动（原因见上，需厂商平台密钥或 root 助手才能自动化，均不划算/不稳）。
2. **App 调速与面板物理按钮不同步**：trunning 的 setSpeed 走 `e.a(this.k)`，`this.k`(mTargetSpeed) 是其进程内私有缓存，不跟随 BLE；唯一外部广播 `to_thardwareservice` 仅 pause/resume，改不了它的缓存。仅在"App 调速后又去碰面板按钮"混用时出现；纯 App 控制不受影响。

---

## 工具文件说明

| 文件 | 说明 |
|---|---|
| TreadmillFTMS/ | Android 项目源码 |
| TreadmillFTMS/app/build/outputs/apk/debug/app-debug.apk | 最新编译的 APK |
| ftms_tester.html | 网页控制台（需桌面 Chrome，Web Bluetooth API） |

注：反编译的 APK 源码在上一个 session 的 /tmp/jadx_out 中，已不可用。如需重新反编译：
  jadx -d /tmp/jadx_out /system/priv-app/SoleBrushlessThardwareService/SoleBrushlessThardwareService.apk

---

## 部署步骤（每次重新连接跑步机后）

```bash
# 1. 安装 APK
adb install -r TreadmillFTMS/app/build/outputs/apk/debug/app-debug.apk

# 2. 授权蓝牙权限（仅首次或重装后需要）
adb shell pm grant com.treadmill.ftms android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.treadmill.ftms android.permission.BLUETOOTH_ADVERTISE

# 3. 启动服务
adb shell am startforegroundservice -n com.treadmill.ftms/.FtmsBridgeService

# 4. 查看日志
adb shell logcat -s FtmsBridge:D
```

---

## 下一步待办

1. 【最高优先级】测试新广播包格式是否能让 FitShow 找到跑步机
2. 如果 FitShow 仍找不到：抓取真实 TR1200 的完整 GATT profile，补全 FFF0 服务下的特征值
3. 研究从软件启动跑步机的方法（绕过状态机限制）：
   - 找到 trunning 中触发 startRunning() 的完整 Intent/AIDL 调用链
   - 或通过直接写 ttyS3 发送启动帧（需要确认 Android→跑步机的帧格式）
4. 将 FtmsBridgeService 打包为系统 app（避免每次重装后重新授权权限）
5. 网页工具：支持从停止状态启动

---

## 关键文件路径（跑步机上）

```
/system/priv-app/SoleBrushlessThardwareService/SoleBrushlessThardwareService.apk
/system/priv-app/SewQhItTrunningEn/SewQhItTrunningEn.apk
/dev/ttyS3   <- 跑步机电机控制串口（2400 baud）
/dev/ttyS0   <- 其他用途（115200 baud）
/dev/ttyS1   <- 蓝牙模块
```

## 跑步机蓝牙信息

```
MAC 地址：22:22:F5:55:06:00
设备名（原始）：F63MAX-1218
设备名（桥接后）：TR1200-T
```

## 构建环境

```
Java:         OpenJDK 21 (/Users/Val/Library/Java/JavaVirtualMachines/openjdk-21.0.2)
Android SDK:  ~/Library/Android/sdk  (API 33/34, build-tools 34)
Gradle:       /tmp/gradle_dist/gradle-8.7/bin/gradle（或用项目内 ./gradlew）
jadx:         /tmp/jadx/bin/jadx
```

构建命令：
```bash
export JAVA_HOME="/Users/Val/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd TreadmillFTMS && ./gradlew assembleDebug
```
