#!/bin/bash
#
# Rebuilds the static native dependencies (OpenSSL libcrypto + libplist) that the AirPlay
# library links against, for every Android ABI the app ships.
#
# The results live in app/src/main/cpp/prebuilt/<abi>/{lib,include} and are committed, so a
# normal `./gradlew assembleRelease` needs nothing but the NDK. Run this only to refresh them.
#
# Requirements: Android NDK (set NDK_ROOT), curl, tar, autotools.
#
set -eo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
NDK_ROOT="${NDK_ROOT:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"
API=24

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux)  HOST_TAG=linux-x86_64 ;;
    *) echo "unsupported host $(uname -s)"; exit 1 ;;
esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }

OPENSSL_VER=3.0.16
PLIST_VER=2.6.0

WORK="$REPO/.native-deps-build"
OUT="$REPO/app/src/main/cpp/prebuilt"
mkdir -p "$WORK" "$OUT"
cd "$WORK"

[ -d "openssl-$OPENSSL_VER" ] || {
    echo ">>> fetching openssl $OPENSSL_VER"
    curl -sL -o openssl.tar.gz \
        "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz"
    tar xzf openssl.tar.gz
}
[ -d "libplist-$PLIST_VER" ] || {
    echo ">>> fetching libplist $PLIST_VER"
    curl -sL -o libplist.tar.bz2 \
        "https://github.com/libimobiledevice/libplist/releases/download/$PLIST_VER/libplist-$PLIST_VER.tar.bz2"
    tar xjf libplist.tar.bz2
}

for ABI in arm64-v8a armeabi-v7a x86_64; do
    case $ABI in
        arm64-v8a)   SSL_TARGET=android-arm64;  HOST=aarch64-linux-android;    CCPFX=aarch64-linux-android$API ;;
        armeabi-v7a) SSL_TARGET=android-arm;    HOST=armv7a-linux-androideabi; CCPFX=armv7a-linux-androideabi$API ;;
        x86_64)      SSL_TARGET=android-x86_64; HOST=x86_64-linux-android;     CCPFX=x86_64-linux-android$API ;;
    esac
    DEST="$OUT/$ABI"
    mkdir -p "$DEST/lib" "$DEST/include"

    if [ ! -f "$DEST/lib/libcrypto.a" ]; then
        echo ">>> building openssl for $ABI"
        rm -rf "$WORK/ossl-$ABI"
        cp -R "openssl-$OPENSSL_VER" "$WORK/ossl-$ABI"
        (
            cd "$WORK/ossl-$ABI"
            PATH="$TOOLCHAIN/bin:$PATH" ./Configure "$SSL_TARGET" \
                no-shared no-tests no-engine no-dso no-ui-console no-comp \
                --prefix="$DEST" >/dev/null
            PATH="$TOOLCHAIN/bin:$PATH" make -j8 build_libs >/dev/null
            cp libcrypto.a "$DEST/lib/"
            mkdir -p "$DEST/include/openssl"
            cp -R include/openssl/*.h "$DEST/include/openssl/"
        )
    fi

    if [ ! -f "$DEST/lib/libplist-2.0.a" ]; then
        echo ">>> building libplist for $ABI"
        rm -rf "$WORK/plist-$ABI"
        cp -R "libplist-$PLIST_VER" "$WORK/plist-$ABI"
        (
            cd "$WORK/plist-$ABI"
            export CC="$TOOLCHAIN/bin/${CCPFX}-clang"
            export CXX="$TOOLCHAIN/bin/${CCPFX}-clang++"
            export AR="$TOOLCHAIN/bin/llvm-ar"
            export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
            export STRIP="$TOOLCHAIN/bin/llvm-strip"
            export CFLAGS="-fPIC -O2"
            ./configure --host=$HOST --prefix="$DEST" \
                --enable-static --disable-shared --without-cython --without-tests >/dev/null
            make -j8 >/dev/null
            make install >/dev/null
        )
        # the C++ wrapper and libtool archives are not used by the app
        rm -f "$DEST"/lib/libplist++* "$DEST"/lib/*.la
    fi
    echo "=== $ABI ==="
    ls "$DEST/lib"
done
echo "done; build tree left in $WORK (safe to delete)"
