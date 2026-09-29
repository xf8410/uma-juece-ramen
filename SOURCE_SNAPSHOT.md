# Source provenance

- Android source baseline: [`xf8410/uma-juece`](https://github.com/xf8410/uma-juece) at `61e5d9c19134193d912b310a7ffc7faf91ed8409`.
- Ramen mechanics/data upstream: [`xulai1001/umaai-rs`](https://github.com/xulai1001/umaai-rs).
- This repository intentionally keeps only the Ramen scenario. Release keeps `com.umaai.assistant`; the handoff debug build uses `com.umaai.assistant.dev` and can coexist with the general build.

Files were originally recreated through GitHub's per-file content API. Git history from that source repository was not copied. The current handoff is delivered as a Git branch with pinned dependency patches.

The 2026-09-29 handoff uses a pinned upstream revision plus a reviewed runtime patch, recorded in `engine/source-lock.json`. Collector changes are supplied separately under `docs/handoff/`. See [HANDOFF.md](HANDOFF.md) for the current implementation and remaining validation work.
