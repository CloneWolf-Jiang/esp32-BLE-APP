# ESPHome 蓝牙配网

这是一个原生 Kotlin Android 应用，使用真实 BLE 广播和 ESPHome Improv BLE 状态完成配网；不模拟设备、授权或配网成功。

## 当前实现

- 指引用户长按三键开关最左侧的 1 号按键 5 秒，并说明设备没有可视反馈、授权窗口为 1 分钟。
- 通过 Improv 服务 UUID 和服务数据识别广播设备；连接后验证全部 Improv GATT 特征。
- 按串行 GATT 操作顺序订阅状态、错误和 RPC 响应，再读取 Capabilities 与当前状态。
- 仅在设备协议状态为 `AUTHORIZED` 时发送凭据；只有收到真实 `PROVISIONED` 状态才显示成功。
- 首选设备端 Wi-Fi 扫描（Capabilities bit 2），否则请求 Android 系统 Wi-Fi 扫描；允许手动输入 SSID 和隐藏网络。
- 密码只保存在当前 Activity 的内存中，不写日志或本地存储；流程完成或离开 Activity 后清除。
- 使用 Android 原生 View，支持 API 21 起、系统浅色/深色主题。运行期权限按 API 分支请求。

## 构建

需要 JDK 17 和 Android SDK Platform 36。测试 APK 使用临时自签名密钥签名；证书公开文件位于 `release/esp32-ble-test-cert.pem`。私钥不会提交到仓库，故该 APK 只用于首次安装测试，无法用同一签名发布后续覆盖升级。

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

调试 APK（构建成功后）：`app/build/outputs/apk/debug/app-debug.apk`。本仓库未配置正式签名密钥。

## 可下载测试包

- 测试 APK：[`release/esp32-ble-app-test.apk`](release/esp32-ble-app-test.apk)
- 自签名公钥证书：[`release/esp32-ble-test-cert.pem`](release/esp32-ble-test-cert.pem)
- APK SHA-256：`677933841adb464a2c19e9e59bb625de698b9a4f246ff6870aff86bbe5cb92a9`
- 证书 SHA-256 指纹：`EF:5E:C0:3F:70:3D:61:12:38:7E:37:43:89:00:D9:C5:C7:F9:4E:DF:26:FC:66:54:DE:6E:A6:F4:22:BD:21:93`

APK 已用 Android SDK 本地工具编译为 DEX、打包、对齐，并通过 APK Signature Scheme v2/v3 验签。当前执行环境无法从 Google Maven 下载 Android Gradle Plugin，因此没有声称 Gradle 任务构建成功；也没有可连接的 Android 设备执行安装或蓝牙实测。测试 APK 以 `com.clonewolf.esphomeble`、`versionCode 1` 标识。

证书是自签名测试证书，不受系统或应用商店信任。相同签名可用于验证此次 APK；为避免把私钥放进代码仓库，私钥未随证书或 APK 发布。卸载后重装不受影响，但以后不能用另一个签名的 APK 覆盖升级这次安装。

## Improv BLE 核实结果

依据 [Improv Wi-Fi BLE 规范](https://www.improv-wifi.com/ble/)、[官方 C++ SDK UUID/命令定义](https://github.com/improv-wifi/sdk-cpp/blob/main/src/improv.h)、[官方 BLE SDK 特征与能力实现](https://github.com/improv-wifi/sdk-ble-js/blob/main/src/const.ts) 以及 [ESPHome 官方实现](https://github.com/esphome/esphome/tree/dev/esphome/components/improv_ble)：

| 项目 | 核实值 |
| --- | --- |
| 服务 | `00467768-6228-2272-4663-277478268000` |
| 状态 / 错误 / RPC 命令 / RPC 响应 / 能力 | UUID 后缀依次为 `8001` / `8002` / `8003` / `8004` / `8005` |
| 特征属性 | 状态、错误、RPC 响应为 Read + Notify；RPC 命令为 Write；Capabilities 为 Read |
| 状态 | `01` 等待授权，`02` 已授权，`03` 配网中，`04` 已配网 |
| 错误 | `00` 无错误，`01` RPC 无效，`02` 未知 RPC，`03` 无法连接，`04` 未授权 |
| RPC | `01` 提交 Wi-Fi，`02` Identify，`03` 设备信息，`04` 扫描 Wi-Fi |
| Capabilities | bit 0 Identify，bit 1 设备信息，bit 2 Wi-Fi 扫描 |
| RPC 帧 | 命令字节、数据长度、数据、低 8 位加和校验；字符串以 1 字节长度前缀编码 |

特征属性及数据格式由协议层校验。订阅通知后再读取状态，避免漏掉状态变化；所有 GATT 读写均串行化。Wi-Fi RPC 按默认 ATT MTU 的 20 字节安全分段顺序写入，兼容 ESPHome 累积分片后解析的实现。Improv BLE 没有独立的数值版本特征。

## ESPHome 固件要求与限制

用户已在实际固件上验证蓝牙物理授权可用。设备没有指示灯等可视反馈；APP 不能观察固件内部的按键动作，只能读取真实 Improv BLE 授权状态后继续。仓库仍没有 ESPHome YAML、GPIO 接线信息或固件版本，无法独立核验按键引脚和继电器自动化。**仅安装 APP 不会让物理按键具备授权功能。**固件应使用与独立 1 号按键对应的 `binary_sensor` 作为 `improv_ble.authorizer`，并将授权持续时间设为 `1min`。

ESPHome 当前官方源码中，`improv_ble` 的 Capabilities 只设置 Identify 位（可选状态灯）；它没有声明或处理 `GET_WIFI_NETWORKS`，该命令会落入未知 RPC。因此本应用会转用手机 Wi-Fi 扫描，并始终保留手动 SSID 输入。设备端扫描能力按已声明的 bit 2 判断，不根据设备名推测。

配置中应明确设置 `authorizer` 和 `authorized_duration: 1min`。未配置 `authorizer` 的 ESPHome 实例会以 `AUTHORIZED` 初始状态运行，因此必须在目标固件中确认物理授权配置；BLE 协议不能告诉 APP 授权来自按键还是固件未启用授权。

组件级配置应指向单独的 1 号按键授权传感器：

```yaml
improv_ble:
  authorizer: provision_button
  authorized_duration: 1min
```

`provision_button` 的 GPIO、有效电平和上拉/下拉必须按实际原理图确定；其输入应配置稳定按下 5 秒后才置为有效。不要把短按传感器直接复用为会切换继电器的自动化触发器。拿到现有 YAML 和硬件引脚图后，才能给出安全、可刷写的完整固件改动。

固件方案必须以实际引脚图和现有 YAML 为准；在缺少这些资料时，本仓库不提供可直接刷写的 GPIO YAML，也不应猜测或覆盖现有继电器配置。

## Android 权限与兼容性

- API 21–22：旧蓝牙权限；API 23–30：运行时定位权限用于 BLE 扫描；API 31+：`BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`，扫描声明 `neverForLocation`。
- Android Wi-Fi 扫描使用 `WifiManager.startScan()` / `getScanResults()`；API 23+ 申请精确位置权限并检查系统定位开关。系统可能限频、返回缓存或空结果，空列表不会被解释为“没有网络”；可以手动输入。
- Android 13+ 的 `NEARBY_WIFI_DEVICES` 适用于部分 Wi-Fi 设备操作，但不是读取扫描结果的替代权限，因此此处不以它绕过定位权限。
- 应用配置 `targetSdk 36`，不得通过降 target 绕过权限要求。发布到应用市场前，需复核当时的目标 API 要求。

用户已确认固件蓝牙授权实际测试通过；Android APP 的运行时 BLE/Wi-Fi 兼容性仍需在 API 21、Android 6–11、Android 12+、Android 13+ 和不同厂商设备上按验收清单测试。
