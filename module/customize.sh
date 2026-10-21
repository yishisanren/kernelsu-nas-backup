#!/system/bin/sh
[ "$KSU" = true ] || abort "仅支持 KernelSU"
[ "$API" -ge 28 ] || abort "需要 Android 9 或更新系统"
ui_print "NAS 模块备份"
ui_print "安装后重启，打开 WebUI 配置 WebDAV。"
ui_print "恢复前会检查环境；不会自动重启手机。"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/nasbackup" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
mkdir -p /data/adb/nas_module_backup
chmod 0700 /data/adb/nas_module_backup
