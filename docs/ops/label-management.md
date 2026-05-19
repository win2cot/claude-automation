# ラベル運用ガイド

## 1. 概要

tasks-webapi の既存 20 ラベル(`area/*` + `priority/*` + 構造 + GitHub デフォルト)に加えて、claude-automation で 4 種を追加運用する。

## 2. 新規ラベル(4 種)

| ラベル | 用途 | 付与者 | 色 |
|---|---|---|---|
| `claude:ready` | 実装 GO サイン | 人 | `#1f883d`(緑系) |
| `needs-human-decision` | 設計分岐・人を呼ぶ | Claude / 人 | `#d1242f`(赤系) |
| `task-type:impl` | コード実装タスク | 人(Issue 起票時) | `#0969da`(青系) |
| `task-type:design` | 規約/ADR/設計書タスク | 人(Issue 起票時) | `#8250df`(紫系) |

## 3. 命名規則

- `claude:*` 接頭辞 = Claude 自動化フローのトリガー / 状態
- `task-type:*` 接頭辞 = タスクの性質分類
- 既存の `area/*`, `priority/*` と接頭辞ぶつかりなし

## 4. ライフサイクル

### claude:ready

- 付与: 人が Issue 起票後、実装着手 OK と判断したとき
- 除去: 実装 Claude が PR を open した時点で workflow が自動除去(任意)
- 再付与: `needs-human-decision` 解除後、再開時

### needs-human-decision

- 付与: Claude が設計分岐検知時、人が必要と判断時
- 除去: 人が方針コメント投稿後

### task-type:impl / task-type:design

- 付与: Issue 起票時(人)
- 除去: 原則しない(Issue の性質を表す不変属性)
- 切替: 例外的に Issue の性質が変わった場合のみ手動切替

## 5. labels.yml による同期

`.github/labels.yml` を SoT(Source of Truth)とし、`label-sync` Action 等で実リポジトリのラベル定義を同期する(導入は次フェーズ)。

## 6. tasks-webapi 側への展開

claude-automation の labels.yml を tasks-webapi にもコピー、または同期する。同期方式は次フェーズで決定(R03 残課題)。
