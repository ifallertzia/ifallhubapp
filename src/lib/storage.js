// Storage adapter: Capacitor Preferences on device, localStorage on web.
//
// Every key is written to both stores so the app works identically in the
// Android WebView (Preferences is the durable copy) and in a desktop browser
// (localStorage). Reads prefer the Preferences copy when the native bridge is
// present, falling back to localStorage.

import { Capacitor, registerPlugin } from '@capacitor/core';

const Preferences = registerPlugin('Preferences');

function hasNativePreferences() {
  return Capacitor.isNativePlatform();
}

export async function readSetting(key) {
  if (hasNativePreferences()) {
    try {
      const { value } = await Preferences.get({ key });
      if (value !== null && value !== undefined) return value;
    } catch {
      /* fall through to localStorage */
    }
  }
  try {
    return localStorage.getItem(key) ?? null;
  } catch {
    return null;
  }
}

export async function writeSetting(key, value) {
  const text = value === null || value === undefined ? null : String(value);
  if (hasNativePreferences()) {
    try {
      if (text === null) await Preferences.remove({ key });
      else await Preferences.set({ key, value: text });
    } catch {
      /* fall through to localStorage */
    }
  }
  try {
    if (text === null) localStorage.removeItem(key);
    else localStorage.setItem(key, text);
  } catch {
    /* storage may be blocked; the in-memory copy still works */
  }
}

export async function removeSetting(key) {
  return writeSetting(key, null);
}
