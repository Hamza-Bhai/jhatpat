// JhatPat phone (client) page.
(function () {
  const $ = (s) => document.querySelector(s);
  const t = JP.t;
  let me = null;
  let items = [];
  let seen = new Set(JSON.parse(JP.store("jp_seen") || "[]"));
  let resumeWaiters = [];

  function target() {
    const [kind, id] = ($("#target").value || "host:").split(":");
    return { kind, id };
  }

  async function loadTargets() {
    const r = await JP.api("/api/dev/targets");
    const sel = $("#target");
    const prev = sel.value;
    const opts = [`<option value="host:">${t("to_laptop")}</option>`];
    r.devices.forEach((d) => opts.push(`<option value="device:${d.id}">📱 ${JP.esc(d.name)}</option>`));
    r.groups.forEach((g) => opts.push(`<option value="group:${g.id}">👨‍👩‍👧 ${JP.esc(g.name)}</option>`));
    if (r.devices.length) opts.push(`<option value="all:">${t("to_all")}</option>`);
    sel.innerHTML = opts.join("");
    if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
  }

  function render() {
    const incoming = items.filter((it) => it.from !== me.id);
    const unseen = incoming.filter((it) => !seen.has(it.id)).length;
    $("#badge").textContent = unseen;
    $("#badge").classList.toggle("hidden", !unseen);
    $("#inbox").innerHTML = items.length
      ? items
          .map((it) => {
            const mine = it.from === me.id;
            const who = mine ? `→ ${JP.esc(it.toNames.join(", "))}` : `${t("from")} ${JP.esc(it.fromName)}`;
            const dl = `/api/dev/items/${it.id}/download`;
            const body =
              it.kind === "text"
                ? `<div class="textbox">${JP.linkify(it.text)}</div><button class="btn small" data-copy="${it.id}">${t("copy")}</button>`
                : `<div class="item-title">${JP.fileIcon(it.name, it.kind)} ${JP.esc(it.name)}${it.kind === "folder" ? ".zip" : ""} <span class="muted">${it.size ? JP.fmtBytes(it.size) : ""}</span></div>
                   ${mine ? "" : `<a class="btn small primary" href="${dl}" download data-dl="${it.id}">${t("download")}</a>`}`;
            return `<li><div class="muted">${who} · ${new Date(it.createdAt).toLocaleTimeString()}</div>${body}</li>`;
          })
          .join("")
      : `<li class="empty">${t("no_items")}</li>`;
  }

  async function load() {
    items = await JP.api("/api/dev/items");
    render();
  }
  function markSeen(id) {
    seen.add(id);
    JP.store("jp_seen", JSON.stringify([...seen].slice(-500)));
    render();
  }

  function waitForResume(bar, stat) {
    return new Promise((resolve) => {
      bar.classList.add("err");
      stat.innerHTML = `${t("paused")} <button class="btn small" data-resume>${t("resume")}</button>`;
      const done = () => {
        bar.classList.remove("err");
        stat.textContent = "";
        resumeWaiters = resumeWaiters.filter((f) => f !== done);
        resolve();
      };
      stat.querySelector("[data-resume]").onclick = done;
      resumeWaiters.push(done);
    });
  }
  // Network came back or the page became visible again: continue paused uploads.
  const wake = () => resumeWaiters.slice().forEach((f) => f());
  window.addEventListener("online", wake);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) wake(); });

  async function uploadFiles(files) {
    const to = target();
    for (const f of files) {
      const rel = f.webkitRelativePath || "";
      const li = document.createElement("li");
      li.innerHTML = `<div class="item-title">${JP.fileIcon(f.name)} ${JP.esc(rel || f.name)} <span class="muted">${JP.fmtBytes(f.size)}</span></div>
        <div class="bar"><i></i></div><div class="muted" data-stat></div>`;
      $("#uploads").prepend(li);
      const bar = li.querySelector(".bar");
      const stat = li.querySelector("[data-stat]");
      try {
        await JP.uploadFile("/api/dev/uploads", f, rel, to, {
          onProgress: ({ sent, total, speed }) => {
            bar.firstElementChild.style.width = (total ? (sent / total) * 100 : 100) + "%";
            stat.textContent = `${Math.floor(total ? (sent / total) * 100 : 100)}% · ${JP.fmtSpeed(speed)} · ${JP.fmtETA((total - sent) / speed)}`;
          },
          onPause: () => waitForResume(bar, stat),
        });
        bar.classList.add("done");
        bar.firstElementChild.style.width = "100%";
        stat.textContent = t("sent") + " 🎉";
        setTimeout(() => li.remove(), 4000);
      } catch (e) {
        bar.classList.add("err");
        stat.textContent = t("failed") + ": " + e.message;
      }
    }
  }

  // ---------- events ----------
  document.addEventListener("click", async (e) => {
    const el = e.target.closest("[data-copy], [data-dl]");
    if (!el) return;
    if (el.dataset.copy) {
      const it = items.find((x) => x.id === el.dataset.copy);
      if (it && (await JP.copy(it.text))) JP.toast(t("copied"), 1500);
      markSeen(el.dataset.copy);
    } else if (el.dataset.dl) {
      markSeen(el.dataset.dl);
    }
  });

  $("#sendBtn").onclick = () => $("#fileInput").click();
  $("#folderBtn").onclick = () => $("#folderInput").click();
  $("#fileInput").onchange = (e) => { uploadFiles([...e.target.files]); e.target.value = ""; };
  $("#folderInput").onchange = (e) => { uploadFiles([...e.target.files]); e.target.value = ""; };
  // Folder picking works on Android/desktop browsers, not on iOS Safari.
  const canFolder = "webkitdirectory" in document.createElement("input") && !/iPhone|iPad|iPod/.test(navigator.userAgent);
  $("#folderBtn").classList.toggle("hidden", !canFolder);

  $("#sendText").onclick = async () => {
    const text = $("#text").value;
    if (!text.trim()) return;
    try {
      await JP.api("/api/dev/text", { method: "POST", json: { text, to: target() } });
      $("#text").value = "";
      JP.toast(t("sent") + " 🎉", 1500);
      load();
    } catch (e) {
      JP.toast("😟 " + JP.esc(e.message));
    }
  };

  document.querySelectorAll("[data-theme-btn]").forEach((b) => (b.onclick = () => JP.theme.toggle()));
  $("#langBtn").onclick = () => JP.setLang(JP.lang() === "en" ? "ur" : "en");
  document.addEventListener("jp-lang", () => {
    $("#langBtn").textContent = t("lang");
    render();
    loadTargets().catch(() => {});
  });

  function kicked() {
    $("#mainView").classList.add("hidden");
    $("#kickedView").classList.remove("hidden");
  }

  async function start() {
    JP.applyI18n();
    $("#langBtn").textContent = t("lang");
    JP.theme.paint();
    try {
      me = await JP.api("/api/dev/me");
    } catch (e) {
      if (e.status === 401) return location.replace("/");
      throw e;
    }
    $("#me").textContent = me.name;
    await Promise.all([load(), loadTargets()]);
    const es = JP.sse("/api/dev/events", {
      item: async (d) => {
        await load();
        const it = d && items.find((x) => x.id === d.id);
        if (it && it.from !== me.id) {
          if (navigator.vibrate) navigator.vibrate(80);
          JP.toast(`📥 ${JP.esc(it.fromName)}: ${JP.esc(it.kind === "text" ? it.text.slice(0, 60) : it.name)}`, 2500);
        }
      },
      "item-removed": load,
      kicked: () => { es.close(); kicked(); },
    });
    setInterval(() => loadTargets().catch(() => {}), 15000);
  }
  start().catch((e) => JP.toast("😟 " + JP.esc(e.message), 0));
})();
