from bodrod.clients.slskd import SlskdFile, SlskdResponse
from bodrod.config import DEFAULT_FORMATS
from bodrod.matching import rank_candidates
from bodrod.text import normalize, similarity


def peer(username: str, *files: SlskdFile, free: bool = True, queue: int = 0, speed: int = 1_000_000) -> SlskdResponse:
    return SlskdResponse(
        username=username, has_free_slot=free, queue_length=queue, upload_speed=speed, files=list(files)
    )


def f(path: str, ext: str = "mp3", bitrate: int | None = 320, length: int | None = 200) -> SlskdFile:
    return SlskdFile(filename=path, size=8_000_000, extension=ext, bit_rate=bitrate, length_s=length)


def rank(responses, artist="Трофим", title="Город Сочи", duration=200):
    return rank_candidates(
        responses,
        artist=artist,
        title=title,
        duration_s=duration,
        allowed_formats=DEFAULT_FORMATS,
        min_bitrate=192,
        limit=5,
    )


def test_normalize():
    assert normalize("Трофим — Город Сочи (Remastered 2020) feat. Кто-то") == "трофим город сочи"
    assert normalize("Ёлка") == "елка"
    assert similarity("город сочи", "Город Сочи!") == 1.0


def test_picks_matching_file_and_rejects_wrong_ones():
    responses = [
        peer("remixer", f(r"Music\Трофим\Трофим - Город Сочи (DJ Remix).mp3")),
        peer("other_artist", f(r"Music\Шансон\Иванов - Город Сочи.mp3")),
        peer("lowq", f(r"Music\Трофим\02 Город Сочи.mp3", bitrate=128)),
        peer("wrong_length", f(r"Music\Трофим\Город Сочи.mp3", length=400)),
        peer("good", f(r"Music\Трофим\Город Сочи\03. Трофим - Город Сочи.mp3")),
        peer("not_audio", f(r"Music\Трофим\Город Сочи.txt", ext="txt")),
    ]
    result = rank(responses)
    assert [c.username for c in result] == ["good"]


def test_prefers_lossless_and_free_slot():
    responses = [
        peer("busy_flac", f(r"Трофим\Город Сочи.flac", ext="flac", bitrate=None), free=False, queue=40),
        peer("fast_flac", f(r"Трофим\Город Сочи.flac", ext="flac", bitrate=None)),
        peer("mp3", f(r"Трофим\Город Сочи.mp3")),
    ]
    result = rank(responses)
    assert result[0].username == "fast_flac"
    assert {c.username for c in result} == {"fast_flac", "busy_flac", "mp3"}


def test_one_candidate_per_peer():
    responses = [peer("collector", f(r"A\Трофим - Город Сочи.mp3"), f(r"B\Трофим - Город Сочи.flac", ext="flac"))]
    result = rank(responses)
    assert len(result) == 1
    assert result[0].filename.endswith(".flac")


def test_remix_allowed_when_requested():
    responses = [peer("p", f(r"Трофим\Трофим - Город Сочи (Remix).mp3"))]
    assert rank(responses, title="Город Сочи (Remix)") != []
