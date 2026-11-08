# Preverified server runtime

`xray` is the Linux amd64 executable from the official Xray-core v26.10.31 release. The original ZIP SHA-256 is `e4bd9119f50ae7c41658b1abd0679ae06f98bd864a2907ff33f2f2cf7081b214`; the extracted executable is pinned in `xray.sha256`. Preparation verifies the ZIP and ELF architecture on Windows. Installation verifies the executable again before activation. No compiler, package installation or image build runs on the VPS.

Node and nginx are official Docker Hub Linux amd64 images pinned by platform manifest digest in `deploy/compose.yaml`. The Node runtime mounts application source and Xray read-only. At the owner's explicit request, there is no custom container/heap RAM limit and available RAM does not block admission. Startup is observed for actual TLS health, process restarts and OOM kill events; failure prints scoped diagnostics and rolls back FamilyGram only. A successful startup check is not a real messaging or load test.
