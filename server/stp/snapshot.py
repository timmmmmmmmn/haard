# Gebruik: HA_TOKEN=... python3 snapshot.py, dan ha.json naar ~/haard/stp/ op de XPS en 'docker restart ha-lijst speech-to-phrase'.
# Maakt een momentopname van wat Speech-to-Phrase van Home Assistant wil weten (geen sleutels erin)
import asyncio, json, os, aiohttp
async def main():
    tok = os.environ['HA_TOKEN']  # langlevend token, alleen voor dit ene moment
    uit = {}
    async with aiohttp.ClientSession() as s, s.ws_connect('wss://ha.timvanbergen.nl/api/websocket', max_msg_size=0) as w:
        await w.receive_json(); await w.send_json({'type': 'auth', 'access_token': tok}); await w.receive_json()
        n = 0
        async def vraag(m):
            nonlocal n; n += 1; m['id'] = n; await w.send_json(m)
            while True:
                r = await w.receive_json()
                if r.get('id') == n: return r.get('result')
        cfg = await vraag({'type': 'get_config'}); uit['get_config'] = {'language': cfg['language']}
        pl = await vraag({'type': 'assist_pipeline/pipeline/list'})
        uit['assist_pipeline/pipeline/list'] = {'pipelines': [{'stt_language': p.get('stt_language'), 'language': p.get('language')} for p in pl['pipelines']]}
        ex = await vraag({'type': 'homeassistant/expose_entity/list'})
        zichtbaar = [e for e, i in ex['exposed_entities'].items() if i.get('conversation')]
        uit['homeassistant/expose_entity/list'] = {'exposed_entities': {e: {'conversation': True} for e in zichtbaar}}
        st = await vraag({'type': 'get_states'}); uit['get_states'] = [x for x in st if x['entity_id'] in zichtbaar]
        uit['config/floor_registry/list'] = await vraag({'type': 'config/floor_registry/list'})
        uit['config/area_registry/list'] = await vraag({'type': 'config/area_registry/list'})
        uit['config/entity_registry/get_entries'] = await vraag({'type': 'config/entity_registry/get_entries', 'entity_ids': zichtbaar})
        uit['conversation/sentences/list'] = await vraag({'type': 'conversation/sentences/list'}) or {'trigger_sentences': []}
    json.dump(uit, open('ha.json', 'w'), ensure_ascii=False)
    print(len(zichtbaar), 'apparaten,', len(uit['config/area_registry/list']), 'kamers, taal', uit['get_config']['language'])
asyncio.run(main())
