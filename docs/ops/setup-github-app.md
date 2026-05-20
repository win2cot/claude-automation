# GitHub App セットアップ手順

実装 Claude とレビュワ Claude を別 actor で動かすため、win2cot アカウントから GitHub App を 2 つ作成する。

## 1. なぜ App 2 つか

1. **Approval を制度的にカウントするため**: 同一 actor だと「自分の PR を自分で approve できない」制約に抵触
2. **GHA AI ループガード回避**: `GITHUB_TOKEN` で起こしたイベントは他 workflow を起動しない仕様への対策

詳細は ADR-0001 セクション 2.4 参照。

## 2. 作成する App

| App 名(案) | 用途 | install 先 |
|---|---|---|
| `tasks-webapi-claude-impl` | 実装 Claude | tasks-webapi, claude-automation |
| `tasks-webapi-claude-review` | レビュワ Claude | tasks-webapi, claude-automation |

PR/Issue では `tasks-webapi-claude-impl[bot]` / `tasks-webapi-claude-review[bot]` という別 actor として登場する。

## 3. App 作成手順

GitHub Settings → Developer settings → GitHub Apps → New GitHub App

### 共通設定

- **Homepage URL**: `https://github.com/win2cot/claude-automation`
- **Webhook**: Active = OFF(claude-code-action は webhook 不要)
- **Where can this GitHub App be installed?**: Only on this account

### Permissions(両 App 共通・必須)

#### Repository permissions

| 権限 | 設定 | 用途 |
|---|---|---|
| Actions | **Read and write** | workflow 変更を含む PR を扱うため必須 |
| Contents | **Read and write** | コミット・PR 作成 |
| Issues | **Read and write** | Issue コメント・ラベル付与 |
| Metadata | **Read-only** | 必須デフォルト |
| Pull requests | **Read and write** | PR レビュー・コメント |

#### Subscribe to events

- 不要(`claude-code-action` は GHA から token として使うのみ)

### 作成後の作業

1. **Private key を生成・ダウンロード**: App 設定画面下部の「Generate a private key」をクリック、`.pem` ファイルをダウンロード
2. **App ID をメモ**: 後で secrets に登録
3. **App を install**: 「Install App」タブから tasks-webapi と claude-automation の 2 リポジトリに install

## 4. Secrets 設定(リポジトリ側)

両リポジトリ(tasks-webapi, claude-automation)に以下の Secrets を登録する。

| Secret 名 | 値 |
|---|---|
| `CLAUDE_IMPL_APP_ID` | impl 用 App の App ID |
| `CLAUDE_IMPL_PRIVATE_KEY` | impl 用 App の private key(.pem ファイルの中身まるごと) |
| `CLAUDE_REVIEW_APP_ID` | review 用 App の App ID |
| `CLAUDE_REVIEW_PRIVATE_KEY` | review 用 App の private key |
| `CLAUDE_CODE_OAUTH_TOKEN` | Claude Code OAuth トークン(`sk-ant-oat01-...`)。後述の手順で取得 |

### CLAUDE_CODE_OAUTH_TOKEN の取得手順

本プロジェクトでは Anthropic API Key ではなく、Claude Max プランの OAuth トークンを使う(追加課金なし)。`anthropics/claude-code-action` は `claude_code_oauth_token` 入力で公式サポートされている。

1. ローカル(Claude Code が動く環境)で以下を実行:

   ```bash
   claude setup-token
   ```

2. ブラウザが開いて Anthropic にログイン(Max プランのアカウント)→ 認可
3. ターミナルに `sk-ant-oat01-...` 形式のトークンが表示される
4. このトークンを GitHub Secret `CLAUDE_CODE_OAUTH_TOKEN` として両リポジトリに登録

注意事項:

- このトークンは長期有効。漏洩した場合は Claude の Settings から revoke
- Max プランの利用枠を消費する(API Key とは別経路)
- OAuth トークンの利用は **Anthropic 公式 Action 内** に限定し、サードパーティツールでは使わない(Anthropic ToS の制約)

## 5. Workflow での使い方

```yaml
jobs:
  impl:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/create-github-app-token@v1
        id: app-token
        with:
          app-id: ${{ secrets.CLAUDE_IMPL_APP_ID }}
          private-key: ${{ secrets.CLAUDE_IMPL_PRIVATE_KEY }}

      - uses: actions/checkout@v4
        with:
          token: ${{ steps.app-token.outputs.token }}

      - uses: anthropics/claude-code-action@v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          github_token: ${{ steps.app-token.outputs.token }}
```

## 6. 動作確認

App install 後、claude-automation の `tests/loop-guard/` シナリオでループガード回避が成立しているかを確認する。具体的には:

1. impl App が PR コメントを投稿
2. そのコメントイベントで review workflow がトリガーされること
3. review App が approve を投げ、approval としてカウントされること
4. auto-merge が動作すること

これらが動けば本番セットアップ完了。

## 7. トラブルシューティング

### Workflow が起動しない

- App permission に `actions: write` がついているか確認
- App が両リポジトリに install されているか確認
- Secrets 名がワークフロー YAML と一致しているか確認

### Approval がカウントされない

- impl App と review App が **別の App** になっているか確認
- review App から approve しているか(impl App だと同一 actor となりカウントされない)

### Workflow validation エラー

- App permission に `actions: write` があるか
- workflow 変更を含む PR は専用 PR で先に main に取り込む(別 PR と混ぜない)
- `docs/ops/runbook.md` の該当セクション参照
