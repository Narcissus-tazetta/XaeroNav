#!/usr/bin/env bash
set -euo pipefail

node="${1:-}"
case "$node" in
    1.21.1-fabric|1.21.1-forge|1.21.1-neoforge|1.20.1-fabric|1.20.1-forge) ;;
    *)
        echo "Usage: $0 <1.21.1-fabric|1.21.1-forge|1.21.1-neoforge|1.20.1-fabric|1.20.1-forge>" >&2
        exit 2
        ;;
esac

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

# 同じGradle invocationへ2タスクを渡すと、Gradle 9がstonecutterMergeによる
# src/の更新をcompileJavaの暗黙依存として拒否する。プロセスを分ければ、2回目の
# configurationは切り替え後のソースを通常の入力として扱える。
./gradlew ":stonecutterSwitchTo$node"
exec ./gradlew ":$node:runClient"
