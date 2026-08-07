# PhotoRate2

Kotlin Multiplatform (Android + iOS) app that scans every image in the device gallery for hands,
then displays those with a valid thumb-rating score (1–5).

## Architecture

Clean Architecture with MVVM. Layers from outer to inner:

- **UI** — Platform-specific (Compose on Android, SwiftUI on iOS). No business logic.
- **ViewModel** — Exposes `StateFlow<UiState>`. Sealed classes/interfaces for UI states and intents.
- **Use Case** — Single-responsibility orchestrators (e.g. `PopulateGalleryUseCase`). A repository may be injected directly into a viewmodel if a potential UseCase is a dumb wrapper over one of repository methods.
- **Repository** — Interfaces in `commonMain`, `Default*` implementations using SQLDelight.
- **DataSource** — Platform gallery APIs (MediaStore / PHAsset), Ktor, MediaPipe ML inference.

Dependency injection via Koin with `expect`/`actual` for `platformModule`. No service locators or
manual wiring.

# Development rules

- **No comments**: only add comments where it's impossible to understand the flow or decisions that
  led to this code otherwise. Use comments VERY sparingly.
- **SOLID**: single responsibility per class, dependency inversion at repository boundaries,
  open/closed for new features via interfaces.
- **Code style**: no magic numbers. No comments unless the why is non-obvious.
- **Module structure**: multi module, split between shared domain+data and separate per-platform UI
  layers.
- **Verification**: always build Android (`./gradlew :app:assembleDebug`) with no errors. Optionally
  build iOS and run tests if the change touches shared logic or platform-specific code. When using
  Ktlint, run `ktlintFormat` first to auto-fix easily fixable problems.
- **Implementation scope**: if the actual implementation diverges significantly from an initial
  plan, confirm with the user before proceeding.
- **Take slow Internet into account**: adjust large download timeouts accordingly, try proceeding with some other work in parallel while waiting.
- **Mark important progress/discoveries**: whenever you reach a goalpost, write about it in the chat with a "PROGRESS REPORT" headline; describe what went according to the initial plan and what didn't.

# Sandbox

You're running inside a Nono
sandbox (https://nono.sh/docs/cli/getting_started/quickstart#checking-policy-access-nono-why). If
you encounter a permissions/sandbox related error, investigate it and surface to the user.

### Check if a sensitive path would be blocked

nono why --path ~/.ssh/id_rsa --op read
Output: DENIED - sensitive_path (SSH keys and config)

### Check with capability context

nono why --path ./src --op write --allow .
Output: ALLOWED - Granted by: --allow .

### JSON output for AI agents

nono why --json --path ~/.aws --op read
{"status":"denied","reason":"sensitive_path","category":"AWS credentials",...}

### Check an Tool Sandbox  command/argv policy denial

nono why --profile gh --command gh -- issue comment 1052
Output: DENIED - agents may read issues but not comment on them

# context7

Use the `ctx7` CLI to fetch current documentation whenever the user asks about a library, framework,
SDK, API, CLI tool, or cloud service — even well-known ones like React, Next.js, Prisma, Express,
Tailwind, Django, or Spring Boot. This includes API syntax, configuration, version migration,
library-specific debugging, setup instructions, and CLI tool usage. Use even when you think you know
the answer — your training data may not reflect recent changes. Prefer this over web search for
library docs.

Do not use for: refactoring, writing scripts from scratch, debugging business logic, code review, or
general programming concepts.

## Steps

1. Resolve library: `npx ctx7@latest library <name> "<what to look up>"` — use the official library
   name with proper punctuation (e.g., "Next.js" not "nextjs", "Customer.io" not "customerio", "
   Three.js" not "threejs")
2. Pick the best match (ID format: `/org/project`) by: exact name match, description relevance, code
   snippet count, source reputation (High/Medium preferred), and benchmark score (higher is better).
   If results don't look right, try alternate names or queries (e.g., "next.js" not "nextjs", or
   rephrase the question)
3. Fetch docs: `npx ctx7@latest docs <libraryId> "<what to look up>"` — run a separate `docs`
   command per distinct concept if the question spans multiple topics, unless it's about how they
   interact
4. Answer using the fetched documentation

You MUST call `library` first to get a valid ID unless the user provides one directly in
`/org/project` format. Be specific about what to look up in the library's documentation — specific
and detailed queries return better results than vague single words, but keep each query to a single
concept unless the question is about how concepts interact; combined multi-topic queries dilute
ranking and return shallow results for each topic. Do not run more than 3 commands per question. Do
not include sensitive information (API keys, passwords, credentials) in queries.

For version-specific docs, use `/org/project/version` from the `library` output (e.g.,
`/vercel/next.js/v14.3.0`).

If a command fails with a quota error, inform the user and suggest `npx ctx7@latest login` or
setting `CONTEXT7_API_KEY` env var for higher limits. Do not silently fall back to training data.
