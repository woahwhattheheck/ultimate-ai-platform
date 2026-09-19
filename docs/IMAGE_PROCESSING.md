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

Both dimensions must be 1–4096, or both zero to retain dimensions. With
`crop=false`, resizing preserves aspect ratio and does not enlarge the image.
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
source workspace, duplicate canonical inputs, symlinked managed roots, and
inputs replaced between validation and staging are rejected.

Every request receives a generated `.ultimate-image-*` runtime directory under
the managed root. Only generated staging and output names are used inside it.
The tool copies each validated input with `NOFOLLOW_LINKS`, invokes ImageMagick
with an argument vector rather than a shell, bounds native thread/memory/map/disk
and dimensions, and enforces a 30-second timeout per image. Output signatures
must match the requested PNG, JPEG, or WebP format.

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
