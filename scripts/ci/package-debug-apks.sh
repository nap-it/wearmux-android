#!/usr/bin/env bash

set -euo pipefail

build_ref=${1:-debug}
build_sha=${2:-local}
output_dir=${3:-apk-artifacts}

# Keep artifact names unique across branches and commits. The SHA is shortened
# here so callers can pass either a full commit ID (GitHub) or a short one
# (GitLab).
build_ref=$(printf '%s' "$build_ref" | tr '/ ' '--' | tr -cd '[:alnum:]._-')
build_sha=$(printf '%s' "$build_sha" | tr -cd '[:alnum:]' | cut -c1-7)
build_id="${build_ref:-debug}-${build_sha:-local}"

mkdir -p "$output_dir/phone" "$output_dir/wear"
phone_apk="$output_dir/phone/wearmux-phone-debug-${build_id}.apk"
wear_apk="$output_dir/wear/wearmux-wear-debug-${build_id}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$phone_apk"
cp wear/build/outputs/apk/debug/wear-debug.apk "$wear_apk"

(cd "$output_dir" && sha256sum phone/*.apk wear/*.apk > SHA256SUMS)
printf 'build_id=%s\n' "$build_id" > "$output_dir/build.env"
printf 'Packaged debug APKs in %s (%s)\n' "$output_dir" "$build_id"
