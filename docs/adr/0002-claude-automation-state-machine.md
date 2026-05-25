# ADR-0002: Claude 自動化フローのステートマシン

- ステータス: Proposed
- 起票日: 2026-05-25
- 起票者: win2cot
- 関連リポジトリ: claude-automation(本リポジトリ), tasks-webapi(利用者)
- 関連 ADR: ADR-0001(本 ADR が前提とする高レベル設計、アクター構成・トリガー・App 構成・signal 規約)

## 1. 背景

v1.1.0(blocked シグナル + 障壁検知)リリース後、本番初動で完走/障壁/失敗の各パターンを実観測する中で、claude-automation のフローには以下のような「状態遷移として整理されていない」問題が顕在化した。

- max-turns 到達で workflow が異常終了したまま、`claude:ready` ラベル・着手宣言コメントが宙ぶらりんになる(#207 / #86 / #87 で 3 件、いずれも branch 未到達の早期死亡パターンを実観測)
- Project の claude-automation field が「不適用」「議論先行」の Issue に対し、誤って `claude:ready` が付与された際に、現状の workflow は何の抑止もせず impl を起動してしまう余地がある
- レビュワが `CHANGES_REQUESTED` を出した後、人手で fix した PR の再レビューを起動する正規経路が無く、Draft → Ready 切替で迂回している(PR #205)
- impl-fix が修正コミットを push し直しても、PR 上の古い `CHANGES_REQUESTED` review が dismiss されず残置されることがある
- impl-fix の `actions/checkout@v4` が「人手 PR のブランチ」で認証エラーになる(PR #205、claude-automation-impl App 経由の token scope mismatch 疑い)
- approve 済みでも「out-of-date with base branch」で auto-merge が走らず、人手で Update branch ボタン押下が必要になる
- Claude Max の 5 時間ウィンドウ枯渇 / OAuth 失効 / Anthropic rate limit / context window overflow / output token overflow など、未観測だが Sprint 1 以降で十分起こりうる失敗モードに対し、整理された受け止め方が無い

これらは現象としてはバラバラだが、共通点として「現在 PR/Issue がどの状態にあり、次にどの workflow が起動して良いか / 何をして良くないか」が明示モデル化されていないことが原因にある。改善ネタは v1.1.0 リリース後 1 週間弱で SM-01 〜 SM-18 + SM-L1〜SM-L3 の 21 件規模まで膨らんでおり(SM 番号の対応は §2.8 参照)、これを個別パッチの積み重ねで対応すると、各 reusable workflow の `if` 条件が独立進化してスパゲッティ化することが避けられない。

v1.2.0 着手の最初に、フロー全体をステートマシンとして整理し、許可される遷移・許可されない遷移・各状態への到達条件と通知ポリシーを正本として明文化する。以後の改善(SM-13 / SM-14 / SM-08 / SM-01 / SM-10 / SM-L1 など)は本 ADR の派生実装と位置付け、ステートマシン準拠で個別 Issue 化・実装する。

## 2. 決定

以下を採用する。

### 2.1 前提条件(3 層)

本 ADR のステートマシンは、以下 3 層の前提条件が全て満たされていることを前提に成立する。前提が崩れた場合は別途 ADR レベルで対処する。

#### 2.1.1 claude-automation 側の不変条件(本リポジトリが守る)

| 不変条件 | 内容 | 確認方法 |
|---|---|---|
| concurrency 設定 | 全 reusable workflow に `concurrency: group: claude-{type}-{pr_or_issue_number}` / `cancel-in-progress: false` を設定する。pending キューの latest-wins と同一 reviewer の review-per-user 上書きと組み合わせ、race 挙動を自己回復させる | `grep -l 'concurrency:' .github/workflows/reusable-*.yml`(5 ファイル全て該当、2026-05-25 確認) |
| impl-fix の checkout SHA | impl-fix は `pull_request_review.submitted` などの payload にある PR head SHA を `actions/checkout` の `ref` に渡し、stale な main SHA を見ない | `reusable-impl-fix.yml` の checkout step を参照 |
| bot identity の固定 | 同じ役割(impl / review)の bot identity は常に同一 actor 名で投稿する。GitHub の review-per-user セマンティクスにより、最新 review が古い review を上書きする挙動が成立する | `git config user.name "claude-automation-{impl,review}[bot]"` を各 reusable workflow で固定 |
| signal マーカー | impl/impl-fix の完遂・障壁は ADR-0001 §2.7.1 の `signal: claude-impl-done` / `signal: claude-impl-blocked` を投稿する。`!` を含まないため bash 履歴展開で破壊されない | `docs/automation/conventions.md` 参照 |

#### 2.1.2 利用者リポジトリ側の前提条件

利用者リポジトリ(tasks-webapi 等)が満たすべき設定。`docs/ops/setup-github-app.md` および `docs/ops/runbook.md` に手順化する。

- ブランチ保護で `dismiss_stale_reviews: true` を設定(新規 push で古い approve / requested_changes を自動 dismiss)
- Required status checks に「実在する CI ジョブ名」のみを登録(存在しない名前を登録すると auto-merge が stuck する、PR #198 観察)
- Allow auto-merge を ON、Allow squash merging を ON
- `allowed_bots: "claude-automation-impl,claude-automation-review"` および `allowed_non_write_users: "claude-automation-impl,claude-automation-review"` を両 reusable workflow の入力で明示(ADR-0001 §2.4.2 (d) 準拠)
- ブランチ保護の Bypass list に両 App を追加(Required check 待ちなしで bot が必要な merge を実行可能にするためではなく、後述 SM-10 out-of-date 自動 update 等で意図的にこれを使う場面はない。基本は人と同じ条件で merge する設計)

#### 2.1.3 GitHub / Anthropic プラットフォーム依存

本 ADR のステートマシンが想定する外部仕様。これらが変わった場合は ADR レベルで再評価する。

- GitHub Actions の concurrency: pending キューは latest-wins(新しい pending が古い pending を自動 cancel)
- GitHub Reviews API: 同一 reviewer の最新 review が古い review を上書きする(review-per-user セマンティクス)
- GitHub Auto-merge: CI green + branch up-to-date + 必要数の有効 approve が揃った時点で merge する。enable 時点の条件で待機し続けるため、設定変更後は disable → re-enable で再評価が必要(PR #198 観察)
- GitHub Actions limit: 月間実行時間 / 同時実行枠 / API rate limit
- Claude Max OAuth: 5 時間ウィンドウ、月間トークン上限、context window、output token、OAuth token 失効

特に (3) は「GitHub / Anthropic の仕様変更で死んだ場合の被疑領域」を明示するために独立して列挙する。新しい失敗モードを観測した際の切り分け起点として用いる。

### 2.2 状態モデル

ステートマシンの状態は **フェーズ × {成功, 失敗(理由)}** という概念モデルで設計する。具体的な enum 表現では、後続処理が違う失敗は別状態に分割する(B 原則)。

例:
- `Impl-Done` / `Impl-Blocked` / `Impl-Aborted` は別状態。後続が異なる(Done → レビュー起動、Blocked → 人手介入、Aborted → cleanup + 人手介入)
- `Impl-Aborted` 配下の reason(`max-turns` / `oauth-revoked` / `context-overflow` 等)は **同じ後続 cleanup を経るため同一状態**、reason は metadata で識別する

`Human-Required` は **フラグ**(GitHub label `needs-human-decision` と 1:1)として扱い、状態軸には乗せない。将来「フラグの立ち方で次の遷移が変わる」ケースが出てきたら状態に昇格させるが、現状はラベル付与の有無で十分に表現できる。

### 2.3 Outcome state enum

workflow が runs-log / audit-log に書き込む `state` フィールドの値を以下で定義する。`reason` enum は初期セット + 追加可能と位置付ける(失敗パターン観察の蓄積で拡張する前提)。

#### impl 系

| state | 意味 | 後続 |
|---|---|---|
| `Impl-Done` | impl が完遂、`signal: claude-impl-done` 投稿、PR ready_for_review | review-pending |
| `Impl-Blocked` | impl が障壁検知で停止、`signal: claude-impl-blocked` 投稿 + `needs-human-decision` 付与、PR は Draft のまま | 人手介入待ち |
| `Impl-Aborted` | impl が `signal` を出さずに workflow 異常終了。reason で詳細化 | cleanup + 人手介入 |

#### impl-fix 系

| state | 意味 | 後続 |
|---|---|---|
| `Impl-Fix-Done` | impl-fix が指摘対応完遂、`signal: claude-impl-done` 投稿 | review 再起動 |
| `Impl-Fix-Blocked` | impl-fix が障壁検知で停止、`signal: claude-impl-blocked` 投稿 + `needs-human-decision` 付与 | 人手介入待ち |
| `Impl-Fix-Aborted` | impl-fix が `signal` を出さずに異常終了 | cleanup + 人手介入 |

#### review 系

| state | 意味 | 後続 |
|---|---|---|
| `Review-Approved` | レビュワが approve 提出 | auto-merge enable へ |
| `Review-RequestedChanges` | レビュワが requested_changes 提出 | impl-fix 起動 |
| `Review-Aborted` | レビュー workflow が異常終了 | cleanup + 人手介入 |

#### merge 系

| state | 意味 | 後続 |
|---|---|---|
| `Auto-Merge-Enabled` | `gh pr merge --auto --squash` 実行成功 | GitHub 側で条件待ち → merge |
| `Auto-Merge-Failed` | auto-merge enable が失敗 | 人手介入 |

通知投稿(notify-human)と audit GHA(watchdog / field audit / 反映漏れ)は PR/Issue のライフサイクル状態軸ではなく **workflow run の outcome** として別軸で扱う。詳細は §2.7「Workflow run outcome enum」で定義する。

#### Aborted の reason enum(初期セット)

`Impl-Aborted` / `Impl-Fix-Aborted` / `Review-Aborted` 共通で、`reason` を以下の集合から選ぶ。

- `max-turns` — claude-code-action の `error_max_turns` で停止(SDK 出力で確認)
- `barrier` — barrier 検知の signal を投げる前に GHA runner 側が落ちた exception 系
- `oauth-revoked` — `CLAUDE_CODE_OAUTH_TOKEN` 失効
- `rate-limit` — Anthropic API の rate limit
- `quota-exhausted` — Claude Max 5h ウィンドウ / 月間枠の枯渇
- `context-overflow` — context window 超過
- `output-overflow` — output token 超過
- `throttle` — Anthropic 側の throttle
- `unknown` — 上記いずれにも分類できない

reason の特定方法は claude-code-action の SDK 出力ログ + GHA step の outcome を組合せて判定する。詳細は `docs/automation/conventions.md` に書き下す。

### 2.4 状態遷移

正常系の遷移グラフ(疑似 Mermaid 記法):

```
Idle --label:claude:ready+field=適用--> Waiting-Impl
Waiting-Impl --impl 起動--> Impl-In-Progress
Impl-In-Progress --signal:done--> Impl-Done --pr ready--> Review-Pending
Impl-In-Progress --signal:blocked--> Impl-Blocked
Impl-In-Progress --workflow 異常終了--> Impl-Aborted

Review-Pending --review 起動--> Review-In-Progress
Review-In-Progress --approve--> Review-Approved --auto-merge enable--> Auto-Merge-Enabled --CI green + up-to-date--> Merged
Review-In-Progress --requested_changes--> Review-RequestedChanges --impl-fix 起動--> Impl-Fix-In-Progress
Review-In-Progress --workflow 異常終了--> Review-Aborted

Impl-Fix-In-Progress --signal:done--> Impl-Fix-Done --signal で review 再発火--> Review-In-Progress
Impl-Fix-In-Progress --signal:blocked--> Impl-Fix-Blocked
Impl-Fix-In-Progress --workflow 異常終了--> Impl-Fix-Aborted

任意状態 --label:needs-human-decision 付与--> Human-Required(フラグ、状態は据え置き)
任意状態 --watchdog (SM-L1 起動失敗) 検出--> 元状態は据え置き + Human-Required フラグ ON
```

watchdog / 通知投稿の結果は PR/Issue 状態に影響しない(`Human-Required` フラグ立てや audit-log への記録だけが副作用)。それらは §2.7「Workflow run outcome enum」で扱う。

#### 不正遷移(Tier 1 で抑止する)

下記の遷移は **明示的に禁止** し、検出時には早期 exit + 通知を行う。実装は SM-13 / SM-14 / SM-08 / SM-01 などの派生として個別 PR で行うが、ステートマシン上の位置付けは本 ADR で定義する(派生実装の対応表は §2.8 を参照)。

| 不正遷移 | 抑止する派生実装 | 抑止方法 |
|---|---|---|
| `field ≠ 適用` の Issue で `claude:ready` 起動 | SM-13 | impl workflow 冒頭で Project field を GraphQL 取得、`適用` 以外なら早期 exit + キャンセル理由コメント + `claude:ready` 自動除去 |
| `Impl-Aborted` 後の宙ぶらりん | SM-14 | impl / impl-fix / review の最終 step を `if: always()` で実行し、`outcome != success` なら着手宣言コメントに「max-turns 等で異常終了、未着手」を追記 + `claude:ready` 除去 + `needs-human-decision` 付与 |
| 古い `Review-RequestedChanges` 残置 | SM-08 | impl-fix の `signal: claude-impl-done` 投稿時に既存の requested_changes review を `gh api ... /pulls/N/reviews/REVIEW_ID/dismissals` で自動 dismiss |
| 人手 fix 後の review 再起動経路欠落 | SM-01 | `signal: claude-human-fix-done` を write 権限ユーザ限定で issue_comment trigger 受付 → review 起動。または `re-review` ラベル付与 trigger を追加 |
| `Review-Approved` だが `out-of-date with base branch` | SM-10 | auto-merge enable 時に out-of-date を検知したら `PUT /repos/{owner}/{repo}/pulls/{number}/update-branch` を自動呼出 |
| workflow run 自体が起動しない(SM-L1 起動失敗 watchdog) | SM-L1 | watchdog GHA が `claude:ready` 付与から N 分タイムアウトで「対応 workflow run が存在しない」を検出 → `needs-human-decision` + audit-log finding |

#### Tier 1 で明文化のみ(実装は既存の挙動で十分)

| 観察事例 | 仕様としての位置付け |
|---|---|
| PR #247 で観察したレース自己回復(レビュワ A が stale SHA で CHANGES_REQUESTED → impl-fix が HEAD で動き → 次レビュワが新 SHA で APPROVED → auto-merge) | §2.5 で詳述。concurrency + review-per-user + auto-merge の合わせ技で **設計通りに自己回復する**。コード変更不要、ADR で明文化することで「これは仕様」を残す |
| SM-02 impl-fix の checkout 認証エラー(人手 PR を impl-fix が拾った時の token scope mismatch) | 前提条件 2.1.1「impl-fix の checkout SHA」が崩れる事象として Tier 1 明文化 + 派生実装で対応。`actions/checkout` の `token` 引数を `claude-automation-impl` App token に明示し、人手 PR でも fallback できる経路を確保 |

#### Tier 2(ADR で想定挙動だけ明文化、実装は当面しない)

未観測だが理論上発生しうる事象。観測されてから実装に格上げする。

- **PR close mid-run**: impl 実行中に人が PR を close した場合、impl は最終 commit を push しようとして失敗するか、push 成功後に close 済み PR にコメント投稿で完了する。後者の場合は `signal: claude-impl-done` が close 済み PR に残るが review は draft 同様に起動しないため自然消滅
- **`claude:ready` 剥がし mid-run**: impl 実行中にラベルが剥がされても workflow は走り切る。完了時に再起動はされないので人手側で意図通り
- **GHA infra failure**: GitHub 側の不調で workflow が異常終了するパターン。Aborted reason は `unknown` 扱い、cleanup は SM-14 と共通
- **連続 push 連打**: 同一 PR への短時間連続 push。concurrency 設定で latest-wins、最新 push のみが impl-fix → review を起動する
- **SM-L2 Claude 早期失敗**: OAuth 失効 / rate limit / Claude Max 枯渇等で claude-code-action が起動直後に error。`Impl-Aborted(reason=oauth-revoked|rate-limit|quota-exhausted)`、後続 cleanup は SM-14 と共通
- **SM-L3 Claude 実行中枯渇**: context window / output token / throttle を実行中に踏む。max-turns 死亡と挙動が近い。`Impl-Aborted(reason=context-overflow|output-overflow|throttle)`、後続 cleanup は SM-14 と共通

#### Tier 3(スコープ外)

- 並走 rate limit カスケード(複数 repo / 複数 PR が同時に Claude Max を食い尽くす連鎖)
- force push / 履歴書き換え(human が意図的に履歴を破壊するケース)
- claude-automation 本体の workflow file が変更された PR(R19/R20 制約は ADR-0001 §2.5 で対処済)

### 2.5 レース挙動の自己回復(仕様)

PR #247(2026-05-24)で観察した自己回復シーケンスを **仕様として明文化** する。下記の race は本 ADR の concurrency + review-per-user + auto-merge の合わせ技で **コード変更なしに自己回復する**。

観察例(PR #247):

1. レビュワ A が stale SHA `61de656` で `CHANGES_REQUESTED` を提出
2. impl-fix が HEAD SHA `7adad34` を checkout して修正 → push で `3529cc1` 生成
3. 次のレビュワ起動が新 SHA `3529cc1` で `APPROVED` を提出
4. auto-merge が新 SHA の APPROVED + CI green を確認して merge

仕様としての説明:

- pending キューが latest-wins で自動圧縮されるため、queue 暴走は起きない
- 同一 reviewer bot の最新 review が古い `CHANGES_REQUESTED` を上書きする(GitHub の review-per-user セマンティクス)
- auto-merge は「新 SHA の APPROVED + CI green + up-to-date + dismiss_stale_reviews 設定」を待つため、stale SHA の review に騙されない

「設計通りにそうなる」ことを ADR で残すことで、将来同様の race を観察した時に「これは仕様、対処不要」と即判断できるようにする。

### 2.6 通知ポリシー(3 直交軸)

通知の発火条件と集約先を 3 軸で分解する。

#### 軸 1: アクション

- 自動回復試行(失敗時のみ通知)
- 即時通知
- 静かにログ
- 不要

#### 軸 2: 通知強度

- 即時メンション = `needs-human-decision` 付与 + `@win2cot` メンション(コメント本文に明示)
- バッチ集約 = audit-log Issue にコメント append、メンションなし

#### 軸 3: 集約先

- 本体 Issue/PR — 当該作業の Issue or PR に直接コメント
- 実行ログ収集 Issue — claude-automation repo の専用 Issue 1 本に全 run record を append
- audit-log Issue — claude-automation repo の専用 Issue 1 本に watchdog / field audit / 反映漏れ検知結果を append

#### 各遷移へのマッピング

| 遷移 / 検知 | アクション | 強度 | 集約先 |
|---|---|---|---|
| `Impl-Blocked` / `Impl-Fix-Blocked` | 即時通知 | 即時メンション | 本体 Issue/PR + 実行ログ収集 |
| `Impl-Aborted` / `Impl-Fix-Aborted` / `Review-Aborted` | 即時通知 | 即時メンション | 本体 Issue/PR + 実行ログ収集 |
| field 不整合での `claude:ready` 起動キャンセル(SM-13) | 即時通知 | 即時メンション | 本体 Issue |
| SM-L1 起動失敗 watchdog の検出 | 即時通知 | 即時メンション | 本体 Issue/PR + audit-log |
| `Merged`(linked Issue のクローズトリガー) | 即時通知 | 即時メンション(`@win2cot`) | 当該 Issue。「次の Issue を選べる」シグナル。**メンション = 失敗だけではない** ことを明示 |
| `Impl-Done` / `Impl-Fix-Done` / `Review-Approved` / `Auto-Merge-Enabled` | 静かにログ | (メンションなし) | 実行ログ収集 |
| SM-10 auto-update branch / SM-08 requested_changes dismiss の自動回復 | 自動回復試行 → 失敗時のみ通知 | 失敗時メンション | 本体 Issue/PR + 実行ログ収集 |
| SM-16 / SM-17 audit GHA の検知結果 | バッチ集約 | バッチ集約(重大な不整合のみメンション付き) | audit-log |

メンション対象は厳しく絞る方針(通知過剰回避)。具体的なメンション条件は `docs/automation/conventions.md` で書き下す。

ADR-0001 までは Slack `#cowork-inbox` への Daily Inventory 通知に依存していたが、本 ADR では Slack 経由通知を採用しない。理由は別途 SM-18 Slack 撤去で扱うが、ステートマシン上の通知出力は **GitHub Issue 集約 + needs-human-decision + @win2cot メンション** に一本化する。

### 2.7 実行ログ収集 / audit-log の record スキーマ

run 単位の記録は claude-automation repo の **専用 Issue 2 本** に分離して集約する。判定基準は「この workflow run には Target が 1 つ決まっているか?」。

| Issue | 記録対象 | 該当 workflow |
|---|---|---|
| **実行ログ収集 Issue**(仮称: runs-log) | 特定 Target に対して作業した workflow の実行結果 | impl / impl-fix / review / auto-merge / notify-human |
| **audit-log Issue**(仮称: audit-log) | 複数 Target を scan / audit する workflow の検知結果 | SM-L1 起動失敗 watchdog / SM-16 field audit / SM-17 反映漏れチェック |

#### 共通設計指針

- 1 run = 1 コメント、Issue 1 本に append-only、never close
- フォーマット: Markdown ヘッダ + 表(可読性)+ 末尾の JSON ブロック(機械集計)のハイブリッド
- `schema_version` で版管理。フィールド追加だけなら version 据え置き、削除・型変更で +1
- `run_id`(GHA `${{ github.run_id }}`)を一意キー、再起動 retry は別 record として記録
- audit-log は **検知ゼロの run も毎回残す**(watchdog 自体の沈黙を検出可能にするため)

#### Workflow run outcome enum(notify-human / audit GHA の run outcome 軸)

§2.3「Outcome state enum」が PR/Issue のライフサイクル状態軸を扱うのに対し、本軸は **workflow run の outcome** を扱う。状態軸とは独立で、ライフサイクル状態を変更しない(副作用としてラベル付与や audit-log append が起きるのみ)。

notify-human 用:

| state | 意味 | 後続 |
|---|---|---|
| `Notify-Posted` | `needs-human-decision` 関連のコメント投稿成功 | — |
| `Notify-Failed` | コメント投稿が失敗(`gh issue comment` の API rate limit / network error など) | 障害として人手介入 |

audit GHA 用(SM-L1 起動失敗 watchdog / SM-16 field audit / SM-17 反映漏れチェック):

| state | 意味 | 後続 |
|---|---|---|
| `Watchdog-Clear` | 不整合・反映漏れを検出せず完了 | — |
| `Watchdog-Triggered` | 不整合・反映漏れを検出 | audit-log に finding 追記 + 必要に応じて `needs-human-decision` 付与(ライフサイクル状態は元のまま) |

#### 実行ログ収集 Issue の JSON フィールド

必須:

- `schema_version` (integer)
- `run_id` (string)
- `workflow` (`impl` | `impl-fix` | `review` | `auto-merge` | `notify-human`)
- `target` (`{type: "issue"|"pr", number: integer, repo: string}`)
- `state` — workflow に応じて以下のいずれか:
  - `impl` / `impl-fix` / `review` / `auto-merge` の場合: §2.3 Outcome state enum
  - `notify-human` の場合: 本節 Workflow run outcome enum の `Notify-Posted` / `Notify-Failed`
- `duration_ms` (integer)
- `started_at` / `ended_at` (ISO8601)
- `artifacts` (`{branch_created: bool, pr_created: bool, commits_pushed: integer, comments_posted: integer}`)

失敗時のみ:

- `reason` (Aborted reason enum、§2.3 参照)

Claude 実行時のみ:

- `num_turns` (integer)

拡張候補(後付け、schema_version 据え置きで追加可):

- `scope` — impl 着手宣言コメント由来の `{files: integer, adrs: integer, est_turns: integer}`(SM-03 Issue 起票時 scope ガイドラインの根拠データ)
- `cost` — `{tokens: integer, usd: number}`
- `timings` — `{start_to_first_comment_ms, first_comment_to_pr_create_ms, pr_create_to_ready_ms, ready_to_done_ms}`

#### audit-log Issue の JSON フィールド

必須:

- `schema_version` (integer)
- `run_id` (string)
- `audit_type` (`launch-failure` | `field-audit` | `followup-audit`)
- `state` (Workflow run outcome enum の `Watchdog-Clear` / `Watchdog-Triggered`、本節上部参照)
- `duration_ms` (integer)
- `started_at` (ISO8601)
- `scanned_count` (integer)
- `findings` (array)

findings 要素:

- `target` (`{type, number, repo}`)
- `finding_type` (string、audit_type ごとに定義)
- `details` (object)
- `action_taken` (`needs-human-decision-applied` | `comment-posted` | `none`)

#### 集計の使い方

- Cowork や人手で `gh issue view <runs-log-issue> --comments --json comments` を取得 → JSON ブロックを抽出 → 集計
- SM-04 max-turns 増の効果測定 = `state = Impl-Aborted` かつ `reason = max-turns` の件数を before/after で比較
- SM-03 scope ガイドライン策定 = `scope.files` ↔ `state`(完走/Aborted)の相関分析
- audit-log の `state = Watchdog-Clear` の連続性 = watchdog GHA 自体の生存確認(沈黙時に検知漏れに気づける)

### 2.8 派生実装の位置付け

本 ADR merge 後、以下を **親 tracker Issue + sub-issue + 依存関係 + Project Custom Field 埋め** の構造で一括起票する(memory feedback `feedback_issue_first_and_batched_intake` 準拠)。

#### Tier 1 派生実装

- **SM-14 max-turns 後処理** — impl / impl-fix / review の最終 step を `if: always()` で実行、`Impl-Aborted` / `Impl-Fix-Aborted` / `Review-Aborted` の cleanup
- **SM-13 claude:ready 空振り** — impl workflow 冒頭で Project field を取得し `適用` 以外なら早期 exit
- **SM-08 requested_changes 自動 dismiss** — impl-fix の `signal: claude-impl-done` 投稿時に古い requested_changes を dismiss
- **SM-01 人力修正の再レビュー経路** — `signal: claude-human-fix-done` または `re-review` ラベル trigger を追加
- **SM-10 out-of-date branch 自動 update** — auto-merge enable 時に out-of-date を自動 update
- **SM-L1 起動失敗 watchdog** — `claude:ready` 付与から N 分タイムアウトで workflow run 不在を検出
- **SM-02 impl-fix checkout 認証エラー対応** — 前提条件を崩す事象として ADR 内 Tier 1 で扱う。`actions/checkout` の `token` 引数 + 人手 PR 拾い時の fallback 設計

#### ADR の外側(独立 PR、ステートマシン準拠だが ADR で定義不要)

- **SM-11 impl-fix 出力の markdown 保持** — PR description / 対応完了レポートの markdown 構造保持
- **SM-06 CI 完了前 review 抑止** — review workflow 冒頭で CI 完了待ち or `check_suite.completed` trigger 化
- **SM-04 max-turns 上限増** — `reusable-impl.yml` の max-turns を 80 / 100 に増やす実験
- **SM-05 actions Node 24 移行** — `actions/checkout@v5` 即時、`actions/create-github-app-token` stable v3 待ち
- **SM-15 実行ログ収集の収集側実装** — record スキーマは §2.7 で定義、収集 step の実装は独立 PR
- **SM-18 Slack 撤去** — Daily Inventory GHA / Secret / App / channel の撤去
- **SM-16 field audit 防波堤** — Project Custom Field 空欄スキャン、優先度最低、Step 13 完了時に再判定
- **SM-17 反映漏れ防波堤** — ADR / 設計規約 merge 時の関連 Issue 追従チェック、優先度最低
- **SM-09 sandbox CDN 許可**(v1.3.0 候補)
- **SM-07 `@claude` で Issue 起票**(v1.3.0 候補)

#### 不採用

- **SM-12 半適用 復活** — 運用区別が無く意味なし。「適用 / 不適用 / 議論先行」3 値の現行設計を維持

#### SM 番号の付与方針と対応表

SM 番号は本 ADR 内のローカル識別子であり、派生実装の Issue・PR・コミットメッセージから本 ADR の特定派生を一意に指すためのコード。命名は「ステートマシン派生」の `SM-` プレフィックス + 通し番号(限界系は `SM-L<n>`)。

メモリ・既存議論で使われていた旧記号(課題コード `(a)`〜`(s)` と `(L-α/β/γ)`)からの対応表:

| SM 番号 | 旧記号 | 呼び名 | 本 ADR での位置付け |
|---|---|---|---|
| SM-01 | (a) | 人力修正の再レビュー経路 | Tier 1 派生 |
| SM-02 | (b) | impl-fix checkout 認証エラー対応 | Tier 1 派生(前提条件 §2.1.1 を崩す事象として明文化) |
| SM-03 | (d-i) | Issue 起票時 scope ガイドライン | ADR 外 / 運用データ蓄積後に確定 |
| SM-04 | (d-ii) | max-turns 上限増(80 / 100 実験) | ADR 外 / 独立 PR |
| SM-05 | (e) | actions Node 24 移行 | ADR 外 / 独立 PR |
| SM-06 | (f) | CI 完了前 review 抑止 | ADR 外 / 独立 PR |
| SM-07 | (g) | `@claude` で Issue 起票 | ADR 外 / v1.3.0 候補 |
| SM-08 | (h) | requested_changes 自動 dismiss | Tier 1 派生 |
| SM-09 | (i) | sandbox CDN 許可 | ADR 外 / v1.3.0 候補 |
| SM-10 | (j) | out-of-date branch 自動 update | Tier 1 派生 |
| SM-11 | (k) | impl-fix 出力の markdown 保持 | ADR 外 / 独立 PR |
| SM-12 | (l) | 半適用 復活 | 不採用 |
| SM-13 | (m) | claude:ready 空振り抑止 | Tier 1 派生 |
| SM-14 | (n) | max-turns 後処理(cleanup) | Tier 1 派生 |
| SM-15 | (p) | 実行ログ収集の収集側実装 | ADR 外 / 独立 PR(record スキーマは ADR §2.7) |
| SM-16 | (q) | field audit 防波堤 GHA | ADR 外 / 独立 PR(優先度最低) |
| SM-17 | (r) | 反映漏れ防波堤 GHA | ADR 外 / 独立 PR(優先度最低) |
| SM-18 | (s) | Slack 撤去 | ADR 外 / 独立 PR |
| SM-L1 | (L-α) | 起動失敗 watchdog | Tier 1 派生 |
| SM-L2 | (L-β) | Claude 早期失敗(OAuth 失効 / rate limit / quota) | Tier 2 明文化のみ |
| SM-L3 | (L-γ) | Claude 実行中枯渇(context / output / throttle) | Tier 2 明文化のみ |

旧記号 `(c)`(Draft 維持 + 部分 push の挙動仕様化)および `(o)`(本 ADR 自身)は SM 番号付与せず。memory 内の旧記号は本 ADR とは別タイミングで一括 rename する。

### 2.9 用語

| 表記 | 用法 |
|---|---|
| ステートマシン | 本 ADR の対象。日本語 ADR / 規約 / Issue / コミットメッセージ全般で本表記を採用する(「状態機械」は不採用)|
| state machine | 英語表記は変更なし。コード上の識別子や英語ドキュメントではそのまま使う |
| 状態 / state | enum 上の各要素(`Impl-Done` 等)を指す |
| 遷移 / transition | 状態間の有向辺。許可遷移と不正遷移を区別する |
| 遷移条件 / guard | 遷移を発火させる条件(label / signal / event / Project field の組合せ) |
| 出力 / output | 遷移発火時に行う副作用(コメント投稿 / ラベル付与 / runs-log append 等) |

## 3. 採用しなかった選択肢と理由

- **個別パッチ継続(ステートマシン化なし)**: SM-13 / SM-14 / SM-08 / SM-01 などを個別 PR で順次対応する案。短期では速いが、各 reusable workflow の `if` 条件が独立進化してスパゲッティ化する懸念が強い。21 件規模の改善ネタを抱えた現状で骨格を作らないと、後の保守性が大きく毀損する
- **ADR で決定根拠だけ書き、状態定義・遷移表・スキーマは別 spec ファイルに分離**: ADR-0001 が既に「決定 + 詳細」を 1 ファイルに含むスタイルを確立しており、本 ADR でも同スタイルを踏襲する方が一貫性が高い。spec 分離は ADR 改訂時の二重管理リスクが大きく、本 ADR が 500 行を超える肥大化が起きた段階で再検討する
- **「状態機械」表記**: 日本語ソフトウェア工学文脈で「ステートマシン」が定着している(2026-05-25 win2cot 指摘)。「状態機械」は機械系・制御系の訳語イメージが強く、本プロジェクトでは採用しない
- **半適用 field 復活(SM-12)**: 「適用」と運用上区別されず判定コストだけ発生していた経緯から不採用、現行 3 値設計(適用 / 不適用 / 議論先行)を維持
- **Slack 経由通知の継続**: ADR-0001 期は Daily Inventory が Slack `#cowork-inbox` に集約していたが、`api.github.com` allowlist 解除で Cowork 直叩きが可能になり Slack の価値が消滅。通知出力は GitHub Issue 集約 + `needs-human-decision` + メンション に統一する(詳細は SM-18 Slack 撤去 で扱う)

## 4. 影響範囲

### claude-automation

- **新規ドキュメント**: 本 ADR 1 本(`docs/adr/0002-claude-automation-state-machine.md`)
- **ADR-0001 への追記**: 「関連 ADR」セクションに本 ADR へのリンクを追記(別 PR で対応可)
- **`docs/automation/conventions.md` 拡張**: Outcome state enum / reason enum / runs-log・audit-log のフォーマット例 / メンション条件の書き下しを追加(派生実装の最初の PR と同時 or 直前)
- **5 reusable workflow の `if` 条件改修**: SM-14 / SM-13 / SM-08 / SM-01 / SM-10 派生実装の各 PR で順次。本 ADR merge では workflow ファイル自体は変更しない
- **新規 GHA**: SM-L1 watchdog GHA、SM-15 実行ログ収集 step の追加、SM-16 / SM-17 防波堤 GHA(後者は優先度最低)
- **新規 Issue 2 本**: runs-log Issue / audit-log Issue を claude-automation repo に open 維持で立てる(派生実装の最初の PR で実施)

### tasks-webapi

- shim workflow は `@v1` 参照のため、本体側 reusable workflow の `if` 条件改修は自動取込される
- 追加 secret / 追加 App permission は **不要**(本 ADR のスコープ内では既存 permission で完結)

### 派生実装の Issue 起票

- 本 ADR merge 後、Tier 1 派生 7 件 + ADR 外独立改善 9 件 = 計 16 件規模を **親 tracker + sub-issue + 依存関係 + Project field 埋め** で一括起票する
- 起票時の優先順は memory `project_claude_automation.md` の「改訂版シーケンス」(Step 2〜14)に従う

## 5. 検証計画

本 ADR 自身は文書なので、検証は (1) ドキュメント整合と (2) 派生実装の動作実証の 2 軸で行う。

### (1) ドキュメント整合(本 ADR merge 前に実施)

- ADR-0001 との論理的整合(アクター・トリガー・signal 規約・App 構成・bot identity に齟齬なし)
- memory `project_claude_automation.md` のブレスト結果(① 観察棚卸し / ② スコープ仕分け / ③ 通知ポリシー / ④ ログスキーマ)との対応関係
- 用語統一: 「ステートマシン」「状態機械」の grep を merge 前 commit で実施(memory feedback `feedback_grep_before_rename_commit` 準拠)
- SM 番号一意性 + 旧記号対応表との双方向整合
- 遷移表の双方向確認: §2.4 で挙げた状態が §2.3 enum に全て登場、逆も成立
- §2.3 Outcome state enum(PR/Issue ライフサイクル軸)と §2.7 Workflow run outcome enum(workflow run 軸)が混ざっていないこと

### (2) 派生実装の動作実証(本 ADR merge 後、各派生 PR で)

派生実装ごとに以下のいずれかで検証する。

- **SM-14 max-turns cleanup**: 既観測の早期死亡パターン(branch 未到達)を意図的に再現 or 過去 run のシミュレーションで cleanup step の動作確認
- **SM-13 field 不整合**: テスト用 Issue を「不適用」field で起票 → `claude:ready` 付与 → workflow 早期 exit + キャンセル理由コメント + ラベル除去を確認
- **SM-08 requested_changes dismiss**: ローカル sample app PR で意図的に requested_changes → impl-fix → 古い review が dismiss されることを確認
- **SM-10 auto-update branch**: out-of-date PR を作って approve、自動 update が走り CI 再走 → auto-merge へ
- **SM-L1 watchdog**: `claude:ready` 付与だが workflow が起動しない条件(短期は手動で workflow disable)を作って watchdog 検出を確認
- **SM-02 impl-fix checkout**: 人手 PR で意図的に impl-fix を起動 → token scope が正しく動作するか確認

各派生実装の検証手順は派生 PR 内で書き下す。本 ADR としては「派生実装で必ず検証手順を明示する」ことを規約とする。

## 6. 関連ドキュメント

- `docs/adr/0001-claude-automation-design.md` — 本 ADR が前提とする高レベル設計
- `docs/automation/strategy.md` — 運用手順(Mermaid 図含む、派生実装に合わせて更新予定)
- `docs/automation/conventions.md` — マーカー・未対応テーブル・ラベル規約。Outcome state enum / Workflow run outcome enum / reason enum / メンション条件の書き下しを追加予定
- `docs/automation/prompts/system-impl.md` / `system-review.md` — impl / レビュワプロンプト本体。派生実装で更新予定
- `docs/automation/discussions/2026-05-19-design-v3_2.xlsx` — ADR-0001 議論履歴
- (新規) runs-log Issue / audit-log Issue — 派生実装の最初の PR で立てる、URL を本 ADR に追記
- (memory) `project_claude_automation.md` — v1.2.0 改善ネタ全体記録 + ブレスト結果

## 7. 残課題

- **派生実装 16 件の Issue 起票**: 親 tracker + sub-issue + 依存関係 + Project field 埋めを本 ADR merge 後に実施
- **SM-16 / SM-17 防波堤 GHA の着手要否最終判定**: Step 13 完了時に Cowork proactive fill の実運用穴を再評価して着手要否を決める。v1.3.0 繰越も選択肢
- **runs-log Issue / audit-log Issue の番号確定**: 立てた時点で本 ADR の §2.7 と §6 にリンク追記
- **SM-03 scope ガイドラインの数値根拠データ蓄積**: SM-15 実行ログ収集の `scope` フィールドが稼働後、Sprint 0 / Sprint 1 で 1〜数ヶ月運用してから経験則に基づく分割基準を確定
- **v1.2.0 release notes 構成**: 派生実装 PR を段階 release するか、まとめて v1.2.0 タグするかを Step 12 程度の時点で判断
- **ADR-0001 §2.7 「ラリー上限」「同一指摘検知」の本 ADR への取込判断**: R02(ラリー上限)/ R04(設計分岐判定基準)は ADR-0001 残課題として残置されている。本 ADR の Tier 1 派生実装と連動する可能性があるので、SM-04 max-turns 増の効果測定後に再評価
- **memory 内の旧記号 rename**: project_claude_automation.md ほか memory 全般の旧記号 `(a)`〜`(s)` / `(L-α/β/γ)` を本 ADR の SM 番号に揃える作業。ADR merge とは別タイミングで実施
