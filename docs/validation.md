# 検証方針と対応 profile

## 検証の層

1. 自作 class/JAR の回帰テスト：metadata、annotation、正規化、リスク、coverage、終了コード。
2. 自作 reference bytecode：同じ呼出し先の異なる命令位置、継承、mapping の型 descriptor、欠損の区別。
3. CLI と配布物：各 OS の生成 launcher を実際に起動し、JSON が parse できることを確認。
4. HTML：DOM スクリプトをネットワークなしで実行し、件数・ID・フィルタ・逆引き・escape を検証。実際のブラウザのレイアウトとキーボード操作は別途目視確認する。
5. 実 Modpack：ローカルで版・hash を固定して任意実施。ゲームを使う動作確認は静的解析から独立させる。

合成 fixture は意図した annotation/class-file 形状の読み取りを検証するものです。全 injector の handler は実際の Mixin runtime に適合するという保証をしません。static initializer に例外を持つ class も含み、解析ではそれが実行されないことを確認します。

## 固定した参考 profile

| Loader | Game | Profile の根拠 | 検証内容 |
|---|---|---|---|
| Fabric 0.16.14 | 1.21.1 | [公式 gradle.properties](https://github.com/FabricMC/fabric-loader/blob/0.16.14/gradle.properties)：sponge-mixin 0.15.5+mixin.0.8.7、MixinExtras 0.4.1 | schema 1 / nested / sides / 注入 metadata の合成 fixture |
| Forge 47.3.0 | 1.20.1 | mods.toml / MixinConfigs manifest adapter | TOML と multi-mod artifact の合成 fixture；ゲーム起動未検証 |
| NeoForge 21.1.200 | 1.21.1 | neoforge.mods.toml adapter | 基本登録の合成 fixture；新 FML 属性を旧 profile に適用しない |
| Quilt 0.26.3 | 1.21.1 | [固定 schema commit](https://github.com/QuiltMC/quilt-json-schemas/tree/c29d9b835c115cd195b1b479910e41f7e44c989d) | schema 1 / dedicated_server / mixin / access_widener の合成 fixture |
| FML 12.0 | 宣言を利用者が指定 | [MixinFacade](https://github.com/neoforged/FancyModLoader/blob/bcbdb3520249deba18d92de2fcbdcee550c75233/loader/src/main/java/net/neoforged/fml/loading/mixin/MixinFacade.java) | 明示的な新版属性 profile；loader の許容 behaviorVersion 範囲は未検証 |

Mixin defaults の参考は [Sponge Mixin 0.8.7](https://github.com/SpongePowered/Mixin/tree/4053421aa10aaac6127d969028a29c94fe3054f6)。priority と injector order を別々に保持する。未指定の Mixin 版ではこの profile を仮定として報告する。未知の指定版の defaults の完全一致を保証しない。

## 実 Mod の任意検証

- 自分で取得した JAR を workspace 外の専用ディレクトリに保存する。第三者 JAR を本リポジトリに commit しない。
- game / loader / Mixin / Mod / mappings の版、SHA-256、入手元 URL、physical side、設定を manifest に記録する。
- 最初に JAR-only report を保存し、次に同じ pack を対応 namespace のゲーム JAR と mapping で解析する。
- 原因が既知の Overwrite / Redirect と、正常に併用できる普通の Inject / MixinExtras を含む pack で、false positives と false negatives を記録する。
- ゲームでの手動確認を行う場合、そのログと結果を静的 findings に対応付ける。ツールの scan コマンドからゲームや Mod を起動しない。

## 性能

`./gradlew benchmark` は生成時間を除いた scan 時間を計測する。100 JAR は 100 target group、1000 JAR は 100 group / 4500 pairs。正しい件数と 30 秒以内を検証し、最大 heap は 512 MiB に設定する。CSV の used_heap は scan 後の観測値で、ピークや GC 後の live memory ではない。密集 target の pair 打ち切りは回帰テストで検証する。

実 Mod は class/resource 数が大きく、合成 fixture の速度とメモリ使用を一般化しない。archive limit は大きな pack に合わせて利用者が調整する。

## リリース前の目視確認

`./gradlew demo` 後に `build/demo/report.html` をブラウザで開き、狭い画面・キーボード Tab 操作・検索・フィルタ・target graph・suppressed findings・長い Mod 名を確認する。DOM 自動テストだけでは外観確認の完了としない。
