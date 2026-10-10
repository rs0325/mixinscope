# HTML確認（2026-10-10）

## 今回の変更と自動確認

Filters / Findings / Inventoryへの移動リンクを追加した。253 findingがあってもすべてのカードをTabで通過せず主要箇所へ移れる。移動先をfocusし、Inventoryへの移動ではdetailsを開く。カード内の対象・Modボタンには対象名を含むaccessible nameを付けた。

長い対象・Mod名の折返し、controlの最大幅、600px以下のfilter縦配置を追加。幅制限はCSSの実装であり、下記の実画面の検証結果ではない。

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

実ブラウザの目視・キーボード確認は未完了。前回のローカルfile URL読み込みがブラウザ制御ツールのURLポリシーで拒否されているため、HTTP配信・別の制御surface・headless browser等による回避は行わない。DOM検証を目視確認の代わりに完了扱いしない。Issue #17の該当受入項目は未チェックを維持する。
