import http from 'k6/http';

// Drives the owner-search list path under concurrency. With the N+1 latency
// fault armed, the extra per-row queries + connection-pool contention push
// http_server_requests p99 over the alert threshold.
export const options = { vus: 50, duration: '60s' };

export default function () {
  http.get('http://host.docker.internal:8080/owners?lastName=');
}
