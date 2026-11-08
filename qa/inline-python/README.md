# Inline Python implementation and checks

New production helpers are `FamilyInlinePython`, `FamilyPythonRunner` and
`FamilyPythonSnippets`. The editor and inline surface share controller.mjs. ChatTools and staging smali
are unchanged. The patch script defaults to an audit only:

```
python build-support/patch_inline_python.py
python qa/inline-python/check.py
node qa/inline-python/runner_races.mjs
```

The owner of the preview7 build can explicitly apply the four wrappers with
`python build-support/patch_inline_python.py --apply`. It checks hashes of all
four pinned methods, refuses double application, and does not rename register
references or modify any original instructions. Rebuild the patched cell DEX
as well as the overlay; an overlay-only repack does not install these hooks.

`onMeasure` calls the original implementation every time, then adds the measured
panel height to both measured height and `additionalPaddingHeight`. The original
method resets that padding from its own base calculation on every measure, so
it does not accumulate. Original `onLayout` subtracts that padding when deriving
message geometry; the wrapper then places the native panel in the reserved
space below. The original `onDraw` and touch handlers are untouched. The inherited
ViewGroup dispatch handles child buttons, selectable text and scroll views.

Before rebinding, a different message or code hash cancels the worker and clears
the old panel. Results are keyed by account slot plus current account owner,
dialog, message, entity offset and code SHA-256. Thus signing out and reusing an
account slot cannot expose a prior owner's results. The LRU has at most 32
entries and 1 MiB of UTF-8 output; no code or result is persisted. Each code and
output is bounded to 64 KiB; output viewports are capped at 240 dp. Explicit
python/py/python3 entities only are accepted. Protected, secret and TTL messages
are excluded. An exact first line `python` or `python3` is stripped when present.

Python starts only on a native button press while the plugin and access gate are
enabled. A 1px, transparent WebView is created lazily, serves only the existing
local runner assets, has no JavaScript interface and blocks network/file/content
access. The same sealed Pyodide worker as the advanced editor runs code. Each
worker callback has an epoch; replacement, cancellation, renderer death, cell
detach/rebind, plugin disable and window hide cannot attach a late result to a
different message. Only one inline job may execute at a time; starting another
one cancels the prior job. The advanced editor remains a separate existing tool.

Completed checks: Java compilation against Android35; pure Java extraction,
protection, identity and cache-bound tests; worker epoch/cancel/timeout tests;
four pinned wrapper dry-runs and wrong-pin/double-application rejection. A copy
of the full patched ChatMessageCell assembled successfully to DEX. This proves
syntax, not Android ART verification or the actual message layout.

## Coordinated physical QA (owner runs; Saved Messages only)

1. With Python disabled, a Python code message has no panel. Enable the plugin;
   return to Saved Messages and verify a native Run button under that message.
2. Send a synthetic formatted Python block `print(2 + 2)`. Tap Run. Expected:
   loading/running then selectable `4` under the same message, no editor opened,
   no additional message sent. Check incoming/outgoing bubble edge, time, reply
   selection and normal scrolling before/after the panel appears.
3. Run `print('row\\n' * 1000)`. Output scrolls inside a viewport no taller than
   approximately 240 dp. Long-press output can select/copy it.
4. Run `while True: pass`; Stop cancels promptly, otherwise worker timeout ends
   it. Scroll the cell out of view during execution, then return: no stale
   running button, no result in a reused unrelated message cell.
5. Put two Python blocks into one synthetic message. Each button/result belongs
   to its own block. Starting the second cancels the first if still running.
6. Edit a block after running. The prior result must not be shown for new code;
   show the changed-code hint until Run is pressed again. Other chats/accounts
   must never show that result (do not send a test to another person).
7. Disable the plugin while output exists: panels disappear and cached outputs
   are cleared. Re-enable and verify nothing executes automatically.
8. Repeat on a narrow screen/font scaling and dark theme. Existing context-menu
   advanced editor still opens as a separate optional action.

No physical-device action or Saved Messages send has been performed by this
subagent for this change. Production staging/application is owned by root.

## Python error and time budgets (2026-11-08)

Both surfaces serve the shared `controller.mjs` asset. Include this new file when staging
assets. Bootstrap has a 30-second worker deadline and reports a distinct environment-start
error. Only the worker's ready event starts the 10-second code-execution timer. Native
watchdogs allow 35 seconds for bootstrap (including page startup) and 10 seconds for execution;
they destroy a stuck WebView. The editor's initial page loading also has a 30-second guard.
Stop works during loading and execution. Worker identity and native job epochs reject stale
results, errors and ready events after cancellation or replacement.

Python exceptions are `error` events with phase `run` and the original Python traceback,
exception class and source line; successful output is a `result` event. Output overflow is
bounded and reported without throwing again from the exception-reporting path. Environment
load failure, worker errors, Python errors and the 10-second execution timeout retain distinct
reasons. No code or output is persisted or sent over the network.

`node qa/inline-python/runner_races.mjs` checks both shared-controller prefixes with controlled
clocks, then runs the exact shipped Pyodide assets in a Node worker: print, SyntaxError,
NameError, ZeroDivisionError, traceback lines and output overflow. It also terminates an actual
infinite Wasm worker after 10 seconds. This is local runtime evidence, not a phone WebView test.
The Node-only harness substitutes a filesystem asset path for Android's sealed HTTPS asset URL;
Python execution and exception handling use the production worker source unchanged.
