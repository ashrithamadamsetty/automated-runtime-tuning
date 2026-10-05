"use strict";

const API = "/tuning";
const REFRESH_MS = 2000;

/* Presentation metadata. The dashboard labels what the backend reports; it never
   decides control behaviour. */
const LOOPS = {
    "thread-pool":   { short: "threads",     resource: "threadPool",   unit: "threads",     key: "maxThreads" },
    "database-pool": { short: "dbPool",      resource: "dbPool",       unit: "connections", key: "maxPoolSize" },
    "rate-limiter":  { short: "rateLimiter", resource: "rateLimiter",  unit: "req/s",       key: "limitPerSecond" }
};
const ORDER = ["thread-pool", "database-pool", "rate-limiter"];

let currentMode = null;
let emergencyStopped = false;
let busy = false;

/* ===================== helpers ===================== */

const $ = id => document.getElementById(id);

async function api(url, options = {}) {
    const res = await fetch(url, options);
    const text = await res.text();
    let data;
    try { data = JSON.parse(text); } catch { data = text; }
    if (!res.ok) throw new Error(data?.message || data?.error || `Request failed (${res.status})`);
    return data;
}

function toast(message, kind = "ok") {
    const el = $("toast");
    el.textContent = message;
    el.className = `toast show ${kind}`;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.className = "toast"; }, 2800);
}

const n0 = v => (v === null || v === undefined || Number.isNaN(v)) ? "—" : Math.round(v);
const n2 = v => (v === null || v === undefined || Number.isNaN(v)) ? "—" : Number(v).toFixed(0);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function intervalLabel(iso) {
    if (!iso) return "—";
    const m = /PT(?:(\d+)M)?(?:([\d.]+)S)?/.exec(iso);
    if (!m) return iso;
    const secs = (parseInt(m[1] || 0, 10) * 60) + parseFloat(m[2] || 0);
    return secs >= 60 ? `${secs / 60}m` : `${secs}s`;
}

/* ===================== polling ===================== */

async function refresh() {
    if (busy) return;
    let status, resources;
    try {
        [status, resources] = await Promise.all([
            api(`${API}/status`),
            Promise.all([
                api(`${API}/thread-pool`),
                api(`${API}/database-pool`),
                api(`${API}/rate-limiter`)
            ])
        ]);
    } catch {
        $("modeNote").textContent = "Server unreachable. Retrying…";
        return;
    }

    const [tp, db, rl] = resources;
    renderMode(status);
    renderResources(status, { threadPool: tp, dbPool: db, rateLimiter: rl });
    renderLoops(status);
    renderAudit();
}

/* ===================== mode ===================== */

const MODE_NOTES = {
    off: "Control loops are not evaluated at all. Nothing is measured and nothing is changed.",
    shadow: "Decisions are calculated and written to the audit log, but not applied. Safe default.",
    active: "Decisions are applied to the running server, subject to step, bound and cooldown limits.",
    "emergency-stopped": "Kill switch engaged. All automatic changes are blocked until it is released."
};

function renderMode(s) {
    currentMode = s.mode;
    emergencyStopped = s.emergencyStopped;

    document.querySelectorAll("#modeSwitch button").forEach(b => {
        b.classList.toggle("on", !emergencyStopped && b.dataset.mode === s.mode);
        b.disabled = emergencyStopped;
    });

    $("modeNote").textContent = MODE_NOTES[s.effectiveMode] || MODE_NOTES[s.mode] || "";

    const kill = $("btnKill");
    kill.textContent = emergencyStopped ? "Release kill switch" : "Kill switch";
    kill.classList.toggle("danger", !emergencyStopped);
}

/* ===================== resources ===================== */

function renderResources(status, live) {
    const row = $("resourceRow");
    row.innerHTML = ORDER.map(name => {
        const meta = LOOPS[name];
        const cfg = status.loops[name].config;
        const value = live[meta.resource][meta.key];

        const atCeil = value >= cfg.max;
        const atFloor = value <= cfg.min;
        const chip = atCeil
            ? '<span class="chip ceil">AT CEILING</span>'
            : atFloor ? '<span class="chip floor">AT FLOOR</span>'
                : '<span class="chip head">HEADROOM</span>';

        const span = Math.max(1, cfg.max - cfg.min);
        const pct = Math.min(100, Math.max(2, ((value - cfg.min) / span) * 100));
        const fill = atCeil ? "ceil" : pct > 85 ? "warn" : "";

        return `
            <div class="resource">
                <div class="resource-head"><h3>${esc(meta.resource)}</h3>${chip}</div>
                <div class="value-row">
                    <span class="value">${n0(value)}</span>
                    <span class="unit">${esc(meta.unit)}</span>
                    <span class="bounds">min ${cfg.min} &middot; max ${cfg.max}</span>
                </div>
                <div class="bar"><i class="${fill}" style="width:${pct}%"></i></div>
            </div>`;
    }).join("");
}

/* ===================== loops ===================== */

function statusCell(loop, d, cfg) {
    if (!cfg.enabled) return ['<span class="status-idle">disabled</span>', ""];
    if (!d) return ['<span class="status-idle">idle</span>', ""];
    if (d.reason && /failed|non-finite/.test(d.reason)) {
        return [`<span class="status-fault">fault</span>`, d.reason];
    }
    if (d.atMax || d.atMin) return ['<span class="status-limit">at limit</span>', d.reason || ""];
    if (d.applied) return ['<span class="status-controlling">controlling</span>', ""];
    if (d.reason === "shadow mode") return ['<span class="status-shadow">shadow</span>', ""];
    if (d.reason === "within deadband") return ['<span class="status-idle">settled</span>', ""];
    if (d.reason === "cooldown active") return ['<span class="status-held">cooldown</span>', ""];
    return [`<span class="status-held">${esc(d.reason || "held")}</span>`, ""];
}

function renderLoops(s) {
    const every = intervalLabel(s.interval);

    $("loopRows").innerHTML = ORDER.filter(n => s.loops[n]).map(name => {
        const meta = LOOPS[name];
        const cfg = s.loops[name].config;
        const d = s.loops[name].lastDecision;
        const [status, title] = statusCell(name, d, cfg);

        // Reverse acting: raising the resource lowers the measured signal.
        const arrow = cfg.reverseActing
            ? '<span class="dir" title="reverse acting: more capacity lowers the signal">&uarr;</span>'
            : '<span class="dir" title="direct acting">&darr;</span>';

        return `
            <tr>
                <td>
                    <div class="loop-name">${esc(meta.short)}</div>
                    <div class="loop-res">&rarr; ${esc(name)}</div>
                </td>
                <td class="${cfg.enabled ? "state-on" : "state-off"}">${cfg.enabled ? "on" : "off"}</td>
                <td class="num">${every}</td>
                <td class="num">${n2(cfg.target)} ${arrow}</td>
                <td class="num">${d ? n0(d.actual) : "—"}</td>
                <td class="num">${d ? n0(d.error) : "—"}</td>
                <td class="num">${d ? n0(d.proportional) : "—"}</td>
                <td class="num">${d ? n0(d.integral) : "—"}</td>
                <td class="num">${d ? n0(d.derivative) : "—"}</td>
                <td class="num">${d ? d.requestedValue : "—"}</td>
                <td title="${esc(title)}">${status}</td>
                <td>
                    <button class="btn small" data-toggle="${esc(name)}" data-next="${!cfg.enabled}">
                        ${cfg.enabled ? "Disable" : "Enable"}
                    </button>
                </td>
            </tr>`;
    }).join("");
}

/* ===================== audit ===================== */

async function renderAudit() {
    let entries;
    try { entries = await api(`${API}/audit?limit=120`); } catch { return; }

    const body = $("auditRows");
    if (!entries.length) {
        body.innerHTML = '<tr><td colspan="8" class="muted">No entries yet.</td></tr>';
        return;
    }

    body.innerHTML = entries.map(e => {
        const time = new Date(e.at).toLocaleTimeString();
        const mode = (e.mode || "").toLowerCase();
        return `
            <tr>
                <td class="time">${time}</td>
                <td class="loop">${esc(e.loop || "—")}</td>
                <td class="num">${e.from ?? "—"}</td>
                <td class="num">${e.want ?? "—"}</td>
                <td class="num">${e.to ?? "—"}</td>
                <td class="mode ${esc(mode)}">${esc(mode.toUpperCase() || "—")}</td>
                <td class="${e.applied ? "yes" : "no"}">${e.applied ? "yes" : "no"}</td>
                <td class="detail">${esc(e.detail)}</td>
            </tr>`;
    }).join("");
}

/* ===================== actions ===================== */

async function act(label, fn) {
    busy = true;
    try {
        await fn();
    } catch (e) {
        toast(e.message, "err");
    } finally {
        busy = false;
        refresh();
    }
}

document.addEventListener("click", async event => {
    const modeBtn = event.target.closest("#modeSwitch button");
    if (modeBtn && !modeBtn.disabled) {
        const next = modeBtn.dataset.mode;
        if (next === currentMode) return;
        if (next === "active" && !confirm(
            "Switch to ACTIVE?\n\nThe controllers will begin changing this server's thread pool, connection pool and rate limit.")) return;
        return act("mode", async () => {
            await api(`${API}/mode?value=${next}`, { method: "POST" });
            toast(`Mode set to ${next}`);
        });
    }

    const toggle = event.target.closest("button[data-toggle]");
    if (toggle) {
        const name = toggle.dataset.toggle;
        const next = toggle.dataset.next === "true";
        return act("toggle", async () => {
            await api(`${API}/loops/${name}/enabled?value=${next}`, { method: "POST" });
            toast(`${name} ${next ? "enabled" : "disabled"}`);
        });
    }

    if (event.target.id === "btnKill") {
        if (emergencyStopped) {
            return act("release", async () => {
                await api(`${API}/emergency-stop/release`, { method: "POST" });
                toast("Kill switch released");
            });
        }
        if (!confirm("Engage the kill switch?\n\nAll automatic tuning stops and every resource returns to its configured default.")) return;
        return act("kill", async () => {
            await api(`${API}/emergency-stop`, { method: "POST" });
            toast("Kill switch engaged", "err");
        });
    }

    if (event.target.id === "btnReset") {
        if (!confirm("Reset every resource to its configured default and clear PID state?")) return;
        return act("reset", async () => {
            await api(`${API}/reset`, { method: "POST" });
            toast("Reset to defaults");
        });
    }

    if (event.target.id === "btnClearAudit") {
        return act("clear", async () => {
            await api(`${API}/audit/clear`, { method: "POST" });
            toast("Audit log cleared");
        });
    }
});

/* ===================== lifecycle ===================== */

let timer = setInterval(refresh, REFRESH_MS);

document.addEventListener("visibilitychange", () => {
    clearInterval(timer);
    if (!document.hidden) { refresh(); timer = setInterval(refresh, REFRESH_MS); }
});

refresh();