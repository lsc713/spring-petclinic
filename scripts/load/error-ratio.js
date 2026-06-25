import http from 'k6/http';

// Drives the vet-list path. With the error-ratio fault armed, every request
// returns 5xx, pushing the SERVER_ERROR ratio over the alert threshold.
export const options = { vus: 20, duration: '45s' };

export default function () {
  http.get('http://host.docker.internal:8080/vets.html');
}
