"""Render the 512x512 Google Play store icon from the iOS icon source.

Run from the repo root:

    python3 tools/render-store-icon.py [output.png]

Output is 512x512 RGBA PNG, ~130 KB, well inside Play's 1 MB limit. The render
is deterministic: the same inputs produce a byte-identical file, which is why
the artifact itself is not committed.

WHAT IT DRAWS, AND WHY THAT IS NOT THE SOURCE ART

The store icon reproduces what a launcher actually shows for the shipped
adaptive icon -- the 72dp visible aperture of the 108dp canvas -- rather than
the full canvas. Play displays the store icon behind a light rounded-rect mask,
so rendering the whole canvas would show margin no user ever sees and a mark
noticeably smaller than the one on their home screen. Matching the aperture
makes the listing and the launcher agree.

The mark is therefore 58.71dp of a 72dp aperture -- 417.5px of 512 -- which is
why it reads as nearly edge to edge here while sitting comfortably inside the
66dp safe zone on device. Both facts are the same geometry seen through
different crops. See app/src/main/res/drawable/ic_launcher_foreground.xml.

INPUTS (read-only, from the sibling iOS repo)

  ../wxyc-dj-ios/WXYCDJ/AppIcon.icon/Assets/logo.svg              the wordmark
  ../wxyc-dj-ios/WXYCDJ/AppIcon.icon/Assets/GilmoreRings Snapshot.png  background

What does not port is the iOS 26 presentation: the glass, specular and
translucency specializations in icon.json are Liquid Glass and have no Android
equivalent. This takes the geometry and the artwork and leaves the lighting.

WHY A HAND-ROLLED RENDERER

Standard library only -- no Pillow, no cairosvg, no rsvg, and PyObjC/Quartz is
absent on this machine. Adding an image dependency to draw one icon once is a
worse trade than 200 lines that need no environment. It is tractable because
logo.svg is unusually plain: four paths using only M, L, C and Z, all absolute,
no gradients, no strokes, one fill, and pure scale+translate group matrices.
A general SVG renderer this is NOT -- it will silently mis-draw arcs, relative
commands, quadratics, strokes or gradients, none of which the input contains.
Check the input before reusing it on other art.

Fill is even-odd (matching the source's fill-rule) at 4x supersampling.
"""
import math
import os
import re
import struct
import sys
import zlib

SS = 4                       # supersample factor
OUT = 512                    # Play store icon edge, px
CANVAS_DP = 108.0            # adaptive icon canvas
VISIBLE_DP = 72.0            # adaptive icon mask aperture
MARK_W_DP = 58.71            # as shipped in ic_launcher_foreground.xml
INK = (60.1, 1988.9, 684.3, 1407.8)   # minx, maxx, miny, maxy, source space

HERE = os.path.dirname(os.path.abspath(__file__))


def _find_ios_assets():
    """Locate wxyc-dj-ios's icon assets.

    $WXYC_DJ_IOS wins if set. Otherwise try the sibling checkout, then the same
    sibling one level further up -- which is what makes this work from a git
    worktree under .worktrees/, where the repo is two levels below the org root
    rather than one. Worktrees are this repo's normal workflow, so a resolver
    that only handles the primary checkout would fail most of the time it ran.
    """
    env = os.environ.get('WXYC_DJ_IOS')
    roots = [env] if env else []
    roots += [os.path.join(HERE, *([os.pardir] * n), 'wxyc-dj-ios') for n in (2, 3)]
    for r in roots:
        cand = os.path.join(r, 'WXYCDJ', 'AppIcon.icon', 'Assets')
        if os.path.isdir(cand):
            return cand
    return os.path.join(HERE, os.pardir, os.pardir, 'wxyc-dj-ios',
                        'WXYCDJ', 'AppIcon.icon', 'Assets')


IOS = _find_ios_assets()
LOGO = os.path.join(IOS, 'logo.svg')
RINGS = os.path.join(IOS, 'GilmoreRings Snapshot.png')


def decode_png(path):
    """Minimal non-interlaced PNG reader. Returns (W, H, channels, rows)."""
    f = open(path, 'rb').read()
    if f[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG: %s' % path)
    pos, idat = 8, b''
    W = H = ct = 0
    while pos < len(f):
        ln, typ = struct.unpack('>I4s', f[pos:pos + 8])
        d = f[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            W, H, _bd, ct, _c, _fl, interlace = struct.unpack('>IIBBBBB', d)
            if interlace:
                raise ValueError('interlaced PNG not supported')
        elif typ == b'IDAT':
            idat += d
        elif typ == b'IEND':
            break
        pos += 12 + ln
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride = W * ch
    prev = bytearray(stride)
    rows, p = [], 0
    for _ in range(H):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ft == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 255
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        rows.append(line)
        prev = line
    return W, H, ch, rows


def encode_png(path, W, H, rgba_rows):
    """Colour type 6 (RGBA). Play's icon spec calls for a 32-bit PNG; this icon
    is opaque, so every alpha byte is 255, but the channel has to be there."""
    raw = b''.join(b'\x00' + bytes(r) for r in rgba_rows)

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    open(path, 'wb').write(
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress(raw, 9))
        + chunk(b'IEND', b''))


def flatten_cubic(p0, p1, p2, p3, out):
    """Subdivide a cubic by control-polygon length.

    The divisor is set from measurement, not taste. At `// 6 + 3` (2,165 edges)
    the output differs from a deliberately oversampled reference (19,747 edges)
    in 86 of 262,144 pixels -- 0.033%, max channel delta 31/255, mean 0.005 --
    i.e. a handful of antialiased edge samples and nothing structural. `// 2 + 4`
    is used anyway because the whole render is about a second either way, so
    there is no reason to spend the accuracy: at that setting (5,946 edges) the
    gap closes to 17 pixels, max delta 16/255.
    """
    d = math.dist(p0, p1) + math.dist(p1, p2) + math.dist(p2, p3)
    n = max(3, min(400, int(d) // 2 + 4))
    for i in range(1, n + 1):
        t = i / n
        mt = 1.0 - t
        a, b, c, e = mt * mt * mt, 3 * mt * mt * t, 3 * mt * t * t, t * t * t
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + e * p3[0],
                    a * p0[1] + b * p1[1] + c * p2[1] + e * p3[1]))


TOKEN = re.compile(r'[MLCZmlcz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?')


def parse_path(d, xf):
    """M/L/C/Z only -- all logo.svg uses. Returns transformed closed subpaths."""
    toks = TOKEN.findall(d)
    subs, cur, start, pos, i, cmd = [], [], None, (0.0, 0.0), 0, 'M'
    while i < len(toks):
        if toks[i] in 'MLCZmlcz':
            cmd = toks[i]
            i += 1
        if cmd in 'Mm':
            pos = (float(toks[i]), float(toks[i + 1])); i += 2
            if cur:
                subs.append(cur)
            start, cur, cmd = pos, [xf(pos)], 'L'
        elif cmd in 'Ll':
            pos = (float(toks[i]), float(toks[i + 1])); i += 2
            cur.append(xf(pos))
        elif cmd in 'Cc':
            p1 = (float(toks[i]), float(toks[i + 1]))
            p2 = (float(toks[i + 2]), float(toks[i + 3]))
            p3 = (float(toks[i + 4]), float(toks[i + 5])); i += 6
            pts = []
            flatten_cubic(pos, p1, p2, p3, pts)
            cur.extend(xf(q) for q in pts)
            pos = p3
        elif cmd in 'Zz':
            if cur:
                cur.append(cur[0])
                subs.append(cur)
                cur = []
            if start:
                pos = start
    if cur:
        subs.append(cur)
    return subs


def main(argv):
    out_path = argv[1] if len(argv) > 1 else 'play-store-icon-512.png'
    for f in (LOGO, RINGS):
        if not os.path.exists(f):
            sys.exit('missing input: %s\n(expects wxyc-dj-ios checked out beside this repo)' % f)

    svg = open(LOGO).read()
    pairs = re.findall(r'<g transform="matrix\(([^)]*)\)">\s*<path d="([^"]*)"', svg)
    if len(pairs) != 4:
        sys.exit('expected 4 glyph paths in logo.svg, found %d -- the art changed; '
                 're-derive INK before trusting this render' % len(pairs))

    W = OUT * SS
    minx, maxx, miny, maxy = INK
    mark_px = MARK_W_DP * (OUT / VISIBLE_DP) * SS
    s_out = mark_px / (maxx - minx)
    ox = W / 2.0 - mark_px / 2.0
    oy = W / 2.0 - (maxy - miny) * s_out / 2.0

    edges = []
    for m, d in pairs:
        a, _, _, dd, gx, gy = [float(v) for v in m.split(',')]

        def xf(p, a=a, dd=dd, gx=gx, gy=gy):
            return ((a * p[0] + gx - minx) * s_out + ox,
                    (dd * p[1] + gy - miny) * s_out + oy)

        for sub in parse_path(d, xf):
            for j in range(len(sub) - 1):
                (x0, y0), (x1, y1) = sub[j], sub[j + 1]
                if y0 != y1:
                    edges.append((min(y0, y1), max(y0, y1), x0, y0, (x1 - x0) / (y1 - y0)))
    edges.sort(key=lambda e: e[0])

    coverage = [bytearray(W) for _ in range(W)]
    ei, active = 0, []
    for y in range(W):
        yc = y + 0.5
        while ei < len(edges) and edges[ei][0] <= yc:
            active.append(edges[ei])
            ei += 1
        if active:
            active = [e for e in active if e[1] > yc]
        if not active:
            continue
        xs = sorted(e[2] + (yc - e[3]) * e[4] for e in active if e[0] <= yc < e[1])
        row = coverage[y]
        for k in range(0, len(xs) - 1, 2):          # even-odd, matching fill-rule
            x0 = max(0, int(math.ceil(xs[k] - 0.5)))
            x1 = min(W - 1, int(math.floor(xs[k + 1] - 0.5)))
            for x in range(x0, x1 + 1):
                row[x] = 255

    bw, bh, bch, brows = decode_png(RINGS)
    side = min(bw, bh)                              # centred square crop
    cx0, cy0 = (bw - side) / 2.0, (bh - side) / 2.0
    vis = side * VISIBLE_DP / CANVAS_DP             # then the mask aperture
    off = (side - vis) / 2.0
    samples = SS * SS
    out_rows = []
    for oy_ in range(OUT):
        sy = int(cy0 + off + (oy_ + 0.5) * vis / OUT)
        sy = max(0, min(bh - 1, sy))
        srow = brows[sy]
        row = bytearray()
        for ox_ in range(OUT):
            sx = int(cx0 + off + (ox_ + 0.5) * vis / OUT)
            sx = max(0, min(bw - 1, sx))
            o = sx * bch
            R, G, B = srow[o], srow[o + 1], srow[o + 2]
            acc = 0
            for dy in range(SS):
                crow = coverage[oy_ * SS + dy]
                base = ox_ * SS
                for dx in range(SS):
                    acc += crow[base + dx]
            al = acc / (255.0 * samples)
            row += bytes((int(R + (255 - R) * al),
                          int(G + (255 - G) * al),
                          int(B + (255 - B) * al), 255))
        out_rows.append(row)

    encode_png(out_path, OUT, OUT, out_rows)
    print('%s  %dx%d RGBA  %d bytes  (%d edges, mark %.1fpx of %d)'
          % (out_path, OUT, OUT, os.path.getsize(out_path), len(edges), mark_px / SS, OUT))


if __name__ == '__main__':
    main(sys.argv)
