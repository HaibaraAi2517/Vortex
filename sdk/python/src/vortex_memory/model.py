"""Small synchronous adapter for OpenAI-compatible Chat Completions endpoints."""
from __future__ import annotations

import json
import math
import os
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener

from .client import _NoRedirect


class ModelError(RuntimeError):
    """A model response was missing, incomplete, or rejected. Provider bodies are not logged."""


class CompatibleChatModel:
    def __init__(self, base_url: str, model: str, *, api_key: str = "", timeout: float = 120):
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("Use an HTTP(S) model base URL without credentials, query or fragment")
        if not model.strip() or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("A model name and positive finite timeout are required")
        self.url, self.model = base_url.rstrip("/") + "/chat/completions", model
        self.api_key, self.timeout = api_key, timeout
        self._opener = build_opener(_NoRedirect())

    @classmethod
    def from_env(cls) -> CompatibleChatModel:
        explicit = any(os.environ.get(k) for k in ("MODEL_BASE_URL", "MODEL_NAME", "MODEL_API_KEY"))
        if not explicit and os.environ.get("DEEPSEEK_API_KEY"):
            return cls("https://api.deepseek.com/v1", "deepseek-chat", api_key=os.environ["DEEPSEEK_API_KEY"])
        url, model = os.environ.get("MODEL_BASE_URL"), os.environ.get("MODEL_NAME")
        if not url or not model:
            raise ValueError("Set MODEL_BASE_URL, MODEL_NAME and MODEL_API_KEY (or DEEPSEEK_API_KEY alone)")
        return cls(url, model, api_key=os.environ.get("MODEL_API_KEY", ""),
                   timeout=float(os.environ.get("MODEL_TIMEOUT_SECONDS", "120")))

    def __call__(self, messages: list[dict[str, str]], *, json_mode: bool = False) -> str:
        body = {"model": self.model, "messages": messages, "temperature": 0, "max_tokens": 2048}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.api_key:
            headers["Authorization"] = "Bearer " + self.api_key
        request = Request(self.url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), headers=headers)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                payload = json.load(response)
        except HTTPError as exc:
            status = exc.code
            exc.close()
            raise ModelError(f"Model endpoint returned HTTP {status}") from None
        except (URLError, TimeoutError, OSError, ValueError):
            raise ModelError("Model endpoint did not return a valid response") from None
        try:
            choice = payload["choices"][0]
            text = choice["message"]["content"]
            if choice.get("finish_reason") != "stop" or not isinstance(text, str) or not text.strip():
                raise ValueError()
            return text
        except (KeyError, IndexError, TypeError, ValueError):
            raise ModelError("Model response was empty, truncated or requested an unsupported tool call") from None
