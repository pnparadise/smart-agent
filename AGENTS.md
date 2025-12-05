# Repository Guidelines

## Project Structure & Module Organization
- `lib/` holds app logic; entry point `lib/main.dart` wires navigation and uses shared styling from `lib/theme.dart`.
- Screens live in `lib/screens/` (e.g., `home_page.dart`, `smart_rules.dart`, `tunnel_list.dart`); keep new UI pages here with helper widgets nearby.
- API helpers and data models live in `lib/api.dart` and `lib/models.dart`; extend them rather than scattering networking/model logic.
- Tests sit in `test/` (starting with `widget_test.dart`); mirror `lib/` paths when adding coverage.
- Platform assets/configs live under `android/`; add other platform folders similarly when targeting iOS/web/desktop.

## Build, Test, and Development Commands
- `flutter pub get` – install/update dependencies from `pubspec.yaml`.
- `flutter analyze` – static analysis using `analysis_options.yaml`.
- `flutter test` – run widget/unit tests; add `--coverage` if reporting coverage.
- `flutter run -d <device>` – launch locally on emulator/simulator/physical device.
- `flutter build apk --release` (or `flutter build ios`) – create release artifacts.

## Coding Style & Naming Conventions
- Follow `flutter_lints` defaults (see `analysis_options.yaml`); prefer 2-space indent and trailing commas for multiline args to keep diffs tidy.
- Format with `dart format lib test` before committing.
- File names use `snake_case.dart`; classes and widgets use `PascalCase`; private members use a leading underscore.
- Keep widgets small; extract shared styles to `theme.dart` and shared components to `lib/screens` subfolders or a new `lib/widgets/` if they grow.

## Testing Guidelines
- Add widget/unit tests alongside features in `test/`, mirroring `lib/` paths.
- Use descriptive `testWidgets` names; prefer golden tests for stable UI when possible.
- Ensure new features include happy-path and edge coverage; avoid merging with failing or skipped tests.

## Commit & Pull Request Guidelines
- Use concise, present-tense commit messages; Conventional Commit prefixes (`feat:`, `fix:`, `chore:`) are encouraged.
- For PRs, include: summary of change, testing performed (commands), linked issues/task IDs, and screenshots/recordings for UI-impacting work.
- Keep diffs focused; isolate refactors from feature changes; update docs (`README.md`, `AGENTS.md`) when behavior shifts.

## Security & Configuration Tips
- Do not commit secrets or device-specific configs; rely on environment variables or local `.env` files ignored by VCS.
- Validate that release builds strip debug logging and sensitive data before distributing.
