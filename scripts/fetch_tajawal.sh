#!/usr/bin/env bash
set -e
DEST="app/src/main/res/font"; mkdir -p "$DEST"
BASE="https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal"
curl -L "$BASE/Tajawal-Regular.ttf" -o "$DEST/tajawal_regular.ttf"
curl -L "$BASE/Tajawal-Medium.ttf" -o "$DEST/tajawal_medium.ttf"
curl -L "$BASE/Tajawal-Bold.ttf"   -o "$DEST/tajawal_bold.ttf"
echo "Tajawal installed."
