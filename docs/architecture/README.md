# Architecture decisions

XaeroNav の実装で、局所的なコードコメントだけでは見失いやすい設計契約をまとめます。
ここに置く文書は作業履歴ではありません。現在の実装を変更するときに守るべき境界と、その境界を
検証するテストを記録します。細かな数値の根拠は、定数の近くにあるコメントを正典とします。

## Decisions

- [ADR-001: Three-stage route pipeline](001-route-pipeline.md)
- [ADR-002: Asynchronous state ownership](002-async-state.md)
- [ADR-003: Loader, Xaero hook, and distribution contracts](003-platform-integration.md)

## Updating these records

設計を変えるコミットでは、コード、対応するテスト、この文書を同じ変更で更新します。過去の調査ログや
採用しなかった案を丸ごと追記せず、現在も有効な判断と再検討条件だけを残します。
