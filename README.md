# En-Ru File Finder

An IntelliJ Platform plugin that instantly switches between the `en` and `ru` language folders for the same file,
using a single shortcut.

## Features

- **en/ru file switching** – open the counterpart of the current file from the opposite language folder.
  Works with a single file (from the editor or Project view) and with multiple files selected in the Project view.
- **Folder switching** – selecting a folder navigates to its counterpart folder in the Project tool window.
- **Missing file creation** – if no counterpart exists, you are asked whether to create it (with the same content),
  including any missing intermediate directories.
- **Caret position and scroll sync** – the caret line and the relative viewport position are preserved when switching.
- **Diff view support** – switching inside a VCS diff view opens the diff for the counterpart file; already-open diff
  tabs are reused and focused instead of being recreated.
- **Git Blame (annotations) mirroring** – the "Annotate with Git Blame" gutter state is copied to the counterpart:
  if blame is shown in the current file/diff it is shown in the other one too, and if it is hidden it is hidden
  there as well.

## Usage

The default shortcut is **Ctrl+Alt+\\** (set in `plugin.xml`). With focus in an editor, a Project view selection,
or a VCS diff view, pressing the shortcut switches to the counterpart `en`/`ru` file.

Only files located under an `en` or `ru` folder are processed; other files produce a notification.

## Requirements

- IntelliJ IDEA 2025.2+ (sinceBuild = 252)
- The plugin depends on the platform `com.intellij.modules.lang` and `com.intellij.modules.vcs` modules.

## Project structure

```
.
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── settings.gradle.kts     Gradle project settings
└── src
    └── main
        ├── kotlin/
        │   └── com/github/huginnandmuninn52/filefinder/SwitchEnRuAction.kt
        └── resources/META-INF/plugin.xml
```

## Building and testing locally

Launch a sandboxed IDE instance with the plugin installed:

```shell
./gradlew runIde
```

This starts IntelliJ IDEA (2025.2.4) with the plugin registered, so you can create a small test project containing
an `en/` and `ru/` folder pair and verify the features:

- single-file switching and caret/scroll preservation,
- multi-file selection and folder navigation,
- missing-file creation,
- diff switching from a VCS diff with tab reuse,
- Git Blame mirroring in the gutter.

To debug, run the `runIde` task with the debugger (ideally from a Run/Debug configuration in IntelliJ) and inspect
the `idea.log` tab for plugin messages.

## Plugin verifier

To check compatibility and API usage against the supported IDE versions, use the verifier:

```shell
./gradlew verifyPlugin
```

The verifier reports incompatible versions, usages of deprecated/scheduled-for-removal APIs, and other issues that block Marketplace publication.
Run it before publishing or bumping the target IDE version.

In CI, the plugin is verified with the
[IntelliJ Platform Plugin Verifier GitHub Action](https://github.com/marketplace/actions/intellij-platform-plugin-verifier)
(see `.github/workflows/compatibility.yml`).

### Standalone `verifier-all.jar check-plugin`

As an alternative to the Gradle task, the same verifier can be run directly from the command line:

```shell
curl -L -o verifier-all.jar \
  "https://github.com/JetBrains/intellij-plugin-verifier/releases/download/1.301/verifier-all.jar"

java -jar verifier-all.jar check-plugin \
  build/distributions/En-Ru-File-Finder-1.4.4.zip \
  ideaIU:2025.2 \
  ideaIU:LATEST-EAP-SNAPSHOT
```

Useful options:

- `-s <severity>` – only report problems at or above the given severity (e.g. `-s ERROR` gates the exit code).
- `--ignore-deprecated`, `--ignore-internal`, `--ignore-experimental`, … – skip whole problem categories.
- `--mute-plugin-problems <id1,id2,...>` and `--external-prefixes <prefix>` – ignore specific plugin problems or
  external class prefixes (mirrors the GitHub Action inputs).
- `-l <DEBUG|INFO|WARN>` – log verbosity.

Both the `verifyPlugin` Gradle task and the GitHub Action wrap this jar; the CLI is the fallback when you want to
verify without a Gradle project or with a specific verifier version.

## Publishing

Release through [JetBrains Marketplace](https://plugins.jetbrains.com) using the `publishPlugin` Gradle task, or
upload the built plugin manually via the Marketplace upload page.