// H.265/HEVC handling for the Ifallhub player.
//
// On the web this module historically used ffmpeg.wasm; on Android the heavy
// lifting is done natively by FFmpegKitNext (com.arthenica:ffmpeg-kit-next)
// through the Capacitor `FFmpeg` plugin. The plugin live-transcodes unsupported
// releases (H.265/HEVC, .mkv containers, Dolby Vision) to H.264/AAC and serves
// the fragmented MP4 from a loopback HTTP server, so the WebView <video>
// element (and the whole player overlay) keeps working unchanged.

import {
  executeFFmpeg,
  getFFmpegVersion,
  isNativePlatform,
  isPluginAvailable,
  isTranscoding,
  startTranscode,
  stopTranscode,
} from '../native/ffmpeg.js';

const H265_CODEC_PATTERN = /(?:^|[^a-z0-9])(?:hevc|h\.?\s*265|x\.?\s*265|hvc1|hev1|hvc2|dvh1|dolby[\s._-]*vision)(?:$|[^a-z0-9])/i;

/** Release text or URL that looks like H.265/HEVC. */
export function isH265Url(value) {
  const text = String(value || '');
  let decoded = text;
  try {
    decoded = decodeURIComponent(text);
  } catch {
    // A signed URL can contain a literal percent; the raw text is still useful.
  }
  return H265_CODEC_PATTERN.test(`${text} ${decoded}`);
}

/** Container types the WebView cannot demux without FFmpeg. */
export function isUnsupportedContainer(text = '') {
  return /\.(mkv|avi|wmv|flv|divx|m2ts|vob)([?#\s]|$)/i.test(String(text));
}

/**
 * A release needs the native transcoder when it is H.265/HEVC or ships in a
 * container the WebView cannot play. Mirrors `unsupportedReason()` in
 * engine.js so the two stay in sync.
 */
export function needsNativeTranscode(release = {}, playbackUrl = '') {
  const text = `${release.codec || ''} ${release.filename || ''} ${release.quality || ''} ${playbackUrl}`;
  return isH265Url(text) || isUnsupportedContainer(text);
}

let cachedAvailable = null;

/** True when the native FFmpeg plugin can transcode on this device. */
export async function canNativeTranscode() {
  if (!isNativePlatform()) return false;
  if (cachedAvailable === null) {
    cachedAvailable = await isPluginAvailable();
  }
  return cachedAvailable;
}

/**
 * Live-transcodes `inputUrl` to a playable H.264 stream and returns the local
 * playback URL (http://127.0.0.1:<port>/live.mp4). Throws with a user-readable
 * message when FFmpeg cannot start.
 */
export async function transcodeToPlayableUrl(inputUrl, { headers = {} } = {}) {
  if (!inputUrl) throw new Error('No stream URL to transcode.');
  const result = await startTranscode({ inputUrl, headers });
  const playbackUrl = result?.playbackUrl;
  if (!playbackUrl) {
    throw new Error('FFmpegKitNext did not return a playable stream.');
  }
  // Give the local server + ffmpeg a moment to write the first fragments so
  // <video> sees a valid fMP4 header on the first request.
  await new Promise((resolve) => setTimeout(resolve, 1200));
  return playbackUrl;
}

export async function shutdownTranscode() {
  cachedAvailable = null;
  return stopTranscode();
}

export { executeFFmpeg, getFFmpegVersion, isTranscoding };

/** Full status snapshot for the diagnostics UI. */
export async function describeDecoder() {
  const native = isNativePlatform();
  const available = await canNativeTranscode();
  const version = available ? await getFFmpegVersion() : { version: '' };
  return {
    native,
    ffmpeg: available,
    version: version?.version || '',
  };
}
