// "저장하지 않은 내용이 있으면 떠나기 전에 묻기" (S37): while something typed is unsaved, closing or reloading the
// tab asks first. Off when the person turned the preference off.
import { useEffect } from "react";
import { useMe } from "@/api/hooks";

export function useUnsavedWarning(dirty: boolean) {
  const on = useMe().data?.preferences.warnOnUnsaved ?? true;
  useEffect(() => {
    if (!dirty || !on) return;
    const ask = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = ""; // older browsers need it set
    };
    window.addEventListener("beforeunload", ask);
    return () => window.removeEventListener("beforeunload", ask);
  }, [dirty, on]);
}
