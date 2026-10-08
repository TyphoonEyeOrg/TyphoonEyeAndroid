#!/usr/bin/env bash
# Build the GitHub edition of TyphoonEye locally (same tasks and asset names as master CI).
# Run from anywhere:  scripts/build-github.sh [--skip-tests] [--debug] [--force] [-h|--help]
# Secrets (API keys, keystore passwords) are never printed, only reported as set/unset.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/build-github.sh [options]

Builds the GitHub edition from branch master (or HEAD exactly at a v* tag): unit tests,
then the signed release APKs, renamed like the GitHub Release assets into dist/github/:
  TyphoonEye-v<ver>-app-github-release.apk                 universal (versionCode <base>0)
  TyphoonEye-v<ver>-app-github-split-<abi>-release.apk     armeabi-v7a/arm64-v8a/x86/x86_64 (<base>1..4)
plus SHA256SUMS (with the commit hash).

Options:
  --skip-tests   skip the unit tests
  --debug        build the debug variant (debug key, no signing checks) into dist/github-debug/
  --force        allow another branch and/or a dirty work tree (loud warning; not for releases)
  -h, --help     show this help

Requirements: JDK 21; Android SDK via ANDROID_HOME / ANDROID_SDK_ROOT / sdk.dir; for release builds
RELEASE_STORE_FILE (or ./release.keystore), RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS,
RELEASE_KEY_PASSWORD in local.properties or the environment; data-source keys (JUHE_KEY and/or
QWEATHER_*) as in local.properties.example. Missing signing setup is a hard error.
USAGE
}

# shellcheck source-path=SCRIPTDIR source=lib/build-common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/build-common.sh"

OPT_SKIP_TESTS=0; OPT_FORCE=0; OPT_DEBUG=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-tests) OPT_SKIP_TESTS=1 ;;
    --force) OPT_FORCE=1 ;;
    --debug) OPT_DEBUG=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done

cd_repo_root "${BASH_SOURCE[0]}"
LP="$REPO_ROOT/local.properties"
BTYPE=release; [ "$OPT_DEBUG" = 1 ] && BTYPE=debug
info "TyphoonEye GitHub edition — $BTYPE build in $REPO_ROOT"

check_jdk
check_sdk
read_version

# --- branch / tag guard ---
BRANCH="$(current_branch)"
TAG="$(exact_tag 'v*')"
COMMIT="$(git rev-parse HEAD)"
if [ "$BRANCH" = master ] || [ -n "$TAG" ]; then
  ok "HEAD: branch $BRANCH${TAG:+, tag $TAG} ($COMMIT)"
elif [ "$OPT_FORCE" = 1 ]; then
  loud_warn "HEAD is on '$BRANCH', not master or a v* tag. Building anyway because of --force. Do NOT publish these APKs."
else
  die "the GitHub edition is built from branch master or a v* tag; HEAD is on '$BRANCH' (no v* tag). Check out master (or pass --force for a throwaway build)."
fi
if [ -n "$TAG" ] && [ "$TAG" != "v$VERSION_NAME_PROP" ]; then
  die "tag $TAG does not match version.properties VERSION_NAME=$VERSION_NAME_PROP"
fi
if is_dirty; then
  if [ "$OPT_FORCE" = 1 ]; then
    loud_warn "work tree has uncommitted changes; the APKs do not correspond to commit $COMMIT. Continuing because of --force."
  else
    die "work tree has uncommitted changes (see git status). Commit or stash them (or pass --force for a throwaway build)."
  fi
fi

# Gradle prefers VERSION_NAME/VERSION_CODE from local.properties/env over version.properties.
if [ -n "$(prop_from_file VERSION_NAME "$LP")$(prop_from_file VERSION_CODE "$LP")" ]; then
  die "local.properties sets VERSION_NAME/VERSION_CODE, which override version.properties. Remove them."
fi
unset VERSION_NAME VERSION_CODE

# --- tasks: master has no product flavors; foss-line branches have github + fdroid ---
if has_flavor github; then
  FLAVOR=github; TEST_TASK=testGithubDebugUnitTest
  ASSEMBLE="assembleGithub$( [ "$BTYPE" = debug ] && echo Debug || echo Release )"
else
  FLAVOR=""; TEST_TASK=testDebugUnitTest
  ASSEMBLE="assemble$( [ "$BTYPE" = debug ] && echo Debug || echo Release )"
fi
EXPECTED_ABIS="$ABIS"
if universal_enabled; then EXPECTED_ABIS="universal $ABIS"; fi
OUT_DIR="app/build/outputs/apk/${FLAVOR:+$FLAVOR/}$BTYPE"
info "Gradle: $TEST_TASK, $ASSEMBLE (${FLAVOR:-no} flavor); expecting: $EXPECTED_ABIS"

# --- data-source keys (set/unset only) ---
juhe=""; for k in JUHE_KEY JUHE_API_KEY JUHEKEY; do [ -n "$juhe" ] || juhe="$(prop_or_env "$k" "$LP")"; done
qw_key="$(prop_or_env QWEATHER_API_KEY "$LP")"
qw_kid="$(prop_or_env QWEATHER_KID "$LP")"; [ -n "$qw_kid" ] || qw_kid="$(prop_or_env QWEATHER_PUBLIC_ID "$LP")"
qw_proj="$(prop_or_env QWEATHER_PROJECT_ID "$LP")"
qw_pk="$(prop_or_env QWEATHER_PRIVATE_KEY "$LP")"; [ -n "$qw_pk" ] || qw_pk="$(prop_or_env QWEATHER_PROJECT_KEY "$LP")"
qw_jwt=""; if [ -n "$qw_kid" ] && [ -n "$qw_proj" ] && [ -n "$qw_pk" ]; then qw_jwt=1; fi
info "Data-source keys: JUHE_KEY $(set_or_unset "$juhe"), QWEATHER_API_KEY $(set_or_unset "$qw_key"), QWeather JWT (KID/PROJECT_ID/PRIVATE_KEY) $(set_or_unset "$qw_jwt"), QWEATHER_HOST $(set_or_unset "$(prop_or_env QWEATHER_HOST "$LP")"), AMAP_KEY $(set_or_unset "$(prop_or_env AMAP_KEY "$LP")")"
if [ -z "$juhe" ] && [ -z "$qw_key" ] && [ -z "$qw_jwt" ]; then
  warn "no Juhe key and no QWeather credentials: this build will have NO live data source (users see 'no live data source' and the optional demo mode)."
elif [ -z "$juhe" ] || { [ -z "$qw_key" ] && [ -z "$qw_jwt" ]; }; then
  warn "only one of Juhe / QWeather is configured; the other source (and QWeather warnings) will be unavailable."
fi
unset juhe qw_key qw_kid qw_proj qw_pk qw_jwt

# --- signing preflight (hard failure) ---
if [ "$BTYPE" = release ]; then
  check_release_signing "$LP"
else
  warn "--debug: signed with the local debug key; output goes to dist/github-debug/ and must not be published"
fi

# --- build ---
if [ "$OPT_SKIP_TESTS" = 0 ]; then gradle "$TEST_TASK"; else warn "--skip-tests: unit tests not run"; fi
rm -rf "$OUT_DIR"
gradle "$ASSEMBLE"

# --- verify + collect ---
if [ "$BTYPE" = release ]; then DIST="dist/github"; else DIST="dist/github-debug"; fi
start_dist "$DIST"
for abi in $EXPECTED_ABIS; do
  if [ "$abi" = universal ]; then src="$OUT_DIR/app-${FLAVOR:+$FLAVOR-}universal-$BTYPE.apk"
  else src="$OUT_DIR/app-${FLAVOR:+$FLAVOR-}$abi-$BTYPE.apk"; fi
  [ -f "$src" ] || die "expected APK missing: $src"
  check_version_code "$src" "$abi"
  if [ "$BTYPE" = release ]; then verify_release_signature "$src"; fi
  # Same names as the GitHub Release assets made by master CI (the in-app updater relies on them).
  if [ "$abi" = universal ]; then name="TyphoonEye-v$VERSION_NAME_PROP-app-github-$BTYPE.apk"
  else name="TyphoonEye-v$VERSION_NAME_PROP-app-github-split-$abi-$BTYPE.apk"; fi
  cp "$src" "$DIST/$name"
done
for extra in "$OUT_DIR"/*.apk; do
  [ -f "$extra" ] || continue
  case " $EXPECTED_ABIS " in *" $(abi_of_output "$extra" "$FLAVOR" "$BTYPE") "*) ;; *) warn "unexpected extra APK ignored: $(basename "$extra")" ;; esac
done
finish_dist "$DIST" "$COMMIT$(is_dirty && echo ' (dirty)' || true)"
