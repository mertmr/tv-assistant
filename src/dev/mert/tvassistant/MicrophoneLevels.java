package dev.mert.tvassistant;

/** Aggregate PCM16 levels only: no retained samples, transcription, or networking. */
final class MicrophoneLevels {
  private long samples, nonzero;
  private int peak;
  private double energy;

  void accept(short[] pcm, int length) {
    if (length < 0 || length > pcm.length) throw new IllegalArgumentException("Invalid PCM length");
    for (int i = 0; i < length; i++) {
      int value = pcm[i];
      int magnitude = Math.abs(value); // Promote before abs so -32768 is handled correctly.
      peak = Math.max(peak, magnitude);
      if (value != 0) nonzero++;
      energy += (double) value * value;
    }
    samples += length;
  }

  long samples() { return samples; }
  long nonzero() { return nonzero; }
  int peak() { return peak; }
  double rms() { return samples == 0 ? 0 : Math.sqrt(energy / samples); }

  long durationMs(int sampleRate) {
    if (sampleRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
    return samples * 1000 / sampleRate;
  }

  String observation() {
    if (samples == 0) return "No PCM samples received.";
    if (nonzero == 0) return "All samples were silent. Raw microphone access is not established.";
    return "Nonzero audio received. This does not establish speech quality or remote-mic access.";
  }
}
