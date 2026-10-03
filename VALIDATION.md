# Version 0.2.1 — local microphone diagnostic (coding-only)

- Removed the speculative OAuth transcription upload: OpenAI explicitly excludes audio input and
  transcription from ChatGPT plan-sharing. No paid API backend or browser-token workaround added.
- Added opt-in local **Mic test** with RECORD_AUDIO consent, bounded nonblocking capture, aggregate
  sample/RMS/peak measurements, selectable source, optional SCO (off by default), cancellation and
  lifecycle cleanup. It cannot upload audio, access account credentials or submit a command.
- APK built and signature-verified with the existing signing key. 20 offline JVM checks passed,
  including five PCM-level/preference regression checks. Report: reports/local-speed-checks.txt.
- No TV commands, microphone test, STT request, or new live AI task was performed for this continuation.
  Actual remote audio access, routing, permission/lifecycle behavior and speech recognition remain
  unverified. Another thread owns the TV; install/testing require renewed user authorization.

# Version 0.2.0 — coding-only validation

- Final APK built, aligned, signed with the preserved key, and signature-verified locally.
- 15 offline JVM checks passed: WebSocket payload/delta/reset shape, shared SSE/WebSocket event assembly, failed/incomplete/abrupt/cancelled streams, plan references/parameters, recursion and size guards, failed/partial/cancelled action stopping, label ambiguity/password/disabled guards, scope/origin guards, and reusable-path restrictions. Report: reports/local-speed-checks.txt. These do not establish a live WebSocket connection or actual device workflow performance.
- Old-app baseline before changes: device/clock/calculation task took 17,960 ms and four AI requests. Report: reports/benchmark-before.txt. No after-change timing is available.
- An intermediate APK labeled 0.1.5/code6 and test APK installed before the user said the TV is in use by another thread. The running command was cancelled immediately. No successful new on-device test result was obtained; no further TV commands were sent. Final source refinements and APK remain local.
- New instrumentation checks are prepared for actual WebView plan execution, verified reuse, and stopping after DOM drift. Authenticated WebSocket behavior on the TV, compatibility beyond the baseline device, and single-command Silk completion remain pending.

The following report describes version 0.1.4 and earlier, not the new version.

# Validation — 2026-10-02

Development APK `dev.mert.tvassistant` version 0.1.4 is installed on AFTKA, Fire OS 7.7.1.3 / Android API 28.

## Completed

- APK compiled, aligned, signed and signature-verified.
- **83 full on-device checks passed, 0 failed** after the vision update. The suite includes screenshot request shape, image retention, coordinate mapping, stale/out-of-bounds/changed-app/reused snapshot rejection, schema validation, OAuth/stream handling, live public YouTube lookup, and real WebView actions. Report: `reports/tv-assistant-test-results.txt`. Earlier read-only live AI YouTube lookup: `reports/tv-assistant-youtube-test-results.txt`.
- Actual remote dictation previously reached the keyboard probe: “Open stremio and the wire.” The assistant uses the same keyboard path.
- Assistant text submission and calculator execution were checked live.
- Launchable app inventory and device state were verified.
- Playback permission was enabled with explicit user consent; Stremio's active media session was read successfully. Transport commands remain dependent on advertised player support.
- Navigation permission was enabled with explicit user consent, preserving the existing accessibility service. Reading visible native labels was verified. An empty screen during the keyboard-hide transition led to a bounded retry fix.
- Stremio search was exercised. The final exact-title route opened **The Wire (2002–2008)** with **Season 1** and its episodes visible, using public catalog metadata and a verified app deep link.
- Real WebView tests verified extraction, text entry, clicks, action results, node filtering, stale snapshot rejection, password exclusion, and disabled-element rejection.
- Test-only APK was removed after verification. The main assistant remains installed with navigation and playback enabled.
- The user completed ChatGPT sign-in and plan-usage consent in Silk on the TV. OAuth token exchange and account-specific model discovery succeeded. No desktop Codex credentials were read or reused.
- Live ChatGPT inference called `device_info` and `calculate`, then correctly answered Amazon AFTKA / Android 9 / API 28 and 7 × 8 = 56.
- A second live AI task called `open_app`, `wait`, and `screen_read`, then correctly identified Stremio’s visible title as **The Wire (2002–2008)**. It completed within the configured request/tool bounds.
- The live plan-sharing stream supplied complete output items in `response.output_item.done` while its terminal envelope had an empty output array. The parser now preserves those completed items; regression tests verify restoration and prevent duplication.

## AI routing correction

The original direct-command shortcut misread “Open up Stremio and the show Ted Lasso” as a title containing “the show.” Version 0.1.1 removes that shortcut from connected mode: every connected request goes to the model for intent interpretation and tool selection. Local parsing is limited to disconnected or explicitly selected local-only mode. The request and tool budgets still apply.

The exact original command was retested live. AI selected `find_media` with the actual title Ted Lasso, then `media_details` with catalog identifier `tt10986410`. Stremio displayed **Ted Lasso (2020–)**; the assistant answered “Opened Ted Lasso in Stremio.” The show screen was independently confirmed through ADB; the model did not call `screen_read` in this particular task.

## YouTube lookup and playback correction

The latest request was “I want to watch the American test kitchen latest video on YouTube.” The original AI stopped at search, and a screen read returned an empty node list. YouTube’s profile chooser and player are custom-rendered and expose no useful native labels on this Fire TV.

Version 0.1.2 adds `youtube_search`, `youtube_latest`, and `youtube_play`. AI resolves real channel IDs from public YouTube search metadata, reads the channel’s public upload feed for publication ordering, and requests the exact returned video through the installed YouTube app. The tool reports matching active-player evidence when available; otherwise it reports unverified playback. Empty native screen reads now return an explicit visibility limitation. No screenshot capture or vision permission was added.

A direct watch link was exercised on the device after selecting the existing highlighted YouTube profile. The user confirmed it worked and then switched to another video. Subsequent validation is read-only to preserve that playback. The first live feed test revealed an unprefixed channel ID in the root feed element; canonicalization fixes that format and has a regression test. The final focused suite passed 11/11 checks. Live AI resolved America’s Test Kitchen and returned “Burn Your Bell Peppers for Maximum Flavor,” published 2026-10-01T16:29:57+00:00, from the feed. This item is a Short. The complete new AI lookup-to-playback chain was not rerun after the user switched videos; direct-link playback had already been confirmed separately. Public feeds may include Shorts and public HTML can change or be unavailable.

## Visual UI tools and native YouTube search

The next failed request was “Open up videos about baldur skate 3 on YouTube.” AI correctly interpreted Baldur’s Gate 3 but chose a web search URL that did not reliably open the native results screen. The installed Fire TV app accepts `youtube://search?query=...`; this route displayed Baldur’s Gate 3 results on-device. A coordinate tap in that custom-rendered results screen opened a video, confirming that touch input works despite the absence of accessible labels.

Version 0.1.3 adds a user-approved MediaProjection foreground session plus `screen_see`, `screen_tap`, and `screen_swipe`. Screenshots are JPEG images in memory, sent to the selected model only during requested task observations. Only the newest screenshot is retained in the next model request. Coordinate actions are bounded by image dimensions and require a fresh, unused snapshot from the same foreground package. Accessible password-entry screens are excluded. Existing internal-browser DOM tools remain available. No hosted OpenAI computer-use tool or Mac-dependent automation is required for these app tools.

All 83 checks passed. The user enabled Android screen capture. A live AI task subsequently called `screen_see`, visually located Apps, clicked it with `screen_tap`, and called `screen_see` again. The model correctly confirmed the installed-app list; the actual TV UI independently showed it. This verifies native image acquisition, plan-sharing image input, model interpretation, and accessibility gesture execution together. Android 6 clients can use non-gesture tools; coordinate gestures require API 24 or later. Settings offers bounded 8/12-request options for longer visual tasks, while the default remains four.

## Static-screen capture and Silk retest

Version 0.1.4 fixes capture timing: the reader retains one latest raw image with a bounded three-image queue. Static interfaces no longer require a new compositor update after the tool request. Frames are JPEG-encoded only for task observations. Visual actions now return a post-action image plus native node IDs; screen observations include both visual and native snapshots. Native reads choose the active/focused application window when Fire TV's keyboard takes over the accessibility root, preserving access to editable web fields. Visual observations include a compact native-node list with editable/focused fields first, avoiding loss of structured field IDs on large pages. Native tools wait at most two seconds for a temporarily disconnected, already-enabled accessibility service. The AI prompt explicitly preserves the requested browser and uses a visible site search instead of inventing a search URL.

A `keyboard_keys` tool batches up to 32 visible keyboard-key gestures from one fresh screenshot, stopping on cancellation, failed delivery, hidden keyboard, or changed application. It excludes accessible password fields and returns a fresh observation. This handles Silk's separate keyboard editing buffer without a model request for each letter. The app now has 36 tools.

The 83-test suite passed again with this final implementation. The real WebView readiness test now waits for its actual page state instead of relying on an 800 ms delay; an initial run exposed that timing race.

The original long Silk instruction first reached the intended website and a Google result for The Wire season 1 episode 1, but hit the 12-request budget before clicking it. The selected TV settings are 12 requests/16 steps for this longer test; fresh installs still default to 4/10.

The final live test used `keyboard_keys` successfully: the site showed real search results for The Wire. The original command still exhausted 12 requests at the results screen. A continuation task used the app's own screenshots, taps, and swipes to open the show, scroll its season list, and open **The Wire 1. Sezon 1. Bölüm**. An independent ADB screenshot confirmed the episode breadcrumb and heading. The driver restored Silk to the foreground before the continuation; no show/episode clicks were performed through ADB. The continuation also hit its limit after its final observation, so no final AI success message was obtained. Episode-page navigation is verified; playback and reliable single-command completion are not. The TV is left on the episode page with the approved screen session active.

## Scope of the evidence

Other Android TV models, other Fire OS versions, Vega clients, external IR/CEC volume, app-specific search routes beyond the exercised Stremio route, and every possible accessibility UI are not verified. These boundaries are documented in README.md.

## Codex allowance

The five-hour window was 19% used at the start of the full implementation and 99% used at the final check during the live Silk retest (1% remaining). No reset credits or paid credits were used. AI tasks in the TV app require separate explicit account consent and have configured request/tool bounds.
