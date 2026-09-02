(() => {
  "use strict";

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const PAGE_SIZE_OPTIONS = [5, 20, 50, 100];
  const PAGE_SIZE_STORAGE_KEY = "jiagu.packLogs.pageSize";

  function storedPageSize() {
    try {
      const value = Number.parseInt(localStorage.getItem(PAGE_SIZE_STORAGE_KEY), 10);
      return PAGE_SIZE_OPTIONS.includes(value) ? value : 20;
    } catch (_) {
      return 20;
    }
  }

  function rememberPageSize(value) {
    try { localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(value)); } catch (_) { }
  }

  const state = {
    companyId: new URLSearchParams(window.location.search).get("companyId") || "",
    authKey: sessionStorage.getItem("jiagu.authKey") || "",
    authType: sessionStorage.getItem("jiagu.authType") || "", // 'admin' or 'company'
    logs: [],
    page: 1,
    total: 0,
    pageSize: storedPageSize()
  };

  const statusText = {
    ACTIVE: "正常", SUSPENDED: "已暂停", EXPIRED: "已过期", REVOKED: "已删除",
    DRAFT: "草稿", PUBLISHED: "已发布"
  };

  let toastTimer;
  function showToast(message) {
    const toast = $("#toast");
    toast.textContent = message;
    toast.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove("show"), 2400);
  }

  async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.authType === 'admin') {
      headers.set("Authorization", `Bearer ${state.authKey}`);
    } else {
      headers.set("X-Company-Key", state.authKey);
    }

    const response = await fetch(path, { ...options, headers });
    if (response.status === 401) {
      state.authKey = "";
      state.authType = "";
      sessionStorage.removeItem("jiagu.authKey");
      sessionStorage.removeItem("jiagu.authType");
      openLogin();
      throw new Error("验证无效或已过期");
    }
    if (!response.ok) {
      let message = `请求失败（${response.status}）`;
      try { message = (await response.json()).message || message; } catch (_) { }
      throw new Error(message);
    }
    const envelope = await response.json();
    return envelope.details;
  }

  const formatDate = (seconds) => {
    if (!seconds) return "—";
    return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(seconds * 1000));
  };
  const formatNumber = (value) => new Intl.NumberFormat("zh-CN").format(value || 0);

  function openLogin() {
    const dialog = $("#loginDialog");
    $("#companyIdInput").value = state.companyId;
    dialog.showModal();
  }

  async function handleLogin(event) {
    event.preventDefault();
    const companyId = event.target.elements.companyId.value.trim();
    const key = event.target.elements.key.value.trim();
    $("#loginError").textContent = "";

    try {
      // Try as company key first, or try admin if key looks like one
      // Since we don't know for sure, the backend will handle it.
      // We'll temporarily set authType based on trial or just let the first request decide.
      // For simplicity, we try the pack-logs API.

      state.companyId = companyId;
      state.authKey = key;
      // Heuristic: admin tokens are usually shorter/different in this system?
      // Actually let's just try one and if it fails try other or just let the server decide.
      // The server.go logic checks Authorization header first.

      // We will try one request to validate
      const testHeaders = new Headers();
      testHeaders.set("Authorization", `Bearer ${key}`);
      let resp = await fetch(`/api/v1/companies/${encodeURIComponent(companyId)}/pack-logs?pageSize=1`, { headers: testHeaders });

      if (resp.ok) {
        state.authType = 'admin';
      } else {
        testHeaders.delete("Authorization");
        testHeaders.set("X-Company-Key", key);
        resp = await fetch(`/api/v1/companies/${encodeURIComponent(companyId)}/pack-logs?pageSize=1`, { headers: testHeaders });
        if (resp.ok) {
          state.authType = 'company';
        } else {
          throw new Error("验证失败：无效的 Token 或 API Key");
        }
      }

      sessionStorage.setItem("jiagu.authKey", state.authKey);
      sessionStorage.setItem("jiagu.authType", state.authType);
      $("#loginDialog").close();
      initPage();
    } catch (error) {
      $("#loginError").textContent = error.message;
    }
  }

  async function loadLogs(page = 1) {
    state.page = page;
    const logsState = $("#logsState");
    const container = $("#logsContainer");
    logsState.classList.remove("hidden");
    container.innerHTML = "";

    try {
      const data = await request(`/api/v1/companies/${encodeURIComponent(state.companyId)}/pack-logs?page=${page}&pageSize=${state.pageSize}`);
      state.logs = data.items || [];
      state.total = data.total || 0;
      renderLogs();
      logsState.classList.add("hidden");
      const authInfo = $("#authInfo");
      const authBadge = document.createElement("span");
      const companyLabel = document.createElement("b");
      authBadge.textContent = state.authType === "admin" ? "A" : "C";
      companyLabel.textContent = state.companyId;
      authInfo.replaceChildren(authBadge, companyLabel);
    } catch (error) {
      logsState.classList.remove("hidden");
      logsState.innerHTML = `<p style="color:var(--red)">${error.message}</p>`;
    }
  }

  function renderLogs() {
    const container = $("#logsContainer");
    container.replaceChildren();

    $("#logsCount").textContent = `共 ${state.total} 条记录`;
    renderPagination();

    if (state.logs.length === 0) {
      const p = document.createElement("p");
      p.className = "table-state";
      p.textContent = "暂无打包记录";
      container.append(p);
      return;
    }

    const groups = state.logs.reduce((acc, log) => {
      if (!acc[log.packageName]) acc[log.packageName] = [];
      acc[log.packageName].push(log);
      return acc;
    }, {});

    Object.entries(groups).forEach(([packageName, releases]) => {
      const groupHeader = document.createElement("div");
      groupHeader.className = "group-header";
      const groupIcon = document.createElement("span");
      groupIcon.className = "group-icon";
      groupIcon.textContent = "📦";
      const groupName = document.createElement("span");
      groupName.textContent = packageName;
      groupHeader.append(groupIcon, groupName);
      container.append(groupHeader);

      const table = document.createElement("table");
      table.innerHTML = `
        <thead>
          <tr>
            <th style="padding-left:48px">版本</th>
            <th>状态</th>
            <th>打包者</th>
            <th>打包时间</th>
            <th>下发次数</th>
          </tr>
        </thead>
        <tbody></tbody>
      `;
      const tbody = table.querySelector("tbody");
      releases.forEach(release => {
        const tr = document.createElement("tr");
        tr.style.cursor = "default";

        const versionTd = document.createElement("td");
        versionTd.style.paddingLeft = "48px";
        const version = document.createElement("strong");
        version.textContent = release.versionCode;
        versionTd.append(version);

        const statusTd = document.createElement("td");
        const badge = document.createElement("span");
        badge.className = `status status-${release.status.toLowerCase()}`;
        badge.textContent = statusText[release.status] || release.status;
        statusTd.append(badge);

        const packerTd = document.createElement("td");
        const packerWrap = document.createElement("div");
        packerWrap.className = "packer-cell";
        const avatar = document.createElement("span");
        avatar.className = "packer-avatar";
        avatar.textContent = (release.packer || "?").charAt(0).toUpperCase();
        const name = document.createElement("span");
        name.textContent = release.packer || "未知";
        packerWrap.append(avatar, name);
        packerTd.append(packerWrap);

        const timeTd = document.createElement("td");
        timeTd.textContent = formatDate(release.createdAt);

        const deliveryTd = document.createElement("td");
        deliveryTd.textContent = formatNumber(release.deliveryCount);

        tr.append(versionTd, statusTd, packerTd, timeTd, deliveryTd);
        tbody.append(tr);
      });
      container.append(table);
    });

  }

  function renderPagination() {
    const el = $("#logsPagination");
    el.replaceChildren();
    const totalPages = Math.ceil(state.total / state.pageSize);
    if (totalPages <= 1) return;

    const createBtn = (page, label, active = false, disabled = false) => {
      const btn = document.createElement("button");
      btn.className = "page-btn" + (active ? " active" : "");
      btn.textContent = label;
      btn.disabled = disabled || active;
      btn.type = "button";
      btn.addEventListener("click", () => loadLogs(page));
      return btn;
    };

    el.append(createBtn(state.page - 1, "‹", false, state.page === 1));
    for (let i = 1; i <= totalPages; i++) {
      if (i === 1 || i === totalPages || (i >= state.page - 1 && i <= state.page + 1)) {
        el.append(createBtn(i, i, i === state.page));
      } else if (i === state.page - 2 || i === state.page + 2) {
        const span = document.createElement("span");
        span.textContent = "...";
        span.style.padding = "0 5px";
        el.append(span);
      }
    }
    el.append(createBtn(state.page + 1, "›", false, state.page === totalPages));
  }

  function initPage() {
    $("#pageSizeSelect").value = String(state.pageSize);
    if (state.authKey && state.companyId) {
      loadLogs(1);
    } else {
      openLogin();
    }
  }

  $("#refreshButton").addEventListener("click", () => loadLogs(state.page));
  $("#pageSizeSelect").addEventListener("change", event => {
    const pageSize = Number.parseInt(event.target.value, 10);
    if (!PAGE_SIZE_OPTIONS.includes(pageSize)) return;
    state.pageSize = pageSize;
    rememberPageSize(pageSize);
    if (state.authKey && state.companyId) loadLogs(1);
  });
  $("#loginForm").addEventListener("submit", handleLogin);
  $("#authInfo").addEventListener("click", openLogin);

  initPage();
})();
