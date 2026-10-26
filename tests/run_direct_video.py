# SPDX-License-Identifier: GPL-3.0-only
"""Reproducible synthetic AirPlay-video test; a dedicated Android emulator only.
Build :direct:assembleDebug :direct:assembleDebugAndroidTest before running.
Requires adb, ffmpeg and Python 3.12+. No Apple-device compatibility is implied.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, io, json, os, re, subprocess, tarfile, tempfile, threading

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='Dedicated running emulator, e.g. emulator-5560')
parser.add_argument('--adb', default='adb')
parser.add_argument('--ffmpeg', default='ffmpeg')
parser.add_argument('--output', default='verification/direct-video')
parser.add_argument('--test', help='Optional fully qualified instrumentation class or class#method')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
if not args.serial.startswith('emulator-'):
    raise SystemExit('Use a dedicated emulator; this runner must not install to a real user device.')
def adb(*cmd, timeout=60):
    return subprocess.check_output([args.adb, '-s', args.serial, *cmd], timeout=timeout)
assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Not an emulator'
apk = root/'direct/build/outputs/apk/debug/direct-debug.apk'
test_apk = root/'direct/build/outputs/apk/androidTest/debug/direct-debug-androidTest.apk'
for file in (apk, test_apk):
    adb('install', '-r', str(file))
spec = importlib.util.spec_from_file_location('fixtures', root/'tests/serve_playback_media.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
with tempfile.TemporaryDirectory(prefix='puppy-direct-media-') as temp:
    media = Path(temp)
    (media/'360').mkdir()
    base = [args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=30', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100', '-t', '40', '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '30', '-bf', '0', '-pix_fmt', 'yuv420p', '-c:a', 'aac']
    subprocess.run(base + ['-movflags', '+faststart', str(media/'video.mp4')], check=True)
    subprocess.run([args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(media/'video.mp4'), '-c', 'copy', '-f', 'hls', '-hls_time', '2', '-hls_list_size', '0', '-hls_segment_filename', str(media/'360/segment%03d.ts'), str(media/'360/index.m3u8')], check=True)
    server = fixture.server(media)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        adb('shell', 'am', 'force-stop', 'cn.local.livingroomcast.airplayvideo')
        adb('logcat', '-c')
        selection = ['-e', 'class', args.test] if args.test else []
        result = adb('shell', 'am', 'instrument', '-w', '-r', *selection, 'cn.local.livingroomcast.airplayvideo.test/androidx.test.runner.AndroidJUnitRunner', timeout=600).decode()
        (out/'android-results.txt').write_text(result)
        (out/'logcat.txt').write_bytes(adb('logcat', '-d'))
        count = re.search(r'OK \((\d+) tests?\)', result)
        if not count or 'FAILURES' in result:
            raise AssertionError('Test failed; see '+str(out/'android-results.txt'))
        raw = adb('exec-out', 'run-as', 'cn.local.livingroomcast.airplayvideo', 'tar', '-C', 'files/direct-tests', '-cf', '-', '.')
        screenshots = out/'screenshots'
        screenshots.mkdir(exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            archive.extractall(screenshots, filter='data')
        report = {'tests': int(count.group(1)), 'serial': args.serial, 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'real_apple_app_validation': False}
        (out/'summary.json').write_text(json.dumps(report, indent=2)+'\n')
        print(json.dumps(report), flush=True)
    finally:
        server.shutdown()
        server.server_close()
