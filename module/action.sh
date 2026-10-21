#!/system/bin/sh
MODDIR=${0%/*}
"$MODDIR/bin/nasbackup" backup >/dev/null 2>&1 &
echo "已启动备份，请在 WebUI 查看状态。"
