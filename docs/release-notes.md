# MixinScope 0.1.0-alpha.1

Minecraft Mod JARを実行せずに読み、Mixinの変更対象と潜在的な互換性リスクを調査するCLIの初回プレリリース候補です。

## 利用開始

Java 21以上を用意し、`mixinscope-0.1.0-alpha.1.zip`または`.tar`を展開してください。Gradle / Node.js / Pythonは通常の解析には不要です。JAR単体でなく、展開した`bin`と`lib`を一緒に保持します。

macOS / Linux:

```sh
./mixinscope-0.1.0-alpha.1/bin/mixinscope --version
./mixinscope-0.1.0-alpha.1/bin/mixinscope scan ./mods --format html --output report.html
```

Windows PowerShell:

```powershell
& .\mixinscope-0.1.0-alpha.1\bin\mixinscope.bat --version
& .\mixinscope-0.1.0-alpha.1\bin\mixinscope.bat scan .\mods --format html --output report.html
```

`report.html`をブラウザで開きます。より具体的なprofileを指定する場合は、同梱READMEの例に従い、実際のloader / Minecraft / Mixin / namespaceを宣言してください。省略した版はunknownとして扱います。

## 含まれる機能

- Fabric / Forge / NeoForge / Quilt metadata、登録されたnested JAR、標準Mixinと固定MixinExtras profileの静的解析。
- MS001–MS008の潜在リスク、根拠・confidence・適用条件・未解析coverageの報告。
- text / JSON / 単一HTML、Mod / targetの逆引きとフィルタ。
- Tiny v2 / refmap、元reference bytecodeの命令候補照合、理由付きsuppression、baseline比較。

report schema 1.1 / ruleset 4。終了コード0は解析完了、`--fail-on`で閾値該当なら1、入力・解析・出力エラーや部分失敗は2です。

## 検証と限界

Java回帰テスト、自作runtime14ケースの静的判定との対応比較、Windows / macOS / Linux CI、固定実Mod6ケースの静的検証を実施。FabricのMod間HIGH全3件はJARの宣言と照合しました。HTMLは通常・長い名前・253件・空reportを実Chromeで確認しました。

同じメソッドへの変更だけならLOWです。HIGHでも変換に成功する場合があり、クラッシュの確定ではありません。pluginや任意transformerを実行せず、loaderの依存選択も完全には再現しません。実Minecraftの互換性、precision / recall、他runtime版・他ブラウザ・screen readerは未検証です。配布物にMinecraft、実Mod、テスト用Mixin runtimeは含みません。

MixinScopeはMIT。同梱ライブラリのlicense / noticeはTHIRD-PARTY.mdとlicenses/にあります。変更内容はCHANGELOG.md、詳細はREADME.mdとdocs/を参照してください。
