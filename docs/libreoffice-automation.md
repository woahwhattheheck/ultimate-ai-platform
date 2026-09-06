# LibreOffice document processing

`LibreOfficeAutomationTool` is auto-discovered by `ToolRegistry` and exposed as
`processDocuments`. It uses a host-installed `soffice` executable; it does not
download or install LibreOffice at runtime.

## Current acceptance status for issue #9

The document engine is implemented. **The production $150/session-window billing
integration remains pending the maintainer's authoritative session, tariff and
window contract.** Current main's `ChatSession` tracks tokens; tool registration
does not pass authenticated session context or a USD allocation ledger.

The default tool therefore rejects every execution with `Budget denied`.
A host-owned `LibreOfficeComputeBudget` Spring bean must atomically reserve the
worst-case cost of the whole batch from a trusted `ToolContext`, including prior
usage and concurrent reservations, with a hard cap of USD 150.00. Missing
identity, expired windows, unknown rates and ledger failures must deny execution.
The adapter must enforce the limit across serving instances and other included
tools. Model arguments cannot supply the session identity, cost or credit.
This interface alone does not implement a dollar ledger; a permissive adapter
would invalidate the billing boundary. The tests use explicit fixtures, not real
billing authorization.

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
Each output is limited to 10,000,000 bytes and returned as Base64:

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
output directory and temporary environment. Arguments go directly to
`ProcessBuilder`, without a shell. Do not share this directory with writers
outside the server process.

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
