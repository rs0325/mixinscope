# コード・設計レビュー（2026-10-10）

競合判定、対象解決、JSON契約、処理制限を対象に一巡のレビューを行った。発見した6点を修正し、同じ入力で問題が再現しない回帰テストを追加した。実Minecraftの起動とHTMLの実ブラウザ確認は、このレビューの範囲に含めない。

## 発見事項と修正

| 優先度 | 問題 | 修正・確認 |
|---|---|---|
| P1 | 未対応注入点・args・不正な候補が同じ文字列というだけで強い候補一致になり得た | 対応宣言のgrammarを共通検証。JAR-only / referenceの両方でMS003等へ渡さず、diagnosticとraw inventoryを保持 |
| P2 | 重複slice IDを先頭の定義で解釈していた | 未対応として記録し、命令一致を検証済みにしない |
| P1 | 命令・mapping等の処理中に協調的な時間予算を確認しない経路があった | records / 命令 / slice / shift / metadata登録にも確認を追加。期限切れはpartial analysisへ伝える |
| P2 | directory候補を全件保持してからsortし、期限後も入力ごとに失敗を列挙し続ける経路があった | 保持するJAR候補をmax-entriesで制限し、辞書順の先頭を選ぶ。省略はpartial。期限切れは全体へ伝える |
| P1 | suppressionの条件名の誤記が無視され、意図より広く抑制され得た。不完全なbaselineにDTOの既定値を補い得た | suppressionの既知field・型・期限を全件検証してから適用。baselineは使用するheader・構造・coverage counter・IDの欠損/重複を拒否 |
| P2 | PrintWriterがstdoutエラーを保持しても成功終了になり得た | 書込み後のcheckErrorを検査し、OUTPUT_ERROR / exit2にする |

## 確認した設計上の境界

- 共有メソッドはMS001 / LOW。強い規則には一意なtarget identityと、対応する候補・命令照合を必要とする。severity、confidence、applicabilityは独立して扱う。
- Mixin root selector / Overwriteはdeclared class内のmethodを対象とする。親の継承宣言は参考情報で、子のtargetを親の変更として集約しない。追加transformerのmergeは未検証。
- refmap / Tinyのnamespaceと版を宣言し、literalや混在namespaceを検証済みにしない。dynamic selector / local / expression等の未対応範囲をcoverageに残す。
- JSONは完全inventoryを維持し、フィルタをvisibleFindingIdsに分離。対象summaryは各findingと対応する。suppressionは削除ではなく理由付き状態。schema/rulesetやcoverage差があるbaseline消失を解消と断定しない。
- JAR・Mod・plugin・serviceを解析中にロードしない。ZIPのpath / CRC / size / ratio / depth / entriesと候補数を制限し、失敗・省略を報告する。入力上限はOSのheap / CPU sandboxではない。
- HTMLの埋込みJSONをescapeし、描画はtextContentを使う。ネットワーク通信を要求しない。出力失敗時は既存destinationを保持する。
- Wrapper checksum、依存lock、固定CI actionsを確認。テスト用Mixin runtime / fixture serviceをCLI配布物に含めない。これは依存関係の包括的な脆弱性監査ではない。

## 検証と残る条件

160件のJava回帰テスト、14ケースの自作runtime対照、8レポートのJSON Schema検証、HTML DOM検証、固定実Mod6ケースの静的解析、配布物生成がローカルで成功。3 OS CIの結果はdraft PR #18の最新検証欄に記録する。コマンドと観測結果は[validation.md](validation.md)、[real-mods-results.json](real-mods-results.json)、draft PR #18の検証欄を参照。

レビューで見つけた上記の問題は修正済み。その後の実Chrome確認で通常レポートの目視・キーボード操作を検証し、320px幅でfilterが横へ広がる問題を修正した。長い名前・253件・空レポートの実画面は準備待ちで、HTMLの包括的な確認は未完了。詳細は[html-verification.md](html-verification.md)。実Modpackのfalse positives / false negatives、他runtime版、完全なloader依存選択と任意transformerの効果は既知の未検証範囲として維持する。レビュー記録はmainへのマージやリリース承認を意味しない。
