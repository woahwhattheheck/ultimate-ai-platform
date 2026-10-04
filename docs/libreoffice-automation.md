# LibreOffice document processing

`LibreOfficeAutomationTool` is auto-discovered by `ToolRegistry` and exposed as
`processDocuments`. It uses a host-installed `soffice` executable; it does not
download or install LibreOffice at runtime.

## Host prerequisites

Use Java 21 for the server and the Maven wrapper in `server/`. Install a matching
LibreOffice Writer, Calc and Draw runtime on the server host, with `soffice`
available on its `PATH`. PDF page selection requires Draw's `draw_pdf_import`
filter as well as its PDF export filter.

On Debian/Ubuntu, the focused document workflow installs these packages:

```sh
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  libreoffice-writer libreoffice-calc libreoffice-draw poppler-utils
```

`poppler-utils` supplies `pdfinfo` and `pdftotext` for the real document tests.
Use LibreOffice components from the same distribution/build; a partial bundle
may provide document creation and PDF export while lacking PDF import.
The application does not install these host dependencies, and the repository's
Docker Compose file provisions only PostgreSQL and Redis.

## Current acceptance status for issue #9

The document engine and the LibreOffice-specific **USD 150/session-window hard
allocation gate are implemented**. `AiOrchestrator` attaches the authenticated
chat session identifier as server-owned Spring AI `ToolContext` metadata, and the
tool-capable Ollama/Gemini providers forward that hidden context to tool calls.
Model arguments cannot supply or override the session identity, allocation rate,
or credit.

`LibreOfficeJdbcComputeBudget` is the production Spring bean. Migration
`V18__create_libreoffice_compute_budgets.sql` provides the PostgreSQL ledger,
and each reservation uses one atomic upsert against the trusted session id. The
window is 30 minutes from its first reservation. At the configured conservative
rate of USD 0.625 per reserved process second and the 60-second process ceiling,
one document operation reserves USD 37.50 and the maximum four-document batch
reserves exactly USD 150.00. Reservations are not refunded after process
failure, so retries cannot bypass the cap. Concurrent reservations, exhausted
allocation, missing/invalid session context, and database failures all deny
execution before scratch files or LibreOffice processes are created.

This ledger is intentionally scoped to this LibreOffice tool. It is an allocation
guard for issue #9, not a claim that the application now has a provider-wide
billing ledger or that USD 0.625/second is an observed provider invoice rate.
Changing the session window or allocation rate is therefore an explicit host
policy decision, while the USD 150 hard cap remains enforced by the adapter.

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

The normal unit suite exercises UTF-8 boundaries, batch validation, budget denial,
hidden tool context, process start/failure/timeout/interruption, pipes filled
beyond OS capacity, symbolic links and scratch cleanup. The dedicated
`LibreOffice document tests` workflow runs those tests plus real DOCX and XLSX
generation, DOCX/XLSX-to-PDF conversion and PDF page extraction on an ordinary
GitHub-hosted Ubuntu runner. It installs LibreOffice only on that runner.

Run the real smoke suite from `server/`, where the Maven wrapper and project POM
live. From the repository root on an appropriately prepared host:

```sh
cd server
LIBREOFFICE_SMOKE=true ./mvnw -B -Dtest=LibreOfficeAutomationToolTest,LibreOfficeAutomationToolRegistryTest,LibreOfficeAutomationToolSmokeTest test
```

The real smoke tests are skipped unless `LIBREOFFICE_SMOKE=true`; no model
provider or database is required for this focused suite.

## Recorded real-document execution

[Run 37185385304](https://github.com/woahwhattheheck/ultimate-ai-platform/actions/runs/37185385304)
completed successfully on 2026-10-04. The job explicitly checked out and verified
product source `29b882f839c95c765bd3f1acd243b9302d305b2c` before execution.
Its validation-workflow commit was
`372e12309339b0502a532405060da29088beea06`; that controller is separate from the
product checkout.

The existing `LibreOfficeAutomationToolSmokeTest` ran on Ubuntu 24.04.5 with
Temurin Java 21.0.12.1, Maven 3.9.16, LibreOffice Writer/Calc/Draw 24.2.7.2 and
Poppler 24.02.0. From `server/`, the selected command was:

```sh
LIBREOFFICE_SMOKE=true ./mvnw -B -Dtest=LibreOfficeAutomationToolSmokeTest test
```

Result: **2 tests, 0 failures, 0 errors, 0 skipped**; Maven reported
`BUILD SUCCESS`. The test class took 2.684 seconds. This is one functional run,
not a throughput measurement.

The two maintained methods verify:

- TXT-to-DOCX content, DOCX-to-PDF text, CSV-to-XLSX content, preservation of a
  formula-like value as text, and XLSX-to-PDF text.
- A real multipage PDF, extraction of one selected page with the expected text,
  and removal of the managed request files after both scenarios.

The complete-runtime run resolves the two previously reported smoke failures
from the partial LibreOffice runtime and constrained execution host. It does not
retroactively change those earlier results.

Evidence: [job 111386107091](https://github.com/woahwhattheheck/ultimate-ai-platform/actions/runs/37185385304/job/111386107091),
artifact `ultimate42-documents-7c63414ea055` (ID `11296583589`, 11,686 bytes),
provider-reported SHA-256
`ab539e281b21aef6a3343ce2804e31f0cdf7fbfc82bde7fed7d2836a83de1db6`.
The artifact includes JUnit results and recorded source/runtime details.

This selected smoke fixture uses its permissive test budget adapter. It does
not validate PostgreSQL reservation concurrency, production billing, provider
tool invocation, the complete unit suite, or all application CI. Historical
successful runs at `5a9118de310b905243a19a5aa93ec5f475e6960e` remain historical
evidence and must not be described as validation of later source.

References: [LibreOffice command parameters](https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html),
[conversion filters](https://help.libreoffice.org/latest/en-US/text/shared/guide/convertfilters.html),
[PDF page export](https://help.libreoffice.org/latest/en-US/text/shared/guide/pdf_params.html),
[Spring AI tool context](https://docs.spring.io/spring-ai/reference/api/tools.html).
