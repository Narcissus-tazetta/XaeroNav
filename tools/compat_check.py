#!/usr/bin/env python3
"""ノードのjarを、そのノードより前のMinecraftでも使う版（minecraftCompatFor）を実機で確かめるための
Prism Launcherのインスタンスを作る。

    tools/compat_check.py            # インスタンスを作る（手で起動して確かめる用）
    tools/compat_check.py --auto     # 作ったうえで順に起動し、Xaeroのフックが当たって動いたかを表で出す
    tools/compat_check.py --auto 1.21.9-fabric   # 名前で絞る

配布jarと、そのノードのXaero（xaerolibを含む）はGradleの`stageRuntimeTestMods`から取る。
更新の止まった版のXaeroを使う版だけ、XaeroをModrinthから取る。
--autoはmc-runtime-test（CIの起動テストと同じもの）を入れてワールドへ入り、runtime hook probeの結果を読む。
終わったらmc-runtime-testとprobeを外し、手で遊べる状態へ戻す。
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
PRISM_APP = Path("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher")
PRISM_DATA = Path.home() / "Library/Application Support/PrismLauncher"
CACHE = PROJECT / "build/compat-check"
FABRIC_LOADER = "0.19.5"
RUNTIME_TEST_RELEASE = "4.5.1"
PROBE_ARG = "-Dxaeronav-ci.runtimeHookProbe=true"
OFFLINE_NAME = "XaeroNavCheck"
# 初めて使う版はPrismがアセットを取り終えるまでゲームが始まらない（10分ほどかかることがある）
STARTUP_TIMEOUT_SECONDS = 1800
TIMEOUT_SECONDS = 600


@dataclass(frozen=True)
class Target:
    minecraft: str
    loader: str
    node: str
    fabric_api: str
    # Noneなら`stageRuntimeTestMods`が集めた現行のXaeroを使う
    old_xaero: tuple[str, str] | None = None
    # mc-runtime-testの配布物が無い版は、受け付ける範囲の広い隣の版のものを使う
    runtime_test_minecraft: str | None = None

    @property
    def name(self) -> str:
        return f"{self.minecraft}-{self.loader}"

    @property
    def instance_id(self) -> str:
        return f"xaeronav-check-{self.name}"


# 下側の版と、比較用にノード本来の版を並べる（Accessor・描画のmixinはノード本来の版の動きも変えるため）
TARGETS = [
    Target("1.21.6", "fabric", "1.21.8-fabric", "0.128.2+1.21.6",
           old_xaero=("1.39.10_Fabric_1.21.6", "25.2.7_Fabric_1.21.6")),
    Target("1.21.7", "fabric", "1.21.8-fabric", "0.129.0+1.21.7",
           old_xaero=("1.39.12_Fabric_1.21.7", "25.2.10_Fabric_1.21.7")),
    Target("1.21.8", "fabric", "1.21.8-fabric", "0.136.1+1.21.8"),
    Target("1.21.9", "fabric", "1.21.10-fabric", "0.134.1+1.21.9",
           old_xaero=("1.39.17_Fabric_1.21.9", "25.2.15_Fabric_1.21.9"), runtime_test_minecraft="1.21.10"),
    Target("1.21.10", "fabric", "1.21.10-fabric", "0.138.4+1.21.10"),
    Target("26.1", "fabric", "26.1.2-fabric", "0.155.3+26.1.2"),
    Target("26.1.1", "fabric", "26.1.2-fabric", "0.155.3+26.1.2"),
    Target("26.1.2", "fabric", "26.1.2-fabric", "0.155.3+26.1.2"),
]


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(".part")
        with urllib.request.urlopen(url) as response, open(tmp, "wb") as out:
            shutil.copyfileobj(response, out)
        tmp.rename(dest)
    return dest


def modrinth_file(project: str, version_number: str) -> Path:
    dest = CACHE / "modrinth" / f"{project}-{version_number}.jar"
    if dest.exists():
        return dest
    with urllib.request.urlopen(f"https://api.modrinth.com/v2/project/{project}/version") as response:
        versions = json.load(response)
    match = next((v for v in versions if v["version_number"] == version_number), None)
    if match is None:
        raise SystemExit(f"Modrinthの{project}に{version_number}が無い")
    primary = next((f for f in match["files"] if f["primary"]), match["files"][0])
    return download(primary["url"], dest)


def runtime_test_jar(target: Target) -> Path:
    minecraft = target.runtime_test_minecraft or target.minecraft
    loader = "lexforge" if target.loader == "forge" else target.loader
    name = f"mc-runtime-test-{minecraft}-{RUNTIME_TEST_RELEASE}-{loader}-release.jar"
    url = f"https://github.com/headlesshq/mc-runtime-test/releases/download/{RUNTIME_TEST_RELEASE}/{name}"
    return download(url, CACHE / "mc-runtime-test" / name)


def stage_nodes(nodes: list[str]) -> None:
    tasks = [f":{node}:stageRuntimeTestMods" for node in nodes]
    subprocess.run(["./gradlew", *tasks, f"-Pxaeronav.onlyNodes={','.join(nodes)}", "--console=plain", "-q"],
                   cwd=PROJECT, check=True)


def staged_mods(node: str) -> list[Path]:
    return sorted((PROJECT / "build/runtime-test" / node / "mods").glob("*.jar"))


def components(target: Target) -> list[dict]:
    result = [{"uid": "net.minecraft", "version": target.minecraft, "important": True}]
    if target.loader == "fabric":
        result += [
            {"uid": "net.fabricmc.intermediary", "version": target.minecraft, "dependencyOnly": True},
            {"uid": "net.fabricmc.fabric-loader", "version": FABRIC_LOADER},
        ]
    else:
        raise SystemExit(f"{target.loader}のインスタンスはまだ作れない")
    return result


def java_path(minecraft: str) -> Path:
    # Prism全体の既定のJavaは17のことがある。Prismが持つMojangのランタイムから版に合うものを明示する
    runtime = "java-runtime-epsilon" if not minecraft.startswith("1.") else "java-runtime-delta"
    path = PRISM_DATA / "java" / runtime / "bin/java"
    if not path.exists():
        raise SystemExit(f"{runtime}が無い。Prismで一度その版のMinecraftを起動してJavaを入れること: {path}")
    return path


def ensure_options(game_dir: Path) -> None:
    # ウィンドウからフォーカスが外れると一時停止メニューが開き、mc-runtime-testがワールドで待ち続ける。
    # 初回起動の案内画面もクイックプレイを止める
    wanted = {"pauseOnLostFocus": "false", "onboardAccessibility": "false"}
    options = game_dir / "options.txt"
    lines = options.read_text().splitlines() if options.exists() else []
    lines = [line for line in lines if line.split(":", 1)[0] not in wanted]
    lines += [f"{key}:{value}" for key, value in wanted.items()]
    options.write_text("\n".join(lines) + "\n")


def write_instance(target: Target, auto: bool) -> Path:
    root = PRISM_DATA / "instances" / target.instance_id
    mods = root / "minecraft/mods"
    mods.mkdir(parents=True, exist_ok=True)
    ensure_options(root / "minecraft")
    pack = root / "mmc-pack.json"
    # Prismは起動時にこのファイルへ解決済みの依存（LWJGLなど）を書き足す。毎回書き直すと、初回起動と同じく
    # メタデータの取得が起動に間に合わず「ゲームが見つからない」で落ちることがある
    if not pack.exists():
        pack.write_text(json.dumps({"formatVersion": 1, "components": components(target)}, indent=4))
    config = {
        "ConfigVersion": "1.3",
        "InstanceType": "OneSix",
        "name": f"XaeroNav check {target.minecraft} {target.loader}",
        "iconKey": "default",
        "OverrideJavaLocation": "true",
        "JavaPath": str(java_path(target.minecraft)),
        "OverrideJavaArgs": "true" if auto else "false",
        "JvmArgs": PROBE_ARG if auto else "",
    }
    (root / "instance.cfg").write_text("[General]\n" + "".join(f"{k}={v}\n" for k, v in config.items()))

    for old in mods.glob("*.jar"):
        old.unlink()
    for jar in staged_mods(target.node):
        if target.old_xaero is None or jar.name.startswith("xaeronav-"):
            shutil.copy2(jar, mods)
    if target.old_xaero is not None:
        worldmap, minimap = target.old_xaero
        shutil.copy2(modrinth_file("xaeros-world-map", worldmap), mods)
        shutil.copy2(modrinth_file("xaeros-minimap", minimap), mods)
    if target.loader == "fabric":
        shutil.copy2(modrinth_file("fabric-api", target.fabric_api), mods)
    if auto:
        shutil.copy2(runtime_test_jar(target), mods)
    return root


HOOKS = ["WORLD_MAP_RENDER", "MINIMAP_RENDER", "WORLD_MAP_KEY", "WORLD_MAP_MENU", "WAYPOINT_MENU"]


def launch(target: Target, offline: bool) -> None:
    # Prismはオフライン起動ではライブラリを取りに行かない。初めて起動する版はPrismの既定のアカウントで起動する
    extra = ["--offline", OFFLINE_NAME] if offline else []
    subprocess.Popen([str(PRISM_APP), "--launch", target.instance_id, *extra],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stop_game(target: Target) -> None:
    # 確認用のゲームはこのインスタンスのパスを引数に持つ。前回の打ち切りで残ったものがいると、新しい起動が
    # Prismに無視され、ログも取れない
    subprocess.run(["pkill", "-f", f"instances/{target.instance_id}/"], check=False)
    time.sleep(2)


def run_instance(target: Target, root: Path, offline: bool) -> str:
    log = root / "minecraft/logs/latest.log"
    loader_log = root / "minecraft/fabricloader.log"
    stop_game(target)
    log.unlink(missing_ok=True)
    loader_log.unlink(missing_ok=True)
    launch(target, offline)
    relaunched = False
    deadline = time.time() + STARTUP_TIMEOUT_SECONDS
    started = False
    text = ""
    while time.time() < deadline:
        time.sleep(3)
        if log.exists() and not started:
            started = True
            deadline = time.time() + TIMEOUT_SECONDS
        if not log.exists():
            # その版を初めて起動したときは、Prismのメタデータの取得が起動に間に合わず本体の無いまま起動することがある。
            # 取得は済んでいるので、起動し直せば通る
            if not relaunched and loader_log.exists() and "couldn't locate the game" in loader_log.read_text(errors="replace"):
                loader_log.unlink()
                relaunched = True
                time.sleep(5)
                launch(target, offline)
            continue
        text = log.read_text(errors="replace")
        if "No tests found, Successfully finished." in text or "XAERONAV_RUNTIME_HOOK_PROBE_FAILED" in text \
                or "---- Minecraft Crash Report ----" in text:
            break
    else:
        stop_game(target)
        return "時間切れ（ログを見ること）"

    # mc-runtime-testは終わるとゲームを閉じるが、CIと同じく終了処理で固まることがある
    time.sleep(10)
    stop_game(target)

    if "XAERONAV_RUNTIME_HOOK_PROBE_SUCCESS" in text:
        return "OK（全フック実行）"
    failed = [line for line in text.splitlines() if "XAERONAV_RUNTIME_HOOK_PROBE_FAILED" in line]
    if failed:
        return "NG: " + failed[0].split("XAERONAV_RUNTIME_HOOK_PROBE_FAILED", 1)[1].strip()
    missing = [hook for hook in HOOKS if f"XAERONAV_HOOK_EXECUTED {hook}" not in text]
    return f"NG: 未実行 {missing}" if missing else "NG（ログを見ること）"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--auto", action="store_true", help="起動してフックの結果を確かめる")
    parser.add_argument("--offline", action="store_true",
                        help="アカウントを使わずに起動する（その版を一度オンラインで起動してライブラリが揃っている場合だけ）")
    parser.add_argument("names", nargs="*", help="対象の名前（例: 1.21.9-fabric）。省略すると全部")
    args = parser.parse_args()

    targets = [t for t in TARGETS if not args.names or t.name in args.names]
    unknown = set(args.names) - {t.name for t in targets}
    if unknown:
        raise SystemExit(f"知らない対象: {sorted(unknown)}。一覧: {[t.name for t in TARGETS]}")
    if not PRISM_APP.exists():
        raise SystemExit(f"Prism Launcherが無い: {PRISM_APP}")

    stage_nodes(sorted({t.node for t in targets}))

    results = {}
    for target in targets:
        root = write_instance(target, args.auto)
        if args.auto:
            print(f"{target.name}: 起動中…", flush=True)
            results[target.name] = run_instance(target, root, args.offline)
            print(f"{target.name}: {results[target.name]}", flush=True)
            write_instance(target, auto=False)
        else:
            print(f"{target.name}: {root}")

    if args.auto:
        print("\n| 版 | 結果 |\n|---|---|")
        for name, result in results.items():
            print(f"| {name} | {result} |")
    print("\nPrism Launcherに「XaeroNav check …」のインスタンスができている。手で起動すれば遊んで確かめられる。")
    if any("NG" in r or "時間切れ" in r for r in results.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
