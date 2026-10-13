#!/usr/bin/env bash
# Fetch the reproducible FFmpegKitNext full-GPL Android Maven bundle required
# by the native transcode plugin. Official FFmpegKit artifacts were retired
# from Maven Central; this pin is built from arthenica/ffmpeg-kit-next source.
set -euo pipefail

readonly VERSION="9.0.0"
readonly URL="https://github.com/XiaoHao560/ffmpeg-kit-next/releases/download/v${VERSION}/ffmpeg-kit-next-maven.zip"
readonly SHA256="80a59a3a4072b53cc9f1f010ecdcdbec9270358f86c62185b066786c51740d13"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-$repo_root/android/vendor/ffmpeg-kit-next-maven}"
archive="$(mktemp "${TMPDIR:-/tmp}/ffmpeg-kit-next-${VERSION}.XXXXXX.zip")"
trap 'rm -f "$archive"' EXIT

curl --fail --location --retry 3 --retry-all-errors --output "$archive" "$URL"
echo "$SHA256  $archive" | sha256sum --check --status
rm -rf "$destination"
mkdir -p "$destination"
unzip -q "$archive" -d "$destination"
test -f "$destination/com/arthenica/ffmpeg-kit-next/$VERSION/ffmpeg-kit-next-$VERSION.aar"
printf 'FFmpegKitNext %s Maven bundle is ready at %s\n' "$VERSION" "$destination"
