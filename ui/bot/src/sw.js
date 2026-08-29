/* Service worker for the SDD-M15 dashboard.
 *
 * This is a live trading view, so freshness beats offline: every non-API GET is
 * network-first with a cached fallback, and the caches are versioned so a new
 * deploy drops the old ones on activate. That stops a stale bundle (e.g. an old
 * AuthService where sign-out did not redirect) from lingering after a deploy.
 * API responses are never touched. Registers only over HTTPS. */

const VERSION = 'v2';
const SHELL_CACHE = `sdd-shell-${VERSION}`;
const ASSET_CACHE = `sdd-assets-${VERSION}`;
const KEEP = new Set([SHELL_CACHE, ASSET_CACHE]);

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(names.filter((n) => !KEEP.has(n)).map((n) => caches.delete(n)));
      await self.clients.claim();
    })(),
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') {
    return;
  }
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) {
    return; // let the network handle cross-origin and all API (live) traffic
  }

  const isShell = url.pathname === '/' || url.pathname === '/index.html';
  const cacheName = isShell ? SHELL_CACHE : ASSET_CACHE;
  const cacheKey = isShell ? '/index.html' : event.request;

  event.respondWith(
    fetch(event.request)
      .then((resp) => {
        if (resp && resp.ok) {
          const copy = resp.clone();
          caches.open(cacheName).then((c) => c.put(cacheKey, copy));
        }
        return resp;
      })
      .catch(async () => {
        const cached = await caches.match(cacheKey);
        if (cached) {
          return cached;
        }
        if (isShell) {
          return caches.match('/index.html');
        }
        throw new Error('offline and not cached: ' + url.pathname);
      }),
  );
});
