#!/bin/bash

# Bibao.apk 故障排除命令集合
# 用于诊断和解决权限问题

echo "========================================="
echo "Bibao.apk 故障排除命令"
echo "========================================="

echo "1. 基本权限检查"
echo "==================="

echo "检查APK文件："
ls -la /vendor/app/qcguiagent/Bibao.apk 2>/dev/null || echo "APK文件不存在"

echo "检查APK SELinux标签："
ls -Z /vendor/app/qcguiagent/Bibao.apk 2>/dev/null || echo "无法获取SELinux标签"

echo "检查模型目录："
ls -la /data/local/qcguiagent/ark_000007_20250724_8850/ 2>/dev/null || echo "模型目录不存在或无法访问"

echo "检查模型目录SELinux标签："
ls -Z /data/local/qcguiagent/ark_000007_20250724_8850/ 2>/dev/null || echo "无法获取模型目录SELinux标签"

echo ""
echo "2. SELinux状态检查"
echo "==================="

echo "SELinux状态："
getenforce 2>/dev/null || echo "SELinux命令不可用"

echo "检查SELinux拒绝日志："
dmesg | grep avc | tail -10 2>/dev/null || echo "无SELinux拒绝日志或无权限查看"

echo ""
echo "3. OpenCL设备检查"
echo "==================="

echo "Qualcomm GPU设备："
ls -la /dev/kgsl-3d0 2>/dev/null || echo "/dev/kgsl-3d0 不存在"

echo "ARM Mali GPU设备："
ls -la /dev/mali* 2>/dev/null || echo "Mali GPU设备不存在"

echo "DRI设备："
ls -la /dev/dri/* 2>/dev/null || echo "DRI设备不存在"

echo "OpenCL库："
find /vendor /system -name "*OpenCL*" -type f 2>/dev/null || echo "未找到OpenCL库"

echo ""
echo "4. 系统日志检查"
echo "==================="

echo "最近的Bibao相关日志："
logcat -d | grep -i bibao | tail -10 2>/dev/null || echo "无Bibao相关日志或logcat不可用"

echo "最近的权限拒绝日志："
logcat -d | grep -i denied | tail -10 2>/dev/null || echo "无权限拒绝日志"

echo "最近的OpenCL相关日志："
logcat -d | grep -i opencl | tail -10 2>/dev/null || echo "无OpenCL相关日志"

echo ""
echo "5. 用户权限测试"
echo "==================="

echo "测试system用户访问模型目录："
su system -c "ls /data/local/qcguiagent/ark_000007_20250724_8850/" 2>/dev/null && echo "✓ system用户可以访问" || echo "✗ system用户无法访问"

echo "测试system用户读取模型文件："
su system -c "head -c 100 /data/local/qcguiagent/ark_000007_20250724_8850/*" 2>/dev/null && echo "✓ system用户可以读取文件" || echo "✗ system用户无法读取文件"

echo ""
echo "6. 快速修复命令"
echo "==================="

cat << 'EOF'
# 如果上述检查发现问题，可以尝试以下快速修复：

# 修复基本权限：
sudo chown -R system:system /data/local/qcguiagent/ark_000007_20250724_8850
sudo chmod 755 /data/local/qcguiagent/ark_000007_20250724_8850
sudo find /data/local/qcguiagent/ark_000007_20250724_8850 -type f -exec chmod 644 {} \;

# 修复SELinux标签：
sudo chcon -R u:object_r:system_data_file:s0 /data/local/qcguiagent/ark_000007_20250724_8850/

# 临时关闭SELinux（仅用于测试）：
sudo setenforce 0

# 修复OpenCL设备权限：
sudo chmod 666 /dev/kgsl-3d0  # Qualcomm
sudo chmod 666 /dev/mali0     # ARM Mali

# 更宽松的权限（仅用于测试）：
sudo chmod -R 777 /data/local/qcguiagent/ark_000007_20250724_8850/
EOF

echo ""
echo "7. 应用级检查建议"
echo "==================="

cat << 'EOF'
请检查Bibao.apk的AndroidManifest.xml是否包含以下权限：

<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />

对于OpenCL使用，可能需要：
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.opengles.aep" android:required="false" />

如果应用运行时还是无法访问，可能需要在应用代码中请求运行时权限。
EOF

echo ""
echo "========================================="
echo "故障排除完成"
echo "========================================="