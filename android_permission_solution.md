# Bibao.apk 访问模型文件权限配置解决方案

## 问题分析

您的Bibao.apk应用在访问 `/data/local/qcguiagent/ark_000007_20250724_8850` 目录中的模型文件时遇到权限问题。这主要涉及以下几个方面：

1. **文件系统权限**：Linux标准权限设置
2. **SELinux策略**：Android系统的强制访问控制
3. **应用权限**：APK的运行时权限和清单权限

## 解决方案

### 1. 文件系统权限设置

#### 设置目录和文件权限
```bash
# 设置模型目录权限（确保应用可以访问）
chmod 755 /data/local/qcguiagent
chmod 755 /data/local/qcguiagent/ark_000007_20250724_8850
chmod -R 644 /data/local/qcguiagent/ark_000007_20250724_8850/*

# 设置所有者为system（您已经完成）
chown -R system:system /data/local/qcguiagent/ark_000007_20250724_8850

# 设置Bibao.apk权限
chmod 644 /vendor/app/qcguiagent/Bibao.apk
chown system:system /vendor/app/qcguiagent/Bibao.apk
```

### 2. SELinux策略配置

#### 检查当前SELinux状态
```bash
# 检查SELinux状态
getenforce

# 检查文件SELinux标签
ls -Z /vendor/app/qcguiagent/Bibao.apk
ls -Z /data/local/qcguiagent/ark_000007_20250724_8850/
```

#### 设置正确的SELinux标签
```bash
# 为APK设置正确的SELinux上下文
setenforce 0  # 临时关闭SELinux（仅用于测试）

# 设置APK的SELinux标签
chcon u:object_r:vendor_app_file:s0 /vendor/app/qcguiagent/Bibao.apk

# 设置模型文件目录的SELinux标签
chcon -R u:object_r:system_data_file:s0 /data/local/qcguiagent/ark_000007_20250724_8850/

# 或者使用更宽松的标签
chcon -R u:object_r:shell_data_file:s0 /data/local/qcguiagent/ark_000007_20250724_8850/
```

### 3. 创建自定义SELinux策略

如果需要永久解决方案，需要创建自定义SELinux策略：

#### 创建策略文件
```bash
# 创建策略文件目录
mkdir -p /system/etc/selinux/

# 创建自定义策略
cat > /system/etc/selinux/bibao_policy.te << 'EOF'
# Bibao应用访问模型文件的SELinux策略
type bibao_app, domain;
type bibao_data_file, file_type, data_file_type;

# 允许bibao应用读取模型文件
allow bibao_app bibao_data_file:file { read open getattr };
allow bibao_app bibao_data_file:dir { read search open getattr };

# 允许访问OpenCL
allow bibao_app gpu_device:chr_file { read write open ioctl };
allow bibao_app vendor_file:file { read open getattr execute };
EOF
```

### 4. OpenCL权限配置

#### 确保OpenCL设备权限
```bash
# 检查OpenCL设备
ls -la /dev/kgsl-3d0
ls -la /dev/mali*

# 设置OpenCL设备权限
chmod 666 /dev/kgsl-3d0  # Qualcomm GPU
chmod 666 /dev/mali0     # ARM Mali GPU (如果存在)

# 设置OpenCL库权限
chmod 755 /vendor/lib*/libOpenCL.so
chmod 755 /system/lib*/libOpenCL.so
```

### 5. 应用清单权限

确保Bibao.apk的AndroidManifest.xml包含必要权限：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- OpenCL权限 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.opengles.aep" android:required="false" />
```

## 完整部署脚本

### 部署脚本 (deploy_bibao.sh)
```bash
#!/bin/bash

# Bibao.apk完整部署脚本

echo "开始部署Bibao.apk..."

# 1. 创建必要目录
mkdir -p /vendor/app/qcguiagent
mkdir -p /data/local/qcguiagent

# 2. 复制APK文件（假设从当前目录）
cp Bibao.apk /vendor/app/qcguiagent/

# 3. 设置APK权限
chown system:system /vendor/app/qcguiagent/Bibao.apk
chmod 644 /vendor/app/qcguiagent/Bibao.apk

# 4. 设置模型文件目录权限
chown -R system:system /data/local/qcguiagent/ark_000007_20250724_8850
chmod 755 /data/local/qcguiagent
chmod 755 /data/local/qcguiagent/ark_000007_20250724_8850
find /data/local/qcguiagent/ark_000007_20250724_8850 -type f -exec chmod 644 {} \;
find /data/local/qcguiagent/ark_000007_20250724_8850 -type d -exec chmod 755 {} \;

# 5. 设置SELinux标签
chcon u:object_r:vendor_app_file:s0 /vendor/app/qcguiagent/Bibao.apk
chcon -R u:object_r:system_data_file:s0 /data/local/qcguiagent/ark_000007_20250724_8850/

# 6. 设置OpenCL设备权限
if [ -e /dev/kgsl-3d0 ]; then
    chmod 666 /dev/kgsl-3d0
    chown system:system /dev/kgsl-3d0
fi

if [ -e /dev/mali0 ]; then
    chmod 666 /dev/mali0
    chown system:system /dev/mali0
fi

# 7. 重启相关服务
stop
start

echo "Bibao.apk部署完成!"
echo "请检查应用是否可以正常访问模型文件"
```

## 故障排除

### 1. 检查日志
```bash
# 查看系统日志
logcat | grep -i bibao
logcat | grep -i denied
logcat | grep -i opencl

# 查看SELinux拒绝日志
dmesg | grep avc
```

### 2. 测试访问权限
```bash
# 测试文件访问权限
su system -c "ls -la /data/local/qcguiagent/ark_000007_20250724_8850/"
su system -c "cat /data/local/qcguiagent/ark_000007_20250724_8850/README.txt"  # 如果存在
```

### 3. 临时解决方案
如果以上方案不起作用，可以临时使用：
```bash
# 临时关闭SELinux（仅用于测试）
setenforce 0

# 设置更宽松的权限
chmod -R 777 /data/local/qcguiagent/ark_000007_20250724_8850/
```

## 注意事项

1. **安全性**：确保权限设置不会影响系统安全
2. **持久性**：某些权限可能在重启后丢失，需要添加到启动脚本
3. **兼容性**：不同Android版本的SELinux策略可能有差异
4. **测试**：在生产环境应用前，请在测试设备上验证所有配置

## 建议的实施步骤

1. 首先应用文件系统权限配置
2. 测试应用是否可以访问文件
3. 如果仍有问题，配置SELinux策略
4. 最后配置OpenCL相关权限
5. 验证所有功能正常工作