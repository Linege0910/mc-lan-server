import sys, tarfile, io

def ar_members(path):
    data = open(path, 'rb').read()
    p = 8
    out = {}
    while p + 60 <= len(data):
        name = data[p:p+16].decode('latin1').strip()
        size = int(data[p+48:p+58].decode('latin1').strip())
        body = data[p+60:p+60+size]
        out[name] = body
        p = p + 60 + size + (size % 2)
    return out

if __name__ == '__main__':
    deb = sys.argv[1]
    want = sys.argv[2] if len(sys.argv) > 2 else None
    mem = ar_members(deb)
    print('members:', sorted(mem.keys()))
    key = [k for k in mem if k.startswith('data.tar')][0]
    print('data:', key, len(mem[key]), 'bytes')
    tf = tarfile.open(fileobj=io.BytesIO(mem[key]))
    names = tf.getnames()
    print('entries:', len(names))
    for n in names[:8]:
        print('  ', n)
    if want:
        with open(want, 'wb') as f:
            f.write(tf.extractfile('data/data/com.termux/files/usr/' + sys.argv[3]).read())
        print('extracted', sys.argv[3], '->', want)
