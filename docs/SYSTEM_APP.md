# 作为系统 App 安装（/system/priv-app）

跑步机 ROM 为 `userdebug` + `adb root` 可用 + `/system` 可 overlayfs 写入，
因此可把桥接装成系统 App：开机自启、权限持久、无需每次重装重新授权。

## 前提
- 设备已开启网络 adb（`adb connect <ip>:5555`）
- `adb root` 可成功（本机为 userdebug）

## 安装步骤

```bash
ADB=$ANDROID_HOME/platform-tools/adb
APK=release/app-debug.apk

$ADB root && $ADB remount

# 1. 移除可能存在的普通安装版本（避免签名/路径冲突）
$ADB uninstall com.treadmill.ftms   # 没装过会报错，可忽略

# 2. 放入 /system/priv-app
$ADB shell mkdir -p /system/priv-app/TreadmillFTMS
$ADB push $APK /system/priv-app/TreadmillFTMS/TreadmillFTMS.apk
$ADB shell chmod 755 /system/priv-app/TreadmillFTMS
$ADB shell chmod 644 /system/priv-app/TreadmillFTMS/TreadmillFTMS.apk

# 3.（可选）默认权限，工厂重置后也自动授予 BLE 权限
$ADB push android/system-app/default-permissions-treadmill-ftms.xml \
          /system/etc/default-permissions/treadmill-ftms.xml

# 4. 让 PackageManager 重新扫描 /system —— 整机重启最稳
$ADB reboot
```

⚠️ 注意：`adb shell stop && start`（仅重启框架）会让**网络 adb 掉线**，且本机实测会断连；
直接 `adb reboot` 整机重启更稳。重启后网络 adb 若未自动恢复，需重新 `adb connect`
（端口 5555 模式重启后可能失效，必要时用 USB `adb tcpip 5555` 重新开启）。

## 重启后一次性授权（之后持久，无需再做）

```bash
$ADB root
$ADB shell pm grant com.treadmill.ftms android.permission.BLUETOOTH_CONNECT
$ADB shell pm grant com.treadmill.ftms android.permission.BLUETOOTH_ADVERTISE
$ADB shell am startforegroundservice -n com.treadmill.ftms/.FtmsBridgeService
```

## 验证

```bash
$ADB shell pm path com.treadmill.ftms      # → /system/priv-app/TreadmillFTMS/...
$ADB logcat -s FtmsBridge:D                # 应见 onServiceAdded ×3 + Advertising started OK
```

## 升级
重新构建后，`adb root && adb remount`，重新 push 步骤 2 的 APK，然后 `adb reboot`。
权限授予会保留，无需重新授权。

## 待办（需平台签名）
拉起 trunning 的 `QuickStartActivity`（让大屏进运行界面）需 `START_ANY_ACTIVITY`
（signature 级权限）→ 需用 ROM 的平台密钥签名本 App。ROM 为 AOSP test-keys 签名
（cert DN = `CN=Android, O=Android, android@android.com`），理论上可用公开的 AOSP
平台密钥重签 + `sharedUserId="android.uid.system"` 实现。见 README「下一步」。
