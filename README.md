# MixinScope

Minecraft Mod の JAR を実行せずに読み、Mixin の適用対象と潜在的な互換性リスクを調査する CLI です。

**開発版です。** 静的な変更箇所の重複と危険度の候補を報告します。実際の Modpack の互換性やクラッシュを確定するものではありません。

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
  --minecraft-version 1.21.1 --mixin-version 0.8.7 \
  --input-namespace intermediary --side client
```

JAR を複数指定することもできます。`--side server` は専用サーバー、`--side both` は物理 client と専用 server を別のシナリオとして比較します。client-only と server-only の変更は同時適用とみなしません。

```sh
build/install/mixinscope/bin/mixinscope scan ./mods --format json --output report.json
build/install/mixinscope/bin/mixinscope scan ./mods --format html --output report.html
```

HTML はネットワーク通信・外部 CDN なしで閲覧できます。検索、危険度・confidence・Mod・対象のフィルタと、対象から Mixin handler への逆引きがあります。JSON は完全な inventory と findings を保持し、表示フィルタの結果を `visibleFindingIds` に格納します。

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

同じメソッド内に同じ呼出し先が複数あっても、ordinal・slice が異なる命令を指す場合は同一命令の衝突と区別します。参照 JAR は他 Mod の変換前の状態なので、Mixin の全適用パスや実行結果を再現したという意味ではありません。

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

抑制は理由必須です。finding を削除せず、`suppressed` と理由を記録します。期限当日まで有効で、既定の評価日は UTC の現在日です。baseline は new / unchanged / resolved を返し、解析失敗・未解決・不明な条件・環境差などで消えた finding は `unverifiedDisappearance` とします。`resolved` も静的な finding の消失であり、互換性の保証ではありません。

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

## 対応範囲

| 項目 | 現在の範囲 |
|---|---|
| 実行 Java | Java 21 以上 |
| 入力 class file | major 45–72（ASM 9.11、preview の実行互換性は未検証） |
| Loader discovery | Fabric schema 1、Quilt schema 1、Forge/NeoForge TOML と manifest、登録された nested JAR |
| Mixin | Inject / Redirect / Overwrite / ModifyArg(s) / ModifyVariable / ModifyConstant |
| MixinExtras | WrapOperation / WrapMethod / ModifyExpressionValue / ModifyReturnValue / WrapWithCondition の宣言抽出 |
| 元 bytecode の注入点照合 | HEAD / RETURN / TAIL / exact INVOKE / exact FIELD / class-owner NEW / 明示的な型付き CONSTANT |
| Slice | 一意な from/to boundary、ordinal、shift；未対応条件は診断 |
| Mapping | Tiny v2.0 と refmap context；ProGuard/SRG は未対応 |
| 他の変換 | Access Widener / class tweaker / AT / Coremod / transformation service 等の存在と解析範囲の記録 |

NeoForge の `requiredMods` / `behaviorVersion` は旧版に無条件適用しません。`--metadata-profile fml-12.0` で明示的に新版の requiredMods 判定を選択できます。behaviorVersion の loader 内の許容範囲は未検証として残します。古い NeoForge で `META-INF/mods.toml` を使う場合は `--loader neoforge` を指定してください。

未対応の独自 selector、MixinExtras expression / local capture、method merge / intrinsic、動的登録は diagnostic に残します。plugin の getMixins / shouldApplyMixin / preApply / postApply は実行しません。ローダーの依存解決・JAR 選択も完全には再現しません。登録情報のない任意 transformer や、別メソッドを介した意味的干渉は検出できません。

## 終了コードと制限

- `0`：解析完了。finding の有無や互換性を保証するものではありません。
- `1`：明示した `--fail-on` の閾値以上の、抑制されていない finding がある。
- `2`：入力・解析・出力エラー、部分失敗。`--strict` は diagnostic がある場合も 2 にします。2 は 1 に優先します。

入力 Mod の class loading・初期化・plugin 実行はありません。ZIP entry の CRC、重複、unsafe path、symlink、展開量等をチェックします。既定の上限は archive 128 MiB、entry 16 MiB、総展開量 256 MiB、深さ 4、entry 50,000、圧縮比 200、candidate pairs 100,000、協調的な時間制限 120 秒です。`scan --help` のオプションで調整できます。pair 上限による省略は報告します。時間制限は Java 内の処理単位で確認するもので、OS の sandbox や強制 CPU 制限ではありません。

## 自作の例と検証

```sh
./gradlew test demo benchmark distZip distTar
# build/demo/report.{txt,json,html} と mods/*.jar を生成
# build/benchmark/results.csv に 100/1000 JAR の検証結果
```

HTML の DOM テストだけ追加で Node.js 22 以上と pnpm 11.25.0 を使います。

```sh
pnpm install --frozen-lockfile --ignore-scripts
pnpm test:html
```

CI は Windows／macOS／Linux で Java テストと CLI smoke test、HTML DOM テストを実行し、配布物を artifact として保存します。合成 fixture のテストは実際の Minecraft/Mixin 実行を証明するものではありません。外観の目視確認と実 Mod の検証手順は [検証ガイド](docs/validation.md) に記載しています。

[初期設計](https://github.com/rs0325/mixinscope/issues/1) · [開発 Issue](https://github.com/rs0325/mixinscope/issues) · [Report schema](docs/report.schema.json)

MIT License。Minecraft または各 Mod loader の公式プロジェクトではありません。
