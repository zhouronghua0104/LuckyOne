# 服务启动 Activity 失败排查（`com.crescent.myjnidemo/.MainActivity`）

## 现象（来自日志）

```text
ActivityTaskManager E START ... cmp=com.crescent.myjnidemo/.MainActivity ...
ActivityTaskManager E isHomeIntent ... result: false
TDAPolicy E getTDAByType: packageName=com.crescent.myjnidemo,displayId=0
```

从这几行看，请求已经发到了 `ActivityTaskManager`，但后续被系统策略链路（`TDAPolicy`）介入，典型表现是**后台拉起页面被系统/厂商策略拦截**，而不是普通 `ClassNotFound` 或 `Activity` 未注册问题。

---

## 高概率根因（按优先级）

1. **后台启动限制（Android 10+）**
   - 从 `Service` 直接 `startActivity()`，当应用不在前台时，系统可能拒绝。
2. **车机/定制 ROM 策略拦截（日志里的 `TDAPolicy`）**
   - 即使 `Intent` 正确，也可能因 display/policy 白名单限制被拒绝。
3. **清单配置不完整（次要）**
   - `MainActivity` 启动入口未正确声明（例如 launcher `intent-filter`、`exported` 等）。

---

## 建议修复方案

### 1) 不要让后台 Service 直接硬拉起页面

优先改成：**前台通知 + PendingIntent**，让用户触发进入页面（系统最兼容）。

```kotlin
val intent = Intent(this, MainActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
val contentIntent = PendingIntent.getActivity(this, 1001, intent, piFlags)

val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setSmallIcon(R.drawable.ic_notification)
    .setContentTitle("任务进行中")
    .setContentText("点击返回主界面")
    .setContentIntent(contentIntent)
    .setAutoCancel(true)
    .build()

startForeground(1001, notification)
```

### 2) 如果必须在 Service 内主动拉起（不推荐），至少做保护

```kotlin
fun Service.safeLaunchMainActivity(): Boolean {
    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    return try {
        startActivity(intent)
        true
    } catch (e: SecurityException) {
        // 常见于系统策略/权限限制
        false
    } catch (e: Exception) {
        false
    }
}
```

### 3) 检查 `AndroidManifest.xml`

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

> 说明：同应用内显式启动理论上不依赖 `exported`，但 launcher 入口缺失会导致外部/系统拉起异常，建议保持标准声明。

### 4) 车机/定制系统必须同步策略白名单

日志已出现 `TDAPolicy`，通常需要平台侧确认：
- `com.crescent.myjnidemo/.MainActivity` 是否允许在当前 `displayId=0` 被拉起；
- 是否允许来自 Service/后台状态发起启动；
- 是否有“仅 Home/系统应用可拉起”的策略。

---

## 快速排查命令（adb）

```bash
# 重点看 Activity 启动决策
adb logcat -v time ActivityTaskManager:V ActivityManager:V TDAPolicy:V *:S

# 验证 Activity 是否在清单中（设备端）
adb shell cmd package resolve-activity -c android.intent.category.LAUNCHER com.crescent.myjnidemo

# 手动启动看系统返回（包含错误原因）
adb shell am start -W -n com.crescent.myjnidemo/.MainActivity
```

---

## 结论

这类日志最常见不是代码崩溃，而是**后台启动 Activity 被系统/厂商策略拦截**。  
落地优先级建议：**通知点击进入 > 前台服务 > 平台策略白名单核对 > 清单补齐**。
