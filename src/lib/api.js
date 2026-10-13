// Thin client for the Ifallhub API.
//
// Web deployments run same-origin (the server proxies /api/* to the Rust
// engine). The Android app points at the public engine by default, with an
// optional per-device engine URL and access key in Settings, so the same client
// code works in both. The access key (when the operator set one) is attached
// to every call and never leaves this device.

import { Capacitor } from '@capacitor/core';
import { escapeHtml } from './format.js';
import { readSetting, writeSetting } from './storage.js';

const ACCESS_KEY_STORAGE = 'ifallhub_access_key';
const ENGINE_URL_STORAGE = 'ifallhub_engine_url';

// Default engine used by the Android app when nothing is configured yet.
// Override it from the in-app Settings dialog (gear icon in the top bar).
export const DEFAULT_ENGINE_URL = 'https://ifallhub.onrender.com';

export const state = {
  engine: 'checking', // checking | connected | demo | offline
  version: '',
  engineUrl: '',
  supports: {
    collections: null,
    related: null,
    suggest: null,
    meta: null,
    library: null,
    events: null,
  },
};

const listeners = new Set();

export function onEngineChange(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit() {
  for (const listener of listeners) listener({ ...state });
}

export function setEngine(status, version = state.version) {
  state.engine = status;
  state.version = version;
  emit();
}

export function markSupport(feature, supported) {
  if (state.supports[feature] === supported) return;
  state.supports[feature] = supported;
  emit();
}

/** Resolves the engine base URL (no trailing slash, no /api suffix). */
export function getEngineUrl() {
  if (state.engineUrl) return state.engineUrl;
  return '';
}

/**
 * Resolve a playback/media URL returned by the engine.
 *
 * The API intentionally returns same-origin media routes such as
 * `/api/media/<token>/https/...`. In the Android WebView, however, the app is
 * served from `https://localhost` while the engine lives at Render. Resolve
 * those relative routes against the configured engine, not the WebView origin.
 */
export function resolvePlaybackUrl(rawUrl) {
  const value = String(rawUrl || '').trim();
  if (!value) return '';
  const base = getEngineUrl() || (typeof window !== 'undefined' ? window.location.origin : '');
  try {
    const url = new URL(value, base || undefined);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : '';
  } catch {
    return '';
  }
}

/**
 * Absolute URL for an API path. Same-origin (`/api/...`) when no engine is
 * configured (web deployment), otherwise `${engineUrl}/api/...`.
 */
export function resolveApiUrl(path) {
  const cleanPath = path.startsWith('/') ? path : `/${path}`;
  const base = getEngineUrl();
  if (!base) return cleanPath;
  return `${base.replace(/\/+$/, '')}${cleanPath}`;
}

export function getAccessKey() {
  try {
    return localStorage.getItem(ACCESS_KEY_STORAGE) || '';
  } catch {
    return '';
  }
}

export function setAccessKey(key) {
  try {
    if (key) localStorage.setItem(ACCESS_KEY_STORAGE, key);
    else localStorage.removeItem(ACCESS_KEY_STORAGE);
  } catch {
    /* Private browsing can block storage. */
  }
  void writeSetting(ACCESS_KEY_STORAGE, key || '');
  // EventSource cannot send headers, so the key is mirrored into the
  // `moviebox_key` cookie the API already accepts. Same-origin only.
  try {
    const value = key
      ? `moviebox_key=${encodeURIComponent(key)}; Path=/; SameSite=Strict; Max-Age=31536000`
      : 'moviebox_key=; Path=/; SameSite=Strict; Max-Age=0';
    document.cookie = value;
  } catch {
    /* Cookies may be blocked; header auth still works for normal requests. */
  }
}

/** Loads (and remembers) the engine URL configured on this device. */
export async function loadEngineConfig({ isNative = Capacitor.isNativePlatform() } = {}) {
  const stored = (await readSetting(ENGINE_URL_STORAGE)) || '';
  const key = (await readSetting(ACCESS_KEY_STORAGE)) || '';
  const configured = String(stored || '').trim().replace(/\/+$/, '');
  // Android ships as a standalone app, so it must not default to the WebView's
  // `https://localhost` origin. The public Ifallhub deployment is the built-in
  // engine; browser deployments still use their own same-origin API.
  state.engineUrl = configured || (isNative ? DEFAULT_ENGINE_URL : '');
  if (key && !getAccessKey()) {
    try {
      localStorage.setItem(ACCESS_KEY_STORAGE, key);
    } catch {
      /* ignore */
    }
  }
  return state.engineUrl;
}

/** Switches the engine URL at runtime (Settings dialog). */
export async function setEngineUrl(url, { isNative = Capacitor.isNativePlatform() } = {}) {
  const configured = String(url || '').trim().replace(/\/+$/, '');
  // Clearing the Android field restores its built-in engine instead of sending
  // future requests back to the WebView's localhost origin.
  state.engineUrl = configured || (isNative ? DEFAULT_ENGINE_URL : '');
  await writeSetting(ENGINE_URL_STORAGE, state.engineUrl);
  // Re-check the engine against the new URL.
  return checkEngine();
}

export function apiUrl(path, params = {}) {
  const url = new URL(resolveApiUrl(path), window.location.origin);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value));
  }
  if (getEngineUrl()) return `${url.pathname}${url.search}`;
  return `${url.pathname}${url.search}`;
}

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export async function api(path, options = {}) {
  const headers = { Accept: 'application/json', ...(options.headers || {}) };
  const key = getAccessKey();
  if (key) headers['X-MovieBox-Key'] = key;
  const target = resolveApiUrl(path);
  const response = await fetch(target, { ...options, headers, signal: options.signal });
  const contentType = response.headers.get('content-type') || '';
  const payload = contentType.includes('application/json')
    ? await response.json()
    : { error: await response.text() };

  if (response.status === 401 && (options.accessAttempts || 0) < 3) {
    setAccessKey('');
    const unlocked = await options.onUnauthorized?.();
    if (unlocked) return api(path, { ...options, accessAttempts: (options.accessAttempts || 0) + 1 });
  }
  if (response.status === 404 && options.onMissing) return options.onMissing();
  if (!response.ok) {
    throw new ApiError(payload.error || `Request failed (${response.status})`, response.status);
  }
  return payload;
}

export async function checkEngine() {
  try {
    const health = await api('/api/health');
    if (health.mode === 'demo') setEngine('demo', health.version || '');
    else setEngine('connected', health.version || '');
    return health;
  } catch {
    setEngine('offline', '');
    return null;
  }
}

// --- Endpoints -------------------------------------------------------------

export const providers = (options) => api('/api/providers', options);

export function collections({ limit = 24, pages = 3 } = {}, options = {}) {
  return api(apiUrl('/api/collections', { limit, pages }), options);
}

export function discover({ provider = 'moviebox', page = 1 } = {}, options = {}) {
  return api(apiUrl('/api/discover', { provider, page }), options);
}

export function search({ q, provider = 'moviebox', page = 1 } = {}, options = {}) {
  return api(apiUrl('/api/search', { q, provider, page }), options);
}

export function suggest({ q } = {}, options = {}) {
  return api(apiUrl('/api/suggest', { q }), options);
}

export function details({ provider, id } = {}, options = {}) {
  return api(apiUrl('/api/details', { provider, id }), options);
}

export function related({ provider, id, limit = 18 } = {}, options = {}) {
  return api(apiUrl('/api/related', { provider, id, limit }), options);
}

export function meta({ provider, ids = [] } = {}, options = {}) {
  return api(apiUrl('/api/meta', { provider, id: ids.join(',') }), options);
}

export function streams({
  provider,
  id,
  season = 0,
  episode = 0,
  isSeries = false,
  expectedTitle = '',
  expectedYear = '',
} = {}, options = {}) {
  return api(
    apiUrl('/api/streams', {
      provider,
      id,
      season,
      episode,
      is_series: isSeries ? 'true' : 'false',
      expected_title: expectedTitle,
      expected_year: expectedYear,
    }),
    options,
  );
}

export function subtitles({ title, year = '', season = 0, episode = 0 } = {}, options = {}) {
  return api(apiUrl('/api/subtitles', { title, year, season, episode }), options);
}

export function subtitleFile({ setId, index } = {}, options = {}) {
  return api(apiUrl('/api/subtitle-file', { set_id: setId, index }), options);
}

export function play({ streamSetId, releaseIndex, mirrorIndex = 0 } = {}, options = {}) {
  return api('/api/play', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      stream_set_id: streamSetId,
      release_index: releaseIndex,
      mirror_index: mirrorIndex,
    }),
    ...options,
  });
}

export function library(options = {}) {
  return api('/api/library', options);
}

export function saveWatchlist(entry, remove = false, options = {}) {
  return api('/api/library/watchlist', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...entry, remove }),
    ...options,
  });
}

export function saveProgress(entry, remove = false, options = {}) {
  return api('/api/library/progress', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...entry, remove }),
    ...options,
  });
}

export function saveRating(entry, options = {}) {
  return api('/api/library/rating', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(entry),
    ...options,
  });
}

export function clearLibrary(scope = 'all', options = {}) {
  return api('/api/library/clear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scope }),
    ...options,
  });
}

/** Absolute URL for the library SSE stream (used by library.js). */
export function libraryEventsUrl() {
  return resolveApiUrl('/api/library/events');
}

/** Human-readable copy for provider errors surfaced in the UI. */
export function describeError(error) {
  const message = error?.message || 'The engine did not respond.';
  return escapeHtml(message).replace(/&amp;/g, '&');
}
