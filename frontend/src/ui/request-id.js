// getRandomValues also works on LAN HTTP, where randomUUID may be unavailable.
export function requestId(cryptoProvider = globalThis.crypto) {
  const bytes = new Uint8Array(16);
  cryptoProvider.getRandomValues(bytes);
  return (
    "farm-" +
    Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("")
  );
}
