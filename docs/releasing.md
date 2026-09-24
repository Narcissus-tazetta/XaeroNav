# リリース手順

## 初回設定

Modrinth と CurseForge に XaeroNav のプロジェクトを作成する。GitHub リポジトリの
**Settings → Secrets and variables → Actions** に以下を登録する。

| 種別 | 名前 | 値 |
| --- | --- | --- |
| Variable | `MODRINTH_PROJECT_ID` | Modrinth のプロジェクト ID |
| Variable | `CURSEFORGE_PROJECT_ID` | CurseForge の数値プロジェクト ID |
| Secret | `MODRINTH_TOKEN` | Modrinth の Personal Access Token（Create versions、Read versions、Write versions） |
| Secret | `CURSEFORGE_TOKEN` | CurseForge の API トークン |

GitHub Release には Actions の組み込み `GITHUB_TOKEN` を使うため、追加の GitHub トークンは不要。
トークンや ID をリポジトリ内のファイルへ書かないこと。

## 毎回のリリース

1. `changelogs/<X.Y.Z>.md` に公開する変更点を書く。これは Modrinth、CurseForge、GitHub Release の共通本文になる。ファイルが無いか空なら処理は開始しない。リポジトリ直下の `CHANGELOG.md` はこのディレクトリへの案内だけなので、リリースごとの更新は要らない。
2. 変更点を含むコミットを `main` へ反映する。
3. GitHub Actions の **Release → Run workflow** で `X.Y.Z` を入力する。またはそのコミットへ `vX.Y.Z` タグを push する。
4. ビルドと配布 JAR の検査後、各ローダー・Minecraft 版を Modrinth と CurseForge に個別投稿する。全10件が成功すると、GitHub Release を下書きなしで公開する。

公開 JAR 名は `xaeronav-X.Y.Z-<loader>-<minecraft>.jar`。通常の開発ビルドと違い、コミットハッシュは付かない。
Modrinth と CurseForge 上のバージョン番号は `X.Y.Z-<loader>-<minecraft>` とし、5件が同じプロジェクト内で重複しないようにする。

公開中に一部のジョブが失敗したら、Actions の同じ実行から **Re-run failed jobs** を使う。成功した投稿を含めて新しい実行を開始すると、同じバージョンを重複投稿する可能性がある。通信エラーの直後は、対象サイトに投稿が作成されたか確認してから再実行する。

ローカルでの設定確認には、公開済みの対象 JAR を `build/libs` に用意し、仮の ID とトークンを設定して `-Ppublish_dry_run=true` を付ける。dry run は実際のアップロードを行わない。
