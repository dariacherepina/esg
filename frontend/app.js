// ESG Screening UI — plain JavaScript, no framework, no build step.
// All /api/... calls go to the same origin; nginx forwards them to the four services.

const REFRESH_MS = 5000;
const SECTOR_LABELS = { TECHNOLOGY: "Technology", ENERGY: "Energy", FINANCE: "Finance", MANUFACTURING: "Manufacturing" };
const RATING_LABELS = { EXCELLENT: "Excellent", GOOD: "Good", FAIR: "Fair", POOR: "Poor", NONE: "Not scored" };

const state = {
  companies: [],
  scores: new Map(),   // companyId -> score
  reports: new Map(),  // companyId -> report
  portfolios: [],
  selectedId: null,
  errors: {},          // service -> message
};

const $ = (id) => document.getElementById(id);

// ---------- data ----------

async function getJson(url, service) {
  try {
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    delete state.errors[service];
    return await response.json();
  } catch (error) {
    state.errors[service] = `${service} unavailable (${error.message})`;
    return null;
  }
}

/** Loads all four services in parallel; a failing service only hides its own data. */
async function load() {
  const [companies, scores, reports, portfolios] = await Promise.all([
    getJson("/api/companies", "company-service"),
    getJson("/api/scores", "esg-score-service"),
    getJson("/api/reports", "report-service"),
    getJson("/api/portfolios", "portfolio-service"),
  ]);
  if (companies) state.companies = companies;
  if (scores) state.scores = new Map(scores.map((s) => [s.companyId, s]));
  if (reports) state.reports = new Map(reports.map((r) => [r.companyId, r]));
  if (portfolios) state.portfolios = portfolios;
  $("updated").textContent = `Updated ${new Date().toLocaleTimeString()}`;
  render();
}

async function recalculate() {
  const button = $("recalculate");
  button.disabled = true;
  button.textContent = "Recalculating…";
  try {
    const response = await fetch("/api/scores", { method: "POST" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    showInfo("Scores recalculated — portfolios and reports update via events.");
  } catch (error) {
    state.errors["recalculate"] = `Recalculation failed (${error.message})`;
  } finally {
    button.disabled = false;
    button.textContent = "Recalculate all scores";
    // events are processed asynchronously: load now and once more shortly after
    await load();
    setTimeout(load, 1500);
  }
}

// ---------- helpers ----------

/** The report's rating; until the report has arrived, derived from the score with the same bands. */
function ratingOf(companyId) {
  const report = state.reports.get(companyId);
  return report ? report.rating : ratingFromValue(state.scores.get(companyId)?.total);
}

/** Same bands as report-service: Poor < 40 <= Fair < 60 <= Good < 80 <= Excellent. */
function ratingFromValue(value) {
  if (value == null) return "NONE";
  if (value >= 80) return "EXCELLENT";
  if (value >= 60) return "GOOD";
  if (value >= 40) return "FAIR";
  return "POOR";
}

function fmt(value, digits = 2) {
  return value == null ? "–" : Number(value).toFixed(digits);
}

/** Builds an element safely: text is always set via textContent (no HTML injection from data). */
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else node.setAttribute(key, value);
  }
  for (const child of children) {
    if (child != null) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function badge(rating) {
  return el("span", { class: `badge rating-${rating}`, text: RATING_LABELS[rating] });
}

function scoreBar(value, rating) {
  const fill = el("div", { class: `fill rating-${rating}` });
  fill.style.width = `${Math.max(0, Math.min(100, value ?? 0))}%`;
  return el("div", { class: "scorebar" },
    el("div", { class: "track" }, fill),
    el("span", { class: "value", text: fmt(value) }));
}

function showInfo(message) {
  state.info = message;
  setTimeout(() => { if (state.info === message) { state.info = null; renderAlerts(); } }, 4000);
}

// ---------- rendering ----------

function render() {
  renderAlerts();
  renderTable();
  renderDetail();
  renderPortfolios();
}

function renderAlerts() {
  const alerts = $("alerts");
  alerts.replaceChildren();
  if (state.info) alerts.append(el("div", { class: "alert info", text: state.info }));
  for (const message of Object.values(state.errors)) alerts.append(el("div", { class: "alert", text: message }));
}

function filteredCompanies() {
  const search = $("search").value.trim().toLowerCase();
  const sector = $("sector").value;
  const rating = $("rating").value;
  return state.companies.filter((c) =>
    (!search || c.name.toLowerCase().includes(search)) &&
    (!sector || c.sector === sector) &&
    (!rating || ratingOf(c.id) === rating));
}

function renderTable() {
  const rows = filteredCompanies().map((company) => {
    const score = state.scores.get(company.id);
    const rating = ratingOf(company.id);
    const row = el("tr", { class: company.id === state.selectedId ? "selected" : "" },
      el("td", { class: "num muted", text: company.id }),
      el("td", { text: company.name }),
      el("td", { class: "muted", text: SECTOR_LABELS[company.sector] ?? company.sector }),
      el("td", { class: "num", text: fmt(score?.environmental, 1) }),
      el("td", { class: "num", text: fmt(score?.social, 1) }),
      el("td", { class: "num", text: fmt(score?.governance, 1) }),
      el("td", {}, scoreBar(score?.total, rating)),
      el("td", {}, badge(rating)));
    row.addEventListener("click", () => {
      state.selectedId = company.id;
      history.replaceState(null, "", `#company=${company.id}`); // shareable link to this company
      render();
    });
    return row;
  });
  $("company-rows").replaceChildren(...rows);
  $("count").textContent = `(${rows.length} of ${state.companies.length})`;
}

function renderDetail() {
  const panel = $("detail");
  const company = state.companies.find((c) => c.id === state.selectedId);
  if (!company) return;
  const score = state.scores.get(company.id);
  const report = state.reports.get(company.id);
  const rating = ratingOf(company.id);

  const pillars = el("div", { class: "pillars" },
    ...[["Environmental", score?.environmental], ["Social", score?.social], ["Governance", score?.governance]]
      .map(([label, value]) => el("div", { class: "pillar" },
        el("span", { class: "muted", text: label }), scoreBar(value, ratingFromValue(value)))));

  const raw = el("dl", { class: "raw" },
    ...[
      ["CO₂ emissions", `${fmt(company.co2Emissions, 0)} t / €1M`],
      ["Renewable energy", `${fmt(company.renewableEnergyPct, 0)} %`],
      ["Water consumption", `${fmt(company.waterConsumption, 0)} m³ / €1M`],
      ["Board diversity", `${fmt(company.boardDiversityPct, 0)} %`],
      ["Corruption incidents (3 y)", company.corruptionIncidents],
      ["Transparency score", fmt(company.transparencyScore, 0)],
    ].flatMap(([label, value]) => [el("dt", { text: label }), el("dd", { text: value })]));

  const recommendations = report
    ? el("ul", { class: "recommendations" }, ...report.recommendations.map((r) => el("li", { text: r })))
    : el("p", { class: "muted", text: "No report yet — recalculate scores to generate one." });

  panel.replaceChildren(
    el("div", { class: "detail-head" },
      el("div", {},
        el("h2", { text: company.name }),
        el("span", { class: "muted", text: SECTOR_LABELS[company.sector] ?? company.sector })),
      badge(rating)),
    el("div", { class: "detail-score" }, fmt(score?.total), el("small", { text: " / 100" })),
    el("h3", { text: "Pillar scores" }), pillars,
    el("h3", { text: "Raw ESG data" }), raw,
    el("h3", { text: "Recommendations" }), recommendations,
    score ? el("p", { class: "muted", text: `Calculated ${new Date(score.calculatedAt).toLocaleString()}` }) : null);
}

function renderPortfolios() {
  const items = state.portfolios.map((p) => {
    const rating = ratingFromValue(p.aggregateScore);
    const holdings = p.holdings.map((h) => h.companyName ?? `#${h.companyId}`).join(", ");
    return el("li", {},
      el("div", { class: "name" }, el("strong", { text: p.name }), badge(rating)),
      scoreBar(p.aggregateScore, rating),
      el("span", { class: "muted", text: holdings }));
  });
  $("portfolios").replaceChildren(...(items.length ? items : [el("li", { class: "muted", text: "No portfolios" })]));
}

// ---------- wiring ----------

for (const id of ["search", "sector", "rating"]) $(id).addEventListener("input", renderTable);
$("recalculate").addEventListener("click", recalculate);
const linked = location.hash.match(/^#company=(\d+)$/);
if (linked) state.selectedId = Number(linked[1]);
load();
setInterval(load, REFRESH_MS);
