import { contextualFollowUps, normalizeFollowUps } from './follow-up-model.js';

// Each mounted conversation owns its cache and cancellation fence. Late network
// responses must never replace another conversation's suggestions.
export function createFollowUpLoader({ request, update }) {
  let version = 0, controller = null, frozen = false;
  const cache = new Map();
  function cancel() { version++; controller?.abort(); controller = null; }
  function remember(key, result) {
    cache.set(key, result);
    if (cache.size > 24) cache.delete(cache.keys().next().value);
  }
  return {
    cancel,
    freeze() { frozen = true; },
    dispose() { cancel(); cache.clear(); },
    async load(context, messages, modelEnabled) {
      cancel(); frozen = false;
      const ticket = version;
      const fallback = { suggestions: contextualFollowUps(messages), source: 'context' };
      update(fallback);
      if (!modelEnabled) return;
      const key = JSON.stringify([context.farmId, context.conversationId, context.messageId]);
      if (cache.has(key)) { update(cache.get(key)); return; }
      controller = new AbortController();
      try {
        const response = await request(context, controller.signal);
        if (ticket !== version) return;
        const suggestions = normalizeFollowUps(response, context);
        const result = suggestions.length ? { suggestions, source: 'model' } : fallback;
        remember(key, result);
        // A focused/clicked option must stay still even when the model finishes.
        if (!frozen) update(result);
      } catch {
        if (ticket === version) remember(key, fallback);
        // The answer and composer remain usable when predictions are unavailable.
      } finally { if (ticket === version) controller = null; }
    },
  };
}
