# 判定根拠の評価（2026-10-10）

固定実Mod6レポートの分類を集計し、FabricのMod間HIGH候補全3件をJARの宣言と照合した。自作14ケースは同じjavac成果物の静的解析と実Mixin変換の観測を対応付け、全件が期待するrule / severity / confidence / coverageと一致した。[機械可読結果](evidence-results.json)に対象ID、class hash、宣言、観測を保存する。

これは静的な根拠と制御された自作実行の評価である。この2026-10-10の評価では実Minecraftのクラッシュ・振る舞いの正解データを取得していないため、実Modに対するprecision / recall、false positive / false negative率は未測定。LOWは無害を保証せず、HIGHはクラッシュを意味しない。宣言一致は同時適用の証明ではない。

## 固定実Modの内訳

| ケース | LOW | MEDIUM | HIGH | Mod間 | 同一artifact内 |
|---|---:|---:|---:|---:|---:|
| Fabric baseline 1.21.1 | 234 | 30 | 5 | 45 | 224 |
| Fabric 1.21.1 | 234 | 30 | 5 | 45 | 224 |
| Fabric FerriteCore dev 1.21.1 | 4 | 0 | 0 | 0 | 4 |
| Forge 1.20.1 | 87 | 5 | 0 | 0 | 92 |
| NeoForge 1.21.1 | 5 | 0 | 0 | 0 | 5 |
| Quilt 1.20.1 | 321 | 56 | 0 | 109 | 268 |

全findingのconfidenceはLOW。JAR-onlyなのでゲームのmethod存在・命令一致は未検証で、pluginやnested候補の選択も確定しない。baselineと更新版は大部分が重複するため、合計を独立した検証標本数として扱わない。同一artifact内のfindingも、Mod間の互換性問題の件数ではない。分類はrule / severity / confidence / categoryごとに結果JSONへ記録する。

## FabricのMod間HIGH候補の宣言確認

Lithium 0.13.1とModernFix 5.19.5+mc1.21.1の固定JARをJDK 21の`javap -v -p`で読み、class annotationのowner、handler descriptor、Overwrite / Inject、configへの登録、InjectのHEAD / cancellableとrefmap値を照合した。Mod、Mixin plugin、Minecraftは実行しない。classとartifactのSHA-256、完全なMixin名、対象finding IDは結果JSONに記録する。

| 対象（intermediary） | 規則 | 確認した宣言 |
|---|---|---|
| `net/minecraft/class_1959.method_21740(Lnet/minecraft/class_2338;)F` | MS002 | 両ModのBiomeMixinが同じownerとdescriptorを持つOverwrite |
| `net/minecraft/class_2487.method_10553()Lnet/minecraft/class_2487;` | MS004 | LithiumのOverwriteとModernFixのcancellable HEAD Inject。`copy()Lnet/minecraft/nbt/CompoundTag;`をrefmapで正規化すると一致 |
| `net/minecraft/class_3215.method_12121(IILnet/minecraft/class_2806;Z)Lnet/minecraft/class_2791;` | MS004 | LithiumのOverwriteとModernFixのcancellable HEAD Inject。`getChunk`のrefmap値が同じowner / name / descriptor |

3件とも両側のconfigにpluginがあり、適用はCONDITIONAL。設定や他Modの存在により片方が無効になる可能性を評価していない。選定は現在版FabricのMod間HIGH全件であり、無作為標本ではない。その他のfindingについてjavapによる個別宣言照合は行っていない。正解ラベルは`UNAVAILABLE`を維持する。

## 自作runtime対照で観測した境界

固定profileはFabric Mixin `0.15.5+mixin.0.8.7` / MixinExtras `0.4.1`。対象はリポジトリ内のJavaクラスだけ。

- 通常の非cancellable Injectが2個：MS001 / LOW、callback 2回、元処理1回、結果6。
- WrapOperation / WrapMethodの連鎖：MS001 + MS007 / LOW、元処理1回、結果9。
- 直接Operation.callがないwrapper：bypass例は元処理0回、helper委譲例は元処理1回。直接呼出しの欠如からbypassを断定できず、WRAPPER_CALL_UNVERIFIEDを保持する。
- Overwrite 2個：MS002 / HIGH、変換・呼出しは成功し、元処理0回、結果11または22。HIGHをクラッシュ確率の二値ラベルへ置き換えない。
- 同じ命令へのrequired Redirect 2個：MS003 / HIGH、実際のInjectionErrorを観測。
- 同じ引数へのModifyArg 2個：MS006 / MEDIUM、結果14または16の許容集合で照合。別の実行順を試した検証ではない。
- initializer：追加フィールド値7 / 5、target値3。静的解析は偽のinjectorを生成せず未解析coverageを記録。
- require=0の継承対象：callback 0回。子自身のoverride：callback 1回。require=1の継承Inject、継承Overwrite、異なるownerを指定するselectorは期待する例外を観測し、静的解析は未解決・未対応coverageに残す。単独の失敗をMod間競合findingとして捏造しない。

14ケースは代表的な制御例であり、Minecraftの全注入点・ゲーム版・loader版に一般化しない。静的解析は各Mixinを別の自作ModとしてJAR化し、runtimeは同じclass成果物を1つのfixture configから変換するため、loader依存解決・複数configの選択はこの対応付けの範囲外。

## 再現手順

```sh
./gradlew --no-daemon test fixtureIntegration
python3 tools/evaluate-evidence.py

# opt-in取得と固定6レポートの再生成。すでにキャッシュ済みなら--downloadを省略
./gradlew --no-daemon installDist
python3 tools/verify-real-mods.py --download
python3 tools/evaluate-evidence.py --real
# 必要なら --javap /absolute/path/to/jdk/bin/javap
```

CIは14ケースの対応付けを3 OSで実行し、静的report、runtime観測、比較結果をartifactへ保存する。実Mod照合は手動のopt-in検証。比較ツールは未生成report、profile / contract変更、期待する分類・観測の不一致、JAR hash不一致、確認済みHIGHの標本変更を失敗として扱う。結果は`build/evidence-review/`に書く。公開済み結果は今回の固定corpusによる記録で、第三者JAR・class bytecode・javap全文を再配布しない。

## 実Minecraftの追加対照

#19では別途、固定Lithium / ModernFixの専用server 4構成を実行し、静的HIGH候補3件の適用・スキップ・非選択とNBT・chunk操作を照合した。[手順と観測結果](minecraft-runtime-validation.md)を参照。上記の静的corpus / 自作14ケースの結果や正解ラベルを上書きするものではなく、Minecraftのprecision / recallは引き続き未算出。
