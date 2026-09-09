#!/usr/bin/env python3
"""缝合 FlorisBoard + AgentImeBridge：
   - 清单 IME 服务名 → 桥类（字符串池同索引替换重建）
   - classes.dex：清 FlorisImeService 类级 + onCreate/onDestroy/onCreateInputView 的 final 位
   - 注入 classes2.dex（桥）+ assets/agent_pinyin.txt（词典）
   - zipalign + debug 签名 → /tmp/floris_merged.apk
用法: python3 merge_floris.py <florisboard.apk> <bridge_classes2.dex> <pinyin_dict.txt> <out.apk>
"""
import struct, zipfile, hashlib, zlib, subprocess, os, sys

SRC, DEX2, DICT, OUT = sys.argv[1:5]
OLD_SVC = 'dev.patrickgold.florisboard.FlorisImeService'
NEW_SVC = 'dev.patrickgold.florisboard.agent.AgentImeBridge'
UNLOCK = ('onCreate', 'onDestroy', 'onCreateInputView')

def u32(b, o): return struct.unpack_from('<I', b, o)[0]
def u16(b, o): return struct.unpack_from('<H', b, o)[0]

def patch_manifest(data: bytes) -> bytes:
    assert u16(data, 0) == 0x0003
    off = 8
    assert u16(data, off) == 0x0001
    csize = u32(data, off + 4); scount = u32(data, off + 8)
    stylecount = u32(data, off + 12); flags = u32(data, off + 16)
    sstart = u32(data, off + 20)
    assert stylecount == 0 and not (flags & 0x100), 'need style-less UTF-16 pool'

    def uleb16(b, p):
        v = u16(b, p)
        if v & 0x8000: return ((v & 0x7FFF) << 16) | u16(b, p + 2), p + 4
        return v, p + 2

    strings = []
    for i in range(scount):
        so = u32(data, off + 28 + i * 4)
        ln, p = uleb16(data, off + sstart + so)
        strings.append(data[p:p + ln * 2].decode('utf-16-le'))
    hits = [i for i, s in enumerate(strings) if s == OLD_SVC]
    assert len(hits) == 1, f'service string hits={hits}'
    strings[hits[0]] = NEW_SVC

    header_size = 28
    str_start = header_size + scount * 4
    buf = bytearray(); offsets = []
    for s in strings:
        offsets.append(len(buf))
        enc = s.encode('utf-16-le'); ln = len(s)
        if ln > 0x7FFF: buf += struct.pack('<HH', 0x8000 | (ln >> 16), ln & 0xFFFF)
        else: buf += struct.pack('<H', ln)
        buf += enc + b'\x00\x00'
    while len(buf) % 4: buf += b'\x00'
    pool_size = str_start + len(buf)
    pool = struct.pack('<HHIIIIII', 0x0001, header_size, pool_size, scount, 0, flags, str_start, 0)
    for o in offsets: pool += struct.pack('<I', o)
    pool += bytes(buf)
    rest = data[8 + csize:]
    total = 8 + len(pool) + len(rest)
    print(f'[manifest] pool {csize}B -> {pool_size}B')
    return struct.pack('<HHI', 0x0003, 8, total) + pool + rest

def uleb(b, p):
    v = 0; sh = 0; start = p
    while True:
        bb = b[p]; p += 1
        v |= (bb & 0x7F) << sh; sh += 7
        if not (bb & 0x80): break
    return v, p, p - start

def enc_uleb(v):
    out = bytearray()
    while True:
        b = v & 0x7F; v >>= 7
        if v: out.append(b | 0x80)
        else: out.append(b); break
    return bytes(out)

def patch_dex(data: bytes) -> bytes:
    d = bytearray(data)
    soff = u32(d, 0x3C); toff = u32(d, 0x44)
    ntype = u32(d, 0x40); ndef = u32(d, 0x60); doff = u32(d, 0x64)
    moff = u32(d, 0x5C)
    def getstr(i):
        p = u32(d, soff + i * 4)
        ln, p, _ = uleb(d, p)
        return bytes(d[p:p + ln]).decode('utf-8', 'replace')
    tidx = None
    for i in range(ntype):
        if getstr(u32(d, toff + i * 4)) == 'Ldev/patrickgold/florisboard/FlorisImeService;':
            tidx = i; break
    assert tidx is not None
    co = None
    for i in range(ndef):
        c = doff + i * 32
        if u32(d, c) == tidx: co = c; break
    assert co is not None
    p = u32(d, co + 24)
    sf, p, _ = uleb(d, p); inf, p, _ = uleb(d, p); dm, p, _ = uleb(d, p); vm, p, _ = uleb(d, p)
    for _ in range(sf + inf):
        _, p, _ = uleb(d, p); _, p, _ = uleb(d, p)
    patched = []
    for count in (dm, vm):
        mid = 0  # direct/virtual 两列表的 idx_diff 各自独立累计
        for _ in range(count):
            diff, p, _ = uleb(d, p); mid += diff
            fpos = p
            flags, p, flen = uleb(d, p)
            _, p, _ = uleb(d, p)
            name = getstr(u32(d, moff + mid * 8 + 4))
            if name in UNLOCK and (flags & 0x10):
                nf = flags & ~0x10
                assert len(enc_uleb(nf)) == flen
                d[fpos:fpos + flen] = enc_uleb(nf)
                patched.append(name)
    assert sorted(patched) == sorted(UNLOCK), patched
    cflags = u32(d, co + 4)
    struct.pack_into('<I', d, co + 4, cflags & ~0x10)
    d[0x0C:0x20] = hashlib.sha1(bytes(d[32:])).digest()
    struct.pack_into('<I', d, 8, zlib.adler32(bytes(d[12:])))
    print(f'[dex] unlocked: {patched}')
    return bytes(d)

src = zipfile.ZipFile(SRC)
man = patch_manifest(src.read('AndroidManifest.xml'))
dex = patch_dex(src.read('classes.dex'))
skip = {'AndroidManifest.xml', 'classes.dex'}
out = zipfile.ZipFile('/tmp/floris_merged_unsigned.apk', 'w', zipfile.ZIP_DEFLATED)
for item in src.infolist():
    if item.filename in skip or item.filename.startswith('META-INF/') and (
            item.filename.endswith(('.SF', '.RSA', '.DSA', '.MF'))):
        continue
    out.writestr(item, src.read(item.filename))
out.writestr('AndroidManifest.xml', man)
out.writestr('classes.dex', dex)
out.writestr('classes2.dex', open(DEX2, 'rb').read())
out.writestr('assets/agent_pinyin.txt', open(DICT, 'rb').read())
out.close()

BT = subprocess.check_output(['bash', '-c',
    'find ~/Library/Android/sdk/build-tools -mindepth 1 -maxdepth 1 -type d | sort | tail -1']).decode().strip()
subprocess.run([f'{BT}/zipalign', '-f', '4', '/tmp/floris_merged_unsigned.apk',
                '/tmp/floris_merged_aligned.apk'], check=True)
subprocess.run([f'{BT}/apksigner', 'sign', '--ks', os.path.expanduser('~/.android/debug.keystore'),
                '--ks-pass', 'pass:android', '--out', OUT, '/tmp/floris_merged_aligned.apk'], check=True)
print(f'[done] {OUT}')
