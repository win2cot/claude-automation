# Renovate PR 機械承認(reusable-renovate-approve.yml)

Renovate が生成した依存更新 PR を、Claude Code Action(LLM 推論)を使わず機械的に判定して `claude-automation-review[bot]` として approve する補助 workflow。承認後の auto-merge 有効化は既存 [`reusable-auto-merge.yml`](../../.github/workflows/reusable-auto-merge.yml) が引き継ぐ。

主経路(impl / review / auto-merge / notify-human)とは orthogonal な独立補助 workflow として配置する(ADR-0002 のステートマシンには組み入れない)。

## 1. 受入条件(本 workflow が approve する条件)

以下を **すべて** 満たすときのみ approve する。1 つでも欠ければ no-op で job 終了し、`needs-human-decision` ラベルは付けない(設計判断 6、Issue #101)。

| # | 条件 | 実装 |
|---|---|---|
| 1 | author が `renovate[bot]`(または gh CLI 表記の `app/renovate`) | `author.login` |
| 2 | label `dependencies` 付き | `labels[].name` |
| 3 | label `needs-human-decision` 未付与 | `labels[].name` |
| 4 | `update_type_label`(default `update:patch`)付き | `labels[].name` |
| 5 | PR body に block list のパッケージ名が含まれない | `body` 部分一致(初期 block: `org.springframework` / `org.projectlombok:lombok` / `com.google.errorprone:error_prone_core` / `com.uber.nullaway:nullaway`) |
| 6 | `mergeable == 'MERGEABLE'`(コンフリクト不在。CLEAN は承認後に成立するため前提にしない) | `mergeable` |
| 7 | required check 全件 `SUCCESS` / `NEUTRAL` / `SKIPPED` | `statusCheckRollup[]` |
| 8 | 変更ファイルが `allowed_file_patterns`(default: `**/build.gradle*` / `gradle/libs.versions.toml` / `gradle/wrapper/**` / `**/Dockerfile` / `.github/workflows/*.yml`)に全てマッチ | `files[].path` を fnmatch |

`update_type_label` チェック(#4)が本 workflow の中核安全装置。Renovate 側 `renovate.json` で patch 更新時に `update:patch` を付与する packageRules が無いと **常に no-op に倒れる**。この設計により、shim だけ配備して renovate.json 改修を忘れたケースでも誤承認は起きない。

## 2. caller(利用者側 shim)の最小構成

利用者側リポジトリ(例: `tasks-webapi`)に以下のような shim を配置する。

```yaml
name: Renovate Auto Approve

on:
  pull_request:
    types: [opened, synchronize, ready_for_review]
  check_suite:
    types: [completed]

# reusable 側の jobs.approve.permissions と subset 整合が必要
permissions:
  contents: read
  pull-requests: write

jobs:
  approve:
    if: >-
      (github.event_name == 'pull_request' &&
       github.event.pull_request.user.login == 'renovate[bot]') ||
      (github.event_name == 'check_suite' &&
       github.event.check_suite.conclusion == 'success' &&
       github.event.check_suite.pull_requests[0] != null)
    uses: win2cot/claude-automation/.github/workflows/reusable-renovate-approve.yml@v1
    with:
      pr_number: >-
        ${{ github.event.pull_request.number ||
            github.event.check_suite.pull_requests[0].number }}
    secrets:
      CLAUDE_REVIEW_APP_ID: ${{ secrets.CLAUDE_REVIEW_APP_ID }}
      CLAUDE_REVIEW_PRIVATE_KEY: ${{ secrets.CLAUDE_REVIEW_PRIVATE_KEY }}
```

加えて利用者側の `renovate.json` に以下の packageRules を追加して `update:patch` を自動付与する(無いと #4 が常に falsy で本 workflow は no-op)。

```json
{
  "packageRules": [
    { "matchUpdateTypes": ["patch"], "addLabels": ["update:patch"] },
    { "matchUpdateTypes": ["minor"], "addLabels": ["update:minor"] },
    { "matchUpdateTypes": ["major"], "addLabels": ["update:major"] }
  ]
}
```

## 3. lab セルフテスト手順(claude-automation 内)

本リポは Renovate を運用していないため、`test-renovate-approve.yml` は `[lab-renovate]` で始まる PR title を Renovate PR と同等に扱う逃げ道を持つ。動作確認の流れ:

1. `feat/lab-renovate-XXX` などのブランチで build.gradle 1 行のような無害な変更を入れる
2. PR title を `[lab-renovate] sample patch update` で作成
3. ラベル `dependencies` + `update:patch` を手動付与
4. `test-renovate-approve.yml` が起動し、reusable が判定
5. CI green になった時点で `claude-automation-review[bot]` の approve が付くことを観測
6. 続けて `test-auto-merge.yml` が approve を受けて auto-merge を有効化

非対象ケース(file 違反 / block list 該当 / label 不足 / CI 未完了)も同様に PR を作って no-op 終了を確認する。

## 4. リリース手順

新規 reusable workflow 追加なので、merge 後に `v1` annotated tag の移動が **必要**(参照規約: `reference-v1-tag-retag-policy`)。`system-impl.md` / `system-review.md` などの curl 配信側ファイルは触らないが、caller 側 shim は `@v1` で参照するため。

```bash
git switch main
git pull --ff-only
git tag -fa v1 -m 'v1: add reusable-renovate-approve.yml'
git push --force origin v1
```

## 5. 非スコープ(別 Issue で扱う)

- `tasks-webapi/.github/workflows/claude-renovate-approve.yml` shim 配備(本 workflow 動作確認後 follow-up Issue)
- 機械承認できなかった Renovate PR の集約 dashboard / weekly 集約
- minor / major 更新の取り扱い(現状は no-op、必要に応じて Claude Review path にフォールバック検討)
- ADR-0002 ステートマシン本文への組み入れ(独立補助 workflow として配置するため対象外)

## 6. 関連

- 起票: [Issue #101](https://github.com/win2cot/claude-automation/issues/101)
- 契機: [tasks-webapi#157](https://github.com/win2cot/tasks-webapi/pull/157)
- 既存規約: [conventions.md](./conventions.md)(`--body-file` 統一 / signal マーカー)
- 既存戦略: [strategy.md](./strategy.md)(主経路の運用フロー)
