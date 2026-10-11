# MixinScope

Minecraft Mod の JAR を実行せずに読み、Mixin の適用対象と潜在的な互換性リスクを調査する CLI です。

静的な変更箇所の重複と危険度の候補を報告します。正式版でも、実際の Modpack の互換性やクラッシュを確定するものではありません。

## 配布版から使う

初回正式版は `0.1.0` です。[GitHub Release](https://github.com/rs0325/mixinscope/releases/tag/v0.1.0)のZIP / TARを利用します。ZIP / TARを展開した場合、macOS / Linuxは `./mixinscope-0.1.0/bin/mixinscope`、Windowsは `mixinscope-0.1.0\bin\mixinscope.bat` を使います。`bin`と`lib`を一緒に保持してください。通常の解析にはJava 21以上だけが必要です。

[利用手順・リリースノート](docs/release-notes.md) · [公開準備と検証](docs/release-preparation.md) · [同梱依存のlicense / notice](THIRD-PARTY.md)

## 必要環境とビルド

Java 21 以上が必要です。Gradle Wrapper と依存ロックを同梱しています。通常の解析には Node.js は不要です。

```sh
./gradlew test installDist
build/install/mixinscope/bin/mixinscope --help
```

Windows では `gradlew.bat test installDist`、`build\install\mixinscope\bin\mixinscope.bat` を使います。

## 解析

```sh
build/install/mixinscope/bin/mixinscope scan ./mods \
  --loader fabric --loader-version 0.16.14 \
  --minecraft-version 1.21.1 --mixin-version 0.8.7 --mixinextras-version 0.4.1 \
  --input-namespace intermediary --side client
```

JAR を複数指定することもできます。`--side server` は専用サーバー、`--side both` は物理 client と専用 server を別のシナリオとして比較します。client-only と server-only の変更は同時適用とみなしません。

```sh
build/install/mixinscope/bin/mixinscope scan ./mods --format json --output report.json
build/install/mixinscope/bin/mixinscope scan ./mods --format html --output report.html
```

HTML はネットワーク通信・外部 CDN なしで閲覧できます。先頭の Filters / Findings / Inventory リンクで主要箇所へ移動できます。検索、危険度・confidence・Mod・対象のフィルタと、対象から Mixin handler への逆引きがあります。JSON は完全な inventory と findings を保持し、表示フィルタの結果を `visibleFindingIds` に格納します。

同じ対象への finding は `targetGroups` にまとめ、影響 Mod・最大 risk・未抑制の最大 risk・規則を表示します。個々の finding ID と evidence も保持します。グループは game / loader profile / namespace / owner / method / descriptor を含むキーで区別します。変更を持つ Mod は、finding がなくても inventory から辿れます。

名前だけの selector や wildcard は、他 Mod の descriptor 付き宣言から候補を収集します。これは完全なメソッド一覧ではなく、一つの候補が見つかっても解決済みにはしません。候補による重複は MS001 / LOW risk / LOW confidence に限定します。宣言された owner と、参照 classpath で確認した declaring owner は別々に記録します。Mixin の root selector / Overwrite が変更するのは対象クラス自身の method body です。親クラスの継承メソッドだけが見つかっても、target key を親へ移さず `TARGET_INHERITED_NOT_LOCAL` として未解決にします。`declaringOwner` に親を記録する場合も、検証済みの注入対象という意味ではありません。子が独自に宣言したメソッドは、親の overload と混ぜません。selector に対象と異なる owner が明示されている場合も診断に残します。

`--mod mod_id`、`--target attack`、`--min-severity high` は表示用のフィルタです。CI の判定には `--fail-on high` を使います。表示フィルタで危険な finding を隠しても終了コードの判定は変わりません。

### 元のゲーム bytecode を併用する

```sh
build/install/mixinscope/bin/mixinscope scan ./mods \
  --minecraft-version 1.21.1 --input-namespace intermediary \
  --mappings ./mappings.tiny --output-namespace named \
  --mapping-game-version 1.21.1 \
  --game-jar ./minecraft-named.jar --game-namespace named \
  --classpath ./reference-library.jar
```

Tiny v2.0 の owner・method・descriptor を正規化します。**ゲーム JAR は解決後の namespace と一致させてください。ゲーム JAR 自体の remap はしません。** mappings の Minecraft 版は Tiny に含まれないため、宣言した provenance が正しいことを利用者が確認する必要があります。`--mapping-game-version` の宣言が scan の版と異なる場合は拒否します。

Refmap の default mappings を読み、必要なら `--refmap-context <name>` で context を指定します。`--input-namespace` は選択した refmap を適用した後の target selector の namespace を表します。複数 namespace が混ざる pack は分けて解析してください。`remap=false` は literal selector として input namespace に残し、異なる output namespace の参照 bytecode では検証しません。

同じメソッド内に同じ呼出し先が複数あっても、ordinal・slice が異なる命令を指す場合は同一命令の衝突と区別します。参照 JAR は他 Mod の変換前の状態なので、Mixin の全適用パスや実行結果を再現したという意味ではありません。`java/lang/Object` を含む必要 classpath が欠けると、継承全体を完全とは扱わず、見つからないメソッドを MISSING と断定しません。

Refmap は注入先メソッドと `@At target`、slice の境界にも適用し、元の annotation は保持します。注入点側の `remap=false` はその参照の変換を抑止します。元 bytecode で一致する注入点がない場合は、`require=0` でも診断に残します。

`normalizedAt` / `normalizedSlices` は JAR-only の候補比較にも使います。refmap の文字列 class target は injector の `remap` と独立して扱います。shift が bytecode label をまたぐ場合や、literal point と参照 JAR の namespace が異なる場合は、命令位置を確認済みにはしません。

### 抑制と baseline

```json
{
  "suppressions": [
    { "rule": "MS002", "mod": "examplemod", "reason": "確認済みの組合せ", "expires": "2026-12-31" }
  ]
}
```

```sh
build/install/mixinscope/bin/mixinscope scan ./mods \
  --suppressions suppressions.json --date 2026-10-10 \
  --baseline previous-report.json --format json --output current-report.json
```

抑制は理由必須です。`suppressions` は配列とし、未知のフィールド名や空の selector はエラーにします。ファイル全体の検証が成功してから適用するため、誤記した条件で抑制を広げたり、一部だけ適用したりしません。finding を削除せず、`suppressed` と理由を記録します。期限当日まで有効で、既定の評価日は UTC の現在日です。baseline は new / unchanged / resolved を返し、解析失敗・未解決・不明な条件・環境差などで消えた finding は `unverifiedDisappearance` とします。`resolved` も静的な finding の消失であり、互換性の保証ではありません。

baseline の header、比較に使う配列・coverage・finding ID を確認し、不完全な JSON に現在版の既定値を補って比較することはしません。history ファイルは最大 32 MiB です。

現在の report schema は 1.1、ruleset は 4 です。1.0 の baseline も読み取れますが、schema / ruleset をまたぐ消失は `unverifiedDisappearance` に分類します。

## 初期の判定規則

| Rule | 条件 | Risk |
|---|---|---|
| MS001 | 同じ対象メソッドへの複数変更 | LOW |
| MS002 | 複数の明示的な `@Overwrite` | HIGH |
| MS003 | 同じ命令または同じ静的候補への複数 `@Redirect` | HIGH |
| MS004 | `@Overwrite` と他の変更の同居 | HIGH |
| MS005 | cancellable callback と他の変更の同居 | MEDIUM |
| MS006 | 同じ引数・定数・変数候補への値変更 | MEDIUM |
| MS007 | MixinExtras の共有対象 | LOW |
| MS008 | MixinExtras と Redirect の同じ操作候補 | MEDIUM |

Risk と confidence は独立です。confidence は規則の条件をどれだけ確認できたかを示し、実行時競合の確定度ではありません。MS003 の JAR-only 比較は候補一致なので MEDIUM confidence、参照 bytecode で同じ命令を確認すると HIGH confidence になります。曖昧な selector、plugin、nested candidate、namespace 不明などは LOW confidence です。

未対応の custom point、args、不正な descriptor、未対応の Constant 条件、重複 slice ID は JAR-only でも診断します。文字列が一致しても MS003 / MS006 / MS008 の同一注入候補とは扱いません。共有メソッドの MS001 は残ります。

MS006 は命令の重複だけで暗黙の引数・local variable・handler 型の同一性を証明しません。引数 index が明示されていない場合などは、命令が一致しても MEDIUM confidence に留めます。

## 対応範囲

| 項目 | 現在の範囲 |
|---|---|
| 実行 Java | Java 21 以上 |
| 入力 class file | major 45–72（ASM 9.11、preview の実行互換性は未検証） |
| Loader discovery | Fabric schema 1、Quilt schema 1、Forge/NeoForge TOML と manifest、登録された nested JAR |
| Mixin | Inject / Redirect / Overwrite / ModifyArg(s) / ModifyVariable / ModifyConstant |
| MixinExtras | 0.3.2 / 0.4.1 / 0.5.0 の固定 profile；WrapOperation / WrapMethod / ModifyExpressionValue / ModifyReturnValue / legacy・v2 WrapWithCondition |
| 元 bytecode の注入点照合 | HEAD / RETURN / TAIL / exact INVOKE / exact FIELD / class-owner NEW / 明示的な型付き CONSTANT |
| Slice | 一意な from/to boundary、ordinal、shift；未対応条件は診断 |
| Mapping | Tiny v2.0 と refmap context；ProGuard/SRG は未対応 |
| 他の変換 | Access Widener / class tweaker / AT / Coremod / transformation service 等の存在と解析範囲の記録 |

MixinExtras の profile は `--mixinextras-version` で宣言します。numeric injector order と追加の application stage は分けて保持します。0.3.2 に存在しない WrapMethod / v2 WrapWithCondition は UNKNOWN として残します。bundled version は候補として記録し、実際に loader が選ぶ版を推測しません。wrapper の handler 内の直接的な `Operation.call` は記録しますが、helper への委譲・到達可能性・実行時の呼出し回数は判断しません。直接呼出しが見つからないことを bypass の証明や HIGH 判定の理由にはしません。

NeoForge の `requiredMods` / `behaviorVersion` は旧版に無条件適用しません。`--metadata-profile fml-12.0` は [固定 FML snapshot](https://github.com/neoforged/FancyModLoader/tree/bcbdb3520249deba18d92de2fcbdcee550c75233) とその Mixin 依存 `0.17.1+mixin.0.8.7` を明示的に宣言する profile です。この snapshot の numeric behaviorVersion は 0.17.1 を許容し、既定 compatibility は 17001 です。Maven qualifier・独自の loader 依存版は未検証にします。これは Sponge Mixin の版 0.8.7 と異なる番号です。requiredMods の presence は入力候補と宣言した minecraft / neoforge から評価し、実際の依存選択は再現しません。古い NeoForge で `META-INF/mods.toml` を使う場合は `--loader neoforge` を指定してください。

未対応の独自 selector、MixinExtras expression / local capture、method merge / intrinsic、Overwrite aliases、動的登録は diagnostic に残します。非自明な constructor / static initializer は未解析の merge として coverage に記録します。plugin の getMixins / shouldApplyMixin / preApply / postApply は実行しません。ローダーの依存解決・JAR 選択も完全には再現しません。登録情報のない任意 transformer や、別メソッドを介した意味的干渉は検出できません。

## 終了コードと制限

- `0`：解析完了。finding の有無や互換性を保証するものではありません。
- `1`：明示した `--fail-on` の閾値以上の、抑制されていない finding がある。
- `2`：入力・解析・出力エラー、部分失敗。`--strict` は diagnostic がある場合も 2 にします。2 は 1 に優先します。

入力 Mod の class loading・初期化・plugin 実行はありません。ZIP entry の CRC、重複、unsafe path、symlink、展開量等をチェックします。既定の上限は archive 128 MiB、entry 16 MiB、総展開量 256 MiB、深さ 4、entry 50,000、directory ごとの JAR 候補 50,000（`--max-entries` と共通）、圧縮比 200、selector ごとの候補 1,000、owner / selector の組合せ 100,000、candidate pairs 100,000、協調的な時間制限 120 秒です。`scan --help` のオプションで調整できます。候補・target・pair 上限による省略は報告します。参照継承の探索は 256 levels / 4,096 visits、完全性の確認は 256 nodes までで、打ち切りは未検証として報告します。wildcard は regex を使わず、directory 列挙、metadata 登録、mapping records、命令・slice・shift の走査中にも時間予算を確認します。directory 候補の保持数も制限し、省略時は partial / 終了コード 2 です。descriptor は反復で読み、JVM の array / 引数 slot 上限を確認します。時間制限は Java 内の処理単位で確認するもので、OS の sandbox や強制 CPU 制限ではありません。

JSON payload の UTF-8 展開量は既定 64 MiB（`--max-report-mib`）までです。HTML は escape 後の payload、text も全 inventory の JSON payload を検査します。超過時や stdout 書込み失敗は明示エラーと終了コード 2 を返し、既存の出力ファイルを保持します。evidence の長い文字列は明示的な preview とし、元の annotation は inventory に残します。

## 自作の例と検証

```sh
./gradlew test fixtureIntegration demo benchmark distZip distTar
# build/demo/report.{txt,json,html} と mods/*.jar を生成
# 長い名前・253finding・空レポートのHTMLも生成（docs/html-verification.md参照）
# build/benchmark/results.csv に 100/1000 JAR の検証結果
```

HTML の DOM テストだけ追加で Node.js 22 以上と pnpm 11.25.0 を使います。

```sh
pnpm install --frozen-lockfile --ignore-scripts
pnpm test:html
pnpm test:schema
```

CI は Windows／macOS／Linux で Java テストと CLI smoke test、JSON Schema / HTML DOM テスト、自作 fixture の Mixin runtime テストを実行し、配布物を artifact として保存します。`fixtureIntegration` は外部入力を受け付けず、自作クラスだけを変換・実行します。テスト用 Mixin / MixinExtras は CLI 配布物に含めません。合成 annotation 形状のテスト、自作クラスの runtime テスト、Minecraft の実動作確認はそれぞれ別の検証です。

実 Mod の固定 corpus は通常のテスト・CI ではダウンロードしません。Python 3 と curl を用意し、任意で次を実行できます。

```sh
./gradlew installDist
python3 tools/verify-real-mods.py --download
# 2回目以降は download オプションなしで検証済み cache を利用
```

版・入手元・SHA-512 / SHA-256 / SHA-1 は [manifest](docs/real-mods-manifest.json)、固定 corpus の検証結果と限界は [検証ガイド](docs/validation.md) に記録しています。[判定根拠の評価](docs/evidence-evaluation.md)では、自作runtime対照14ケースと静的判定の対応、および固定実Modの宣言照合を記録しています。[実Minecraftの追加対照](docs/minecraft-runtime-validation.md)では、固定2 Modの専用server 4構成でHIGH候補3件の適用・スキップ・非選択を確認しました。Minecraft上のprecision / recallとModpack全体の互換性は未確定です。通常の解析・静的corpus検証では取得したMod JARとpluginを実行しません。承認済みの追加対照だけが別環境で実Minecraftと固定Modを実行します。第三者JARはcommitしません。通常・長い名前・253件・空レポートは実Chromeで目視とキーボード操作を確認済みです（[観測記録](docs/html-verification.md)）。

[初期設計](https://github.com/rs0325/mixinscope/issues/1) · [開発 Issue](https://github.com/rs0325/mixinscope/issues) · [Report schema](docs/report.schema.json)

MIT License。Minecraft または各 Mod loader の公式プロジェクトではありません。
