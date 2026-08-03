#!/bin/sh
# Build a *redistributable* ken_ceres_jvm: one dylib that depends on nothing but the system.
#
# `buildCeresNative` links the Ceres that `find_package` finds, which on this machine is Homebrew's.
# That build is right for iterating on the wrapper and wrong for shipping: it carries
# /opt/homebrew/opt/{ceres-solver,glog,gflags} in its load commands, so it only runs where Homebrew
# is installed at the same prefix. The committed libs/libken_ceres_jvm.dylib has the property we
# want — `otool -L` shows only libc++ and libSystem — but it is the ABI v1 artifact from June and
# was built without a sparse backend.
#
# This script reproduces the v1 property for the current wrapper, with sparse support, by building a
# static Ceres from pinned source:
#
#   * Ceres 2.2.0 — the version every measurement in dev_docs/CERES.md was taken against.
#   * Eigen 3.4.0 — pinned because Ceres 2.2.0's find_package(Eigen3 3.3) *rejects* the Eigen 5.x
#     that Homebrew now installs, so "whatever is on the machine" is not a stable input.
#   * MINIGLOG=ON, so there is no libglog/libgflags to link. Miniglog writes to std::cerr and gates
#     nothing but by MAX_LOG_LEVEL, which Ceres defaults to 2 — two VLOG levels of it — so the smoke
#     suite emits ~11k lines of "Allocating values array with N bytes". MINIGLOG_MAX_LOG_LEVEL=-3 is
#     FATAL, which is what quiet_glog_once() asks of real glog (FLAGS_stderrthreshold = GLOG_FATAL).
#     Miniglog's severities run the other way from glog's: FATAL -3, ERROR -2, WARNING -1, INFO 0.
#   * EIGENSPARSE=ON with EIGENMETIS=OFF. Eigen's sparse Cholesky is header-only, so
#     SPARSE_NORMAL_CHOLESKY — the polytope realizer's default, and Ceres silently falls back to a
#     dense solve without it — costs no extra shared library. Eigen's *METIS ordering* would pull in
#     libmetis, so it is off; that changes the fill-reducing ordering, not what can be solved.
#
# Downloads are checksummed. Nothing here is vendored, so the first run needs the network; after
# that the tarballs are cached under build/ceres-dist/downloads.
#
#   ./native/ken-ceres/build_redistributable.sh
#   ./gradlew smokeCeres -PceresLibrary=build/ken-ceres-redistributable/libken_ceres_jvm.dylib

set -eu

CERES_VERSION=2.2.0
CERES_SHA256=12efacfadbfdc1bbfa203c236e96f4d3c210bed96994288b3ff0c8e7c6f350d4
CERES_URL="https://github.com/ceres-solver/ceres-solver/archive/refs/tags/${CERES_VERSION}.tar.gz"

EIGEN_VERSION=3.4.0
EIGEN_SHA256=8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72
EIGEN_URL="https://gitlab.com/libeigen/eigen/-/archive/${EIGEN_VERSION}/eigen-${EIGEN_VERSION}.tar.gz"

# FATAL only. See the header comment; this is Ceres' own cache variable, not a redefinition of its macro.
LOG_LEVEL=-3

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
WORK="$REPO_ROOT/build/ceres-dist"
DOWNLOADS="$WORK/downloads"
PREFIX="$WORK/prefix"
OUT_DIR="$REPO_ROOT/build/ken-ceres-redistributable"

for tool in cmake curl tar shasum otool; do
    command -v "$tool" >/dev/null 2>&1 || { echo "error: $tool is required and was not found" >&2; exit 1; }
done

mkdir -p "$DOWNLOADS" "$PREFIX" "$OUT_DIR"

# Fetch once, verify every time: a cached tarball that no longer matches is a corrupted cache, not a
# reason to carry on.
fetch() {
    url=$1; sha=$2; dest=$3
    if [ -f "$dest" ] && [ "$(shasum -a 256 "$dest" | cut -d' ' -f1)" = "$sha" ]; then
        echo "  cached  $(basename "$dest")"
        return 0
    fi
    echo "  fetch   $(basename "$dest")"
    curl -sSL -o "$dest.tmp" "$url"
    actual=$(shasum -a 256 "$dest.tmp" | cut -d' ' -f1)
    if [ "$actual" != "$sha" ]; then
        rm -f "$dest.tmp"
        echo "error: $(basename "$dest") sha256 mismatch" >&2
        echo "  expected $sha" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi
    mv "$dest.tmp" "$dest"
}

echo "sources"
fetch "$CERES_URL" "$CERES_SHA256" "$DOWNLOADS/ceres-$CERES_VERSION.tar.gz"
fetch "$EIGEN_URL" "$EIGEN_SHA256" "$DOWNLOADS/eigen-$EIGEN_VERSION.tar.gz"

[ -d "$WORK/ceres-solver-$CERES_VERSION" ] || tar xzf "$DOWNLOADS/ceres-$CERES_VERSION.tar.gz" -C "$WORK"
[ -d "$WORK/eigen-$EIGEN_VERSION" ] || tar xzf "$DOWNLOADS/eigen-$EIGEN_VERSION.tar.gz" -C "$WORK"

echo "eigen $EIGEN_VERSION (headers only)"
cmake -S "$WORK/eigen-$EIGEN_VERSION" -B "$WORK/eigen-build" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DBUILD_TESTING=OFF >/dev/null
cmake --install "$WORK/eigen-build" >/dev/null

echo "ceres $CERES_VERSION (static, miniglog, eigen sparse)"
cmake -S "$WORK/ceres-solver-$CERES_VERSION" -B "$WORK/ceres-build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DEigen3_DIR="$PREFIX/share/eigen3/cmake" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DMINIGLOG=ON \
    -DMINIGLOG_MAX_LOG_LEVEL="$LOG_LEVEL" \
    -DGFLAGS=OFF \
    -DEIGENSPARSE=ON \
    -DEIGENMETIS=OFF \
    -DSUITESPARSE=OFF \
    -DACCELERATESPARSE=OFF \
    -DLAPACK=OFF \
    -DUSE_CUDA=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_BENCHMARKS=OFF \
    -DBUILD_DOCUMENTATION=OFF \
    -DPROVIDE_UNINSTALL_TARGET=OFF >/dev/null
cmake --build "$WORK/ceres-build" --parallel >/dev/null
cmake --install "$WORK/ceres-build" >/dev/null

echo "wrapper (static)"
# Miniglog installs its glog/logging.h under ceres/internal/miniglog, and ceres headers include it
# as "glog/logging.h", so that directory has to be on the include path too.
cmake -S "$SCRIPT_DIR" -B "$WORK/wrapper" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="-DMAX_LOG_LEVEL=$LOG_LEVEL" \
    -DKEN_CERES_SYSTEM_CERES=OFF \
    -DKEN_CERES_STATIC_LIB="$PREFIX/lib/libceres.a" \
    -DKEN_CERES_INCLUDE_DIRS="$PREFIX/include;$PREFIX/include/eigen3;$PREFIX/include/ceres/internal/miniglog" >/dev/null
cmake --build "$WORK/wrapper" --parallel >/dev/null

cp "$WORK/wrapper/libken_ceres_jvm.dylib" "$OUT_DIR/libken_ceres_jvm.dylib"

# The whole point of this script is the load commands, so assert them rather than trusting the flags.
# Anything outside /usr/lib and /System is a dependency the target machine has to already have.
leaked=$(otool -L "$OUT_DIR/libken_ceres_jvm.dylib" | tail -n +2 | awk '{print $1}' \
    | grep -v '^@rpath/libken_ceres_jvm.dylib$' \
    | grep -v '^/usr/lib/' \
    | grep -v '^/System/Library/' || true)
if [ -n "$leaked" ]; then
    echo "error: the result is not redistributable; it still links" >&2
    echo "$leaked" | sed 's/^/  /' >&2
    exit 1
fi

abi=$(strings "$OUT_DIR/libken_ceres_jvm.dylib" | grep -m1 '^ken_ceres_jvm_abi_v' || echo "unknown")
size=$(du -h "$OUT_DIR/libken_ceres_jvm.dylib" | cut -f1)
echo
echo "ken_ceres_jvm -> $OUT_DIR/libken_ceres_jvm.dylib"
echo "  abi           $abi"
echo "  size          $size"
echo "  links         $(otool -L "$OUT_DIR/libken_ceres_jvm.dylib" | tail -n +3 | wc -l | tr -d ' ') system libraries only"
echo
echo "verify it:"
echo "  ./gradlew smokeCeres -PceresLibrary=build/ken-ceres-redistributable/libken_ceres_jvm.dylib"
