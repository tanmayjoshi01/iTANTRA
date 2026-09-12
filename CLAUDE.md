# CLAUDE.md — iTANTRA Project Control Entry Point

This file is the entry point for any Claude Code session working in this repository. Read it first, every session, before making any change.

## What iTANTRA is

iTANTRA is an offline, multilingual, device-to-device speech communication system for constrained data links, built as a Smart India Hackathon 2026 project (Problem Statement SIH26173, Indian Space Research Organisation, Department of Space). The system converts speech to text on-device, sends compact text between two Android phones over a direct link (Wi-Fi Direct / Bluetooth), and reconstructs speech on the receiving device — avoiding the need to carry live audio over an unreliable link. See `README.md` for the full project description; this documentation set does not repeat it.

## Current identity

- Developer active in this branch: **Tanmay**
- Branch: **Tanmay-iTarntra**
- Current development stage: **Stage 1 — Audio + VAD Foundation** (implementation complete; Android/device validation pending)
- Next planned stage: **Stage 2 — Offline STT** (not started; must not be started without explicit instruction)

## Mandatory reading order

Read in this order when picking up work in this repository:

1. `CLAUDE.md` (this file)
2. `docs/claude/current-state.md` — what is actually done right now
3. `docs/claude/stages.md` — the full roadmap and stage boundaries
4. `docs/claude/architecture.md` — the technical design and what exists vs. planned
5. `docs/claude/ownership.md` — Tanmay/Paras boundaries
6. `docs/claude/instructions.md` — detailed operating rules
7. `docs/claude/engineering-rules.md` — engineering standards
8. `docs/claude/testing-and-validation.md` — validation levels and what "done" means
9. `docs/claude/decisions.md` — consult when an architectural decision is relevant or a past decision needs revisiting

## Operating contract (summary — see linked files for detail)

- **Inspect before modifying.** Read the relevant existing code, tests, and docs before changing anything. Never assume structure; verify it.
- **Stay in scope.** Do not begin a stage beyond the current one (see `docs/claude/stages.md`) without explicit instruction. Do not expand a task's scope on your own initiative.
- **Respect ownership boundaries.** Do not modify Paras's ownership areas (application module, UI, transport, protocol) unless explicitly asked. See `docs/claude/ownership.md`.
- **Git is manual.** Claude must never perform Git write operations (commit, push, pull, merge, rebase, reset, branch create/delete, PR create) in this repository unless the user explicitly instructs it in that specific request. Read-only inspection (`git status`, `git log`, `git diff`) is always fine.
- **No unnecessary dependencies.** Do not install software, download SDKs/models, or add dependencies without explicit instruction. Report what's missing and give exact manual commands instead.
- **Test real changes.** Run the tests that actually exist for what you changed. Never report a test, build, or device result that was not actually observed. Distinguish "implemented" from "validated" — see `docs/claude/testing-and-validation.md`.
- **Report uncertainty honestly.** If a claim (a license, a benchmark, a device behavior, an architectural fact) is not verified, say so explicitly rather than asserting it. Distinguish official SIH requirements from this team's own engineering decisions.

Full detail for each of these rules lives in the files listed above — this file is an index, not the rulebook.
