#!/usr/bin/env bash
set -euo pipefail
proof_folder="$PWD/app/build/tor-proof"
mkdir -p "$proof_folder"
command -v tcpdump >/dev/null
sudo sh -c 'echo "$$" > "$1"; exec tcpdump -i any -n -s 0 -U -w "$2" "udp port 53 or tcp port 53"' sh "$proof_folder/tcpdump.pid" "$proof_folder/tor-dns.pcap" >"$proof_folder/tcpdump.log" 2>&1 &
tor_capture_launcher=$!
finish_tor_capture() {
  tor_test_exit=$?
  set +e
  tor_capture_pid=$(cat "$proof_folder/tcpdump.pid")
  sudo kill -0 "$tor_capture_pid" 2>/dev/null
  tor_capture_alive=$?
  sudo kill -INT "$tor_capture_pid" 2>/dev/null
  wait "$tor_capture_launcher"
  sudo chown "$(id -u):$(id -g)" "$proof_folder/tor-dns.pcap"
  python3 validation/verify_tor_dns.py "$proof_folder"
  tor_dns_exit=$?
  adb exec-out run-as com.chk.agentbrowser.debug tar -C files/tor-proof -cf - . | tar -xf - -C "$proof_folder"
  if [ "$tor_test_exit" -ne 0 ]; then exit "$tor_test_exit"; fi
  if [ "$tor_capture_alive" -ne 0 ]; then echo 'DNS capture did not stay alive for the complete test'; exit 1; fi
  exit "$tor_dns_exit"
}
trap finish_tor_capture EXIT
"$@"
