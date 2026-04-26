# ADR-0003: Use Export Return Value for Change Detection

## Status

Accepted

## Date

2025-12-01 (approximate)

## Context

After exporting the model to GRAFICO files, we need to know if there are changes to commit. Two approaches exist:

1. Call `hasChangesToCommit()` (runs `git status`) after export
2. Use the return value of `GraficoModelExporter.exportModel()` which tracks written/deleted files internally

The first approach has a subtle bug: `git status` checks the working tree against the index. If `git add` hasn't been run yet, status reflects the working tree. But more critically, export + status is two operations when one suffices.

## Decision

Use `exportModel()`'s return value to detect changes. The exporter already tracks whether any files were written or deleted. This is both faster (no extra git operation) and more reliable (no timing dependency on `git add`).

The return value determines whether to run `git add` at all — skipping staging when nothing changed.

See also: `REFACTORING_NOTES.md` in the grafico package.

## Consequences

- **Positive**: Faster — avoids redundant `git status` call
- **Positive**: Atomic — single source of truth for "did anything change?"
- **Negative**: Callers of `exportModelToGraficoFiles(monitor)` must use the return value, not call `hasChangesToCommit()` separately
