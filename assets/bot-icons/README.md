# Bot Icons

claude-automation の GitHub App 用ロゴ(`claude-automation-impl` / `claude-automation-review`)を **バージョン管理** する場所。

GitHub App の Logo は `https://github.com/settings/apps/<app-name>` の **Display Information → Logo** からアップロードする。本ディレクトリの PNG をブラウザでアップロードすれば反映される。

## ディレクトリ構成

```
assets/bot-icons/
├── README.md             … このファイル
├── v1/                   … 現行(2026-05-21〜)
│   ├── claude-automation-impl.svg
│   ├── claude-automation-impl.png   ← App にアップロードするのはこちら(200x200)
│   ├── claude-automation-review.svg
│   └── claude-automation-review.png ← 同上
└── v2/, v3/, ...         … 将来の更新時に追加
```

## バージョン管理ポリシー

- アイコン差し替え時は **既存 vN を残し、新規 vN+1 を追加** する(過去版を上書きしない)
- 新版を採用したら、App Settings で新版 PNG を再アップロード
- README の「現行」表記を新版に更新

## 現在の意匠

| Bot | 背景色 | モチーフ |
|---|---|---|
| impl | 青 `#0969da`(GitHub blue 系) | ハンマー(実装担当) |
| review | 紫 `#8250df`(GitHub purple 系) | 虫眼鏡 + 緑チェックマーク(レビュー担当) |

## SVG / PNG の使い分け

- **PNG (200x200)**: App Logo アップロード用。GitHub の推奨サイズ
- **SVG**: ソースとして保管(編集・別サイズ書き出し用)

PNG は SVG から生成。再生成手順:

```bash
# CairoSVG を使う例
pip install --break-system-packages cairosvg
python3 -c "
import cairosvg
for name in ['claude-automation-impl', 'claude-automation-review']:
    cairosvg.svg2png(
        url=f'{name}.svg',
        write_to=f'{name}.png',
        output_width=200,
        output_height=200,
    )
"
```

## 関連

- `docs/adr/0001-claude-automation-design.md` §2.4 — Bot actor 設計
- `docs/ops/setup-github-app.md` — App セットアップ手順
