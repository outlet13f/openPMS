// For controls whose value makes a round trip (component → URL → props): remember what was sent last, so
// quick successive edits build on each other instead of on props that have not caught up yet.
// Props that match an earlier send are ignored while newer sends are still on their way; props that match
// nothing sent (someone else changed the value, e.g. another saved view) win.
import { useEffect, useRef, useState } from "react";

export function useLatestSent<T>(value: T, key: (v: T) => string) {
  const latest = useRef(value);
  const pending = useRef<string[]>([]);
  const [, rerender] = useState(0);
  const k = key(value);

  useEffect(() => {
    const i = pending.current.indexOf(k);
    if (i >= 0) {
      pending.current = pending.current.slice(i + 1);
      if (pending.current.length > 0) return; // a newer value is still coming back
    } else {
      pending.current = [];
    }
    latest.current = value;
    rerender((n) => n + 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [k]);

  const send = (next: T) => {
    latest.current = next;
    pending.current.push(key(next));
    rerender((n) => n + 1);
  };
  return { latest, send };
}
