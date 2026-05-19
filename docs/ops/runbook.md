# 障害時手順(Runbook)

claude-automation 運用での想定障害と対応手順。

## 1. Claude が暴走している(ループ・誤実装)

### 症状

- 同じ PR で実装 ↔ レビュー が無限ループしている
- 想定外のファイルを大量変更している
- API コスト消費が急増している

### 即時対応

1. **対象 PR を draft に戻す**
   ```bash
   gh pr ready --undo <PR番号>
   ```
2. **`needs-human-decision` ラベルを付与**(これで auto-merge も無効化される)
   ```bash
   gh pr edit <PR番号> --add-label "needs-human-decision"
   ```
3. **必要なら workflow を無効化**(Settings → Actions → 対象 workflow → Disable workflow)

### 根本対応

- ラリー回数を確認、`docs/automation/conventions.md` のラリー上限を見直す
- 同一指摘検知ロジックを見直す
- 該当 PR をクローズし、Issue から仕切り直し

## 2. Workflow validation エラーで PR がマージできない

### 症状

```
Workflow validation failed. The workflow file must exist and have
identical content to the version on the repository's default branch.
```

### 原因

`.github/workflows/*.yml` を変更する PR で、Required status check が green にならず、ブランチ保護が merge をブロックしている。

### 即時対応

1. **当該 workflow が Required check に登録されていれば、一時的に外す**(Settings → Branches → Branch protection rules)
2. 既存 CI(ビルド・テスト)が green であることを確認して merge
3. merge 後、Required check に再登録(必要なら)

### 根本対応

- claude-automation の reusable workflow パターンに移行(tasks-webapi 側 workflow を `uses:` だけの薄い shim に)
- Claude 関連 workflow は Required check に登録しない方針を徹底
- workflow 変更を含む PR は **専用 PR** にして他の変更と混ぜない

## 3. Approval がカウントされず auto-merge が動かない

### 症状

レビュワ Claude が approve しているのに、ブランチ保護の「Require approvals: 1」を満たしていない扱いになる。

### 原因(候補)

- impl App と review App が同じ App になっている → 同一 actor として扱われ approve 不可
- review App が tasks-webapi に install されていない
- Secrets に登録した App ID / private key が間違っている

### 対応

1. App が 2 つあり、別 ID であることを Settings → Developer settings → GitHub Apps で確認
2. 両 App が対象リポジトリに install されていることを確認
3. workflow YAML で参照している Secret 名と、リポジトリ Secrets の名前が一致するか確認

## 4. API コスト消費が予算超過しそう

### 症状

`docs/cost/budget.md` の月間上限に近づいている。

### 即時対応

1. **claude-impl / claude-review workflow を一時無効化**(Settings → Actions → Disable workflow)
2. 進行中の PR は `needs-human-decision` 付与で停止
3. 残課題は Claude Code または手動で進める

### 根本対応

- ラリー上限を厳しくする
- 同一指摘検知を強化(2 回 → 1 回)
- Issue の粒度を細かくして 1 PR あたりのトークン消費を抑える

## 5. Claude Code がローカルで暴走した

### 症状

Claude Code が想定外のファイルを大量変更、または無限ループ。

### 即時対応

1. **Claude Code セッションを中断**(Ctrl+C、または Claude Code の停止操作)
2. ローカルの作業ブランチで `git status` で変更確認
3. 必要なら `git reset --hard` または `git stash` で巻き戻し
4. push 前に必ず差分確認

### 予防

- Claude Code セッションを長時間放置しない
- 重要な作業前に commit を細かく刻む
- `--dangerous` 系オプションは使わない

## 6. auto-merge 後に不具合が見つかった

### 症状

main にマージされた変更で不具合発見。

### 対応

1. **Revert PR を作成**
   ```bash
   gh pr create --title "Revert: <元 PR タイトル>" --body "Closes #..."
   ```
   または手動で `git revert <commit>`
2. **`hotfix` ラベルを付与**(優先処理)
3. revert PR の自動化フローは Claude に任せず、人 + Claude Code で迅速対応

## 7. ラベルや App 設定が壊れた

### 症状

`claude:ready` を付けても実装 Claude が動かない、`needs-human-decision` で通知が来ない。

### 対応

1. workflow の最新実行ログを確認(Actions タブ)
2. ラベル名と workflow の `if` 条件文字列の一致を確認
3. App permission が変わっていないか確認
4. `.github/labels.yml` と実リポジトリのラベル一覧が同期しているか確認

## 緊急時の連絡先

- 本人(win2cot)で対応
- 不明点は Cowork(対話的 Claude)に相談
