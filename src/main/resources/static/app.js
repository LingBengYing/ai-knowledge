import { createApi, ApiError, validateUpload } from './api.mjs';
import { WorkbenchState, batchFeedback, parseTags, checkedTask, taskPending, taskLabel } from './workbench-state.mjs';
import { showNotice } from './notices.mjs';

const $ = id => document.getElementById(id);
const state = new WorkbenchState();
const controllers = new Map();
let config = null;
let principal = 'owner';
let api = createApi({}, () => principal);
let connected = false;
let folders = [];
let folderId = '';
let page = 1;
let total = 0;
let totalPages = 0;
let loading = false;
let searchTimer;
let dialogIntent = null;
let taskTimer;
let taskPollPaused = false;

function ingestionEnabled() { return config?.capabilities?.includes('text_upload') && config.capabilities.includes('ingestions'); }

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function button(text, handler, className) {
  const node = element('button', text, className);
  node.type = 'button';
  node.addEventListener('click', handler);
  return node;
}

function notice(id, message = '') {
  showNotice($, id, message);
}

function messageFor(error) {
  if (error instanceof ApiError) return error.message;
  if (error instanceof TypeError) return '无法连接 Java 服务，请确认服务已启动后重试。';
  return '服务返回的数据暂时不可用，请刷新后重试。';
}

function clearFeedback() {
  $('operation-feedback').hidden = true;
  $('feedback-items').replaceChildren();
}

function resetContext({ identity = false } = {}) {
  clearTimeout(searchTimer);
  stopTaskPolling();
  state.invalidate();
  renderTask();
  for (const controller of controllers.values()) controller.abort();
  controllers.clear();
  loading = false;
  total = 0;
  totalPages = 0;
  closeDialog();
  clearFeedback();
  notice('list-error');
  notice('global-error');
  if (identity) {
    folders = [];
    folderId = '';
    $('tag').replaceChildren(new Option('全部标签', ''));
    renderFolders();
    notice('folder-error');
  }
  renderRows();
  renderDetails();
  renderControls();
}

function authenticationFailed(error) {
  if (!(error instanceof ApiError) || error.status !== 401) return false;
  connected = false;
  resetContext({ identity: true });
  $('identity-status').textContent = '身份未通过验证，请重新建立会话或切换开发身份。';
  notice('global-error', messageFor(error));
  return true;
}

async function read(kind, path, success, errorId) {
  controllers.get(kind)?.abort();
  const controller = new AbortController();
  controllers.set(kind, controller);
  const ticket = state.beginRead(kind);
  try {
    const result = await api(path, { signal: controller.signal });
    if (state.isCurrent(ticket)) success(result, ticket);
  } catch (error) {
    if (error.name !== 'AbortError' && state.isCurrent(ticket) && !authenticationFailed(error)) {
      notice(errorId, messageFor(error));
    }
  } finally {
    if (state.isCurrent(ticket)) {
      controllers.delete(kind);
      if (kind === 'documents') {
        loading = false;
        renderRows();
        renderDetails();
        renderControls();
        renderTask();
        scheduleTask();
      }
    }
  }
}

function documentQuery() {
  const query = new URLSearchParams({ page: String(page), page_size: $('page-size').value, sort: $('sort').value });
  for (const [key, value] of [['q', $('search').value.trim()], ['type', $('type').value], ['status', $('status').value], ['tag', $('tag').value], ['folder_id', folderId]]) {
    if (value) query.set(key, value);
  }
  return query;
}

function loadDocuments() {
  if (!connected) return Promise.resolve();
  loading = true;
  notice('list-error');
  renderRows();
  renderControls();
  return read('documents', `/v1/management/documents?${documentQuery()}`, (result, ticket) => {
    if (!Array.isArray(result?.items) || !Number.isInteger(result.total) || !Number.isInteger(result.total_pages)) throw new Error('invalid page');
    total = result.total;
    totalPages = result.total_pages;
    if (totalPages > 0 && page > totalPages) {
      page = totalPages;
      resetContext();
      loadData();
      return;
    }
    state.commitPage(ticket, result.items);
  }, 'list-error');
}

function loadFolders() {
  notice('folder-error');
  return read('folders', '/v1/management/folders', result => {
    if (!Array.isArray(result?.items)) throw new Error('invalid folders');
    folders = result.items;
    renderFolders();
    const current = $('detail-folder');
    if (current) {
      const selected = current.value;
      const replacement = folderSelect('detail-folder', selected);
      current.replaceChildren(...replacement.options);
      current.value = selected;
    }
  }, 'folder-error');
}

function loadTags() {
  return read('tags', '/v1/management/tags', result => {
    if (!Array.isArray(result?.items)) throw new Error('invalid tags');
    const selected = $('tag').value;
    $('tag').replaceChildren(new Option('全部标签', ''));
    const values = [...new Set([...result.items, ...(selected ? [selected] : [])])];
    for (const value of values) $('tag').append(new Option(value, value));
    $('tag').value = selected;
  }, 'global-error');
}

function loadData() {
  if (!connected) return Promise.resolve();
  return Promise.all([loadDocuments(), loadFolders(), loadTags()]);
}

function changeFilter({ delayed = false } = {}) {
  page = 1;
  resetContext();
  if (delayed) searchTimer = setTimeout(loadData, 250);
  else loadData();
}

function renderControls() {
  const busy = state.mutating;
  const unavailable = !connected || busy;
  $('refresh').disabled = unavailable || loading;
  $('new-folder').disabled = unavailable;
  $('upload').disabled = unavailable || !ingestionEnabled();
  $('upload').title = ingestionEnabled() ? 'PDF / TXT / Markdown，1字节至20MiB' : '服务未启用文本上传';
  for (const id of ['task-refresh', 'task-dismiss']) $(id).disabled = unavailable;
  $('task-cancel').disabled = unavailable || !state.task?.can_cancel;
  $('task-retry').disabled = unavailable || !state.task?.can_retry;
  $('select-page').disabled = unavailable || loading || state.items.length === 0;
  $('select-page').checked = state.items.length > 0 && state.selected.size === state.items.length;
  $('select-page').indeterminate = state.selected.size > 0 && state.selected.size < state.items.length;
  $('selection-count').textContent = `已选 ${state.selected.size} 项`;
  for (const id of ['batch-move', 'batch-tag', 'clear-selection']) $(id).disabled = unavailable || loading || state.selected.size === 0;
  $('previous-page').disabled = unavailable || loading || page <= 1;
  $('next-page').disabled = unavailable || loading || page >= totalPages;
  $('list-count').textContent = loading ? '正在读取授权资料…' : `共 ${total} 份资料`;
  $('page-label').textContent = `第 ${totalPages ? page : 0} / ${totalPages} 页`;
  $('current-folder').textContent = folderId === 'unfiled' ? '未分类' : folders.find(folder => folder.folder_id === folderId)?.name ?? '全部资料';
  $('table-region').setAttribute('aria-busy', String(loading));
  for (const node of $('filters').elements) node.disabled = unavailable;
  $('page-size').disabled = unavailable || loading;
  for (const node of document.querySelectorAll('[data-mutation-control]')) node.disabled = unavailable || loading;
  for (const node of document.querySelectorAll('[data-detail-control]')) node.disabled = unavailable || loading || !state.detail?.can_edit;
  for (const node of $('jwt-identity').querySelectorAll('button')) node.disabled = state.identityPending;
  $('dialog-submit').disabled = busy;
  $('dialog-cancel').disabled = busy;
  $('close-detail').disabled = busy;
}

function renderFolders() {
  const list = $('folder-list');
  list.replaceChildren();
  for (const folder of [{ folder_id: '', name: '全部资料' }, { folder_id: 'unfiled', name: '未分类' }, ...folders]) {
    const row = element('div', undefined, 'folder-row');
    const select = button('', () => { folderId = folder.folder_id; changeFilter(); renderFolders(); }, 'folder-filter');
    select.classList.toggle('selected', folderId === folder.folder_id);
    select.setAttribute('aria-current', folderId === folder.folder_id ? 'page' : 'false');
    select.append(element('span', folder.name));
    if (folder.document_count !== undefined) select.append(element('span', folder.document_count, 'muted'));
    select.disabled = !connected || state.mutating;
    row.append(select);
    if (folder.can_edit) {
      const rename = button('改名', () => folderDialog('rename', folder), 'folder-more');
      const remove = button('删除', () => folderDialog('remove', folder), 'folder-more');
      rename.setAttribute('aria-label', `重命名目录：${folder.name}`);
      remove.setAttribute('aria-label', `删除目录：${folder.name}`);
      rename.dataset.mutationControl = '';
      remove.dataset.mutationControl = '';
      row.append(rename, remove);
    }
    list.append(row);
  }
  renderControls();
}

function sizeLabel(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '大小不可得';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function dateLabel(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间不可得' : date.toLocaleString('zh-CN', { hour12: false });
}

const types = { document: '文档', image: '图片', audio: '音频', video: '视频' };
const statuses = { ready: '演示就绪', queued: '等待解析', processing: '正在解析', parsed: '已解析 · 未索引', failed: '解析失败', cancelled: '已取消' };
const roles = { owner: '所有者', editor: '可编辑', reader: '只读' };

function openDetail(id) {
  if (loading || state.mutating) return;
  state.openDetail(id);
  renderRows();
  renderDetails();
  $('detail-heading').tabIndex = -1;
  $('detail-heading').focus();
}

function renderRows() {
  const rows = $('document-rows');
  rows.replaceChildren();
  $('list-empty').hidden = loading || state.items.length > 0;
  if (loading) {
    for (let i = 0; i < 5; i += 1) {
      const row = element('tr');
      row.setAttribute('aria-hidden', 'true');
      const cell = element('td');
      cell.colSpan = 7;
      cell.append(element('div', undefined, 'skeleton'), element('div', undefined, 'skeleton short'));
      row.append(cell); rows.append(row);
    }
    return;
  }
  const empty = $('list-empty');
  empty.replaceChildren(element('strong', connected ? '当前筛选下没有资料' : '请先确认访问身份'), element('p', connected ? (ingestionEnabled() ? '上传PDF、TXT或Markdown，或试试清除筛选条件。解析完成后仍未索引，不能问答。' : '试试清除筛选条件。当前服务未启用文本上传，合成演示资料需由Java显式seed工具创建。') : '只有通过服务器身份验证后，才会显示有权访问的资料。'));
  for (const item of state.items) {
    const row = element('tr');
    row.classList.toggle('active', state.detail?.document_id === item.document_id);
    const checkCell = element('td');
    const checkbox = element('input');
    checkbox.type = 'checkbox';
    checkbox.checked = state.selected.has(item.document_id);
    checkbox.disabled = state.mutating;
    checkbox.setAttribute('aria-label', `选择资料：${item.display_name}`);
    checkbox.dataset.documentId = item.document_id;
    checkbox.addEventListener('change', () => { state.select(item.document_id, checkbox.checked); renderControls(); });
    checkCell.append(checkbox);
    const name = element('td');
    name.append(button(item.display_name, () => openDetail(item.document_id), 'document-name'), element('div', item.filename, 'filename'));
    if (item.synthetic_fixture) name.append(element('span', 'synthetic_fixture · 合成资料', 'fixture-badge'));
    const type = element('td', undefined, 'optional');
    type.append(element('div', types[item.document_type] ?? item.media_info?.mime_type ?? '未知类型'), element('div', sizeLabel(item.media_info?.size_bytes), 'filename'));
    const classification = element('td', undefined, 'optional');
    classification.append(element('div', item.folder_name ?? '未分类'));
    const tags = element('div', undefined, 'tag-list');
    for (const tag of item.tags ?? []) tags.append(element('span', tag, 'tag'));
    classification.append(tags);
    const status = element('td'); status.append(element('span', statuses[item.status] ?? item.status, 'status-badge'));
    const updated = element('td', undefined, 'optional');
    updated.append(element('div', dateLabel(item.updated_at)), element('div', roles[item.current_role] ?? '未知权限', 'filename'));
    const action = element('td'); action.append(button('详情', () => openDetail(item.document_id), 'row-detail'));
    if (ingestionEnabled() && item.latest_job) action.append(button('任务', () => openTask(item), 'row-detail'));
    row.append(checkCell, name, type, classification, status, updated, action);
    rows.append(row);
  }
}

function folderSelect(id, selected) {
  const select = element('select'); select.id = id;
  select.append(new Option('未分类', ''));
  for (const folder of folders) select.append(new Option(folder.name, folder.folder_id));
  if (selected && !folders.some(folder => folder.folder_id === selected)) select.append(new Option('当前目录（等待刷新）', selected));
  select.value = selected ?? '';
  return select;
}

function field(labelText, input) {
  const label = element('label', labelText);
  label.htmlFor = input.id;
  label.append(input);
  return label;
}

function checkedTags(value, requireNonempty = false) {
  const tags = parseTags(value);
  if ((requireNonempty && !tags.length) || tags.length > 20 || tags.some(tag => tag.length > 40)) {
    throw new ApiError(422, '标签最多 20 个，每个最多 40 字；批量添加时至少填写一个标签。');
  }
  return tags;
}

function renderDetails() {
  const container = $('detail-content');
  container.replaceChildren();
  const item = state.detail;
  $('close-detail').hidden = !item;
  container.className = item ? '' : 'detail-empty';
  if (!item) {
    container.append(element('strong', '选择一份资料'), element('p', '点击列表中的名称，查看文件信息并修改显示名称、目录或标签。'), element('p', '真实上传资料可查看解析任务，尚无正文、摘要和来源预览。'));
    return;
  }
  container.append(element('h3', item.display_name, 'detail-title'), element('span', item.synthetic_fixture ? 'synthetic_fixture · 合成验证资料' : '资料元数据', 'fixture-badge'));
  if (ingestionEnabled() && item.latest_job) container.append(button('查看解析任务', () => openTask(item), 'detail-task'));
  const form = element('form', undefined, 'detail-form');
  const name = element('input'); name.id = 'detail-name'; name.value = item.display_name; name.maxLength = 255; name.required = true;
  const folder = folderSelect('detail-folder', item.folder_id);
  const tags = element('textarea'); tags.id = 'detail-tags'; tags.value = (item.tags ?? []).join('，'); tags.maxLength = 1000;
  for (const node of [name, folder, tags]) { node.disabled = !item.can_edit; node.dataset.detailControl = ''; }
  form.append(field('显示名称', name), element('p', '仅修改显示名称，不改原文件名、版本或内容哈希。', 'help-text'), field('所在目录', folder), field('设置标签', tags), element('p', '用逗号或换行分隔。保存会替换本资料全部标签；留空清除。', 'help-text'));
  const error = element('p', undefined, 'notice error'); error.id = 'detail-error'; error.hidden = true;
  form.append(error);
  if (item.can_edit) {
    const save = element('button', '保存整理信息', 'primary'); save.type = 'submit'; save.dataset.mutationControl = ''; form.append(save);
  } else form.append(element('p', '当前身份为只读，不能修改这份资料。', 'help-text'));
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!item.can_edit || state.detail?.document_id !== item.document_id) return;
    let payload;
    try { payload = { display_name: name.value.trim(), folder_id: folder.value || null, tags: checkedTags(tags.value) }; }
    catch (failure) { notice('detail-error', messageFor(failure)); return; }
    mutate(() => api(`/v1/management/documents/${encodeURIComponent(item.document_id)}`, { method: 'PATCH', body: payload }), () => {
      feedback('资料整理已保存', [{ document_id: item.document_id, ok: true, detail: `${item.display_name}：显示名称、目录及标签已保存。` }]);
      return loadData();
    }, 'detail-error');
  });
  container.append(form);
  const metadata = element('dl', undefined, 'metadata');
  for (const [label, value] of [['原文件名', item.filename], ['类型 / 大小', `${item.media_info?.mime_type ?? '不可得'} / ${sizeLabel(item.media_info?.size_bytes)}`], ['当前权限', roles[item.current_role] ?? item.current_role], ['更新于', dateLabel(item.updated_at)], ['资料 ID', item.document_id], ['版本 ID', item.active_revision_id], ['内容 SHA-256', item.media_info?.sha256 ?? '不可得']]) metadata.append(element('dt', label), element('dd', value));
  container.append(metadata);
  const unavailable = element('div', undefined, 'detail-unavailable');
  unavailable.append(element('p', '正文、摘要、来源预览和生命周期操作尚未迁移。'));
  const actions = element('div');
  for (const label of ['查看来源 · 迁移中', '重建 · 迁移中', '删除 · 迁移中']) {
    const action = button(label, () => {}); action.disabled = true; actions.append(action);
  }
  unavailable.append(actions); container.append(unavailable);
  renderControls();
}

function feedback(title, items) {
  $('operation-feedback').hidden = false;
  $('operation-feedback').classList.toggle('failure', items.some(item => !item.ok));
  $('feedback-title').textContent = title;
  $('feedback-items').replaceChildren(...items.map(item => element('li', `${item.ok ? '成功' : '失败'} · ${item.detail}`, item.ok ? '' : 'failed')));
}

function stopTaskPolling() {
  clearTimeout(taskTimer);
  controllers.get('ingestion')?.abort();
  controllers.delete('ingestion');
  state.reads.delete('ingestion');
}

function renderTask() {
  const task = state.task;
  $('task-panel').hidden = !task;
  $('task-metadata').replaceChildren();
  if (!task) {
    $('task-status').textContent = '';
    notice('task-error');
    return;
  }
  $('task-status').textContent = `${taskLabel(task.state)} · 第${task.attempt}/3次尝试${taskPending(task) && !taskPollPaused ? ' · 自动刷新中' : ''}`;
  for (const [label, value] of [['任务编号', task.task_id], ['资料编号', task.document_id], ['解析版本', task.revision_id], ...(task.error_code ? [['失败代码', task.error_code]] : [])]) {
    $('task-metadata').append(element('dt', label), element('dd', value));
  }
  $('task-cancel').hidden = !task.can_cancel;
  $('task-retry').hidden = !task.can_retry;
  $('task-boundary').textContent = task.state === 'parsed'
    ? '原文件已解析并保存版本化结果，但尚未索引、发布可检索版本或接通问答。'
    : task.state === 'failed' || task.state === 'cancelled'
      ? '不会自动重试。只有服务器允许时才能显式重试；最多3次尝试。'
      : '任务已接收不代表解析完成。关闭或收起此页面不会取消服务器任务，请使用“取消解析”。';
}

function scheduleTask() {
  clearTimeout(taskTimer);
  if (connected && ingestionEnabled() && taskPending(state.task) && !taskPollPaused) taskTimer = setTimeout(loadTask, 1500);
}

function watchTask(value) {
  stopTaskPolling();
  state.watchTask(value);
  taskPollPaused = false;
  notice('task-error');
  renderTask();
  renderControls();
  scheduleTask();
}

function openTask(item) {
  if (!connected || state.mutating || loading || !ingestionEnabled()) return;
  try {
    const task = state.taskForDocument(item.document_id);
    watchTask(task);
    $('task-heading').focus();
    loadTask();
  } catch { notice('global-error', '任务信息暂时不可用，请刷新资料列表后重试。'); }
}

async function loadTask() {
  const task = state.task;
  if (!task || !connected || !ingestionEnabled()) return;
  if (state.mutating) { scheduleTask(); return; }
  stopTaskPolling();
  const controller = new AbortController();
  controllers.set('ingestion', controller);
  const ticket = state.beginRead('ingestion');
  try {
    const result = await api(`/v1/ingestions/${encodeURIComponent(task.task_id)}`, { signal: controller.signal });
    if (!state.commitTask(ticket, result)) return;
    notice('task-error');
    renderTask();
    renderRows();
    renderControls();
  } catch (error) {
    if (error.name === 'AbortError' || !state.isCurrent(ticket)) return;
    if (authenticationFailed(error)) return;
    if (error instanceof ApiError && [403, 404].includes(error.status)) {
      resetContext();
      notice('global-error', '任务已不可访问，请按当前权限重新查看资料。');
      await loadData();
      return;
    }
    taskPollPaused = true;
    notice('task-error', `${messageFor(error)} 自动刷新已暂停，请手动刷新任务核对结果。`);
    renderTask();
  } finally {
    if (state.isCurrent(ticket)) { controllers.delete('ingestion'); scheduleTask(); }
  }
}

function taskAction(action) {
  const before = state.task;
  if (!before || state.mutating || !ingestionEnabled() || (action === 'cancel' ? !before.can_cancel : !before.can_retry)) return;
  stopTaskPolling();
  mutate(() => api(`/v1/ingestions/${encodeURIComponent(before.task_id)}/${action}`, { method: 'POST' }), result => {
    const task = checkedTask(result);
    if (task.task_id !== before.task_id || task.document_id !== before.document_id || task.revision_id !== before.revision_id
      || task.attempt !== before.attempt + (action === 'retry' ? 1 : 0)) throw new Error('invalid task action result');
    watchTask(task);
    return loadData();
  }, 'task-error');
}

async function mutate(request, success, errorId) {
  if (!connected) return;
  const ticket = state.beginMutation();
  if (!ticket) return;
  notice(errorId);
  renderControls();
  renderFolders();
  try {
    const result = await request();
    if (!state.finishMutation(ticket)) return;
    await success(result);
  } catch (error) {
    if (!state.finishMutation(ticket) && ticket.epoch !== state.epoch) return;
    if (!authenticationFailed(error)) notice(errorId, messageFor(error));
  } finally {
    renderControls();
    renderFolders();
    scheduleTask();
  }
}

function closeDialog() {
  if ($('edit-dialog').open) $('edit-dialog').close();
  dialogIntent = null;
}

function showDialog(title, description, fields, intent, submitText) {
  if (!connected || state.mutating) return;
  dialogIntent = intent;
  $('dialog-title').textContent = title;
  $('dialog-description').textContent = description;
  $('dialog-fields').replaceChildren(...fields);
  $('dialog-submit').textContent = submitText;
  notice('dialog-error');
  $('edit-dialog').showModal();
  $('dialog-fields').querySelector('input, select, textarea')?.focus();
}

function folderDialog(kind, folder = null) {
  const fields = [];
  if (kind !== 'remove') {
    const input = element('input'); input.id = 'dialog-name'; input.required = true; input.maxLength = 100; input.value = folder?.name ?? '';
    fields.push(field('目录名称', input));
  }
  showDialog(kind === 'create' ? '新建目录' : kind === 'rename' ? '重命名目录' : '删除空目录', kind === 'remove' ? `确认删除“${folder.name}”？只允许删除空目录，不会删除资料；非空目录将由服务器拒绝。` : '单层目录只用于整理资料，不改变任何访问权限。', fields, { kind, folder }, kind === 'remove' ? '确认删除空目录' : '保存目录');
}

function batchDialog(action) {
  if (!state.selected.size) return;
  const ids = [...state.selected];
  const labels = new Map(state.items.map(item => [item.document_id, item.display_name]));
  const input = action === 'move' ? folderSelect('dialog-folder', null) : element('textarea');
  if (action === 'tag') { input.id = 'dialog-tags'; input.required = true; input.maxLength = 1000; }
  showDialog(action === 'move' ? `移动 ${ids.length} 份资料` : `为 ${ids.length} 份资料添加标签`, action === 'move' ? '每份资料单独检查操作权限，结果逐项返回。选择“未分类”可移出目录。' : '新标签会追加并去重，保留每份资料已有标签。不会覆盖其他标签。', [field(action === 'move' ? '目标目录' : '添加标签（逗号或换行分隔）', input)], { kind: 'batch', action, ids, labels }, '确认批量整理');
}

$('dialog-form').addEventListener('submit', event => {
  event.preventDefault();
  const intent = dialogIntent;
  if (!intent || state.mutating || !connected) return;
  if (intent.kind === 'upload') {
    if (!ingestionEnabled()) return;
    let file;
    try { file = validateUpload($('upload-file').files[0]); }
    catch (error) { notice('dialog-error', messageFor(error)); return; }
    const controller = new AbortController();
    controllers.set('upload', controller);
    mutate(async () => {
      try { return await api(`/v1/documents?filename=${encodeURIComponent(file.name)}`, { method: 'POST', file, signal: controller.signal }); }
      finally { if (controllers.get('upload') === controller) controllers.delete('upload'); }
    }, result => {
      const task = checkedTask(result);
      closeDialog();
      $('filters').reset();
      folderId = ''; page = 1;
      resetContext();
      watchTask(task);
      feedback('上传任务已创建', [{ ok: true, detail: '原文件已接收，正在等待解析；这不是索引或问答完成。' }]);
      $('task-heading').focus();
      return loadData();
    }, 'dialog-error');
  } else if (intent.kind === 'batch') {
    let body;
    try { body = { document_ids: intent.ids, action: intent.action, ...(intent.action === 'move' ? { folder_id: $('dialog-folder').value || null } : { tags: checkedTags($('dialog-tags').value, true) }) }; }
    catch (error) { notice('dialog-error', messageFor(error)); return; }
    mutate(() => api('/v1/management/document-actions', { method: 'POST', body }), result => {
      const report = batchFeedback(intent.ids, result?.items);
      closeDialog();
      state.selectPage(false);
      feedback(`批量结果：${report.succeeded} 项成功，${report.failed} 项失败`, report.items.map(item => ({ ...item, detail: `${intent.labels.get(item.document_id) ?? item.document_id}：${item.detail}` })));
      return loadData();
    }, 'dialog-error');
  } else {
    const path = intent.kind === 'create' ? '/v1/management/folders' : `/v1/management/folders/${encodeURIComponent(intent.folder.folder_id)}`;
    const method = intent.kind === 'create' ? 'POST' : intent.kind === 'rename' ? 'PATCH' : 'DELETE';
    const body = intent.kind === 'remove' ? undefined : { name: $('dialog-name').value.trim() };
    mutate(() => api(path, { method, body }), () => {
      closeDialog();
      if (intent.kind === 'remove' && folderId === intent.folder.folder_id) { folderId = ''; page = 1; resetContext(); }
      feedback('目录操作已完成', [{ ok: true, detail: intent.kind === 'remove' ? '空目录已删除，资料未删除。' : '目录名称已保存。' }]);
      return loadData();
    }, 'dialog-error');
  }
});

$('dialog-cancel').addEventListener('click', () => { if (!state.mutating) closeDialog(); });
$('edit-dialog').addEventListener('cancel', event => { if (state.mutating) event.preventDefault(); else dialogIntent = null; });
$('new-folder').addEventListener('click', () => folderDialog('create'));
$('upload').addEventListener('click', () => {
  if (!ingestionEnabled()) return;
  const input = element('input'); input.id = 'upload-file'; input.type = 'file'; input.required = true;
  input.accept = '.pdf,.txt,.md,application/pdf,text/plain,text/markdown';
  showDialog('上传文本资料', '支持PDF、TXT和Markdown，1字节至20MiB。文件会作为当前身份的资料保存并异步解析；图片、音频和视频暂不支持。', [field('选择原文件', input), element('p', '上传后请查看任务状态。已解析不等于已索引，不能用于问答。', 'help-text')], { kind: 'upload' }, '上传并解析');
});
$('task-refresh').addEventListener('click', () => { taskPollPaused = false; notice('task-error'); loadTask(); });
$('task-cancel').addEventListener('click', () => taskAction('cancel'));
$('task-retry').addEventListener('click', () => taskAction('retry'));
$('task-dismiss').addEventListener('click', () => watchTask(null));
$('batch-move').addEventListener('click', () => batchDialog('move'));
$('batch-tag').addEventListener('click', () => batchDialog('tag'));
$('dismiss-feedback').addEventListener('click', clearFeedback);
$('close-detail').addEventListener('click', () => {
  if (!state.closeDetail()) return;
  renderDetails();
  renderRows();
  $('documents-heading').focus();
});
$('refresh').addEventListener('click', () => { resetContext(); loadData(); });
$('select-page').addEventListener('change', event => {
  state.selectPage(event.target.checked);
  for (const input of document.querySelectorAll('input[data-document-id]')) input.checked = state.selected.has(input.dataset.documentId);
  renderControls();
});
$('clear-selection').addEventListener('click', () => { state.selectPage(false); renderRows(); renderControls(); });
$('search').addEventListener('input', () => changeFilter({ delayed: true }));
$('filters').addEventListener('submit', event => { event.preventDefault(); changeFilter(); });
for (const id of ['type', 'status', 'tag', 'sort', 'page-size']) $(id).addEventListener('change', () => changeFilter());
$('clear-filters').addEventListener('click', () => { $('filters').reset(); folderId = ''; changeFilter(); renderFolders(); });
for (const [id, delta] of [['previous-page', -1], ['next-page', 1]]) $(id).addEventListener('click', () => { page += delta; resetContext(); loadData(); });

$('principal').addEventListener('input', () => {
  connected = false;
  resetContext({ identity: true });
  $('identity-status').textContent = '身份已修改，点击“切换身份”重新加载授权资料。';
});
$('dev-identity').addEventListener('submit', event => {
  event.preventDefault();
  principal = $('principal').value.trim();
  if (!principal || !config) return;
  page = 1;
  connected = true;
  resetContext({ identity: true });
  $('identity-status').textContent = `开发身份：${principal} · 组织：${config.workspace_id}`;
  loadData();
});
$('jwt-identity').addEventListener('submit', async event => {
  event.preventDefault();
  const token = $('token').value.trim();
  if (!token || !config || !state.beginIdentityChange()) return;
  $('token').value = '';
  connected = false;
  page = 1;
  resetContext({ identity: true });
  const ticket = state.beginRead('identity');
  $('identity-status').textContent = '正在由服务器校验凭证…';
  try {
    const result = await api('/v1/session', { method: 'POST', body: { token } });
    if (!state.isCurrent(ticket)) return;
    connected = true;
    $('identity-status').textContent = `会话身份：${result.principal_id} · 组织：${result.workspace_id}`;
    await loadData();
  } catch (error) { if (state.isCurrent(ticket)) notice('global-error', messageFor(error)); }
  finally { state.finishIdentityChange(); renderControls(); }
});
$('sign-out').addEventListener('click', async () => {
  if (!state.beginIdentityChange()) return;
  connected = false;
  $('token').value = '';
  resetContext({ identity: true });
  const ticket = state.beginRead('identity');
  $('identity-status').textContent = '正在退出会话…';
  try {
    await api('/v1/session', { method: 'DELETE' });
    if (state.isCurrent(ticket)) $('identity-status').textContent = '会话已退出；凭证只由 HttpOnly Cookie 管理。';
  } catch (error) { if (state.isCurrent(ticket)) notice('global-error', '服务端退出尚未确认，请重试退出后再离开。'); }
  finally { state.finishIdentityChange(); renderControls(); }
});

async function start() {
  try {
    config = await api('/v1/config');
    if (!['development_headers', 'jwt'].includes(config?.auth_mode) || !config.capabilities?.includes('management')) throw new Error('unsupported config');
    api = createApi(config, () => principal);
    $('scope-title').textContent = ingestionEnabled() ? '当前可用：资料整理 + 文本解析' : '当前可用：资料整理';
    $('scope-description').textContent = ingestionEnabled() ? '支持PDF/TXT/Markdown真实上传。已解析仍未索引，问答不可用；synthetic_fixture合成资料单独标识。' : '服务尚未启用文本上传。合成资料会单独标识，不代表已完成解析、索引或问答。';
    $('dev-identity').hidden = config.auth_mode !== 'development_headers';
    $('jwt-identity').hidden = config.auth_mode !== 'jwt';
    connected = true;
    $('identity-status').textContent = config.auth_mode === 'development_headers' ? `开发身份：${principal} · 组织：${config.workspace_id}` : `组织：${config.workspace_id} · 会话身份由服务器校验`;
    renderFolders();
    await loadData();
  } catch (error) {
    connected = false;
    notice('global-error', messageFor(error));
    $('identity-status').textContent = '无法读取 Java 服务配置。请确认服务就绪后刷新页面。';
    renderControls();
  }
}

start();
