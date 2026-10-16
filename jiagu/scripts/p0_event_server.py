"""Local-only regression receiver. Durable SQLite dedupe, explicit ACK and fault modes."""
import argparse
import json
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--directory', default='build/p0/server')
args = parser.parse_args()
root = Path(args.directory)
root.mkdir(parents=True, exist_ok=True)
lock = threading.Lock()
mode = 'accept'
db = sqlite3.connect(root / 'received.db', check_same_thread=False)
db.executescript('CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, payload TEXT);'
                 'CREATE TABLE IF NOT EXISTS attempts(id TEXT, payload TEXT, result TEXT);')

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        with lock:
            rows = [json.loads(p) for (p,) in db.execute('SELECT payload FROM events')]
            attempts = list(db.execute('SELECT id,result FROM attempts'))
        self.reply(200, {'mode': mode, 'events': rows, 'attempts': attempts})

    def do_POST(self):
        global mode
        length = int(self.headers.get('Content-Length', '0'))
        if length > 8192:
            self.reply(413, {})
            return
        body = json.loads(self.rfile.read(length))
        if self.path == '/control':
            mode = body['mode']
            self.reply(200, {'mode': mode})
            return
        payload = json.dumps(body, sort_keys=True, separators=(',', ':'))
        identity = body['telemetryEventId']
        result = mode
        with lock:
            db.execute('INSERT INTO attempts VALUES(?,?,?)', (identity, payload, result))
            if mode in ('accept', 'ackdrop'):
                previous = db.execute('SELECT payload FROM events WHERE id=?', (identity,)).fetchone()
                if previous and previous[0] != payload:
                    db.commit()
                    self.reply(409, {'error': 'CONTENT_CONFLICT'})
                    return
                db.execute('INSERT OR IGNORE INTO events VALUES(?,?)', (identity, payload))
            db.commit()
        if mode == 'ackdrop':
            self.close_connection = True
        elif mode == 'offline':
            self.reply(503, {})
        elif mode == 'reject':
            self.reply(422, {})
        else:
            self.reply(200, {'telemetryEventId': identity})

print('P0 receiver ready on 127.0.0.1:18765', flush=True)
ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()
