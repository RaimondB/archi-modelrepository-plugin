# Attribute-Level Merge and Rules-Based Merge Automation Plan

## Overview
This document outlines the plan for implementing attribute-level 3-way XML merging and rules-based merge automation in the coArchi plugin. The goal is to enable fine-grained, automated, and configurable merge conflict resolution for ArchiMate models stored in GRAFICO format, supporting both interactive (UI) and headless/CI workflows.

---

## Motivation
- **Current Limitation:** Merge conflicts are resolved at the file level (ours/theirs), requiring manual intervention for most conflicts.
- **Desired Outcome:** Enable attribute-level merging (e.g., merge individual XML attributes/elements), and allow users to define rules for automatic conflict resolution (e.g., "prefer folder X on conflict").
- **Headless/CI Support:** Provide a way to resolve conflicts automatically in CI pipelines or scripts, without user interaction.

---

## Phases

### Phase 1: Attribute-Level 3-Way XML Merge Engine
- Implement a 3-way XML merge engine that:
  - Accepts base, ours, and theirs XML content.
  - Merges at the attribute and element level (not just file level).
  - Detects and reports true conflicts (e.g., same attribute changed in both ours and theirs).
  - Outputs merged XML and a list of unresolved conflicts.
- Integrate the engine into `MergeConflictHandler` and related classes.
- Provide unit tests for all major merge scenarios (add, delete, modify, move, rename, attribute change, etc.).

### Phase 2: Rules File and Rules Engine
- Define a `.coarchi-merge-rules` file (YAML or JSON) at the repo root.
- Supported rules:
  - Folder precedence (e.g., "prefer /business/ over /application/").
  - Attribute precedence (e.g., "prefer 'documentation' from theirs").
  - Auto-resolve strategies (e.g., "always take theirs for /views/*").
- Implement a rules parser and engine.
- Integrate rules engine into merge flow (both UI and headless).
- Provide documentation and examples for rules file.

### Phase 3: Headless/CI Merge Automation
- Expose merge automation via CLI and API.
- Support non-interactive operation:
  - If all conflicts are auto-resolved by rules, complete merge.
  - If unresolved conflicts remain, abort with error and report details.
- Add tests for CI scenarios (auto-resolve, partial resolve, abort on unresolved).

### Phase 4: Interactive UI and Conflict Resolution
- Enhance the UI to:
  - Show attribute-level conflicts and allow manual resolution.
  - Display which rules were applied.
  - Allow user to override rules interactively.
- Provide clear feedback and logging for all merge operations.

---

## Design Principles
- **Non-blocking:** Headless merges must never prompt for UI input.
- **Transparency:** All auto-resolutions must be logged and traceable.
- **Extensibility:** Rules engine should be easy to extend for new strategies.
- **Safety:** Never silently discard conflicting changes; always report unresolved conflicts.

---

## Example `.coarchi-merge-rules` (YAML)
```yaml
folders:
  - path: /business/
    prefer: ours
  - path: /views/
    prefer: theirs
attributes:
  - element: documentation
    prefer: theirs
  - element: name
    prefer: ours
```

---

## Implementation Checklist
- [ ] 3-way XML merge engine (Phase 1)
- [ ] Rules file format and parser (Phase 2)
- [ ] Rules engine integration (Phase 2)
- [ ] CLI/headless merge automation (Phase 3)
- [ ] UI enhancements for conflict resolution (Phase 4)
- [ ] Documentation and usage examples

---

## References
- [PERFORMANCE_ARCHITECTURE.md](../org.archicontribs.modelrepository/src/org/archicontribs/modelrepository/grafico/PERFORMANCE_ARCHITECTURE.md)
- [REFACTORING_NOTES.md](../org.archicontribs.modelrepository/src/org/archicontribs/modelrepository/grafico/REFACTORING_NOTES.md)
- [ARCHITECTURE.md](../ARCHITECTURE.md)

---

*Last updated: April 28, 2026*