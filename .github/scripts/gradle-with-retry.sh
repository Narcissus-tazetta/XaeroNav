#!/usr/bin/env bash
# ./gradlew を実行し、依存の取得で落ちたときだけ1回やり直す。
#
# CIは数十ジョブが同時に依存を取りに行くので、Maven Central・Plugin Portal・各ローダーのmavenが
# 実在するjar/pomに404や接続リセットを返して落ちることがある（同じキャッシュのまま再実行すると通る）。
# Gradleは「見つからなかった」結果もキャッシュするため、やり直しは --refresh-dependencies を付ける。
# コンパイルエラー・テスト失敗はやり直さない。
set -uo pipefail

log=$(mktemp)
./gradlew "$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -eq 0 ]; then
  exit 0
fi

if ! grep -qE "Could not (resolve|find|GET|HEAD|download)|was not found in any of the following sources|Connection reset|(Read|Connect) timed out|Received status code (429|5[0-9]{2})" "$log"; then
  exit "$status"
fi

echo "::warning::依存の取得で失敗した。30秒後に --refresh-dependencies でやり直す"
sleep 30
./gradlew "$@" --refresh-dependencies
