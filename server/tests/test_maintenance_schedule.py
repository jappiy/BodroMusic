import asyncio
import datetime as dt

import pytest
from fastapi.testclient import TestClient

from bodrod.main import build_services, create_app

from .test_api import auth
from .test_maintenance import catalog_world, make


def test_schedule_api_persists_and_validates(tmp_path):
    world = catalog_world()
    services, _, db, _ = make(world, tmp_path, maintenance_at="05:15")
    endpoint = "/api/v1/maintenance/schedule"
    with TestClient(create_app(services, run_worker=False)) as client:
        assert client.get("/api/v1/maintenance", headers=auth()).json()["schedule"] == "05:15"
        assert client.put(endpoint, json={"schedule": "06:30"}).status_code == 401
        for value in ["24:00", "12:60", "6:30", "", "04:00\n"]:
            assert client.put(endpoint, headers=auth(), json={"schedule": value}).status_code == 422
        result = client.put(endpoint, headers=auth(), json={"schedule": "06:30"})
        assert result.status_code == 200
        assert result.json()["schedule"] == "06:30"
        assert dt.datetime.fromtimestamp(result.json()["nextRunAt"] / 1000).time() == dt.time(6, 30)
        # Не-администратор не может менять общее расписание.
        import sqlite3

        with sqlite3.connect(db) as conn:
            conn.execute("UPDATE user SET is_admin = 0 WHERE user_name = 'vasya'")
        assert client.put(endpoint, headers=auth(), json={"schedule": "07:00"}).status_code == 403
    restarted = build_services(services.settings, world.client())
    assert restarted.maintenance.schedule == "06:30"


@pytest.mark.parametrize("schedule", ["00:00", "06:30", "23:59"])
def test_next_run_rolls_to_tomorrow_at_or_after_target(tmp_path, schedule):
    services, _, _, _ = make(catalog_world(), tmp_path)
    maintenance = services.maintenance
    maintenance.set_schedule(schedule)
    hour, minute = map(int, schedule.split(":"))
    target = dt.datetime(2026, 11, 8, hour, minute)
    assert maintenance.next_run_at(target.timestamp() - 1) == target.timestamp()
    assert maintenance.next_run_at(target.timestamp()) == (target + dt.timedelta(days=1)).timestamp()


def test_changing_schedule_wakes_sleeping_scheduler(tmp_path):
    services, _, _, _ = make(catalog_world(), tmp_path)
    maintenance = services.maintenance

    async def scenario():
        calculated = []

        def next_run():
            import time

            calculated.append(maintenance.schedule)
            return time.time() + 3600

        maintenance.next_run_at = next_run
        task = asyncio.create_task(maintenance.run_forever())
        await asyncio.sleep(0)
        maintenance.set_schedule("07:45")
        for _ in range(20):
            await asyncio.sleep(0)
            if calculated[-1] == "07:45":
                break
        assert calculated == ["04:00", "07:45"]
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
