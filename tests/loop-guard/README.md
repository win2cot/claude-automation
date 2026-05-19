# Loop Guard 検証

GitHub Actions の AI ループ防止機構(`GITHUB_TOKEN` で起こしたイベントは他 workflow を起動しない)への対策が成立していることを確認する。

## 検証シナリオ

### シナリオ 1: コメント連鎖が成立する

**期待動作**: 実装 App が PR にコメントを投稿すると、その `issue_comment.created` イベントで review workflow がトリガーされる。

**手順**:

1. 実装用ダミー Issue を起票し `claude:ready` 付与
2. 実装 App が PR を open、修正コミット、対応完了レポートコメント投稿まで自動進行
3. レビュー App が再レビューを起動することを確認
4. Actions タブで両 App の workflow 起動履歴を確認

**失敗時**:
- App permission に `actions: write` があるか確認
- workflow YAML で参照している token が App token になっているか確認(`GITHUB_TOKEN` ではない)
- `docs/ops/setup-github-app.md` を再確認

### シナリオ 2: Approval が制度的にカウントされる

**期待動作**: レビュワ App が approve した際、ブランチ保護の「Require approvals: 1」が満たされて auto-merge が動く。

**手順**:

1. シナリオ 1 の続きで、レビュワ App が approve まで進む
2. PR のレビュー一覧で `tasks-webapi-claude-review[bot]` の approve が表示されること
3. auto-merge が有効化されること
4. CI green と共に自動マージされること

**失敗時**:
- impl App と review App が **別 App** であることを確認(同じだと自己 approve 不可)
- `docs/ops/setup-github-app.md` を再確認

### シナリオ 3: Workflow validation エラーへの対策

**期待動作**: workflow ファイルを変更する PR でも、Required check が green になり merge できる。

**手順**:

1. `.github/workflows/test-impl.yml` を編集する PR を作成
2. Required check に Claude workflow が含まれていないことを確認
3. 既存 CI(あれば)が green であれば merge できることを確認

**失敗時**:
- Required check の選定を見直す(`docs/ops/runbook.md` セクション 2)
- App permission に `actions: write` があるか確認

## 結果記録

各シナリオの確認日時と結果を以下に記録(初回セットアップ後):

| シナリオ | 確認日 | 結果 | 備考 |
|---|---|---|---|
| 1. コメント連鎖 | - | - | - |
| 2. Approval カウント | - | - | - |
| 3. Workflow validation | - | - | - |
