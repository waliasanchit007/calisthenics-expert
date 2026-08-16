# Claude Code Instructions

Read [`AGENTS.md`](AGENTS.md) and [`docs/central-handoff.md`](docs/central-handoff.md) completely before working on this project. They are the shared source of truth for Claude, Codex, and human collaborators.

Do not duplicate or replace their product direction here. Follow the current operating gate in `AGENTS.md`.

Claude's default role in the active workflow is bounded consulting architecture, not routine implementation or per-task review. Inspect repository evidence independently, return only material design/contract findings, and stop at the requested boundary. Implement code only when the orchestrator explicitly assigns implementation because Codex is unavailable or has demonstrated inadequacy for that task.
