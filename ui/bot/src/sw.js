/* Minimal service worker: network-first for navigation (keeps the dashboard
 * fresh — it is a live trading view), cache-first for static assets. Registers
 * only when the app is served over HTTPS. */
self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET') {
    return;
  }
  if (url.pathname.startsWith('/api/')) {
    // Never cache API responses (live trading data).
    return;
  }
  if (url.pathname === '/') {
    // Network-first for the shell so deploys show immediately.
    event.respondWith(
      fetch(event.request)
        .then((resp) => {
          const copy = resp.clone();
          caches.open('sdd-shell').then((c) => c.put('/index.html', copy));
          return resp;
        })
        .catch(() => caches.match('/index.html')),
    );
    return;
  }
  event.respondWith(
    caches.match(event.request).then(
      (cached) =>
        cached ||
        fetch(event.request).then((resp) => {
          const copy = resp.clone();
          caches.open('sdd-assets').then((c) => c.put(event.request, copy));
          return resp;
        }),
    ),
  );
});
