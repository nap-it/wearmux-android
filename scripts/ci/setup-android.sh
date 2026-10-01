#!/usr/bin/env bash

# Source this script so the build inherits JAVA_HOME and the SDK paths.
set -euo pipefail

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo 'Android CI setup requires a Linux x86_64 runner.' >&2
  return 1
fi

for tool in git tar sha256sum getconf; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing runner utility: $tool. See the CI setup in docs/technical-guide.md." >&2
    return 1
  fi
done
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
  echo 'The runner needs curl or wget to download build tools.' >&2
  return 1
fi

# Android Build Tools 36's native libraries require glibc 2.17. Check the
# runner before downloading anything, rather than failing inside a JVM loader.
libc_version=$(getconf GNU_LIBC_VERSION)
echo "Runner C library: $libc_version"
if [[ ! "$libc_version" =~ ^glibc\ ([0-9]+)\.([0-9]+)$ ]]; then
  echo 'Android CI requires a glibc-based Linux runner (glibc 2.17 or newer).' >&2
  return 1
fi
if (( BASH_REMATCH[1] < 2 || (BASH_REMATCH[1] == 2 && BASH_REMATCH[2] < 17) )); then
  echo 'Android Build Tools 36 require glibc 2.17 or newer. Use a newer runner or a container executor.' >&2
  return 1
fi

export ANDROID_HOME="${ANDROID_HOME:-${CI_PROJECT_DIR:-$PWD}/.android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ci_tools="${CI_PROJECT_DIR:-$PWD}/.ci-tools"
mkdir -p "$ci_tools" "$ANDROID_HOME/cmdline-tools"

download_ci_tool() {
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --retry 3 --connect-timeout 30 \
      "$1" --output "$2"
  else
    wget --quiet --tries=3 --timeout=30 --output-document="$2" "$1"
  fi
}

# Temurin 21 supports older glibc hosts than the JetBrains runtime. The Gradle
# daemon requires Java 21 without a vendor restriction. Use a separate cache
# directory so an incompatible JetBrains JDK from an earlier job is not reused.
if [[ ! -x "$ci_tools/temurin21/bin/java" ]]; then
  (
    download_dir=$(mktemp -d "$ci_tools/jdk-download.XXXXXX")
    trap 'rm -rf "$download_dir"' EXIT
    archive="$download_dir/jdk.tar.gz"
    download_ci_tool \
      'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' \
      "$archive"
    printf '%s  %s\n' \
      ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 \
      "$archive" | sha256sum --check --status
    mkdir "$download_dir/jdk"
    tar -xzf "$archive" --strip-components=1 -C "$download_dir/jdk"
    rm -rf "$ci_tools/temurin21"
    mv "$download_dir/jdk" "$ci_tools/temurin21"
  )
fi
export JAVA_HOME="$ci_tools/temurin21"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
"$JAVA_HOME/bin/java" -version

if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  (
    download_dir=$(mktemp -d "$ci_tools/sdk-download.XXXXXX")
    trap 'rm -rf "$download_dir"' EXIT
    archive="$download_dir/commandline-tools.zip"
    download_ci_tool \
      https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
      "$archive"
    printf '%s  %s\n' \
      4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 \
      "$archive" | sha256sum --check --status
    # The JDK includes a ZIP extractor, avoiding a dependency on system unzip.
    (cd "$download_dir" && "$JAVA_HOME/bin/jar" xf "$archive")
    chmod +x "$download_dir/cmdline-tools/bin/"*
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$download_dir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  )
fi

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# Process substitution preserves sdkmanager's exit status without treating the
# yes process's expected broken pipe as a pipeline failure.
"$sdkmanager" --licenses < <(yes) >/dev/null
"$sdkmanager" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
