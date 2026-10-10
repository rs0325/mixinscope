# Third-party runtime dependencies

MixinScopeのコードはMIT。配布CLIのruntime依存は以下の未改変JARとして同梱する。各license / noticeは[manifest](licenses/manifest.json)から参照できる。JAR内の元license / noticeも保持する。テスト専用Mixin / MixinExtras、JUnit、実Mod corpusはCLI配布物に含めない。

| JAR | License / notice の出典 |
|---|---|
| antlr4-runtime-4.11.1.jar | [公式資料](https://raw.githubusercontent.com/antlr/antlr4/4.11.1/LICENSE.txt) |
| asm-9.11.jar | [公式資料](https://asm.ow2.io/license.html) |
| asm-tree-9.11.jar | [公式資料](https://asm.ow2.io/license.html) |
| checker-qual-3.21.2.jar | 配布JARのMETA-INF（同梱本文） |
| commons-codec-1.19.0.jar | 配布JARのMETA-INF（同梱本文） |
| commons-compress-1.28.0.jar | 配布JARのMETA-INF（同梱本文） |
| commons-io-2.20.0.jar | 配布JARのMETA-INF（同梱本文） |
| commons-lang3-3.18.0.jar | 配布JARのMETA-INF（同梱本文） |
| jackson-annotations-2.20.jar | 配布JARのMETA-INF（同梱本文） |
| jackson-core-2.20.1.jar | 配布JARのMETA-INF（同梱本文） |
| jackson-databind-2.20.1.jar | 配布JARのMETA-INF（同梱本文） |
| picocli-4.7.7.jar | [公式資料](https://raw.githubusercontent.com/remkop/picocli/v4.7.7/LICENSE) |
| tomlj-1.1.1.jar | [公式資料](https://raw.githubusercontent.com/tomlj/tomlj/1.1.1/LICENSE) |

公式タグの本文をコピーし、ASMは公式licenseページの本文を保存した。META-INFの本文は配布JARからそのまま取得した。取得元・JAR SHA-256・同梱ファイルをmanifestへ固定し、配布検証でruntime JARの集合とhashを照合する。依存版を変更する際はmanifestとlicense / noticeを再確認する。
