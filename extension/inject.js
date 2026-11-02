(function() {
  // Intercept modern Clipboard API
  if (navigator.clipboard && navigator.clipboard.writeText) {
    const originalWriteText = navigator.clipboard.writeText;
    navigator.clipboard.writeText = async function(text) {
      window.postMessage({ type: 'CLIPVAULT_INTERCEPT', text: text }, '*');
      return originalWriteText.apply(this, arguments);
    };
  }
  
  // Intercept execCommand('copy')
  const originalExecCommand = document.execCommand;
  document.execCommand = function(command, ui, value) {
    if (command && command.toLowerCase() === 'copy') {
      // If they selected text programmatically before calling execCommand('copy')
      try {
        const sel = window.getSelection().toString().trim();
        if (sel) {
          window.postMessage({ type: 'CLIPVAULT_INTERCEPT', text: sel }, '*');
        }
      } catch (e) {}
    }
    return originalExecCommand.apply(this, arguments);
  };
})();
