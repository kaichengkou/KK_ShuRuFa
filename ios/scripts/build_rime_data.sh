#!/bin/sh
# Assembles the Rime data the iOS keyboard ships and compiles it ahead of time.
#   ios/scripts/build_rime_data.sh <kk_rime_deploy> <out_dir>
# <out_dir>/RimeShared is Rime's shared data folder, with the compiled dictionaries in build/.
# The same files as the Android app's prepareRimeData task (app/build.gradle.kts), so both
# platforms type alike. The dictionary sources are dropped after compiling: the keyboard never
# compiles (a keyboard extension has a few dozen MB of memory), it only reads build/.
set -eu
DEPLOY=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
mkdir -p "$2"
OUT=$(cd "$2" && pwd)
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ICE="$ROOT/rime-data/rime-ice"
SHARED="$OUT/RimeShared"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

rm -rf "$SHARED"
mkdir -p "$SHARED"
# rime-ice: schemas, dictionaries, OpenCC emoji tables, Lua; not the other frontends' configs
(cd "$ICE" && find . -maxdepth 1 -type f \( -name '*.yaml' -o -name custom_phrase.txt -o -name rime.lua \) \
   ! -name '*.custom.yaml' ! -name squirrel.yaml ! -name weasel.yaml -exec cp {} "$SHARED/" \;)
for d in cn_dicts en_dicts opencc lua; do cp -R "$ICE/$d" "$SHARED/"; done
rm -f "$SHARED/cn_dicts/41448.dict.yaml"
cp -R "$ROOT/rime-data/overlay/." "$SHARED/"
cp -R "$ROOT/rime-data/packs/." "$SHARED/"
for f in STCharacters STPhrases TSCharacters TSPhrases; do
  cp "$ROOT/app/src/main/jni/librime/deps/opencc/data/dictionary/$f.txt" "$SHARED/opencc/"
done

mkdir -p "$WORK/user"
cp "$ROOT/ios/RimeData/rime_ice.custom.yaml" "$WORK/user/"
"$DEPLOY" "$SHARED" "$WORK/user" nihao zhongguo shurufa

mv "$WORK/user/build" "$SHARED/build"
# dictionary sources: compiled into build/ already
rm -rf "$SHARED/cn_dicts" "$SHARED/en_dicts"
rm -f "$SHARED"/*.dict.yaml

# the keyboard starts the way it will on the phone: from build/ alone, deploying nothing
mkdir -p "$WORK/check"
KK_PREBUILT=1 "$DEPLOY" "$SHARED" "$WORK/check" nihao womenshi
du -sh "$SHARED" "$SHARED/build"
