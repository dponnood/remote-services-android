# Zashboard asset slot

This directory is the deliberately small APK asset slot for a future
Zashboard build. The current build contains only `index.html`, a visible
placeholder; it does **not** contain an upstream `dist` archive and does not
connect to a router.

Planned upstream metadata:

- Source: <https://github.com/Zephyruso/zashboard>
- License: MIT License (see `LICENSE`)
- Runtime boundary: the WebView loads this directory through AndroidX
  `WebViewAssetLoader`; OpenClash/Mihomo API traffic is expected to use the
  loopback gateway contract in `feature/web`.

When replacing the placeholder, copy the upstream `LICENSE` and any required
third-party notices unchanged next to the generated assets. Do not put an API
secret in a query string or checked-in asset.
