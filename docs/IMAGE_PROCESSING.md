# Image processing tool

`ImageProcessingTool` is discovered by Spring through `UltimateTool`. It needs
ImageMagick 7's `magick` executable on PATH. Watermarking also uses the
DejaVu Sans font. The tool does not install either dependency.

The caller supplies an existing workspace below
`~/ultimate-managed-workspaces`, one to ten relative PNG/JPEG/WebP input paths,
and an existing relative output directory. The output prefix determines names
such as `thumbnail-1.webp` and `thumbnail-2.webp`. Existing paths, including
dangling symbolic links, are refused.

Example arguments for `processImages`:

```json
{
  "workspacePath": "/home/ultimate/ultimate-managed-workspaces/job-123",
  "inputPaths": ["photos/front.png", "photos/back.jpeg"],
  "outputDirectory": "results",
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
ImageMagick expansion characters (`%`, `\\`, `@`).

Each input and output is limited to 20 MiB. Input format is detected from its
header and passed with an explicit raster coder; the first frame is processed.
The tool auto-orients images, converts to sRGB, then strips metadata. It passes
an argument list directly to ProcessBuilder, uses generated intermediate names,
limits native pixel-cache resources, and enforces a 30-second timeout per image.

Every batch uses its own temporary directory. Generated intermediate files and
pixel caches are removed in a finally block, including failure and interruption
paths. Original inputs remain intact. Successful outputs remain in the requested
directory for subsequent tools. No output is published until all transformations
succeed; a publication failure rolls back outputs created by that call.

The managed-root check provides filesystem confinement, not tenant
authentication. The caller/service must select the authorized workspace and
restrict local OS access to it. Deploy ImageMagick with an appropriate host
security policy and keep native libraries patched.

Tests include command construction, content/path validation, output preservation,
batch failure/rollback, discovery, missing executables, and a real subprocess
timeout/pipe-drain fixture. A native image smoke test runs when ImageMagick 7 is
already installed; otherwise that test is skipped explicitly.
