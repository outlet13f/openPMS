// S28 양식 구성: the built-in attributes of a work package's detail and the default form.
// backend: wp/TypeForms.java holds the same list and default.
import type { TypeForm } from "./types";

export const BUILT_IN_ATTRIBUTES = ["assignee", "responsible", "dates", "duration", "work", "remainingWork", "doneRatio", "type", "priority", "version", "category", "parent"] as const;
export type BuiltInAttribute = (typeof BUILT_IN_ATTRIBUTES)[number];

export const DEFAULT_TYPE_FORM: TypeForm = {
  groups: [
    { name: "사람", attributes: ["assignee", "responsible"] },
    { name: "일정", attributes: ["dates", "duration"] },
    { name: "진행률", attributes: ["work", "remainingWork", "doneRatio"] },
    { name: "세부 정보", attributes: ["type", "priority", "version", "category", "parent"] },
  ],
  hidden: [],
};
