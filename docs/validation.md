# 検証方針と対応 profile

## 検証の層

1. 自作 class/JAR の回帰テスト：metadata、annotation、正規化、リスク、coverage、終了コード。
2. 自作 reference bytecode：同じ呼出し先の異なる命令位置、継承、mapping の型 descriptor、欠損の区別。
3. CLI と配布物：各 OS の生成 launcher を実際に起動し、JSON が parse できることを確認。
4. HTML：DOM スクリプトをネットワークなしで実行し、件数・ID・フィルタ・逆引き・escape を検証。実際のブラウザのレイアウトとキーボード操作は別途目視確認する。
5. 実 Modpack：ローカルで版・hash を固定して任意実施。ゲームを使う動作確認は静的解析から独立させる。
6. 自作クラスの Mixin runtime 対照：実際の annotation でコンパイルしたクラスを別 JVM で変換・実行し、静的な判定の限界と対応付ける。

合成 fixture は意図した annotation/class-file 形状の読み取りを検証するものです。全 injector の handler は実際の Mixin runtime に適合するという保証をしません。static initializer に例外を持つ class も含み、解析ではそれが実行されないことを確認します。

## 固定した参考 profile

| Loader | Game | Profile の根拠 | 検証内容 |
|---|---|---|---|
| Fabric 0.16.14 | 1.21.1 | [公式 gradle.properties](https://github.com/FabricMC/fabric-loader/blob/0.16.14/gradle.properties)：sponge-mixin 0.15.5+mixin.0.8.7、MixinExtras 0.4.1 | schema 1 / nested / sides / 注入 metadata の合成 fixture |
| Forge 47.3.0 | 1.20.1 | mods.toml / MixinConfigs manifest adapter | TOML と multi-mod artifact の合成 fixture；ゲーム起動未検証 |
| NeoForge 21.1.200 | 1.21.1 | neoforge.mods.toml adapter | 基本登録の合成 fixture；新 FML 属性を旧 profile に適用しない |
| Quilt 0.26.3 | 1.21.1 | [固定 schema commit](https://github.com/QuiltMC/quilt-json-schemas/tree/c29d9b835c115cd195b1b479910e41f7e44c989d) | schema 1 / dedicated_server / mixin / access_widener の合成 fixture |
| FML 12.0 固定 snapshot | 宣言を利用者が指定 | [MixinFacade](https://github.com/neoforged/FancyModLoader/blob/bcbdb3520249deba18d92de2fcbdcee550c75233/loader/src/main/java/net/neoforged/fml/loading/mixin/MixinFacade.java) と [gradle.properties](https://github.com/neoforged/FancyModLoader/blob/bcbdb3520249deba18d92de2fcbdcee550c75233/gradle.properties) | requiredMods、numeric behaviorVersion 0.17.1、既定 compatibility 17001；qualifier / custom dependencies / インストールされた loader は未検証 |

Mixin defaults の参考は [Sponge Mixin 0.8.7](https://github.com/SpongePowered/Mixin/tree/4053421aa10aaac6127d969028a29c94fe3054f6)。priority と injector order を別々に保持する。未指定の Mixin 版ではこの profile を仮定として報告する。未知の指定版の defaults の完全一致を保証しない。

MixinExtras profile は [0.3.2](https://github.com/LlamaLad7/MixinExtras/tree/f73ce6f262c06a8ef686ca5517eb3020d59749bb)、[0.4.1](https://github.com/LlamaLad7/MixinExtras/tree/ca7ec9887cadf5d4e0c6ee62fd29291e9a077d06)、[0.5.0](https://github.com/LlamaLad7/MixinExtras/tree/73e5b74a55e7de77b084c5a4230eac0c5937c547) に固定する。WrapOperation / WrapMethod / v2 WrapWithCondition の追加 stage と、通常の numeric order を混同しない。0.3.2 には WrapMethod / v2 WrapWithCondition がない。bundled version の候補は runtime 選択を証明しない。

## 自作クラスの runtime 対照

`./gradlew fixtureIntegration` は Fabric sponge-mixin `0.15.5+mixin.0.8.7` と MixinExtras `0.4.1` を test-only classpath で使う。CLI 配布物にこれらの runtime と独自 Mixin service は入らない。fixture harness は引数を拒否し、リポジトリで作成したクラスだけを対象にする。Minecraft とダウンロードした Mod / plugin は起動しない。

| 自作のケース | compute(3) の対照 | 静的解析で期待する結果 |
|---|---|---|
| 普通の non-cancellable Inject 2 個 | 元の結果 6、callback 2 回、元処理 1 回 | MS001 / LOW；HIGH なし |
| WrapOperation 2 個 | 6 + 1 + 2 = 9、元処理 1 回 | MS001 / MS007 / LOW |
| WrapMethod 2 個 | 6 + 1 + 2 = 9、元処理 1 回 | MS001 / MS007 / LOW |
| original を直接呼ばない wrapper | 99 または 101、元処理 0 回 | 直接呼出し不在の記録；その事実だけでは HIGH にしない |
| helper 経由で original を呼ぶ wrapper | 7、元処理 1 回 | 直接呼出し不在は bypass の証明にならない |
| Overwrite 2 個 | 11 または 22、元処理 0 回 | MS002 / HIGH；実行失敗を断定しない |
| 同じ操作への require=1 Redirect 2 個 | 実際の InjectionError を期待して検証 | MS003 / HIGH、参照命令一致の confidence HIGH |
| index=0 ModifyArg 2 個 | 順序によって 14 または 16、元処理 1 回 | MS006 / MEDIUM；明示 index と命令の一致 |
| constructor / static initializer の field merge | 元の seed=3、Mixin の instance seed=7 / static seed=5 | MIXIN_INITIALIZER_UNANALYZED；架空の injector finding を生成しない |
| 親の compute を継承する子への require=0 Inject | 結果6、callback0回 | 親の宣言は参考情報、子の target は未解決 |
| 子が compute を override、親には overload も存在 | 子の結果9、callback1回 | 対象候補は子の宣言だけ。親 overload と集約しない |
| 継承だけの子への require=1 Inject | InvalidInjectionException：target不足 | TARGET_INHERITED_NOT_LOCAL、親の命令を検証済みにしない |
| 継承だけの子への Overwrite | InvalidMixinException：target不足 | 親のmethod bodyへのOverwriteとして集約しない |
| 対象と異なる owner を method selector に指定 | InvalidInjectionException：owner非対応 | SELECTOR_UNSUPPORTED、他クラスへの変更を生成しない |

`build/runtime-fixture/results.json` に観測結果を保存する。順序を保証するテストではなく、許容する順序における対照である。`CompiledFixtureTest` は同じ javac 出力を JAR にして、ロードせずに annotation / handler descriptor / direct-call syntax / 元 bytecode を解析する。runtime 対照の範囲は 0.4.1 と自作クラスであり、他の版・Minecraft の loader 環境へ一般化しない。

## 実 Mod の任意検証

固定 corpus は [real-mods-manifest.json](real-mods-manifest.json) に版 ID、公式配布 URL、size、配布元 SHA-512 / SHA-1、計算した SHA-256 を記録する。Python 3 と curl で `python3 tools/verify-real-mods.py --download` を実行すると、TLS を検証して取得し、全 hash を確認してから静的 scan する。cache は `build/real-mods/cache`、レポートと summary は `build/real-mods` に保存する。次回は `--download` なしで全 cache を再検証できる。通常の CI はこのダウンロードを実行しない。

2026-10-10 に実行した固定 corpus の summary は [real-mods-results.json](real-mods-results.json) に保存する。Fabric の Lithium / Sodium / ModernFix、Forge の ModernFix、NeoForge の FerriteCore、Quilt の QSL を含む。Lithium は 0.13.0 → 0.13.1 の baseline 差分も確認する。Quilt QSL は root 1 JAR と登録 nested 87 JAR を読み取る。全ケースが exit 0 / partial=false でも、plugin・nested 選択・未対応 selector 等の diagnostic は残る。これは静的読み取りの検証であり、互換性の確認ではない。

FerriteCore Fabric `W4lg2p7U` の primary file は配布元でも `-dev.jar` で、named と intermediary の宣言が混在する。単独の `namespace=unknown` ケースに分け、他の Fabric pack の target identity と混ぜない。ruleset 3 では、宣言 owner と default refmap の qualified owner が異なる注入先も未対応として保持する。この dev corpus の finding は5から4へ減ったが、namespace / runtime context を確認していないため互換性の改善や実際の競合解消とは判定しない。NeoForge FerriteCore の `${refmap_target}refmap.json` は実ファイルがないため REFMAP_MISSING を保持する。Forge / Quilt の実 Mixin・MixinExtras runtime 版は unknown のままにし、bundled 候補を選択済みと表示しない。

この検証ではゲーム JAR・mappings を取得せず、Minecraft の起動も行っていない。実際のクラッシュに関する false positives / false negatives の評価は、自作 runtime 対照と分けて未完了として扱う。

- 自分で取得した JAR を workspace 外の専用ディレクトリに保存する。第三者 JAR を本リポジトリに commit しない。
- game / loader / Mixin / Mod / mappings の版、SHA-256、入手元 URL、physical side、設定を manifest に記録する。
- 最初に JAR-only report を保存し、次に同じ pack を対応 namespace のゲーム JAR と mapping で解析する。
- 原因が既知の Overwrite / Redirect と、正常に併用できる普通の Inject / MixinExtras を含む pack で、false positives と false negatives を記録する。
- ゲームでの手動確認を行う場合、そのログと結果を静的 findings に対応付ける。ツールの scan コマンドからゲームや Mod を起動しない。

## 性能

`./gradlew benchmark` は生成時間を除いた scan 時間を計測する。100 JAR は 100 target group、1000 JAR は 100 group / 4500 pairs。正しい件数と 30 秒以内を検証し、最大 heap は 512 MiB に設定する。CSV の used_heap は scan 後の観測値で、ピークや GC 後の live memory ではない。密集 target の pair 打ち切りは回帰テストで検証する。

実 Mod は class/resource 数が大きく、合成 fixture の速度とメモリ使用を一般化しない。archive limit は大きな pack に合わせて利用者が調整する。

`build/benchmark/environment.json` に OS・CPU architecture・利用可能 processor 数・Java 版・最大 heap と時間予算を保存し、CSV と一緒に比較する。

## リリース前の目視確認

`./gradlew demo` 後に `build/demo/report.html` をブラウザで開き、狭い画面・キーボード Tab 操作・検索・フィルタ・target graph・suppressed findings・長い Mod 名を確認する。DOM 自動テストだけでは外観確認の完了としない。具体的な手順と状態は[html-verification.md](html-verification.md)に記録する。

## 極端な入力と出力 budget

大量の wildcard と長い descriptor は regex の backtracking / stack に依存せず処理する。owner / selector の組合せを max-targets で制限し、skippedTargets と raw declarations を保持する。深い / cyclic hierarchy、異なる interface 宣言、class path と internal name の不一致、欠けた platform Object を回帰テストで検証する。

JSON / HTML payload は UTF-8 byte budget を書込み前に確認する。HTML-safe escape による膨張も上限に含める。text は全 JSON payload を事前検証する。超過を終了コード 2 とし、既存 destination の保持を3形式で確認する。巨大な evidence は preview へ制限し、元 annotation を inventory に残す。これらは OS の CPU / heap sandbox ではなく、全入力に対するピークメモリの保証はしない。

## 対象クラスと継承の区別

固定 Mixin 0.8.7 の [TargetSelectors.findRootTargets](https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/main/java/org/spongepowered/asm/mixin/injection/selectors/TargetSelectors.java) は target ClassNode の methods を走査する。[MixinApplicatorStandard.findTargetMethod](https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/main/java/org/spongepowered/asm/mixin/transformer/MixinApplicatorStandard.java) の Overwrite 検索も対象クラス内であり、[MemberInfo.attach](https://github.com/SpongePowered/Mixin/blob/4053421aa10aaac6127d969028a29c94fe3054f6/src/main/java/org/spongepowered/asm/mixin/injection/struct/MemberInfo.java) は異なる owner を拒否する。

ruleset 3 では own declarations のみから candidateKeys と命令を検証する。hierarchy は継承だけの宣言を diagnostic / declaringOwner に残す参考情報とし、target key を親へ移さない。別 transformer が target method を事前に merge する可能性は未検証なので、継承だけのケースは UNRESOLVED / UNVERIFIED のままにする。ruleset 2 の finding 消失を解消と呼ばない回帰テストも含む。

## 最終レビューの回帰

[review.md](review.md) に対象範囲・修正結果・残るリリース条件を記録する。ruleset 4 / MS003・MS006・MS008 ruleVersion 2 は、未対応注入点を強い候補一致から除く。JAR-only と reference ありの双方で custom point / args / 不正 descriptor / ordinal / shift / Constant 条件、重複 slice を検証する。

命令走査と Tiny records、directory 列挙、metadata 登録にも協調的な budget を確認する。directory は --max-entries と同じ候補数を上限に、辞書順の先頭を保持し、省略を partial にする。OS の CPU / heap 強制制限ではない。

suppression 全体の検証前に finding を変更しない。未知 field の誤記による広い抑制、欠損 header / coverage / 重複 finding ID の baseline、stdout 書込み失敗を回帰テストで確認する。baseline 検証は比較に使用する構造の検査で、レポートの署名や出所の真正性を確認するものではない。

最終ローカル検証：160 Java tests / failures=0、14 authored runtime cases、8 JSON Schema reports、HTML DOM、installDist / distZip / distTarが成功。ruleset 4 の固定6ケースはすべてexit 0 / partial=false。Fabricのfindingは270→269、Lithium更新差分はnew=3 / unchanged=266 / unverifiedDisappearance=3。未対応注入点を候補一致から外した差であり、runtime競合の解消とは扱わない。

## 静的判定とruntime観測の対応付け

`./gradlew --no-daemon test fixtureIntegration` の後、`python3 tools/evaluate-evidence.py` で14ケースのrule / severity / confidence / coverageとruntime観測を照合する。固定実Modのキャッシュと6レポートがある場合は `python3 tools/evaluate-evidence.py --real` でJDKのjavapによる宣言照合を追加できる。ダウンロードとMod実行は行わない。詳細・結果・評価できない指標は[evidence-evaluation.md](evidence-evaluation.md)、[evidence-results.json](evidence-results.json)。
