/** UI state contains no credentials and cannot expand a server-authorized page. */
export class WorkbenchState {
  epoch = 0;
  sequence = 0;
  reads = new Map();
  items = [];
  selected = new Set();
  detail = null;
  mutation = null;
  identityPending = false;
  task = null;
  indexTask = null;

  invalidate() {
    this.epoch += 1;
    this.reads.clear();
    this.items = [];
    this.selected.clear();
    this.detail = null;
    this.mutation = null;
    this.task = null;
    this.indexTask = null;
  }

  beginRead(kind) {
    const ticket = { epoch: this.epoch, sequence: ++this.sequence, kind };
    this.reads.set(kind, ticket);
    return ticket;
  }

  isCurrent(ticket) {
    return ticket?.epoch === this.epoch && this.reads.get(ticket.kind) === ticket;
  }

  commitPage(ticket, items) {
    if (!this.isCurrent(ticket)) return false;
    // A list request may have captured an earlier attempt before a task poll/action finished.
    // Reconcile only existing authorized rows; never add a task's document to the page.
    this.items = items.map(item => this.reconcileTaskRow(this.reconcileTaskRow(item, false), true));
    const available = new Set(items.map(item => item.document_id));
    this.selected = new Set([...this.selected].filter(id => available.has(id)));
    this.detail = this.items.find(item => item.document_id === this.detail?.document_id) ?? null;
    return true;
  }

  reconcileTaskRow(item, index) {
    const slot = index ? 'indexTask' : 'task';
    const field = index ? 'latest_index_job' : 'latest_job';
    const current = this[slot];
    if (index && current && item.document_id === current.document_id && !item[field]
      && item.latest_job?.document_id === current.document_id && item.latest_job?.revision_id === current.revision_id) {
      return { ...item, index_status: current.state, latest_index_job: current };
    }
    if (!current || item.document_id !== current.document_id || item[field]?.task_id !== current.task_id
      || item[field]?.revision_id !== current.revision_id) return item;
    const listed = index ? checkedIndexTask(item[field]) : checkedTask(item[field]);
    if (listed.document_id !== item.document_id) throw new Error('invalid task document');
    if (taskAdvances(current, listed)) this[slot] = listed;
    return { ...item, [index ? 'index_status' : 'status']: this[slot].state, [field]: this[slot] };
  }

  select(id, selected) {
    if (!this.items.some(item => item.document_id === id)) return;
    if (selected) this.selected.add(id);
    else this.selected.delete(id);
  }

  selectPage(selected) {
    this.selected = new Set(selected ? this.items.map(item => item.document_id) : []);
  }

  openDetail(id) {
    this.detail = this.items.find(item => item.document_id === id) ?? null;
    return this.detail;
  }

  closeDetail() {
    if (this.mutating) return false;
    this.detail = null;
    return true;
  }

  get mutating() { return this.mutation !== null; }

  beginMutation() {
    if (this.mutating) return null;
    this.mutation = { epoch: this.epoch, sequence: ++this.sequence };
    return this.mutation;
  }

  finishMutation(ticket) {
    if (this.mutation !== ticket || ticket.epoch !== this.epoch) return false;
    this.mutation = null;
    return true;
  }

  beginIdentityChange() {
    if (this.identityPending) return false;
    this.identityPending = true;
    return true;
  }

  finishIdentityChange() { this.identityPending = false; }

  watchTask(value) {
    this.reads.delete('ingestion');
    this.task = value === null ? null : checkedTask(value);
    return this.task;
  }

  taskForDocument(id) {
    // Detail controls can outlive their captured row; resolve only the current authorized page.
    const item = this.items.find(row => row.document_id === id);
    if (!item) throw new Error('unavailable task document');
    const task = checkedTask(item.latest_job);
    if (task.document_id !== id) throw new Error('invalid task document');
    return task;
  }

  commitTask(ticket, value) {
    if (!this.isCurrent(ticket) || ticket.kind !== 'ingestion' || !this.task) return false;
    const next = checkedTask(value);
    if (next.task_id !== this.task.task_id || next.document_id !== this.task.document_id || next.revision_id !== this.task.revision_id) {
      throw new Error('invalid task identity');
    }
    if (next.attempt < this.task.attempt) return false;
    if (!taskAdvances(this.task, next)) throw new Error('invalid task transition');
    this.task = next;
    this.items = this.items.map(item => item.document_id === next.document_id ? { ...item, status: next.state, latest_job: next } : item);
    if (this.detail?.document_id === next.document_id) this.detail = this.items.find(item => item.document_id === next.document_id) ?? this.detail;
    return true;
  }

  watchIndexTask(value) {
    this.reads.delete('indexing');
    this.indexTask = value === null ? null : checkedIndexTask(value);
    return this.indexTask;
  }

  indexTaskForDocument(id) {
    const item = this.items.find(row => row.document_id === id);
    if (!item) throw new Error('unavailable task document');
    const task = checkedIndexTask(item.latest_index_job);
    if (task.document_id !== id) throw new Error('invalid task document');
    return task;
  }

  commitIndexTask(ticket, value) {
    if (!this.isCurrent(ticket) || ticket.kind !== 'indexing' || !this.indexTask) return false;
    const next = checkedIndexTask(value);
    if (next.task_id !== this.indexTask.task_id || next.document_id !== this.indexTask.document_id || next.revision_id !== this.indexTask.revision_id) {
      throw new Error('invalid task identity');
    }
    if (next.attempt < this.indexTask.attempt) return false;
    if (!taskAdvances(this.indexTask, next)) throw new Error('invalid task transition');
    this.indexTask = next;
    // A task response never carries authority to publish an active revision or enable answers.
    this.items = this.items.map(item => item.document_id === next.document_id ? { ...item, index_status: next.state, latest_index_job: next } : item);
    if (this.detail?.document_id === next.document_id) this.detail = this.items.find(item => item.document_id === next.document_id) ?? this.detail;
    return true;
  }
}

const taskNames = { queued: '等待解析', processing: '正在解析', parsed: '已解析 · 未索引', failed: '解析失败', cancelled: '已取消' };
const indexTaskNames = { queued: '等待索引', processing: '正在索引', indexed: '已索引', failed: '索引失败', cancelled: '索引已取消' };

export function taskLabel(state, item) { return state === 'parsed' && item ? documentStatusLabel({ ...item, status: 'parsed' }) : taskNames[state] ?? '未知任务状态'; }
export function indexTaskLabel(state) { return indexTaskNames[state] ?? '未知索引状态'; }
export function taskPending(task) { return task?.state === 'queued' || task?.state === 'processing'; }

export function canStartIndexing(config, item) {
  return Array.isArray(config?.capabilities) && config.capabilities.includes('text_index') && config.capabilities.includes('indexings')
    && item?.can_index === true && item.status === 'parsed' && !item.synthetic_fixture && !item.active_revision_id && !item.latest_index_job;
}

export function documentStatusLabel(item) {
  if (item.status !== 'parsed') return item.status === 'ready' ? '演示就绪' : taskLabel(item.status);
  if (!item.synthetic_fixture && item.active_revision_id && item.index_publication_id) return '已解析 · 已索引';
  const indexState = item.index_status ?? 'not_indexed';
  return `已解析 · ${indexState === 'not_indexed' ? '未索引' : indexTaskLabel(indexState)}`;
}

function taskAdvances(current, next) {
  if (next.attempt !== current.attempt) return next.attempt > current.attempt;
  if (!taskPending(current)) return next.state === current.state;
  return current.state !== 'processing' || next.state !== 'queued';
}

export function checkedTask(value) {
  return checkedTaskShape(value, taskNames);
}

export function checkedIndexTask(value) {
  return checkedTaskShape(value, indexTaskNames);
}

function checkedTaskShape(value, names) {
  const validId = id => typeof id === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u.test(id);
  if (!value || !validId(value.task_id) || !validId(value.document_id) || !validId(value.revision_id)
    || !Object.hasOwn(names, value.state) || !Number.isInteger(value.attempt) || value.attempt < 1 || value.attempt > 3
    || typeof value.can_cancel !== 'boolean' || typeof value.can_retry !== 'boolean'
    || !(value.error_code === null || (typeof value.error_code === 'string' && /^[A-Za-z0-9_:-]{1,100}$/u.test(value.error_code)))
    || (value.can_cancel && !taskPending(value))
    || (value.can_retry && (!['failed', 'cancelled'].includes(value.state) || value.attempt >= 3))) throw new Error('invalid task response');
  return Object.freeze({ task_id: value.task_id, document_id: value.document_id, revision_id: value.revision_id,
    state: value.state, attempt: value.attempt, error_code: value.error_code, can_cancel: value.can_cancel, can_retry: value.can_retry });
}

export function batchFeedback(ids, results) {
  const items = ids.map(id => {
    const matches = Array.isArray(results) ? results.filter(item => item.document_id === id) : [];
    if (matches.length !== 1 || typeof matches[0].ok !== 'boolean') {
      return { document_id: id, ok: false, error_code: 'missing_result', detail: '未收到有效结果，请刷新后核对本项。' };
    }
    const item = matches[0];
    return { document_id: id, ok: item.ok, error_code: item.error_code ?? '', detail: item.detail ?? (item.ok ? '操作成功。' : '操作未完成。') };
  });
  const succeeded = items.filter(item => item.ok).length;
  return { items, succeeded, failed: items.length - succeeded };
}

export function parseTags(value) {
  return [...new Set(value.split(/[,，\n]/u).map(tag => tag.trim()).filter(Boolean))];
}
