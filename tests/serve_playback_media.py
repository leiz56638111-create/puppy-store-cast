# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic HLS VOD, a sliding DVR window and an unknown-length TS stream."""
from datetime import datetime,timezone
from functools import partial
from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer
import time

def server(directory,port=18099):
    clock=time.monotonic()
    start=time.time()-120
    class Handler(SimpleHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            if self.path=='/live.m3u8':
                sequence=20+int((time.monotonic()-clock)/2)
                sequence=min(sequence,68)
                rows=['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:2',f'#EXT-X-MEDIA-SEQUENCE:{sequence}']
                for i in range(sequence,sequence+20):
                    instant=datetime.fromtimestamp(start+i*2,timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
                    rows.extend([f'#EXT-X-PROGRAM-DATE-TIME:{instant}','#EXTINF:2.0,',f'360/segment{i:03d}.ts'])
                data=('\n'.join(rows)+'\n').encode()
                self.send_response(200);self.send_header('Content-Type','application/vnd.apple.mpegurl');self.send_header('Cache-Control','no-cache');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            elif self.path=='/unseekable.ts':
                self.send_response(200);self.send_header('Content-Type','video/mp2t');self.send_header('Connection','close');self.end_headers()
                try:
                    for segment in sorted((directory/'360').glob('segment*.ts')):
                        self.wfile.write(segment.read_bytes());self.wfile.flush();time.sleep(.01)
                except (BrokenPipeError,ConnectionResetError):pass
                self.close_connection=True
            else:super().do_GET()
    return ThreadingHTTPServer(('127.0.0.1',port),partial(Handler,directory=str(directory)))
