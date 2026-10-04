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
    # Fabricはfabric-apiの版、Forge・NeoForgeはローダー自身の版
    version: str
    # ModrinthのXaeroの版。Noneなら`stageRuntimeTestMods`が集めた現行の版を使う
    worldmap: str | None = None
    minimap: str | None = None
    # 古い系統のXaero（World Map 1.39.x・Minimap 25.2.x）はxaerolibを使わず、ステージングされた
    # 現行のxaerolibはその版のMinecraftを拒むので入れない
    xaerolib: bool = True
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
    Target("1.20.2", "fabric", "1.20.2-fabric", "0.91.6+1.20.2"),
    Target("1.20.2", "forge", "1.20.2-forge", "48.1.0"),
    Target("1.20.5", "fabric", "1.20.6-fabric", "0.97.8+1.20.5", runtime_test_minecraft="1.20.6"),
    Target("1.20.6", "fabric", "1.20.6-fabric", "0.100.8+1.20.6"),
    Target("1.20.6", "forge", "1.20.6-forge", "50.2.10"),
    Target("1.20.6", "neoforge", "1.20.6-neoforge", "20.6.141"),
    # Minimap 25.3.2と一緒に動くWorld Mapは1.41.2まで（1.42.0以降は古いMinimapを拒む）
    Target("1.20.3", "fabric", "1.20.4-fabric", "0.91.1+1.20.3",
           worldmap="fabric-1.20.4-1.41.2", minimap="25.3.2_Fabric_1.20.4"),
    Target("1.20.4", "fabric", "1.20.4-fabric", "0.97.3+1.20.4"),
    Target("1.21", "fabric", "1.21.1-fabric", "0.102.0+1.21",
           worldmap="fabric-1.21.1-1.41.2", minimap="25.3.2_Fabric_1.21"),
    Target("1.21.1", "fabric", "1.21.1-fabric", "0.116.7+1.21.1"),
    Target("1.21.3", "fabric", "1.21.3-fabric", "0.114.1+1.21.3"),
    Target("1.21.3", "forge", "1.21.3-forge", "53.1.12"),
    Target("1.21.3", "neoforge", "1.21.3-neoforge", "21.3.97"),
    Target("1.21.6", "fabric", "1.21.8-fabric", "0.128.2+1.21.6",
           worldmap="1.39.10_Fabric_1.21.6", minimap="25.2.7_Fabric_1.21.6", xaerolib=False),
    Target("1.21.7", "fabric", "1.21.8-fabric", "0.129.0+1.21.7",
           worldmap="1.39.12_Fabric_1.21.7", minimap="25.2.10_Fabric_1.21.7", xaerolib=False),
    Target("1.21.8", "fabric", "1.21.8-fabric", "0.136.1+1.21.8"),
    Target("1.21.9", "fabric", "1.21.10-fabric", "0.134.1+1.21.9",
           worldmap="1.39.17_Fabric_1.21.9", minimap="25.2.15_Fabric_1.21.9", xaerolib=False, runtime_test_minecraft="1.21.10"),
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
    # Stonecutterは有効ノードが構成に含まれていないと設定の段階で止まる
    active = (PROJECT / ".sc_active_version").read_text().strip()
    only = ",".join(sorted(set(nodes) | {active}))
    subprocess.run(["./gradlew", *tasks, f"-Pxaeronav.onlyNodes={only}", "--console=plain", "-q"],
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
    elif target.loader == "forge":
        result.append({"uid": "net.minecraftforge", "version": target.version})
    else:
        result.append({"uid": "net.neoforged", "version": target.version})
    return result


def java_path(minecraft: str) -> Path:
    # Prism全体の既定のJavaは17のことがある。Prismが持つMojangのランタイムから版に合うものを明示する
    # Prismは版のメタデータが許すJavaのメジャー版しか受け付けない（1.20.4以前に21を渡すと起動を断る）
    if minecraft.startswith("26."):
        runtime = "java-runtime-epsilon"
    elif minecraft.startswith("1.21") or minecraft in ("1.20.5", "1.20.6"):
        runtime = "java-runtime-delta"
    else:
        runtime = "java-runtime-gamma"
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
        replaced = (target.worldmap is not None and jar.name.startswith("xaeroworldmap-")) \
            or (target.minimap is not None and jar.name.startswith("xaerominimap-")) \
            or (not target.xaerolib and jar.name.startswith("xaerolib-"))
        if not replaced:
            shutil.copy2(jar, mods)
    if target.worldmap is not None:
        shutil.copy2(modrinth_file("xaeros-world-map", target.worldmap), mods)
    if target.minimap is not None:
        shutil.copy2(modrinth_file("xaeros-minimap", target.minimap), mods)
    if target.loader == "fabric":
        shutil.copy2(modrinth_file("fabric-api", target.version), mods)
    if auto:
        shutil.copy2(runtime_test_jar(target), mods)
    return root


HOOKS = ["WORLD_MAP_RENDER", "MINIMAP_RENDER", "WORLD_MAP_KEY", "WORLD_MAP_MENU", "WAYPOINT_MENU"]


def quit_prism() -> None:
    # Prismは起動中、読み込んだインスタンスの設定をメモリに持ち、保存のたびにinstance.cfgを上書きする。
    # 外からJavaやJVM引数を書き換えるときは、先に閉じておかないと元へ戻される
    if subprocess.run(["pgrep", "-f", str(PRISM_APP)], capture_output=True).returncode != 0:
        return
    print("Prism Launcherを閉じる（インスタンスの設定を書き換えるため）", flush=True)
    subprocess.run(["osascript", "-e", 'tell application "Prism Launcher" to quit'], capture_output=True, check=False)
    for k in range(30):
        # 終了を確認するダイアログなどで断られたら、プロセスへ終了を送る
        if k == 5:
            subprocess.run(["pkill", "-TERM", "-f", str(PRISM_APP)], check=False)
        if subprocess.run(["pgrep", "-f", str(PRISM_APP)], capture_output=True).returncode != 0:
            return
        time.sleep(1)
    raise SystemExit("Prism Launcherが閉じない。手で閉じてからやり直すこと")


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


def game_running(target: Target) -> bool:
    return subprocess.run(["pgrep", "-f", f"instances/{target.instance_id}/"], capture_output=True).returncode == 0


# 結果が決まる行。mc-runtime-testの終わり方は版で違う（1.20.2は"No tests found"を出さずに閉じる）ので頼らない
DECIDED = ("XAERONAV_RUNTIME_HOOK_PROBE_SUCCESS", "XAERONAV_RUNTIME_HOOK_PROBE_FAILED",
           "---- Minecraft Crash Report ----", "Incompatible mods found", "ModLoadingException")


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
    gone = 0
    text = ""
    while time.time() < deadline:
        time.sleep(3)
        if not log.exists():
            # その版を初めて起動したときは、Prismのメタデータの取得が起動に間に合わず本体の無いまま起動することがある。
            # 取得は済んでいるので、起動し直せば通る
            if not relaunched and loader_log.exists() and "couldn't locate the game" in loader_log.read_text(errors="replace"):
                loader_log.unlink()
                relaunched = True
                time.sleep(5)
                launch(target, offline)
            continue
        if not started:
            started = True
            deadline = time.time() + TIMEOUT_SECONDS
        text = log.read_text(errors="replace")
        if any(marker in text for marker in DECIDED):
            break
        # マーカーを出さずに閉じた（固まらずに終わった）ら、そこで判定する
        gone = 0 if game_running(target) else gone + 1
        if gone >= 3:
            break
    else:
        stop_game(target)
        return "時間切れ（ログを見ること: " + str(log) + "）"

    time.sleep(5)
    stop_game(target)
    text = log.read_text(errors="replace")

    if "XAERONAV_RUNTIME_HOOK_PROBE_SUCCESS" in text:
        return "OK（全フック実行）"
    failed = [line for line in text.splitlines() if "XAERONAV_RUNTIME_HOOK_PROBE_FAILED" in line]
    if failed:
        return "NG: " + failed[0].split("XAERONAV_RUNTIME_HOOK_PROBE_FAILED", 1)[1].strip()
    lines = text.splitlines()
    for marker in ("Incompatible mods found", "ModLoadingException"):
        hit = next((k for k, line in enumerate(lines) if marker in line), None)
        if hit is not None:
            detail = next((line.strip() for line in lines[hit:] if line.strip().startswith("- Mod ")), lines[hit].strip())
            return f"NG（起動前に失敗）: {detail}"
    if "---- Minecraft Crash Report ----" in text:
        return f"NG（クラッシュ）: {log}"
    missing = [hook for hook in HOOKS if f"XAERONAV_HOOK_EXECUTED {hook}" not in text]
    return f"NG: 未実行 {missing}（{log}）"


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

    quit_prism()
    roots = {target.name: write_instance(target, args.auto) for target in targets}
    results = {}
    if args.auto:
        for target in targets:
            print(f"{target.name}: 起動中…", flush=True)
            results[target.name] = run_instance(target, roots[target.name], args.offline)
            print(f"{target.name}: {results[target.name]}", flush=True)
        quit_prism()
        for target in targets:
            write_instance(target, auto=False)
    else:
        for target in targets:
            print(f"{target.name}: {roots[target.name]}")

    if args.auto:
        print("\n| 版 | 結果 |\n|---|---|")
        for name, result in results.items():
            print(f"| {name} | {result} |")
    print("\nPrism Launcherに「XaeroNav check …」のインスタンスができている。手で起動すれば遊んで確かめられる。")
    if any("NG" in r or "時間切れ" in r for r in results.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
