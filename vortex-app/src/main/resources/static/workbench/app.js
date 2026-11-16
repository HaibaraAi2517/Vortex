"use strict";

const $ = (id) => document.getElementById(id);
const state = { namespace: "personal", token: "", facts: [], cursor: null, edit: null,
  historyCursor: null, context: "", busy: false };
const labels = { ACTIVE: "有效", EXPIRED: "已到期", RETRACTED: "已撤回", DELETED: "已删除" };
const modes = { HYBRID: "混合检索", KEYWORD_ONLY: "中文关键词", VECTOR_ONLY: "向量检索" };

// Content, keys, tags and server errors are untrusted text. Never render them as HTML.
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function announce(message, error = false) {
  $("notice").textContent = message;
  $("notice").classList.toggle("error", error);
  $("notice").hidden = !message;
}

function connection(ok, text) {
  const node = $("connection-state");
  node.classList.toggle("offline", !ok);
  node.replaceChildren(element("i"), document.createTextNode(text));
}

async function api(path, { method = "GET", body, optional = false } = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 35000);
  try {
    const headers = { Accept: "application/json" };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(`/api/v1/memory${path}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal, cache: "no-store", credentials: "omit"
    });
    if (optional && response.status === 404) return null;
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const messages = {
        401: "需要 Bearer token。请打开连接设置，输入服务配置的 token。",
        403: "没有访问此记忆空间的权限。请使用被允许的 namespace。",
        404: "接口或记忆不存在。工作台需要使用 local 模式的新版服务。",
        409: "这条记忆已被其他请求更新。你的修改未保存，请关闭编辑框、刷新后重新编辑。",
        429: "请求过于频繁，请稍后重试。"
      };
      if (response.status === 401 || response.status === 403) connection(false, "等待授权");
      const error = new Error(messages[response.status] || data.message || `服务返回 HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    connection(true, "本地服务已连接");
    return data;
  } catch (error) {
    if (error.name === "AbortError" || error instanceof TypeError) {
      connection(false, "服务未连接");
      throw new Error("请求未完成。请确认本地服务正在运行；如果正在保存，请刷新记忆库确认结果后再试。");
    }
    throw error;
  } finally { clearTimeout(timeout); }
}

async function busy(action, errorTarget = null) {
  if (state.busy) return;
  state.busy = true;
  const controls = [...document.querySelectorAll("button, input, textarea, select")]
    .map((node) => [node, node.disabled]);
  controls.forEach(([node]) => { node.disabled = true; });
  $("editor-dialog").setAttribute("aria-busy", "true");
  if (errorTarget) { $(errorTarget).hidden = true; $(errorTarget).textContent = ""; }
  try { await action(); }
  catch (error) {
    if (errorTarget && $(errorTarget).closest("dialog")?.open) { $(errorTarget).textContent = error.message; $(errorTarget).hidden = false; }
    else if ($("editor-dialog").open) { $("editor-error").textContent = error.message; $("editor-error").hidden = false; }
    else announce(error.message, true);
  } finally {
    state.busy = false;
    controls.forEach(([node, wasDisabled]) => { if (node.isConnected) node.disabled = wasDisabled; });
    $("editor-dialog").setAttribute("aria-busy", "false");
  }
}

function params(values) { return new URLSearchParams(values).toString(); }
function factPath(key) { return `/facts?${params({ namespace: state.namespace, key })}`; }
function dateText(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "长期有效"; }
function tagsNode(tags = []) {
  const row = element("div", "tag-row");
  tags.forEach((tag) => row.append(element("span", "tag", tag)));
  return row;
}
function empty(title, text, symbol = "◈") {
  const node = element("div", "empty-state");
  node.append(element("span", null, symbol), element("h3", null, title), element("p", null, text));
  return node;
}

function renderLibrary() {
  $("memory-count").textContent = `${state.facts.length}${state.cursor ? "+" : ""}`;
  $("library-description").textContent = $("include-inactive").checked ? "含已撤回和已到期的事实" : "当前有效的事实";
  $("load-more").hidden = !state.cursor;
  const list = $("memory-list");
  list.replaceChildren();
  if (!state.facts.length) {
    list.append(empty("给记忆留一个开始", "新建一条偏好，或者运行上方的真实演示。"));
    return;
  }
  state.facts.forEach((fact) => {
    const card = element("article", "memory-card");
    card.dataset.key = fact.key;
    const top = element("div", "memory-card-top");
    top.append(element("span", "memory-symbol", "◈"), element("span", "memory-key", fact.key),
      element("span", `status-tag${fact.status === "ACTIVE" ? "" : " inactive"}`, labels[fact.status] || fact.status));
    const bottom = element("div", "memory-card-bottom");
    const actions = element("div", "memory-actions");
    const edit = element("button", "text-button", "编辑 / 历史 ↗");
    edit.setAttribute("aria-label", `编辑 ${fact.key}`);
    edit.addEventListener("click", () => busy(() => openEditor(fact.key)));
    actions.append(element("span", "revision", `v${fact.revision}`), edit);
    bottom.append(tagsNode(fact.tags), actions);
    card.append(top, element("p", "memory-content", fact.content || "已撤回，原内容可在版本历史中查看。"), bottom);
    list.append(card);
  });
}

async function loadFacts(append = false) {
  const query = { namespace: state.namespace, limit: "30", includeInactive: String($("include-inactive").checked) };
  if (append && state.cursor) query.afterKey = state.cursor;
  const page = await api(`/facts/list?${params(query)}`);
  state.facts = append ? [...state.facts, ...page.items] : page.items;
  state.cursor = page.nextCursor;
  renderLibrary();
}

function resetRecall() {
  state.context = "";
  $("copy-context").hidden = true;
  $("result-meta").textContent = "等待一次回忆";
  $("recall-results").replaceChildren(empty("记忆会在这里浮现", "用自然语言提问，查看可交给 Agent 的参考片段。这里展示检索结果，不生成聊天答案。", "⌕"));
}

function setNamespace(value) {
  state.namespace = value;
  $("namespace").value = value;
  $("space-name").textContent = value;
  $("space-name").title = value;
  state.facts = [];
  state.cursor = null;
  renderLibrary();
  resetRecall();
  try { localStorage.setItem("vortex.namespace", value); } catch (_) { /* Optional preference only. */ }
  const url = new URL(location.href);
  url.searchParams.set("namespace", value);
  history.replaceState(null, "", url);
}

async function recall(query, mode) {
  const started = performance.now();
  const result = await api("/recall", { method: "POST", body: { namespace: state.namespace, query,
    retrievalMode: mode, topK: 5, tokenBudget: 2048, rerankEnabled: false } });
  const hits = result.fragments || [];
  $("result-meta").textContent = `${hits.length} 条结果 · ${modes[mode]} · ${Math.round(performance.now() - started)} ms`;
  const container = $("recall-results");
  container.replaceChildren();
  state.context = hits.map((hit, i) => `[记忆 ${i + 1}] ${hit.fragment.content}`).join("\n\n");
  $("copy-context").hidden = !hits.length;
  if (!hits.length) container.append(empty("暂时没有相关记忆", "换个问法，或先保存一条相关事实。", "⌕"));
  hits.forEach((hit, i) => {
    const card = element("article", "recall-hit");
    const heading = element("div", "recall-hit-top");
    heading.append(element("span", null, `MEMORY ${String(i + 1).padStart(2, "0")}`),
      element("span", null, `排序分 ${Number(hit.score).toFixed(3)}`));
    card.append(heading, element("p", null, hit.fragment.content), tagsNode(hit.fragment.tags));
    container.append(card);
  });
  if (hits.length) container.append(element("p", "recall-note", "排序分只用于比较结果顺序，不代表答案正确率。更新或撤回的旧版本不参与普通召回。"));
  return hits;
}

function fillEditor(fact) {
  state.edit = fact;
  $("memory-form").reset();
  $("fact-key").value = fact?.key || "";
  $("fact-key").readOnly = Boolean(fact);
  $("load-key").hidden = Boolean(fact);
  $("fact-content").value = fact?.content || "";
  $("fact-tags").value = (fact?.tags || []).join(", ");
  if (fact?.expiresAt) {
    const time = new Date(fact.expiresAt);
    $("fact-expiry").value = new Date(time.getTime() - time.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }
  $("editor-title").textContent = fact ? "编辑记忆" : "新建记忆";
  $("revision-label").textContent = fact ? `当前 v${fact.revision} · ${labels[fact.status]}` : "首次保存 · v1";
  $("history-section").hidden = !fact;
  $("retract-memory").hidden = !fact || fact.status !== "ACTIVE";
  $("delete-memory").hidden = !fact || fact.status === "DELETED";
  $("delete-confirmation").hidden = true;
  $("editor-error").hidden = true;
}

async function openEditor(key = null) {
  const fact = key ? await api(factPath(key)) : null;
  fillEditor(fact);
  $("history-list").replaceChildren();
  $("history-more").hidden = true;
  if (!$("editor-dialog").open) $("editor-dialog").showModal();
  if (fact) await loadHistory(false);
}

async function loadHistory(append) {
  const query = { namespace: state.namespace, key: state.edit.key, limit: "20" };
  if (append && state.historyCursor) query.beforeRevision = state.historyCursor;
  const history = await api(`/facts/history?${params(query)}`);
  if (!append) $("history-list").replaceChildren();
  history.forEach((fact) => {
    const row = element("li");
    const heading = element("div", "history-meta");
    heading.append(element("strong", null, `v${fact.revision}`),
      element("span", null, fact.revision === state.edit.revision ? `当前版本 · ${labels[fact.status]}` : "历史版本"));
    row.append(heading, element("p", null, fact.content || labels[fact.status]),
      element("div", "history-time", dateText(fact.updatedAt)));
    $("history-list").append(row);
  });
  state.historyCursor = history.at(-1)?.revision || null;
  $("history-more").hidden = history.length < 20;
}

async function saveMemory() {
  const key = $("fact-key").value.trim();
  const content = $("fact-content").value.trim();
  const tags = [...new Set($("fact-tags").value.split(/[,，]/u).map((tag) => tag.trim()).filter(Boolean))];
  if (!key || !content) throw new Error("请填写事实标识和记忆内容。");
  if (tags.length > 32 || tags.some((tag) => tag.length > 128)) throw new Error("最多 32 个标签，每个不超过 128 字符。");
  const expiresAt = $("fact-expiry").value ? new Date($("fact-expiry").value).toISOString() : null;
  if (expiresAt && new Date(expiresAt) <= new Date()) throw new Error("有效期必须晚于当前时间；长期保存请清空有效期。");
  const fact = await api("/facts", { method: "PUT", body: { namespace: state.namespace, key, content,
    tags, expectedRevision: state.edit?.revision || 0, expiresAt } });
  $("editor-dialog").close();
  announce(`已保存 ${fact.key} · v${fact.revision}。当前事实已写入本地存储。`);
  resetRecall();
  await loadFacts();
}

async function runDemo() {
  announce("");
  const log = $("demo-log");
  log.hidden = false;
  log.replaceChildren();
  const step = (text) => log.append(element("li", null, text));
  const namespace = `demo-${new Date().toISOString().slice(0, 10)}-${crypto.randomUUID().slice(0, 8)}`;
  setNamespace(namespace);
  const put = (key, content, revision, tags) => api("/facts", { method: "PUT", body: {
    namespace, key, content, expectedRevision: revision, tags } });
  try {
    await put("user.home.city", "我现在住在北京。", 0, ["生活", "住所"]);
    const first = await recall("用户现在住在哪里？", "KEYWORD_ONLY");
    if (!first.some((hit) => hit.fragment.content === "我现在住在北京。")) throw new Error("首个住所未被召回，演示未通过。");
    step("v1：已记住并召回北京");
    await put("user.home.city", "我已经搬到上海了，现在住在上海。", 1, ["生活", "住所"]);
    step("v2：住所已更新为上海");
    await put("user.preference.coffee", "我喜欢喝美式咖啡，不加糖。", 0, ["偏好", "咖啡"]);
    await put("project.vortex.stack", "Vortex 使用 Java 21 和 Spring Boot，内嵌 Lucene 提供中文关键词与向量检索。", 0, ["项目", "技术栈"]);
    $("query").value = "用户现在住在哪里？";
    $("retrieval-mode").value = "KEYWORD_ONLY";
    const current = await recall($("query").value, "KEYWORD_ONLY");
    if (!current.some((hit) => hit.fragment.content.includes("现在住在上海")) || current.some((hit) => hit.fragment.content.includes("北京"))) {
      throw new Error("当前版本检查未通过，请查看实际检索结果。");
    }
    step("已验证：召回上海，排除旧版北京");
    await loadFacts();
    announce("真实演示已完成。点击住所的「编辑 / 历史」查看两个版本，也可以搜索咖啡偏好，或新建你自己的记忆。");
  } catch (error) {
    log.append(element("li", "failed", "演示未完成；已写入的内容保留在当前演示空间。"));
    throw error;
  }
}

$("namespace-form").addEventListener("submit", (event) => {
  event.preventDefault();
  busy(async () => {
    const value = $("namespace").value.trim();
    if (!value) throw new Error("记忆空间不能为空。");
    setNamespace(value);
    $("demo-log").hidden = true;
    announce("");
    await loadFacts();
  });
});
$("refresh").addEventListener("click", () => busy(async () => { resetRecall(); await loadFacts(); announce("已从本地存储刷新记忆库。"); }));
$("include-inactive").addEventListener("change", () => busy(() => loadFacts()));
$("load-more").addEventListener("click", () => busy(() => loadFacts(true)));
$("new-memory").addEventListener("click", () => busy(() => openEditor()));
$("run-demo").addEventListener("click", () => busy(runDemo));
$("recall-form").addEventListener("submit", (event) => {
  event.preventDefault();
  busy(async () => { announce(""); const query = $("query").value.trim(); if (!query) throw new Error("请输入要检索的问题。"); await recall(query, $("retrieval-mode").value); });
});
document.querySelectorAll("[data-query]").forEach((node) => node.addEventListener("click", () => {
  $("query").value = node.dataset.query;
  $("recall-form").requestSubmit();
}));
$("memory-form").addEventListener("submit", (event) => { event.preventDefault(); busy(saveMemory, "editor-error"); });
$("load-key").addEventListener("click", () => busy(async () => {
  const key = $("fact-key").value.trim();
  if (!key) throw new Error("先输入要读取的事实标识。");
  const fact = await api(factPath(key), { optional: true });
  if (!fact) { $("revision-label").textContent = "此 key 尚未使用，可以首次保存"; return; }
  fillEditor(fact);
  await loadHistory(false);
}, "editor-error"));
$("history-more").addEventListener("click", () => busy(() => loadHistory(true), "editor-error"));
$("retract-memory").addEventListener("click", () => busy(async () => {
  await api("/facts/retract", { method: "POST", body: { namespace: state.namespace, key: state.edit.key, expectedRevision: state.edit.revision } });
  $("editor-dialog").close();
  resetRecall();
  announce("已撤回记忆。历史仍保留，普通召回不再使用这条事实。");
  await loadFacts();
}, "editor-error"));
$("delete-memory").addEventListener("click", () => { $("delete-confirmation").hidden = false; $("confirm-delete").focus(); });
$("cancel-delete").addEventListener("click", () => { $("delete-confirmation").hidden = true; });
$("confirm-delete").addEventListener("click", () => busy(async () => {
  await api(`${factPath(state.edit.key)}&expectedRevision=${state.edit.revision}`, { method: "DELETE" });
  $("editor-dialog").close();
  resetRecall();
  announce("已删除内容与历史，仅保留防止旧写入复活的版本标记。");
  await loadFacts();
}, "editor-error"));
$("copy-context").addEventListener("click", () => busy(async () => {
  try { await navigator.clipboard.writeText(state.context); announce("已复制检索片段，可作为参考数据加入 Agent 上下文。"); }
  catch (_) { throw new Error("浏览器未允许剪贴板访问，请直接选中检索片段复制。"); }
}));
function openConnection() { if (!state.busy) $("connection-dialog").showModal(); }
$("connection-open").addEventListener("click", openConnection);
$("connection-state").addEventListener("click", openConnection);
$("connection-form").addEventListener("submit", (event) => {
  event.preventDefault();
  busy(async () => {
    state.token = $("bearer-token").value.trim();
    $("bearer-token").value = "";
    $("connection-dialog").close();
    state.facts = [];
    state.cursor = null;
    renderLibrary();
    resetRecall();
    await loadFacts();
    announce("连接成功，记忆库已同步。");
  });
});
$("guide-open").addEventListener("click", () => {
  const namespace = JSON.stringify(state.namespace);
  $("python-example").textContent = `from vortex_memory import VortexClient\n\nmemory = VortexClient(base_url=${JSON.stringify(location.origin)}, namespace=${namespace})\nkey = "user.home.city"\nold = memory.get_fact(key)\nmemory.remember(key, "我现在住在上海",\n    expected_revision=old["revision"] if old else 0)\n\ncontext = memory.context("用户现在住在哪里？")\nprint(context)`;
  $("mcp-example").textContent = JSON.stringify({ mcpServers: { vortex: { command: "python", args: ["-m", "vortex_memory.mcp"],
    env: { VORTEX_URL: location.origin, VORTEX_NAMESPACE: state.namespace } } } }, null, 2);
  $("guide-dialog").showModal();
});
document.querySelectorAll("[data-close]").forEach((node) => node.addEventListener("click", () => { if (!state.busy) $(node.dataset.close).close(); }));
document.querySelectorAll("dialog").forEach((dialog) => dialog.addEventListener("cancel", (event) => { if (state.busy) event.preventDefault(); }));

try {
  const saved = new URLSearchParams(location.search).get("namespace") || localStorage.getItem("vortex.namespace");
  if (saved && saved.length <= 128 && saved.trim() && !saved.includes("\0")) state.namespace = saved;
} catch (_) { /* Browsing works when preference storage is unavailable. */ }
setNamespace(state.namespace);
busy(() => loadFacts());
