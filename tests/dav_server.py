"""Isolated WebDAV fixture. Only bind loopback; no real NAS data or credentials."""
import argparse,base64,hashlib,http.server,json,pathlib,urllib.parse,xml.sax.saxutils,threading,os,ssl
parser=argparse.ArgumentParser();parser.add_argument('--root',required=True);parser.add_argument('--port',type=int,default=18791);parser.add_argument('--cert');parser.add_argument('--key');args=parser.parse_args()
ROOT=pathlib.Path(args.root).resolve();ROOT.mkdir(parents=True,exist_ok=True)
os.chmod(ROOT,0o700)
stats={'puts':0,'object_puts':0,'bytes':0,'unauthorized':0}
fault={'next':False,'transform':False,'bad_range':False}
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,status,body=b'',**headers):
        self.send_response(status);self.send_header('Content-Length',str(len(body)))
        for k,v in headers.items():self.send_header(k,v)
        self.end_headers()
        if self.command!='HEAD':self.wfile.write(body)
    def resolve_path(self):
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
        if '/objects/' in str(p) and len(data)>32*1024*1024:self.reply(413);return
        if self.path=='/control/fail-next-object':fault['next']=True;self.reply(201);return
        if self.path=='/control/transform-html':fault['transform']=True;self.reply(201);return
        if self.path=='/control/bad-range':fault['bad_range']=bool(data);self.reply(201);return
        if not p.parent.exists():self.reply(409);return
        if fault['next'] and '/objects/' in str(p):
            fault['next']=False;p.write_bytes(data[:len(data)//2]);os.chmod(p,0o600);self.reply(503);return
        p.write_bytes(data);os.chmod(p,0o600);stats['puts']+=1;stats['bytes']+=len(data)
        if '/objects/' in str(p):stats['object_puts']+=1
        self.reply(201)
    def do_GET(self):
        p=self.resolve_path()
        if p is None:return
        if self.path=='/stats':self.reply(200,json.dumps(stats).encode());return
        if self.path=='/redirect':self.reply(307,Location='http://127.0.0.1:1/credential-sink');return
        if not p.is_file():self.reply(404);return
        data=p.read_bytes()
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
