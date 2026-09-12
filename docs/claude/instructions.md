# Claude Code Operating Instructions — iTANTRA

Detailed operating rules for any Claude Code session working in this repository. `CLAUDE.md` is the index; this file is the rulebook it points to.

## Working with existing code

- **Inspect before modifying.** Read the file(s) you intend to change, their tests, and any documentation that references them before writing any change. Do not guess at structure, naming, or behavior that can be checked directly.
- **Understand existing implementation first.** Before extending or refactoring a component (for example anything in `speech-engine`), read its current implementation and tests fully. Do not reimplement something that already exists.
- **Preserve working code.** Do not rewrite or restructure code that is already implemented and tested unless the task requires it. A request to add a feature is not a request to reorganize unrelated working code.
- **Make minimal changes.** Change only what the task requires. Avoid incidental edits (renames, reformatting, reordering) bundled into an unrelated change.
- **Avoid unnecessary refactoring.** Refactor only when the task explicitly calls for it or when a change cannot be made correctly without it — and say so when it happens.
- **Do not expand scope.** If a task looks like it should include more than what was asked (extra tests, extra features, extra files), ask or state the boundary explicitly rather than silently doing more.
- **Surface architectural uncertainty.** If a change touches an undecided or "Under Evaluation" item (see `decisions.md`), stop and report the decision that would need to be made rather than picking one silently.

## Testing and claims

- **Test changes you make.** Run the tests that apply to the code you touched (see `testing-and-validation.md` for the applicable level) before describing a change as working.
- **Never fabricate test results.** Only report a test as passing or failing if it was actually executed and its output observed in this session.
- **Never claim device validation without actual device testing.** "Compiles" or "unit tests pass" is not "validated on a physical device." State explicitly which validation level (see `testing-and-validation.md`) a claim is based on.
- **Never claim benchmarks without measurements.** Do not state a latency, CPU/RAM figure, model size, WER, or any other performance number unless it was actually measured in this session or is cited from an existing, dated measurement in this repository's docs.
- **Distinguish implementation from validation** in every status report: "implemented" means code exists and compiles/unit-tests where applicable; it does not imply Android build success, device behavior, or performance characteristics.
- **Distinguish official SIH requirements from engineering recommendations.** When citing a requirement, state whether it comes from the official SIH problem statement (SIH26173) or is this team's own engineering interpretation/decision (see README, Problem Statement section, and `decisions.md`).

## Git rules

Claude must never, in this repository, without explicit per-request instruction from the user:

- `git commit`
- `git push`
- `git pull`
- `git merge`
- `git rebase`
- `git reset`
- create or delete branches
- create pull requests
- modify Git history in any other way
- modify GitHub repository/settings (branch protection, collaborators, Actions, etc.)

Claude may always run read-only Git inspection: `git status`, `git log`, `git diff`, `git branch`, `git show`. Developers (Tanmay, Paras) control all Git write operations manually.

## Software and dependency installation

- Do not install software, SDK components, or system packages automatically.
- Do not install Android SDK platforms/build-tools without explicit instruction, even to unblock a build.
- Do not download large ML models or datasets without explicit approval.
- When a dependency, SDK component, or tool is missing, report exactly what is missing and provide the exact manual command(s) the developer would run (e.g. an `sdkmanager` invocation) rather than running them.

## Model rules (relevant from Stage 2 onward)

- Verify a model's license before recommending it for integration.
- Distinguish the model's own license (e.g. the weights) from the license of the runtime/inference engine used to run it (e.g. ONNX Runtime) — these can differ and both matter.
- Do not assume an open-source or "free" model is automatically suitable for redistribution inside an SIH submission or a public repository; check the specific license terms.
- Record each model's source and license in the project documentation when it is adopted (see `decisions.md`).
- Do not commit large model binaries to Git without an explicit, documented decision on distribution mechanism (see README, "Model Files and Large Assets").

## Documentation maintenance

- Keep `docs/claude/current-state.md` accurate to the actual repository state; when it goes stale during a session, say so rather than silently trusting it.
- Do not duplicate information that already lives in `README.md` or another `docs/claude/` file — cross-reference instead.
- Follow the content-quality rules in `engineering-rules.md` (no emojis, no marketing language, no unsupported claims) in all documentation edits.
