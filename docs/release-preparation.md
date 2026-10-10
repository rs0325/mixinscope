# 0.1.0-alpha.1 公開準備

現在はdraft PRの候補で、GitHub Release・tagは未作成。mainへのマージとプレリリース公開は最終承認後に行う。VERSIONはCLI / JSON / Gradle配布名の唯一の正本。GitHubの公開名は`v0.1.0-alpha.1`、pre-releaseとする。初期backlogのv0.2 / v0.3は機能別の開発マイルストーンであり、現在の製品リリース番号ではない。

## 候補生成と検証

```sh
./gradlew --no-daemon test fixtureIntegration demo installDist distZip distTar
python3 tools/evaluate-evidence.py
python3 tools/prepare-release.py
```

`build/release/0.1.0-alpha.1/`にZIP / TAR、SHA256SUMS、RELEASE-NOTES.md、verification.jsonを生成する。検証ツールは両archiveの内容一致、Unix launcherの実行属性、MITとruntime依存のlicense / notice、依存JAR hashを確認する。検証済みZIPを一時directoryへ展開し、展開版CLIのversionと自作Mod scanのJSON版・MS002を照合する。実Modは実行しない。

ZIPとTARの内容は同じ。JDK/JREは同梱しない。MIT、README、CHANGELOG、THIRD-PARTY、docs、licenses、Windows / Unix launchersとruntime依存を同梱する。テストfixtureとMixin runtimeは含めない。CIは3 OSで配布検証を行い、候補一式をartifactへ保存する。

## 公開時に確認するもの

- 承認対象commitの3 OS CIが成功し、そのcommitに対応した候補を用意する。
- verification.jsonのsourceRevisionを確認し、sourceDirtyがfalseであることを確認する。PR用CIのrevisionはmerge用commitの場合があるため、正式公開用は承認したcommitから再生成する。
- ZIP / TARのSHA-256をSHA256SUMSと照合する。OSごとの完全なarchive hash一致は前提にせず、選択した一組のZIP / TARとchecksumsを公開する。
- [リリースノート](release-notes.md)の既知の限界を保ち、安定版・Minecraft実動作検証済みとして案内しない。
- 承認後にPRをマージし、承認したsourceからtagとGitHub pre-releaseを作成する。公開assetはZIP / TAR / SHA256SUMS。検証記録とsourceRevisionも保持する。

Issueのチェックは個別受入条件の検証を表す。実Minecraft上のfalse positives / false negativesは未測定として残す。Issue・マイルストーンのcloseは公開範囲と残条件を確認して行い、一括closeを前提としない。
