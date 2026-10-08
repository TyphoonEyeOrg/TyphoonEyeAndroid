# shellcheck shell=bash disable=SC2034  # variables are read by the sourcing scripts
# Shared helpers for scripts/build-github.sh and scripts/build-foss.sh.
# Sourced, not executed. Bash 3.2+ (macOS) and GNU/Linux; no GNU-only flags.
# Never prints secret values: keys and passwords are reported only as set/unset.

# Project release certificate (SHA-256), = AllowedAPKSigningKeys in metadata/seamain.org.typhoonEye.yml.
RELEASE_CERT_SHA256="b7d11c3c8a02bb7228b94bdc8643ec358eef029e38c0588ce0a817a93302ea40"
# Must match abiVersionCodes in app/build.gradle.kts (versionCode = base*10 + n; universal = base*10).
ABIS="armeabi-v7a arm64-v8a x86 x86_64"
abi_code() {
  case "$1" in
    armeabi-v7a) echo 1 ;; arm64-v8a) echo 2 ;; x86) echo 3 ;; x86_64) echo 4 ;; universal) echo 0 ;;
    *) echo "" ;;
  esac
}

# ---------- output ----------
if [ -t 1 ] && [ -t 2 ]; then
  C_RED=$'\033[31m'; C_YEL=$'\033[33m'; C_GRN=$'\033[32m'; C_BLD=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_YEL=""; C_GRN=""; C_BLD=""; C_OFF=""
fi
info() { printf '%s==>%s %s\n' "$C_BLD" "$C_OFF" "$*"; }
ok()   { printf '%s ok%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%swarning:%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }
loud_warn() {
  printf '%s\n' "${C_RED}############################################################################" >&2
  printf '%s\n' "# WARNING: $*" >&2
  printf '%s\n' "############################################################################${C_OFF}" >&2
}

# ---------- repo root ----------
# cd_repo_root SCRIPT_PATH: cd to the repository containing the script (works from any cwd).
cd_repo_root() {
  local script_dir
  script_dir="$(cd "$(dirname "$1")" && pwd)"
  cd "$script_dir/.." || die "cannot cd to repo root"
  [ -f gradlew ] && [ -f app/build.gradle.kts ] && [ -f version.properties ] \
    || die "repo root not found (expected gradlew, app/build.gradle.kts, version.properties in $(pwd))"
  REPO_ROOT="$(pwd)"
}

# ---------- properties ----------
# prop_from_file KEY FILE -> raw value of the last `KEY=value` line (Java Properties: last wins).
# Handles spaces around '=' and CRLF; no escape processing.
prop_from_file() {
  local key="$1" file="$2"
  [ -f "$file" ] || return 0
  awk -v k="$key" '
    { sub(/\r$/, "") }
    /^[ \t]*[#!]/ { next }
    {
      line = $0
      sub(/^[ \t]+/, "", line)
      eq = index(line, "=")
      if (eq == 0) next
      name = substr(line, 1, eq - 1)
      sub(/[ \t]+$/, "", name)
      if (name != k) next
      val = substr(line, eq + 1)
      sub(/^[ \t]+/, "", val)
      v = val; found = 1
    }
    END { if (found) printf "%s", v }
  ' "$file"
}

# prop_or_env KEY [FILE] -> local.properties value, else environment value (Gradle's order).
prop_or_env() {
  local key="$1" file="${2:-local.properties}" v
  v="$(prop_from_file "$key" "$file")"
  [ -n "$v" ] || v="$(printenv "$key" 2>/dev/null || true)"
  printf '%s' "$v"
}

set_or_unset() { if [ -n "$1" ]; then printf 'set'; else printf 'unset'; fi; }

# ---------- toolchain ----------
JAVA_BIN=""; JAVA_VERSION=""; JAVA_VENDOR_LINE=""
check_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  else
    die "no JDK found. Install JDK 21 (Temurin 21 recommended) and set JAVA_HOME or put java on PATH."
  fi
  local out
  out="$("$JAVA_BIN" -version 2>&1)" || die "'$JAVA_BIN -version' failed"
  JAVA_VERSION="$(printf '%s\n' "$out" | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
  JAVA_VENDOR_LINE="$(printf '%s\n' "$out" | sed -n '2p')"
  [ "${JAVA_VERSION%%.*}" = "21" ] || die "JDK 21 required, found '${JAVA_VERSION:-unknown}' ($JAVA_BIN). Point JAVA_HOME at a JDK 21."
  ok "JDK $JAVA_VERSION — $JAVA_VENDOR_LINE"
}

SDK_DIR=""
check_sdk() {
  local from_props
  from_props="$(prop_from_file sdk.dir "$REPO_ROOT/local.properties")"
  if [ -n "${ANDROID_HOME:-}" ]; then SDK_DIR="$ANDROID_HOME"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then SDK_DIR="$ANDROID_SDK_ROOT"
  elif [ -n "$from_props" ]; then SDK_DIR="$from_props"
  else die "Android SDK not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT), or add sdk.dir=/path/to/sdk to local.properties."
  fi
  [ -d "$SDK_DIR" ] || die "Android SDK directory does not exist: $SDK_DIR"
  [ -n "$(latest_build_tool aapt2)" ] || die "no build-tools with aapt2/apksigner in $SDK_DIR (sdkmanager \"build-tools;36.0.0\")"
  export ANDROID_HOME="$SDK_DIR"
  ok "Android SDK $SDK_DIR (build-tools $(basename "$(dirname "$(latest_build_tool aapt2)")"))"
}

# latest_build_tool NAME -> path of NAME in the highest build-tools version, or empty.
latest_build_tool() {
  local dir
  dir="$(for d in "$SDK_DIR"/build-tools/*/; do [ -d "$d" ] && basename "$d"; done | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)"
  if [ -n "$dir" ] && [ -x "$SDK_DIR/build-tools/$dir/$1" ]; then
    printf '%s' "$SDK_DIR/build-tools/$dir/$1"
  fi
}

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else sha256sum "$1" | awk '{print $1}'; fi
}

# apk_version_code APK -> versionCode via aapt2 (apkanalyzer fallback).
apk_version_code() {
  local aapt2 vc="" an
  aapt2="$(latest_build_tool aapt2)"
  if [ -n "$aapt2" ]; then
    vc="$("$aapt2" dump badging "$1" 2>/dev/null | sed -n "s/^package:.* versionCode='\([0-9]*\)'.*/\1/p" | head -n 1)"
  fi
  if [ -z "$vc" ]; then
    an="$SDK_DIR/cmdline-tools/latest/bin/apkanalyzer"
    [ -x "$an" ] && vc="$("$an" manifest version-code "$1" 2>/dev/null || true)"
  fi
  printf '%s' "$vc"
}

# abi_of_output NAME FLAVOR BUILD_TYPE: app-[flavor-]<abi|universal>-<type>[-unsigned].apk -> abi | universal
abi_of_output() {
  local n
  n="$(basename "$1" .apk)"; n="${n%-unsigned}"; n="${n#app-}"
  [ -n "$2" ] && n="${n#"$2"-}"
  n="${n%-"$3"}"
  case "$n" in "$3"|"") n="universal" ;; esac
  printf '%s' "$n"
}

# ---------- version ----------
VERSION_NAME_PROP=""; VERSION_CODE_PROP=""
read_version() {
  VERSION_NAME_PROP="$(prop_from_file VERSION_NAME "$REPO_ROOT/version.properties")"
  VERSION_CODE_PROP="$(prop_from_file VERSION_CODE "$REPO_ROOT/version.properties")"
  [ -n "$VERSION_NAME_PROP" ] && [ -n "$VERSION_CODE_PROP" ] || die "VERSION_NAME / VERSION_CODE missing in version.properties"
  case "$VERSION_CODE_PROP" in *[!0-9]*) die "VERSION_CODE in version.properties is not a number" ;; esac
  info "version.properties: VERSION_NAME=$VERSION_NAME_PROP VERSION_CODE=$VERSION_CODE_PROP (APK versionCodes ${VERSION_CODE_PROP}0 universal, ${VERSION_CODE_PROP}1..${VERSION_CODE_PROP}4 per ABI)"
}

# ---------- git ----------
current_branch() { git symbolic-ref --quiet --short HEAD 2>/dev/null || echo "(detached)"; }
# exact_tag PATTERN -> tag pointing exactly at HEAD that matches PATTERN, or empty.
exact_tag() { git describe --tags --exact-match --match "$1" HEAD 2>/dev/null || true; }
is_dirty() { [ -n "$(git status --porcelain 2>/dev/null)" ]; }

has_flavor() { grep -q "create(\"$1\")" "$REPO_ROOT/app/build.gradle.kts"; }
universal_enabled() { grep -Eq 'isUniversalApk[[:space:]]*=[[:space:]]*true' "$REPO_ROOT/app/build.gradle.kts"; }

# ---------- signing ----------
# release_store_path LPFILE -> absolute keystore path Gradle would use (may not exist).
release_store_path() {
  local store
  store="$(prop_or_env RELEASE_STORE_FILE "$1")"
  if [ -z "$store" ]; then printf '%s' "$REPO_ROOT/release.keystore"; return; fi
  case "$store" in /*) printf '%s' "$store" ;; *) printf '%s' "$REPO_ROOT/$store" ;; esac
}

# check_release_signing LPFILE: hard failure unless Gradle will sign with the release keystore.
# (app/build.gradle.kts silently falls back to the DEBUG key when the keystore is missing.)
check_release_signing() {
  local f="$1" path pw alias kpw
  path="$(release_store_path "$f")"
  pw="$(prop_or_env RELEASE_STORE_PASSWORD "$f")"
  alias="$(prop_or_env RELEASE_KEY_ALIAS "$f")"
  kpw="$(prop_or_env RELEASE_KEY_PASSWORD "$f")"
  info "Release signing: keystore $( [ -f "$path" ] && echo found || echo MISSING ), RELEASE_STORE_PASSWORD $(set_or_unset "$pw"), RELEASE_KEY_ALIAS $(set_or_unset "$alias"), RELEASE_KEY_PASSWORD $(set_or_unset "$kpw")"
  [ -f "$path" ] || die "release keystore not found at $path (RELEASE_STORE_FILE in local.properties/env, default ./release.keystore).
  Gradle would silently sign the release APK with the DEBUG key, so the build is refused.
  Configure RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD (see local.properties.example), or use --debug."
  if [ -z "$pw" ] || [ -z "$alias" ] || [ -z "$kpw" ]; then
    die "release signing incomplete: RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD must all be set (local.properties or environment)."
  fi
  ok "release keystore and credentials present"
}

# verify_release_signature APK: apksigner verify; the only signer must be the project release cert.
verify_release_signature() {
  local apk="$1" apksigner out digest n
  apksigner="$(latest_build_tool apksigner)"
  [ -n "$apksigner" ] || die "apksigner not found under $SDK_DIR/build-tools"
  out="$("$apksigner" verify --print-certs "$apk" 2>&1)" || die "apksigner verify failed for $(basename "$apk")"
  n="$(printf '%s\n' "$out" | grep -c 'certificate SHA-256 digest:' || true)"
  digest="$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' | head -n 1 | tr 'A-F' 'a-f')"
  if printf '%s\n' "$out" | grep -q 'certificate DN: .*CN=Android Debug'; then
    die "$(basename "$apk") is signed with the Android DEBUG certificate ($digest) — release keystore was not used."
  fi
  [ "$n" = 1 ] || die "$(basename "$apk") has $n signer certificates, expected exactly 1"
  [ "$digest" = "$RELEASE_CERT_SHA256" ] || die "$(basename "$apk") is NOT signed with the project release certificate (got ${digest:-none}, expected $RELEASE_CERT_SHA256)."
  ok "$(basename "$apk"): signer SHA-256 $RELEASE_CERT_SHA256"
}

# check_version_code APK ABI
check_version_code() {
  local vc expected
  vc="$(apk_version_code "$1")"
  expected="$((VERSION_CODE_PROP * 10 + $(abi_code "$2")))"
  [ "$vc" = "$expected" ] || die "$(basename "$1"): versionCode ${vc:-unknown}, expected $expected ($2). Is VERSION_CODE overridden in local.properties/env?"
}

# ---------- gradle ----------
gradle() { info "./gradlew $*"; ./gradlew "$@"; }

# ---------- dist ----------
# start_dist DIR: empty DIR of earlier APKs/SHA256SUMS.
start_dist() { mkdir -p "$1"; rm -f "$1"/*.apk "$1/SHA256SUMS"; }

# finish_dist DIR COMMIT: write SHA256SUMS (with commit/version header) and print the summary table.
finish_dist() {
  local dir="$1" commit="$2" f name size
  {
    echo "# TyphoonEye $VERSION_NAME_PROP (versionCode base $VERSION_CODE_PROP)"
    echo "# commit $commit"
    for f in "$dir"/*.apk; do [ -f "$f" ] && printf '%s  %s\n' "$(sha256_of "$f")" "$(basename "$f")"; done
  } > "$dir/SHA256SUMS"
  printf '\n%-62s %-12s %9s  %s\n' "FILE" "VERSIONCODE" "SIZE" "SHA-256"
  for f in "$dir"/*.apk; do
    [ -f "$f" ] || continue
    name="$(basename "$f")"
    size="$(wc -c < "$f" | tr -d ' ')"
    printf '%-62s %-12s %9s  %s\n' "$name" "$(apk_version_code "$f")" \
      "$(awk -v b="$size" 'BEGIN{printf "%.1f MB", b/1048576}')" "$(sha256_of "$f")"
  done
  printf '\n'
  ok "$(cd "$dir" && pwd)/ (SHA256SUMS for commit $commit)"
}
