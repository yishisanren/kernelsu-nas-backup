#!/system/bin/sh
MODDIR=${0%/*}
# Only local, preverified atomic replacements run here. No network or APK installation.
[ -f /data/adb/nas_module_backup/restore.json ] || exit 0
"$MODDIR/bin/nasbackup" early >/dev/null 2>&1
