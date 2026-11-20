import httpx

from worker.liveness import NOT_LIVE_RECHECK_SECONDS, Liveness

LIVE = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:40\n#EXTINF:2.0,\nseg40.ts\n"
RECORDING = LIVE + "#EXT-X-ENDLIST\n"
MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh/index.m3u8\n"


def liveness(pages, now=None):
    def handler(request):
        body = pages.get(str(request.url))
        return httpx.Response(200, text=body) if body is not None else httpx.Response(404)

    clock = (lambda: now[0]) if now else (lambda: 0.0)
    return Liveness(httpx.AsyncClient(transport=httpx.MockTransport(handler)), clock=clock)


async def test_a_playlist_still_being_written_is_live_and_a_finished_one_isnt():
    check = liveness({"https://cdn/live.m3u8": LIVE, "https://cdn/old.m3u8": RECORDING})

    assert await check.is_live("https://cdn/live.m3u8")
    assert not await check.is_live("https://cdn/old.m3u8")


async def test_a_master_playlist_is_judged_by_its_first_variant():
    check = liveness({"https://cdn/show/master.m3u8": MASTER, "https://cdn/show/low/index.m3u8": LIVE})

    assert await check.is_live("https://cdn/show/master.m3u8")


async def test_files_errors_and_nonsense_are_not_live():
    check = liveness({"https://cdn/empty.m3u8": "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "https://cdn/page.m3u8": "<html>"})

    assert not await check.is_live("https://cdn/sermon.mp4")
    assert not await check.is_live("https://cdn/missing.m3u8")
    assert not await check.is_live("https://cdn/empty.m3u8")
    assert not await check.is_live("https://cdn/page.m3u8")


async def test_a_not_live_answer_is_remembered_for_a_while_then_checked_again():
    now = [0.0]
    pages = {"https://cdn/later.m3u8": RECORDING}
    check = liveness(pages, now)
    assert not await check.is_live("https://cdn/later.m3u8")

    pages["https://cdn/later.m3u8"] = LIVE
    assert not await check.is_live("https://cdn/later.m3u8"), "still within the wait"
    now[0] = NOT_LIVE_RECHECK_SECONDS + 1
    assert await check.is_live("https://cdn/later.m3u8?token=1".split("?")[0])
