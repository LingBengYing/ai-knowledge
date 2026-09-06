/** Resolve notice nodes when a response arrives, not when its request starts. */
export function showNotice(resolve, id, message = '') {
  let node = resolve(id);
  // Clearing a removed local notice must not clear an unrelated global failure.
  if (message) {
    const dialog = node?.closest?.('dialog');
    if (!node || (dialog && !dialog.open)) node = resolve('global-error');
  }
  if (!node) return false;
  node.textContent = message;
  node.hidden = !message;
  return true;
}
