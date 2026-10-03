package dev.mert.tvassistant;

import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, bounded, local-only mic-access test. Never retains audio or obtains an auth token. */
final class MicrophoneProbe {
  static final String PREF_SOURCE = "mic_probe_source";
  static final String PREF_SCO = "mic_probe_sco";
  private static final String[] SOURCES = {"VOICE_RECOGNITION", "MIC", "VOICE_COMMUNICATION", "DEFAULT"};
  private static final int[] SOURCE_IDS = {
    MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
    MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.DEFAULT
  };
  private static final long CAPTURE_MS = 8000;

  interface Listener {
    void microphoneState(String value);
    void microphoneResult(String report);
    void microphoneError(String message);
  }

  private static final class Run {
    final AtomicBoolean cancelled = new AtomicBoolean();
  }

  private final Context context;
  private final Handler ui;
  private final Listener listener;
  // Accessed only on the main thread, including callback delivery.
  private Run current;

  MicrophoneProbe(Context context, Handler ui, Listener listener) {
    this.context = context.getApplicationContext();
    this.ui = ui;
    this.listener = listener;
  }

  static int sourceIndex(int index) {
    return index >= 0 && index < SOURCES.length ? index : 0;
  }

  static String sourceName(int index) { return SOURCES[sourceIndex(index)]; }
  static int nextSource(int index) { return (sourceIndex(index) + 1) % SOURCES.length; }

  static boolean permitted(Context context) {
    return context.checkSelfPermission("android.permission.RECORD_AUDIO")
        == PackageManager.PERMISSION_GRANTED;
  }

  boolean active() { return current != null; }

  void cancel() {
    if (current != null) current.cancelled.set(true);
    // Keep the session busy until worker cleanup completes; a new test must not race SCO teardown.
  }

  void start(int source, boolean useSco) {
    if (active()) return;
    if (!permitted(context)) {
      listener.microphoneError("Microphone permission was not granted.");
      return;
    }
    Run run = new Run();
    current = run;
    new Thread(() -> {
      String report = null, error = null;
      try {
        report = capture(run, sourceIndex(source), useSco);
      } catch (Exception e) {
        error = e instanceof SecurityException
            ? "Fire OS denied microphone access. Use the keyboard mic as fallback."
            : "Microphone test failed: " + e.getClass().getSimpleName();
      }
      final String result = report, failure = error;
      ui.post(() -> {
        if (current != run) return;
        current = null;
        if (run.cancelled.get()) return; // Suppress late callbacks after Stop/onPause/onDestroy.
        if (failure != null) listener.microphoneError(failure);
        else listener.microphoneResult(result);
      });
    }, "local-mic-test").start();
  }

  private void status(Run run, String value) {
    ui.post(() -> {
      if (current == run && !run.cancelled.get()) listener.microphoneState(value);
    });
  }

  private String capture(Run run, int source, boolean useSco) throws Exception {
    AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    AudioRecord recorder = null;
    short[] buffer = null;
    boolean scoAttempted = false, scoConnected = false, scoWasOn = audio.isBluetoothScoOn();
    try {
      if (useSco && !scoWasOn) {
        status(run, "Testing Bluetooth SCO routing… (local only)");
        scoAttempted = true;
        scoConnected = connectSco(run, audio);
      }
      if (run.cancelled.get()) return null;
      int rate = 0;
      for (int candidate : new int[] {16000, 8000, 44100}) {
        int min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) continue;
        AudioRecord attempt = null;
        try {
          attempt = new AudioRecord(SOURCE_IDS[source], candidate, AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, candidate / 5 * 2));
          if (attempt.getState() == AudioRecord.STATE_INITIALIZED) {
            recorder = attempt;
            rate = candidate;
            break;
          }
        } catch (IllegalArgumentException ignored) {
        }
        if (attempt != null) attempt.release();
      }
      if (recorder == null) return "No audio input initialized for " + sourceName(source)
          + ". Try another microphone test source. Remote capture is unverified.";
      recorder.startRecording();
      if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        return "Fire OS did not start microphone capture. Use the keyboard mic as fallback.";
      MicrophoneLevels levels = new MicrophoneLevels();
      buffer = new short[Math.max(160, rate / 10)];
      long began = SystemClock.elapsedRealtime(), lastStatus = 0;
      int readError = 0;
      status(run, "Mic test: speak for 8 seconds. Local levels only; no uploads.");
      while (!run.cancelled.get() && SystemClock.elapsedRealtime() - began < CAPTURE_MS
          && levels.samples() < rate * CAPTURE_MS / 1000) {
        int n = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
        if (n < 0) { readError = n; break; }
        if (n > 0) {
          levels.accept(buffer, n);
          Arrays.fill(buffer, (short) 0);
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatus >= 500) {
          lastStatus = now;
          status(run, "Local mic test " + ((now - began) / 1000) + "/8 s · RMS "
              + (int) levels.rms() + " · peak " + levels.peak());
        }
        Thread.sleep(20);
      }
      if (run.cancelled.get()) return null;
      AudioDeviceInfo route = recorder.getRoutedDevice();
      return String.format(Locale.US,
          "Local microphone test\nSource: %s · %d Hz mono PCM16\nSCO: %s\nRoute type: %s\n"
              + "Samples: %d (%d ms) · nonzero: %d\nRMS: %.1f · peak: %d%s\n\n%s\n\n"
              + "Nothing was saved or uploaded. ChatGPT plan-sharing does not support transcription. "
              + "Keep keyboard dictation until raw remote audio is independently confirmed.",
          sourceName(source), rate, scoWasOn ? "pre-existing route flag (connection unverified)"
              : scoConnected ? "connected" : useSco ? "unavailable" : "not requested",
          route == null ? "not reported" : Integer.toString(route.getType()), levels.samples(),
          levels.durationMs(rate), levels.nonzero(), levels.rms(), levels.peak(),
          readError == 0 ? "" : " · read error " + readError, levels.observation());
    } finally {
      if (buffer != null) Arrays.fill(buffer, (short) 0);
      if (recorder != null) {
        try { recorder.stop(); } catch (Exception ignored) {}
        recorder.release();
      }
      // Undo our routing attempt even on timeout/cancellation/error; do not tear down existing SCO.
      if (scoAttempted) {
        try { audio.stopBluetoothSco(); } catch (Exception ignored) {}
        try { audio.setBluetoothScoOn(scoWasOn); } catch (Exception ignored) {}
      }
    }
  }

  private boolean connectSco(Run run, AudioManager audio) throws Exception {
    AtomicBoolean connected = new AtomicBoolean();
    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) {
        connected.set(i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            == AudioManager.SCO_AUDIO_STATE_CONNECTED);
      }
    };
    context.registerReceiver(receiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
    try {
      audio.startBluetoothSco();
      audio.setBluetoothScoOn(true);
      long deadline = SystemClock.elapsedRealtime() + 3000;
      while (!run.cancelled.get() && !connected.get() && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(40);
      return connected.get();
    } finally {
      context.unregisterReceiver(receiver);
    }
  }
}
