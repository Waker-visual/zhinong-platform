// Compare local UTC offsets, so southern-hemisphere summer and non-DST zones work too.
export function daylightHours(date) {
  const januaryOffset = new Date(date.getFullYear(), 0, 1).getTimezoneOffset();
  const julyOffset = new Date(date.getFullYear(), 6, 1).getTimezoneOffset();
  const daylight = date.getTimezoneOffset() < Math.max(januaryOffset, julyOffset);
  return daylight ? [6, 20] : [7, 18];
}
export function scheduledMode(date) {
  const minutes = date.getHours() * 60 + date.getMinutes();
  const [startHour, endHour] = daylightHours(date);
  const start = startHour * 60, end = endHour * 60;
  return minutes >= start && minutes < end ? "LIGHT" : "DARK";
}
