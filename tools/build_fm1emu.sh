#!/bin/sh
# Builds fm1-emu's fm1-web library (Joakim Eriksson, MIT: https://github.com/joakimeriksson/fm1-emu) for Android, at
# the tag below, into app/src/main/jniLibs/<abi>/libfm1_web.so (not in the tree: built). Needs Rust with the
# aarch64-linux-android and x86_64-linux-android targets, and the NDK (ANDROID_HOME, NDK_VERSION).
set -e
FM1EMU_TAG=v0.5.1
FM1EMU_COMMIT=c7e47c4
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/${NDK_VERSION:-29.0.14206865}}"
T="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$T/clang" ] || { echo "build_fm1emu: no NDK at $NDK" >&2; exit 1; }
SRC=build/fm1-emu
if [ ! -d "$SRC/.git" ] || [ "$(git -C "$SRC" rev-parse --short=7 HEAD)" != "$FM1EMU_COMMIT" ]; then
    rm -rf "$SRC"
    git clone -q --depth 1 --branch "$FM1EMU_TAG" https://github.com/joakimeriksson/fm1-emu.git "$SRC"
fi
[ "$(git -C "$SRC" rev-parse --short=7 HEAD)" = "$FM1EMU_COMMIT" ] || { echo "build_fm1emu: $FM1EMU_TAG is not $FM1EMU_COMMIT" >&2; exit 1; }
for pair in aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64; do
    target=${pair%%:*}; abi=${pair##*:}
    up=$(echo "$target" | tr 'a-z-' 'A-Z_'); low=$(echo "$target" | tr '-' '_')
    env "CARGO_TARGET_${up}_LINKER=$T/${target}26-clang" "CC_${low}=$T/${target}26-clang" "AR_${low}=$T/llvm-ar" \
        cargo build -q --release --manifest-path "$SRC/Cargo.toml" -p fm1-web --target "$target"
    mkdir -p "app/src/main/jniLibs/$abi"
    cp "$SRC/target/$target/release/libfm1_web.so" "app/src/main/jniLibs/$abi/"
    "$T/llvm-strip" --strip-unneeded "app/src/main/jniLibs/$abi/libfm1_web.so"
done
echo "fm1-emu $FM1EMU_TAG ($FM1EMU_COMMIT): $(ls app/src/main/jniLibs/*/libfm1_web.so | wc -l) libraries"
