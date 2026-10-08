#!/usr/bin/env bash
# Build the F-Droid (foss) edition of TyphoonEye locally, like the CI fdroid release step.
# Run from anywhere:  scripts/build-foss.sh [--skip-tests] [--debug] [--force] [--compare DIR] [-h|--help]
# The build runs in a temporary clean `git worktree` of HEAD; your checkout and its
# local.properties are never modified. Secrets are never printed, only reported as set/unset.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/build-foss.sh [options]

Builds the F-Droid edition from branch foss (or HEAD exactly at an fdroid-* tag) in a temporary
clean worktree of HEAD, then copies the signed per-ABI release APKs to dist/foss/ under the names
used by the fdroid-* GitHub releases (fdroiddata Binaries):
  app-fdroid-<abi>-release-signed.apk   armeabi-v7a/arm64-v8a/x86/x86_64 (versionCode <base>1..4)
plus SHA256SUMS (with the commit hash). The APKs are checked for any API key value found in
local.properties / the environment.

Options:
  --skip-tests     skip testFdroidDebugUnitTest
  --debug          build the debug variant (debug key, no signing checks) into dist/foss-debug/
  --force          allow a branch other than foss / fdroid-* tag (loud warning). A dirty work
                   tree is always refused (the build uses the committed HEAD).
  --compare DIR    compare each APK with the CI/F-Droid APK of the same name in DIR using
                   apksigcopier (pip install apksigcopier)
  -h, --help       show this help

Requirements: JDK 21 (Temurin 21.0.12 like CI / F-Droid for byte-identical output); Android SDK via
ANDROID_HOME / ANDROID_SDK_ROOT / sdk.dir; for release builds RELEASE_STORE_FILE (or
./release.keystore), RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD in
local.properties or the environment. Missing signing setup is a hard error.
USAGE
}

# shellcheck source-path=SCRIPTDIR source=lib/build-common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/build-common.sh"

OPT_SKIP_TESTS=0; OPT_FORCE=0; OPT_DEBUG=0; COMPARE_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-tests) OPT_SKIP_TESTS=1 ;;
    --force) OPT_FORCE=1 ;;
    --debug) OPT_DEBUG=1 ;;
    --compare) [ $# -ge 2 ] || die "--compare needs a directory"; COMPARE_DIR="$2"; shift ;;
    --compare=*) COMPARE_DIR="${1#--compare=}" ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done
if [ -n "$COMPARE_DIR" ]; then
  [ -d "$COMPARE_DIR" ] || die "--compare: not a directory: $COMPARE_DIR"
  COMPARE_DIR="$(cd "$COMPARE_DIR" && pwd)"
fi

cd_repo_root "${BASH_SOURCE[0]}"
LP="$REPO_ROOT/local.properties"
BTYPE=release; [ "$OPT_DEBUG" = 1 ] && BTYPE=debug
[ -z "$COMPARE_DIR" ] || [ "$BTYPE" = release ] || die "--compare only makes sense for release builds"
CI_JDK="21.0.12"
info "TyphoonEye F-Droid edition — $BTYPE build of $REPO_ROOT"

check_jdk
case "$JAVA_VERSION:$JAVA_VENDOR_LINE" in
  "$CI_JDK"*:*Temurin*) ok "JDK matches CI / F-Droid (Temurin $CI_JDK)" ;;
  *) warn "JDK is not Temurin $CI_JDK (CI / F-Droid pin it). The APK is valid but may not be byte-identical to F-Droid's build." ;;
esac
check_sdk
read_version
has_flavor fdroid || die "app/build.gradle.kts has no 'fdroid' product flavor; the F-Droid edition is built from the foss branch."

# --- branch / tag guard ---
BRANCH="$(current_branch)"
TAG="$(exact_tag 'fdroid-*')"
COMMIT="$(git rev-parse HEAD)"
if [ "$BRANCH" = foss ] || [ -n "$TAG" ]; then
  ok "HEAD: branch $BRANCH${TAG:+, tag $TAG} ($COMMIT)"
elif [ "$OPT_FORCE" = 1 ]; then
  loud_warn "HEAD is on '$BRANCH', not foss or an fdroid-* tag. Building anyway because of --force. Do NOT publish these APKs."
else
  die "the F-Droid edition is built from branch foss or an fdroid-* tag; HEAD is on '$BRANCH' (no fdroid-* tag). Check out foss (or pass --force for a throwaway build)."
fi
if [ -n "$TAG" ] && [ "$TAG" != "fdroid-$VERSION_NAME_PROP" ]; then
  die "tag $TAG does not match version.properties VERSION_NAME=$VERSION_NAME_PROP"
fi
if is_dirty; then
  die "work tree has uncommitted changes (see git status). This script builds the committed HEAD ($COMMIT) in a clean worktree; commit or stash first."
fi

# --- signing preflight (hard failure), from the main checkout's local.properties / env ---
if [ "$BTYPE" = release ]; then
  check_release_signing "$LP"
else
  warn "--debug: signed with the local debug key; output goes to dist/foss-debug/ and must not be published"
fi

# --- temporary clean worktree; always removed ---
WT_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/typhooneye-foss.XXXXXX")"
WT="$WT_PARENT/src"
LEAK_FILE="$WT_PARENT/leak-values"
cleanup() {
  local rc=$?
  trap - EXIT INT TERM HUP
  if [ -d "$WT" ]; then
    (cd "$WT" && ./gradlew --stop >/dev/null 2>&1) || true
    git -C "$REPO_ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true
  fi
  rm -rf "$WT_PARENT"
  git -C "$REPO_ROOT" worktree prune >/dev/null 2>&1 || true
  [ "$rc" = 0 ] || warn "build failed (exit $rc); temporary worktree removed"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Values of every data-source key configured locally (never printed); the APK must contain none.
( umask 077; : > "$LEAK_FILE" )
LEAK_NAMES="$( { { grep -E '^[[:space:]]*(JUHE[A-Z_]*|QWEATHER_[A-Z_]*|AMAP_KEY)[[:space:]]*=' "$LP" 2>/dev/null || true; } | sed -e 's/=.*//' -e 's/[[:space:]]//g'
                 { env | grep -E '^(JUHE[A-Z_]*|QWEATHER_[A-Z_]*|AMAP_KEY)=' || true; } | sed 's/=.*//'; } | sort -u | tr "\n" " " | sed "s/ $//")"
for k in $LEAK_NAMES; do
  v="$(prop_or_env "$k" "$LP")"
  case "$k" in
    QWEATHER_HOST) case "$v" in *devapi.qweather.com*|*//api.qweather.com*|api.qweather.com*) continue ;; esac ;;  # public default host, in app code
    QWEATHER_PRIVATE_KEY|QWEATHER_PROJECT_KEY)  # PEM: search a chunk of the base64 body
      v="$(printf '%s' "$v" | sed -e 's/\\n/ /g' -e 's/-----[A-Z ]*-----//g' | tr -d ' \t\r\n' | cut -c1-40)" ;;
  esac
  if [ "${#v}" -ge 6 ]; then printf '%s\n' "$v" >> "$LEAK_FILE"; fi
done
unset v
LEAK_COUNT="$(wc -l < "$LEAK_FILE" | tr -d ' ')"
info "Key check: ${LEAK_NAMES:-none} configured locally ($LEAK_COUNT value(s) to search for; values not shown)"

info "Creating clean worktree of $COMMIT in $WT"
git worktree add --detach "$WT" "$COMMIT" >/dev/null

# Fresh local.properties like CI: sdk.dir + RELEASE_* only (0600, deleted with the worktree).
# Keystore path made absolute (release.keystore is not in git). Values copied, never printed.
(
  umask 077
  {
    echo "sdk.dir=$SDK_DIR"
    if [ "$BTYPE" = release ]; then
      echo "RELEASE_STORE_FILE=$(release_store_path "$LP")"
      for k in RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
        raw="$(prop_from_file "$k" "$LP")"
        # env values are escaped for the properties format; file values are copied verbatim
        [ -n "$raw" ] || raw="$( { printenv "$k" || true; } | sed 's/\\/\\\\/g')"
        printf '%s=%s\n' "$k" "$raw"
      done
    fi
  } > "$WT/local.properties"
)

cd "$WT"
# Gradle prefers VERSION_NAME/VERSION_CODE from the environment over version.properties; CI blanks them.
unset VERSION_NAME VERSION_CODE
gradle --stop || true
if [ "$OPT_SKIP_TESTS" = 0 ]; then gradle testFdroidDebugUnitTest; else warn "--skip-tests: unit tests not run"; fi
if [ "$BTYPE" = release ]; then gradle assembleFdroidRelease; else gradle assembleFdroidDebug; fi
OUT_DIR="$WT/app/build/outputs/apk/fdroid/$BTYPE"

# --- verify ---
for abi in $ABIS; do
  apk="$OUT_DIR/app-fdroid-$abi-$BTYPE.apk"
  [ -f "$apk" ] || die "expected APK missing: app-fdroid-$abi-$BTYPE.apk"
  check_version_code "$apk" "$abi"
  if [ "$LEAK_COUNT" -gt 0 ]; then
    rm -rf "$WT_PARENT/unzipped"; mkdir "$WT_PARENT/unzipped"
    unzip -q -o "$apk" -d "$WT_PARENT/unzipped"
    if grep -r -a -F -q -f "$LEAK_FILE" "$WT_PARENT/unzipped"; then
      die "$(basename "$apk") contains the value of a locally configured key ($LEAK_NAMES). The F-Droid APK must not carry API keys; nothing copied to dist/."
    fi
    rm -rf "$WT_PARENT/unzipped"
  fi
  if [ "$BTYPE" = release ]; then verify_release_signature "$apk"; fi
done
for extra in "$OUT_DIR"/*.apk; do
  case " $ABIS " in *" $(abi_of_output "$extra" fdroid "$BTYPE") "*) ;; *) die "unexpected APK $(basename "$extra") (the F-Droid edition has no universal APK)" ;; esac
done
if [ "$LEAK_COUNT" -gt 0 ]; then ok "no configured key value found in any APK (dex, resources, assets)"; fi

# --- collect (F-Droid release asset names) ---
cd "$REPO_ROOT"
if [ "$BTYPE" = release ]; then DIST="dist/foss"; SUFFIX="release-signed"; else DIST="dist/foss-debug"; SUFFIX="debug"; fi
start_dist "$DIST"
for abi in $ABIS; do
  cp "$OUT_DIR/app-fdroid-$abi-$BTYPE.apk" "$DIST/app-fdroid-$abi-$SUFFIX.apk"
done
finish_dist "$DIST" "$COMMIT"

# --- optional comparison with CI / F-Droid APKs ---
if [ -n "$COMPARE_DIR" ]; then
  if ! command -v apksigcopier >/dev/null 2>&1; then
    warn "--compare: apksigcopier not installed (pip install apksigcopier); comparison skipped"
  else
    fails=0
    for abi in $ABIS; do
      name="app-fdroid-$abi-$SUFFIX.apk"
      if [ ! -f "$COMPARE_DIR/$name" ]; then warn "--compare: $COMPARE_DIR/$name not found"; fails=$((fails + 1)); continue; fi
      if apksigcopier compare "$COMPARE_DIR/$name" "$DIST/$name" >/dev/null 2>&1; then
        ok "$name: identical to $COMPARE_DIR/$name (apksigcopier compare)"
      else
        warn "$name: DIFFERS from $COMPARE_DIR/$name"; fails=$((fails + 1))
      fi
    done
    [ "$fails" = 0 ] || die "--compare: $fails APK(s) missing or not reproducible"
  fi
fi
info "JDK used: $JAVA_VERSION ($JAVA_VENDOR_LINE); CI and F-Droid pin Temurin $CI_JDK."
