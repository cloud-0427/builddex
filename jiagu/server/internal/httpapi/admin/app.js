(() => {
  "use strict";

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const state = { companies: [], selected: null, filter: "ALL", query: "", mode: "create" };
  const statusText = { ACTIVE: "正常", SUSPENDED: "已暂停", EXPIRED: "已过期", REVOKED: "已删除" };
  let token = sessionStorage.getItem("jiagu.adminToken") || "";
  let toastTimer;

  const companyDialog = $("#companyDialog");
  const tokenDialog = $("#tokenDialog");
  const keyDialog = $("#keyDialog");
  const deleteDialog = $("#deleteDialog");
  const statusDialog = $("#statusDialog");
  const form = $("#companyForm");

  function showToast(message) {
    const toast = $("#toast");
    toast.textContent = message;
    toast.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove("show"), 2400);
  }

  async function copyText(text) {
    if (!text) throw new Error("empty clipboard text");

    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(text);
        return;
      } catch (_) {
        // Continue with the compatibility path. Clipboard permission can still
        // be denied in a secure context by browser or embedding policies.
      }
    }

    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.setAttribute("readonly", "");
    textarea.setAttribute("aria-hidden", "true");
    textarea.style.position = "fixed";
    textarea.style.left = "-9999px";
    textarea.style.top = "0";
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);

    try {
      if (!document.execCommand("copy")) throw new Error("copy command rejected");
    } finally {
      textarea.remove();
    }
  }

  async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Authorization", `Bearer ${token}`);
    if (options.body !== undefined) headers.set("Content-Type", "application/json");
    const response = await fetch(path, { ...options, headers });
    if (response.status === 401) {
      token = "";
      sessionStorage.removeItem("jiagu.adminToken");
      if (!tokenDialog.open) tokenDialog.showModal();
      throw new Error("管理员 Token 无效或已失效");
    }
    if (!response.ok) {
      let message = `请求失败（${response.status}）`;
      try { message = (await response.json()).message || message; } catch (_) { /* no JSON */ }
      throw new Error(message);
    }
    if (response.status === 204) return null;
    const envelope = await response.json();
    if (!envelope.code || typeof envelope.details !== "object" || envelope.details === null) {
      throw new Error("服务端响应格式无效");
    }
    return envelope.details;
  }

  const formatDate = (seconds, forever = false) => {
    if (!seconds) return forever ? "永久有效" : "—";
    return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(seconds * 1000));
  };
  const formatNumber = (value) => new Intl.NumberFormat("zh-CN").format(value || 0);
  const dateTimeLocal = (seconds) => {
    if (!seconds) return "";
    const date = new Date(seconds * 1000 - new Date(seconds * 1000).getTimezoneOffset() * 60000);
    return date.toISOString().slice(0, 16);
  };
  const secondsFromInput = (value) => value ? Math.floor(new Date(value).getTime() / 1000) : 0;
  const initials = (id) => (id || "?").slice(0, 2).toUpperCase();

  function effectiveStatus(company) {
    const now = Date.now() / 1000;
    if (company.status === "ACTIVE" && company.authorizedUntil && company.authorizedUntil < now) return "EXPIRED";
    return company.status;
  }

  function parseExt(company) {
    try { return JSON.parse(company.extJson || "{}"); } catch (_) { return {}; }
  }

  async function loadCompanies(silent = false) {
    const tableState = $("#tableState");
    if (!silent) {
      tableState.classList.remove("hidden");
      tableState.innerHTML = '<span class="spinner"></span><p>正在读取公司信息…</p>';
    }
    try {
      const data = await request("/api/v1/companies");
      state.companies = data.items || [];
      render();
      tableState.classList.add("hidden");
      if (state.selected) {
        state.selected = state.companies.find(item => item.companyId === state.selected.companyId) || null;
        if (state.selected) renderDetail(); else closeDrawer();
      }
    } catch (error) {
      tableState.classList.remove("hidden");
      tableState.innerHTML = "";
      const p = document.createElement("p");
      p.textContent = error.message;
      tableState.append(p);
    }
  }

  function render() {
    renderMetrics();
    const query = state.query.toLowerCase();
    const items = state.companies.filter(company => {
      const matchesQuery = !query || company.companyId.toLowerCase().includes(query) || (company.description || "").toLowerCase().includes(query);
      return matchesQuery && (state.filter === "ALL" || effectiveStatus(company) === state.filter);
    });
    const rows = $("#companyRows");
    rows.replaceChildren(...items.map(companyRow));
    $("#resultCount").textContent = `共 ${items.length} 家公司`;
    const tableState = $("#tableState");
    if (!items.length) {
      tableState.classList.remove("hidden");
      tableState.replaceChildren();
      const p = document.createElement("p");
      p.textContent = state.companies.length ? "没有符合筛选条件的公司" : "尚未创建公司，点击右上角开始接入";
      tableState.append(p);
    } else {
      tableState.classList.add("hidden");
    }
  }

  function renderMetrics() {
    const now = Date.now() / 1000;
    const inThirtyDays = now + 30 * 86400;
    const active = state.companies.filter(item => effectiveStatus(item) === "ACTIVE").length;
    const expiring = state.companies.filter(item => item.status === "ACTIVE" && item.authorizedUntil > now && item.authorizedUntil <= inThirtyDays).length;
    const inactive = state.companies.filter(item => ["SUSPENDED", "REVOKED", "EXPIRED"].includes(effectiveStatus(item))).length;
    $("#totalMetric").textContent = formatNumber(state.companies.length);
    $("#activeMetric").textContent = formatNumber(active);
    $("#expiringMetric").textContent = formatNumber(expiring);
    $("#inactiveMetric").textContent = formatNumber(inactive);
  }

  function companyRow(company) {
    const tr = document.createElement("tr");
    tr.tabIndex = 0;
    tr.setAttribute("aria-label", `查看 ${company.companyId}`);
    tr.addEventListener("click", () => openDetail(company.companyId));
    tr.addEventListener("keydown", event => { if (event.key === "Enter") openDetail(company.companyId); });

    const companyTd = document.createElement("td");
    const companyCell = document.createElement("div");
    companyCell.className = "company-cell";
    const avatar = document.createElement("span");
    avatar.className = "avatar";
    avatar.textContent = initials(company.companyId);
    const labels = document.createElement("span");
    const name = document.createElement("strong");
    name.textContent = company.companyId;
    const desc = document.createElement("small");
    desc.textContent = company.description || "暂无说明";
    labels.append(name, desc);
    companyCell.append(avatar, labels);
    companyTd.append(companyCell);
    tr.append(companyTd);

    const statusTd = document.createElement("td");
    const currentStatus = effectiveStatus(company);
    const badge = document.createElement("span");
    badge.className = `status status-${currentStatus.toLowerCase()}`;
    badge.textContent = statusText[currentStatus] || currentStatus;
    statusTd.append(badge);
    tr.append(statusTd);

    const periodTd = document.createElement("td");
    periodTd.textContent = `${formatDate(company.authorizedFrom)} — ${company.authorizedUntil ? formatDate(company.authorizedUntil) : "永久"}`;
    tr.append(periodTd, quotaCell(company.packCount, company.packLimit), quotaCell(company.deliveryCount, company.deliveryLimit));

    const updatedTd = document.createElement("td");
    updatedTd.textContent = formatDate(company.updatedAt);
    const actionTd = document.createElement("td");
    const more = document.createElement("button");
    more.type = "button";
    more.className = "row-action";
    more.textContent = "›";
    more.setAttribute("aria-label", "查看详情");
    actionTd.append(more);
    tr.append(updatedTd, actionTd);
    return tr;
  }

  function quotaCell(count, limit) {
    const td = document.createElement("td");
    const wrap = document.createElement("div");
    wrap.className = "quota";
    const line = document.createElement("div");
    line.className = "quota-line";
    const used = document.createElement("span");
    used.textContent = formatNumber(count);
    const max = document.createElement("span");
    max.textContent = limit ? `/ ${formatNumber(limit)}` : "不限量";
    line.append(used, max);
    const progress = document.createElement("div");
    progress.className = "progress";
    const bar = document.createElement("i");
    bar.style.width = `${limit ? Math.min(100, count / limit * 100) : 0}%`;
    progress.append(bar);
    wrap.append(line, progress);
    td.append(wrap);
    return td;
  }

  async function openDetail(companyId) {
    try {
      state.selected = await request(`/api/v1/companies/${encodeURIComponent(companyId)}`);
      renderDetail();
      $("#drawerScrim").classList.remove("hidden");
      $("#detailDrawer").classList.add("open");
      $("#detailDrawer").setAttribute("aria-hidden", "false");
    } catch (error) { showToast(error.message); }
  }

  function renderDetail() {
    const company = state.selected;
    if (!company) return;
    $("#detailTitle").textContent = company.companyId;
    const body = $("#detailBody");
    body.replaceChildren();
    const lead = document.createElement("div");
    lead.className = "detail-lead";
    const badge = document.createElement("span");
    const currentStatus = effectiveStatus(company);
    badge.className = `status status-${currentStatus.toLowerCase()}`;
    badge.textContent = statusText[currentStatus] || currentStatus;
    const description = document.createElement("p");
    description.textContent = company.description || "暂无公司说明";
    lead.append(badge, description);
    body.append(lead);

    const grid = document.createElement("div");
    grid.className = "detail-grid";
    [
      ["授权开始", formatDate(company.authorizedFrom)], ["授权结束", formatDate(company.authorizedUntil, true)],
      ["打包用量", `${formatNumber(company.packCount)} / ${company.packLimit ? formatNumber(company.packLimit) : "不限量"}`],
      ["下发用量", `${formatNumber(company.deliveryCount)} / ${company.deliveryLimit ? formatNumber(company.deliveryLimit) : "不限量"}`],
      ["创建时间", formatDate(company.createdAt)], ["最后更新", formatDate(company.updatedAt)]
    ].forEach(([label, value]) => {
      const item = document.createElement("div");
      item.className = "detail-item";
      const small = document.createElement("small"); small.textContent = label;
      const strong = document.createElement("strong"); strong.textContent = value;
      item.append(small, strong); grid.append(item);
    });
    body.append(grid);
    const section = document.createElement("div");
    section.className = "detail-section";
    const title = document.createElement("h3"); title.textContent = "扩展信息";
    const pre = document.createElement("pre"); pre.className = "json-view"; pre.textContent = JSON.stringify(parseExt(company), null, 2);
    section.append(title, pre); body.append(section);
    const revoked = company.status === "REVOKED";
    $("#deleteButton").disabled = revoked;
    $("#deleteButton").textContent = revoked ? "已逻辑删除" : "逻辑删除";
    $("#editButton").disabled = revoked;
    const statusButton = $("#statusButton");
    const authorizationExpired = company.authorizedUntil > 0 && company.authorizedUntil < Date.now() / 1000;
    if (revoked) {
      statusButton.textContent = "删除后不可恢复";
      statusButton.disabled = true;
    } else if (authorizationExpired || company.status === "EXPIRED") {
      statusButton.textContent = "授权已过期";
      statusButton.disabled = true;
    } else if (company.status === "SUSPENDED") {
      statusButton.textContent = "恢复服务";
      statusButton.disabled = false;
    } else {
      statusButton.textContent = "暂停服务";
      statusButton.disabled = false;
    }
  }

  function closeDrawer() {
    $("#drawerScrim").classList.add("hidden");
    $("#detailDrawer").classList.remove("open");
    $("#detailDrawer").setAttribute("aria-hidden", "true");
  }

  function openCreate() {
    state.mode = "create";
    form.reset();
    form.classList.remove("editing");
    form.elements.companyId.disabled = false;
    form.elements.authorizedFrom.value = dateTimeLocal(Math.floor(Date.now() / 1000));
    form.elements.packLimit.value = "0";
    form.elements.deliveryLimit.value = "0";
    $("#formEyebrow").textContent = "公司接入";
    $("#formTitle").textContent = "新建公司";
    $("#saveButton").textContent = "创建公司";
    $("#jsonHint").textContent = "可填写合同号、联系人等自定义信息";
    $("#jsonHint").style.color = "";
    companyDialog.showModal();
  }

  function openEdit() {
    const company = state.selected;
    if (!company || company.status === "REVOKED") return;
    state.mode = "edit";
    form.reset();
    form.classList.add("editing");
    form.elements.companyId.value = company.companyId;
    form.elements.companyId.disabled = true;
    form.elements.description.value = company.description || "";
    form.elements.authorizedFrom.value = dateTimeLocal(company.authorizedFrom);
    form.elements.authorizedUntil.value = dateTimeLocal(company.authorizedUntil);
    form.elements.packLimit.value = company.packLimit;
    form.elements.deliveryLimit.value = company.deliveryLimit;
    form.elements.status.value = company.status;
    form.elements.ext.value = JSON.stringify(parseExt(company), null, 2);
    $("#formEyebrow").textContent = "授权设置";
    $("#formTitle").textContent = `编辑 ${company.companyId}`;
    $("#saveButton").textContent = "保存修改";
    $("#jsonHint").textContent = "可填写合同号、联系人等自定义信息";
    $("#jsonHint").style.color = "";
    companyDialog.showModal();
  }

  function formPayload() {
    let ext = {};
    const extValue = form.elements.ext.value.trim();
    if (extValue) ext = JSON.parse(extValue);
    if (Array.isArray(ext) || ext === null || typeof ext !== "object") throw new Error("扩展信息必须是 JSON 对象");
    const authorizedFrom = secondsFromInput(form.elements.authorizedFrom.value);
    const authorizedUntil = secondsFromInput(form.elements.authorizedUntil.value);
    if (authorizedUntil && authorizedUntil <= authorizedFrom) throw new Error("授权结束时间必须晚于开始时间");
    const payload = {
      description: form.elements.description.value.trim(), authorizedFrom, authorizedUntil,
      packLimit: Number(form.elements.packLimit.value || 0), deliveryLimit: Number(form.elements.deliveryLimit.value || 0), ext
    };
    if (state.mode === "create") payload.companyId = form.elements.companyId.value.trim();
    else payload.status = form.elements.status.value;
    return payload;
  }

  async function saveCompany(event) {
    event.preventDefault();
    const save = $("#saveButton");
    try {
      const payload = formPayload();
      save.disabled = true;
      save.textContent = state.mode === "create" ? "正在创建…" : "正在保存…";
      if (state.mode === "create") {
        const result = await request("/api/v1/companies", { method: "POST", body: JSON.stringify(payload) });
        companyDialog.close();
        $("#apiKeyOutput").textContent = result.companyApiKey;
        keyDialog.showModal();
      } else {
        state.selected = await request(`/api/v1/companies/${encodeURIComponent(state.selected.companyId)}`, { method: "PATCH", body: JSON.stringify(payload) });
        companyDialog.close();
        renderDetail();
        showToast("公司信息已更新");
      }
      await loadCompanies(true);
    } catch (error) {
      $("#jsonHint").textContent = error.message;
      $("#jsonHint").style.color = "#c83c3c";
    } finally {
      save.disabled = false;
      save.textContent = state.mode === "create" ? "创建公司" : "保存修改";
    }
  }

  async function connect(event) {
    event.preventDefault();
    token = event.currentTarget.elements.token.value.trim();
    $("#tokenError").textContent = "";
    try {
      const data = await request("/api/v1/companies");
      sessionStorage.setItem("jiagu.adminToken", token);
      state.companies = data.items || [];
      tokenDialog.close();
      render();
    } catch (error) { $("#tokenError").textContent = error.message; }
  }

  async function confirmDelete() {
    if (!state.selected) return;
    const button = $("#confirmDeleteButton");
    button.disabled = true;
    button.textContent = "正在删除…";
    try {
      await request(`/api/v1/companies/${encodeURIComponent(state.selected.companyId)}`, { method: "DELETE" });
      deleteDialog.close();
      closeDrawer();
      state.selected = null;
      await loadCompanies(true);
      showToast("公司已逻辑删除，历史数据仍然保留");
    } catch (error) { showToast(error.message); }
    finally { button.disabled = false; button.textContent = "确认删除"; }
  }

  function openStatusDialog() {
    const company = state.selected;
    if (!company || company.status === "REVOKED" || company.status === "EXPIRED") return;
    state.statusTarget = company.status === "SUSPENDED" ? "ACTIVE" : "SUSPENDED";
    const resuming = state.statusTarget === "ACTIVE";
    $("#statusSymbol").textContent = resuming ? "▶" : "Ⅱ";
    $("#statusDialogTitle").textContent = resuming ? "恢复公司服务？" : "暂停公司服务？";
    $("#statusMessage").textContent = `公司“${company.companyId}”将${resuming ? "恢复" : "暂停"}打包和设备授权服务。`;
    $("#statusNotice").textContent = resuming
      ? "恢复后，公司可在授权有效期和调用限额内继续使用服务。"
      : "暂停不会删除任何数据，之后可以随时恢复。";
    $("#confirmStatusButton").textContent = resuming ? "确认恢复" : "确认暂停";
    statusDialog.showModal();
  }

  async function confirmStatusChange() {
    if (!state.selected || !state.statusTarget) return;
    const button = $("#confirmStatusButton");
    const target = state.statusTarget;
    button.disabled = true;
    button.textContent = target === "ACTIVE" ? "正在恢复…" : "正在暂停…";
    try {
      state.selected = await request(`/api/v1/companies/${encodeURIComponent(state.selected.companyId)}`, {
        method: "PATCH", body: JSON.stringify({ status: target })
      });
      statusDialog.close();
      renderDetail();
      await loadCompanies(true);
      showToast(target === "ACTIVE" ? "公司服务已恢复" : "公司服务已暂停");
    } catch (error) { showToast(error.message); }
    finally {
      button.disabled = false;
      button.textContent = target === "ACTIVE" ? "确认恢复" : "确认暂停";
    }
  }

  $("#serviceAddress").textContent = location.host;
  $("#createButton").addEventListener("click", openCreate);
  $("#refreshButton").addEventListener("click", () => loadCompanies());
  $("#changeTokenButton").addEventListener("click", () => { $("#tokenError").textContent = ""; tokenDialog.showModal(); });
  $("#searchInput").addEventListener("input", event => { state.query = event.target.value.trim(); render(); });
  $$(".filter").forEach(button => button.addEventListener("click", () => {
    $$(".filter").forEach(item => item.classList.remove("active"));
    button.classList.add("active");
    state.filter = button.dataset.status;
    render();
  }));
  $$(".dialog-close").forEach(button => button.addEventListener("click", () => companyDialog.close()));
  $("#closeDrawerButton").addEventListener("click", closeDrawer);
  $("#drawerScrim").addEventListener("click", closeDrawer);
  $("#editButton").addEventListener("click", openEdit);
  $("#statusButton").addEventListener("click", openStatusDialog);
  $("#deleteButton").addEventListener("click", () => {
    $("#deleteMessage").textContent = `公司“${state.selected.companyId}”删除后将无法继续使用服务。`;
    deleteDialog.showModal();
  });
  $("#cancelDeleteButton").addEventListener("click", () => deleteDialog.close());
  $("#confirmDeleteButton").addEventListener("click", confirmDelete);
  $("#cancelStatusButton").addEventListener("click", () => statusDialog.close());
  $("#confirmStatusButton").addEventListener("click", confirmStatusChange);
  $("#closeKeyButton").addEventListener("click", () => keyDialog.close());
  $("#copyKeyButton").addEventListener("click", async () => {
    try { await copyText($("#apiKeyOutput").textContent); showToast("API Key 已复制"); }
    catch (_) { showToast("复制失败，请手动选择复制"); }
  });
  form.addEventListener("submit", saveCompany);
  $("#tokenForm").addEventListener("submit", connect);
  document.addEventListener("keydown", event => { if (event.key === "Escape" && $("#detailDrawer").classList.contains("open")) closeDrawer(); });

  if (token) loadCompanies(); else tokenDialog.showModal();
})();
