import test from 'node:test';
import assert from 'node:assert/strict';

import { pickAutomaticSubtitle, subtitleLanguageTag, subtitleToVtt } from '../src/player/subtitles.js';

test('subtitleToVtt converts UTF-8 SRT timestamps and preserves cue text', () => {
  const source = '\uFEFF1\r\n00:00:01,050 --> 00:00:03,200\r\nHello, world!\r\n';
  assert.equal(
    subtitleToVtt(source),
    'WEBVTT\n\n1\n00:00:01.050 --> 00:00:03.200\nHello, world!',
  );
});

test('subtitleToVtt leaves WebVTT cues intact', () => {
  const source = 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCaption';
  assert.equal(subtitleToVtt(source), source);
});

test('subtitleToVtt converts basic ASS dialogue and formatting tags', () => {
  const source = [
    '[Script Info]',
    'Title: Example',
    '[Events]',
    'Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text',
    'Dialogue: 0,0:00:01.23,0:00:02.50,Default,,0,0,0,,{\\i1}Namaste!\\NWelcome',
  ].join('\n');
  assert.equal(
    subtitleToVtt(source),
    'WEBVTT\n\n00:00:01.230 --> 00:00:02.500\nNamaste!\nWelcome',
  );
});

test('subtitleToVtt rejects empty and unsupported files', () => {
  assert.throws(() => subtitleToVtt('  '), /empty/);
  assert.throws(() => subtitleToVtt('<html>not a subtitle</html>'), /not supported/);
});

test('automatic subtitle selection prefers English, then Hindi, then first available', () => {
  const hindi = { index: 0, name: 'Hindi', language: 'hi' };
  const english = { index: 1, name: 'English (US)', language: 'en' };
  const spanish = { index: 2, name: 'Spanish', language: 'es' };
  assert.equal(pickAutomaticSubtitle([hindi, english, spanish]), english);
  assert.equal(pickAutomaticSubtitle([hindi, spanish]), hindi);
  assert.equal(pickAutomaticSubtitle([spanish]), spanish);
  assert.equal(pickAutomaticSubtitle([]), null);
});

test('subtitleLanguageTag maps common language names and codes', () => {
  assert.equal(subtitleLanguageTag('English'), 'en');
  assert.equal(subtitleLanguageTag('Hindi'), 'hi');
  assert.equal(subtitleLanguageTag('eng'), 'en');
  assert.equal(subtitleLanguageTag('Unknown caption'), 'und');
});
