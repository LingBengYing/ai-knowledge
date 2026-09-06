export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

/** Cookies remain browser-owned; this client never stores a token. */
export function createApi(config, principal, fetcher = globalThis.fetch) {
  return async function request(path, { method = 'GET', body, signal } = {}) {
    if (!path.startsWith('/v1/')) throw new Error('仅允许同源 API 请求。');
    const headers = { Accept: 'application/json' };
    if (config.auth_mode === 'development_headers') {
      headers['X-Workspace-Id'] = config.workspace_id;
      headers['X-Principal-Id'] = principal();
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetcher(path, {
      method, headers, signal, credentials: 'same-origin', cache: 'no-store',
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
    const result = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
      const detail = typeof result?.detail === 'string' && result.detail.length <= 500
        ? result.detail : `请求未完成（${response.status}），请稍后重试。`;
      throw new ApiError(response.status, detail);
    }
    return result;
  };
}
