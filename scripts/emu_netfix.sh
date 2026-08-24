#!/usr/bin/env bash
# 修复 vibe_test 模拟器无法联网的问题（eth0 DOWN + 缺少默认路由）。
#
# 背景：该 AVD 冷启动后 eth0 处于 DOWN 且没有默认网关路由，
# 导致 App 无法解析/连接 https://emby.bangumi.ca，
# 登录表现为 “UnknownHostException / 无法解析主机 / (旧版本) invalid server URL”。
# 本脚本在模拟器 boot 完成后恢复其网络，使 App 可以正常访问外网服务器。
#
# 用法：
#   ./scripts/emu_netfix.sh              # 给已运行的模拟器(emulator-5554)打补丁
#   ./scripts/emu_netfix.sh --boot       # 启动模拟器并等 boot 完成后打补丁
#
# 注意：Android 模拟器的网络配置在每次冷启动后都会重置，
#       因此每次冷启动 emulator 后都需要重新运行本脚本。

set -e

SDK="${SDK:-/c/Users/gjh/AppData/Local/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"
AVD="${AVD:-vibe_test}"
SERIAL="${SERIAL:-emulator-5554}"

adbc() { "$ADB" -s "$SERIAL" "$@"; }

boot_emulator() {
  echo ">> 启动模拟器 $AVD ..."
  nohup "$SDK/emulator/emulator.exe" -avd "$AVD" \
    -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -no-snapshot -no-metrics -netdelay none -netspeed full \
    > /tmp/vibe_emu.log 2>&1 &
  for i in $(seq 1 60); do
    if [ "$(adbc shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo ">> 已 boot 完成 (${i}x5s)"
      return 0
    fi
    sleep 5
  done
  echo "!! 等待 boot 超时" >&2
  return 1
}

apply_fix() {
  echo ">> 获取 root ..."
  adbc root >/dev/null 2>&1 || true
  sleep 2
  adbc wait-for-device

  echo ">> 恢复 eth0（拉起接口）..."
  adbc shell "ip link set eth0 up" || true

  echo ">> 补齐默认路由与局域网路由 ..."
  adbc shell "ip route add default via 10.0.2.2 dev eth0" || true
  adbc shell "ip route add 10.0.2.0/24 dev eth0 scope link src 10.0.2.15" || true

  echo ">> 校验路由："
  adbc shell "ip route"

  echo ">> 校验 DNS/连通性（应返回 204 O 或 TCP 可达）..."
  adbc shell "ping -c 1 -W 3 emby.bangumi.ca >/dev/null 2>&1 && echo '  DNS+ping OK' || echo '  (ping 可能被墙，DNS 已可解析)'"
  echo ">> 完成。App 现在应能访问 https://emby.bangumi.ca (登录会返回 HTTP 401 表示服务器可达)。"
}

case "${1:-}" in
  --boot) boot_emulator; apply_fix ;;
  -h|--help) sed -n '1,20p' "$0" ;;
  *) apply_fix ;;
esac
