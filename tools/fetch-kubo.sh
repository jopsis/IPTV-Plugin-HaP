#!/usr/bin/env bash
# Compiles kubo (IPFS) as libipfs.so for the Android ABIs HaP bundles, and
# drops the binaries into app/src/main/jniLibs/<abi>/libipfs.so.
#
# Requires: go >= 1.21, git.
# arm64-v8a builds with CGO_ENABLED=0 (no NDK needed): the Go runtime's TLS
# handling works with plain syscalls on 64-bit ARM Android.
# armeabi-v7a REQUIRES the Android NDK: "android/arm requires external (cgo)
# linking" is enforced by the Go toolchain itself, not a choice made here.
# The NDK's own minSdk-tagged clang wrapper is used as CC so cgo can find a
# working C compiler; ANDROID_NDK_HOME (or ANDROID_HOME/ndk/<version>) and
# NDK_ANDROID_API (default 27, matching build.gradle.kts minSdk) select it.
#
# Usage:
#   tools/fetch-kubo.sh                 # build arm64-v8a + armeabi-v7a
#   tools/fetch-kubo.sh arm64-v8a       # build a single ABI
#   KUBO_VERSION=v0.43.0 tools/fetch-kubo.sh

set -euo pipefail

KUBO_VERSION="${KUBO_VERSION:-v0.43.0}"
NDK_ANDROID_API="${NDK_ANDROID_API:-27}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
  ABIS=("arm64-v8a" "armeabi-v7a")
fi

if ! command -v go >/dev/null 2>&1; then
  echo "error: go is not installed or not on PATH" >&2
  exit 1
fi

find_ndk_dir() {
  if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    echo "$ANDROID_NDK_HOME"
    return 0
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  local latest
  latest="$(ls -1 "$sdk/ndk" 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$latest" ]; then
    echo "$sdk/ndk/$latest"
    return 0
  fi
  return 1
}

host_tag() {
  case "$(uname -s)" in
    Darwin) echo "darwin-x86_64" ;;
    Linux) echo "linux-x86_64" ;;
    *) echo "error: unsupported host OS for NDK toolchain lookup: $(uname -s)" >&2; return 1 ;;
  esac
}

echo "Cloning kubo $KUBO_VERSION..."
git clone --depth 1 --branch "$KUBO_VERSION" https://github.com/ipfs/kubo "$WORK_DIR/kubo"

build_abi() {
  local abi="$1"
  local goarch goarm="" cgo_enabled=0 cc=""
  case "$abi" in
    arm64-v8a) goarch="arm64" ;;
    armeabi-v7a)
      goarch="arm"; goarm="7"; cgo_enabled=1
      local ndk_dir host
      ndk_dir="$(find_ndk_dir)" || { echo "error: Android NDK not found; set ANDROID_NDK_HOME (armeabi-v7a needs it, arm64-v8a doesn't)" >&2; return 1; }
      host="$(host_tag)"
      cc="$ndk_dir/toolchains/llvm/prebuilt/$host/bin/armv7a-linux-androideabi${NDK_ANDROID_API}-clang"
      if [ ! -x "$cc" ]; then
        echo "error: NDK clang not found at $cc (check ANDROID_NDK_HOME / NDK_ANDROID_API=$NDK_ANDROID_API)" >&2
        return 1
      fi
      ;;
    *) echo "error: unsupported ABI '$abi' (expected arm64-v8a or armeabi-v7a)" >&2; return 1 ;;
  esac

  local out_dir="$JNI_LIBS_DIR/$abi"
  mkdir -p "$out_dir"
  echo "Building kubo for $abi (GOARCH=$goarch${goarm:+ GOARM=$goarm}${cc:+, CC=$cc})..."
  (
    cd "$WORK_DIR/kubo"
    # -tags untested_go_version: github.com/cockroachdb/swiss (a kubo
    #   transitive dep) only ships its go:linkname runtime shim for Go
    #   versions it has explicitly tested; this opts in on newer toolchains.
    # -ldflags -checklinkname=0: github.com/wlynxg/anet (used by libp2p to
    #   list network interfaces on Android without cgo) links against an
    #   unexported net.zoneCache symbol; Go's stricter linkname signature
    #   check added in 1.23 rejects it on toolchains newer than anet's own
    #   testing matrix. Both are known-safe workarounds, not correctness
    #   fixes: re-check after bumping KUBO_VERSION in case upstream lands
    #   real fixes and these flags become unnecessary.
    env CGO_ENABLED="$cgo_enabled" GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} ${cc:+CC="$cc"} \
      go build -tags untested_go_version -trimpath -ldflags "-s -w -checklinkname=0" \
        -o "$out_dir/libipfs.so" ./cmd/ipfs
  )
  echo "  -> $out_dir/libipfs.so"
}

for abi in "${ABIS[@]}"; do
  build_abi "$abi"
done

echo "Done. libipfs.so is not committed to git (see .gitignore); rerun this script after a clean checkout."
