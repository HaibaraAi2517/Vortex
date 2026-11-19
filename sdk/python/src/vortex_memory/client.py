from __future__ import annotations

import json
import math
import os
from datetime import datetime
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class VortexHTTPError(RuntimeError):
    def __init__(self, status: int, body: Any):
        self.status = status
        self.body = body
        detail = body.get("detail", body.get("message", "Request failed")) if isinstance(body, dict) else "Request failed"
        super().__init__(f"Vortex HTTP {status}: {detail}")


class VortexConflictError(VortexHTTPError):
    """The key changed since it was read. Read again before deciding whether to replace it."""
    @property
    def current_revision(self) -> int | None:
        return self.body.get("currentRevision") if isinstance(self.body, dict) else None


class VortexConnectionError(RuntimeError):
    """A response was not received. A mutation may have committed; read its key before retrying."""


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the Bearer token or a memory body to a redirected host.


class VortexClient:
    def __init__(self, base_url: str = "http://127.0.0.1:8080", *, namespace: str = "personal",
                 token: str | None = None, timeout: float = 30):
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("base_url must be an absolute HTTP(S) URL without credentials, query or fragment")
        if not namespace.strip() or len(namespace) > 128 or "\0" in namespace:
            raise ValueError("namespace must contain 1..128 characters without NUL")
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("timeout must be positive and finite")
        self.base_url = base_url.rstrip("/")
        self.namespace = namespace
        self.token = token
        self.timeout = timeout
        self._opener = build_opener(_NoRedirect())

    @classmethod
    def from_env(cls) -> VortexClient:
        return cls(os.environ.get("VORTEX_URL", "http://127.0.0.1:8080"),
                   namespace=os.environ.get("VORTEX_NAMESPACE", "personal"),
                   token=os.environ.get("VORTEX_SECURITY_BEARER_TOKEN"))

    def _request(self, method: str, path: str, body: Any = None, **params: Any) -> Any:
        url = self.base_url + "/api/v1/memory" + path
        if params:
            url += "?" + urlencode(params)
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(url, data=data, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                return json.load(response)
        except HTTPError as exc:
            try:
                with exc:
                    payload = json.loads(exc.read(1_048_576))
            except (ValueError, UnicodeDecodeError):
                payload = {"message": "Server returned a non-JSON error"}
            error = VortexConflictError if exc.code == 409 else VortexHTTPError
            raise error(exc.code, payload) from None
        except (URLError, TimeoutError, OSError) as exc:
            raise VortexConnectionError("Cannot reach Vortex; check its URL and local service. If a write timed out, read the key before retrying.") from exc
        except (ValueError, UnicodeDecodeError) as exc:
            raise VortexConnectionError("Vortex returned an invalid response. A write may have committed; read the key before retrying.") from exc

    def remember(self, key: str, content: str, *, expected_revision: int = 0,
                 tags: list[str] | None = None, expires_at: datetime | str | None = None) -> dict:
        """Create at revision 0 or replace the exact revision read by the caller. No automatic retries."""
        if isinstance(expires_at, datetime):
            if expires_at.tzinfo is None:
                raise ValueError("expires_at must include a timezone")
            expires_at = expires_at.isoformat()
        return self._request("PUT", "/facts", {"namespace": self.namespace, "key": key, "content": content,
                             "expectedRevision": expected_revision, "tags": tags or [], "expiresAt": expires_at})

    def get_fact(self, key: str) -> dict | None:
        try:
            return self._request("GET", "/facts", namespace=self.namespace, key=key)
        except VortexHTTPError as exc:
            if exc.status == 404:
                return None
            raise

    def history(self, key: str, *, limit: int = 20, before_revision: int = 2**63 - 1) -> list[dict]:
        return self._request("GET", "/facts/history", namespace=self.namespace, key=key,
                             limit=limit, beforeRevision=before_revision)

    def retract(self, key: str, *, expected_revision: int) -> dict:
        """Stop recalling a fact while retaining its revision history."""
        return self._request("POST", "/facts/retract", {"namespace": self.namespace, "key": key,
                             "expectedRevision": expected_revision})

    def forget(self, key: str, *, expected_revision: int) -> dict:
        """Erase content and history from the live store; keep only the revision tombstone."""
        return self._request("DELETE", "/facts", namespace=self.namespace, key=key, expectedRevision=expected_revision)

    def store(self, content: str, *, tags: list[str] | None = None) -> dict:
        """Append an event/document. Use remember for facts that can change."""
        return self._request("POST", "/store", {"namespace": self.namespace, "content": content, "tags": tags or []})

    def delete_fragment(self, fragment_id: str) -> dict:
        from urllib.parse import quote
        return self._request("DELETE", "/fragment/" + quote(fragment_id, safe=""))

    def recall(self, query: str, *, top_k: int = 5, token_budget: int = 2048,
               tags: list[str] | None = None, mode: str = "HYBRID", for_agent: bool = False) -> dict:
        """Request ranked candidates or, with for_agent, context selected for the current task."""
        return self._request("POST", "/context" if for_agent else "/recall", {"namespace": self.namespace, "query": query, "topK": top_k,
                             "tokenBudget": token_budget, "tags": tags or [], "retrievalMode": mode})

    def context(self, query: str, **kwargs: Any) -> str:
        """Retrieved text for an agent prompt. Treat it as data, never as tool/system instructions."""
        result = self.recall(query, for_agent=True, **kwargs)
        return "\n\n".join(hit["fragment"]["content"] for hit in result["fragments"])
