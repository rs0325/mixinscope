# Changelog

## 0.1.0-alpha.1 — 公開準備中

最初のプレリリース候補。Minecraft Mod JARのMixin宣言と潜在的な互換性リスクを、ゲームを起動せずに調査するJava 21 CLI。

- Fabric / Forge / NeoForge / Quiltの登録metadataとnested JARを制限付きで解析。
- 標準Mixinと固定MixinExtras profile、MS001–MS008の根拠・risk・confidenceを報告。
- Tiny v2 / refmapと参照bytecodeの命令候補を照合し、未解決・未対応範囲をcoverageに記録。
- text / JSON / 単一HTML、Modとtargetの逆引き、フィルタ、理由付きsuppression、保守的なbaseline比較。
- JSON schema 1.1 / ruleset 4。CLI・JSON・配布名はVERSIONから統一。
- Java回帰、14自作runtime対照、3 OS CI、固定実Modの静的検証と宣言照合、実Chromeの表示・キーボード検証を実施。

同じメソッドへの変更だけならLOW。HIGHはクラッシュの確定ではない。Mod class / plugin / transformerは実行しない。実Minecraft上の互換性とprecision / recallは未測定。詳細は[利用手順](README.md)、[評価記録](docs/evidence-evaluation.md)。
