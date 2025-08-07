#!/bin/bash

# Bibao.apk完整部署脚本
# 解决APK访问模型文件权限问题

set -e  # 遇到错误立即退出

echo "========================================="
echo "开始部署Bibao.apk..."
echo "========================================="

# 检查是否有root权限
if [ "$EUID" -ne 0 ]; then 
    echo "错误: 此脚本需要root权限运行"
    echo "请使用: sudo bash deploy_bibao.sh"
    exit 1
fi

# 1. 创建必要目录
echo "步骤1: 创建必要目录..."
mkdir -p /vendor/app/qcguiagent
mkdir -p /data/local/qcguiagent
echo "✓ 目录创建完成"

# 2. 复制APK文件（假设从当前目录）
echo "步骤2: 部署APK文件..."
if [ -f "Bibao.apk" ]; then
    cp Bibao.apk /vendor/app/qcguiagent/
    echo "✓ APK文件复制完成"
else
    echo "警告: 当前目录未找到Bibao.apk文件"
    echo "请确保APK文件位于 /vendor/app/qcguiagent/Bibao.apk"
fi

# 3. 设置APK权限
echo "步骤3: 设置APK文件权限..."
if [ -f "/vendor/app/qcguiagent/Bibao.apk" ]; then
    chown system:system /vendor/app/qcguiagent/Bibao.apk
    chmod 644 /vendor/app/qcguiagent/Bibao.apk
    echo "✓ APK权限设置完成"
else
    echo "错误: 找不到APK文件，跳过权限设置"
fi

# 4. 设置模型文件目录权限
echo "步骤4: 设置模型文件目录权限..."
if [ -d "/data/local/qcguiagent/ark_000007_20250724_8850" ]; then
    # 设置所有者
    chown -R system:system /data/local/qcguiagent/ark_000007_20250724_8850
    
    # 设置目录权限
    chmod 755 /data/local/qcguiagent
    chmod 755 /data/local/qcguiagent/ark_000007_20250724_8850
    
    # 递归设置文件和目录权限
    find /data/local/qcguiagent/ark_000007_20250724_8850 -type f -exec chmod 644 {} \;
    find /data/local/qcguiagent/ark_000007_20250724_8850 -type d -exec chmod 755 {} \;
    
    echo "✓ 模型文件目录权限设置完成"
else
    echo "错误: 模型文件目录不存在: /data/local/qcguiagent/ark_000007_20250724_8850"
    echo "请确保模型文件已正确部署"
    exit 1
fi

# 5. 检查并设置SELinux标签
echo "步骤5: 配置SELinux权限..."

# 检查SELinux状态
if command -v getenforce >/dev/null 2>&1; then
    SELINUX_STATUS=$(getenforce)
    echo "当前SELinux状态: $SELINUX_STATUS"
    
    if [ "$SELINUX_STATUS" = "Enforcing" ]; then
        # 设置APK的SELinux标签
        if [ -f "/vendor/app/qcguiagent/Bibao.apk" ]; then
            chcon u:object_r:vendor_app_file:s0 /vendor/app/qcguiagent/Bibao.apk 2>/dev/null || \
            chcon u:object_r:system_app_data_file:s0 /vendor/app/qcguiagent/Bibao.apk 2>/dev/null || \
            echo "警告: 无法设置APK的SELinux标签"
        fi
        
        # 设置模型文件目录的SELinux标签
        chcon -R u:object_r:system_data_file:s0 /data/local/qcguiagent/ark_000007_20250724_8850/ 2>/dev/null || \
        chcon -R u:object_r:shell_data_file:s0 /data/local/qcguiagent/ark_000007_20250724_8850/ 2>/dev/null || \
        echo "警告: 无法设置模型文件的SELinux标签"
        
        echo "✓ SELinux标签设置完成"
    fi
else
    echo "注意: 系统不支持SELinux或命令不可用"
fi

# 6. 设置OpenCL设备权限
echo "步骤6: 配置OpenCL设备权限..."

# Qualcomm GPU设备
if [ -e /dev/kgsl-3d0 ]; then
    chmod 666 /dev/kgsl-3d0
    chown system:system /dev/kgsl-3d0
    echo "✓ Qualcomm GPU设备权限设置完成"
fi

# ARM Mali GPU设备
if [ -e /dev/mali0 ]; then
    chmod 666 /dev/mali0
    chown system:system /dev/mali0
    echo "✓ ARM Mali GPU设备权限设置完成"
fi

# 其他可能的GPU设备
for device in /dev/dri/card* /dev/dri/render*; do
    if [ -e "$device" ]; then
        chmod 666 "$device"
        chown system:system "$device"
        echo "✓ GPU设备权限设置完成: $device"
    fi
done

# 设置OpenCL库权限
for lib in /vendor/lib*/libOpenCL.so /system/lib*/libOpenCL.so; do
    if [ -f "$lib" ]; then
        chmod 755 "$lib"
        echo "✓ OpenCL库权限设置完成: $lib"
    fi
done

# 7. 验证权限设置
echo "步骤7: 验证权限设置..."

echo "APK文件权限:"
if [ -f "/vendor/app/qcguiagent/Bibao.apk" ]; then
    ls -la /vendor/app/qcguiagent/Bibao.apk
    if command -v ls >/dev/null 2>&1 && ls --help 2>&1 | grep -q "\-Z"; then
        ls -Z /vendor/app/qcguiagent/Bibao.apk 2>/dev/null || echo "无法显示SELinux标签"
    fi
fi

echo "模型文件目录权限:"
ls -la /data/local/qcguiagent/ark_000007_20250724_8850/ | head -10

# 8. 创建权限验证脚本
echo "步骤8: 创建权限验证脚本..."
cat > /data/local/qcguiagent/verify_permissions.sh << 'EOF'
#!/bin/bash
echo "=== Bibao.apk 权限验证脚本 ==="

echo "1. 检查APK文件:"
ls -la /vendor/app/qcguiagent/Bibao.apk 2>/dev/null || echo "APK文件不存在"

echo "2. 检查模型文件目录:"
ls -la /data/local/qcguiagent/ark_000007_20250724_8850/ 2>/dev/null || echo "模型目录不存在"

echo "3. 检查OpenCL设备:"
for device in /dev/kgsl-3d0 /dev/mali0; do
    if [ -e "$device" ]; then
        echo "$device: $(ls -la $device)"
    fi
done

echo "4. 测试system用户访问权限:"
su system -c "ls /data/local/qcguiagent/ark_000007_20250724_8850/" 2>/dev/null && echo "✓ system用户可以访问模型目录" || echo "✗ system用户无法访问模型目录"

echo "=== 验证完成 ==="
EOF

chmod +x /data/local/qcguiagent/verify_permissions.sh
echo "✓ 权限验证脚本创建完成: /data/local/qcguiagent/verify_permissions.sh"

echo "========================================="
echo "Bibao.apk部署完成!"
echo "========================================="

echo "后续步骤:"
echo "1. 运行验证脚本: bash /data/local/qcguiagent/verify_permissions.sh"
echo "2. 如果仍有问题，查看日志: logcat | grep -i 'bibao\\|denied\\|opencl'"
echo "3. 临时测试可以关闭SELinux: setenforce 0"
echo "4. 重启设备以确保所有权限生效"

echo ""
echo "注意事项:"
echo "- 某些权限可能需要重启后才能完全生效"
echo "- 如果问题持续，请检查应用的AndroidManifest.xml权限声明"
echo "- 建议在测试环境先验证配置的正确性"