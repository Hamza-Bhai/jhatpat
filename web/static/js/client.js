// JhatPat phone (client) page: a chat with the laptop and other devices.
(function () {
  const $ = (s) => document.querySelector(s);
  const t = JP.t;
  let me = null;
  let items = [];
  const scroller = JP.stickyScroll($("#chat"));
  JP.viewSource = () => ({ items: items.slice().reverse(), base: "/api/dev/items/" });

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

  function render(force) {
    scroller.update(() => {
      const list = items.slice().reverse(); // oldest first, like a chat
      $("#feed").innerHTML = list
        .map((it) => {
          const mine = it.from === me.id;
          return JP.bubble(it, { mine, who: mine ? "" : it.fromName, base: "/api/dev/items/" });
        })
        .join("");
      $("#empty").classList.toggle("hidden", list.length > 0 || $("#pending").children.length > 0);
    }, force);
  }

  async function load(force) {
    items = await JP.api("/api/dev/items");
    render(force);
  }

  async function uploadFiles(files) {
    const to = target();
    $("#empty").classList.add("hidden");
    for (const f of files) {
      const rel = f.webkitRelativePath || "";
      let pb;
      scroller.update(() => (pb = JP.pendingBubble($("#pending"), f, rel || f.name)), true);
      try {
        await JP.uploadFile("/api/dev/uploads", f, rel, to, {
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
    try {
      await JP.api("/api/dev/text", { method: "POST", json: { text, to: target() } });
      ta.value = "";
      fit();
      await load(true);
    } catch (e) {
      JP.toast("😟 " + JP.esc(e.message));
    }
  }

  // ---------- events ----------
  document.addEventListener("click", async (e) => {
    const el = e.target.closest("[data-copy]");
    if (!el) return;
    const it = items.find((x) => x.id === el.dataset.copy);
    if (it && (await JP.copy(it.text))) JP.toast(t("copied"), 1500);
  });

  const fit = JP.autoGrow($("#text"));
  $("#sendText").onclick = sendText;
  $("#attachBtn").onclick = () => $("#fileInput").click();
  $("#folderBtn").onclick = () => $("#folderInput").click();
  $("#fileInput").onchange = (e) => { uploadFiles([...e.target.files]); e.target.value = ""; };
  $("#folderInput").onchange = (e) => { uploadFiles([...e.target.files]); e.target.value = ""; };
  // Folder picking works on Android/desktop browsers, not on iOS Safari.
  const canFolder = "webkitdirectory" in document.createElement("input") && !/iPhone|iPad|iPod/.test(navigator.userAgent);
  $("#folderBtn").classList.toggle("hidden", !canFolder);
  // Desktop browsers: Enter sends, Shift+Enter makes a new line.
  $("#text").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && !("ontouchstart" in window)) { e.preventDefault(); sendText(); }
  });

  document.querySelectorAll("[data-theme-btn]").forEach((b) => (b.onclick = () => JP.theme.toggle()));

  $("#meBtn").onclick = async () => {
    const name = prompt(t("your_name"), me.name);
    if (name === null || !name.trim() || name.trim() === me.name) return;
    try {
      me = await JP.api("/api/dev/rename", { method: "POST", json: { name } });
      $("#me").textContent = me.name;
      JP.toast("✅ " + JP.esc(me.name), 1500);
    } catch (e) {
      JP.toast("😟 " + JP.esc(e.message));
    }
  };

  function kicked() {
    $("#shell").classList.add("hidden");
    $("#kickedView").classList.remove("hidden");
  }

  async function start() {
    JP.applyI18n();
    JP.theme.paint();
    try {
      me = await JP.api("/api/dev/me");
    } catch (e) {
      if (e.status === 401) return location.replace("/");
      throw e;
    }
    $("#me").textContent = me.name;
    await Promise.all([load(true), loadTargets()]);
    const es = JP.sse("/api/dev/events", {
      item: async (d) => {
        await load();
        const it = d && items.find((x) => x.id === d.id);
        if (it && it.from !== me.id && navigator.vibrate) navigator.vibrate(80);
      },
      "item-removed": () => load(),
      kicked: () => { es.close(); kicked(); },
    });
    // If JhatPat restarted (or we were removed), go back to the join screen.
    setInterval(() => loadTargets().catch((e) => { if (e.status === 401) location.replace("/"); }), 15000);
  }
  start().catch((e) => JP.toast("😟 " + JP.esc(e.message), 0));
})();
