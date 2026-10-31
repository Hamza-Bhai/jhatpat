// JhatPat laptop (host) page.
(function () {
  const $ = (s) => document.querySelector(s);
  const t = JP.t;
  let state = { devices: [], groups: [], known: [], items: [] };
  let lastDoctor = null;
  let browsePath = "";
  const selected = new Set();
  const scroller = JP.stickyScroll($("#chat"));
  // Inside the Android app (WebView) the app provides window.JhatPatApp. Quitting, the
  // received-folder button and the host's file browser are handled natively there.
  const inApp = !!window.JhatPatApp;
  if (inApp) ["#quitBtn", "#openFolder", "#pickLaptop"].forEach((sel) => $(sel) && $(sel).classList.add("hidden"));
  JP.viewSource = () => ({ items: state.items.filter(inConversation).reverse(), base: "/api/host/items/" });

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
  // Which items belong to the conversation picked in "Chat with".
  function inConversation(it) {
    const { kind, id } = target();
    if (kind === "device") return it.from === id || it.to.includes(id);
    if (kind === "group") {
      const g = state.groups.find((x) => x.id === id);
      if (!g) return false;
      const ids = approvedDevices().filter((d) => g.members.includes(d.pid)).map((d) => d.id);
      return ids.includes(it.from) || it.to.some((x) => ids.includes(x));
    }
    return true;
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

  function renderChat(force) {
    scroller.update(() => {
      const list = state.items.filter(inConversation).reverse(); // oldest first
      $("#feed").innerHTML = list
        .map((it) => {
          const mine = it.from === "host";
          return JP.bubble(it, { mine, who: mine ? "" : it.fromName, base: "/api/host/items/", removable: true });
        })
        .join("");
      $("#empty").classList.toggle("hidden", list.length > 0 || $("#pending").children.length > 0);
    }, force);
  }

  function renderAll() {
    $("#pin").textContent = state.pin || "····";
    $("#joinLink").textContent = state.joinURL ? state.joinURL.replace(/\/j\/.*$/, "") : "—";
    renderTargets();
    renderApprovals();
    renderDevices();
    renderGroups();
    renderChat();
  }

  function refreshQR() {
    $("#qr").src = "/api/host/qr.png?ts=" + Date.now();
  }

  async function load(force) {
    const s = await JP.api("/api/host/state");
    const qrChanged = s.joinURL !== state.joinURL;
    state = s;
    if (s.doctor === false) ["#doctorPill", "#phoneTrouble"].forEach((sel) => $(sel) && $(sel).classList.add("hidden"));
    renderAll();
    if (force) renderChat(true);
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
        const key =
          c.id === "profile" && c.status === "warn" && c.detail === "Public" ? "chk_profile_public"
          : c.id === "firewall" && c.detail === "phone-seen" ? "chk_firewall_seen"
          : `chk_${c.id}_${c.status}`;
        const text = JP.has(key) ? t(key, { d: JP.esc(c.detail || r.ip || "") }) : `${c.id}: ${c.status}`;
        return `<div class="check"><span class="ico">${ico[c.status] || "•"}</span><div>${text}</div></div>`;
      })
      .join("");
    $("#fixFirewall").classList.toggle("hidden", !r.checks.some((c) => c.id === "firewall" && c.status === "fail"));
  }
  async function runDoctor(trouble) {
    if (state.doctor === false) return null;
    const r = await act(() => JP.api("/api/host/doctor" + (trouble ? "?trouble=1" : "")));
    if (r) renderDoctor(r);
    return r;
  }

  // ---------- sending ----------
  async function uploadFiles(files) {
    const to = target();
    $("#empty").classList.add("hidden");
    for (const f of files) {
      let pb;
      scroller.update(() => (pb = JP.pendingBubble($("#pending"), f, f.webkitRelativePath || f.name)), true);
      try {
        await JP.uploadFile("/api/host/uploads", f, f.webkitRelativePath || "", to, {
          onProgress: ({ sent, total, speed }) => pb.progress(sent, total, speed),
          onPause: () => pb.pause(),
        });
        pb.done();
        await load(true);
      } catch (e) {
        pb.fail(e.message);
      }
    }
  }

  async function sendText() {
    const ta = $("#text");
    const text = ta.value;
    if (!text.trim()) return;
    if (await act(() => JP.api("/api/host/send", { method: "POST", json: { text, to: target() } }))) {
      ta.value = "";
      fit();
    }
    load(true);
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
    $("#browseList").innerHTML =
      r.entries
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
      return;
    } else if (d.go) return browse(d.go);
    else if (d.close !== undefined) return el.closest(".modal").classList.add("hidden");
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

  $("#target").onchange = () => renderChat(true);
  $("#newPin").onclick = async () => { await act(() => JP.api("/api/host/pin/regenerate", { method: "POST" })); await load(); refreshQR(); };
  $("#phoneTrouble").onclick = async () => { $("#doctorModal").classList.remove("hidden"); await runDoctor(true); };
  $("#doctorPill").onclick = async () => { $("#doctorModal").classList.remove("hidden"); await runDoctor(false); };
  $("#rerun").onclick = () => runDoctor(false);
  $("#fixFirewall").onclick = async () => { await act(() => JP.api("/api/host/doctor/firewall", { method: "POST" })); runDoctor(false); };
  $("#quitBtn").onclick = async () => {
    if (!confirm(JP.t("quit_confirm"))) return;
    await act(() => JP.api("/api/host/quit", { method: "POST" }));
    document.body.innerHTML = '<div class="wrap"><p style="padding:40px;font-size:18px">⚡ ' + JP.t("quit_done") + "</p></div>";
  };
  $("#openFolder").onclick = () => act(() => JP.api("/api/host/open-folder", { method: "POST" }));
  $("#createGroup").onclick = async () => {
    const name = $("#groupName").value.trim();
    if (!name) return;
    if (await act(() => JP.api("/api/host/groups", { method: "POST", json: { name } }))) $("#groupName").value = "";
    load();
  };

  const fit = JP.autoGrow($("#text"));
  $("#sendText").onclick = sendText;
  $("#text").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendText(); }
  });
  $("#attachBtn").onclick = () => $("#fileInput").click();
  $("#fileInput").onchange = (e) => { uploadFiles([...e.target.files]); e.target.value = ""; };
  $("#pickLaptop").onclick = () => { selected.clear(); $("#browseModal").classList.remove("hidden"); browse(browsePath); };
  $("#browseUp").onclick = (e) => { e.stopPropagation(); browse($("#browseUp").dataset.path); };
  $("#sendSelected").onclick = async () => {
    if (!selected.size) return;
    if (await act(() => JP.api("/api/host/send", { method: "POST", json: { paths: [...selected], to: target() } }))) {
      $("#browseModal").classList.add("hidden");
    }
    load(true);
  };

  // The whole chat panel is a drop zone.
  const card = $("#chatCard");
  let depth = 0;
  card.addEventListener("dragenter", (e) => { e.preventDefault(); depth++; card.classList.add("over"); });
  card.addEventListener("dragover", (e) => e.preventDefault());
  card.addEventListener("dragleave", () => { if (--depth <= 0) { depth = 0; card.classList.remove("over"); } });
  card.addEventListener("drop", (e) => {
    e.preventDefault();
    depth = 0;
    card.classList.remove("over");
    if (e.dataTransfer.files.length) uploadFiles([...e.dataTransfer.files]);
  });

  document.querySelectorAll("[data-theme-btn]").forEach((b) => (b.onclick = () => JP.theme.toggle()));

  // "Open" on an item the Android app moved to the Gallery: the app opens it natively.
  document.addEventListener("click", (e) => {
    const b = e.target.closest("[data-open-moved]");
    if (b && window.JhatPatApp) window.JhatPatApp.open(b.dataset.openMoved);
  });

  JP.sse("/api/host/events", {
    "device-request": (d) => { JP.toast(`📱 <b>${JP.esc(d.name)}</b> ${t("wants_connect")}`, 6000); load(); runDoctor(false); },
    devices: () => load(),
    item: () => load(),
    "item-removed": () => load(),
    pin: () => load(),
    doctor: renderDoctor,
  });

  JP.applyI18n();
  JP.theme.paint();
  load(true).catch((e) => JP.toast("😟 " + JP.esc(e.message), 0)).then(() => runDoctor(false));
})();
