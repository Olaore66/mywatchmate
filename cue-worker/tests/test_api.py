import json

import httpx
import pytest

from worker import api as api_module
from worker.api import BackendApi


@pytest.fixture(autouse=True)
def no_waiting(monkeypatch):
    async def instant(_seconds):
        return None
    monkeypatch.setattr(api_module.asyncio, "sleep", instant)


def backend(handler):
    return BackendApi("https://api.example", "secret", httpx.AsyncClient(transport=httpx.MockTransport(handler)))


async def test_asks_for_live_sessions_with_the_service_token():
    def handler(request):
        assert request.url.path == "/api/v1/internal/live-sessions"
        assert request.headers["X-Service-Token"] == "secret"
        return httpx.Response(200, json=[{"sessionId": "a1", "spaceId": "r1", "source": "LIVESTREAM",
                                          "streamUrl": "https://cdn/live.m3u8", "screenShare": False}])

    sessions = await backend(handler).live_sessions()

    assert sessions[0].session_id == "a1"
    assert sessions[0].stream_url == "https://cdn/live.m3u8"


async def test_a_cue_is_sent_and_the_backends_answer_returned():
    def handler(request):
        assert json.loads(request.content) == {"sessionId": "a1", "rawValue": "link", "cueType": "QR"}
        return httpx.Response(200, json={"detectionId": "d1", "status": "SHOWN", "reason": None})

    assert (await backend(handler).report_cue("a1", "link"))["status"] == "SHOWN"


async def test_trouble_is_retried_but_a_refusal_is_final():
    calls = {"n": 0}

    def flaky(request):
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.ConnectError("down")
        if calls["n"] == 2:
            return httpx.Response(503)
        return httpx.Response(200, json={"status": "REJECTED", "reason": "NOT_PAYAZA"})

    assert (await backend(flaky).report_cue("a1", "x"))["reason"] == "NOT_PAYAZA"
    assert calls["n"] == 3

    refused = {"n": 0}

    def unauthorised(request):
        refused["n"] += 1
        return httpx.Response(401, json={"code": "INVALID_SERVICE_TOKEN"})

    assert await backend(unauthorised).report_cue("a1", "x") is None
    assert refused["n"] == 1


async def test_it_gives_up_after_three_tries():
    assert await backend(lambda request: httpx.Response(500)).report_cue("a1", "x") is None
