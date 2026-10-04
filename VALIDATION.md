# User-named site containment — Fire TV validation

Measured on hdfilmcehennemi.nl. The exact prompt "go to https://www.hdfilmcehennemi.nl/ and
find South Park S3E5" took **72,102 ms, 12 requests, 15 action steps, 60,191 ms in model
responses**. It never reached the episode. Two real defects, not a latency problem:

1. It called `web_search` twice and navigated the TV to google.com. The user named one site;
   sending their title to a third-party search engine is not searching where they asked, and a
   search page is not the destination.
2. It re-opened the same URL four times and re-ran searches after the site's own search had
   already answered, then ended on an unrelated Google results page.

Ground truth from the device (curl is 403; the site only serves real browser requests): the
site's own search for "South Park" returns only the film *South Park: The End Of Obesity*; the
series index `/yabancidiziizle-5/` has no South Park entry; `/dizi/south-park/` and
`/dizi/south-park-izle/` both return the site's own 404 page. Site search and the series index
agree the series is not carried there, so no honest success was possible. The catalog uses
`/dizi/<slug>/sezon-N/bolum-M/`; curl 403 confirms bot blocking is not a factor the assistant
can route around, and no URL was guessed or constructed by the product.

Instructions now require: stay on the site the user named and never substitute a search engine
or another domain; search once with the plain title and read the result nodes; treat a film,
remake or echoed query heading as a non-match; never re-run the same search or re-open a URL
already read; check the site's own category/index links at most once; never construct or guess
a URL or slug, only follow observed hrefs; and converge, reporting honestly if the destination
is not on screen by the halfway point of the budget.

Re-measured on the same prompt and TV: **29,358 ms, 4 requests, 5 action steps (3 batched),
24,274 ms in model responses**, and it never left hdfilmcehennemi.nl. It reported the film/
series mismatch and stopped. That is 2.5x faster with 3x fewer requests, and it still honestly
reports that the episode is not available. Traces: `reports/browser-southpark-s3e5.txt` (before)
and `reports/browser-southpark-improved.txt` (after).

These are single measurements on one site, not a general latency guarantee. A positive control
is still needed on a site that does carry the requested episode.

Regression: 43 offline JVM checks pass. Device suite 110/111; the one failure is
"YouTube live upload feed returns exact playable ID" with `Public metadata request failed:
HTTP 404`. Verified unrelated: a direct `curl` of
`https://www.youtube.com/feeds/videos.xml?channel_id=UCxAS_aK7sS2x_bqnlJHDSHw` also returns
HTTP 404, so the public endpoint used by the test fixture is dead upstream. This change edits
only prompt text in `AssistantEngine` and cannot affect that fetch. A working replacement channel
ID is needed before that check can pass.

# Stremio direct episode selection — Fire TV validation

`media_details` now accepts optional `season` and `episode` together. It fetches the real
Cinemeta series metadata and selects the matching video ID, rather than constructing an ID
or stepping through every season. Missing/ambiguous episodes fail without launching a guessed
destination. The documented episode deep link uses `autoPlay=false`; no stream is selected.
The assistant remains responsible for interpreting intent and choosing the correct title.

Stremio can reuse a detail activity and update streams while leaving the previous episode
heading visible. A live S3E6 → S3E5 test caught this (the model incorrectly claimed success;
`reports/southpark-episode-final.txt` retains that failed run). Episode links now clear the
Stremio task before launching a fresh activity, without force-stopping it or clearing saved
data. Verification requires the actual Stremio series and selected-video heading view IDs,
the requested S/E code, and the catalog episode title. Stream filenames/list rows do not count.
An unverified selection stops dependent action-plan steps and is visibly reported as unverified.

The exact command “Open stremio and find south park episodes 5 season 3” previously failed
in 80,987 ms / 8 requests / 16 action steps. The fixed live episode-switching test opened
S3E6 “Sexual Harassment Panda” in 9,167 ms, then S3E5 “Tweek vs. Craig” in 8,696 ms; each used
2 requests and 2 action steps with an explicit final-screen assertion. See
`reports/southpark-episode-six-fresh.txt` and `reports/southpark-episode-five-fresh.txt`.
Earlier direct-link tests also passed for The Wire S1E1 and for South Park under the original
4-request limit (13,562 ms cold / 9,816 ms warm); `reports/episode-fix-wire.txt` and
`reports/southpark-episode-fixed.txt`. These are individual measurements, not a latency guarantee.
The final fresh-task build also passed the exact command with the original 4-request cap
in 8,765 ms / 3 requests / 2 actions (`reports/southpark-episode-fresh-budget4.txt`). The saved
8-request setting was restored afterward, and navigation reconnected after instrumentation.
Actual account model: `gpt-5.6-sol`, not the previously assumed GPT-6.1 label.

Regression checks: 43 offline JVM checks and 111 Fire TV device checks passed. New coverage
includes paired/integer arguments, missing/duplicate/mismatched catalog entries, real ID reuse,
autoplay disabled, strict heading verification, requested-versus-verified UI reporting, and
stopping dependent actions after unverified selection. Browsing tests now accept expected
series/season/episode arguments and fail if the final selected episode differs.

# Automatic website search learning — 2026-10-03, 0.2.4/code11

Verified public search action plans now learn without an explicit cache_name. The learner
accepts optional click + type + terminal body-text wait on one literal HTTPS origin, with
verified field delivery and a result heading containing the query exactly once. It replaces
literal or parameterized query/verification values with standard query/result parameters;
only static controls and surrounding verification wording persist. A stable site/control
identity refreshes the same entry for later searches. Workflow replay takes query only and
derives result verification locally; callers cannot override it with weaker text. AI still
interprets intent, chooses relevant workflows, and selects real links from fresh results.
Explicitly named caches retain their existing behavior. Cache capacity remains20.

Validation: signed APK built using the preserved key;38 offline JVM checks and108 full API34
emulator checks passed. New checks exercise automatic literal/parameterized learning, no
retained query, stable identity, replay for a different title, missing/invalid query, overriding
a supplied result label, failed/unverified/unrelated headings, origin mismatch and control
drift before typing. The real asynchronous WebView fixture learned Severance and replayed
Better Call Saul with query only, verifying its changed results. Existing ambiguity, timeout,
cancellation and native/browser guards also passed. Reports: local-speed-checks.txt and
emulator-api34-results.txt. Native vision suite was not rerun because capture code is unchanged.

This version was not installed or benchmarked on the physical Fire TV. It remains on0.2.3;
previous26.3s command/cache_hits0 measurement does not demonstrate learned reuse. Automatic
learning currently covers this bounded public search shape; menu paths still use explicitly
saved verified workflows. Logged-in Silk, arbitrary multi-page actions, playback and remote
voice were not retested. APK:dist/tv-assistant.apk. Emulator stopped after validation.

# Public browser speed work — 2026-10-03, 0.2.3/code10

Implemented the three recommended changes: web_page participates in bounded action_plan;
ui_target supports a public HTTPS-origin scope with fresh click/type/wait labels; verified
public searches can be cached with parameter-only typing and final verification. Opening a
site returns at most five relevant saved public workflows, avoiding a separate lookup call.
No natural-language intent parser was introduced: AI still interprets connected commands and
chooses tools/workflows. Raw IDs, snapshots, coordinates and supplied parameter values are not
cached. Reuse validates the live origin and fresh unique targets; failures stop dependent steps.

Readiness now observes content changes/stability or expected body text with bounded cancellable
polling. Field values cannot satisfy body-text waits. Public label typing verifies immediately,
allowing a following batched wait to observe AJAX results. A model-emitted exact $param.query
shorthand is resolved/preflighted as a parameter (canonical JSON object form is also supported),
preventing literal placeholders from being typed. Missing parameters reject the whole plan before
its first action. Large plan outputs retain every step status and final observations/errors;
previous generic trimming had removed final results and forced redundant reads.

Validation: 34 offline JVM checks; 106 full Android TV/API34 emulator checks; 12 actual native/vision
checks (the native run preceded the final prompt/context-only refinements). Final signed APK built
with preserved key, installed on AFTKA/API28. Emulator stopped. The asynchronous search fixture
completed its three-step plan in approximately0.72s including a650ms site delay. Cached reuse with
a different title, no retained parameter values, origin/label drift, ambiguity, timeout and
cancellation were verified. Final suite reports pass. Earlier overlapping test execution stopped
one process; later sequential runs passed. One unrelated live public YouTube search assertion was
transiently unsuccessful; the subsequent full suite passed.

The original full command still opens The Wire S1E1 without a playback action. Final repeat with
normal screen vision active: 26,326ms, five AI requests, six tool steps, three batched steps,
20,169ms model time, 5,242ms action time, WebSocket, zero image resets, zero cache hits. Baseline
was31,805ms/seven requests: about17% shorter elapsed time and32% lower action time in these individual
samples. No guarantee/median/p95 measurement is established. The live AI rebuilt the search plan;
actual cache reuse is emulator-verified, not observed on this TV. A preceding final-build sample
took40,866ms/six requests after an out-of-range timeout was rejected before actions. Initial
runs took44,188ms and40,334ms while placeholder/observation issues were being repaired. One42,366ms
run was disturbed by the driver bringing the assistant forward during final verification and is
excluded from performance claims. All individual metrics: reports/public-browser-speed.json.

Reports: firetv-speed-warm-ui.txt (assistant trace/metrics), firetv-speed-warm-episode.txt (actual
Silk episode before viewing the report), firetv-speed-left-episode.txt (restored last episode page).
Driver entered only the command, enabled normal approved capture, read observations, and restored
Silk's last page after viewing the assistant report; it did not select show/episode links during
the AI task. Navigation/playback and approved screen vision are active; VoicePilot preserved.
Remote dictation, playback, other sites/devices and universal reliability were not retested.

Remaining recommendations to remind the user at completion: benchmark fastest eligible models
and reasoning settings; keep connections/browser startup warm between commands; improve prompt
cache reuse. Watch WebMCP adoption; Ultrafast is an optional paid API path, not established as
available through this Plus sign-in route. Live workflow selection also needs further tuning.
No separate billing, reset credits, desktop credentials or audio uploads were used. Last allowance:
86% five-hour used (14% remaining), weekly15% used.

# Silk episode fix — 2026-10-03, version 0.2.2/code9

**The complete single-command task now succeeds.** On AFTKA/Fire OS7/API28, the exact command
“Open up Silk and navigate to hdfilmcehennemi.nl. Find The Wire in the site search and open
season 1 episode 1. Do not start playback.” completed in **31,805 ms, 7 AI requests, 6 tools**
over the live WebSocket connection. AI used web_page to open the real public site, click its
observed search input, type the title through DOM input/change events, inspect returned results
and the show page's actual episode href, then open that href in Silk and inspect screen_see.
No search/episode route or show identifier is hardcoded in the product. No ADB show/episode
selection was used during the AI task. The driver typed the command and independently read the final UI.
After viewing the assistant report, Silk's launcher opened its home surface; the driver restored its
already-verified last webpage. The episode title was confirmed again after that restoration.

Silk's actual title/breadcrumb/heading confirmed **The Wire 1. Sezon 1. Bölüm**. The TV is left
on that episode page, with navigation/playback and normal approved screen vision active. No
playback action was sent; playback itself was not tested. Reports: reports/firetv-silk-success-ui.txt,
reports/firetv-silk-episode-screen.txt, reports/firetv-the-wire-episode.png. Separate real-site search
verification took 19,648 ms/four requests/three tools and returned The Wire and Outside the Wire;
reports/firetv-public-search-ui.txt.

Changes: new generic web_page tool (40 tools total) uses one background app WebView to inspect
public HTTPS pages without replacing the foreground browser. It supports fresh-node read/click/type
and actual hrefs, with password/disabled/stale-node checks, sensitive-action approval, restricted
URLs and SSL-error cancellation. It does not access or copy Silk cookies; it uses this app's WebView
store. It is not an arbitrary-JavaScript tool. A 1280px CSS viewport avoids the mobile layout hiding
search, and pointer SVG/ARIA controls can be inspected/clicked. Media requires a user gesture.

Also: native text replacement in Silk is rejected with guidance to use real input; keyboard_keys
supports a separately observed Next/Search submit point with one bounded retry while the same
keyboard remains open. Navigation rebinds are retried only before an action starts. Native clicks,
scrolls and URL opens return observations when vision is active; scroll count1–4 shares the tool
budget. Zero-size native nodes no longer fill observations. ui_target returns the latest native
snapshot after verification. Partial plans report completed/requested counts; budget-final prompts
no longer describe the configured cap as missing permission.

Validation: final **99 full emulator checks +12 native/vision checks =111 passed**, including actual
public DOM input/change events, real result hrefs, TV viewport, SVG clicks, stale/password/disabled
rejection, and usable post-target native snapshots. **27 local JVM checks passed**, including integer
scroll preflight. Signed APK built with the preserved key and installed on the TV. One test initially
compared the foreground activity after requesting its destruction; the fixture now keeps that activity
alive until the isolation assertion is complete. Final report: reports/emulator-api34-results.txt,
reports/emulator-native-results.txt, reports/local-speed-checks.txt. Emulator stopped afterward.

Earlier attempts in this turn still failed: keyboard-only input intermittently left popular results
unchanged; a successful search reached the show page but exhausted the request cap while scrolling.
The first public-page viewport hid the search field. These failures were preserved in reports/.
The final public-page route avoids that unreliable native-input path for this public site. Signed-in
Silk pages still require native/visual navigation; other websites and universal reliability are not
established by one successful full test. The current TV budgets remain12 requests/16 steps.

Codex allowance at completion: 53% five-hour used (47% remaining), weekly9% used. No reset/paid
credits, separate API billing, desktop credentials, audio uploads or account-token copies were used.

# Fire TV validation — 2026-10-03, version 0.2.1/code8

The user explicitly freed the physical TV: “ok lets try on firetv”. Reconnected AFTKA at
192.168.1.104:5555 after restarting the stale, isolated ADB server on port 5038. Updated with
the preserved signing key and existing TV account. Navigation/playback enabled while preserving
VoicePilot. Test-only APK removed; screen vision restarted via the normal app/Android consent flow.
The updated assistant is foreground and ready. The emulator remains stopped.

- **94 full checks passed on Fire OS/API28** (reports/tv-assistant-test-results.txt). The old
  0.1.4 report is preserved as reports/firetv-0.1.4-results.txt. This suite ran before the tiny
  keyboard reconnection guard below; the final build and all 26 offline checks passed afterward.
- Live authenticated WebSocket continuation worked with the TV app's own account. The read-only
  device/clock/7*8 task used one action_plan and two requests: **9,615 ms**, versus the prior
  **17,960 ms/four requests**. About 46% less elapsed time in one uncontrolled sample; not a general
  performance guarantee. reports/benchmark-results.txt and reports/benchmark-before.txt.
- Full Silk request (“Open up Silk and navigate to hdfilmcehennemi.nl. Find The Wire in the site
  search and open season 1 episode 1. Do not start playback.”) did **not** reach the episode.
  First attempt: 65,403 ms, 12 requests, 11 tools. keyboard_keys missed a transient navigation
  service rebind. Added the existing bounded two-second rebind wait to keyboard_keys and a
  generic lifecycle log. Final APK built, signed, installed; 26 offline checks passed.
- Retry: 41,295 ms, 12 requests, 11 tools, WebSocket with one image-context reset. No keyboard_keys
  call occurred, so live batch-key retry is still unverified. Native typing put “The Wire” in the
  field, but the AJAX results still showed popular titles. The assistant exhausted its request
  budget and reported incomplete progress. Its claim that navigation was unavailable was not
  supported by the retry trace; action refusal/limits must be reported more accurately. Real
  IME key entry, search-result verification and more effective batching remain priorities.
  Redacted logs: reports/firetv-silk-0.2.1-ui.txt and reports/firetv-silk-retry-ui.txt.
- “Open Stremio and the TV show The Wire.” **worked** in 33,043 ms/four AI requests/four tools,
  including two batched steps. AI recovered from find_media HTTP504 using search_app and then
  screen_read. Independently confirmed The Wire (2002–2008), Season 1, S01E01 The Target in the
  actual Stremio UI. Playback was not requested or verified. reports/firetv-stremio-ui.txt.
- Remote microphone capture/dictation was not retested in this turn; commands were typed through
  the real FireTVIME. No audio upload, paid billing, resets, desktop credentials or copied account
  secrets were used. Final Codex allowance: 22% five-hour used (78% remaining), weekly5% used.

Follow up on Silk by improving general real-IME input and observation/batching, not a fixed
show/site parser. Keep connected routing entirely AI-driven. The UI action log also currently
formats stopped action_plan results as “Done”; distinguish completed_steps/stopped_early.

# Android TV emulator validation — 2026-10-03

The user authorized using an emulator when needed. Installed Android TV API 34 ARM64 SDK image
and created the project-owned TVAssistant_API34 AVD under ignored build/avd. emulator.py verifies
emulator-5560 identity before install/test/open/stop; physical Fire TV remains untouched. Host
audio is disabled. No account credentials were copied and no ChatGPT inference requests were used.

Final validation: **94 full emulator checks + 11 actual native/vision checks passed**, plus **26
local JVM checks passed**. Reports: reports/emulator-api34-results.txt,
reports/emulator-native-results.txt, reports/local-speed-checks.txt. Actual browser batching/cache
reuse/drift stopping, native controls/text input, native cached paths, Android consent, nonblank
screen frames and visual taps were exercised. An initial immediate text observation failed during
a transition; the test now waits for observable text. Native ui_target typing also searched for the
old value label after changing it: fixed to follow resource identity/bounds and poll for the new
value. Final capture/tap checks passed. Initial failures are not evidence of universal reliability.

The current product remains 0.2.1/code8; final APK is dist/tv-assistant.apk and has been installed
only into this emulator. Physical device permission/state is owned by the other thread. Live
Responses WebSocket behavior, actual AI/browser task speedup, Silk/Alexa/remote microphone behavior,
and the original single-command episode flow remain unverified for this build. Use the emulator
for most future development. No physical-TV access has been reauthorized.

# 2026-10-03 speed review — local only

Continued with no TV commands while waiting for renewed device access. WebSocket payload creation
now copies only request settings before appending the actual input/delta, avoiding serialization of
old screenshot/history data that was immediately discarded. Tool schema validation is shared and
recursive; every plan literal and supplied parameter is checked before the first action, and
result references are checked again after resolution. Incorrect text verification stops dependent
steps. Minimum-API-compatible finite-number checks are used.

APK build/signature verification, instrumentation source compilation and **25 offline JVM checks**
passed. Live WebSocket behavior, real browsing speed and device workflows remain pending.
The current microphone diagnostic changes were preserved. The Mac already has emulator/AVD tools
and a phone AVD; the SDK catalog offers Android TV ARM64 images (API 31/33/34/36). No TV emulator was
installed or started. An Android TV AVD is the proposed independent development environment;
Silk/Alexa/Fire remote behavior still needs the physical Fire TV.

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
