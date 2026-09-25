#!/usr/bin/env python3
"""Actual KWin Wayland minimize/restore, with Field heartbeat and resumed NGX image checks.

Run under the desktop user's DBus session, against a freshly built Field --port.
Only the uniquely named smoke window is changed; every KWin script is unloaded.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/neural-tools/wayland"


def dbus(*args):
    return subprocess.check_output(["qdbus6", "org.kde.KWin", *args], text=True).strip()


def kwin(minimized, token):
    name = "field-wayland-" + token
    script = WORK / (name + ".js")
    script.write_text('const w=workspace.windowList().find(w=>w.caption==="Field Wayland reconstruction check");\n'
                      'if (!w) throw new Error("Field test window missing");\n'
                      f'w.minimized={str(minimized).lower()};\n'
                      f'print("FIELD_WAYLAND_{token} "+JSON.stringify({{id:String(w.internalId),minimized:w.minimized,caption:w.caption}}));\n')
    index = dbus("/Scripting", "org.kde.kwin.Scripting.loadScript", str(script), name)
    try:
        dbus("/Scripting/Script" + index, "org.kde.kwin.Script.run")
        for _ in range(30):
            lines = subprocess.check_output(["journalctl", "--user", "--since", "1 minute ago", "-o", "cat", "--no-pager"], text=True)
            hits = [line.split("FIELD_WAYLAND_" + token + " ", 1)[1] for line in lines.splitlines() if "FIELD_WAYLAND_" + token + " " in line]
            if hits:
                state = json.loads(hits[-1]);assert state["minimized"] == minimized, state
                return state
            time.sleep(.1)
        raise RuntimeError("KWin did not confirm its window state")
    finally:
        dbus("/Scripting", "org.kde.kwin.Scripting.unloadScript", name)


def heartbeat(predicate, timeout=90):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            state=json.loads((WORK/"heartbeat.json").read_text())
            if predicate(state):return state
        except (FileNotFoundError,json.JSONDecodeError):pass
        time.sleep(.1)
    raise RuntimeError("Field heartbeat timed out")


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--port",type=int,required=True);args=parser.parse_args()
    WORK.mkdir(parents=True,exist_ok=True)
    for path in [WORK/"heartbeat.json",WORK/"phase.txt"]:path.unlink(missing_ok=True)
    records=[];token=str(time.time_ns())
    with (WORK/"backdoor.json").open("w") as log:
        process=subprocess.Popen([sys.executable,"tools/field_backdoor.py","--json","--attach-existing","--port",str(args.port),
            "--timeout","140","--command",f"run-jvm-main afield.four.reconstruction.WaylandReconstructionSmoke --arg {WORK} --timeout 120"],cwd=ROOT,stdout=log)
        try:
            heartbeat(lambda s:s["ready"])
            for cycle in range(3):
                records.append(kwin(True,f"{token}_{cycle}_min"))
                before=heartbeat(lambda s:True);time.sleep(2)
                after=heartbeat(lambda s:s["iterations"]>before["iterations"]+5,timeout=5)
                records[-1]["heartbeatIterations"]=after["iterations"]-before["iterations"]
                records.append(kwin(False,f"{token}_{cycle}_restore"))
                phase=f"restored-{cycle}";(WORK/"phase.txt").write_text(phase)
                heartbeat(lambda s:s["phase"]==phase,timeout=10)
                assert (WORK/(phase+".png")).exists()
            (WORK/"phase.txt").write_text("finish")
            process.wait(timeout=30)
        finally:
            if process.poll() is None:
                try:kwin(False,token+"_cleanup")
                finally:(WORK/"phase.txt").write_text("finish")
                process.wait(timeout=30)
    text=(WORK/"backdoor.json").read_text()
    assert process.returncode==0 and "WAYLAND RECONSTRUCTION CHECK DONE" in text and "VUID-" not in text,text[-2000:]
    report={"compositor":"KWin Wayland","cycles":records,"field":heartbeat(lambda s:True),"vulkanValidationErrors":0,"completionMarker":True}
    (WORK/"verification.json").write_text(json.dumps(report,indent=2)+"\n");print(json.dumps(report,indent=2))


if __name__=="__main__":main()
