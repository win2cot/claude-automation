# Dummy App

claude-automation の検証用ダミーサンプル。レビュワ Claude の観点チェック発火対象として使う。

- tasks-webapi の規約準拠を完全には模倣しない(あくまで動作確認用の薄いダミー)
- 規約適用度の検証は最終的に tasks-webapi の本物コードベースで行う

## 構成

```
samples/dummy-app/
├── README.md
└── src/main/java/com/example/dummy/
    ├── DummyService.java         … サンプルサービスクラス
    └── DummyController.java      … サンプル REST コントローラ
```

## 使い方

1. Issue を起票して `task-type:impl` + `claude:ready` を付ける
2. 実装 Claude が起動し、`samples/dummy-app/` 配下を変更する PR を作る
3. レビュワ Claude が起動し、観点チェックリストを出力
4. ループガード回避・auto-merge 動作を確認

ビルド・テスト基盤は付属しない(動作確認のみ目的)。

## C-3 Test

動作確認用追記。
