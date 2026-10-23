"""Isolated WebDAV fixture. Only bind loopback; no real NAS data or credentials."""
import argparse,base64,hashlib,http.server,json,pathlib,urllib.parse,xml.sax.saxutils,threading,os,ssl,socket
parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--port',type=int,default=18791);parser.add_argument('--cert');parser.add_argument('--key');parser.add_argument('--latency-ms',type=float,default=0);parser.add_argument('--keep-alive',action='store_true');args=parser.parse_args()
ROOT=pathlib.Path(args.root).resolve();ROOT.mkdir(parents=True,exist_ok=True)
os.chmod(ROOT,0o700)
stats={'puts':0,'object_puts':0,'pack_puts':0,'bytes':0,'get_bytes':0,'unauthorized':0,'requests':0,'GET':0,'HEAD':0,'PUT':0,'MKCOL':0,'PROPFIND':0}
fault={'next':False,'transform':False,'bad_range':False}
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1' if args.keep_alive else 'HTTP/1.0'
    def setup(self):
        super().setup()
        if args.keep_alive:self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
    def log_message(self,*args):pass
    def reply(self,status,body=b'',**headers):
        self.send_response(status);self.send_header('Content-Length',str(len(body)))
        for k,v in headers.items():self.send_header(k,v)
        self.end_headers()
        if self.command!='HEAD':
            try:self.wfile.write(body)
            except (BrokenPipeError,ConnectionResetError):pass  # Expected when corruption tests cancel siblings.
    def resolve_path(self):
        if not self.path.startswith('/control/') and self.path!='/stats':
            import time
            stats['requests']+=1;stats[self.command]=stats.get(self.command,0)+1
            if args.latency_ms:time.sleep(args.latency_ms/1000)
        if self.headers.get('Authorization')!='Basic '+base64.b64encode(b'test:test').decode():stats['unauthorized']+=1;self.reply(401);return None
        value=urllib.parse.unquote(urllib.parse.urlparse(self.path).path).lstrip('/')
        path=(ROOT/value).resolve()
        if not path.is_relative_to(ROOT):self.reply(403);return None
        return path
    def do_MKCOL(self):
        p=self.resolve_path()
        if p is None:return
        if p.exists():self.reply(405)
        elif not p.parent.exists():self.reply(409)
        else:p.mkdir(mode=0o700);self.reply(201)
    def do_PUT(self):
        p=self.resolve_path()
        if p is None:return
        data=self.rfile.read(int(self.headers.get('Content-Length',0)))
        content='/objects/' in str(p) or '/packs/' in str(p)
        if content and len(data)>32*1024*1024:self.reply(413);return
        if self.path=='/control/fail-next-object':fault['next']=True;self.reply(201);return
        if self.path=='/control/transform-html':fault['transform']=True;self.reply(201);return
        if self.path=='/control/bad-range':fault['bad_range']=bool(data);self.reply(201);return
        if not p.parent.exists():self.reply(409);return
        if fault['next'] and content:
            fault['next']=False;p.write_bytes(data[:len(data)//2]);os.chmod(p,0o600);self.reply(503);return
        p.write_bytes(data);os.chmod(p,0o600);stats['puts']+=1;stats['bytes']+=len(data)
        if '/objects/' in str(p):stats['object_puts']+=1
        if '/packs/' in str(p):stats['pack_puts']+=1
        self.reply(201)
    def do_GET(self):
        p=self.resolve_path()
        if p is None:return
        if self.path=='/stats':self.reply(200,json.dumps(stats).encode());return
        if self.path=='/redirect':self.reply(307,Location='http://127.0.0.1:1/credential-sink');return
        if not p.is_file():self.reply(404);return
        data=p.read_bytes()
        if self.command=='GET':stats['get_bytes']+=len(data)
        if self.command=='GET' and fault['transform'] and data.startswith(b'<!doctype html>'):
            if self.headers.get('Range')=='bytes=0-'+str(len(data)-1):
                content_range='bytes 1-'+str(len(data)-1)+'/'+str(len(data)) if fault['bad_range'] else 'bytes 0-'+str(len(data)-1)+'/'+str(len(data))
                self.reply(206,data,**{'Content-Range':content_range});return
            self.reply(200,data+b'<script>proxy-injected-script</script>');return
        self.reply(200,data)
    do_HEAD=do_GET
    def do_PROPFIND(self):
        p=self.resolve_path()
        if p is None:return
        self.rfile.read(int(self.headers.get('Content-Length',0)))
        if not p.is_dir():self.reply(404);return
        body=['<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">']
        for f in [p,*sorted(p.iterdir())]:
            href='/'+str(f.relative_to(ROOT))+('/' if f.is_dir() else '')
            body.append('<d:response><d:href>'+xml.sax.saxutils.escape(href)+'</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>')
        body.append('</d:multistatus>');self.reply(207,''.join(body).encode(),**{'Content-Type':'application/xml'})
class Server(http.server.ThreadingHTTPServer):
    request_queue_size=64  # The fixture must accept the production uploader's 16 concurrent requests.
server=Server(('127.0.0.1',args.port),Handler)
if args.cert:
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(args.cert,args.key);server.socket=context.wrap_socket(server.socket,server_side=True)
print(json.dumps({'ready':True,'port':args.port}),flush=True)
server.serve_forever()
