from fastapi.testclient import TestClient

from bodrod.clients.navidrome import Credentials
from bodrod.main import build_services, create_app

from .fakes import FakeWorld, yandex_track
from .test_worker import SETTINGS


def make_client(world: FakeWorld, tmp_path) -> TestClient:
    settings = SETTINGS.__class__(**{**SETTINGS.__dict__, "data_dir": str(tmp_path)})
    services = build_services(settings, world.client())
    return TestClient(create_app(services, run_worker=False))


def auth(user: str = "vasya", password: str = "secret") -> dict[str, str]:
    creds = Credentials.from_password(user, password)
    return {"X-Subsonic-User": creds.username, "X-Subsonic-Token": creds.token, "X-Subsonic-Salt": creds.salt}


def test_requires_navidrome_credentials(tmp_path):
    with make_client(FakeWorld(), tmp_path) as client:
        assert client.get("/api/health").status_code == 200
        assert client.get("/api/v1/requests").status_code == 401
        assert client.get("/api/v1/requests", headers=auth(password="wrong")).status_code == 401
        info = client.get("/api/v1/info", headers=auth()).json()
        assert info["trackDownloads"] is True and info["artistDownloads"] is True
        assert info["metadataSources"] == ["yandex", "deezer"]


def test_discover_endpoint_uses_camel_case(tmp_path):
    world = FakeWorld(yandex_search={"tracks": {"results": [yandex_track(1, "Город Сочи", "Трофим")]}})
    with make_client(world, tmp_path) as client:
        body = client.get("/api/v1/discover", params={"q": "город сочи"}, headers=auth()).json()
    assert body["intent"] == "track"
    assert body["tracks"][0]["durationMs"] == 200_000
    assert body["tracks"][0]["sourceId"] == "1"
    assert body["topTracks"] == []


def test_create_list_dedup_and_cancel(tmp_path):
    with make_client(FakeWorld(), tmp_path) as client:
        payload = {"kind": "track", "artist": "Трофим", "title": "Город Сочи", "durationMs": 200000}
        first = client.post("/api/v1/requests", json=payload, headers=auth())
        assert first.status_code == 201
        assert first.json()["status"] == "queued"
        again = client.post("/api/v1/requests", json={**payload, "title": "город сочи"}, headers=auth())
        assert again.status_code == 200
        assert again.json()["id"] == first.json()["id"]

        listed = client.get("/api/v1/requests", headers=auth()).json()
        assert [r["id"] for r in listed] == [first.json()["id"]]
        assert client.get("/api/v1/requests", headers=auth("admin", "adminpass")).json() == []

        request_id = first.json()["id"]
        assert client.delete(f"/api/v1/requests/{request_id}", headers=auth()).status_code == 204
        assert client.get(f"/api/v1/requests/{request_id}", headers=auth()).json()["status"] == "cancelled"
        assert client.delete(f"/api/v1/requests/{request_id}", headers=auth()).status_code == 204
        assert client.get(f"/api/v1/requests/{request_id}", headers=auth()).status_code == 404


def test_validation(tmp_path):
    with make_client(FakeWorld(), tmp_path) as client:
        assert client.post("/api/v1/requests", json={"kind": "track", "artist": "X"}, headers=auth()).status_code == 422
        artist = client.post("/api/v1/requests", json={"kind": "artist", "artist": "Трофим"}, headers=auth())
        assert artist.status_code == 201
        assert artist.json()["title"] is None


def test_batch_requests_skip_active_and_blank(tmp_path):
    with make_client(FakeWorld(), tmp_path) as client:
        one = {"kind": "track", "artist": "Трофим", "title": "Город Сочи"}
        first = client.post("/api/v1/requests", json=one, headers=auth()).json()
        items = [one, {"kind": "track", "artist": "Земфира", "title": "Хочешь"}, {"kind": "track", "artist": "X"}]
        body = client.post("/api/v1/requests/batch", json={"items": items}, headers=auth())
        assert body.status_code == 200
        ids = [r["id"] for r in body.json()]
        assert len(ids) == 2 and ids[0] == first["id"]
        assert client.get("/api/v1/info", headers=auth()).json()["likesImport"] is False
