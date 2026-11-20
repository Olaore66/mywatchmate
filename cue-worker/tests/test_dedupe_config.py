import pytest

from worker.config import Config
from worker.dedupe import Dedupe


def test_the_same_code_is_reported_once_per_window_per_session():
    now = [0.0]
    dedupe = Dedupe(600, clock=lambda: now[0])

    assert dedupe.is_new("s1", "link")
    assert not dedupe.is_new("s1", "link")
    assert dedupe.is_new("s2", "link"), "another room is another room"
    assert dedupe.is_new("s1", "other-link")
    now[0] = 601
    assert dedupe.is_new("s1", "link"), "after the window, it counts again"


def test_an_ended_session_is_forgotten():
    dedupe = Dedupe(600, clock=lambda: 0.0)
    dedupe.is_new("s1", "link")
    dedupe.forget_session("s1")
    assert dedupe.is_new("s1", "link")


def test_settings_come_from_the_environment():
    config = Config.from_env({"INTERNAL_CUE_TOKEN": " secret ", "WORKER_SHARD": "1/3", "MAX_ROOMS": "5",
                              "MWM_API_BASE": "https://api.example/"})
    assert config.token == "secret"
    assert (config.shard, config.shards, config.max_rooms) == (1, 3, 5)
    assert config.api_base == "https://api.example"


@pytest.mark.parametrize("env", [{}, {"INTERNAL_CUE_TOKEN": "x", "WORKER_SHARD": "3/3"},
                                 {"INTERNAL_CUE_TOKEN": "x", "WORKER_SHARD": "half"}])
def test_bad_settings_stop_it_at_the_start(env):
    with pytest.raises(SystemExit):
        Config.from_env(env)
