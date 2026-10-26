import asyncio

import httpx

from bodrod.metadata.images import ImageLookup


def deezer(request: httpx.Request) -> httpx.Response:
    if request.url.path == "/search/artist":
        return httpx.Response(
            200,
            json={"data": [
                {"name": "Другой", "picture_xl": "https://e-cdns-images.dzcdn.net/images/artist/aaa/1000x1000.jpg"},
                {"name": "Noize MC", "picture_xl": "https://e-cdns-images.dzcdn.net/images/artist/bbb/1000x1000.jpg"},
                {"name": "Безымянный", "picture_xl": "https://e-cdns-images.dzcdn.net/images/artist//1000x1000.jpg"},
            ]},
        )  # fmt: skip
    if request.url.path == "/search/track":
        return httpx.Response(
            200,
            json={
                "data": [{"title": "Uprising", "artist": {"name": "Muse"}, "album": {"cover_xl": "https://c/1.jpg"}}]
            },
        )
    return httpx.Response(404)


def test_artist_image_and_track_cover_from_deezer():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return deezer(request)

    lookup = ImageLookup(httpx.AsyncClient(transport=httpx.MockTransport(handler)), None)

    async def scenario():
        return (
            await lookup.artist_image("Noize MC"),
            await lookup.artist_image("Безымянный"),
            await lookup.track_cover("Muse", "Uprising"),
            await lookup.track_cover("Muse", "Uprising"),
        )

    noize, nameless, cover, again = asyncio.run(scenario())
    assert noize.endswith("/bbb/1000x1000.jpg")
    assert nameless is None  # заглушка Deezer без хеша — не фото
    assert cover == again == "https://c/1.jpg"
    assert calls.count("/search/track") == 1  # второй раз — из кэша
