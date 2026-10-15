// The person picker of the 서비스 tab (requester) and of "승인자 바꾸기": its own file so the approval panel and the tab
// do not import each other.
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/api";
import { Avatar } from "@/components/ui/avatar";
import { Field, Input } from "@/components/ui/field";
import { Select } from "@/components/ui/select";

/**
 * People to pick as requester (or approver): anyone active (requesters and approvers are usually not members), found by
 * name or mail. `labels` renames the two fields for another use.
 */
export function RequesterPicker({
  value,
  current,
  onChange,
  disabled,
  error,
  labels,
}: {
  value: string;
  current: { id: string; name: string } | null;
  onChange: (id: string) => void;
  disabled: boolean;
  error?: string;
  labels?: { search: string; pick: string };
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setDebounced(q), 250);
    return () => clearTimeout(id);
  }, [q]);
  const people = useQuery({ queryKey: ["principals", debounced, "requester"], queryFn: () => api.principals.search(debounced), enabled: !disabled });
  const [picked, setPicked] = useState<{ id: string; name: string }[]>([]);
  const found = (people.data ?? []).filter((p) => p.kind === "user").map((p) => ({ id: p.id, name: p.name }));
  // the current requester and the one just picked stay choosable while the search moves on
  const options = [...(current ? [current] : []), ...picked, ...found].filter((p, i, all) => all.findIndex((x) => x.id === p.id) === i);
  return (
    <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
      {!disabled && (
        <Field label={labels?.search ?? t("serviceDesk.requesterSearch")}>
          <Input type="search" placeholder={t("serviceDesk.requesterSearchPlaceholder")} value={q} onChange={(e) => setQ(e.target.value)} />
        </Field>
      )}
      <Field label={labels?.pick ?? t("serviceDesk.fields.requester")} error={error}>
        <Select
          value={value}
          disabled={disabled}
          onValueChange={(v) => {
            const p = options.find((x) => x.id === v);
            if (p) setPicked((cur) => [...cur.filter((x) => x.id !== p.id), p]);
            onChange(v);
          }}
          placeholder={t("serviceDesk.noMatch")}
          options={options.map((p) => ({
            value: p.id,
            textValue: p.name,
            label: (
              <span className="inline-flex items-center gap-2">
                <Avatar id={p.id} name={p.name} size="xs" />
                {p.name}
              </span>
            ),
          }))}
        />
      </Field>
    </div>
  );
}
