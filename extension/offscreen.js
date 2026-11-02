/**
 * ClipVault — Offscreen Screen and Audio Recorder
 * 
 * - Handles MediaRecorder API
 * - Records stream received via chromeMediaSourceId
 * - Combines desktop and microphone audio using AudioContext
 * - Saves recorded video file (MP4 if supported, else WebM)
 */

let mediaRecorder = null;
let recordedChunks = [];
let recordingStartTime = 0;
let durationInterval = null;
let activeStream = null;

// Send DOMContentLoaded / startup message to background indicating offscreen is ready.
// Receive the streamId directly in the response callback to bypass timing races.
const sendHandshake = () => {
  chrome.runtime.sendMessage({ type: 'OFFSCREEN_LOADED' }, (response) => {
    if (chrome.runtime.lastError) {
      console.warn('Error sending OFFSCREEN_LOADED:', chrome.runtime.lastError.message);
      return;
    }
    if (response && response.streamId) {
      startRecording(response.streamId)
        .then((res) => {
          if (res.success) {
            chrome.runtime.sendMessage({ type: 'RECORDING_STARTED' }).catch(() => {});
          } else {
            console.error('Failed to start recording:', res.error);
            chrome.runtime.sendMessage({ type: 'RECORDING_FAILED', error: res.error }).catch(() => {});
          }
        })
        .catch((err) => {
          console.error('Error in startRecording:', err);
          chrome.runtime.sendMessage({ type: 'RECORDING_FAILED', error: err.message }).catch(() => {});
        });
    }
  });
};

// Execute handshake immediately and on DOMContentLoaded
sendHandshake();
window.addEventListener('DOMContentLoaded', sendHandshake);

// Synchronous message router to prevent channel closing prematurely
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === 'START_OFFSCREEN_RECORDING') {
    startRecording(message.streamId)
      .then((res) => {
        sendResponse(res);
        if (res.success) {
          chrome.runtime.sendMessage({ type: 'RECORDING_STARTED' }).catch(() => {});
        }
      })
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true; // Keep message channel open for async response
  }

  if (message.type === 'STOP_OFFSCREEN_RECORDING') {
    stopRecording()
      .then((res) => sendResponse(res))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true; // Keep message channel open for async response
  }

  if (message.type === 'GET_RECORDING_STATUS') {
    const isRecording = mediaRecorder && mediaRecorder.state === 'recording';
    const elapsed = recordingStartTime ? Math.floor((Date.now() - recordingStartTime) / 1000) : 0;
    sendResponse({ isRecording: isRecording, elapsed: elapsed });
    return false; // Sync response
  }
});

async function startRecording(streamId) {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    throw new Error('Recording is already in progress.');
  }

  // Get display stream (tries desktop with audio, tab with audio, desktop video-only, tab video-only)
  let stream;
  try {
    // Try 1: Desktop with Audio
    stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        mandatory: {
          chromeMediaSource: 'desktop',
          chromeMediaSourceId: streamId
        }
      },
      video: {
        mandatory: {
          chromeMediaSource: 'desktop',
          chromeMediaSourceId: streamId
        }
      }
    });
  } catch (err1) {
    console.warn('getUserMedia with desktop + audio failed, trying tab + audio:', err1);
    try {
      // Try 2: Tab with Audio
      stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          mandatory: {
            chromeMediaSource: 'tab',
            chromeMediaSourceId: streamId
          }
        },
        video: {
          mandatory: {
            chromeMediaSource: 'tab',
            chromeMediaSourceId: streamId
          }
        }
      });
    } catch (err2) {
      console.warn('getUserMedia with tab + audio failed, trying desktop video-only:', err2);
      try {
        // Try 3: Desktop Video-only
        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            mandatory: {
              chromeMediaSource: 'desktop',
              chromeMediaSourceId: streamId
            }
          }
        });
      } catch (err3) {
        console.warn('getUserMedia with desktop video-only failed, trying tab video-only:', err3);
        // Try 4: Tab Video-only (Final Fallback)
        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            mandatory: {
              chromeMediaSource: 'tab',
              chromeMediaSourceId: streamId
            }
          }
        });
      }
    }
  }

  activeStream = stream;

  // Try capturing user microphone to merge with system audio
  try {
    const micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const micTracks = micStream.getAudioTracks();
    const systemTracks = stream.getAudioTracks();

    if (systemTracks.length > 0 && micTracks.length > 0) {
      // Both exist, let's mix them using AudioContext
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const dest = audioCtx.createMediaStreamDestination();

      const systemSource = audioCtx.createMediaStreamSource(new MediaStream([systemTracks[0]]));
      const micSource = audioCtx.createMediaStreamSource(new MediaStream([micTracks[0]]));

      systemSource.connect(dest);
      micSource.connect(dest);

      // Remove original system audio track
      stream.removeTrack(systemTracks[0]);
      // Add the mixed audio track
      stream.addTrack(dest.stream.getAudioTracks()[0]);

      // Save references on stream object to close later
      stream.audioCtx = audioCtx;
      stream.micStream = micStream;
    } else if (micTracks.length > 0) {
      // Only microphone audio is available (system audio wasn't shared)
      stream.addTrack(micTracks[0]);
      stream.micStream = micStream;
    }
  } catch (e) {
    console.warn('Microphone access denied or audio mixing failed. Recording system audio only if selected.', e);
  }

  recordedChunks = [];
  recordingStartTime = Date.now();

  // Configure MediaRecorder with MP4 support as primary and WebM as fallback
  let mimeType = 'video/mp4;codecs=h264,aac';
  if (!MediaRecorder.isTypeSupported(mimeType)) {
    mimeType = 'video/mp4;codecs=h264,opus';
  }
  if (!MediaRecorder.isTypeSupported(mimeType)) {
    mimeType = 'video/mp4';
  }
  if (!MediaRecorder.isTypeSupported(mimeType)) {
    mimeType = 'video/webm;codecs=vp9,opus';
  }
  if (!MediaRecorder.isTypeSupported(mimeType)) {
    mimeType = 'video/webm;codecs=vp8,opus';
  }
  if (!MediaRecorder.isTypeSupported(mimeType)) {
    mimeType = 'video/webm';
  }

  let options = { mimeType: mimeType };
  try {
    mediaRecorder = new MediaRecorder(stream, options);
  } catch (e) {
    console.warn(`MediaRecorder initialization failed for ${mimeType}, falling back to standard video/webm:`, e);
    mimeType = 'video/webm';
    options = { mimeType: mimeType };
    mediaRecorder = new MediaRecorder(stream, options);
  }

  mediaRecorder.ondataavailable = (event) => {
    if (event.data && event.data.size > 0) {
      recordedChunks.push(event.data);
    }
  };

  mediaRecorder.onstop = () => {
    // Stop all tracks to release stream
    if (activeStream) {
      activeStream.getTracks().forEach(track => track.stop());
      if (activeStream.micStream) {
        activeStream.micStream.getTracks().forEach(track => track.stop());
      }
      if (activeStream.audioCtx) {
        activeStream.audioCtx.close().catch(() => {});
      }
    }

    // Save recording file
    const isMp4 = mimeType.includes('mp4');
    const extension = isMp4 ? 'mp4' : 'webm';

    const blob = new Blob(recordedChunks, { type: mimeType });
    const url = URL.createObjectURL(blob);
    const filename = `clipvault_recording_${Math.floor(Date.now() / 1000)}.${extension}`;

    chrome.downloads.download({
      url: url,
      filename: filename,
      saveAs: true
    }, () => {
      // Tell background that we finished saving
      chrome.runtime.sendMessage({ type: 'RECORDING_SAVED' });
    });
  };

  mediaRecorder.start(1000); // chunk every 1 sec

  // Broadcast duration updates
  durationInterval = setInterval(() => {
    const elapsed = Math.floor((Date.now() - recordingStartTime) / 1000);
    chrome.runtime.sendMessage({ type: 'RECORDING_DURATION_UPDATE', elapsed: elapsed }).catch(() => {});
  }, 1000);

  return { success: true };
}

async function stopRecording() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    clearInterval(durationInterval);
    mediaRecorder.stop();
    return { success: true };
  } else {
    throw new Error('No active recording found.');
  }
}
