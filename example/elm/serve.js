// A dependency-free static server for public/ — all the example needs.
// PORT defaults to 8471, the :http-port in hive-cljs.edn.
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, 'public');
const port = Number(process.env.PORT || 8471);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };

http
  .createServer((req, res) => {
    const url = decodeURIComponent(req.url.split('?')[0]);
    const file = path.join(root, url.endsWith('/') ? url + 'index.html' : url);
    if (!file.startsWith(root)) { res.writeHead(403); return res.end(); }
    fs.readFile(file, (err, body) => {
      if (err) { res.writeHead(404); return res.end('not found'); }
      res.writeHead(200, {
        'content-type': types[path.extname(file)] || 'application/octet-stream',
        'cache-control': 'no-store'
      });
      res.end(body);
    });
  })
  .listen(port, () => console.log('serving ' + root + ' on http://localhost:' + port));
