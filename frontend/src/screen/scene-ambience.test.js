import test from "node:test";
import assert from "node:assert/strict";
import { createSceneAmbience } from "./scene-ambience.js";

function audioHarness({ deferredResume = false } = {}) {
  const parameter = () => ({
    values: [],
    setValueAtTime(value) {
      this.values.push(value);
    },
    setTargetAtTime(value) {
      this.values.push(value);
    },
    linearRampToValueAtTime() {},
    exponentialRampToValueAtTime() {},
  });
  const node = () => ({
    connected: false,
    connect() {
      this.connected = true;
    },
    disconnect() {
      this.connected = false;
    },
  });
  const oscillators = [],
    gains = [],
    timers = new Map(),
    states = [];
  let created = 0,
    nextTimer = 0,
    finishResume;
  const context = {
    currentTime: 0,
    state: "suspended",
    destination: {},
    createGain() {
      const result = { ...node(), gain: parameter() };
      gains.push(result);
      return result;
    },
    createBiquadFilter() {
      return { ...node(), frequency: parameter() };
    },
    createOscillator() {
      const result = {
        ...node(),
        frequency: parameter(),
        stopped: false,
        start() {},
        stop() {
          this.stopped = true;
        },
      };
      oscillators.push(result);
      return result;
    },
    resume() {
      if (deferredResume)
        return new Promise((resolve) => {
          finishResume = () => {
            this.state = "running";
            resolve();
          };
        });
      this.state = "running";
      return Promise.resolve();
    },
    suspend() {
      this.state = "suspended";
      return Promise.resolve();
    },
    close() {
      this.state = "closed";
      return Promise.resolve();
    },
  };
  const ambience = createSceneAmbience({
    createContext() {
      created += 1;
      return context;
    },
    schedule(callback) {
      const id = ++nextTimer;
      timers.set(id, callback);
      return id;
    },
    cancelSchedule(id) {
      timers.delete(id);
    },
    onPlaybackChange(value) {
      states.push(value);
    },
  });
  return {
    ambience,
    context,
    oscillators,
    gains,
    timers,
    states,
    created: () => created,
    finishResume: () => finishResume(),
  };
}

test("music stays silent until explicitly played and a second play does not layer a new track", async () => {
  const audio = audioHarness();
  assert.equal(audio.created(), 0);
  assert.equal(audio.oscillators.length, 0);
  audio.ambience.setVolume(0.32);
  assert.equal(await audio.ambience.play(), true);
  assert.equal(audio.gains[0].gain.values[0], 0.32);
  const voices = audio.oscillators.length;
  await audio.ambience.play();
  assert.equal(audio.created(), 1);
  assert.equal(audio.timers.size, 1);
  assert.equal(audio.oscillators.length, voices);
  audio.ambience.dispose();
});

test("pause silences all voices and cancels work; dispose releases the context", async () => {
  const audio = audioHarness();
  await audio.ambience.play();
  audio.ambience.pause();
  assert.equal(audio.context.state, "suspended");
  assert.equal(audio.timers.size, 0);
  assert.ok(
    audio.oscillators.every((voice) => voice.stopped && !voice.connected),
  );
  assert.ok(audio.gains.slice(1).every((gain) => !gain.connected));
  assert.deepEqual(audio.states, [true, false]);
  await audio.ambience.play();
  assert.equal(audio.created(), 1);
  audio.ambience.dispose();
  assert.equal(audio.context.state, "closed");
  assert.equal(audio.timers.size, 0);
  assert.equal(await audio.ambience.play(), false);
});

test("leaving the page during browser audio permission does not start music later", async () => {
  const audio = audioHarness({ deferredResume: true });
  const pending = audio.ambience.play();
  audio.ambience.pause();
  audio.finishResume();
  assert.equal(await pending, false);
  assert.equal(audio.oscillators.length, 0);
  assert.equal(audio.timers.size, 0);
  assert.deepEqual(audio.states, []);
  audio.ambience.dispose();
});

test("browser suspension stops the player and a delayed scheduler never bursts missed notes", async () => {
  const audio = audioHarness();
  await audio.ambience.play();
  const initialVoices = audio.oscillators.length;
  audio.context.currentTime = 120;
  [...audio.timers.values()][0]();
  assert.ok(audio.oscillators.length - initialVoices <= 3);
  audio.context.state = "interrupted";
  audio.context.onstatechange();
  assert.equal(audio.timers.size, 0);
  assert.deepEqual(audio.states, [true, false]);
  audio.ambience.dispose();
});

test("invalid volume is ignored, volume is bounded, and zero really mutes", async () => {
  const audio = audioHarness();
  await audio.ambience.play();
  audio.ambience.setVolume(2);
  audio.ambience.setVolume(Number.NaN);
  audio.ambience.setVolume(-3);
  assert.deepEqual(audio.gains[0].gain.values, [0.24, 1, 0]);
  audio.ambience.dispose();
});
