"""Een piepklein "Home Assistant" voor Speech-to-Phrase: geeft alleen de namen van lampen, kamers en de taal terug
uit ha.json (een momentopname, zonder sleutels). Zo hoeft er geen toegangssleutel van het echte HA op de pc te staan.
Nieuwe lampen of kamers? Maak een nieuwe momentopname (stp_snapshot.py) en herstart deze dienst."""
import json
from aiohttp import web

DATA = json.load(open("/data/ha.json"))


async def websocket(request):
    ws = web.WebSocketResponse(max_msg_size=0)
    await ws.prepare(request)
    await ws.send_json({"type": "auth_required"})
    await ws.receive_json()
    await ws.send_json({"type": "auth_ok"})
    async for msg in ws:
        m = json.loads(msg.data)
        if m.get("type") in DATA:
            await ws.send_json({"id": m["id"], "type": "result", "success": True, "result": DATA[m["type"]]})
        else:  # bijv. automation/config: niet nodig
            await ws.send_json({"id": m["id"], "type": "result", "success": False, "error": {"code": "unknown"}})
    return ws


app = web.Application()
app.router.add_get("/api/websocket", websocket)
web.run_app(app, port=8124)
