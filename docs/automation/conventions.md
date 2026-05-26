# 規約: コメントマーカー・未対応テーブル・ラベル

両 Claude(実装・レビュワ)が共有する書式とマーカーを定義する。

## 1. シグナルマーカー(2 種)

実装 Claude は修正後 push と同一ジョブで PR コメントにシグナルマーカーを含むレポートを投稿する。マーカーは **完遂** と **停止** の 2 種があり、**排他**(同一コメントに両方含めない)。

| マーカー | 意味 | 投稿者 | 受け取り側の動作 |
|---|---|---|---|
| `<sub>signal: claude-impl-done</sub>` | 全指摘対応完遂 | 実装 Claude | `reusable-review.yml` が `contains(comment.body, 'signal: claude-impl-done')` で発火 → 再レビュー |
| `<sub>signal: claude-impl-blocked</sub>` | 障壁検知・停止 | 実装 Claude | review は再発火しない。`needs-human-decision` ラベルにより notify-human が発火 + auto-merge 保険無効 |

### 設計判断: なぜ `<!--` を使わないか

初期設計では HTML コメント `<!-- claude:impl-done -->` を使う想定だったが、C-3 動作確認で **bash の履歴展開エスケープ** により実投稿時に `<\!-- claude:impl-done -->` に変換されてしまい、検知が破綻した(ADR-0001 §2.5 参照)。

`!` を含まない `<sub>signal: ...</sub>` 形式は:
- bash 履歴展開でエスケープされない
- HTML `<sub>` タグとして Markdown 上で小さなフッターとして表示される(ユーザにも控えめに見える)
- contains() で部分一致検索可能

### 投稿時の推奨実装

シェルクォートでの意図しないエスケープを避けるため、`gh pr comment` は **`--body-file` 経由** で渡すことを推奨する:

```bash
printf '%s\n' '<レポート本文>' '<sub>signal: claude-impl-done</sub>' > /tmp/report.md
gh pr comment ${PR_NUMBER} --body-file /tmp/report.md
```

### 対応完了レポートの本文テンプレート

```markdown
## 対応完了レポート

レビュー指摘への対応をまとめました。

### 対応サマリ

| 指摘 | 内容 | 対応 | コミット |
|---|---|---|---|
| [#c1234](url) | TaskService の null チェック不足 | closed: 規約 X 準拠で追加 | `abc1234` |
| [#c1235](url) | バルク更新の N+1 | declined: 別 Issue #207 で対応 | - |

### 未対応テーブルの更新

PR description の未対応テーブルを上記に合わせて更新済みです。

### CI ステータス

[ ] 既存 CI green を確認(自動)

<sub>signal: claude-impl-done</sub>
```

### 対応不能レポートの本文テンプレート

```markdown
## 対応不能レポート

### 停止理由

(カテゴリ A: 設計分岐 / カテゴリ B: 障壁検知 のどちらかを明記し、詳細を記述)

**カテゴリ A 例**: ADR / 規約に書かれていない設計判断が必要なため停止
**カテゴリ B 例**: `CLAUDE.md` を修正したが claude-code-action により origin/main から自動 restore され反映されなかった

### 試みた対応

(実施したこと、何が起きたか)

### 人への依頼事項

(win2cot に何を判断・対応してほしいか)

<sub>signal: claude-impl-blocked</sub>
```

## 2. 未対応テーブル

PR description 内に以下の Markdown 表を維持する。両 Claude の共有状態オブジェクト。

```markdown
## 未対応レビュー

| 指摘ID | 内容 | 状態 | 対応コミット | 備考 |
|---|---|---|---|---|
| #c1234 | TaskService.foo の null チェック不足 | closed | abc1234 | 規約 X 準拠で追加 |
| #c1235 | バルク更新の N+1 | declined | - | 別 Issue #207 で対応予定 |
```

### 状態の定義

- `open`: 未対応。レビュワ Claude はこれが残っている限り approve しない
- `closed`: 対応コミット済み。レビュワが確認して問題なければそのまま
- `declined`: 見送り(理由は備考に明記)。レビュワは備考を読んで妥当性判断
- `deferred`: 別 Issue で対応(備考に Issue 番号必須)

## 3. ラベル規約

### 新規ラベル(4 種)

| ラベル | 用途 | 付与者 | 色(案) |
|---|---|---|---|
| `claude:ready` | 実装 GO サイン | 人 | 緑系 `#1f883d` |
| `needs-human-decision` | 設計分岐・人を呼ぶ | Claude / 人 | 赤系 `#d1242f` |
| `task-type:impl` | コード実装タスク | 人(Issue 起票時) | 青系 `#0969da` |
| `task-type:design` | 規約/ADR/設計書タスク | 人(Issue 起票時) | 紫系 `#8250df` |

詳細な命名・色の最終決定は別 ADR(残課題 R03)。

### 既存ラベルとの整合

tasks-webapi の既存 20 ラベル(`area/*` + `priority/*` + 構造 + GitHub デフォルト)はそのまま使う。新規ラベルは接頭辞(`claude:`, `task-type:`)で区別する。

## 4. PR description テンプレート

```markdown
## 目的

(Issue 内容の要約)

Closes #NNN

## 変更内容

- 変更点 1
- 変更点 2

## 未対応レビュー

| 指摘ID | 内容 | 状態 | 対応コミット | 備考 |
|---|---|---|---|---|

(初期は空。レビューが入り次第更新)

## チェックリスト(セルフ確認)

- [ ] 関連規約(`tasks-webapi/docs/specs/`)を確認した
- [ ] 関連 ADR(`tasks-webapi/docs/adr/`)を確認した
- [ ] テストを追加・更新した
- [ ] 日本語ドキュメントを更新した(必要なら)
```

## 5. Issue 本文テンプレート(task-type 別)

`.github/ISSUE_TEMPLATE/impl-task.md`, `design-task.md` 参照。

## 6. レビュー観点チェックリスト

レビュワ Claude が出力する形式。task-type 別。

### task-type:impl

```markdown
## レビュー観点チェックリスト

- [ ] 規約準拠(`tasks-webapi/docs/specs/`)
- [ ] 関連 ADR 準拠(`tasks-webapi/docs/adr/`)
- [ ] テスト追加・既存テスト維持
- [ ] エラーハンドリングと境界値
- [ ] ログ・観測性の妥当性
- [ ] スコープ逸脱なし
- [ ] 日本語ドキュメント更新(必要時)
- [ ] CI green
- [ ] 未対応テーブル空
```

### task-type:design

```markdown
## レビュー観点チェックリスト

- [ ] 用語の一貫性(規約・既存 ADR との整合)
- [ ] 図表の整合性
- [ ] 関連 Issue / ADR の相互参照
- [ ] Markdown lint(リンク切れ・記法エラー)
- [ ] 影響範囲の言及(規約変更ならカスケードする実装 Issue を起票)
- [ ] スコープ逸脱なし
- [ ] 未対応テーブル空
```

## 7. ラリー上限と同一指摘検知(暫定)

- 実装 ↔ レビュー の往復上限: **4 往復**(暫定値、運用で調整)
- 同一ファイル × 同一観点の指摘が **2 回連続** で検出されたら `needs-human-decision` 自動付与
- 上限到達時の動作: ラベル付与 + 引き戻し理由コメント(これまでの往復履歴サマリ付き)

具体的な閾値・検知ロジックは別 ADR で最終決定(残課題 R02)。

## 8. 実行ログ収集フォーマット(SM-15)

ADR-0002 §2.7「実行ログ収集 / audit-log の record スキーマ」の実装書き下し。各 reusable workflow の最終 step (`if: always()`) で composite action `.github/actions/runs-log-append` を呼ぶ。

### 8.1 Outcome state enum(impl / impl-fix / review / auto-merge 用)

ADR-0002 §2.3 の値:

- **impl**: `Impl-Done` / `Impl-Blocked` / `Impl-Aborted`
- **impl-fix**: `Impl-Fix-Done` / `Impl-Fix-Blocked` / `Impl-Fix-Aborted`
- **review**: `Review-Approved` / `Review-RequestedChanges` / `Review-Aborted`
- **auto-merge**: `Auto-Merge-Enabled` / `Auto-Merge-Failed`

### 8.2 Workflow run outcome enum(notify-human / audit GHA 用)

ADR-0002 §2.7:

- **notify-human**: `Notify-Posted` / `Notify-Failed`
- **audit GHA**: `Watchdog-Clear` / `Watchdog-Triggered`

### 8.3 Aborted reason enum(初期セット)

ADR-0002 §2.3 参照。`unknown` をデフォルトとし、観察パターンを積みながら拡張する。

- `max-turns` / `barrier` / `oauth-revoked` / `rate-limit` / `quota-exhausted`
- `context-overflow` / `output-overflow` / `throttle` / `cancelled` / `unknown`

### 8.4 1 run record の Markdown + JSON ハイブリッド例

````markdown
## run_id: `26410452890` (impl)

| field | value |
|---|---|
| state | `Impl-Aborted` |
| target | issue win2cot/tasks-webapi#142 |
| duration | 431876 ms |
| started_at | 2026-05-25T16:34:00Z |
| ended_at | 2026-05-25T16:41:12Z |
| reason | `unknown` |
| branch_created | false |
| pr_created | false |

```json
{
  "schema_version": 1,
  "run_id": "26410452890",
  "workflow": "impl",
  "target": {"type": "issue", "number": 142, "repo": "win2cot/tasks-webapi"},
  "state": "Impl-Aborted",
  "duration_ms": 431876,
  "started_at": "2026-05-25T16:34:00Z",
  "ended_at": "2026-05-25T16:41:12Z",
  "artifacts": {"branch_created": false, "pr_created": false, "commits_pushed": 0, "comments_posted": 0},
  "reason": "unknown"
}
```
````

### 8.5 集計の使い方

```bash
gh issue view 57 --repo win2cot/claude-automation --comments \
  --json comments -q '.comments[].body' \
  | awk '/```json/{flag=1; next} /```/{flag=0} flag' \
  | jq -s '.'
```

### 8.6 設計原則

- **副作用ゼロ**: composite action 内の `gh issue comment` 失敗時は warning のみ、必ず `exit 0`。本流(impl / review 等)に影響を与えない
- **append-only / never close**: runs-log Issue (#57) / audit-log Issue (#59) は集計のため open 維持
- **schema_version=1**: フィールド追加は version 据え置き、削除・型変更で +1(ADR-0002 §2.7)
- **artifacts の v1 範囲**: `branch_created` / `pr_created` のみ実装、`commits_pushed` / `comments_posted` は 0 デフォルト(後付け改善可)
- **テスト用分離**: runs-log-test Issue (#58) は `test-runs-log-append.yml`(`workflow_dispatch`)経由の composite 単体検証専用
