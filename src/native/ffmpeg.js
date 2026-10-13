// Capacitor bridge to the native FFmpegKitNext plugin (android/app/src/main/
// java/com/ifallhub/app/FFmpegPlugin.java).
//
// The plugin bundles com.arthenica:ffmpeg-kit-next and exposes:
//   getVersion()                     -> { version }
//   execute({ args })                -> { success, returnCode, output }
//   startTranscode({ inputUrl, headers })
//                                  -> { playbackUrl, port }
//       Runs a live H.265/HEVC → H.264 transcode and streams the fragmented
//       MP4 over a loopback HTTP server the WebView <video> element can play.
//   stopTranscode()                  -> { stopped }
//   isTranscoding()                  -> { active, playbackUrl }

import { Capacitor, registerPlugin } from '@capacitor/core';

const FFmpeg = registerPlugin('FFmpeg');

export function isNativePlatform() {
  return Capacitor.isNativePlatform();
}

export async function isPluginAvailable() {
  if (!isNativePlatform()) return false;
  try {
    const result = await FFmpeg.getVersion();
    return Boolean(result?.version);
  } catch {
    return false;
  }
}

export async function getFFmpegVersion() {
  try {
    return await FFmpeg.getVersion();
  } catch (error) {
    return { version: '', error: String(error?.message || error) };
  }
}

/** Run arbitrary ffmpeg arguments (escape hatch used by diagnostics). */
export async function executeFFmpeg(args = []) {
  return FFmpeg.execute({ args });
}

/**
 * Starts a live transcode of `inputUrl` (typically an H.265/HEVC or .mkv
 * stream) into an H.264 fragmented MP4 served on 127.0.0.1.
 */
export async function startTranscode({ inputUrl, headers = {} } = {}) {
  return FFmpeg.startTranscode({ inputUrl, headers });
}

export async function stopTranscode() {
  try {
    return await FFmpeg.stopTranscode();
  } catch {
    return { stopped: false };
  }
}

export async function isTranscoding() {
  try {
    return await FFmpeg.isTranscoding();
  } catch {
    return { active: false };
  }
}

/** Opens a stream URL with the system handler (VLC / MX Player / browser). */
export async function openExternal(url) {
  if (!isNativePlatform() || !url) return { ok: false };
  try {
    return await FFmpeg.openExternal({ url });
  } catch {
    return { ok: false };
  }
}
