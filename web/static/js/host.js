// JhatPat laptop (host) page.
(function () {
  const $ = (s) => document.querySelector(s);
  const t = JP.t;
  let state = { devices: [], groups: [], known: [], items: [] };
  let lastDoctor = null;
  let browsePath = "";
  const selected = new Set();

  // ---------- helpers ----------
  function target() {
    const [kind, id] = ($("#target").value || "all:").split(":");
    return { kind, id };
  }
  function approvedDevices() {
    return state.devices.filter((d) => d.state === "approved");
  }
  async function act(fn) {
    try {
      return await fn();
    } catch (e) {
      JP.toast("😟 " + JP.esc(e.message));
    }
  }

  // ---------- render ----------
  function renderTargets() {
    const sel = $("#target");
    const prev = sel.value;
    const opts = [`<option value="all:">${t("to_all")}</option>`];
    approvedDevices().forEach((d) => opts.push(`<option value="device:${d.id}">📱 ${JP.esc(d.name)}</option>`));
    state.groups.forEach((g) => opts.push(`<option value="group:${g.id}">👨‍👩‍👧 ${JP.esc(g.name)}</option>`));
    sel.innerHTML = opts.join("");
    if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
  }

  function renderApprovals() {
    const pend = state.devices.filter((d) => d.state === "pending");
    $("#approvals").innerHTML = pend
      .map(
        (d) => `<div class="card sun row spread">
          <b>📱 "${JP.esc(d.name)}" ${t("wants_connect")}</b>
          <span class="row">
            <button class="btn green" data-approve="${d.id}">${t("approve")}</button>
            <button class="btn red" data-block="${d.id}">${t("block")}</button>
          </span></div>`
      )
      .join("");
    document.title = pend.length ? `(${pend.length}) JhatPat ⚡` : "JhatPat ⚡";
  }

  function renderDevices() {
    const list = state.devices.filter((d) => d.state !== "pending");
    $("#devices").innerHTML = list.length
      ? list
          .map(
            (d) => `<li class="row spread">
          <span><b>📱 ${JP.esc(d.name)}</b> <span class="chip ${d.state}">${d.state}</span></span>
          <span class="row">
            <button class="btn small" data-rename="${d.id}">${t("rename")}</button>
            <button class="btn small" data-kick="${d.id}">${t("kick")}</button>
            ${d.state === "approved" ? `<button class="btn small red" data-block="${d.id}">${t("block")}</button>` : ""}
          </span></li>`
          )
          .join("")
      : `<li class="empty">${t("no_devices")}</li>`;
  }

  function renderGroups() {
    $("#groups").innerHTML = state.groups
      .map((g) => {
        const boxes = state.known.length
          ? state.known
              .map(
                (k) => `<label class="row" style="gap:6px"><input type="checkbox" data-member="${g.id}" value="${JP.esc(k.pid)}" ${
                  g.members.includes(k.pid) ? "checked" : ""
                }> ${JP.esc(k.name)}</label>`
              )
              .join("")
          : `<span class="muted">${t("no_known")}</span>`;
        return `<li><div class="row spread"><b>👨‍👩‍👧 ${JP.esc(g.name)}</b>
          <button class="btn small" data-delgroup="${g.id}">${t("delete")}</button></div>
          <div class="muted">${t("members")}:</div><div class="row">${boxes}</div></li>`;
      })
      .join("");
  }

  function renderHistory() {
    $("#history").innerHTML = state.items.length
      ? state.items
          .map((it) => {
            const icon = JP.fileIcon(it.name, it.kind);
            const body =
              it.kind === "text"
                ? `<div class="textbox">${JP.linkify(it.text)}</div><button class="btn small" data-copy="${it.id}">${t("copy")}</button>`
                : `<div class="item-title">${icon} ${JP.esc(it.name)} <span class="muted">${it.size ? JP.fmtBytes(it.size) : ""}</span></div>`;
            return `<li><div class="muted">${JP.esc(it.fromName)} → ${JP.esc(it.toNames.join(", "))} · ${new Date(it.createdAt).toLocaleTimeString()}</div>
              ${body} <button class="btn small" data-remove="${it.id}">${t("remove")}</button></li>`;
          })
          .join("")
      : `<li class="empty">${t("no_items")}</li>`;
  }

  function renderAll() {
    $("#pin").textContent = state.pin || "····";
    $("#joinLink").textContent = state.joinURL ? state.joinURL.replace(/\/j\/.*$/, "") : "—";
    renderTargets();
    renderApprovals();
    renderDevices();
    renderGroups();
    renderHistory();
  }

  function refreshQR() {
    $("#qr").src = "/api/host/qr.png?ts=" + Date.now();
  }

  async function load() {
    const s = await JP.api("/api/host/state");
    const qrChanged = s.joinURL !== state.joinURL;
    state = s;
    renderAll();
    if (qrChanged) refreshQR();
  }

  // ---------- doctor ----------
  function renderDoctor(r) {
    lastDoctor = r;
    const pill = $("#doctorPill");
    pill.className = "pill " + (r.ok ? "ok" : "bad");
    pill.textContent = r.ok ? t("doctor_ok") : t("doctor_bad");
    const ico = { ok: "✅", warn: "⚠️", fail: "❌" };
    $("#checks").innerHTML = r.checks
      .map((c) => {
        const key = `chk_${c.id}_${c.status}`;
        const text = JP.has(key) ? t(key, { d: JP.esc(c.detail || r.ip || "") }) : `${c.id}: ${c.status}`;
        return `<div class="check"><span class="ico">${ico[c.status] || "•"}</span><div>${text}</div></div>`;
      })
      .join("");
    $("#fixFirewall").classList.toggle("hidden", !r.checks.some((c) => c.id === "firewall" && c.status === "fail"));
  }
  async function runDoctor(trouble) {
    const r = await act(() => JP.api("/api/host/doctor" + (trouble ? "?trouble=1" : "")));
    if (r) renderDoctor(r);
    return r;
  }

  // ---------- uploads from drag-and-drop ----------
  async function uploadFiles(files) {
    const to = target();
    for (const f of files) {
      const li = document.createElement("li");
      li.innerHTML = `<div class="item-title">${JP.fileIcon(f.name)} ${JP.esc(f.name)} <span class="muted">${JP.fmtBytes(f.size)}</span></div>
        <div class="bar"><i></i></div><div class="muted" data-stat></div>`;
      $("#uploads").prepend(li);
      const bar = li.querySelector(".bar");
      const stat = li.querySelector("[data-stat]");
      try {
        await JP.uploadFile("/api/host/uploads", f, f.webkitRelativePath || "", to, {
          onProgress: ({ sent, total, speed }) => {
            bar.firstElementChild.style.width = (total ? (sent / total) * 100 : 100) + "%";
            stat.textContent = `${JP.fmtBytes(sent)} / ${JP.fmtBytes(total)} · ${JP.fmtSpeed(speed)} · ${JP.fmtETA((total - sent) / speed)}`;
          },
        });
        bar.classList.add("done");
        bar.firstElementChild.style.width = "100%";
        stat.textContent = t("sent");
        setTimeout(() => li.remove(), 3000);
      } catch (e) {
        bar.classList.add("err");
        stat.textContent = t("failed") + ": " + e.message;
      }
    }
  }

  // ---------- laptop file picker ----------
  async function browse(dir) {
    const r = await act(() => JP.api("/api/host/browse" + (dir ? "?dir=" + encodeURIComponent(dir) : "")));
    if (!r) return;
    browsePath = r.dir;
    $("#browseDir").textContent = r.dir;
    $("#browseUp").disabled = !r.parent;
    $("#browseUp").dataset.path = r.parent || "";
    $("#roots").innerHTML = r.roots.map((x) => `<button class="btn small" data-go="${JP.esc(x.path)}">${JP.esc(x.name)}</button>`).join("");
    $("#browseList").innerHTML = r.entries
      .map(
        (e) => `<li><input type="checkbox" data-pick="${JP.esc(e.path)}" ${selected.has(e.path) ? "checked" : ""}>
        ${e.isDir ? `<button class="linklike" data-go="${JP.esc(e.path)}">📁 ${JP.esc(e.name)}</button>` : `<span>${JP.fileIcon(e.name)} ${JP.esc(e.name)} <span class="muted">${JP.fmtBytes(e.size)}</span></span>`}
      </li>`
      )
      .join("") || `<li class="empty">—</li>`;
  }

  // ---------- events ----------
  document.addEventListener("click", async (e) => {
    const el = e.target.closest("button, [data-close]");
    if (!el) return;
    const d = el.dataset;
    if (d.approve) await act(() => JP.api(`/api/host/devices/${d.approve}/approve`, { method: "POST" }));
    else if (d.block) await act(() => JP.api(`/api/host/devices/${d.block}/block`, { method: "POST" }));
    else if (d.kick) await act(() => JP.api(`/api/host/devices/${d.kick}/kick`, { method: "POST" }));
    else if (d.rename) {
      const name = prompt(t("rename"));
      if (name) await act(() => JP.api(`/api/host/devices/${d.rename}/rename`, { method: "POST", json: { name } }));
    } else if (d.delgroup) await act(() => JP.api(`/api/host/groups/${d.delgroup}`, { method: "DELETE" }));
    else if (d.remove) await act(() => JP.api(`/api/host/items/${d.remove}`, { method: "DELETE" }));
    else if (d.copy) {
      const it = state.items.find((x) => x.id === d.copy);
      if (it && (await JP.copy(it.text))) JP.toast(t("copied"), 1500);
    } else if (d.go) browse(d.go);
    else if (d.close !== undefined) el.closest(".modal").classList.add("hidden");
    else return;
    load().catch(() => {});
  });

  document.addEventListener("change", async (e) => {
    const el = e.target;
    if (el.dataset.member) {
      const g = el.dataset.member;
      const boxes = [...document.querySelectorAll(`[data-member="${g}"]`)];
      await act(() => JP.api(`/api/host/groups/${g}`, { method: "PATCH", json: { members: boxes.filter((b) => b.checked).map((b) => b.value) } }));
      load();
    } else if (el.dataset.pick) {
      el.checked ? selected.add(el.dataset.pick) : selected.delete(el.dataset.pick);
    }
  });

  $("#newPin").onclick = async () => { await act(() => JP.api("/api/host/pin/regenerate", { method: "POST" })); await load(); refreshQR(); };
  $("#phoneTrouble").onclick = async () => { $("#doctorModal").classList.remove("hidden"); await runDoctor(true); };
  $("#doctorPill").onclick = async () => { $("#doctorModal").classList.remove("hidden"); await runDoctor(false); };
  $("#rerun").onclick = () => runDoctor(false);
  $("#fixFirewall").onclick = async () => { await act(() => JP.api("/api/host/doctor/firewall", { method: "POST" })); runDoctor(false); };
  $("#openFolder").onclick = () => act(() => JP.api("/api/host/open-folder", { method: "POST" }));
  $("#createGroup").onclick = async () => {
    const name = $("#groupName").value.trim();
    if (!name) return;
    if (await act(() => JP.api("/api/host/groups", { method: "POST", json: { name } }))) $("#groupName").value = "";
    load();
  };
  $("#sendText").onclick = async () => {
    const text = $("#text").value;
    if (!text.trim()) return;
    if (await act(() => JP.api("/api/host/send", { method: "POST", json: { text, to: target() } }))) {
      $("#text").value = "";
      JP.toast(t("sent"), 1500);
    }
    load();
  };
  $("#pickLaptop").onclick = () => { selected.clear(); $("#browseModal").classList.remove("hidden"); browse(browsePath); };
  $("#browseUp").onclick = (e) => { e.stopPropagation(); browse($("#browseUp").dataset.path); };
  $("#sendSelected").onclick = async () => {
    if (!selected.size) return;
    if (await act(() => JP.api("/api/host/send", { method: "POST", json: { paths: [...selected], to: target() } }))) {
      $("#browseModal").classList.add("hidden");
      JP.toast(t("sent"), 1500);
    }
    load();
  };

  const drop = $("#drop");
  drop.onclick = () => $("#fileInput").click();
  drop.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") $("#fileInput").click(); };
  $("#fileInput").onchange = (e) => { uploadFiles([...e.target.files]); e.target.value = ""; };
  ["dragenter", "dragover"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("over"); }));
  ["dragleave", "drop"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove("over"); }));
  drop.addEventListener("drop", (e) => uploadFiles([...e.dataTransfer.files]));

  document.querySelectorAll("[data-theme-btn]").forEach((b) => (b.onclick = () => JP.theme.toggle()));
  $("#langBtn").onclick = () => JP.setLang(JP.lang() === "en" ? "ur" : "en");
  document.addEventListener("jp-lang", () => {
    $("#langBtn").textContent = t("lang");
    renderAll();
    if (lastDoctor) renderDoctor(lastDoctor);
  });

  JP.sse("/api/host/events", {
    "device-request": (d) => { JP.toast(`📱 <b>${JP.esc(d.name)}</b> ${t("wants_connect")}`, 6000); load(); },
    devices: load,
    item: load,
    "item-removed": load,
    pin: () => load(),
    doctor: renderDoctor,
  });

  JP.applyI18n();
  $("#langBtn").textContent = t("lang");
  JP.theme.paint();
  load().catch((e) => JP.toast("😟 " + JP.esc(e.message), 0));
  runDoctor(false);
})();
