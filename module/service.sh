#!/system/bin/sh
MODDIR=${0%/*}
"$MODDIR/bin/nasbackup" daemon >/dev/null 2>&1 &
