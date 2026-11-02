/**
 * ClipVault — Recorder Page Script
 *
 * Uses getDisplayMedia() for screen capture (modern, reliable).
 * Requests microphone BEFORE screen picker (user gesture required for both).
 * Mixes microphone + system audio if both are available.
 */

let mediaRecorder = null;
let recordedChunks = [];
let recordingStartTime = 0;
let durationInterval = null;
let currentMimeType = 'video/webm';

// DOM Elements
const setupUi = document.getElementById('setup-ui');
const recordingUi = document.getElementById('recording-ui');
const btnStart = document.getElementById('btnStart');
const timerEl = document.getElementById('timer');
const statusEl = document.getElementById('status');

function formatDuration(seconds) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

function setStatus(msg) {
  console.log('[ClipVault Recorder]', msg);
  if (statusEl) statusEl.textContent = msg;
}

// ────────────────────────────────────────────────────────────────
// Main Recording Function
// ────────────────────────────────────────────────────────────────
async function startRecording(isAuto = false) {
  // Show recording UI and hide setup UI
  if (setupUi) setupUi.style.display = 'none';
  if (recordingUi) recordingUi.style.display = 'block';
  setStatus('مائیکروفون کی اجازت چیک کی جا رہی ہے...');

  // ── Step 1: Request microphone FIRST (before screen picker) ──
  let micStream = null;
  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    setStatus('مائیکروفون فعال کر دیا گیا ہے۔ اسکرین سلیکٹر کا انتظار ہے...');
  } catch (e) {
    // Mic denied or blocked
    console.warn('[Recorder] Microphone denied:', e.name, e.message);
    setStatus('مائیکروفون کے بغیر اسکرین ریکارڈنگ کی جا رہی ہے...');
  }

  // ── Step 2: Request screen/window/tab via getDisplayMedia ─────
  let screenStream = null;
  try {
    screenStream = await navigator.mediaDevices.getDisplayMedia({
      video: {
        displaySurface: 'monitor',
        width: { ideal: 1920 },
        height: { ideal: 1080 },
        frameRate: { ideal: 30, max: 60 }
      },
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        sampleRate: 44100
      },
      preferCurrentTab: false,
      selfBrowserSurface: 'exclude',
      systemAudio: 'include'
    });
  } catch (err) {
    // Clean up mic if it was opened
    if (micStream) micStream.getTracks().forEach(t => t.stop());

    // If auto-start failed due to user gesture block, show setup UI fallback
    if (isAuto && (err.name === 'SecurityError' || err.name === 'NotAllowedError' || err.message.includes('gesture') || err.message.includes('activation'))) {
      console.warn('[Recorder] Auto-start blocked due to gesture requirement. Showing manual start button.');
      if (setupUi) setupUi.style.display = 'block';
      if (recordingUi) recordingUi.style.display = 'none';
      return;
    }

    if (err.name === 'NotAllowedError' || err.name === 'AbortError') {
      setStatus('ریکارڈنگ منسوخ کر دی گئی ہے۔');
      chrome.runtime.sendMessage({ type: 'RECORDING_CANCELLED' }).catch(() => {});
    } else {
      setStatus('❌ ریکارڈنگ شروع کرنے میں ناکامی: ' + err.message);
      chrome.runtime.sendMessage({ type: 'RECORDING_FAILED', error: err.message });
    }

    // Go back to setup UI if not auto
    setTimeout(() => {
      if (setupUi) setupUi.style.display = 'block';
      if (recordingUi) recordingUi.style.display = 'none';
    }, 2000);
    return;
  }

  setStatus('تیاری مکمل۔ ریکارڈنگ شروع کی جا رہی ہے...');

  // ── Step 3: Build final audio track ──────────────────────────
  const videoTrack = screenStream.getVideoTracks()[0];
  const sysAudioTracks = screenStream.getAudioTracks(); 
  const micTracks = micStream ? micStream.getAudioTracks() : [];

  let finalAudioTrack = null;

  if (sysAudioTracks.length > 0 && micTracks.length > 0) {
    // Mix system + microphone
    try {
      const audioCtx = new AudioContext();
      const dest = audioCtx.createMediaStreamDestination();
      audioCtx.createMediaStreamSource(new MediaStream([sysAudioTracks[0]])).connect(dest);
      audioCtx.createMediaStreamSource(new MediaStream([micTracks[0]])).connect(dest);
      finalAudioTrack = dest.stream.getAudioTracks()[0];
      screenStream._audioCtx = audioCtx;
      setStatus('سسٹم اور مائیکروفون دونوں کی آواز ریکارڈ کی جا رہی ہے');
    } catch (e) {
      finalAudioTrack = micTracks[0];
      setStatus('مائیکروفون کی آواز ریکارڈ کی جا رہی ہے (مکسر ناکام)');
    }
  } else if (sysAudioTracks.length > 0) {
    finalAudioTrack = sysAudioTracks[0];
    setStatus('صرف سسٹم کی آواز ریکارڈ کی جا رہی ہے');
  } else if (micTracks.length > 0) {
    finalAudioTrack = micTracks[0];
    setStatus('صرف مائیکروفون (آپ کی آواز) ریکارڈ کی جا رہی ہے');
  } else {
    setStatus('بغیر آواز کے صرف ویڈیو ریکارڈ کی جا رہی ہے');
  }

  // ── Step 4: Build final MediaStream ──────────────────────────
  const tracks = [videoTrack];
  if (finalAudioTrack) tracks.push(finalAudioTrack);
  const finalStream = new MediaStream(tracks);

  // ── Step 5: Choose best codec ─────────────────────────────────
  const codecs = [
    'video/mp4;codecs=h264,aac',
    'video/mp4;codecs=h264,opus',
    'video/mp4',
    'video/webm;codecs=vp9,opus',
    'video/webm;codecs=vp8,opus',
    'video/webm'
  ];
  currentMimeType = codecs.find(m => MediaRecorder.isTypeSupported(m)) || 'video/webm';
  console.log('[Recorder] Codec:', currentMimeType);

  try {
    mediaRecorder = new MediaRecorder(finalStream, { mimeType: currentMimeType });
  } catch (e) {
    currentMimeType = 'video/webm';
    mediaRecorder = new MediaRecorder(finalStream, { mimeType: currentMimeType });
  }

  recordedChunks = [];
  recordingStartTime = Date.now();

  mediaRecorder.ondataavailable = (e) => {
    if (e.data && e.data.size > 0) recordedChunks.push(e.data);
  };

  // Handle user stopping via Chrome's built-in "Stop sharing" bar
  videoTrack.addEventListener('ended', () => {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      stopRecording();
    }
  });

  mediaRecorder.onstop = () => {
    clearInterval(durationInterval);

    // Stop all tracks
    screenStream.getTracks().forEach(t => t.stop());
    if (micStream) micStream.getTracks().forEach(t => t.stop());
    if (screenStream._audioCtx) screenStream._audioCtx.close().catch(() => {});

    // Save file
    const ext = currentMimeType.includes('mp4') ? 'mp4' : 'webm';
    const blob = new Blob(recordedChunks, { type: currentMimeType });
    const url = URL.createObjectURL(blob);
    const filename = `clipvault_recording_${Math.floor(Date.now() / 1000)}.${ext}`;

    setStatus('محفوظ کیا جا رہا ہے...');
    chrome.downloads.download({ url, filename, saveAs: true }, () => {
      URL.revokeObjectURL(url);
      setStatus('ریکارڈنگ کامیابی سے محفوظ ہو گئی!');
      chrome.runtime.sendMessage({ type: 'RECORDING_SAVED' });
      setTimeout(() => window.close(), 2500);
    });
  };

  // ── Step 6: Start! ─────────────────────────────────────────
  mediaRecorder.start(1000);
  setStatus('ریکارڈنگ جاری ہے...');

  // Tell background → it will switch focus back to source tab
  chrome.runtime.sendMessage({ type: 'RECORDING_STARTED' });

  // Timer updates to popup
  durationInterval = setInterval(() => {
    const elapsed = Math.floor((Date.now() - recordingStartTime) / 1000);
    if (timerEl) timerEl.textContent = formatDuration(elapsed);
    chrome.runtime.sendMessage({ type: 'RECORDING_DURATION_UPDATE', elapsed }).catch(() => {});
  }, 1000);
}

function stopRecording() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    clearInterval(durationInterval);
    mediaRecorder.stop();
    setStatus('روکا جا رہا ہے...');
  }
}

// ── Message listener from background ─────────────────────────
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === 'STOP_OFFSCREEN_RECORDING') {
    stopRecording();
    sendResponse({ success: true });
    return false;
  }
  if (message.type === 'GET_RECORDING_STATUS') {
    sendResponse({
      isRecording: !!(mediaRecorder && mediaRecorder.state === 'recording'),
      elapsed: recordingStartTime ? Math.floor((Date.now() - recordingStartTime) / 1000) : 0
    });
    return false;
  }
});

// ── Keyboard Shortcut Listener (Ctrl+Shift+S) ──────────────────
window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 's') {
    e.preventDefault();
    stopRecording();
  }
});

// ── Set up event listeners ────────────────────────────────────
window.addEventListener('load', () => {
  if (btnStart) {
    btnStart.addEventListener('click', () => {
      startRecording(false);
    });
  }
});
