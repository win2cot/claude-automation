# ADR-0001: Claude 自動化基盤の設計と claude-automation リポジトリの新設

- ステータス: Accepted
- 起票日: 2026-05-19
- 起票者: win2cot
- 関連リポジトリ: claude-automation(本リポジトリ), tasks-webapi(利用者)

## 1. 背景

tasks-webapi 開発において、Claude を活用した自動化(Issue → 実装 → レビュー → マージ)を本格導入する。現状の課題は以下:

- @claude メンションでの逐次起動が散発的で、漏れや起動順の混乱が起きる
- 実装 Claude とレビュワ Claude のインタラクションが収束せず、同じ指摘がループする / レビュワが指摘を見落とす
- 規約・ADR 準拠のチェックが Claude のプロンプト次第で安定しない
- 設計分岐に直面したとき、Claude が独断で進めるリスクがある
- GitHub Actions の AI ループ防止機構(`GITHUB_TOKEN` で起こしたイベントは他 workflow を起動しない)に抵触する場面があった
- Workflow 編集を含む PR で `Workflow validation failed` エラーが発生し、Required check が green にならず merge がブロックされる(PR #27 の実例)

人(win2cot)の負担を最小化し、設計分岐の判断と Issue ステアリングだけに集中できる体制を作る必要がある。

## 2. 決定

以下を採用する。

### 2.1 アクター構成

| アクター | 種別 | 主たる役割 |
|---|---|---|
| 人(win2cot) | Human | Issue 起票・優先順位付け、設計分岐(`needs-human-decision`)のジャッジ、最終承認 |
| Cowork | 対話的 Claude | 議論、運用設計、規約/ADR/設計書ドラフト、Issue 進行・親子関係の把握、ロードマップ可視化 |
| Claude Code | Local CLI / 補助 | 実コードベース直編集、リポジトリ初期化、ローカル検証、複数 Issue 跨ぐ横断作業、`needs-human-decision` 後の複雑な修正試行 |
| 実装 Claude | GitHub Actions(`claude-code-action`) | Issue 解析 → 実装 → PR open → レビュー応答 → 修正コミット → 対応完了レポート投稿 |
| レビュワ Claude | GitHub Actions(`claude-code-action`) | 初回レビュー、再レビュー、approve、auto-merge トリガ |

人が押すボタンは原則無し(auto-merge)。設計分岐のみ人を呼ぶ。

### 2.2 リポジトリ構成

- **claude-automation(本リポジトリ・新設・public)**: 自動化資産の正本。reusable workflow、プロンプトテンプレ、運用ドキュメント、ADR、ループ検証シナリオ、ダミーサンプルを集約。
- **tasks-webapi(利用者)**: claude-automation の reusable workflow を `uses:` で呼ぶ薄い shim を持つ。
- 将来他プロジェクトでも claude-automation を再利用可能な汎用基盤として設計する。

### 2.3 起動トリガー

@claude メンション運用は廃止し、**ラベル + GitHub イベント主導** に統一する。

| 状況 | トリガー | 起動先 |
|---|---|---|
| Issue が実装可状態になった | `issues.labeled` で `claude:ready` 付与 | 実装 Claude → 実装 → PR open(draft → ready) |
| PR が ready_for_review | `pull_request.ready_for_review` | レビュワ Claude → 初回レビュー |
| レビュワが指摘 | `pull_request_review.submitted(changes_requested)` または `pull_request_review_comment.created` | 実装 Claude → 修正 push + 対応完了レポート(1 ジョブ完結) |
| 実装が対応完了レポートを投稿 | `issue_comment.created` で body に `<!-- claude:impl-done -->` を含む | レビュワ Claude → 再レビュー |
| レビュワが approve | `pull_request_review.submitted(approved)` かつ `needs-human-decision` 無し | auto-merge 有効化(`gh pr merge --auto --squash`) |
| 設計分岐に直面 | `needs-human-decision` ラベル付与 | 人にメンション通知 + auto-merge 保険無効化 |

### 2.4 GitHub App 構成

実装 Claude とレビュワ Claude を **別 actor** にする必要がある。理由は以下 2 点:

1. **Approval を制度的にカウントするため**: 同一 actor だと「自分の PR を自分で approve できない」制約に抵触
2. **GHA AI ループガードを回避するため**: `GITHUB_TOKEN` で起こしたイベントは他 workflow を起動しないため、別 actor の token が必要

実現方式: **win2cot アカウント 1 つから GitHub App を 2 つ作成**。PAT は同一 actor になり approval カウント不可なので採用しない。

| App | install 先 | actor 名(例) |
|---|---|---|
| `tasks-webapi-claude-impl` | tasks-webapi, claude-automation | `tasks-webapi-claude-impl[bot]` |
| `tasks-webapi-claude-review` | tasks-webapi, claude-automation | `tasks-webapi-claude-review[bot]` |

必須 App permission:
- `contents: write`(コミット・PR 作成)
- `pull_requests: write`(レビュー・コメント)
- `issues: write`(コメント・ラベル付与)
- `actions: write`(workflow 変更を含む PR を扱うため必須)
- `metadata: read`

### 2.4.1 Claude API への認証(OAuth トークン方式)

`anthropics/claude-code-action` 内部から Anthropic API を呼ぶ際の認証は、**Anthropic API Key ではなく Claude Max プランの OAuth トークン** を用いる。

理由:

- 開発者(win2cot)は Claude Max プランを契約済み。API Key を別途取得すると追加課金が発生する
- `anthropics/claude-code-action` は `claude_code_oauth_token` 入力を公式サポートしている
- OAuth トークンの利用は Anthropic 公式 Action 内に限定し、サードパーティツールでは使わない(ToS 制約)

取得方法:

```bash
claude setup-token
```

ローカル(Claude Code が動く環境)で実行すると、ブラウザ認可フローを経て `sk-ant-oat01-...` 形式の長期有効トークンが発行される。これを GitHub Secret `CLAUDE_CODE_OAUTH_TOKEN` として両リポジトリに登録する。

### 2.5 Workflow validation エラーと Action prompt 変更ブロックへの対策

GitHub Actions と Anthropic 公式 Action の両方に、workflow file の変更を伴う PR で実行がブロックされる仕様がある。両者は独立した制約だが対策は重なる。

#### (A) GitHub Workflow validation エラー(R19)

PR で workflow を変更すると `Workflow validation failed` で実行されない。

#### (B) Anthropic 公式 Action の prompt checksum 検証(R20)

`anthropics/claude-code-action` は workflow file 内の `prompt` 文字列の checksum を取って検証している。PR diff に該当 workflow file が含まれていると、AI 実行が安全のためブロックされる。

#### 共通対策

1. **reusable workflow パターン**: tasks-webapi 側の `.github/workflows/*.yml` は claude-automation を `uses:` で呼ぶだけの薄い shim にし、本体ロジック変更は claude-automation 側で tag 運用(`@v1`, `@v2`)
2. **Required check の選定**: ブランチ保護で Required にするのは既存 CI(ビルド・テスト・Lint)のみ。Claude 関連 workflow(claude-review, claude-impl, auto-merge)は Required にしない
3. **workflow 変更を含む PR は専用 PR で先に main 取り込み**(他作業と混ぜない)
4. **App permission に `actions: write` を付与**

#### (B) 固有の追加対策

5. **prompt は workflow file に直書きしない**: 主体は外部ファイル(`.github/claude/system-*.md`)に置き、workflow 内では `cat` で読み込んで `--append-system-prompt` 経由で渡す。これにより system prompt 更新時に workflow file 自体を変えずに済み、checksum 検証ブロックを回避できる
6. **workflow file 自体の prompt は最小限**: PR/Issue 番号埋め込み・呼び出し手順サマリのみ。実体は system prompt 側に寄せる

### 2.6 task-type による派生

ラベル `task-type:impl`(コード)と `task-type:design`(規約/ADR/設計書)で、プロンプト・観点チェックリスト・approve 条件を切替える。フロー骨格は共通とし、完全分離は保守二重化のため避ける。

### 2.7 インタラクション収束策

- **未対応テーブル**: PR description 内に Markdown 表で「指摘ID / 内容 / 状態(open/closed/declined) / 対応コミット / 備考」を維持。実装 Claude が更新、レビュワ Claude が読む共有状態
- **対応完了レポートマーカー**: 実装 Claude は修正後 push と同一ジョブで PR コメントに `<!-- claude:impl-done -->` マーカー入りの対応完了レポートを投稿。レビュワはこのマーカーで起動
- **ラリー上限**: 実装 ↔ レビュワの往復上限を設け、上限到達で `needs-human-decision` 自動付与
- **同一指摘検知**: 同一ファイル × 同一観点の指摘が 2 回連続で検出されたら `needs-human-decision` 自動付与

(具体的な上限値・検知ロジックは別 ADR / `docs/automation/conventions.md` で定める)

### 2.8 自動マージ

- リポジトリ設定で Allow auto-merge を ON、squash merge を既定とする
- ブランチ保護で「Require status checks(既存 CI のみ)」「Require approvals: 1」「Dismiss stale approvals on push」
- レビュワ Claude が approve したら `gh pr merge --auto --squash` で有効化
- `needs-human-decision` ラベル付与時は `gh pr merge --disable-auto` で保険無効化

## 3. 採用しなかった選択肢と理由

- **PAT 運用**: 同一 actor になり approval カウント不可。GitHub App 一択
- **`@claude` メンション運用継続**: 起動の予測可能性が低く、漏れの温床
- **task-type 完全分離(impl 用と design 用で workflow を別々に持つ)**: 保守二重化、フロー骨格は同じため不要
- **人による merge ボタン押下を維持**: auto-merge を活用し人の手作業を最小化する方針と合わない
- **Cowork 単独で tasks-webapi 実コードを操作**: Cowork のサンドボックスは tasks-webapi 実 git ディレクトリに直接書けない。Claude Code(ローカル CLI)を補助アクターとして導入
- **Anthropic API Key 認証**: Max プラン契約済みのため、API 側を別途開設・課金する必要は無い。OAuth トークン方式を採用

## 4. 影響範囲

- tasks-webapi: `.github/workflows/` に reusable 呼び出しの薄い workflow ファイルを追加、ブランチ保護を再設定、ラベル 4 種追加(`claude:ready`, `needs-human-decision`, `task-type:impl`, `task-type:design`)
- win2cot アカウント: GitHub App 2 つを作成、両リポジトリへ install
- 開発フロー: 人の手作業範囲が「Issue 起票・優先順位・設計分岐ジャッジ」に絞られる

## 5. 検証計画

- claude-automation 内の `tests/loop-guard/` で AI ループガード対策の動作確認
- `samples/dummy-app/` を対象とした end-to-end スモーク(Issue → PR → レビュー → approve → auto-merge)
- 安定後、tasks-webapi に reusable workflow として導入

## 6. 関連ドキュメント

- `docs/automation/strategy.md` — 運用手順(フロー詳細、Mermaid 図)
- `docs/automation/conventions.md` — マーカー・未対応テーブル書式・ラベル規約
- `docs/automation/prompts/system-impl.md` — 実装 Claude プロンプト本体
- `docs/automation/prompts/system-review.md` — レビュワ Claude プロンプト本体
- `docs/ops/setup-github-app.md` — GitHub App 作成・install 手順
- `docs/ops/runbook.md` — 障害時手順
- `docs/cost/budget.md` — API コスト管理
- `docs/automation/discussions/2026-05-19-design-v3_2.xlsx` — 議論履歴(本 ADR の元になった検討資料)

## 7. 残課題

- ラリー上限値の決定(R02)
- 設計分岐の判定基準の言語化(R04)
- ラベルの命名・色の最終決定(R03)
- プロンプトテンプレの本実装(R06)
- Claude Code の起動主体(人のみか Cowork からも呼べるか)の決定(R18)
- Cowork に Issue 起票権限を持たせるかの判断(R12)

詳細は `docs/automation/discussions/2026-05-19-design-v3_2.xlsx` の「6_残論点」シート参照。
