#!/usr/bin/env bash
set -euo pipefail

node="${1:-}"
# ノードの一覧はsettings.gradle.ktsにしかない。名前の形だけ見て、無いノードはGradleに断らせる
if [[ ! "$node" =~ ^[0-9]+(\.[0-9]+)+-(fabric|forge|neoforge)$ ]]; then
    echo "Usage: $0 <MCバージョン>-<fabric|forge|neoforge> [gradle args...]  (例: 1.21.1-neoforge)" >&2
    exit 2
fi
shift

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

# 同じGradle invocationへ2タスクを渡すと、Gradle 9がstonecutterMergeによる
# src/の更新をcompileJavaの暗黙依存として拒否する。プロセスを分ければ、2回目の
# configurationは切り替え後のソースを通常の入力として扱える。
./gradlew ":stonecutterSwitchTo$node"
# macOS標準のbash 3.2はset -u下で空の"$@"を未定義扱いにするので${@+"$@"}で展開する
exec ./gradlew ":$node:runClient" ${@+"$@"}
