// Writes docs/TRACKERS.md (full table) and prints a compact Markdown list grouped by category,
// both generated from app/src/main/assets/trackers.json so documentation always matches the app.
// Usage: node scripts/tracker-list.mjs [--compact]
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = (p) => fileURLToPath(new URL(`../${p}`, import.meta.url));
const catalog = JSON.parse(readFileSync(root("app/src/main/assets/trackers.json"), "utf8"));
const trackers = catalog.trackers;
const ORDER = ["Advertisement", "Analytics", "Profiling", "Identification", "Location", "Crash reporting"];
const primary = (t) => ORDER.find((c) => t.categories.includes(c)) ?? "Other";

const groups = new Map([...ORDER, "Other"].map((c) => [c, []]));
for (const t of trackers) groups.get(primary(t)).push(t);

const lines = [];
lines.push(`| Category | Trackers |`, `|---|---|`);
for (const [cat, list] of groups) if (list.length) lines.push(`| ${cat} | ${list.length} |`);
lines.push(`| **Total** | **${trackers.length}** |`, "");
const summary = lines.join("\n");

const compact = [...groups]
  .filter(([, list]) => list.length)
  .map(([cat, list]) => `**${cat} (${list.length})**: ${list.map((t) => t.name).join(" · ")}`)
  .join("\n\n");

const esc = (s) => s.replace(/\|/g, "\\|");
const table = [
  `# Trackers TrackerLens recognises`,
  ``,
  `${trackers.length} trackers from the [Exodus Privacy](https://exodus-privacy.eu.org) database (catalog version ${catalog.version}, ODbL 1.0).`,
  `Generated from \`app/src/main/assets/trackers.json\` by \`node scripts/tracker-list.mjs\`; don't edit by hand.`,
  `Each tracker is grouped by its most invasive category (the one that weighs most in the privacy score).`,
  ``,
  summary,
  `| Tracker | Categories | Website |`,
  `|---|---|---|`,
  ...[...groups].flatMap(([, list]) => list).map((t) =>
    `| ${esc(t.name)} | ${t.categories.join(", ") || "—"} | ${t.website ? `[link](${t.website})` : "—"} |`),
  ``,
].join("\n");
writeFileSync(root("docs/TRACKERS.md"), table);

if (process.argv.includes("--compact")) console.log(summary + "\n" + compact);
else console.log(summary);
