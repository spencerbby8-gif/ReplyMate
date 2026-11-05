#!/usr/bin/env python3
"""ReplyMate P22 UI-emulator driver (HARNESS ONLY — tools/emulator/**, PR #2 tree).

Runs the REAL ReplyMate UI on a stock AOSP emulator (API 30 by workflow design —
the owner's device class, OPPO A16K / Android 11) driven by pure adb +
uiautomator-dump + screencap. No test APK, no instrumentation, no product-code
changes: everything asserted is produced by the unmodified app.

Coverage (P22 sections 3-5):
  S1  cold launch -> onboarding skip -> Home reachable
  S2  Settings -> "My voice (global style)" -> Voice screen renders (9 dials)
  S3  Reply length -> Detailed        (row sub updates immediately)
  S4  Emoji use -> Off                (row sub updates immediately)
  S5  force-stop + relaunch -> both choices PERSIST (persistence proof)
  S6  mock HTTPS provider via UI (real TLS, CA trusted into the SYSTEM store
      by root/remount; if the image refuses remount the provider scenarios are
      marked SKIP with the reason logged — never silently)
  S7  Voice preview over rotating realistic sample chats -> REAL generation
      through the app's own HTTP stack; captured request proves the P22 fix:
      length=short carries "usually one short sentence", then length=detailed
      carries NO "1-3 short sentences" clause (dial reaches the real pipeline)
  S8  Writing samples on a manually created contact: add -> visible in stats
      -> survives force-stop/relaunch -> Clear samples empties (P22 learning UI)
  S9  THE OWNER'S BANNER BUG: PingPoster WhatsApp-style notification -> real
      draft banner -> shade tap "Regenerate" -> IMMEDIATE "Regenerating..."
      working card (P22 fix; can never appear on vc920) -> fresh draft card.
  S10 Diagnostics screen: mock key must NOT appear in the rendered report
      (P21 redaction, on-device)
  S11 Voice "Reset all to defaults" -> dial subs return to "(default)"

Every phase appends PASS/FAIL/SKIP (+ evidence filenames) to
evidence/ui/results.md and saves PNG screenshots + uiautomator XML dumps into
evidence/ui/. A FAIL exits non-zero; SKIP never masquerades as PASS.
"""

import json
import os
import re
import shlex
import socket
import ssl
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EV = sys.argv[1] if len(sys.argv) > 1 else "evidence"
Udir = os.path.join(EV, "ui")
os.makedirs(Udir, exist_ok=True)
RESULTS = os.path.join(Udir, "results.md")
PKG = "com.replymate.app"
HOME_MARKER = PKG + ":id/settings_btn"   # resource-id present on Home

_results = []
_shot_n = [0]

def rec(status, sid, detail, note=""):
    line = "%s %s — %s%s" % (status, sid, detail, (" — " + note) if note else "")
    _results.append(line)
    with open(RESULTS, "a") as f:
        f.write(line + "\n")
    print(line, flush=True)

def sh(cmd, timeout=40):
    return subprocess.run(["adb", "shell", cmd], capture_output=True,
                          text=True, timeout=timeout).stdout

def sh_ok(cmd, timeout=40):
    subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)

def dev_out(args, timeout=40, binary=False):
    return subprocess.run(["adb"] + args, capture_output=True,
                          timeout=timeout).stdout if binary else \
        subprocess.run(["adb"] + args, capture_output=True, text=True,
                       timeout=timeout).stdout

# ---------------------------------------------------------------- uiautomator

class Node:
    def __init__(self, el):
        self.text = el.get("text") or ""
        self.desc = el.get("content-desc") or ""
        self.rid = el.get("resource-id") or ""
        self.cls = el.get("class") or ""
        self.checked = el.get("checked") == "true"
        b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds") or "")
        self.bounds = tuple(int(g) for g in b.groups()) if b else (0, 0, 0, 0)

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return ((x1 + x2) // 2, (y1 + y2) // 2)

def dump(retries=4):
    for i in range(retries):
        try:
            sh("uiautomator dump /sdcard/_u.xml", timeout=30)
            xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/_u.xml"],
                                 capture_output=True, timeout=30).stdout
            root = ET.fromstring(xml.decode("utf-8", "replace"))
            return [Node(el) for el in root.iter("node")], \
                xml.decode("utf-8", "replace")
        except Exception:
            time.sleep(1.5)
    return [], ""

def find(nodes, sub, exact=False, ci=False, rid=None):
    for n in nodes:
        if rid and n.rid == rid:
            return n
        if not rid:
            for field in (n.text, n.desc):
                hay = field.lower() if ci else field
                needle = sub.lower() if ci else sub
                if (exact and hay == needle) or (not exact and needle in hay
                                                 and field.strip()):
                    return n
    return None

def wait_for(sub, timeout=30, **kw):
    t0 = time.time()
    while time.time() - t0 < timeout:
        nodes, xml = dump()
        n = find(nodes, sub, **kw)
        if n is not None:
            return n, xml
        time.sleep(1.2)
    return None, xml

def tap_node(n):
    x, y = n.center
    sh_ok("input tap %d %d" % (x, y))
    time.sleep(0.8)

def click_text(sub, timeout=12, **kw):
    n, _ = wait_for(sub, timeout=timeout, **kw)
    if n is None:
        return False
    tap_node(n)
    return True

def click_id(rid_suffix, timeout=12):
    n, _ = wait_for(rid_suffix, timeout=timeout, rid=PKG + ":id/" + rid_suffix)
    if n is None:
        return False
    tap_node(n)
    return True

def type_text(text):
    esc = text.replace(" ", "%s").replace("&", "\\&").replace("'", "\\'")
    sh_ok("input text '%s'" % esc)
    time.sleep(0.6)

def field_by_hint(hint, timeout=12):
    """Ui.field EditText: hint text renders as the EditText's text until typed."""
    n, _ = wait_for(hint, timeout=timeout)
    if n is None:
        return None
    tap_node(n)
    return n

def scroll_until(sub, max_sw=14, **kw):
    nodes, xml = dump()
    n = find(nodes, sub, **kw)
    swipes = 0
    while n is None and swipes < max_sw:
        sh_ok("input swipe 540 1450 540 650 320")
        time.sleep(0.9)
        nodes, xml = dump()
        n = find(nodes, sub, **kw)
        swipes += 1
    return n, xml

def shot(name, xml=None):
    _shot_n[0] += 1
    fn = "%02d-%s" % (_shot_n[0], name)
    png = dev_out(["exec-out", "screencap", "-p"], timeout=40, binary=True)
    with open(os.path.join(Udir, fn + ".png"), "wb") as f:
        f.write(png)
    if xml is None:
        _, xml = dump()
    with open(os.path.join(Udir, fn + ".xml"), "w") as f:
        f.write(xml)
    return fn

def force_stop():
    sh_ok("am force-stop " + PKG)
    time.sleep(1.0)

def launch():
    sh_ok("am start -n %s/.ui.SplashActivity" % PKG)

def go_home_from_onboarding(tag):
    """Skip onboarding if shown; land on Home either way."""
    n, _ = wait_for("do this later", timeout=4)
    if n is not None:
        tap_node(n)
    n, xml = wait_for("settings_btn", timeout=20,
                      rid=PKG + ":id/settings_btn")
    return n is not None, xml

# ------------------------------------------------------------------ mock LLM

MOCK_DIR = os.path.join(Udir, "mock")
os.makedirs(MOCK_DIR, exist_ok=True)
REQ_LOG = os.path.join(MOCK_DIR, "requests.jsonl")
_tls_ok = False
_req_n = [0]

_fail = [False]                    # S9b: force the mock provider down

REPLY_VARIANTS = [
    "omo say less, 4pm still works for me",
    "lol noted, we move",
    "bet — see you then",
]

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.endswith("/models"):
            self._send(200, {"object": "list", "data": [
                {"id": "mock-chat-1", "object": "model"}]})
        else:
            self._send(404, {"error": {"message": "unknown path " + self.path}})

    def do_POST(self):
        try:
            ln = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(ln)
            body = json.loads(raw.decode("utf-8", "replace"))
        except Exception as e:
            self._send(400, {"error": {"message": "bad json: %s" % e}})
            return
        _req_n[0] += 1
        with open(REQ_LOG, "a") as f:
            f.write(json.dumps({"ts": time.time(), "seq": _req_n[0],
                                "path": self.path, "body": body}) + "\n")
        if _fail[0]:
            # S9b: honest provider outage, recorded like any other request
            self._send(503, {"error": {"message": "mock outage (harness-forced)",
                                       "type": "server_error"}})
            return
        blob = json.dumps(body)
        # the provider-editor contract probe ("Reply with exactly: RM-OK")
        if "RM-OK" in blob:
            texts = ["RM-OK"]
        else:
            n = body.get("n") or 1
            texts = [REPLY_VARIANTS[(_req_n[0] + i) % len(REPLY_VARIANTS)]
                     for i in range(int(n))]
        self._send(200, {
            "id": "chatcmpl-mock-%d" % _req_n[0],
            "object": "chat.completion",
            "choices": [
                {"index": i, "message": {"role": "assistant", "content": t},
                 "finish_reason": "stop"} for i, t in enumerate(texts)],
            "usage": {"prompt_tokens": 321, "completion_tokens": 12,
                      "total_tokens": 333}})

def captured_systems():
    out = []
    if not os.path.exists(REQ_LOG):
        return out
    with open(REQ_LOG) as f:
        for line in f:
            try:
                j = json.loads(line)
                for m in j["body"].get("messages", []):
                    if m.get("role") == "system":
                        out.append((j["seq"], m.get("content", "")))
            except Exception:
                pass
    return out

def start_mock():
    global _tls_ok
    cadir = MOCK_DIR
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", cadir + "/ca.key", "-out", cadir + "/ca.pem",
        "-days", "2", "-subj", "/CN=ReplyMateHarnessCA"],
        capture_output=True, check=True)
    san = subprocess.run([
        "openssl", "req", "-newkey", "rsa:2048", "-nodes",
        "-keyout", cadir + "/leaf.key", "-out", cadir + "/leaf.csr",
        "-subj", "/CN=10.0.2.2",
        "-addext", "subjectAltName=IP:10.0.2.2"],
        capture_output=True, check=True)
    ext = os.path.join(cadir, "ext.cnf")
    with open(ext, "w") as f:
        f.write("subjectAltName=IP:10.0.2.2\n")
    subprocess.run([
        "openssl", "x509", "-req", "-in", cadir + "/leaf.csr",
        "-CA", cadir + "/ca.pem", "-CAkey", cadir + "/ca.key",
        "-CAcreateserial", "-out", cadir + "/leaf.pem", "-days", "2",
        "-extfile", ext], capture_output=True, check=True)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cadir + "/leaf.pem", cadir + "/leaf.key")
    httpd = ThreadingHTTPServer(("0.0.0.0", 8443), Handler)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    # trust the CA inside the SYSTEM store (userdebug/root path; honest SKIP
    # otherwise — the owner forbids pretending)
    with open(cadir + "/ca.pem") as f:
        pem = f.read()
    ok = trust_system_ca(pem)
    _tls_ok = ok
    return ok

def trust_system_ca(pem_text):
    # one reboot cycle is unavoidable: disable-verity needs it, and the
    # system cert store is scanned at zygote/class-loader init.
    tag = dev_out(["shell", "getprop", "ro.build.type"]).strip()
    print("S6-tls setup: ro.build.type=" + tag, flush=True)
    def step(cmd, tout=60):
        r = subprocess.run(["adb"] + shlex.split(cmd), capture_output=True,
                           text=True, timeout=tout)
        return (r.stdout or "") + (r.stderr or "")
    o = step("root")
    if "cannot run as root" in o and "production" not in tag:
        pass
    o2 = step("shell id")
    if "uid=0" not in o2:
        rec("SKIP", "S6-tls", "adb root unavailable on this image",
            "provider scenarios cannot run without TLS trust or cleartext")
        return False
    step("disable-verity", tout=60)
    step("reboot", tout=15)
    step("wait-for-device", tout=180)
    time.sleep(20)                       # boot settled (keyguard/mounts)
    step("root")
    time.sleep(2)
    o3 = step("remount", tout=60)
    if "remount succeeded" not in o3.lower() and "verity" in o3.lower():
        pass
    key = hashlib_subject(pem_text)
    local = os.path.join(MOCK_DIR, key + ".0")
    with open(local, "w") as f:
        f.write(pem_text)
    step("push %s /system/etc/security/cacerts/%s.0" % (local, key))
    o4 = step("shell ls /system/etc/security/cacerts/%s.0" % key)
    if key + ".0" not in o4:
        rec("SKIP", "S6-tls", "CA push into system store failed",
            o4.strip()[:120])
        return False
    step("shell chmod 644 /system/etc/security/cacerts/%s.0" % key)
    step("reboot", tout=15)
    step("wait-for-device", tout=180)
    time.sleep(20)
    return True

def hashlib_subject(pem_text):
    # Java system-store filename: openssl-style subject-hash, old (pre-1.0)
    # algorithm for MD5 — Android uses X509_NAME_hash_old.
    p = subprocess.run(["openssl", "x509", "-subject_hash_old", "-noout"],
                       input=pem_text, capture_output=True, text=True,
                       check=True)
    return p.stdout.strip()

# ------------------------------------------------------------------ scenarios

def s1_launch():
    launch()
    ok, xml = go_home_from_onboarding("S1")
    fn = shot("s1-home", xml)
    rec("PASS" if ok else "FAIL", "S1",
        "cold launch reached Home", "evidence %s.png" % fn)

def s2_voice_open():
    ok1 = click_id("settings_btn")
    ok2 = click_text("My voice (global style)", timeout=15)
    n, xml = wait_for("Reply length", timeout=15)
    ok3 = n is not None and find(nodes_of(xml), "Emoji use") is not None
    fn = shot("s2-voice", xml)
    rec("PASS" if (ok1 and ok2 and ok3) else "FAIL", "S2",
        "Voice screen renders with dials",
        "open-steps ok=%s/%s, dials seen=%s, %s.png" % (ok1, ok2, ok3, fn))

def nodes_of(xml):
    try:
        return [Node(el) for el in ET.fromstring(xml).iter("node")]
    except Exception:
        return []

def s3_set_length():
    if not click_text("Reply length"):
        rec("FAIL", "S3", "Reply length row not found")
        return
    if not click_text("Fuller, more detailed replies"):
        rec("FAIL", "S3", "Detailed option not found in picker")
        return
    n, xml = wait_for("current: detailed", timeout=10)
    fn = shot("s3-length-detailed", xml)
    rec("PASS" if n is not None else "FAIL", "S3",
        "Reply length -> Detailed applied live", fn + ".png")

def s4_set_emoji_off():
    if not click_text("Emoji use"):
        rec("FAIL", "S4", "Emoji use row not found")
        return
    if not click_text("Off", exact=True, ci=True, timeout=8):
        rec("FAIL", "S4", "Off option not found in picker")
        return
    n, xml = wait_for("current: off", timeout=10)
    fn = shot("s4-emoji-off", xml)
    rec("PASS" if n is not None else "FAIL", "S4",
        "Emoji use -> Off applied live", fn + ".png")

def s5_persist_after_relaunch():
    force_stop()
    launch()
    ok, _ = go_home_from_onboarding("S5")
    click_id("settings_btn")
    click_text("My voice (global style)", timeout=15)
    nodes, xml = dump()
    a = find(nodes, "current: detailed") is not None
    b = find(nodes, "current: off") is not None
    fn = shot("s5-relaunch-persistence", xml)
    rec("PASS" if (ok and a and b) else "FAIL", "S5",
        "force-stop + relaunch persistence",
        "home=%s length-detailed=%s emoji-off=%s (%s.png)" % (ok, a, b, fn))

def s6_provider_setup():
    if not _tls_ok:
        rec("SKIP", "S6", "mock provider via real TLS",
            "system CA trust unavailable — see S6-tls")
        return False
    force_stop()
    launch()
    go_home_from_onboarding("S6")
    click_id("settings_btn")
    if not click_text("AI providers", timeout=12):
        rec("FAIL", "S6", "providers row not found")
        return False
    if not click_text("Add provider", timeout=12):
        rec("FAIL", "S6", "Add provider row not found")
        return False
    if not click_text("Other (OpenAI-compatible)", timeout=12):
        rec("FAIL", "S6", "openai-compat chip not found")
        return False
    f1 = field_by_hint("e.g. My OpenRouter")
    if f1 is None:
        rec("FAIL", "S6", "label field not found"); return False
    type_text("RM Mock")
    f2 = field_by_hint("https://…") or field_by_hint("https://")
    if f2 is None:
        rec("FAIL", "S6", "base-url field not found"); return False
    type_text("https://10.0.2.2:8443/v1")
    f3 = field_by_hint("pick below or type a model id")
    if f3 is None:
        rec("FAIL", "S6", "model field not found"); return False
    type_text("mock-chat-1")
    # key field: the only remaining EditText with a password/secret hint varies
    # by build; find it as the next empty EditText after the model field.
    nodes, _ = dump()
    edits = [n for n in nodes if "EditText" in n.cls]
    key_node = None
    for n in edits:
        if not n.text or n.text == n.desc or "…" in n.text or \
                "type" in n.text.lower() or "key" in n.text.lower():
            key_node = n
    if key_node is not None:
        tap_node(key_node)
        type_text("rm-mock-key")
    if not click_text("Save & activate", timeout=10):
        rec("FAIL", "S6", "save button not found"); return False
    n, xml = wait_for("RM Mock", timeout=12)
    fn = shot("s6-provider-saved", xml)
    ok = n is not None
    rec("PASS" if ok else "FAIL", "S6",
        "mock provider configured through the real provider editor", fn + ".png")
    return ok

def s7_preview_pipeline():
    """The P22 money proof: dial -> real provider request, captured mock-side."""
    # position: after S6 we sit on the providers list
    sh_ok("input keyevent 4")                  # back to settings
    time.sleep(1)
    click_text("My voice (global style)", timeout=12)
    # 7a: length = very short ("short" level 0)
    click_text("Reply length", timeout=10)
    if not click_text("One short sentence"):
        rec("FAIL", "S7a", "short option not found")
        return
    wait_for("current: short", timeout=8)
    before = len(captured_systems())
    click_text("Preview replies", timeout=10)
    n, xml = wait_for("1)", timeout=45)
    if n is None and find(nodes_of(xml), "Preview failed") is not None:
        rec("FAIL", "S7a", "preview failed at provider", xml[-160:])
        return
    sys_prompts = captured_systems()[before:]
    clause_ok = any("usually one short sentence" in s for _, s in sys_prompts)
    fn = shot("s7a-preview-short", xml)
    rec("PASS" if (n is not None and clause_ok) else "FAIL", "S7a",
        "length=short -> variants rendered AND real request carried the "
        "one-sentence clause",
        "variants=%s clause=%s reqs=%d (%s.png)" % (n is not None, clause_ok,
                                                    len(sys_prompts), fn))
    # 7b: length = detailed -> NO short clause on the wire (the P22 fix)
    click_text("Reply length", timeout=10)
    click_text("Fuller, more detailed replies", timeout=8)
    wait_for("current: detailed", timeout=8)
    before = len(captured_systems())
    click_text("Preview replies", timeout=10)    # label survives the rename
    n2, xml2 = wait_for("1)", timeout=45)
    prompts2 = captured_systems()[before:]
    no_short = all("1–3 short sentences" not in s and "one short sentence"
                   not in s for _, s in prompts2)
    detailed_phrase = any("allow fuller, more detailed replies"
                          in s for _, s in prompts2)
    fn2 = shot("s7b-preview-detailed", xml2)
    rec("PASS" if (n2 is not None and prompts2 and no_short and detailed_phrase)
        else "FAIL", "S7b",
        "length=detailed -> NO short-reply clause in the REAL request "
        "(P22 Rules conflict fix), detailed voice phrase present",
        "reqs=%d noShortClause=%s detailedPhrase=%s (%s.png)"
        % (len(prompts2), no_short, detailed_phrase, fn2))

def s8_writing_samples():
    press_home(); time.sleep(0.8)
    sh_ok("input keyevent 4"); time.sleep(0.8)   # -> settings
    sh_ok("input keyevent 4"); time.sleep(0.8)   # -> home
    if not click_id("new_btn", timeout=10):
        rec("FAIL", "S8", "home new-contact button not found")
        return
    if field_by_hint("e.g. Amara") is None:
        rec("FAIL", "S8", "name field not found"); return
    type_text("Emu Ada")
    if not click_text("Create contact", timeout=10):
        rec("FAIL", "S8", "create button not found"); return
    time.sleep(1.5)
    # reopen the contact from Home
    launch(); go_home_from_onboarding("S8")
    if not click_text("Emu Ada", timeout=12):
        rec("FAIL", "S8", "created contact not listed"); return
    click_id("edit_btn", timeout=10)
    n, _ = scroll_until("Writing samples")
    if n is None:
        rec("FAIL", "S8", "Writing samples row never appeared"); return
    tap_node(n)
    time.sleep(1.0)
    nodes, _ = dump()
    edit = None
    for m in nodes:
        if "EditText" in m.cls:
            edit = m
    if edit is None:
        rec("FAIL", "S8", "sample dialog field missing"); return
    tap_node(edit)
    type_text("omw now gimme 5")
    click_text("Save", exact=True, timeout=8)
    n2, _ = scroll_until("1 saved", max_sw=6)
    ok_added = n2 is not None
    shot("s8-sample-added")
    # persistence across force-stop + relaunch
    force_stop(); launch(); go_home_from_onboarding("S8b")
    click_text("Emu Ada", timeout=12)
    click_id("edit_btn", timeout=10)
    n3, _ = scroll_until("1 saved", max_sw=8)
    ok_persist = n3 is not None
    # clear
    n4, _ = scroll_until("Clear samples", max_sw=8)
    if n4 is not None:
        tap_node(n4)
        time.sleep(1.0)
    n5, _ = scroll_until("0 saved", max_sw=6)
    ok_clear = n5 is not None
    shot("s8-samples-cleared")
    rec("PASS" if (ok_added and ok_persist and ok_clear) else "FAIL", "S8",
        "writing samples add / persist-across-relaunch / clear",
        "added=%s persist=%s cleared=%s" % (ok_added, ok_persist, ok_clear))

def s9_banner_regen():
    """The owner's physical-device bug, replayed on a clean emulator."""
    force_stop(); launch(); go_home_from_onboarding("S9")
    # listener access (compact ladder — the full ladder lives in
    # run_scenarios.sh; duplicates by design so this suite stands alone)
    nls = PKG + "/com.replymate.app.listener.RmNotificationListener"
    sh_ok('settings put secure enabled_notification_listeners "%s"' % nls)
    time.sleep(2)
    sh_ok("cmd notification allow_listener %s" % nls)
    time.sleep(3)
    # insurance against background-restriction gates on the pipeline
    sh_ok("dumpsys deviceidle whitelist +" + PKG)
    # a WhatsApp-style ping -> ingestion -> (mock provider) -> draft banner
    reqs_before = _req_n[0]
    sh_ok("am start -n com.whatsapp/com.pingposter.MainActivity "
          "--es shape wa-11 --ei nid 4242")
    time.sleep(2)
    press_home()
    t0 = time.time()
    banner = False
    while time.time() - t0 < 90:
        d = dev_out(["shell", "dumpsys", "notification", "--noredact"],
                    timeout=30)
        if "Draft reply for" in d:
            banner = True
            break
        time.sleep(3)
    if not banner:
        rec("FAIL", "S9",
            "no draft banner within 90s (pipeline trace in evidence)",
            "provider=%s mockReqs(before)=%d" % (_tls_ok, reqs_before))
        shot("s9-no-banner")
        return
    shot("s9a-banner-posted")
    gen_reqs = _req_n[0] - reqs_before
    sh_ok("cmd statusbar expand-notifications")
    time.sleep(2)
    n, _ = wait_for("Regenerate", timeout=12)
    if n is None:
        # the card may need one tap to expand its actions
        click_text("Draft reply for", timeout=6)
        n, _ = wait_for("Regenerate", timeout=8)
    if n is None:
        rec("FAIL", "S9", "banner posted but Regenerate action not visible")
        shot("s9b-no-action")
        return
    tap_node(n)                                   # THE TAP the owner made
    w, xml_w = wait_for("Regenerating", timeout=15, ci=True)
    fn = shot("s9c-working-card", xml_w)
    if w is None:
        rec("FAIL", "S9",
            "tap gave NO immediate feedback — the reported defect reproduced "
            "(unexpected on this build)", fn + ".png")
        return
    # the working card must resolve to a fresh draft card
    t1 = time.time()
    fresh = False
    while time.time() - t1 < 60:
        nodes, xml2 = dump()
        if find(nodes, "Draft reply for") is not None:
            fresh = True
            break
        time.sleep(2.5)
    fn2 = shot("s9d-fresh-card", xml2 if fresh else None)
    regen_called = _req_n[0] > reqs_before + gen_reqs
    rec("PASS" if (fresh and regen_called) else "FAIL", "S9",
        "banner Regenerate: instant working card + fresh draft + REAL "
        "provider round-trip",
        "workingCard=%s freshCard=%s mockRoundTrip=%s (%s.png,%s.png)"
        % (w is not None, fresh, regen_called, fn, fn2))

def s9b_banner_regen_failure_feedback():
    """P22-fix companion: provider DOWN at tap time must never look like a dead
    button — the working card resolves to an honest 'Couldn't regenerate' state.
    On the pre-fix candidate (6d03767) this scenario is EXPECTED-RED: that red IS
    the emulator reproduction of the owner's physical-device report."""
    _fail[0] = True
    try:
        n, _ = wait_for("Regenerate", timeout=10)
        if n is None:
            click_text("Draft reply for", timeout=6)
            n, _ = wait_for("Regenerate", timeout=8)
        if n is None:
            rec("FAIL", "S9b", "fresh card's Regenerate action not visible")
            shot("s9b-no-action")
            return
        tap_node(n)
        t0 = time.time()
        honest = False
        while time.time() - t0 < 75:
            d = dev_out(["shell", "dumpsys", "notification", "--noredact"],
                        timeout=30)
            if "Couldn't regenerate" in d:
                honest = True
                break
            time.sleep(3)
        fn = shot("s9b-honest-failure")
        rec("PASS" if honest else "FAIL", "S9b",
            "provider down + Regenerate tap -> honest outcome card, never a "
            "dead button (the owner's exact complaint)",
            "honestCard=%s (%s.png)" % (honest, fn))
    finally:
        _fail[0] = False


def press_home():
    sh_ok("input keyevent 3")
    time.sleep(1)

def s10_diagnostics_redaction():
    force_stop(); launch(); go_home_from_onboarding("S10")
    click_id("settings_btn")
    n, _ = scroll_until("Diagnostics", max_sw=10)
    if n is None or not click_text("Diagnostics", timeout=6):
        rec("FAIL", "S10", "diagnostics row not found")
        return
    time.sleep(6)                                 # report builds off-thread
    nodes, xml = dump()
    leaked = any("rm-mock-key" in m.text or "rm-mock-key" in m.desc
                 for m in nodes)
    fn = shot("s10-diagnostics", xml)
    rec("PASS" if not leaked else "FAIL", "S10",
        "Diagnostics report renders; provider key redacted on-device",
        "leaked=%s (%s.png)" % (leaked, fn))

def s11_reset_defaults():
    sh_ok("input keyevent 4"); time.sleep(0.8)    # -> settings
    click_text("My voice (global style)", timeout=12)
    if not click_text("Reset all to defaults", timeout=10):
        rec("FAIL", "S11", "reset button not found")
        return
    nodes, xml = dump()
    ok = find(nodes, "(default)", ci=True) is not None
    shot("s11-reset", xml)
    # relaunch persistence of the reset too
    force_stop(); launch(); go_home_from_onboarding("S11")
    click_id("settings_btn")
    click_text("My voice (global style)", timeout=12)
    nodes2, xml2 = dump()
    ok2 = find(nodes2, "(default)", ci=True) is not None
    shot("s11-reset-relaunch", xml2)
    rec("PASS" if (ok and ok2) else "FAIL", "S11",
        "Reset all to defaults works and persists across relaunch",
        "immediate=%s persisted=%s" % (ok, ok2))

def main():
    with open(RESULTS, "a") as f:
        f.write("# P22 UI-emulator evidence (API 30, clean AOSP emulator)\n"
                "app-under-test: %s\n\n" % os.environ.get("RM_APP_REF", "local"))
    # shut the keyguard + any chatter
    sh_ok("wm dismiss-keyguard")
    sh_ok("input keyevent 82")
    if os.environ.get("RM_API_LEVEL", "30").isdigit() and \
            int(os.environ.get("RM_API_LEVEL", "30")) >= 33:
        sh_ok("pm grant %s android.permission.POST_NOTIFICATIONS" % PKG)
    s1_launch()
    s2_voice_open()
    s3_set_length()
    s4_set_emoji_off()
    s5_persist_after_relaunch()
    tls = False
    try:
        tls = start_mock()
    except Exception as e:
        rec("SKIP", "S6-tls", "mock TLS setup raised", str(e)[:120])
    if tls:
        time.sleep(6)                             # post-reboot settle
        if s6_provider_setup():
            s7_preview_pipeline()
            s9_banner_regen()
            s9b_banner_regen_failure_feedback()
        else:
            rec("SKIP", "S7/S9", "provider-dependent phases",
                "provider setup failed")
    else:
        rec("SKIP", "S6/S7/S9", "provider-dependent phases",
            "no TLS trust on this image")
    s8_writing_samples()
    s10_diagnostics_redaction()
    s11_reset_defaults()
    fails = sum(1 for r in _results if r.startswith("FAIL"))
    skips = sum(1 for r in _results if r.startswith("SKIP"))
    with open(RESULTS, "a") as f:
        f.write("\nFAIL=%d SKIP=%d TOTAL=%d\n" % (fails, skips, len(_results)))
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
