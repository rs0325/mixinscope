# Changelog

## 0.1.0

現行の静的解析CLIを初回正式版として配布。report schema 1.1 / ruleset 4を維持し、alpha版から判定規則・CLIオプション・保存形式は変更しない。

- VERSIONと配布検証を正式版番号へ対応。
- README・利用手順・公開手順を正式版へ揃え、alpha版の公開準備中という記載を修正。
- 固定Lithium / ModernFixの専用server 4構成で、HIGH候補3件の適用・スキップ・plugin非選択とNBT・chunk操作を照合。
- findingにならない通常メソッドmerge競合、共通world設定警告、未確認の意味的互換性を記録。

正式版は記載した静的解析の範囲で配布する。Modpack全体の互換性・クラッシュ予測・precision / recallを保証しない。詳細は[実測記録](docs/minecraft-runtime-validation.md)。

## 0.1.0-alpha.1 — 2026-10-10

最初のプレリリース。Minecraft Mod JARのMixin宣言と潜在的な互換性リスクを、ゲームを起動せずに調査するJava 21 CLI。

- Fabric / Forge / NeoForge / Quiltの登録metadataとnested JARを制限付きで解析。
- 標準Mixinと固定MixinExtras profile、MS001–MS008の根拠・risk・confidenceを報告。
- Tiny v2 / refmapと参照bytecodeの命令候補を照合し、未解決・未対応範囲をcoverageに記録。
- text / JSON / 単一HTML、Modとtargetの逆引き、フィルタ、理由付きsuppression、保守的なbaseline比較。
- JSON schema 1.1 / ruleset 4。CLI・JSON・配布名はVERSIONから統一。
- Java回帰、14自作runtime対照、3 OS CI、固定実Modの静的検証と宣言照合、実Chromeの表示・キーボード検証を実施。

同じメソッドへの変更だけならLOW。HIGHはクラッシュの確定ではない。Mod class / plugin / transformerは実行しない。実Minecraft上の互換性とprecision / recallは未測定。詳細は[利用手順](README.md)、[評価記録](docs/evidence-evaluation.md)。
