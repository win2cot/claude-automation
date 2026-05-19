# claude-automation

Claude を活用した GitHub 自動化基盤(汎用)。tasks-webapi が最初の利用者として組み込む。

## 何をする基盤か

- Issue 起票 → 実装 → レビュー → 自動マージ のフローを Claude 同士が GitHub 上で自動往復
- 人は「Issue 起票・優先順位」「設計分岐の判断」に集中
- 規約・ADR 準拠を強制し、設計分岐は `needs-human-decision` で人にエスカレーション

## アクター

| アクター | 役割 |
|---|---|
| 人(win2cot) | Issue 起票、優先順位付け、設計分岐のジャッジ |
| Cowork | 議論、運用設計、規約/ADR/設計書ドラフト、ロードマップ可視化(対話的 Claude) |
| Claude Code | 実コードベース直編集、リポジトリ初期化、ローカル検証(ローカル CLI、補助) |
| 実装 Claude | Issue → PR → 修正コミット(GHA, `claude-code-action`) |
| レビュワ Claude | PR レビュー → approve → auto-merge トリガ(GHA, `claude-code-action`) |

## ディレクトリ構成

```
claude-automation/
├── README.md
├── docs/
│   ├── adr/                    … 意思決定の正本
│   ├── automation/             … 運用手順、規約、プロンプトテンプレ
│   ├── ops/                    … GitHub App セットアップ、障害時手順、ラベル運用
│   └── cost/                   … API コスト管理
├── .github/
│   ├── workflows/
│   │   ├── reusable-*.yml      … 利用者リポジトリから呼ばれる本体
│   │   └── test-*.yml          … claude-automation 内発火用
│   ├── claude/                 … system prompt 実体
│   ├── ISSUE_TEMPLATE/
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── labels.yml
├── samples/dummy-app/          … 検証用ダミーサンプル
└── tests/loop-guard/           … ループガード検証シナリオ
```

## 利用者リポジトリからの参照(tasks-webapi 等)

`.github/workflows/` に薄い shim を置いて、本リポジトリの reusable workflow を `uses:` で呼ぶ。

```yaml
# tasks-webapi/.github/workflows/claude-impl.yml
name: Claude Impl
on:
  issues:
    types: [labeled]
jobs:
  impl:
    if: github.event.label.name == 'claude:ready'
    uses: win2cot/claude-automation/.github/workflows/reusable-impl.yml@v1
    secrets: inherit
```

## セットアップ

1. GitHub App を 2 つ作成(impl 用 / review 用) → `docs/ops/setup-github-app.md`
2. リポジトリに Secrets を登録(`CLAUDE_IMPL_APP_ID`, `CLAUDE_IMPL_PRIVATE_KEY`, `CLAUDE_REVIEW_APP_ID`, `CLAUDE_REVIEW_PRIVATE_KEY`, `ANTHROPIC_API_KEY`)
3. ブランチ保護を設定(Required check は既存 CI のみ。Claude workflow は含めない)
4. `samples/dummy-app/` でループガード検証(`tests/loop-guard/README.md`)
5. tasks-webapi 等の利用者リポジトリから reusable workflow を呼ぶ

## 重要な設計判断

- `@claude` メンション運用は廃止 → **ラベル + GitHub イベント主導**
- 実装とレビュワは **別 GitHub App**(approval カウント + AI ループガード回避)
- 人が押すボタンは無し(auto-merge)、`needs-human-decision` のみ人を呼ぶ
- reusable workflow パターンで利用者側の workflow ファイル変更頻度を最小化(Workflow validation エラー回避)

詳細は `docs/adr/0001-claude-automation-design.md`。

## ステータス

初期化フェーズ(2026-05-19)。skeleton は揃ったが、workflow 内の Claude Code Action 呼び出し、プロンプト実装、ループガード検証はこれから。
