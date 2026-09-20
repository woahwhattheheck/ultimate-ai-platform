# LibreOffice document processing

`LibreOfficeAutomationTool` is auto-discovered by `ToolRegistry` and exposed as
`processDocuments`. It uses a host-installed `soffice` executable; it does not
download or install LibreOffice at runtime.

## Hard $150 session allocation gate for issue #9

Production wiring now includes `LibreOfficeJdbcComputeBudget`. `AiOrchestrator`
places the authenticated chat `sessionId` into Spring AI `ToolContext`; both the
Gemini and Ollama providers forward that hidden context with `.toolContext(...)`.
Spring AI does not send this map to the model, so neither the model nor tool
arguments can choose a session identity or a charge.

Reservations are stored in PostgreSQL in `libreoffice_compute_budgets` and are
performed with one atomic `INSERT ... ON CONFLICT ... WHERE` statement. The
window is 30 minutes from the first reservation for a chat session. Concurrent
requests and multiple application instances contend on the same session row.
After 30 minutes, the next reservation atomically starts a new window.

The default scheduler allocation is deliberately conservative rather than an
attempt to infer a cloud invoice: each reserved LibreOffice process second is
charged USD 0.625 of allocation. With the 60-second process ceiling, one
operation reserves USD 37.50 and the maximum four-operation batch reserves
exactly USD 150.00. Reservations are charged before any scratch directory or
process exists and are not refunded after failure, so retries cannot bypass the
cap. Any request that would take the current window above USD 150.00 is denied.
Missing/invalid context, database failures and write contention fail closed.

Direct MCP/tool invocations that do not arrive through a trusted server session
remain denied. Deployments may replace `LibreOfficeComputeBudget` with a stricter
allocator, but the tool always supplies an absolute USD 150.00 cap and never
accepts a model-provided cost.

## Inputs and results

| Input format | Payload encoding | Output formats |
| --- | --- | --- |
| txt | Plain UTF-8 text | docx, pdf |
| csv | Plain UTF-8 CSV (comma/quote, formulas disabled) | xlsx, pdf |
| docx | Standard Base64 | docx, pdf |
| xlsx | Standard Base64 | xlsx, pdf |
| pdf | Standard Base64 | pdf |

Provide one to four `payloads`, one common `inputFormat` and `outputFormat`,
and `pdfPages` (empty for all pages, or e.g. `1-3,5`). TXT/CSV inputs generate
new office documents; conversions preserve content to the extent supported by
LibreOffice filters. PDF page selection provides the supported manipulation
operation. Arbitrary office editing, template substitution and PDF-to-DOCX are
not provided.

Every payload is evaluated independently using `StandardCharsets.UTF_8` and
limited to **5,000,000 bytes**, including the Base64 representation for binary
inputs. This is deliberately a strict decimal 5 MB ceiling. Binary signatures,
format pairs, batch count and PDF page syntax are validated before allocation.
Each output is limited to 10,000,000 bytes, and each batch is also capped at 10,000,000 raw output bytes before Base64 encoding so multi-document requests cannot multiply the response bound. Results are returned as Base64:

```json
{"documents":[{"name":"document-1.pdf","encoding":"base64","data":"..."}]}
```

Errors have the shape `{"error":"..."}`. No server paths are returned. A failure
in any document rejects the entire batch; converted files are not retained.

## Process and storage boundary

The production root is `~/ultimate-managed-workspaces`. Only generated scratch
names are used; there is no caller-supplied path, command, executable or filter.
The root must be server-owned and may not traverse symbolic links. Each request
gets a private temporary directory, with restrictive POSIX permissions when
available. Each conversion gets a separate profile (macro security level 3),
output directory and temporary environment. The LibreOffice child inherits only
host process-launch essentials (path, locale, timezone and Windows launch
variables); unrelated server credentials, proxy settings and service configuration
are removed. Private `HOME`, `TMPDIR`, `TMP` and `TEMP` values then override the
remaining environment. Arguments go directly to `ProcessBuilder`, without a shell.
Do not share this directory with writers outside the server process.

Stdout and stderr use independent daemon drainers, continuing to consume after
bounded diagnostic capture fills. Each process has a 60-second timeout. Cleanup
terminates remaining child processes, closes streams and purges inputs, outputs,
profiles and temporary files in `finally`, on success and failure. A teardown
failure returns an error requiring operator intervention.

These Java boundaries are not an operating-system sandbox, tenant authorization
or a disk/CPU/memory quota. A production host processing untrusted documents must
run LibreOffice in its existing restricted execution environment with appropriate
filesystem, network and resource limits. Profile settings do not replace those
host controls. The USD reservation must account for the host's enforced
worst-case runtime/resource allocation.

## Verification

The normal unit suite exercises UTF-8 boundaries, batch validation, hidden tool
context, hard-cap reservation math, ledger failure/contended-write denial,
process start/failure/timeout/interruption, pipes filled beyond OS capacity,
symbolic links and scratch cleanup. The dedicated
`LibreOffice document tests` workflow runs those tests plus real DOCX and XLSX
generation, DOCX/XLSX-to-PDF conversion and PDF page extraction on an ordinary
GitHub-hosted Ubuntu runner. It installs LibreOffice only on that runner.

Run the real smoke suite on an appropriately prepared host:

```sh
LIBREOFFICE_SMOKE=true ./mvnw -B -Dtest=LibreOfficeAutomationToolTest,LibreOfficeAutomationToolRegistryTest,LibreOfficeAutomationToolSmokeTest test
```

The real smoke tests are skipped unless `LIBREOFFICE_SMOKE=true`; no model
provider or database is required for this focused suite.

References: [LibreOffice command parameters](https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html),
[conversion filters](https://help.libreoffice.org/latest/en-US/text/shared/guide/convertfilters.html),
[PDF page export](https://help.libreoffice.org/latest/en-US/text/shared/guide/pdf_params.html),
[Spring AI tool context](https://docs.spring.io/spring-ai/reference/api/tools.html).
