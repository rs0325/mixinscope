# 実Minecraftでの静的候補と適用結果の照合（#19）

2026-10-11に、専用Fabricサーバーで固定2 Modの4構成を実行した。3件のMod間HIGH候補の宣言と適用結果を照合し、同じNBT・chunk操作と保存・終了を確認した。BiomeのOverwrite競合では片方のメソッドがスキップされたが、起動は成功した。HIGHはクラッシュの二値予測ではない。

既存の[静的宣言照合・自作runtime対照](evidence-evaluation.md)とは別の実測であり、結果とhashは[minecraft-runtime-results.json](minecraft-runtime-results.json)に保存する。各構成1回の限定的な検証で、Modpack全体の互換性、意味的干渉、precision / recallは確定していない。

## 固定した環境

ユーザーの環境は公式ランチャーのバニラのみだったため、Minecraft EULAへの同意と取得・実行の承認を受け、専用worktreeの`build/minecraft-runtime/`に新規のlocalhost専用環境を作った。既存ランチャー・ワールド・認証情報は使っていない。

| 項目 | 実測した版・範囲 |
|---|---|
| Minecraft Java Edition | 1.21.1、専用server |
| Fabric Loader / installer | 0.16.14 / 1.0.3 |
| Mixin | 0.8.7、`net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7` |
| MixinExtras | 0.4.1（起動ログで初期化を確認） |
| Java / OS | Temurin 21.0.9+10-LTS / macOS 15.7.7 arm64 |
| A: Lithium | mc1.21.1-0.13.1、version ID `9x0igjLz`、runtime表示0.13.1 |
| B: ModernFix | 5.19.5+mc1.21.1、version ID `qO3cN8gB` |
| Mixin設定 | 両Modとも生成された既定設定、override 0件 |
| 起動 | `127.0.0.1:25571`、画面なし、1構成ずつ、heap最大2 GiB |
| 対照 | Fabricだけ、Aだけ、Bだけ、A+B。構成ごとに新規world |
| CLI | 0.1.0-alpha.1、commit `ab82a531cfff37cbb3a91f4bec53fe54b5897e2b`、schema 1.1、ruleset 4 |

Modのサイズ・SHA-1 / SHA-256 / SHA-512は[固定manifest](real-mods-manifest.json)と一致した。Minecraft serverのサイズ51,627,615 bytesとSHA-1 `59353fb40c36d304f2035d51e7d6e6baa98dc05c`はMojangの公式version metadataと照合した。launcher、Minecraft、各ログ・設定・変換後対象classのSHA-256も結果JSONに記録する。runtimeの版はJAR内の候補から推定せず、実際の起動ログで確認した。

## 評価する命題と根拠

| 区分 | 判定する内容 | 今回使った根拠 |
|---|---|---|
| 静的規則条件 | 同じowner / name / descriptorへのOverwriteやcancellable HEAD Injectの宣言があるか | 固定JARのannotation・refmapと対象signature |
| 適用 | 対象Mixin・handlerが選択・適用され、最終メソッドに残ったか | verboseログ、変換後classの`MixinMerged`、handler呼出しとキャンセル分岐、pluginと既定設定 |
| 適用失敗・スキップ | 同時変更がエラーやメソッドのスキップを起こしたか | 対象を特定したMixin警告・例外。設定による非選択とは分ける |
| 操作結果 | 同じNBT複製・chunk操作・保存が同じ観測結果になったか | 各構成のコマンド応答、NBT値、成功marker、保存・終了ログとprocess exit code |

起動成功だけで適用を判断しない。ログのMixin適用開始だけでは最終メソッドの採用を判断しない。classにhandlerが存在することと、対象メソッドからそのhandlerが呼ばれることも区別した。温度計算やdead-server分岐の実行、handler呼出し回数、全ゲーム操作の互換性は今回の根拠では判定できない。

## 対象選定と静的解析

実行前にA+Bだけを`--side server`で解析し、そのMod間HIGH全件を対象に固定した。Sodiumを含む以前のBOTHレポートは流用していない。結果は175 findings（Mod間26件：HIGH 3 / MEDIUM 3 / LOW 20、同一artifact内149件）。615 modificationsはすべて適用条件が未確定で、Mod間HIGHのconfidenceもLOW。`coverage.partial=false`、failed artifacts / skipped pairs / skipped targetsは0だが、未対応処理がないという意味ではない。diagnosticは454件、ambiguous targetsは11件。

対象keyは各行共通でgameVersion=`1.21.1`、loaderProfile=`fabric:0.16.14`、namespace=`intermediary`。以下のIDは今回生成したJSONと一致する。

| finding ID | rule / 対象 | A単独 | B単独 | A+Bでの最終状態 |
|---|---|---|---|---|
| `926b470667b34d173d6603dbb95b5308f32e959663f8171209f069fc7392977a` | MS002 / `net/minecraft/class_1959.method_21740(Lnet/minecraft/class_2338;)F` | AのOverwrite採用 | BのOverwrite採用 | AのOverwrite採用。Bの同メソッドは競合警告でスキップ |
| `215e68080e020bc60185922c3d2e110986f99d59af3c9e28f9b3f18010887b3c` | MS004 / `net/minecraft/class_2487.method_10553()Lnet/minecraft/class_2487;` | AのOverwrite採用 | BのHEAD Inject適用 | A由来のcopyにBの`copyEfficient`呼出し・キャンセル分岐が存在 |
| `f4f8429bbaeb23129f6b7e54b1ba0d70aa0f15d299f40bab42fd1bea649b6ae1` | MS004 / `net/minecraft/class_3215.method_12121(IILnet/minecraft/class_2806;Z)Lnet/minecraft/class_2791;` | AのOverwrite採用 | Bの対象機能は既定false | AのOverwrite採用。Bはplugin・既定設定により非選択 |

Fabricだけの構成にはA / BのMixinがない。Biomeの温度計算の行動差は未確認。NBT・chunk操作の観測値には差がなかったが、それぞれの対象メソッドの呼出し回数や全分岐の同等性は証明していない。

## 実行結果と根拠位置

各構成で新規worldを作り、同じコマンド列を起動完了後に送った。NBT複製先のItemsは`[{count: 3, Slot: 0b, id: "minecraft:stone"}]`、force loaded chunksは4件で一致した。4構成とも`MS19_NBT_OK`、`Saved the game`、`Stopping server`とexit code 0を確認した。所要時間はダウンロード・起動・操作・終了を含み、性能比較には使わない。

根拠ファイルの位置は`build/minecraft-runtime/`からの相対位置。生ログ・ゲームclassは公開対象に含めず、必要な応答と適用記録だけを結果JSONに抜粋した。

| run | 起動完了 | NBT値 / marker | chunk件数 | 保存 / 停止 | exit |
|---|---|---|---|---|---|
| fabric | `fabric/logs/latest.log:26` | 33 / 34 | 35 | 41 / 43 | 0 |
| lithium | `lithium/logs/latest.log:269` | 282 / 283 | 284 | 290 / 292 | 0 |
| modernfix | `modernfix/logs/latest.log:74` | 82 / 83 | 84 | 90 / 92 | 0 |
| combined | `combined/logs/latest.log:319` | 333 / 334 | 335 | 341 / 343 | 0 |

適用根拠：`combined/logs/latest.log:29,31`にNBTの両Mixin、153–155にBiomeの両MixinとOverwriteスキップ、244にLithiumのchunk Mixinを記録。`combined/class_1959.javap.txt:1968`で温度メソッドの由来がA、`combined/class_2487.javap.txt:3072`でcopyからBのhandler呼出し、3110 / 3188でcopyとhandlerの由来を確認。`combined/class_3215.javap.txt:3535`でchunkメソッドの由来がA。B単独の`modernfix/class_1959.javap.txt:1845`では温度メソッドの由来がBだった。

Bのchunk機能は`modernfix/config/modernfix-mixins.properties:32`と`combined/config/modernfix-mixins.properties:32`で`mixin.feature.stalled_chunk_load_detection=false`。固定JARの`ModernFixMixinPlugin.shouldApplyMixin`は無効optionを拒否する（ローカルの`ModernFixMixinPlugin.javap.txt:873–903`）。ログから対象Mixinが見つからないという事実だけで非選択と断定したものではない。

最初のFabric実行は`data merge`の先頭空白による入力ミスがあり、対照から除外した。ログとworldを保全して新規worldで実行し直したため、実起動は合計5回。全構成に`No key layers in MapLike[{}]`が出た。全構成で`generator-settings={}`だったため、flatのlayers不足に伴う設定警告と判断した。その後world作成・コマンド・保存は継続した。Mod競合の適用失敗には数えず、このworld設定での観測に限定する。正しいflat設定で再検証した結果とは扱わない。

## 誤検知・未検出・不明の扱い

3件の静的宣言条件について誤一致は見つからなかった。Biomeは実際のメソッドスキップ、NBTは両方の変更が残る例、chunkは条件付き候補が非選択となる例だった。非選択や操作成功を、そのままfalse positiveに数えない。Mixin適用例外は観測していないが、競合警告は出ている。

追加で`net/minecraft/class_4706.removeIf(Ljava/util/function/Predicate;)Z`の通常メソッドmerge競合を観測した（`combined/logs/latest.log:264–266`）。Aの`collections.chunk_tickets.SortedArraySetMixin`とBの`bugfix.paper_chunk_patches.SortedArraySetMixin`が対象で、B側がスキップされた。このメソッドにはfindingがなく、両側の`METHOD_MERGE_UNSUPPORTED` diagnosticがある。全merge競合を検出する命題では未検出例であり、既存ruleが扱う注釈付き変更の誤解析とは区別する。今回、新たな解析不具合として確定したものはなく、scanner修正や回帰fixtureの追加は行っていない。

評価標本はHIGHを選んだ3候補だけで、残る172 findingsとfindingのないメソッド群に独立した正解判定を行っていない。意味的干渉の有無は3候補とも不明で、client、温度計算、dead-server条件、長時間安定性、他のゲーム・loader版は未検証。precision / recallは未算出とし、declared条件の3件一致をModpack互換性やクラッシュ予測の精度へ変換しない。

## 再現手順

取得・実行には利用者自身のMinecraft EULAへの同意が必要。通常CIではMinecraftや外部Modを起動しない。第三者JAR、Minecraft本体、変換後class、javap全文、生ログはリポジトリへ追加しない。

1. Java 21と公開版MixinScope 0.1.0-alpha.1を用意する。配布ZIPのSHA-256は`ff1fa0f0dd6edd9c88e9d11f2d2403cba889fb768847789a80120742d654e9de`。
2. [固定Fabric launcher](https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.14/1.0.3/server/jar)を取得する。SHA-256は結果JSONの`launcherSha256`と照合する。
3. 結果JSONの`environment.mods`にある固定URLから2 Modを取得し、サイズ・hashを照合する。JARのfabric metadataに別Modの必須依存はなく、今回の起動時にも依存解決エラーはなかった。
4. `fabric` / `lithium` / `modernfix` / `combined`の4つの空ディレクトリを作り、それぞれにlauncherを置く。modsには順に0個 / A / B / A+Bだけを入れる。各構成に下記の同じserver.propertiesと、同意後の`eula=true`を置く。初回起動でlauncherが公式Minecraftとライブラリを取得する。今回の実行では専用環境内で取得した配布JAR・ライブラリだけを後続構成へコピーし、worldやMod設定はコピーしていない。

```properties
server-ip=127.0.0.1
server-port=25571
online-mode=true
enable-query=false
enable-rcon=false
level-seed=190019
level-name=world
level-type=minecraft:flat
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=1
sync-chunk-writes=true
```

この設定は今回の共通警告も再現する。警告を修正したworld生成や追試は別runとして記録する。

5. combinedのmodsを次のオプションで解析する。partial、diagnostic、finding IDを保存する。入力にはMinecraftやlauncherを含めない。

```sh
mixinscope scan combined/mods --loader fabric --loader-version 0.16.14 \
  --minecraft-version 1.21.1 --input-namespace intermediary \
  --mixin-version 0.8.7 --mixinextras-version 0.4.1 --side server \
  --format json --output static.json
```

6. 各構成のディレクトリで、順番に次を実行する。同じポートを使うため前のサーバーの停止を確認してから次を起動する。

```sh
java -Xms512M -Xmx2G -Dmixin.debug.verbose=true \
  -Dmixin.debug.export=true -Dmixin.debug.export.decompile=false \
  -Dmixin.dumpTargetOnFailure=true -jar fabric-server-launch.jar nogui
```

[Mixinのdebug properties](https://github.com/SpongePowered/Mixin/wiki/Mixin-Java-System-Properties)を使い、適用ログと`.mixin.out`の変換後classを保存する。今回の固定版でも出力を確認した。注入失敗条件を変える`mixin.debug.countInjections`等は指定していない。

7. `Done (`を確認してから、次をconsoleに送る。今回の自動実行では起動待ち180秒、停止待ち60秒を上限にしたが、4構成とも上限内で正常終了した。

```text
gamerule doDaylightCycle false
gamerule doWeatherCycle false
forceload add 0 0 16 16
setblock 0 64 0 minecraft:chest
data merge block 0 64 0 {Items:[{Slot:0b,id:"minecraft:stone",count:3}]}
clone 0 64 0 0 64 0 1 64 0 replace force
data get block 1 64 0 Items
execute if data block 1 64 0 Items[{id:"minecraft:stone",count:3}] run say MS19_NBT_OK
forceload query
save-all flush
stop
```

8. ログ、設定、exit codeを保存し、`javap -v -p .mixin.out/class/net/minecraft/class_2487.class`等で上記3対象の由来・handler呼出しを確認する。pluginの選択理由、対象class未ロード、失敗、根拠不足を区別する。自分のrunのfinding ID・完全な対象key・hashに紐付ける。変換後classにはruntimeのsession ID等が入るため、再実行で同じclass hashになることは要求しない。

参照：[Fabricサーバー導入](https://fabricmc.net/use/server/)、[Minecraft EULA](https://www.minecraft.net/en-us/eula)、[Lithium固定版](https://modrinth.com/mod/lithium/version/9x0igjLz)、[ModernFix固定版](https://modrinth.com/mod/modernfix/version/qO3cN8gB)。
