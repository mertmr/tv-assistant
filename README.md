# TV Assistant

A native Android TV / Fire OS assistant using the remote's keyboard dictation. The APK runs entirely on the TV; the Mac is needed only to build, install, or enable permissions on Fire TV. When connected to ChatGPT with plan usage enabled, every command is interpreted by AI using a bounded Responses API tool loop. Basic local parsing is available only when disconnected or when you explicitly enable local-only mode.

## Start using it

1. Open **TV Assistant**. Its system keyboard opens automatically.
2. Hold the remote mic and speak, then choose **Next** or **Search**.
3. When connected, AI interprets your full request and chooses tools. Check **Actions** for results; **Stop** cancels an AI stream and stops subsequent actions.
4. Open **Settings → Continue with ChatGPT** for AI tasks. Authenticate in the browser **on the TV** and choose whether to grant ChatGPT plan usage. The callback is bound to the TV's loopback address, so completing that URL on a phone would not reach the TV.
5. Return to TV Assistant. Settings shows the active connection, available model picker, request/step limits, local-only mode, spoken answers, and usage-management link.

## Raw microphone experiment (0.2.1)

**ChatGPT plan-sharing does not support the transcription API or audio input.** The existing
subscription login cannot be used for OpenAI speech-to-text. See the official
[preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
There is no hidden API-key/billing fallback, audio upload, or automatic command execution from this
experiment. Local Whisper is a possible future alternative, not an implemented feature.

**Mic test** is an explicit, local-only diagnostic: after confirmation and Android microphone
permission, it measures PCM sample counts/RMS/peak for at most eight seconds. It retains no recording,
obtains no account token, and makes no network request. Stop, leaving the activity, or process death
ends capture. Settings cycles the test source (VOICE_RECOGNITION/MIC/VOICE_COMMUNICATION/DEFAULT).
Optional Bluetooth SCO is **off by default**; when enabled it may temporarily change routing and
adds a maximum three-second connection wait. Attempts are cleaned up even on timeout/error.

Raw Fire TV remote audio access has **not** been tested for this implementation. Nonzero levels may
come from a different microphone, noise, or playback; they do not establish remote speech capture.
Holding Alexa can still start Amazon's system voice UI and interrupt the test. Keep keyboard dictation
until capture and routing are independently confirmed. No new TV installation/testing was performed
for 0.2.1 because another thread owns the device.

Examples that work without a ChatGPT connection:

- “Open Stremio and find The Wire” — looks for an exact title and opens its detail page; otherwise opens search results.
- “Open example dot com” — opens Silk if installed, otherwise the default browser.
- “Browse example.com” — opens the assistant's internal browser.
- “Search The Wire in Stremio” or “Find jazz on YouTube.”
- “Pause,” “resume,” “rewind,” or “next track” — requires an active player advertising that action and playback permission.
- “Set volume to 40 percent,” “mute,” or “volume down.”
- “Weather in Istanbul,” “calculate (12+8)*3/2,” or “what is the time.”
- “Set a timer for 2 minutes called tea,” “show notes,” “show routines,” or “run routine movie night.”
- “Open Wi-Fi settings,” “show device info,” “go back,” or “scroll down.” Navigation commands require the accessibility service.

Examples requiring ChatGPT:

- “I want to watch America’s Test Kitchen’s latest video on YouTube.”
- “Find the HBO series The Wire, open its page in Stremio, and show me season one.”
- “Find a good science article in the internal browser, read it, and summarize it.”
- “Save a movie-night routine that opens Stremio and sets the media volume to 30 percent.”
- “Remember that I prefer Stremio for shows and Silk for ordinary links.”

The AI can ask follow-up questions and retain the last few exchanges in memory. Connected mode always uses AI, including short app/show requests. **History → Use AI for current command** remains available; turn off local-only mode before using it. **Clear** starts a fresh conversation. Twenty recent commands and responses are stored locally and can be cleared in Settings.

## Tools

The app exposes **39 validated functions**, several supporting multiple actions:

| Area | Tools and operations |
| --- | --- |
| Local plans | Batch up to eight allowlisted tools with result references and parameters; resolve fresh scoped labels; reuse verified click/wait workflows and stop on drift |
| Apps and links | List installed apps, launch an app, search Stremio/YouTube/Netflix or a supported Android search activity, open HTTPS links in Silk/system/internal browser, web/video search |
| YouTube | Search public channel/video metadata, read newest uploads from a channel feed, request playback of an exact video, and check matching player-session evidence when available |
| Media | Search public Cinemeta movie/series metadata, open Stremio detail pages, list active sessions, play/pause/stop/next/previous/seek/rewind/fast-forward when supported |
| TV | Read/change/mute Android media volume; open general/network/Bluetooth/display/sound/app settings; read device/connectivity information |
| Screen vision | Optional user-approved screen session, screenshot inspection, taps and swipes using image coordinates, bounded batches of visible keyboard keys; local history retains only the newest screenshot; WebSocket chains reset after at most three images |
| Native navigation | Read visible screen labels, click identified nodes, type in non-password fields, scroll, move focus/select, supported Back/Home/Recents/notification actions |
| Browsing | Read page text and DOM nodes, filter nodes by label/link text, click/type, back/forward/reload/scroll in the internal WebView |
| Personal utilities | Clock, arithmetic, current weather/three-day forecast, local notes/preferences, saved routines, in-process timers, optional TV text-to-speech, bounded waits |

Tool arguments are checked against their definitions before execution. Each action reports what Android accepted. A launch or transport request does not prove that a page loaded or playback started; the AI is instructed to inspect results before claiming completion. Missing permissions and unsupported app actions return explicit errors.

## Usage control

Every command in connected mode consumes ChatGPT inference, including simple launches and utilities. Tool execution itself does not call OpenAI. Disconnected/local-only commands consume no ChatGPT inference; metadata and weather can still make public network requests.

The defaults are **4 AI requests and 10 tool steps per command**. Settings allows 1/2/4/6/8/12 AI requests and 4/8/10/16 tool steps. Nested routine and plan steps, including local scroll actions, count toward the tool budget. When two or more AI requests are configured, the last request is reserved for a factual completion/progress response. Responses are streamed with `store:false`; failed, incomplete, interrupted, and usage-limit streams are not accepted as successful completions. There is no automatic billing fallback, hidden retry loop, or unlimited background agent.

Version 0.2.0 opens one authenticated Responses WebSocket per task and sends only new tool results on continuations. Responses stay `store:false`. Before any generation is submitted, an unavailable WebSocket falls back to HTTPS/SSE; Android 6 uses HTTPS because the socket library's hostname verification requires API 24. A connection lost after submission stops the task instead of replaying uncertain work. Screenshots accumulate only within a bounded chain; after three images it starts a fresh chain on the same socket using local context with the latest image.

`action_plan` executes bounded JSON steps rather than arbitrary code. `ui_target` resolves a unique visible label in the expected package or HTTPS origin, with bounded polling/scrolling. Successful scoped click/wait plans can be cached under `cache_name` only if their final label is verified. `workflow` lists/runs/removes those paths. Cached paths re-resolve each label; they never reuse snapshots, coordinates, typing or saved parameter values. The AI still interprets every connected command and chooses whether a path is relevant. These tools cannot make a custom-rendered interface expose labels.

Task timing is shown in the action log and saved as aggregate `last_performance` numbers: elapsed/model/action milliseconds, request/tool/batch counts, cache hits, transport and image-chain resets. No screenshots or tokens are stored with those metrics. Faster real browsing has not yet been measured for this version.

Models are discovered from the signed-in account. The initial automatic choice prefers an available Luna model; you can choose another eligible model in Settings. The app does not possess a private API for reading remaining subscription percentages. **Manage ChatGPT usage** opens the official usage page. Plus plan usage is shared across participating apps; the app itself cannot impose a percentage cap on the server-side allowance.

## Screen vision and browser control

**Settings → Enable screen vision** starts Android’s screen-capture consent flow. Choose Continue, then accept the OS prompt on the TV. The session has a foreground notification and can be stopped in Settings. After a process restart or upgrade, enable it again. The app captures up to 1280 pixels on the long side and sends a JPEG to your selected model only for requested visual observations during a task (`screen_see`, or an observation after `screen_tap`/`screen_swipe`/`keyboard_keys`). Screenshots stay in memory; they are not stored in history or logs. The capture surface remains active during the approved session, and only the newest raw frame is retained, allowing capture of static screens without encoding every frame. Screenshots can include visible account/page details. Password-entry screens with accessible password nodes are excluded; custom-rendered screens may not expose those nodes. Protected screens may appear black. The selected ChatGPT model must support image inputs.

The AI can use `screen_tap` and `screen_swipe` with a fresh screenshot ID. Coordinates are scaled to the physical display; out-of-bounds, reused, expired, or changed-app snapshots are rejected. Visual actions return a fresh screenshot and native node snapshot when available, reducing separate observation requests. The model must inspect that result: gesture delivery alone does not confirm content success. Native coordinate actions require Android 7/API 24 or newer and navigation permission. Screen vision is optional; Android 6/API 23 can still use supported non-gesture tools. Visual tasks can need more model requests; the default remains four to control Plus usage.

The internal browser already supports DOM reading, clicks, text entry, scrolling, and navigation. Those tools are available without full-screen capture. For Silk or custom-rendered native screens, screen vision supplies visual observation and coordinate actions. This implements our own UI tools through function calling; OpenAI’s hosted `computer` tool is not used on the current ChatGPT plan-sharing route.

## Permissions on this Fire TV

The user explicitly authorized navigation and playback access. Existing accessibility services were preserved. Fire OS exposes a placeholder for the standard accessibility settings intent and no standard notification-access settings activity on this device, so ADB setup is provided.

```sh
adb -P 5038 connect 192.168.1.104:5555
python3 install.py --device 192.168.1.104:5555 --enable-navigation --enable-playback
```

The permission flags are opt-in. The script preserves other accessibility services and rebinds this app's service after an APK update. Use `--disable-navigation` and/or `--disable-playback` to revoke those permissions. The app also exposes standard permission settings for devices that support them. ADB debugging can be turned off after setup; the app does not use ADB at runtime.

Use `--no-launch` with the installer to update without changing the foreground app.

Instrumentation testing stops/restarts the target process and can leave Fire OS's accessibility binding disconnected. Run the installer with the navigation flag after device tests or an upgrade to rebind it. Do not run instrumentation while completing sign-in or an AI task.

## Privacy and boundaries

The app requests internet, connection-state, media-volume, and optional microphone permissions;
it does not request storage permission. Keyboard dictation remains the command-input path and uses
the TV keyboard's own voice processing. RECORD_AUDIO is requested at runtime only after you explicitly
start **Mic test**; that local diagnostic retains only aggregate levels, never audio files or transcripts.

OAuth tokens are encrypted with AES-GCM using Android Keystore, stored only in app-private preferences, and excluded from backups. The implementation validates PKCE/state, RS256 signatures/JWKS, issuer/audience/nonce/expiry, granted plan scope, and returning account identity; refresh is serialized and saves rotated tokens together. Each account keeps its own client ID and token set. Sign-out attempts remote revocation before clearing local credentials. No Codex desktop credentials are copied.

Commands, history, notes, and preferences remain in the app's local sandbox. The current request, relevant tool results, installed app inventory, and explicit preferences are sent to OpenAI during an AI task. Website and app text are treated as untrusted content. Password entry is excluded. Sensitive labeled actions such as purchases, deletion, permission grants, and external sends require a visible confirmation. These checks reduce accidental actions; they are not a guarantee for every unlabeled or custom app UI.

## Compatibility and limits

- Requires Android API **23+**, internet for AI/catalog/weather, and an installed browser for OAuth. Fire OS and Android TV are supported by the native Android architecture; **only the specific Fire TV below has been tested**.
- **Vega-based Fire TVs are not Android devices and cannot run this APK.** They need a separate client.
- Dictation still requires the assistant's system keyboard. The optional Alexa-button accessibility
  redirect dismisses Alexa's visible UI and opens TV Assistant; it may flash Alexa briefly and is
  not a privileged button/microphone replacement. Raw remote capture and independent speech-to-text
  are unverified. Mic test is foreground-only and never adds background recording.
- Each app must support its link/search/session interface or expose readable accessibility nodes. Secure/custom-rendered/DRM interfaces may not be automatable. YouTube on this Fire TV exposes no useful screen labels; exact video links work, but its profile chooser may require you to choose a profile yourself. The assistant can use optional screen vision to inspect and tap these interfaces. Without screen vision, it reports an unreadable screen as a limitation. YouTube metadata comes from public web pages and the public channel upload feed; site changes, consent pages or feed errors can block lookup, and the feed may include Shorts. There is no privileged shell, root, arbitrary JavaScript tool, universal app API, or silent app installation.
- Browser DOM tools operate in the internal WebView, with its own cookie/session store. Silk can be opened and may expose native labels, but its DOM and cookies are not available to the internal browser tools.
- Software volume may be fixed on HDMI devices. Changing an external television/receiver's IR/CEC volume is not implemented.
- Timers run in this app's process; they are not persistent wake-up alarms and do not survive process death or wake a sleeping TV. Spoken output depends on an installed TTS engine and the Settings toggle.
- This is a sideloaded development release, targeting API 28 for the tested Fire OS. Store distribution and additional device/OS testing remain separate work. The source is provided under MIT; ChatGPT plan access remains subject to OpenAI's current account eligibility and preview behavior.

## Build and tests

Requirements: Android SDK platform 36, build-tools 36.0.0, a JDK, Python 3, `adb`, and `rg` for test-result checking. Set `ANDROID_SDK_ROOT` for a nondefault SDK location.

```sh
bash build.sh
bash test-local.sh  # offline JVM checks; never contacts the TV
# The signed APK is written to dist/tv-assistant.apk
TV_DEVICE=192.168.1.104:5555 TV_ADB_PORT=5038 bash test.sh
# Focused metadata and read-only live AI test; does not open a video:
TV_TEST_GROUP=youtube TV_DEVICE=192.168.1.104:5555 TV_ADB_PORT=5038 bash test.sh
```

The build downloads checksum-pinned Java-WebSocket/SLF4J jars from Maven Central on first use. See THIRD_PARTY_NOTICES.md. Offline checks use a separate org.json jar; it is not included in the APK.

Build intermediates and the development signing key are in the project's ignored `build/` directory. Preserve that key for updates to the installed development app. A distributable production release should use a separately managed release key.

Source layout: `MainActivity` owns the TV UI; `LocalCommands`/`LocalFormatter` handle direct commands; `AssistantEngine` runs bounded Responses function calls; `Tools` contains schemas and execution; `NavigationService` reads/acts on native nodes; `BrowserActivity` supplies restricted DOM operations; `ChatAuth`/`Vault` handle OAuth and encrypted accounts; `Net` handles HTTPS and event streams; `MicrophoneProbe`/`MicrophoneLevels` provide the local-only mic diagnostic. The device test runner covers parsing, argument rejection, identity signatures, completion/failure handling, tool budgets, and real WebView actions.

## Verified device

Amazon **AFTKA**, Fire TV Stick 4K Max first generation, Fire OS **7.7.1.3**, Android 9/API 28. Amazon's `FireTVIME` delivered actual remote dictation into the earlier probe. The assistant's typed command/submission, calculator, app inventory, native screen reading, Stremio search, active playback-session access, and real internal browser DOM operations were exercised on-device. The test report is `reports/tv-assistant-test-results.txt`.

The user completed ChatGPT authorization on the TV. Live plan-backed inference successfully executed a device-info/calculator chain and a Stremio launch/wait/screen-read chain, correctly identifying The Wire. See `VALIDATION.md` for the current test count, latest live verification, and compatibility limits.

## Official integration references

- [OpenAI registration/sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [OpenAI account/session lifecycle and Plus usage](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [OpenAI model discovery and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Android accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Stremio deep links](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/deep-links.md)
