# ADR-0001: GRAFICO Format for Git-Friendly Model Storage

## Status

Accepted

## Date

2015-01-01 (approximate — predates formal ADR process)

## Context

Archi stores models as single `.archimate` files (EMF XMI). These are large XML files where small model changes produce large diffs, making Git collaboration impractical. Merge conflicts in monolithic XML are nearly impossible to resolve.

## Decision

Store ArchiMate models in GRAFICO format (Git fRiendly Archi FIle COllection). Each model element, relation, folder, and diagram is stored as a separate XML file in a directory structure mirroring the model's folder hierarchy. File names are based on element IDs, making them stable across renames.

## Consequences

- **Positive**: Small model changes produce small, reviewable diffs. Git merge works at the file level. Multiple users can work on different parts of a model simultaneously.
- **Positive**: File-per-element means git blame works for individual elements.
- **Negative**: A typical model produces 10,000–30,000+ files, requiring performance optimization for I/O.
- **Negative**: Cross-path deletions (element moved in one branch, folder deleted in another) require special detection and resolution logic.
- **Negative**: `folder.xml` files can go missing during merges, requiring repair logic.
