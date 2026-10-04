# Image processing tool

`ImageProcessingTool` is discovered by Spring through `UltimateTool`. It uses
ImageMagick 7's `magick` executable and the DejaVu Sans font for watermarking.
The tool does not install either dependency.

The caller supplies an existing source workspace below
`~/ultimate-managed-workspaces`, one to four relative PNG/JPEG/WebP input paths,
and an artifact prefix. Generated images are **never written back to the caller
workspace**. Each invocation uses a private tool-created runtime below the
managed root, captures bounded outputs into the response, and purges every file
in that runtime in `finally` before returning.

Example arguments for `processImages`:

```json
{
  "workspacePath": "/home/ultimate/ultimate-managed-workspaces/job-123",
  "inputPaths": ["photos/front.png", "photos/back.jpeg"],
  "outputPrefix": "thumbnail",
  "outputFormat": "webp",
  "width": 320,
  "height": 240,
  "crop": true,
  "brightness": 100,
  "saturation": 100,
  "quality": 85,
  "watermark": "Example"
}
```

Both dimensions must be 1–4096, or both zero with `crop=false` to retain
dimensions. With `crop=false`, resizing preserves aspect ratio and does not
enlarge the image.
With `crop=true`, the image is resized to fill the target and cropped centrally.
Brightness and saturation range from 0–200; 100 is neutral. Quality is 1–100.
An empty watermark disables annotation. Watermarks reject controls and
ImageMagick expansion characters (`%`, `\`, `@`).

## Result and limits

Inputs are limited to 20 MiB each. Input format is detected from the file header
and passed to ImageMagick with an explicit raster coder; only the first frame is
processed. A batch contains at most four images.

Each generated image is limited to 10,000,000 bytes, and the whole batch is
limited to 10,000,000 output bytes before Base64 expansion. Successful calls
return artifacts directly:

```json
{
  "images": [
    {
      "name": "thumbnail-1.webp",
      "encoding": "base64",
      "bytes": 12345,
      "data": "UklGR..."
    }
  ]
}
```

Errors have the shape:

```json
{"error":"Image Processing Error: ..."}
```

A failure in any image rejects the whole batch. No partial artifact array is
returned, and no server path is exposed.

## Filesystem and process boundary

The configured managed root and caller source workspace are canonicalized with
`Path.toRealPath()`. Absolute inputs, traversal, paths resolving outside the
source workspace, duplicate canonical inputs, and symlinked managed roots are
rejected. Validation retains
each input's parent directory handle and captures its non-null directory/file
identities, file size, and modification time. The original directory handle is
used for the bounded read. Before opening the input and after reading it, the
tool rejects a parent directory identity, file identity, size, or modification
time that differs from validation. All retained input handles are closed even
when validation or another image in the batch fails.

This requires `SecureDirectoryStream` and stable file identities from the
filesystem. Unsupported providers fail instead of falling back to a pathname
read. These checks do not provide an atomic snapshot against a local process
that continuously modifies a file or swaps and restores the same leaf between
identity checks; source workspaces still require appropriate OS access control.

Every request receives a generated `.ultimate-image-*` runtime directory under
the managed root. Only generated staging and output names are used inside it.
The tool copies each validated input with `NOFOLLOW_LINKS`, invokes ImageMagick
with an argument vector rather than a shell, bounds native thread/memory/map/disk
and dimensions, and enforces a 30-second timeout per image. Output signatures
must match the requested PNG, JPEG, or WebP format.

The ImageMagick child receives an allowlisted host environment limited to process
launch/locale settings plus ImageMagick, font and dynamic-loader configuration.
Private `HOME`, temporary-directory aliases and `MAGICK_TEMPORARY_PATH` are forced
to the generated runtime. Unrelated database, cloud, proxy and service credentials
are not inherited by the native image process.

The complete private runtime—including staged inputs, generated outputs, pixel
caches, and intermediates—is traversed without following symbolic links and
deleted in `finally` after the response artifact bytes have been captured.
Cleanup failure invalidates an otherwise successful result and returns an
operator-intervention error. Caller-owned source files remain unchanged.

The managed-root check provides filesystem confinement, not tenant
authentication. The caller/service must select an authorized source workspace
and restrict local OS access to it. Deploy ImageMagick with an appropriate host
security policy and keep native libraries patched.

Tests cover bounded Base64 return values, command construction, content and path
validation, output-format verification, per-image and aggregate response limits,
batch atomicity, symlink replacement, timeout, interruption, process pipe
draining, root confinement, and cleanup after success and failure. A native smoke
test runs when ImageMagick 7 is installed and otherwise skips explicitly.

## Recorded supported-runtime execution

On October 4, 2026, [run 37202619930](https://github.com/woahwhattheheck/ultimate-ai-platform/actions/runs/37202619930) executed the two maintained image test classes against exact source `eacdf3e79ec07d8e3b8e805009f24a1b63fe7b58`, including the input-identity repair at `5d53b0b8`. The run completed successfully on Ubuntu 24.04 with Temurin Java 21.0.12.1 and the existing pinned official ImageMagick 7.1.2-31 distribution.

| Maintained class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| ImageProcessingToolTest | 16 | 0 | 0 | 0 |
| ImageProcessingToolSecurityTest | 3 | 0 | 0 | 0 |
| Total | 19 | 0 | 0 | 0 |

The regular-directory and regular-file replacement tests reach the actual retained input-handle checks during a two-image batch. They require the replacement to be rejected before a second converter call and verify runtime cleanup. These cases control the converter boundary while using the actual filesystem and product validation/read path.

Both maintained native methods executed. One exercises the real Java subprocess runner, including pipe draining and timeout. The other invokes ImageMagick, compares plain and watermarked output pixels, verifies stripped metadata, and checks that generated runtime files are removed. Spring component discovery and the existing validation, output-limit and cleanup cases also passed.

Reproduce from `server` with the documented native prerequisites installed:

```sh
./mvnw -B '-Dtest=ai.ultimate.tools.builtin.ImageProcessingToolTest,ai.ultimate.tools.builtin.ImageProcessingToolSecurityTest' test
```

The runner checked source tree `89164450554ad7dfecabda04bda7be7f07ec3fdb` before execution and required unchanged tracked files afterward. The exercised product and maintained test blobs are:

- Product: `9437a633a4cf93e96dca4ed4f9d8475996be784b`.
- ImageProcessingToolTest: `7ece927a48ba337d0683698813664df03cdd7d55`.
- ImageProcessingToolSecurityTest: `e923e3482a0509b24217ded720cf03502fe525e1`.

[Raw job log](https://github.com/woahwhattheheck/ultimate-ai-platform/blob/baf81a1f5e4be41e0ea8bbe4c2ac0b09844feadc/work/image-current-runtime-20261004/job.log), [per-case receipt](https://github.com/woahwhattheheck/ultimate-ai-platform/blob/baf81a1f5e4be41e0ea8bbe4c2ac0b09844feadc/work/image-current-runtime-20261004/receipt.json), and [provenance](https://github.com/woahwhattheheck/ultimate-ai-platform/blob/baf81a1f5e4be41e0ea8bbe4c2ac0b09844feadc/work/image-current-runtime-20261004/provenance.json) are retained on the separate evidence branch. Controller commit `10ec673a79878a169b27a0e92b125a77d6bf9d2a` is distinct from the tested product commit. The original contribution includes no execution-workflow change.

The [GitHub artifact](https://github.com/woahwhattheheck/ultimate-ai-platform/actions/runs/37202619930/artifacts/11303143596) contains the original Surefire XML, source manifest and runtime logs: 243,156 bytes, provider-reported SHA256 `270c8c1bee049cdd777d571474ba156bdb58b9eed278713fa934a071d16f2c77`, with configured expiry November 3, 2026. The committed log and receipts remain available independently of that artifact.

This is current-source evidence for the two maintained image classes. The earlier 350-test whole-project run at `c4ded07beeb9f4f176ad58c182b97d7c21e66209` remains historical; it was not rerun or attributed to the newer repair. This continuation makes no new benchmark, complete application-CI, deployment, maintainer-acceptance or payment claim.
