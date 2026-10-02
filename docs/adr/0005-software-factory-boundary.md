# ADR 0005 — Code generation, Git, sandbox and runtime are a separate, unapproved phase
Status: proposed — NOT implemented, needs approval before any work

Today: Prompt → SchemaPatch → Page Schema JSON → registry validation → renderer. No source code, repository, build or runtime exists;
Code Mode shows an honest "not available" screen and Publish is labelled *Demo deployment*.

A future Software Factory pipeline (Prompt → planner → registry retrieval → specification → code generator → Git → isolated sandbox →
build/test/scan → artifact → runtime) changes the trust model (generated code executes somewhere) and must be designed first:
repository ownership and lifecycle, provider (GitHub/GitLab/Gitea), branch and AI-commit policy, sandbox isolation (never inside the API
JVM), resource/time/network limits, secret handling, dependency and secret scanning, artifact storage, and a first runtime target
(likely static artifacts → object storage → CDN/Nginx with a private/public gateway). This ADR records the boundary only.
