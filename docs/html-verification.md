# HTML確認（2026-10-10）

## 今回の変更と自動確認

Filters / Findings / Inventoryへの移動リンクを追加した。253 findingがあってもすべてのカードをTabで通過せず主要箇所へ移れる。移動先をfocusし、Inventoryへの移動ではdetailsを開く。カード内の対象・Modボタンには対象名を含むaccessible nameを付けた。

長い対象・Mod名の折返し、controlの最大幅、600px以下のfilter縦配置を追加。実Chromeの320px表示でfilterのGrid列がcontrolの最小幅に引っ張られ、本文より横に広がる問題を発見した。列を`minmax(0,1fr)`とし、label / controlに`min-width:0`を設定。修正後の通常レポートを320 / 375 / 768pxで確認し直した。長い名前fixtureの実画面はまだ確認していない。

DOMテストはリンク起動後のfocus、最後のページで消えるShow moreから件数表示へのfocus、Modボタンからfilterへのfocus、native label、finding ID / 件数、253件のページング、200 graph entries、長い文字列と空レポート、script文字列の非実行を検証する。JSDOMはブラウザのTab / Enter / Space動作やレイアウトを再現しない。

## 実ブラウザの確認手順

`./gradlew demo`で生成される次のファイルをローカルで開く。すべて自作入力で、ネットワーク通信は要求しない。

| ファイル | 用途・期待値 |
|---|---|
| `build/demo/report.html` | A/BのattackにMS001 LOWとMS002 HIGH、2 finding / 1 target summary。Mod Cはgraph inventoryで確認 |
| `build/demo/html-stress-report.html` | 253 finding。100→200→253のページング。Include suppressedを外すと250。graphは最大200 entries |
| `build/demo/long-label-report.html` | 250文字以上の対象名・Mod ID。2 finding / 1 target summary |
| `build/demo/empty-report.html` | 0 matching / 0 total、Show more非表示 |

確認時はOS、ブラウザ版、viewport幅、zoom、実施者と結果を記録する。

1. 通常幅と320 / 375 / 768px前後、200% zoomで、本文・長い名前・filter・buttonが重ならず、ページ全体の横スクロールで隠れないことを確認。
2. マウスを使わずTab / Shift+Tabで移動し、focus枠が見えることを確認。最初のFilters / Findings / InventoryリンクをEnterで起動し、目的箇所へ移動できることを確認。
3. search入力、selectの矢印キー、Include suppressedのSpace、ResetのEnterを確認。リセット後は全件に戻ることを確認。
4. Inspect target / Mod buttonをEnterまたはSpaceで起動し、target / mod filterへfocusが移ることと、Inventoryに期待するtarget→Mod→Mixin→handlerが出ることを確認。
5. summaryをEnter / Spaceで開閉し、evidence・coverage・baseline・suppressionが読めることを確認。
6. stressのShow moreを起動して最後まで進み、ボタンが消えたとき件数表示へfocusが移ることを確認。0件になったときも移動リンクが使えることを確認。
7. 長い名前が省略されるselectは、対象見出し・evidenceで全名を読めることを確認。各HTMLのfinding IDを隣のJSONまたは埋込みreportと照合する。

## 状態

160 Java tests、14 authored runtime cases、HTML DOM、通常 / CLI / 長い名前の3 JSON Schema reports、配布物生成がローカルで成功。実ブラウザの結果はこれらと分けて記録する。

## 実Chromeの観測結果

2026-10-10、macOS 15.7.7 / arm64、Google Chrome 154.0.8037.99。ユーザーが通常レポートのfile URLを開き、Computer Useでの確認を指定し、必要な操作権限を準備した後、Codexがnative accessibility tree・スクリーンショット・キー入力で確認した。ブラウザ制御APIによるfile URL移動は使用していない。

| 対象 | 観測結果 |
|---|---|
| 通常レポートのレイアウト | Chrome DevToolsのResponsive幅320 / 375 / 768 CSS px、zoom 100%。修正後はfilterとカードが本文の幅に収まり、確認した表示箇所に重なり・横はみ出しなし。実端末の検証ではない |
| 200% zoom | device emulationを解除しChromeの倍率表示200%を確認。本文・filter・Findingカードが折り返され、FindingsリンクのEnterで件数表示へ移動しfocus枠が見える。終了後100%へ戻した |
| 移動と絞り込み | Tab / Shift+Tab / EnterでFilters→search、Findings→件数、Inventory→開いたsummaryへ移動。searchの不一致は0件。native selectの矢印キーでrisk HIGHはMS002の1件。checkboxのSpaceとResetのEnterを確認 |
| Mod / target / graph | Inspect targetのEnterでtarget filterへfocus、Inventoryが開きattackのMod A/B→Mixin→Overwriteを表示。ModボタンのEnterでmod filterへfocus。Mod Cは0 findingでもgetAttributeValueのgraphを保持 |
| 詳細 | Evidence、Inventory、coverage、suppression / baselineのsummaryをSpace / Enterで開閉。キーボードfocus枠と、展開した内容を確認 |
| JSONとの照合 | MS001は`148658ca8facb8fd2e18797ec47e9f9e93f6072b0148a6786c046274a8d93bf0`、MS002は`896755e70bc1a991b377692aaa0e82b9451ba45fd12d791e3c2bca36ec29e560`。実画面のEvidenceのIDが隣のreport.jsonと一致。件数2 / summary 1も一致 |
| Console | 最初にChrome翻訳関連のinvalid URL / CSP拒否等が見えた。修正後のreloadではConsoleにNo errors / No issues。ブラウザの翻訳機能を無効化せず、レポートのCSPも変更していない |

通常レポートの実画面とキーボード確認は実施済み。長い名前・253件・空レポートは、ユーザーに追加タブで開くよう依頼し準備待ち。これらのDOM検証を実画面確認の代わりに完了扱いしない。Issue #17の包括的な目視・キーボード受入項目は未チェックを維持する。他ブラウザ、screen reader、実モバイル端末の検証は行っていない。
