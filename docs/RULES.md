# RULES.md — YouTube Ext App

## Coding Standards
- Kotlin with explicit types on public functions
- Compose for all UI; AndroidView only for WebView
- Single Activity architecture
- No hardcoded strings in UI — use `strings.xml`
- All JS scripts in `assets/js/` folder, loaded at runtime

## Naming
- Files: PascalCase for classes, camelCase for functions
- Packages: lowercase dot-separated
- Composables: PascalCase, prefixed with nothing
- State: `by remember { mutableStateOf(...) }` pattern

## Testing
- Unit test SponsorBlock API parsing
- Unit test ad-block URL matching
- No UI tests needed for V1

## Never Do
- Never store user credentials
- Never intercept Google login forms
- Never load remote scripts from untrusted URLs
- Never use `evaluateJavascript` with unsanitized user input
- Never disable SSL verification
- Never use deprecated WebView APIs
