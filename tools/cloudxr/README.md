# Linux Field → Apple Vision Pro through CloudXR

Host for Apple's Foveated Streaming management protocol and NVIDIA CloudXR Runtime.
It selects OpenXR only in its child Field process; WiVRn and global runtime registration
are untouched. The optional [visionOS client](../../native/field-cloudxr/README.md) ships
as source without a signing identity or proprietary SDK binaries.

## Verified configuration

Real-device tests on 2026-11-12: CachyOS x86-64, RTX 4090, driver 620.70.06,
CloudXR **6.2.3**, Xcode **27.2**, Vision Pro **visionOS 27.3 beta**. CachyOS is outside
NVIDIA's Ubuntu support target. The user confirmed the cube stream, head motion, optional
Vulkan IDE interaction and native WKWebView gesture interaction. Field receives 26 joints
per hand plus aim/pinch/grasp actions. Clicks, fist movement and two-fist scaling work;
rotation and sensitivity need refinement.

Field renders **3800 × 3040 per eye** in format 97 (`R16G16B16A16_SFLOAT`). With
`runtime-foveation: true`, the runtime renders unwarped space and produces 1200 × 960 warped
transport images. Without that setting, the small transport size leaked into the application's
recommended render size. Gaze-following behavior and end-to-end stream bit depth were not
separately proved. Linux audio is unavailable in this SDK configuration.

## Build the helper

Download CloudXR Runtime separately under NVIDIA's license. Tested install:
`~/.local/opt/cloudxr-6.2.3`. Archive SHA-256:
`3b74dabe60e91606a651b01b6dec9c6c28e816c1211824d1b85c36084bb8d175`.
Requirements: Python 3.10+, C++17, nlohmann/json headers, OpenSSL CLI, qrencode,
avahi-publish-service, lsof and systemd user services for the managed example.

```sh
mkdir -p ~/.cache/field-cloudxr
c++ -std=c++17 -O2 tools/cloudxr/runtime_helper.cpp \
  -I ~/.local/opt/cloudxr-6.2.3/include -ldl \
  -o ~/.cache/field-cloudxr/runtime-helper
```

The native helper uses a separate address space and `RTLD_DEEPBIND` to avoid an observed
TLS-signing crash from mixing SDK-bundled and Python/system OpenSSL. Diagnostics go to
private logs; a separate pipe carries API replies.

## Prepare the starter once

Create `~/.local/state/field-cloudxr/workspace`, then run a preparation Field on that workspace
with `./field.sh --workspace <directory>`. On its server, import into a new empty document
(adapt the port; the preparation server need not use OpenXR):

```sh
python templates/cloudxr_one/build_transcript.py
uv run --group backdoor python tools/field_backdoor.py --json --port 9492 --attach-existing \
  --command 'open cloudxr_one --create' \
  --command 'macro write cloudxr_one_create --file /tmp/cloudxr-one-transcripts/create.json' \
  --command 'macro run cloudxr_one_create' \
  --command 'macro write cloudxr_one_launch --file /tmp/cloudxr-one-transcripts/launch.json' \
  --command 'macro write cloudxr_one_stop --file /tmp/cloudxr-one-transcripts/stop.json'
```

Replay create only into a new empty document. Stop the preparation Field before streaming;
the CloudXR launcher needs a fresh JVM.

## Start on connection

Use a bundle ID registered to your Apple team in Xcode and `--bundle-id`. The neutral source
default is `org.field4.CloudXRClient`.

```sh
python tools/cloudxr/host.py --bundle-id org.field4.CloudXRClient \
  --field-unit field-cloudxr-field.service --field-port 9492 \
  --on-client-connected \
  systemd-run --user --collect --unit=field-cloudxr-field \
  --property=WorkingDirectory="$PWD" \
  --property=KillMode=mixed --property=KillSignal=SIGINT \
  python "$PWD/tools/cloudxr/start_field.py" --lan-editor
```

Host options precede `--on-client-connected`, which consumes the remaining argv. A real
connection starts the private Field server on 9492. An existing listener is refused because
OpenXR/Vulkan selection belongs to a fresh JVM. The default is **scene only**, a red 40 cm
cube 1.5 m ahead on black. `--ide` adds the optional Vulkan IDE panel. `--desktop` tests stereo
without a headset. `--lan-editor` binds HTTPS to all interfaces; the HTTP mirror stays local.
The editor URL is `https://<linux-host>:9492/index.html?file=cloudxr_one`.

## Pairing and LAN

Management TCP 55000 is advertised through Bonjour with the matching Application-Identifier.
The QR page is loopback HTTP 55001. Display it on a Mac through SSH:

```sh
ssh -N -L 55001:127.0.0.1:55001 user@linux-host
# Open http://127.0.0.1:55001/ on the Mac; scan the requested pairing QR in Vision Pro.
```

Private identity lives in `~/.local/state/field-cloudxr`. Credentials never appear in argv
or logs. The system provider rejects certificates lasting over 47 days; the host issues
30-day certificates, renewing on startup below three days remaining. Renewal requires
scanning the new fingerprint; server ID/device token stay stable. Restart before expiry.

Secure signaling is TCP 48322. Tested media UDP ports: 47998, 47999, 48005, 48008, 48012.
Discovery can work while a firewall blocks the connection. Example UFW rules, adapted to
your interface/subnet:

```sh
sudo ufw allow in on eno1 from 10.0.0.0/24 to any port 55000,48322 proto tcp
sudo ufw allow in on eno1 from 10.0.0.0/24 to any port 47998,47999,48005,48008,48012 proto udp
sudo ufw allow in on eno1 from 10.0.0.0/24 to any port 9492 proto tcp
```

Use trusted HTTPS/proxying or explicitly trust your development certificate for laptop
editing. Never publish identity.json, private keys, QR images or raw device logs. These
tools do not configure a public tunnel.

## Lifetime, input and checks

PAUSED and management TCP reconnection retain runtime and Field. Full DISCONNECTED first
signals the managed Field listener with SIGINT, verifies save/shutdown, stops its unit and
then retires the runtime. A cgroup check protects unrelated port owners. Host shutdown uses
the same ordering. Native host teardown has occasionally stalled and required the service
timeout; not every disconnect case is resolved. Logs: `~/.local/state/field-cloudxr/logs`.
After a crash, verify cloudxr.pid is dead and ipc_cloudxr has no owner before moving stale
markers aside; never remove live runtime IPC. A failed scene macro leaves Field open for
repair: inspect scene-launch.jsonl and rerun the macro after fixing its first error.

Edit using a laptop browser in Mac Virtual Display. On the tested beta, connecting the
native streaming client stops ordinary typing throughout its UI, including WKWebView,
a plain TextEditor and Apple's message field. Direct Bluetooth and forwarded keyboards
both fail; GCKeyboard transitions stop too. Local immersion without streaming works.
Completion suggestions can insert text. [Reproduction notes](diagnostics/keyboard-repro/README.md)
record the tests; bug ownership is unproven. Keyboard editing in this client is not usable.

The starter announces one `XR_NV_opaque_data_channel` channel:
`f4e1d000-0000-4000-8000-000000000001`. Channel 1 discovery and preset Hello! echo were
confirmed. It polls at 10 Hz, at most four messages per poll, limited to 1000 bytes each.
`OpenXRHmdTarget.createDataChannel(UUID)` owns channels through target cleanup. A working
channel cannot forward keyboard events the app no longer receives.

```sh
python tools/cloudxr/test_host.py                # framing, certificate and lifecycle tests
python tools/cloudxr/test_host.py --integration # real SDK session: UNOCCUPIED host only
```

The integration peer starts/stops a real runtime and can interrupt a headset. It is not a
device test. Kotlin compilation and C ABI checks passed; hand controls and echo were
confirmed separately on the real device.

## Primary references

- [Apple client sample](https://developer.apple.com/documentation/foveatedstreaming/creating-a-foveated-streaming-client-on-visionos)
- [Apple management protocol](https://developer.apple.com/documentation/foveatedstreaming/establishing-foveated-streaming-sessions-with-apple-vision-pro)
- [NVIDIA requirements](https://docs.nvidia.com/cloudxr-sdk/latest/requirement/runtime_req.html)
- [NVIDIA service API](https://docs.nvidia.com/cloudxr-sdk/latest/usr_guide/cloudxr_runtime/runtime_mgmt_api.html)
- [NVIDIA channel](https://docs.nvidia.com/cloudxr-sdk/latest/usr_guide/cloudxr_runtime/opaque_data_channel.html)
- [NVIDIA feature matrix](https://docs.nvidia.com/cloudxr-sdk/latest/release_notes/release_notes.html)
