import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    public_reads: { executor: "constant-vus", vus: 20, duration: "2m", exec: "publicReads" },
    authenticated_reads: { executor: "constant-vus", vus: 5, duration: "2m", exec: "authenticatedReads" },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{path:document}": ["p(95)<300", "p(99)<750"],
    "http_req_duration{path:sources}": ["p(95)<300", "p(99)<750"],
    "http_req_duration{path:me}": ["p(95)<400", "p(99)<1000"],
  },
};

const baseUrl = (__ENV.KIRA_API_URL || "").replace(/\/$/, "");
const token = __ENV.KIRA_LOAD_TOKEN || "";

export function setup() {
  if (!baseUrl.startsWith("https://")) throw new Error("KIRA_API_URL must be an HTTPS API origin");
  if (!token) throw new Error("KIRA_LOAD_TOKEN is required and must be a short-lived non-admin token");
}

export function publicReads() {
  const meta = http.get(`${baseUrl}/source-config/document/meta`, { tags: { path: "document-meta" } });
  check(meta, { "metadata is 200": (r) => r.status === 200 });

  const document = http.get(`${baseUrl}/source-config/document`, { tags: { path: "document" } });
  check(document, { "document is signed": (r) => r.status === 200 && !!r.headers["X-Config-Signature"] });
  if (document.headers.ETag) {
    const unchanged = http.get(`${baseUrl}/source-config/document`, {
      headers: { "If-None-Match": document.headers.ETag },
      tags: { path: "document-conditional" },
    });
    check(unchanged, { "conditional document is 304": (r) => r.status === 304 });
  }

  const sources = http.get(`${baseUrl}/sources?lifecycle=active,disabled`, { tags: { path: "sources" } });
  check(sources, { "source list is 200": (r) => r.status === 200 });
  sleep(0.2);
}

export function authenticatedReads() {
  const response = http.get(`${baseUrl}/auth/me`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { path: "me" },
  });
  check(response, { "authenticated identity is 200": (r) => r.status === 200 });
  sleep(0.5);
}
