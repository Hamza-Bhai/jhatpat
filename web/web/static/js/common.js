// JhatPat shared helpers: API calls, live events, theme, uploads, formatting.
(function () {
  const JP = (window.JP = window.JP || {});

  function store(key, value) {
    try {
      if (value === undefined) return localStorage.getItem(key);
      if (value === null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    } catch (e) {
      return null;
    }
  }
  JP.store = store;

  JP.api = async function (path, opts = {}) {
    const init = { method: opts.method || "GET", headers: {}, credentials: "same-origin" };
    if (opts.json !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.json);
    } else if (opts.body !== undefined) {
      init.body = opts.body;
    }
    if (opts.signal) init.signal = opts.signal;
    const res = await fetch(path, init);
    let data = null;
    const ct = res.headers.get("Content-Type") || "";
    if (ct.includes("application/json")) data = await res.json().catch(() => null);
    if (!res.ok) {
      const err = new Error((data && data.error) || res.statusText);
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  };

  // Live events with automatic reconnect (EventSource retries on its own).
  JP.sse = function (path, handlers) {
    const es = new EventSource(path);
    Object.keys(handlers).forEach((type) => {
      es.addEventListener(type, (e) => {
        let data = null;
        try { data = JSON.parse(e.data); } catch (_) {}
        handlers[type](data);
      });
    });
    return es;
  };

  JP.fmtBytes = function (n) {
    if (!n && n !== 0) return "";
    const u = ["B", "KB", "MB", "GB", "TB"];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (i === 0 ? n : n.toFixed(n < 10 ? 1 : 0)) + " " + u[i];
  };
  JP.fmtSpeed = (bps) => JP.fmtBytes(bps) + "/s";
  JP.fmtETA = function (sec) {
    if (!isFinite(sec) || sec <= 0) return "";
    if (sec < 60) return Math.ceil(sec) + "s";
    if (sec < 3600) return Math.floor(sec / 60) + "m " + Math.ceil(sec % 60) + "s";
    return Math.floor(sec / 3600) + "h " + Math.floor((sec % 3600) / 60) + "m";
  };
  JP.esc = function (s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  };
  // Escapes text and turns URLs into links.
  JP.linkify = function (s) {
    return JP.esc(s).replace(/\bhttps?:\/\/[^\s<]+/g, (u) => `<a href="${u}" target="_blank" rel="noopener noreferrer">${u}</a>`);
  };
  JP.fileIcon = function (name, kind) {
    if (kind === "text") return "💬";
    if (kind === "folder") return "📁";
    const ext = (name.split(".").pop() || "").toLowerCase();
    if (["jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"].includes(ext)) return "🖼️";
    if (["mp4", "mov", "mkv", "avi", "webm", "3gp"].includes(ext)) return "🎬";
    if (["mp3", "wav", "m4a", "aac", "ogg", "flac"].includes(ext)) return "🎵";
    if (["pdf"].includes(ext)) return "📕";
    if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return "🗜️";
    if (["apk", "exe", "msi"].includes(ext)) return "📦";
    return "📄";
  };

  JP.copy = async function (text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch (_) {}
      ta.remove();
      return ok;
    }
  };

  // ---- theme ----
  JP.theme = {
    init() {
      const t = store("jp_theme");
      if (t === "light" || t === "dark") document.documentElement.dataset.theme = t;
      this.paint();
    },
    current() {
      const t = document.documentElement.dataset.theme;
      if (t) return t;
      return window.matchMedia && matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    },
    toggle() {
      const next = this.current() === "dark" ? "light" : "dark";
      document.documentElement.dataset.theme = next;
      store("jp_theme", next);
      this.paint();
    },
    paint() {
      document.querySelectorAll("[data-theme-btn]").forEach((b) => (b.textContent = this.current() === "dark" ? "🌙" : "🌞"));
    },
  };

  // ---- toasts ----
  JP.toast = function (html, ms = 4000) {
    let box = document.querySelector(".toasts");
    if (!box) {
      box = document.createElement("div");
      box.className = "toasts";
      document.body.appendChild(box);
    }
    const t = document.createElement("div");
    t.className = "toast";
    t.innerHTML = html;
    box.appendChild(t);
    if (ms) setTimeout(() => t.remove(), ms);
    return t;
  };

  // ---- resumable chunked upload ----
  // base: "/api/dev/uploads" or "/api/host/uploads".
  // cb.onProgress({sent, total, speed}) ; returns the created item.
  JP.uploadFile = async function (base, file, relPath, to, cb = {}) {
    const start = await JP.api(base, { method: "POST", json: { name: file.name, relPath: relPath || "", size: file.size, to } });
    const id = start.id;
    const chunk = start.chunkSize || 8 * 1024 * 1024;
    let offset = 0;
    let last = { t: performance.now(), b: 0 };
    let speed = 0;
    const delays = [1000, 2000, 4000, 8000, 16000];
    const report = () => cb.onProgress && cb.onProgress({ sent: offset, total: file.size, speed });

    const syncOffset = async () => {
      const r = await JP.api(`${base}/${id}`);
      offset = r.offset;
    };

    let failures = 0;
    while (offset < file.size) {
      const blob = file.slice(offset, Math.min(offset + chunk, file.size));
      try {
        const r = await JP.api(`${base}/${id}?offset=${offset}`, { method: "PUT", body: blob });
        offset = r.offset;
        failures = 0;
      } catch (e) {
        if (e.status === 409 && e.data && typeof e.data.offset === "number") { offset = e.data.offset; continue; }
        if (e.status && e.status < 500 && e.status !== 408 && e.status !== 429) throw e;
        if (failures >= delays.length) {
          if (cb.onPause) await cb.onPause(); // resolves when the user taps resume / network returns
          failures = 0;
        } else {
          await new Promise((r) => setTimeout(r, delays[failures++]));
        }
        try { await syncOffset(); } catch (_) {}
        continue;
      }
      const now = performance.now();
      if (now - last.t > 500) {
        speed = ((offset - last.b) * 1000) / (now - last.t);
        last = { t: now, b: offset };
      }
      report();
    }
    report();
    return JP.api(`${base}/${id}/finish`, { method: "POST" });
  };
})();

// ---- chat bubbles (shared by phone and laptop pages) ----
(function () {
  const JP = window.JP;
  // What a file can be previewed as. Mirrors previewTypes on the server.
  JP.previewKind = function (name, kind) {
    if (kind !== "file") return "";
    const ext = (name.split(".").pop() || "").toLowerCase();
    if (["jpg", "jpeg", "png", "gif", "webp", "bmp", "avif"].includes(ext)) return "image";
    if (["mp4", "m4v", "webm", "mov", "ogv"].includes(ext)) return "video";
    if (["mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus"].includes(ext)) return "audio";
    if (ext === "pdf") return "pdf";
    if (ext === "txt") return "txt";
    return "";
  };

  // opts: { mine, who, base ("/api/dev/items/" or "/api/host/items/"), removable }
  JP.bubble = function (it, opts) {
    const t = JP.t;
    const time = new Date(it.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    const who = opts.who ? `<div class="who">${JP.esc(opts.who)}</div>` : "";
    const view = `${opts.base}${it.id}/view`;
    const dl = `${opts.base}${it.id}/download`;
    const pk = JP.previewKind(it.name, it.kind);
    const size = it.size ? JP.fmtBytes(it.size) : "";
    let body;
    // In the Android app a moved photo, video or song is still previewed (the app answers for it from the Gallery).
    const previewable = window.JhatPatApp && ["image", "video", "audio"].includes(pk);
    if (it.moved && !previewable) {
      const name = it.name + (it.kind === "folder" ? ".zip" : "");
      const open = window.JhatPatApp ? `<button class="btn small primary" data-open-moved="${it.id}">${t("open")}</button>` : "";
      body = `<div class="file"><span class="ico">${JP.fileIcon(it.name, it.kind)}</span>
        <div><b>${JP.esc(name)}</b><span style="opacity:.8">${t("saved_to", { where: JP.esc(it.movedTo) })} ${size}</span></div></div>
        <div class="acts">${open}</div>`;
    } else if (it.kind === "text") {
      body = `<div class="txt">${JP.linkify(it.text)}</div>
        <div class="acts"><button class="btn small" data-copy="${it.id}">${t("copy")}</button></div>`;
    } else {
      const name = it.name + (it.kind === "folder" ? ".zip" : "");
      let media = "";
      if (pk === "image") {
        media = `<button class="thumbbtn" data-view="${it.id}" aria-label="${JP.esc(name)}"><img class="thumb" src="${view}" loading="lazy" alt=""></button>`;
      } else if (pk === "video") {
        media = `<button class="thumbbtn" data-view="${it.id}" aria-label="${JP.esc(name)}"><video class="thumb" src="${view}#t=0.1" preload="metadata" muted playsinline></video><span class="play">▶</span></button>`;
      } else if (pk === "audio") {
        media = `<audio class="audio" controls preload="none" src="${view}"></audio>`;
      }
      const openable = pk === "pdf" || pk === "txt";
      const row = `<div class="file ${openable ? "clickable" : ""}" ${openable ? `data-view="${it.id}" role="button" tabindex="0"` : ""}>
        <span class="ico">${JP.fileIcon(it.name, it.kind)}</span>
        <div><b>${JP.esc(name)}</b><span style="opacity:.8">${size}</span></div></div>`;
      body = media ? `${media}<div class="cap">${JP.esc(name)} <span style="opacity:.8">${size}</span></div>` : row;
      body += it.moved
        ? `<div class="acts"><button class="btn small primary" data-open-moved="${it.id}">${t("open")}</button></div>`
        : `<div class="acts"><a class="btn small primary" href="${dl}" download>${t("download")}</a></div>`;
    }
    const x = opts.removable ? `<button class="x" title="${t("remove")}" data-remove="${it.id}">✕</button>` : "";
    return `<div class="msg ${opts.mine ? "mine" : ""} ${pk === "image" || pk === "video" ? "media" : ""}" data-id="${it.id}">${who}${body}
      <div class="meta">${opts.mine ? JP.esc("→ " + it.toNames.join(", ")) + " · " : ""}${time}${opts.mine ? " ✓" : ""}${x}</div></div>`;
  };

  // ---- full-screen viewer ----
  // Pages set JP.viewSource = () => ({ items, base }) with items oldest first.
  let viewerEl = null;
  JP.openViewer = function (id) {
    if (!JP.viewSource) return;
    const { items, base } = JP.viewSource();
    const it = items.find((x) => x.id === id);
    if (!it) return;
    const pk = JP.previewKind(it.name, it.kind);
    if (pk === "pdf") return window.open(`${base}${it.id}/view`, "_blank", "noopener");
    const gallery = items.filter((x) => ["image", "video"].includes(JP.previewKind(x.name, x.kind)));
    let idx = Math.max(0, gallery.findIndex((x) => x.id === id));
    if (!viewerEl) {
      viewerEl = document.createElement("div");
      viewerEl.className = "viewer";
      viewerEl.innerHTML = `<div class="vbar"><b class="vname"></b><span class="row">
          <a class="btn small primary vdl" download>${JP.t("download")}</a>
          <button class="btn small vclose" aria-label="Close">✕</button></span></div>
        <div class="vbody"></div>
        <button class="vnav vprev" aria-label="Previous">‹</button><button class="vnav vnext" aria-label="Next">›</button>`;
      document.body.appendChild(viewerEl);
      viewerEl.querySelector(".vclose").onclick = JP.closeViewer;
      viewerEl.addEventListener("click", (e) => { if (e.target === viewerEl || e.target.classList.contains("vbody")) JP.closeViewer(); });
      document.addEventListener("keydown", (e) => {
        if (!viewerEl || viewerEl.classList.contains("hidden")) return;
        if (e.key === "Escape") JP.closeViewer();
        if (e.key === "ArrowLeft") viewerEl.querySelector(".vprev").click();
        if (e.key === "ArrowRight") viewerEl.querySelector(".vnext").click();
      });
      let x0 = null;
      viewerEl.addEventListener("touchstart", (e) => (x0 = e.touches[0].clientX), { passive: true });
      viewerEl.addEventListener("touchend", (e) => {
        if (x0 === null) return;
        const dx = e.changedTouches[0].clientX - x0;
        if (Math.abs(dx) > 60) viewerEl.querySelector(dx > 0 ? ".vprev" : ".vnext").click();
        x0 = null;
      });
    }
    const show = (single) => {
      const cur = single || gallery[idx];
      const k = JP.previewKind(cur.name, cur.kind);
      const src = `${base}${cur.id}/view`;
      viewerEl.querySelector(".vname").textContent = cur.name;
      viewerEl.querySelector(".vdl").href = `${base}${cur.id}/download`;
      viewerEl.querySelector(".vbody").innerHTML =
        k === "image" ? `<img src="${src}" alt="">`
        : k === "video" ? `<video src="${src}" controls autoplay playsinline></video>`
        : k === "txt" ? `<iframe src="${src}" title="text"></iframe>`
        : `<div class="vnone">${JP.fileIcon(cur.name, cur.kind)}<p>${JP.esc(cur.name)}</p></div>`;
      const nav = !single && gallery.length > 1;
      viewerEl.querySelector(".vprev").classList.toggle("hidden", !nav);
      viewerEl.querySelector(".vnext").classList.toggle("hidden", !nav);
    };
    viewerEl.querySelector(".vprev").onclick = (e) => { e.stopPropagation(); idx = (idx - 1 + gallery.length) % gallery.length; show(); };
    viewerEl.querySelector(".vnext").onclick = (e) => { e.stopPropagation(); idx = (idx + 1) % gallery.length; show(); };
    show(pk === "image" || pk === "video" ? null : it);
    viewerEl.classList.remove("hidden");
    document.body.style.overflow = "hidden";
  };
  JP.closeViewer = function () {
    if (!viewerEl) return;
    viewerEl.classList.add("hidden");
    viewerEl.querySelector(".vbody").innerHTML = ""; // stops video playback
    document.body.style.overflow = "";
  };
  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-view]");
    if (el) { e.preventDefault(); JP.openViewer(el.dataset.view); }
  });
  document.addEventListener("keydown", (e) => {
    if ((e.key === "Enter" || e.key === " ") && e.target.matches && e.target.matches("[data-view][role=button]")) {
      e.preventDefault();
      JP.openViewer(e.target.dataset.view);
    }
  });

  // A temporary bubble for a file being uploaded; returns {el, progress(sent,total,speed), done(), fail(msg), pause(onResume)}.
  JP.pendingBubble = function (container, file, label) {
    const el = document.createElement("div");
    el.className = "msg mine";
    el.innerHTML = `<div class="file"><span class="ico">${JP.fileIcon(file.name)}</span>
      <div><b>${JP.esc(label || file.name)}</b><span style="opacity:.8">${JP.fmtBytes(file.size)}</span></div></div>
      <div class="bar"><i></i></div><div class="meta" data-stat>…</div>`;
    container.appendChild(el);
    const bar = el.querySelector(".bar");
    const stat = el.querySelector("[data-stat]");
    return {
      el,
      progress(sent, total, speed) {
        bar.classList.remove("err");
        bar.firstElementChild.style.width = (total ? (sent / total) * 100 : 100) + "%";
        stat.textContent = `${Math.floor(total ? (sent / total) * 100 : 100)}% · ${JP.fmtSpeed(speed)} · ${JP.fmtETA((total - sent) / speed)}`;
      },
      done() { el.remove(); },
      fail(msg) { bar.classList.add("err"); stat.textContent = JP.t("failed") + ": " + msg; },
      pause() {
        return new Promise((resolve) => {
          bar.classList.add("err");
          stat.innerHTML = `${JP.t("paused")} <button class="btn small">${JP.t("resume")}</button>`;
          const go = () => { window.removeEventListener("online", go); document.removeEventListener("visibilitychange", vis); stat.textContent = "…"; resolve(); };
          const vis = () => { if (!document.hidden) go(); };
          stat.querySelector("button").onclick = go;
          window.addEventListener("online", go);
          document.addEventListener("visibilitychange", vis);
        });
      },
    };
  };

  // Keeps a scroll box pinned to the bottom when the user is already there.
  // Photos and videos that finish loading later keep it pinned too.
  JP.stickyScroll = function (box) {
    const nearBottom = () => box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    let pinned = true;
    box.addEventListener("scroll", () => (pinned = nearBottom()), { passive: true });
    const keep = () => { if (pinned) box.scrollTop = box.scrollHeight; };
    box.addEventListener("load", keep, true); // images
    box.addEventListener("loadedmetadata", keep, true); // videos
    return {
      update(fn, force) {
        const stick = force || nearBottom();
        fn();
        if (stick) { box.scrollTop = box.scrollHeight; pinned = true; }
      },
    };
  };

  // Grows a textarea with its content.
  JP.autoGrow = function (ta) {
    const fit = () => { ta.style.height = "auto"; ta.style.height = Math.min(ta.scrollHeight + 4, 140) + "px"; };
    ta.addEventListener("input", fit);
    return fit;
  };
})();
