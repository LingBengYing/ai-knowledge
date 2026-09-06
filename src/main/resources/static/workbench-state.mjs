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

  invalidate() {
    this.epoch += 1;
    this.reads.clear();
    this.items = [];
    this.selected.clear();
    this.detail = null;
    this.mutation = null;
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
    this.items = items;
    const available = new Set(items.map(item => item.document_id));
    this.selected = new Set([...this.selected].filter(id => available.has(id)));
    this.detail = items.find(item => item.document_id === this.detail?.document_id) ?? null;
    return true;
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
