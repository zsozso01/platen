# 0005. Package namespace and application id

**Status:** accepted · **Date:** 2026-10-05

## Context

An Android `applicationId` can never change after the first Play release. It should be a namespace the
maintainer legitimately controls, so Maven Central publishing (for the protocol libraries) and store
listings are never blocked by a domain question.

## Decision

Use `io.github.zsozso01.platen` for the application id and as the root Kotlin package. The `io.github.<user>`
namespace is verifiable through GitHub, and is the convention for individual open-source projects.

## Consequences

* Package names are long. Accepted: they are rarely typed, and the IDE shortens them.
* If the project moves to an organisation or its own domain, the *code* packages can be renamed, but the
  `applicationId` stays, because changing it creates a new app in the stores.
