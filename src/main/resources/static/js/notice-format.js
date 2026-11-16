// 공지사항 공통 (NOT-01)
/** '2026-11-16T14:05:00' → '2026.11.16' */
export function noticeDate(value) {
  return value ? value.slice(0, 10).replaceAll('-', '.') : '';
}

export async function fetchJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const error = new Error(body?.message ?? '잠시 후 다시 시도해 주세요.');
    error.status = res.status;
    error.code = body?.code;
    throw error;
  }
  return body;
}

export const notices = {
  list: (page = 0, size = 10) => fetchJson(`/api/notices?page=${page}&size=${size}`),
  get: (id) => fetchJson(`/api/notices/${encodeURIComponent(id)}`),
};
