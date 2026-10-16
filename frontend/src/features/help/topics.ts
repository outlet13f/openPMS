// 이 화면 도움말: which section of which manual explains the screen at this address. Sections are named by their
// number ("7.2"), so a reworded title keeps the link; e2e f17-help checks that every number here exists.
import type { ManualId } from "./manuals";

export interface Topic {
  manual: ManualId;
  section: string;
}

const P = "^/projects/[^/]+";
const TOPICS: [RegExp, ManualId, string][] = [
  // work packages: one item, a new one, the list
  [new RegExp(`${P}/work-packages/new`), "user", "5.1"],
  [new RegExp(`(${P})?/(work-packages|gantt|boards/[^/]+|calendar/[^/]+|team-planner/[^/]+|notifications)/details/\\d+`), "user", "5.2"],
  [new RegExp(`${P}/work-packages/\\d+`), "user", "5.2"],
  [new RegExp(`(${P})?/work-packages/?$`), "user", "6"],
  [new RegExp(`(${P})?/gantt`), "user", "7.1"],
  [new RegExp(`${P}/boards`), "user", "7.2"],
  [new RegExp(`${P}/calendar`), "user", "7.3"],
  [new RegExp(`${P}/team-planner`), "user", "7.4"],
  [new RegExp(`${P}/time`), "user", "15.3"],
  [new RegExp(`${P}/members`), "user", "11.2"],
  [new RegExp(`${P}/settings/modules`), "user", "11.4"],
  [new RegExp(`${P}/settings/work-packages`), "user", "11.6"],
  [new RegExp(`${P}/settings/versions`), "user", "11.7"],
  [new RegExp(`${P}/settings`), "user", "11.3"],
  [/^\/projects\/new/, "user", "11.1"],
  [new RegExp(`${P}/?$`), "user", "4.3"],
  [/^\/projects\/?$/, "user", "4.1"],
  [/^\/notifications/, "user", "8.1"],
  [/^\/my\/notifications/, "user", "8.3"],
  [/^\/my\/account/, "user", "10"],
  [/^\/my\/page/, "user", "3.2"],
  [/^\/my\/timesheet/, "user", "15.1"],
  [/^\/search/, "user", "9"],
  [/^\/help\/api/, "user", "14.4"],
  [/^\/?$/, "user", "3.1"],
  // administration: the operator manual's chapter 9
  [/^\/admin\/users/, "admin", "9.2"],
  [/^\/admin\/groups/, "admin", "9.3"],
  [/^\/admin\/roles/, "admin", "9.4"],
  [/^\/admin\/types/, "admin", "9.5"],
  [/^\/admin\/statuses/, "admin", "9.6"],
  [/^\/admin\/workflows/, "admin", "9.7"],
  [/^\/admin\/priorities/, "admin", "9.8"],
  [/^\/admin\/custom-fields/, "admin", "9.9"],
  [/^\/admin\/progress/, "admin", "9.10"],
  [/^\/admin\/calendar/, "admin", "9.11"],
  [/^\/admin\/authentication/, "admin", "9.12"],
  [/^\/admin\/integrations/, "admin", "9.15"],
  [/^\/admin\/system/, "admin", "9.17"],
  [/^\/admin\/?$/, "admin", "9.1"],
];

export function topicFor(pathname: string): Topic | null {
  const hit = TOPICS.find(([re]) => re.test(pathname));
  return hit ? { manual: hit[1], section: hit[2] } : null;
}

/** Every topic, for the check that each section number exists in its manual. */
export const ALL_TOPICS: Topic[] = TOPICS.map(([, manual, section]) => ({ manual, section }));
