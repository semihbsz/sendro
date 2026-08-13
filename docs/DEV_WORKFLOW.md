# Development Workflow

Day-to-day development happens entirely on Windows. The only non-Windows piece — compiling the iOS app — is delegated to GitHub's macOS runners.

## Prerequisites

Everything from [WINDOWS_SETUP.md](WINDOWS_SETUP.md) (Rust, Node LTS, VS Build Tools), plus VS Code (or your editor of choice) with the rust-analyzer and Swift extensions. Yes, you can write Swift comfortably on Windows — you just can't compile it there, which is what CI is for.

## Desktop: the inner loop

Run the app in dev mode from `sendro\desktop`:

```powershell
npm run tauri dev
```

- **React/Vite frontend**: hot-reloads instantly on save.
- **Rust changes** (either `desktop/src-tauri` or `core/`): the dev process rebuilds and relaunches the app automatically. Incremental rebuilds are seconds; only the first build of the day is slow.

## Core engine: tests

`core/` is a standalone crate with no Tauri dependency — this is deliberate, so the engine is testable headlessly:

```powershell
cd sendro\core
cargo test
```

Run this after touching anything in `core/` — the protocol behavior (pairing HMAC, range handling, hashing, state machine, watch-folder debounce) is covered here, and it's much faster than manual two-device testing. `cargo clippy` before committing keeps the crate warning-clean.

## Frontend: type checking

Vite dev mode doesn't fail on type errors, so check explicitly from `sendro\desktop`:

```powershell
npx tsc --noEmit
```

Run it before pushing; CI treats type errors as failures.

## iOS: the outer loop

There's no simulator or local build on Windows, so iOS iteration goes through CI. The loop:

1. **Edit Swift** in VS Code on Windows (`ios/` — sources plus `project.yml` for XcodeGen; if you add/remove Swift files, XcodeGen picks them up automatically since the project is generated from `project.yml`).
2. **Push** to GitHub.
3. **CI builds** the unsigned IPA (`.github/workflows/ios-build.yml`, ~3–6 min). A compile error fails the workflow — read the `xcodebuild` output in the run logs.
4. **Download** the `Sendro-unsigned` artifact.
5. **Re-sideload** with Sideloadly over USB (~2 min). Installing over the top preserves app data and pairing.

Full loop cost: **roughly 10 minutes per iteration.** Plan around that:

- **Batch small changes.** Don't push one tweaked string; collect a coherent set of changes, review the diff, push once.
- **Desk-check Swift carefully before pushing** — the compiler feedback you'd normally get in seconds costs you a CI round-trip here. The Swift VS Code extension's diagnostics catch a good chunk of errors locally.
- **Push protocol/logic complexity toward `core/` and the protocol** where you have fast Rust tests, and keep the Swift side as thin as feasible.
- Use `workflow_dispatch` (Actions → iOS Build → Run workflow) to rebuild without an empty commit.

## Testing protocol changes without the phone

The Windows server is plain HTTP, so `curl` is your friend while iterating (see `docs/PROTOCOL.md` for exact shapes):

```powershell
curl http://localhost:48800/api/v1/info
```

Unauthenticated `/info` should always answer; authenticated endpoints can be exercised with a captured `Authorization: Bearer …` token from a dev pairing.

## Bumping `protocolVersion`

The wire protocol is pinned by `docs/PROTOCOL.md`. If you make a **breaking** change (renamed/removed fields, changed semantics, new mandatory behavior):

1. Update `docs/PROTOCOL.md` first — it is the single source of truth.
2. Bump the version constant in **all three places** it lives:
   - `core/` — the Rust `protocol_version` constant (served in `/api/v1/info` and the mDNS TXT `v` record).
   - `ios/` — the Swift constant sent in `pair/start` and checked against `/info`.
   - `docs/PROTOCOL.md` — the version header.
3. Remember the compatibility rule: a client seeing an unknown major version must show "Update Sendro" and refuse to transfer — never limp along.

Additive, backward-compatible changes (a new optional field, a new endpoint) do **not** require a bump; unknown JSON fields must be ignored by both sides.

## Release checklist

Before tagging a release:

1. `cargo test` in `core/` — green.
2. `npx tsc --noEmit` in `desktop/` — clean.
3. `npm run tauri build` locally, or confirm `windows-build.yml` is green; install the produced installer on a clean-ish machine/VM if the bundle config changed.
4. iOS CI green; sideload the fresh IPA onto the real phone.
5. Run the [acceptance tests](ACCEPTANCE_TESTS.md) — at minimum tests 1, 3, and 4; all five for anything touching transfer or storage code.
6. Version bumps consistent: `desktop/src-tauri/tauri.conf.json` (app version), `core/Cargo.toml`, iOS `project.yml` (marketing version). Protocol version only if the wire format changed (see above).
7. Update README/docs if behavior visible to the user changed.
8. Tag (`git tag v0.x.y && git push --tags`), attach the Windows installer and the unsigned IPA to the GitHub release.

## Repo etiquette

- `docs/PROTOCOL.md` and `docs/CORE_API.md` are contracts — change them consciously and first, then make the code match.
- Never commit the generated Xcode project; `project.yml` is the source.
- Keep `core/` free of Tauri and UI concerns; the seam is pinned in `docs/CORE_API.md`.
