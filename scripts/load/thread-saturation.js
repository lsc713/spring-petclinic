import http from 'k6/http';

// More VUs than the chaos-threads pool (max=10), each request parks its worker,
// so the Tomcat pool saturates.
export const options = {
	vus: 30,
	duration: '90s',
};

export default function () {
	http.get('http://host.docker.internal:8080/owners?lastName=Davis');
}
