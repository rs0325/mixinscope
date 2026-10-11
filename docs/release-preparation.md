# v0.1.0 正式リリースの準備と公開手順

VERSIONはCLI / JSON / Gradle配布名の唯一の正本。GitHubの公開名は`v0.1.0`とし、pre-releaseではない正式配布にする。mainはリリース用、developは開発用・デフォルトブランチ。alpha版のtag / Release / assetは保持する。

正式版の対応範囲はREADMEとリリースノートに記載した静的解析。HIGHはクラッシュの確定ではなく、Modpack全体の互換性やprecision / recallは未確定。固定2 Modの実Minecraft追加対照は限定された根拠であり、全loader / client / 意味的干渉を検証済みとは案内しない。

## 候補生成と検証

```sh
./gradlew --no-daemon test fixtureIntegration demo installDist distZip distTar
python3 tools/evaluate-evidence.py
python3 tools/prepare-release.py
```

`build/release/0.1.0/`にZIP / TAR、SHA256SUMS、RELEASE-NOTES.md、verification.jsonを生成する。検証ツールは正式版とalpha版の番号を受け付ける。両archiveの内容一致、Unix launcherの実行属性、MITとruntime依存のlicense / notice、依存JAR hashを確認する。検証済みZIPを一時directoryへ展開し、展開版CLIのversionと自作Mod scanのJSON版・MS002を照合する。実Modは実行しない。

ZIPとTARの内容は同じ。JDK/JREは同梱しない。MIT、README、CHANGELOG、THIRD-PARTY、docs、licenses、Windows / Unix launchersとruntime依存を同梱する。テストfixtureとMixin runtimeは含めない。CIは3 OSで配布検証を行い、候補一式をartifactへ保存する。

HTML / JSON schemaの検証にはNode.js 22以上とpnpm 11.25.0を使う。通常のCLI利用にはJava 21以上だけが必要。

```sh
pnpm install --frozen-lockfile --ignore-scripts
pnpm test:html
pnpm test:schema
```

## 統合と公開

1. Issue専用ブランチの変更・配布候補・PR本文を用意し、対象commitの3 OS CIとレビューを確認する。commit / push / PR / merge / tag / Release公開はユーザーの許可範囲で行う。
2. developへ統合した後、developからmain向けのリリースPRを作る。リリース対象以外の変更が含まれないことを確認し、CI成功・レビュー後にmainへ統合する。mainのcheckoutをprimaryで変更しない。
3. 公開対象main commitのcleanな専用checkoutで上記の候補を再生成する。`verification.json`のsourceRevisionが対象commit、sourceDirtyがfalseであることを確認する。PR CIのsourceRevisionは仮のmerge commitの場合があるため、そのまま正式assetの根拠にはしない。
4. ZIP / TARのSHA-256をSHA256SUMSと照合する。OS間の完全なarchive hash一致は前提にせず、選択した一組のZIP / TARとchecksumsを公開する。
5. 対象main commitに`v0.1.0` tagを作り、GitHub Releaseを非pre-releaseとして公開する。公開assetはZIP / TAR / SHA256SUMS / verification.json、本文は[リリースノート](release-notes.md)。既存tagの上書きやforce pushはしない。
6. 公開先のtag commit、Releaseのdraft / prerelease状態、asset名・サイズ・hashを確認する。利用手順のリンクと公開assetの展開版CLIを確認し、結果をIssueへ記録する。

Issue・マイルストーンは、それぞれの完成条件とcloseの許可を確認して終了する。developはデフォルトなので、develop宛てPRのClosesは自動close対象になる。main宛てPRの記載だけで自動closeされるとは扱わない。公開前にIssueが閉じられないよう、準備PRはRefs、正式公開完了までIssueを維持する。
