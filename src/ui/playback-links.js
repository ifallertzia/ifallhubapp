// Keep a visible, copyable history of the URLs actually handed to the player.
// H.265/.mkv files and DASH/HLS manifests may not play in a browser, but can
// still be useful in VLC/mpv. These proxy/session URLs are short-lived, so they
// are shown only in the current page and are never persisted.
import { displayUiText, providerLabel } from '../lib/format.js';
import { writeClipboard } from './vlc-button.js';

export const MAX_LINK_ROWS = 40;
const COPIED_LABEL_MS = 2500;
const panelRows = new WeakMap();

function rowsFor(container) {
  let rows = panelRows.get(container);
  if (!rows) {
    rows = new Map();
    panelRows.set(container, rows);
  }
  return rows;
}

function updateCount(container, rows) {
  const panel = container.closest?.('.playback-links') || container;
  const count = panel.querySelector?.('[data-links-count]');
  if (count) count.textContent = rows.size === 1 ? '1 link' : `${rows.size} links`;
  const copyAll = panel.querySelector?.('[data-copy-all-links]');
  if (copyAll) copyAll.disabled = rows.size === 0;
}

function pruneRows(container, rows) {
  while (rows.size > MAX_LINK_ROWS) {
    const oldest = rows.keys().next().value;
    rows.get(oldest)?.row?.remove?.();
    rows.delete(oldest);
  }
  if (typeof container.scrollHeight === 'number') container.scrollTop = container.scrollHeight;
}

/** Returns the displayed URLs in their on-screen order (oldest first). */
export function playbackLinkUrls(container) {
  if (!container) return [];
  return [...rowsFor(container).values()].map((entry) => entry.url);
}

function flashCopied(button) {
  button.textContent = 'Copied!';
  const timer = setTimeout(() => {
    button.textContent = 'Copy link';
  }, COPIED_LABEL_MS);
  // Do not make Node's unit-test process wait for a UI-only label reset.
  timer?.unref?.();
}

function streamTypeLabel(playback, url) {
  const reported = String(playback.stream_type || '').toLowerCase();
  if (reported === 'dash' || reported === 'hls') return reported.toUpperCase();
  const path = url.pathname.toLowerCase();
  if (path.endsWith('.mpd') || path.includes('/dash/')) return 'DASH';
  if (path.endsWith('.m3u8') || path.endsWith('.m3u') || path.includes('/hls/')) return 'HLS';
  return (reported || 'file').toUpperCase();
}

/** Adds one safe HTTP(S) playback URL to the visible history. */
export function appendPlaybackLink(
  container,
  { playback = {}, release = {}, provider, mirrorIndex = 0, note = '' } = {},
  origin,
) {
  if (!container) return null;
  const rawUrl = playback.playback_url || playback.url;
  if (!rawUrl) return null;

  let url;
  try {
    url = new URL(rawUrl, origin);
    if (!['http:', 'https:'].includes(url.protocol)) return null;
  } catch {
    return null;
  }

  const rows = rowsFor(container);
  const existing = rows.get(url.href);
  if (existing) {
    existing.retries += 1;
    if (!existing.retryBadge) {
      existing.retryBadge = document.createElement('span');
      existing.retryBadge.className = 'playback-link-retries';
      existing.caption.append(existing.retryBadge);
    }
    existing.retryBadge.textContent = ` · retried ×${existing.retries}`;
    return existing.row;
  }

  container.querySelector?.('[data-links-empty]')?.remove?.();
  const row = document.createElement('div');
  row.className = 'playback-link-row';

  const label = document.createElement('label');
  label.className = 'playback-link-main';
  const caption = document.createElement('span');
  const mirror = release.mirrors?.[mirrorIndex];
  const base = `${providerLabel(provider)} · ${displayUiText(release.filename || release.quality || 'Stream')} · ${displayUiText(mirror?.label || `Link ${mirrorIndex + 1}`)} · ${streamTypeLabel(playback, url)}`;
  caption.textContent = base;
  if (note) {
    const badge = document.createElement('span');
    badge.className = 'playback-link-note';
    badge.textContent = `⚠ ${displayUiText(note)}`;
    caption.append(badge);
  }

  const input = document.createElement('input');
  input.className = 'playback-link-url';
  input.type = 'text';
  input.readOnly = true;
  input.value = url.href;
  input.title = 'Select the whole link, or use Copy link.';
  input.addEventListener?.('focus', () => input.select?.());
  input.addEventListener?.('click', () => input.select?.());
  label.append(caption, input);

  const button = document.createElement('button');
  button.className = 'ghost-button small';
  button.type = 'button';
  button.textContent = 'Copy link';
  button.addEventListener('click', async () => {
    if (await writeClipboard(input.value)) {
      flashCopied(button);
      return;
    }
    // Clipboard permissions may be unavailable in a WebView/private browser.
    input.focus?.();
    input.select?.();
    button.textContent = 'Select & copy manually';
  });

  row.append(label, button);
  container.append(row);
  rows.set(url.href, { row, url: url.href, caption, base, retries: 0, retryBadge: null });
  pruneRows(container, rows);
  updateCount(container, rows);
  return row;
}
