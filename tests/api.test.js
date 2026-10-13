import test from 'node:test';
import assert from 'node:assert/strict';

import {
  DEFAULT_ENGINE_URL,
  loadEngineConfig,
  resolveApiUrl,
  resolvePlaybackUrl,
  state,
} from '../src/lib/api.js';

test('native app defaults to the public Ifallhub engine while web remains same-origin', async () => {
  const previousUrl = state.engineUrl;
  try {
    assert.equal(await loadEngineConfig({ isNative: true }), DEFAULT_ENGINE_URL);
    assert.equal(state.engineUrl, 'https://ifallhub.onrender.com');
    assert.equal(resolveApiUrl('/api/health'), 'https://ifallhub.onrender.com/api/health');
    assert.equal(await loadEngineConfig({ isNative: false }), '');
    assert.equal(state.engineUrl, '');
    assert.equal(resolveApiUrl('/api/health'), '/api/health');
  } finally {
    state.engineUrl = previousUrl;
  }
});

test('relative media routes resolve against the selected engine, not the WebView origin', async () => {
  const previousUrl = state.engineUrl;
  const previousWindow = globalThis.window;
  globalThis.window = { location: { origin: 'https://localhost' } };
  try {
    state.engineUrl = DEFAULT_ENGINE_URL;
    assert.equal(
      resolvePlaybackUrl('/api/media/token/https/cdn.example/video/index.mpd?auth=1'),
      'https://ifallhub.onrender.com/api/media/token/https/cdn.example/video/index.mpd?auth=1',
    );
    assert.equal(
      resolvePlaybackUrl('https://media.example/video.mp4'),
      'https://media.example/video.mp4',
    );
    state.engineUrl = '';
    assert.equal(resolvePlaybackUrl('/media/video.mp4'), 'https://localhost/media/video.mp4');
    assert.equal(resolvePlaybackUrl('javascript:alert(1)'), '');
  } finally {
    state.engineUrl = previousUrl;
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
});
