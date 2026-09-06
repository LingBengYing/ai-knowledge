export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export function validateUpload(file) {
  if (!(file instanceof Blob) || typeof file.name !== 'string' || !file.name.trim()
    || file.name.length > 255 || /[/\\\u0000-\u001f\u007f]/u.test(file.name)
    || !/\.(?:pdf|txt|md)$/iu.test(file.name) || file.size < 1 || file.size > 20 * 1024 * 1024) {
    throw new ApiError(422, '请选择1字节至20MiB的PDF、TXT或Markdown文件，文件名不能包含路径或控制字符。');
  }
  try { encodeURIComponent(file.name); } catch { throw new ApiError(422, '文件名包含不合法字符。'); }
  return file;
}

/** Cookies remain browser-owned; this client never stores a token. */
export function createApi(config, principal, fetcher = globalThis.fetch) {
  return async function request(path, { method = 'GET', body, file, signal } = {}) {
    if (!path.startsWith('/v1/')) throw new Error('仅允许同源 API 请求。');
    const headers = { Accept: 'application/json' };
    if (config.auth_mode === 'development_headers') {
      headers['X-Workspace-Id'] = config.workspace_id;
      headers['X-Principal-Id'] = principal();
    }
    if (file !== undefined) {
      validateUpload(file);
      if (method !== 'POST' || body !== undefined || path !== `/v1/documents?filename=${encodeURIComponent(file.name)}`) {
        throw new ApiError(422, '原始文件仅允许发送到指定文本上传接口。');
      }
      headers['Content-Type'] = 'application/octet-stream';
    } else if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetcher(path, {
      method, headers, signal, credentials: 'same-origin', cache: 'no-store',
      ...(file !== undefined ? { body: file } : body !== undefined ? { body: JSON.stringify(body) } : {}),
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
