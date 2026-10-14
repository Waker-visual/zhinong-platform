const PROGRESSION = [
  [60, 64, 67, 71],
  [57, 60, 64, 67],
  [53, 57, 60, 64],
  [55, 59, 62, 69],
];
const PATTERN = [0, 2, 1, 3, 2, 1, 3, 2];
const BEAT_SECONDS = 0.8;
const frequency = (note) => 440 * 2 ** ((note - 69) / 12);

// A quiet original arpeggio, synthesized locally without external recordings.
// The context is created only inside play(), which the UI calls on a user click.
export function createSceneAmbience({
  createContext = () => {
    const AudioContext =
      globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!AudioContext) throw new Error("当前浏览器不支持背景音乐");
    return new AudioContext({ latencyHint: "playback" });
  },
  schedule = globalThis.setInterval.bind(globalThis),
  cancelSchedule = globalThis.clearInterval.bind(globalThis),
  onPlaybackChange = () => {},
} = {}) {
  let context, master, filter, timer;
  let playing = false,
    disposed = false,
    generation = 0,
    volume = 0.24,
    beat = 0,
    nextNoteAt = 0;
  const voices = new Set();

  function stopVoices() {
    for (const voice of voices) {
      voice.oscillator.onended = null;
      voice.oscillator.stop();
      voice.oscillator.disconnect();
      voice.envelope.disconnect();
    }
    voices.clear();
  }

  function stopPlayback() {
    if (timer !== undefined) cancelSchedule(timer);
    timer = undefined;
    stopVoices();
    if (playing) {
      playing = false;
      onPlaybackChange(false);
    }
  }

  function tone(hz, at, duration, gain) {
    const oscillator = context.createOscillator();
    const envelope = context.createGain();
    oscillator.type = "sine";
    oscillator.frequency.setValueAtTime(hz, at);
    envelope.gain.setValueAtTime(0.0001, at);
    envelope.gain.linearRampToValueAtTime(gain, at + 0.075);
    envelope.gain.exponentialRampToValueAtTime(0.0001, at + duration);
    oscillator.connect(envelope);
    envelope.connect(master);
    const voice = { oscillator, envelope };
    voices.add(voice);
    oscillator.onended = () => {
      oscillator.disconnect();
      envelope.disconnect();
      voices.delete(voice);
    };
    oscillator.start(at);
    oscillator.stop(at + duration + 0.05);
  }

  function queueNotes() {
    if (!playing || context.state !== "running") return;
    // If the tab or main thread was delayed, continue gently instead of queuing
    // all the missed notes at once.
    if (nextNoteAt < context.currentTime)
      nextNoteAt = context.currentTime + 0.04;
    while (nextNoteAt < context.currentTime + 0.25) {
      const chord = PROGRESSION[Math.floor(beat / 8) % PROGRESSION.length];
      const note = frequency(chord[PATTERN[beat % 8]] + 12);
      tone(note, nextNoteAt, 2.6, 0.2);
      tone(note * 2, nextNoteAt, 1.3, 0.025);
      if (beat % 8 === 0) tone(frequency(chord[0] - 12), nextNoteAt, 5.8, 0.13);
      nextNoteAt += BEAT_SECONDS;
      beat += 1;
    }
  }

  async function play() {
    if (disposed) return false;
    if (playing) return true;
    const request = ++generation;
    if (!context) {
      context = createContext();
      master = context.createGain();
      master.gain.setValueAtTime(volume, context.currentTime);
      filter = context.createBiquadFilter();
      filter.type = "lowpass";
      filter.frequency.setValueAtTime(2200, context.currentTime);
      master.connect(filter);
      filter.connect(context.destination);
      context.onstatechange = () => {
        if (context.state !== "running") stopPlayback();
      };
    }
    await context.resume();
    if (disposed || request !== generation) return false;
    if (context.state !== "running")
      throw new Error("浏览器已暂停音频，请再次点击播放");
    playing = true;
    onPlaybackChange(true);
    nextNoteAt = context.currentTime + 0.04;
    queueNotes();
    timer = schedule(queueNotes, 100);
    return true;
  }

  function pause() {
    generation += 1;
    stopPlayback();
    if (context && context.state !== "closed")
      void context.suspend().catch(() => {});
  }

  function setVolume(value) {
    const requested = Number(value);
    if (!Number.isFinite(requested)) return;
    volume = Math.min(1, Math.max(0, requested));
    if (context && context.state !== "closed") {
      master.gain.setTargetAtTime(volume, context.currentTime, 0.08);
    }
  }

  function dispose() {
    disposed = true;
    generation += 1;
    stopPlayback();
    if (!context) return;
    context.onstatechange = null;
    master.disconnect();
    filter.disconnect();
    if (context.state !== "closed") void context.close().catch(() => {});
  }

  return { play, pause, setVolume, dispose };
}
