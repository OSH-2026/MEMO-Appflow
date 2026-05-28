#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/build/memo_libbpf}"
TARGET="${TARGET:-linux}"
CLANG="${CLANG:-clang}"
BPFTOOL="${BPFTOOL:-bpftool}"
LIBBPF_SRC="${LIBBPF_SRC:-$ROOT/dataset_cache/libbpf}"
VMLINUX_BTF="${VMLINUX_BTF:-/sys/kernel/btf/vmlinux}"
BPF_SYSROOT="${BPF_SYSROOT:-}"
SKELETON_MODE="${SKELETON_MODE:-none}"

mkdir -p "$OUT"

echo "== feature checks =="
uname -m
"$BPFTOOL" feature probe kernel unprivileged > "$OUT/bpftool_feature_probe.txt" 2>&1 || true
if [[ -r "$VMLINUX_BTF" ]]; then
  echo "btf_vmlinux=yes"
  "$BPFTOOL" btf dump file "$VMLINUX_BTF" format c > "$OUT/vmlinux.h"
else
  echo "btf_vmlinux=no; building stable tracepoint-layout collector without CO-RE relocations"
fi

if [[ -d "$LIBBPF_SRC/src" ]]; then
  LIBBPF_INCLUDE_OVERLAY="$OUT/libbpf_include"
  mkdir -p "$LIBBPF_INCLUDE_OVERLAY/bpf"
  for header in bpf.h bpf_helpers.h bpf_helper_defs.h bpf_tracing.h bpf_core_read.h bpf_endian.h libbpf_common.h libbpf_legacy.h skel_internal.h; do
    cp "$LIBBPF_SRC/src/$header" "$LIBBPF_INCLUDE_OVERLAY/bpf/$header"
  done
  cat > "$LIBBPF_INCLUDE_OVERLAY/bpf/libbpf_version.h" <<'EOF'
#ifndef __LIBBPF_VERSION_H
#define __LIBBPF_VERSION_H
#define LIBBPF_MAJOR_VERSION 1
#define LIBBPF_MINOR_VERSION 6
#endif
EOF
  BPF_HEADER_DIR="$LIBBPF_INCLUDE_OVERLAY"
  LIBBPF_UAPI_DIR="$LIBBPF_SRC/include/uapi"
else
  BPF_HEADER_DIR="$ROOT/src/bpf/compat"
  LIBBPF_UAPI_DIR=""
  echo "libbpf source not found at $LIBBPF_SRC; using minimal in-repo BPF helper headers for BPF object compilation." >&2
  if [[ "$SKELETON_MODE" == "light" ]]; then
    echo "light skeleton loader requires libbpf source headers for bpf/skel_internal.h" >&2
    exit 3
  fi
fi

ARCH="$(uname -m)"
TARGET_ARCH="x86"
if [[ "$TARGET" == "android-arm64" || "$ARCH" == "aarch64" || "$ARCH" == "arm64" ]]; then
  TARGET_ARCH="arm64"
fi

echo "== compile BPF object =="
BPF_CFLAGS=(
  -g -O2 -target bpf -D__TARGET_ARCH_${TARGET_ARCH}
  -I"$OUT"
  -I"$ROOT/src/bpf"
  -I"$BPF_HEADER_DIR"
)
if [[ -n "$LIBBPF_UAPI_DIR" ]]; then
  BPF_CFLAGS+=("-I$LIBBPF_UAPI_DIR")
fi
if [[ -n "$BPF_SYSROOT" ]]; then
  BPF_CFLAGS+=("--sysroot=$BPF_SYSROOT")
  if [[ "$TARGET_ARCH" == "arm64" ]]; then
    BPF_CFLAGS+=("-I$BPF_SYSROOT/usr/include/aarch64-linux-android")
  fi
fi
"$CLANG" "${BPF_CFLAGS[@]}" \
  -c "$ROOT/src/bpf/memo_appflow.bpf.c" \
  -o "$OUT/memo_appflow.bpf.o"

echo "== generate libbpf skeleton =="
if [[ "$SKELETON_MODE" == "light" ]]; then
  "$BPFTOOL" -L gen skeleton "$OUT/memo_appflow.bpf.o" > "$OUT/memo_appflow.light.skel.h"
elif [[ "$SKELETON_MODE" == "full" ]]; then
  "$BPFTOOL" gen skeleton "$OUT/memo_appflow.bpf.o" > "$OUT/memo_appflow.skel.h"
else
  echo "skeleton generation skipped; Android product loader uses bpftool pinning plus raw bpf/perf syscalls"
fi

echo "== resolve userspace compiler =="
USER_CC="${CC:-cc}"
USER_CFLAGS=(-O2 -g -Wall -Wextra)
USER_LDFLAGS=()

if [[ "$TARGET" == "android-arm64" ]]; then
  : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the Android NDK for TARGET=android-arm64}"
  USER_CC="${CC:-$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang}"
  USER_CFLAGS+=("--target=aarch64-linux-android34")
fi

echo "== compile userspace loader =="
"$USER_CC" "${USER_CFLAGS[@]}" \
  -I"$OUT" \
  ${LIBBPF_UAPI_DIR:+-I"$LIBBPF_UAPI_DIR"} \
  -I"$ROOT/src/bpf" \
  -I"$LIBBPF_SRC/src" \
  -I"$BPF_HEADER_DIR" \
  "$ROOT/src/native/memo_libbpf_collector.c" \
  "${USER_LDFLAGS[@]}" \
  -o "$OUT/memo_libbpf_collector"

echo "built: $OUT/memo_appflow.bpf.o"
if [[ "$SKELETON_MODE" == "light" ]]; then
  echo "built: $OUT/memo_appflow.light.skel.h"
elif [[ "$SKELETON_MODE" == "full" ]]; then
  echo "built: $OUT/memo_appflow.skel.h"
fi
echo "built: $OUT/memo_libbpf_collector"
