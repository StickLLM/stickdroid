"""Run the stick's REAL methodc_peer.run_one_session() against the box lab.

Only two seams are patched (both stick-local, nothing on the phone wire):
  * ice_configuration() -> no ICE servers, so the box never contacts public STUN
    (stun.l.google.com / stun.cloudflare.com / relay-tor1.stickllm.com).
  * mint_dev_token() -> redeem a lab grant at the mock gateway instead of running
    /usr/local/bin/stickllm-sticklink-lab-grant via sudo against https://127.0.0.1:443.
The A0 upstream is the mock gateway on https://127.0.0.1:<MOCK_PORT> (loopback,
so LocalA0Bridge's loopback check still runs).
"""
import asyncio, json, os, secrets, ssl, sys, time, urllib.request

from sticklink import methodc_peer as mp
from aiortc import RTCConfiguration

A0 = os.environ.get("LAB_A0", "https://127.0.0.1:8443")


def no_ice():
    return RTCConfiguration(iceServers=[])


def lab_mint(*, force: bool = False) -> str:
    grant = secrets.token_urlsafe(32)
    req = urllib.request.Request(f"{A0}/api/pair/sticklink-lab", data=json.dumps({"grant": grant}).encode(),
                                 method="POST", headers={"Content-Type": "application/json", "X-StickLLM-Client": "ui"})
    with urllib.request.urlopen(req, context=ssl._create_unverified_context(), timeout=10) as r:
        return json.loads(r.read())["token"]


mp.ice_configuration = no_ice
mp.mint_dev_token = lab_mint


async def main():
    mp.log(f"LAB peer: A0 upstream={A0} ws={mp.WS_URL} join_base={mp.JOIN_PUBLIC_BASE} pair_exp={mp.PAIR_OPEN_EXP_SEC}")
    while True:
        try:
            await mp.run_one_session(A0)
        except Exception as e:
            mp.log(f"session ended: {type(e).__name__}: {e}; retry in {mp.SESSION_RETRY_SEC}s")
            await mp.sleep_interruptible(mp.SESSION_RETRY_SEC)

asyncio.run(main())
