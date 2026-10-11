# OpenProject user-review sources (collected 2026-10-11)

Every row in `reviews.csv` is copied verbatim from the page at its URL. Whitespace and line breaks were collapsed to single spaces, and some rows are trimmed to the relevant sentences. Each excerpt was checked by script as an exact substring of the fetched page or API text, except the AlternativeTo rows (see below). Pages were read one at a time with an honest User-Agent (`openPMS-review-research/0.1`). No login was used, no browser User-Agent was faked, and nothing from github.com/opf or OpenProject's code, bundles or app network calls was read.

**Total: 137 rows from 7 sources.** Only 7 rows have star ratings: review sites mostly blocked access, so the ratings sample is very small.

## Sources reached

| Source | URL | Reached | Rows | Date range | Notes |
|---|---|---|---|---|---|
| Hacker News (Algolia API) | https://hn.algolia.com/api/v1/search?query=OpenProject&tags=comment (4 pages, 636 hits) and `&tags=story`; full threads via `/api/v1/items/{40399577, 24816065, 42931780, 47142212}` | yes | 56 | 2014-10-01 – 2026-10-02 | Out of 597 comments matching "open project", I kept only ones that name OpenProject and give an opinion or experience. |
| OpenProject community forum: General discussion | https://community.openproject.org/projects/openproject/forums/6 (listing pages 1–4, 44 topics read) | yes | 48 | 2016-12-30 – 2026-07-28 | Server-rendered HTML, public. Only user posts were kept; staff replies were left out. Dates are each post's absolute timestamp. |
| OpenProject community forum: Support Installation & Updates | https://community.openproject.org/projects/openproject/forums/9 (listing page 1, 9 topics read) | yes | 7 | 2025-05-21 – 2026-05-06 | Upgrade and PostgreSQL migration pain. |
| Lemmy / fediverse (public API) | https://lemmy.world/api/v3/search?q=OpenProject (comments p1–2, posts p1); threads feddit.nl/post/20578375, lemmy.world/post/29158433, lemmy.ml/post/48436042 | yes | 12 | 2023-06-28 – 2026-06-12 | Stands in for Reddit, which was blocked. The comments come from several instances (lemmy.world, lemmy.ml, midwest.social, discuss.tchncs.de, lemmy.ca, lemm.ee). lemmy.libreprime.io/post/105965 did not respond (HTTP 000). |
| SourceForge (business software reviews) | https://sourceforge.net/software/product/OpenProject/ | yes | 2 | 2021-04-23 – 2023-03-12 | The page has only 2 user reviews (rated 4 and 5). |
| OMR Reviews (DE) | https://omr.com/de/reviews/product/openproject | yes | 2 | 2022-10-26 – 2022-11-23 | The page has only 2 reviews (rated 4.5 and 5). Ratings and dates come from the page's JSON-LD. |
| Blog: kronis.dev | https://blog.kronis.dev/everything%20is%20broken/openproject-updates-are-broken | yes | 2 | 2022-12-02 | A self-hoster's post about the boards paywall and a broken Docker upgrade. The author is the same person behind several HN comments. |
| Blog: hilton.org.uk | https://hilton.org.uk/blog/openproject-review | yes | 4 | 2025-07-29 | "B2B SaaS review: OpenProject" |
| AlternativeTo | https://alternativeto.net/software/openproject/about/ | partial | 4 | 2018-02-09 – 2025-04-02 | curl got HTTP 403, so the page was read through WebFetch. That tool only quotes up to 125 characters, so long reviews came back as consecutive verbatim segments and were rejoined. These rows could not be checked as exact substrings by script. One rating is ambiguous: Jason Lindsey's review (2025-04-02) shows 1 star visually but 2 in the page's structured data, and I recorded 1. Positive 5-star reviews from 2018–2019 were not added. |

## Sources tried and not reached

| Source | URL | Reached | Why not |
|---|---|---|---|
| G2 | https://www.g2.com/products/openproject/reviews | no | HTTP 403, both WebFetch and curl (bot protection) |
| Capterra | https://www.capterra.com/p/152761/OpenProject/reviews/ | no | HTTP 403, both WebFetch and curl |
| Capterra DE | https://www.capterra.com.de/software/152761/openproject | no | HTTP 403 |
| TrustRadius | https://www.trustradius.com/products/openproject/reviews | no | HTTP 403 |
| GetApp | https://www.getapp.com/project-management-planning-software/a/openproject/reviews/ | no | HTTP 403 |
| Software Advice | https://www.softwareadvice.com/project-management/openproject-profile/reviews/ | no | HTTP 403 |
| Software Advice DE | https://www.softwareadvice.de/reviews/43756/openproject | no | HTTP 403 |
| PeerSpot | https://www.peerspot.com/products/openproject-reviews | no | The URL lands on a generic listing page with no OpenProject reviews. |
| SourceForge project page | https://sourceforge.net/projects/openproject/reviews/ | yes, empty | No reviews ("Login To Rate This Project") |
| Product Hunt | https://www.producthunt.com/products/openproject/reviews | yes, no text | 1 review (5.0), but its text is not in the server HTML |
| Reddit | WebSearch with `allowed_domains=reddit.com`; https://www.reddit.com/r/jira/comments/1ihmk1b/.json | no | WebSearch refuses reddit.com ("not accessible to our user agent"), and the thread JSON returns HTTP 403. I did not try to get around the block. |
| ProjectManagement.com | https://www.projectmanagement.com/discussion-topic/89126/is-openproject-a-good-alternative- | no | HTTP 403 |
| OpenProject feature tracker (vote counts) | e.g. https://community.openproject.org/wp/29120 | no vote data | Work-package pages are a JS single-page app, and the server HTML contains only "Loading". I did not call the instance's API, to stay clean-room. Vote information is available only indirectly, from staff replies in the forum (see below). |
| Korean / Japanese user reviews | WebSearch "OpenProject 후기 단점", "OpenProject 使ってみた 不満" | no hits | The results were only English review-site pages. No Korean or Japanese user reviews were found. |

## Vote and roadmap hints from forum staff replies (not counted as rows, because they are staff statements)

- Multiple assignees: "Multiple assignees without group creation is one of the feature requests with the highest vote count here." (staff reply, 2025-05-30). https://community.openproject.org/projects/OP/forums/6/topics/19193
- Multiple assignees: "This Epic has received a lot of votes" (staff reply, 2025-11-05). https://community.openproject.org/projects/OP/forums/6/topics/19415
- Recurring work packages: "That is a highly anticipated feature request on the roadmap yet" (staff reply, 2025-07-03; the feature request is wp/29120, OP-5309). https://community.openproject.org/projects/OP/forums/6/topics/19271
- Milestone +1 day lag: "Currently, there is no workaround to prevent this" (staff reply, 2024-07-25; feature request wp/45981). https://community.openproject.org/projects/OP/forums/6/topics/18802
- Offline mobile app: feature request wp/50851 (staff reply, 2024-08-01). https://community.openproject.org/projects/OP/forums/6/topics/10201

## Already fixed or changed per public release notes (do not read these rows as current complaints)

| Complaint in rows | Status |
|---|---|
| Agile boards missing from Community edition (HN 2020–2021, AlternativeTo 2021, SourceForge 2021, kronis.dev 2022) | Basic agile boards came to Community in 12.1 (2022-05; HN story 31245931). Advanced board types stay Enterprise. |
| Gantt cannot be exported or printed (forum 2017) | Staff said in 2025 that the Gantt export "has been realized in the meantime" (topic 8473). |
| Custom fields not saving in Community 17.0.x (forum 2026-01) | Fixed in 17.1.0, per a staff reply in topic 19504. |
| No resource / workload planning (HN 2020, forum 2026-01) | A Resource management module (Resource planner, Staffing view) shipped in 17.7.0 (2026-08-05), but **as an Enterprise add-on (Premium/Corporate)**. |
| Can log more hours than the workday (forum 2024-11) | 17.8.0 (2026-09-02) added configurable global limits on time entries, **as an Enterprise add-on**. |
| Relations shown in the WP table | Made available in Community in 17.8.0. |
| Dark-mode Gantt bug (forum 2024-10) | Fixed in 14.5 (not included as a row). |
| Wiki/Documents editor dated (forum 2026-01, Hilton 2025-07) | 17.0 (2026-01-14) added a real-time BlockNote editor for **Documents only**. Staff say work-package and wiki editing still use the old editor (topic 19493). |
| Recurring work packages, multiple assignees, team planner in Community, offline mobile | Not mentioned in the 17.0–17.9 release notes I read. Team planner is still Enterprise (staff reply 2025-02). |

Release notes read: https://www.openproject.org/docs/release-notes/ (index), plus 17-0-0, 17-5-0, 17-6-0, 17-7-0, 17-8-0 and 17-9-0. Latest release: 17.9.1 (2026-10-01).
