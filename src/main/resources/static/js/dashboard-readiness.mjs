/** Separate, bounded request: readiness never delays the dashboard response. */
export async function loadSummary(host, fetcher = fetch) {
  const content = host.querySelector('[data-summary-content]');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetcher(host.dataset.summaryUrl, {
      signal: controller.signal, cache: 'no-store', headers: {Accept: 'text/html'}
    });
    if (!response.ok || response.redirected) return;
    const document = new DOMParser().parseFromString(await response.text(), 'text/html');
    const summary = document.querySelector('[data-setup-summary-result]');
    if (summary && !controller.signal.aborted) content.replaceChildren(summary);
  } catch { /* Initial unknown states remain visible; no automatic retries. */ }
  finally { clearTimeout(timer); content.setAttribute('aria-busy', 'false'); }
}
if (typeof document !== 'undefined') {
  const host = document.querySelector('[data-dashboard-readiness]');
  if (host) loadSummary(host);
}
